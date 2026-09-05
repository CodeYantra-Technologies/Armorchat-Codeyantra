package com.codeyantra.armorchat.data.contact

import com.codeyantra.armorchat.core.common.AppDispatchers
import com.codeyantra.armorchat.core.database.dao.ContactDao
import com.codeyantra.armorchat.core.database.entity.ContactEntity
import com.codeyantra.armorchat.domain.identity.*
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactRepositoryTest {
    private fun payload(seed: Byte) = IdentityQrPayload.fromPublicKey(
        Ed25519PrivateKeyParameters(ByteArray(32) { seed }, 0).generatePublicKey().encoded)
    private val mine = payload(1)
    private val theirs = payload(2)
    private val qr = IdentityQrPayload.serialize(theirs)
    private class MemoryDao(var localId: String?) : ContactDao {
        val rows = MutableStateFlow<List<ContactEntity>>(emptyList())
        var race: ContactEntity? = null
        override fun observeAll() = rows
        override fun observeById(id: String) = rows.map { list -> list.find { it.id == id } }
        override suspend fun findById(id: String) = rows.value.find { it.id == id }
        override suspend fun findByArmorChatId(armorChatId: String) = rows.value.find { it.armorChatId == armorChatId }
        override suspend fun localArmorChatId() = localId
        override suspend fun insert(contact: ContactEntity): Long {
            race?.let { rows.value += it; race = null }
            if (rows.value.any { it.armorChatId == contact.armorChatId || it.id == contact.id }) return -1
            rows.value += contact
            return 1
        }
        override suspend fun rename(id: String, name: String) { rows.value = rows.value.map { if (it.id == id) it.copy(displayName = name) else it } }
    }
    private fun repository(dao: MemoryDao, dispatcher: TestDispatcher) = ContactRepositoryImpl(dao, AppDispatchers(dispatcher, dispatcher, dispatcher))

    @Test fun validContactSavedAndDuplicatePrevented() = runTest {
        val dao = MemoryDao(mine.id); val repo = repository(dao, StandardTestDispatcher(testScheduler))
        assertTrue(repo.inspectQr(qr) is ContactExchangeResult.Ready)
        val saved = repo.addQr(qr, " Amit ") as ContactExchangeResult.Saved
        assertEquals("Amit", saved.contact.displayName)
        assertTrue(repo.addQr(qr, "Other name") is ContactExchangeResult.Duplicate)
        assertEquals(1, dao.rows.value.size)
        assertEquals(saved.contact, repo.observeContacts().first().single())
    }
    @Test fun selfScanPreventedAtInspectionAndSave() = runTest {
        val dao = MemoryDao(theirs.id); val repo = repository(dao, StandardTestDispatcher(testScheduler))
        assertEquals(ContactExchangeResult.Self, repo.inspectQr(qr))
        assertEquals(ContactExchangeResult.Self, repo.addQr(qr, "Me"))
        assertTrue(dao.rows.value.isEmpty())
    }
    @Test fun invalidInputCannotBypassRepository() = runTest {
        val dao = MemoryDao(mine.id); val repo = repository(dao, StandardTestDispatcher(testScheduler))
        assertEquals(ContactExchangeResult.Invalid, repo.addQr(qr.replace(theirs.id, mine.id), "Name"))
        assertTrue(dao.rows.value.isEmpty())
    }
    @Test fun storedPinConflictNeverOverwritten() = runTest {
        val dao = MemoryDao(mine.id); val repo = repository(dao, StandardTestDispatcher(testScheduler))
        val row = ContactEntity("stable-id", theirs.id, mine.publicKeyBase64Url, "Original", 1, "QR_SCAN")
        dao.rows.value = listOf(row)
        assertEquals(ContactExchangeResult.Conflict, repo.addQr(qr, "Replacement"))
        assertEquals(row, dao.rows.value.single())
    }
    @Test fun concurrentDuplicateInsertionHandled() = runTest {
        val dao = MemoryDao(mine.id); val repo = repository(dao, StandardTestDispatcher(testScheduler))
        dao.race = ContactEntity("racing-id", theirs.id, theirs.publicKeyBase64Url, "First", 1, "QR_SCAN")
        val result = repo.addQr(qr, "Second") as ContactExchangeResult.Duplicate
        assertEquals("racing-id", result.contact.id)
        assertEquals(1, dao.rows.value.size)
    }
    @Test fun concurrentPinConflictRejected() = runTest {
        val dao = MemoryDao(mine.id); val repo = repository(dao, StandardTestDispatcher(testScheduler))
        dao.race = ContactEntity("racing-id", theirs.id, mine.publicKeyBase64Url, "First", 1, "QR_SCAN")
        assertEquals(ContactExchangeResult.Conflict, repo.addQr(qr, "Second"))
        assertEquals(mine.publicKeyBase64Url, dao.rows.value.single().publicIdentityKey)
    }
    @Test fun localNameChangeDoesNotChangeIdentityOrPrimaryKey() = runTest {
        val dao = MemoryDao(mine.id); val repo = repository(dao, StandardTestDispatcher(testScheduler))
        val saved = (repo.addQr(qr, "First") as ContactExchangeResult.Saved).contact
        repo.rename(saved.id, "New local label")
        assertEquals(saved.copy(displayName = "New local label"), repo.observeContacts().first().single())
    }
    @Test fun identityRequired() = runTest {
        val dao = MemoryDao(null); val repo = repository(dao, StandardTestDispatcher(testScheduler))
        assertEquals(ContactExchangeResult.NoLocalIdentity, repo.addQr(qr, "Name"))
        assertTrue(dao.rows.value.isEmpty())
    }
}
