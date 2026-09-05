package com.codeyantra.armorchat.data.conversation

import android.content.Context
import androidx.room.Room
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.common.AppDispatchers
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.CONTACT_MIGRATION_2_3
import com.codeyantra.armorchat.core.database.CONVERSATION_MIGRATION_3_4
import com.codeyantra.armorchat.core.database.entity.ContactEntity
import com.codeyantra.armorchat.core.network.NoOpMessageTransport
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.VerificationMethod
import com.codeyantra.armorchat.domain.repository.SendTextResult
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConversationRepositoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val dispatchers = AppDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Main)
    private val clock = AppClock { 1_700_000_000_000L }

    private fun open(name: String) = Room.databaseBuilder(context, ArmorChatDatabase::class.java, name)
        .addMigrations(CONTACT_MIGRATION_2_3, CONVERSATION_MIGRATION_3_4)
        .build()

    private fun contact(id: String, name: String) = Contact(
        id = id,
        armorChatId = "ac1:TEST${id.take(28).padEnd(28, '0')}",
        publicIdentityKey = "A".repeat(43),
        displayName = name,
        createdAtEpochMs = 1,
        verificationMethod = VerificationMethod.QR_SCAN,
    )

    @Test
    fun sameContactReusesSingleConversation() = runBlocking {
        val name = "convo-${UUID.randomUUID()}.db"
        val db = open(name)
        try {
            val repo = ConversationRepositoryImpl(db.conversationDao(), clock, dispatchers)
            val c = contact("contact-a", "Amit")
            val first = repo.getOrCreateForContact(c)
            val second = repo.getOrCreateForContact(c)
            assertEquals(first.id, second.id)
            assertEquals(1, repo.observeConversations().first().size)
            assertEquals(c.id, first.contactId)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun differentContactsGetDifferentConversations() = runBlocking {
        val name = "convo-${UUID.randomUUID()}.db"
        val db = open(name)
        try {
            val repo = ConversationRepositoryImpl(db.conversationDao(), clock, dispatchers)
            val a = repo.getOrCreateForContact(contact("c1", "A"))
            val b = repo.getOrCreateForContact(contact("c2", "B"))
            assertNotEquals(a.id, b.id)
            assertEquals(2, repo.observeConversations().first().size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun conversationSurvivesRestartWithZeroMessages() = runBlocking {
        val name = "convo-${UUID.randomUUID()}.db"
        var db = open(name)
        val contactId = "contact-persist"
        val conversationId: String
        try {
            val repo = ConversationRepositoryImpl(db.conversationDao(), clock, dispatchers)
            conversationId = repo.getOrCreateForContact(contact(contactId, "Persisted")).id
            assertTrue(db.messageDao().observeByConversation(conversationId).first().isEmpty())
            db.close()
            db = open(name)
            val reopened = ConversationRepositoryImpl(db.conversationDao(), clock, dispatchers)
            val again = reopened.getOrCreateForContact(contact(contactId, "Persisted"))
            assertEquals(conversationId, again.id)
            assertEquals(1, reopened.observeConversations().first().size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun sendTextNeverFakesSuccessfulDelivery() = runBlocking {
        val name = "convo-${UUID.randomUUID()}.db"
        val db = open(name)
        try {
            val conversations = ConversationRepositoryImpl(db.conversationDao(), clock, dispatchers)
            val messages = MessageRepositoryImpl(
                db.messageDao(),
                db.conversationDao(),
                NoOpMessageTransport(),
                dispatchers,
            )
            val conversation = conversations.getOrCreateForContact(contact("c-send", "Send"))
            val result = messages.sendText(conversation.id, "hello")
            assertEquals(SendTextResult.TransportUnavailable, result)
            assertTrue(messages.observeMessages(conversation.id).first().isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun productionListsHaveNoSampleMessages() = runBlocking {
        val name = "convo-${UUID.randomUUID()}.db"
        val db = open(name)
        try {
            db.contactDao().insert(
                ContactEntity("c1", "ac1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "B".repeat(43), "A", 1, "QR_SCAN"),
            )
            val conversations = ConversationRepositoryImpl(db.conversationDao(), clock, dispatchers)
            assertTrue(conversations.observeConversations().first().isEmpty())
            assertTrue(db.messageDao().observeByConversation("missing").first().isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
