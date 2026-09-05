package com.codeyantra.armorchat.feature.contacts

import com.codeyantra.armorchat.domain.identity.IdentityQrPayload
import com.codeyantra.armorchat.domain.model.*
import com.codeyantra.armorchat.domain.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class Repository : ContactRepository {
        var inspections = 0
        var saves = 0
        var fail = false
        val payload = IdentityQrPayload("ac1:test-only", "public-test-only")
        override fun observeContacts() = MutableStateFlow<List<Contact>>(emptyList())
        override suspend fun inspectQr(raw: String): ContactExchangeResult {
            inspections++
            if (fail) error("Sensitive internal failure details")
            return ContactExchangeResult.Ready(payload)
        }
        override suspend fun addQr(raw: String, localName: String): ContactExchangeResult {
            saves++
            return ContactExchangeResult.Saved(Contact("uuid", payload.id, payload.publicKeyBase64Url, localName, 1))
        }
        override suspend fun rename(id: String, localName: String) = Unit
    }

    @Test fun duplicateFrameAndDoubleSaveAreCoalesced() = runTest(dispatcher) {
        val repository = Repository(); val vm = ContactsViewModel(repository)
        vm.scan("public QR"); vm.scan("duplicate frame")
        assertTrue(vm.state.value.busy)
        advanceUntilIdle()
        assertEquals(1, repository.inspections)
        assertTrue(vm.state.value.result is ContactExchangeResult.Ready)
        vm.save("Amit"); vm.save("Amit")
        advanceUntilIdle()
        assertEquals(1, repository.saves)
        assertTrue(vm.state.value.result is ContactExchangeResult.Saved)
        assertFalse(vm.state.value.busy)
    }

    @Test fun resetDropsPendingPayloadAndPreventsSave() = runTest(dispatcher) {
        val repository = Repository(); val vm = ContactsViewModel(repository)
        vm.scan("public QR"); advanceUntilIdle()
        vm.reset(); vm.save("Name"); advanceUntilIdle()
        assertEquals(0, repository.saves)
        assertEquals(ExchangeState(), vm.state.value)
    }

    @Test fun storageFailureHasRecoverableNonSensitiveError() = runTest(dispatcher) {
        val repository = Repository().apply { fail = true }; val vm = ContactsViewModel(repository)
        vm.scan("public QR"); advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.error!!.contains("Sensitive"))
        repository.fail = false
        vm.scan("public QR"); advanceUntilIdle()
        assertTrue(vm.state.value.result is ContactExchangeResult.Ready)
        assertNull(vm.state.value.error)
    }
}
