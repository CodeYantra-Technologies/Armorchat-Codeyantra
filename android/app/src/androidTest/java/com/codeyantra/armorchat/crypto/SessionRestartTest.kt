package com.codeyantra.armorchat.crypto

import android.os.Process
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/** Run via scripts/test-session-restart.sh: separate instrumentation processes,
 * explicit force-stop, plus process death inside each transaction boundary.
 * No assumption that JUnit method ordering simulates a process restart.
 * These tests require a real Android device or configured emulator; host JVM
 * execution is not evidence that Keystore wrapping or restart persistence works.
 */
@RunWith(AndroidJUnit4::class)
class SessionRestartTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "session-restart-test"
    private val checkpoint = File(context.noBackupFilesDir, "session-restart-checkpoint.json")
    private fun open() = SessionStorage.open(context, name)
    private fun req(op:String) = JSONObject().put("op",op)
    private fun b64(b:ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun <T> withSessionDb(block: (SessionDatabase) -> T): T {
        val db = Room.databaseBuilder(
            context,
            SessionDatabase::class.java,
            File(context.noBackupFilesDir, "$name.db").absolutePath,
        ).build()
        try {
            return block(db)
        } finally {
            db.close()
        }
    }
    private fun revision(): Long = withSessionDb { it.records().state("a")!!.revision }
    private fun hasCrashResult(): Boolean = withSessionDb { it.records().result("a", "crash-send") != null }

    @Test fun prepareRestart() {
        // Dedicated test DB only; never delete identity or the shared wrapping key.
        context.deleteDatabase(File(context.noBackupFilesDir,"$name.db").absolutePath)
        SignatureConfig.register()
        open().use { store ->
            fun peer(id:String): Pair<String,String> {
                store.execute(id,"create",req("Create").toString())
                val identity=KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
                val pin=b64((identity.publicKeysetHandle.primary.key as Ed25519PublicKey).publicKeyBytes.toByteArray())
                val body=store.execute(id,"body",req("DeviceBody").put("identity",pin).put("device_id",b64(ByteArray(16){id[0].code.toByte()})).put("generation",1).put("now",1000).toString())
                val signature=RawIdentitySignature.sign(identity,Base64.getUrlDecoder().decode(body.getString("signing_bytes")))
                val credential=JSONObject().put("body",body.getJSONObject("body")).put("signature",b64(signature)).toString()
                store.execute(id,"bind",req("Bind").put("credential",credential).put("pin",pin).put("now",1000).toString())
                return credential to pin
            }
            val a=peer("a"); val b=peer("b")
            val pre=store.execute("b","pre",req("Prekey").put("now",1000).toString()).getString("prekey")
            store.execute("a","out",req("Outbound").put("credential",b.first).put("prekey",pre).put("pin",b.second).put("now",1000).toString())
            val packet=store.execute("a","hello",req("Encrypt").put("plaintext","Hello ArmorChat").toString()).getJSONObject("packet")
            assertEquals("Hello ArmorChat",store.execute("b","in",req("Inbound").put("credential",a.first).put("pin",a.second).put("now",1000).put("packet",packet).toString()).getString("plaintext"))
            val reply=store.execute("b","reply",req("Encrypt").put("plaintext","Hello Alice").toString()).getJSONObject("packet")
            val wrapping=SessionSealing.wrappingKey(false)
            assertNull(wrapping.encoded)
            val probe=SessionSealing.seal(wrapping,"key survived".toByteArray(),"restart-test")
            checkpoint.writeText(JSONObject().put("pid",Process.myPid()).put("probe",b64(probe)).put("reply",reply).toString())
        }
        val data=JSONObject(checkpoint.readText()).put("revision",revision())
        checkpoint.writeText(data.toString())
    }
    @Test fun resumeAfterRestart() {
        val saved=JSONObject(checkpoint.readText())
        assertNotEquals("Must run in a new process",saved.getInt("pid"),Process.myPid())
        assertEquals("key survived",String(SessionSealing.open(SessionSealing.wrappingKey(false),Base64.getUrlDecoder().decode(saved.getString("probe")),"restart-test")))
        open().use { store ->
            val reply=store.execute("b","reply",req("Encrypt").put("plaintext","Hello Alice").toString()).getJSONObject("packet")
            assertEquals(saved.getJSONObject("reply").toString(),reply.toString())
            assertEquals("Hello Alice",store.execute("a","reply-in",req("Decrypt").put("packet",reply).toString()).getString("plaintext"))
            val packet=store.execute("a","next",req("Encrypt").put("plaintext","After restart").toString()).getJSONObject("packet")
            assertEquals(1,packet.getInt("message_type"))
            assertEquals("After restart",store.execute("b","next-in",req("Decrypt").put("packet",packet).toString()).getString("plaintext"))
            assertThrows(SecurityException::class.java) { store.execute("b","replay",req("Decrypt").put("packet",packet).toString()) }
        }
    }
    @Test fun crashAtCommitBoundary() {
        val point=InstrumentationRegistry.getArguments().getString("crashPoint")!!
        require(point in listOf("after_state","after_result","after_commit"))
        open().use { store ->
            store.fault = { if (it == point) {
                val marker = JSONObject(checkpoint.readText()).put("crashPoint", point).put("crashPid", Process.myPid())
                java.io.FileOutputStream(checkpoint).use { stream ->
                    stream.write(marker.toString().toByteArray()); stream.fd.sync()
                }
                Process.killProcess(Process.myPid())
            } }
            store.execute("a","crash-send",req("Encrypt").put("plaintext","Atomic message").toString())
            fail("Expected actual process death")
        }
    }
    @Test fun resumeAfterCrash() {
        val point=InstrumentationRegistry.getArguments().getString("crashPoint")!!
        val saved=JSONObject(checkpoint.readText())
        assertEquals(point,saved.getString("crashPoint"))
        assertNotEquals(saved.getInt("crashPid"),Process.myPid())
        val committed=point=="after_commit"
        assertEquals(saved.getLong("revision") + if (committed) 1 else 0,revision())
        assertEquals(committed,hasCrashResult())
        open().use { store ->
            val request=req("Encrypt").put("plaintext","Atomic message").toString()
            val result=store.execute("a","crash-send",request)
            val again=store.execute("a","crash-send",request)
            assertEquals(result.toString(),again.toString())
            assertEquals("Atomic message",store.execute("b","crash-in",req("Decrypt").put("packet",result.getJSONObject("packet")).toString()).getString("plaintext"))
        }
        assertEquals(saved.getLong("revision")+1,revision())
    }
    @Test fun rollbackOnExceptionAndWrongOperationReuse() {
        prepareRestart()
        val before=revision()
        open().use { store ->
            for (point in listOf("after_state","after_result")) {
                store.fault={ if(it==point) throw IllegalStateException("Injected storage failure") }
                assertThrows(IllegalStateException::class.java) { store.execute("a","crash-send",req("Encrypt").put("plaintext","Atomic message").toString()) }
                assertEquals(before,revision()); assertFalse(hasCrashResult())
            }
            store.fault={}
            store.execute("a","crash-send",req("Encrypt").put("plaintext","Atomic message").toString())
            assertThrows(IllegalStateException::class.java) { store.execute("a","crash-send",req("Encrypt").put("plaintext","Different").toString()) }
        }
    }
}
