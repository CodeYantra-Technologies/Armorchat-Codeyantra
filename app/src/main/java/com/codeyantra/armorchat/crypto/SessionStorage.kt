package com.codeyantra.armorchat.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.*
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Callable
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Entity(tableName = "session_states")
data class StoredSession(@PrimaryKey val id: String, val revision: Long, val sealed: ByteArray)
@Entity(tableName = "session_results", primaryKeys = ["account", "operation"])
data class StoredResult(val account: String, val operation: String, val sealed: ByteArray)
@Entity(tableName = "storage_key")
data class WrappedStorageKey(@PrimaryKey val id: Int = 1, val sealed: ByteArray)
@Dao
interface SessionDao {
    @Query("SELECT * FROM session_states WHERE id = :id") fun state(id: String): StoredSession?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun state(row: StoredSession)
    @Query("SELECT * FROM session_results WHERE account = :account AND operation = :operation")
    fun result(account: String, operation: String): StoredResult?
    @Insert fun result(row: StoredResult)
    @Query("SELECT * FROM storage_key WHERE id = 1") fun key(): WrappedStorageKey?
    @Insert fun key(row: WrappedStorageKey)
}
@Database(entities = [StoredSession::class, StoredResult::class, WrappedStorageKey::class], version = 1, exportSchema = true)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun records(): SessionDao
}

/** Established AES-GCM storage encryption, independent from all identity key material. */
internal object SessionSealing {
    const val ALIAS = "armorchat_session_storage_wrap_v1"
    fun wrappingKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Session wrapping key missing; recovery must not reset sessions" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun seal(key: SecretKey, bytes: ByteArray, aad: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        return cipher.iv + cipher.doFinal(bytes)
    }
    fun open(key: SecretKey, bytes: ByteArray, aad: String): ByteArray {
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }
}

/** Blocking local feasibility store: use from a background worker.
 * Room's transaction serializes read -> native staging -> state + exact result.
 * No native mutable state survives an invocation. No plaintext enters SQLite/WAL.
 * Operation IDs make retries return the original committed ciphertext, never re-encrypt.
 */
class SessionStorage private constructor(private val db: SessionDatabase, private val key: SecretKey) : AutoCloseable {
    internal var fault: (String) -> Unit = {}
    fun execute(account: String, operation: String, request: String): JSONObject {
        require(account.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(operation.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val digest = MessageDigest.getInstance("SHA-256").digest(request.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val aad = "ArmorChat/result/v1/$account/$operation"
        val result = db.runInTransaction(Callable {
            val dao = db.records()
            dao.result(account, operation)?.let {
                val bytes = SessionSealing.open(key, it.sealed, aad)
                try {
                    val saved = JSONObject(String(bytes, Charsets.UTF_8))
                    check(saved.getString("request_digest") == digest) { "Operation ID reused with different request" }
                    return@Callable saved.getJSONObject("output")
                } finally { bytes.fill(0) }
            }
            val old = dao.state(account)
            val state = old?.let { SessionSealing.open(key, it.sealed, "ArmorChat/state/v1/$account/${it.revision}") } ?: byteArrayOf()
            val response = try { VodozemacFeasibility.transact(state, request) } finally { state.fill(0) }
            try {
                val staged = JSONObject(String(response, Charsets.UTF_8))
                val revision = Math.addExact(old?.revision ?: 0, 1)
                val newState = staged.getString("state").toByteArray(Charsets.UTF_8)
                val sealed = try { SessionSealing.seal(key, newState, "ArmorChat/state/v1/$account/$revision") } finally { newState.fill(0) }
                dao.state(StoredSession(account, revision, sealed))
                fault("after_state")
                val output = staged.getJSONObject("output")
                val saved = JSONObject().put("request_digest", digest).put("output", output).toString().toByteArray()
                try { dao.result(StoredResult(account, operation, SessionSealing.seal(key, saved, aad))) } finally { saved.fill(0) }
                fault("after_result")
                output
            } finally { response.fill(0) }
        })
        fault("after_commit")
        return result
    }
    override fun close() = db.close()
    companion object {
        private const val WRAP_AAD = "ArmorChat/session-storage-key/v1"
        // Synchronizes first initialization across store instances in this single-process harness.
        @Synchronized fun open(context: Context, name: String = "session-feasibility"): SessionStorage {
            require(name.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            val file = File(context.noBackupFilesDir, "$name.db")
            val fresh = !file.exists()
            val db = Room.databaseBuilder(context, SessionDatabase::class.java, file.absolutePath).build()
            try {
                val raw = db.runInTransaction(Callable {
                    val existing = db.records().key()
                    if (existing != null) SessionSealing.open(SessionSealing.wrappingKey(false), existing.sealed, WRAP_AAD)
                    else {
                        check(fresh) { "Wrapped storage key missing; refusing to reset" }
                        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        try {
                            db.records().key(WrappedStorageKey(sealed = SessionSealing.seal(SessionSealing.wrappingKey(true), bytes, WRAP_AAD)))
                            bytes.copyOf()
                        } finally { bytes.fill(0) }
                    }
                })
                try { check(raw.size == 32); return SessionStorage(db, SecretKeySpec(raw, "AES")) } finally { raw.fill(0) }
            } catch (e: Exception) { db.close(); throw e }
        }
    }
}
