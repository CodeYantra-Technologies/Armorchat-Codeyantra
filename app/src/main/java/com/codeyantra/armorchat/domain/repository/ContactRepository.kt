package com.codeyantra.armorchat.domain.repository

import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun observeContacts(): Flow<List<Contact>>
    fun observeContact(id: String): Flow<Contact?>
    suspend fun getContact(id: String): Contact?
    suspend fun findByPublicKey(publicIdentityKey: String): Contact?
    suspend fun inspectQr(raw: String): ContactExchangeResult
    suspend fun addQr(raw: String, localName: String): ContactExchangeResult
    suspend fun addVerifiedBundle(
        parsed: com.codeyantra.armorchat.domain.identity.ParsedContactQr,
        localName: String = "",
    ): ContactExchangeResult
    suspend fun rename(id: String, localName: String)
    suspend fun deleteContact(id: String) {}
    suspend fun addInboundPinned(
        publicIdentityKey: String,
        displayName: String = "",
        handle: String? = null,
    ): Contact
}
