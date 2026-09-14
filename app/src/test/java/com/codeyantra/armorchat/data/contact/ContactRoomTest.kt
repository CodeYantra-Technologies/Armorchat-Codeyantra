package com.codeyantra.armorchat.data.contact

import android.content.Context
import androidx.room.Room
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.codeyantra.armorchat.core.common.AppDispatchers
import com.codeyantra.armorchat.core.database.*
import com.codeyantra.armorchat.core.database.entity.*
import com.codeyantra.armorchat.domain.identity.*
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ContactRoomTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dispatchers = AppDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Main)
    private fun payload(seed: Byte) = IdentityQrPayload.fromPublicKey(
        Ed25519PrivateKeyParameters(ByteArray(32) { seed }, 0).generatePublicKey().encoded)
    private fun open(name: String) = Room.databaseBuilder(context, ArmorChatDatabase::class.java, name)
        .addMigrations(*ARMORCHAT_MIGRATIONS).build()

    @Test fun persistedPinSurvivesDatabaseCloseAndReopen() = runBlocking {
        val name = "contact-test-${UUID.randomUUID()}.db"
        var db = open(name)
        try {
            val mine = payload(1); val theirs = payload(2)
            db.identityDao().upsert(IdentityEntity(mine.id, mine.publicKeyBase64Url, 1, 1))
            val repo = ContactRepositoryImpl(db.contactDao(), dispatchers)
            val qr = IdentityQrPayload.serialize(theirs)
            val saved = (repo.addQr(qr, "Amit") as ContactExchangeResult.Saved).contact
            db.close()
            db = open(name)
            val reopened = ContactRepositoryImpl(db.contactDao(), dispatchers)
            assertEquals(saved, reopened.observeContacts().first().single())
            assertTrue(reopened.addQr(qr, "Duplicate") is ContactExchangeResult.Duplicate)
            val row = db.contactDao().findByArmorChatId(theirs.id)!!
            assertEquals(-1L, db.contactDao().insert(row.copy(id = UUID.randomUUID().toString())))
            assertEquals(-1L, db.contactDao().insert(row.copy(id = UUID.randomUUID().toString(), publicIdentityKey = mine.publicKeyBase64Url)))
            reopened.rename(saved.id, "Local rename")
            assertEquals(saved.copy(displayName = "Local rename"), reopened.observeContacts().first().single())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun migrationPreservesIdentityAndQuarantinesUnverifiablePlaceholders() = runBlocking {
        val name = "migration-test-${UUID.randomUUID()}.db"
        val mine = payload(1)
        // Create the exact v2 tables, then let Room validate the migrated v3 schema.
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE identities (armorChatId TEXT NOT NULL PRIMARY KEY, publicIdentityKey TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, version INTEGER NOT NULL)")
            old.execSQL("INSERT INTO identities VALUES (?, ?, 7, 1)", arrayOf(mine.id, mine.publicKeyBase64Url))
            old.execSQL("CREATE TABLE contacts (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL)")
            old.execSQL("INSERT INTO contacts VALUES ('legacy-id', 'Preserved label', 9)")
            old.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT, updatedAtEpochMs INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, ciphertext BLOB, status TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
            old.version = 2
        }
        val db = open(name)
        try {
            assertEquals(mine.id, db.identityDao().get()!!.armorChatId)
            assertEquals(mine.publicKeyBase64Url, db.identityDao().get()!!.publicIdentityKey)
            assertTrue(db.contactDao().observeAll().first().isEmpty())
            db.openHelper.readableDatabase.query("SELECT displayName FROM legacy_contacts WHERE id = 'legacy-id'").use {
                assertTrue(it.moveToFirst()); assertEquals("Preserved label", it.getString(0))
            }
            val qr = IdentityQrPayload.serialize(payload(2))
            assertTrue(ContactRepositoryImpl(db.contactDao(), dispatchers).addQr(qr, "New") is ContactExchangeResult.Saved)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
