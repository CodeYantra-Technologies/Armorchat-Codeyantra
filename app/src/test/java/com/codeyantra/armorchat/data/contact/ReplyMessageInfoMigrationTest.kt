package com.codeyantra.armorchat.data.contact

import android.content.Context
import androidx.room.Room
import com.codeyantra.armorchat.core.database.ARMORCHAT_MIGRATIONS
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.entity.LocalInviteEntity
import com.codeyantra.armorchat.messaging.SessionOrchestrator
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ReplyMessageInfoMigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun migrationFrom10To12PreservesIdentityInviteSessionAndMls() = runBlocking {
        assertPreservedAfterUpgrade(fromVersion = 10, includeReplyColumn = false)
    }

    @Test
    fun migrationFrom11To12PreservesIdentityInviteSessionAndMls() = runBlocking {
        assertPreservedAfterUpgrade(fromVersion = 11, includeReplyColumn = true)
    }

    @Test
    fun migrationFrom9To12PreservesIdentityInviteSessionAndMls() = runBlocking {
        assertPreservedAfterUpgrade(fromVersion = 9, includeReplyColumn = false, includeReceiptColumns = false)
    }

    private suspend fun assertPreservedAfterUpgrade(
        fromVersion: Int,
        includeReplyColumn: Boolean,
        includeReceiptColumns: Boolean = true,
    ) {
        val name = "mig-${fromVersion}-12-${UUID.randomUUID()}.db"
        val identityId = "ac1:TESTIDENTITY0000000000000000"
        val publicKey = "A".repeat(43)
        val inviteToken = "B".repeat(43)
        val inviteId = "invite-id-1"
        val mailboxId = "mailbox-1"
        val encryptedOwner = byteArrayOf(9, 8, 7, 6, 5)
        val encryptedSubmit = byteArrayOf(4, 3, 2, 1)
        val mlsSnapshot = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val contactId = "contact-1"

        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            createV9PlusSchema(
                db = old,
                includeReceiptColumns = includeReceiptColumns,
                includeReplyColumn = includeReplyColumn,
            )
            old.execSQL(
                "INSERT INTO identities VALUES (?, ?, 11, 1, 'Neeraj', 'neeraj')",
                arrayOf(identityId, publicKey),
            )
            old.execSQL(
                "INSERT INTO contacts VALUES (?, ?, ?, 'Peer', 12, 'QR_SCAN', 'peer', NULL, NULL)",
                arrayOf(contactId, "ac1:PEER00000000000000000000000000", "C".repeat(43)),
            )
            old.execSQL(
                "INSERT INTO conversations VALUES ('conv-1', ?, 'Peer', 13)",
                arrayOf(contactId),
            )
            val messageColumns = buildString {
                append("id, conversationId, type, isOutgoing, localPlaintext, ciphertext, status, createdAtEpochMs")
                if (includeReceiptColumns) append(", receiptSessionId, receiptCiphertextSha256, receiptEpoch")
                if (includeReplyColumn) append(", replyToJson")
            }
            val messageValues = buildString {
                append("'msg-1', 'conv-1', 'TEXT', 1, 'hello', NULL, 'SENT', 14")
                if (includeReceiptColumns) append(", 'sess-1', 'digest-1', 2")
                if (includeReplyColumn) append(", '{\"v\":1}'")
            }
            old.execSQL("INSERT INTO messages ($messageColumns) VALUES ($messageValues)")
            old.execSQL(
                """
                INSERT INTO contact_sessions (
                    contactId, peerPin, relayOrigin, mailboxId, encryptedOwnerToken, encryptedSubmitCapability,
                    deviceId, binding, localRouteQr, peerRouteQr, mlsSnapshot, groupCreated, state, updatedAtEpochMs, mlsSessionId
                ) VALUES (?, ?, 'https://relay1.codeyantra.in', ?, ?, ?, 'device-1', 'binding-1', NULL, NULL, ?, 1, 'ESTABLISHED', 15, 'mls-sess-1')
                """.trimIndent(),
                arrayOf(
                    SessionOrchestrator.INVITE_PEER,
                    SessionOrchestrator.INVITE_PEER,
                    mailboxId,
                    encryptedOwner,
                    encryptedSubmit,
                    mlsSnapshot,
                ),
            )
            old.execSQL(
                """
                INSERT INTO contact_sessions (
                    contactId, peerPin, relayOrigin, mailboxId, encryptedOwnerToken, encryptedSubmitCapability,
                    deviceId, binding, localRouteQr, peerRouteQr, mlsSnapshot, groupCreated, state, updatedAtEpochMs, mlsSessionId
                ) VALUES (?, ?, 'https://relay1.codeyantra.in', ?, ?, ?, 'device-2', 'binding-2', 'local-qr', 'peer-qr', ?, 1, 'ESTABLISHED', 16, 'mls-sess-2')
                """.trimIndent(),
                arrayOf(contactId, "C".repeat(43), mailboxId, encryptedOwner, encryptedSubmit, mlsSnapshot),
            )
            old.execSQL(
                "INSERT INTO local_invites (id, token, expiresAtEpochSeconds, inviteId, updatedAtEpochMs, state) VALUES ('v3', ?, 99, ?, 17, 'ACTIVE')",
                arrayOf(inviteToken, inviteId),
            )
            old.version = fromVersion
        }

        val db = Room.databaseBuilder(context, ArmorChatDatabase::class.java, name)
            .addMigrations(*ARMORCHAT_MIGRATIONS)
            .build()
        try {
            val identity = db.identityDao().get()!!
            assertEquals(identityId, identity.armorChatId)
            assertEquals(publicKey, identity.publicIdentityKey)
            assertEquals("Neeraj", identity.displayName)
            assertEquals("neeraj", identity.handle)

            val invite = db.localInviteDao().get()!!
            assertEquals(LocalInviteEntity.ROW_ID, invite.id)
            assertEquals(inviteToken, invite.token)
            assertEquals(99L, invite.expiresAtEpochSeconds)
            assertEquals(inviteId, invite.inviteId)
            assertEquals("ACTIVE", invite.state)

            val inviteSession = db.contactSessionDao().find(SessionOrchestrator.INVITE_PEER)!!
            assertEquals(mailboxId, inviteSession.mailboxId)
            assertArrayEquals(encryptedOwner, inviteSession.encryptedOwnerToken)
            assertArrayEquals(encryptedSubmit, inviteSession.encryptedSubmitCapability)
            assertArrayEquals(mlsSnapshot, inviteSession.mlsSnapshot)
            assertEquals("mls-sess-1", inviteSession.mlsSessionId)
            assertTrue(inviteSession.groupCreated)

            val contactSession = db.contactSessionDao().find(contactId)!!
            assertEquals(mailboxId, contactSession.mailboxId)
            assertArrayEquals(encryptedOwner, contactSession.encryptedOwnerToken)
            assertArrayEquals(mlsSnapshot, contactSession.mlsSnapshot)
            assertEquals("ESTABLISHED", contactSession.state)

            val message = db.messageDao().findById("msg-1")!!
            assertEquals("hello", message.localPlaintext)
            assertEquals("SENT", message.status)
            if (includeReplyColumn) {
                assertEquals("{\"v\":1}", message.replyToJson)
            } else {
                assertNull(message.replyToJson)
            }
            assertNull(message.deliveredAtEpochMs)
            assertNull(message.readAtEpochMs)
            db.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(12, cursor.getInt(0))
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun createV9PlusSchema(
        db: android.database.sqlite.SQLiteDatabase,
        includeReceiptColumns: Boolean,
        includeReplyColumn: Boolean,
    ) {
        db.execSQL(
            """
            CREATE TABLE identities (
                armorChatId TEXT NOT NULL PRIMARY KEY,
                publicIdentityKey TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                version INTEGER NOT NULL,
                displayName TEXT NOT NULL,
                handle TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE contacts (
                id TEXT NOT NULL PRIMARY KEY,
                armorChatId TEXT NOT NULL,
                publicIdentityKey TEXT NOT NULL,
                displayName TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                verificationMethod TEXT NOT NULL,
                handle TEXT,
                previousHandle TEXT,
                handleChangedAtEpochMs INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX index_contacts_armorChatId ON contacts (armorChatId)")
        db.execSQL(
            """
            CREATE TABLE conversations (
                id TEXT NOT NULL PRIMARY KEY,
                contactId TEXT NOT NULL,
                title TEXT,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX index_conversations_contactId ON conversations (contactId)")
        val extraMessageCols = buildString {
            if (includeReceiptColumns) {
                append(", receiptSessionId TEXT, receiptCiphertextSha256 TEXT, receiptEpoch INTEGER")
            }
            if (includeReplyColumn) append(", replyToJson TEXT")
        }
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT NOT NULL PRIMARY KEY,
                conversationId TEXT NOT NULL,
                type TEXT NOT NULL,
                isOutgoing INTEGER NOT NULL,
                localPlaintext TEXT,
                ciphertext BLOB,
                status TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL
                $extraMessageCols,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_messages_conversationId ON messages (conversationId)")
        db.execSQL(
            """
            CREATE TABLE contact_sessions (
                contactId TEXT NOT NULL PRIMARY KEY,
                peerPin TEXT NOT NULL,
                relayOrigin TEXT NOT NULL,
                mailboxId TEXT,
                encryptedOwnerToken BLOB,
                encryptedSubmitCapability BLOB,
                deviceId TEXT,
                binding TEXT,
                localRouteQr TEXT,
                peerRouteQr TEXT,
                mlsSnapshot BLOB,
                groupCreated INTEGER NOT NULL,
                state TEXT NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                mlsSessionId TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE local_invites (
                id TEXT NOT NULL PRIMARY KEY,
                token TEXT NOT NULL,
                expiresAtEpochSeconds INTEGER NOT NULL,
                inviteId TEXT NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'ACTIVE'
            )
            """.trimIndent(),
        )
    }
}
