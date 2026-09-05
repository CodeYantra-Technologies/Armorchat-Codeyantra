package com.codeyantra.armorchat.data.contact

import com.codeyantra.armorchat.core.common.AppDispatchers
import com.codeyantra.armorchat.core.database.dao.ContactDao
import com.codeyantra.armorchat.core.database.entity.ContactEntity
import com.codeyantra.armorchat.domain.identity.IdentityQrPayload
import com.codeyantra.armorchat.domain.model.*
import com.codeyantra.armorchat.domain.repository.ContactRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val contactDao: ContactDao,
    private val dispatchers: AppDispatchers,
) : ContactRepository {
    override fun observeContacts() = contactDao.observeAll().map { rows -> rows.map { it.domain() } }

    override suspend fun inspectQr(raw: String): ContactExchangeResult = withContext(dispatchers.io) {
        val payload = IdentityQrPayload.parse(raw).getOrNull() ?: return@withContext ContactExchangeResult.Invalid
        val localId = contactDao.localArmorChatId() ?: return@withContext ContactExchangeResult.NoLocalIdentity
        if (payload.id == localId) return@withContext ContactExchangeResult.Self
        val existing = contactDao.findByArmorChatId(payload.id)
        if (existing != null) existing.resultFor(payload) else ContactExchangeResult.Ready(payload)
    }

    override suspend fun addQr(raw: String, localName: String): ContactExchangeResult = withContext(dispatchers.io) {
        require(localName.length <= 100) { "Local name is too long" }
        // Revalidate at persistence boundary; the UI cannot supply a trusted ID/key pair.
        val check = inspectQr(raw)
        if (check !is ContactExchangeResult.Ready) return@withContext check
        val row = ContactEntity(UUID.randomUUID().toString(), check.payload.id,
            check.payload.publicKeyBase64Url, localName.trim(), System.currentTimeMillis(), "QR_SCAN")
        if (contactDao.insert(row) != -1L) ContactExchangeResult.Saved(row.domain())
        else contactDao.findByArmorChatId(row.armorChatId)?.resultFor(check.payload)
            ?: ContactExchangeResult.Conflict
    }

    override suspend fun rename(id: String, localName: String) = withContext(dispatchers.io) {
        require(localName.length <= 100)
        contactDao.rename(id, localName.trim())
    }

    private fun ContactEntity.resultFor(payload: IdentityQrPayload): ContactExchangeResult =
        if (publicIdentityKey == payload.publicKeyBase64Url) ContactExchangeResult.Duplicate(domain())
        else ContactExchangeResult.Conflict

    private fun ContactEntity.domain() = Contact(id, armorChatId, publicIdentityKey,
        displayName, createdAtEpochMs, VerificationMethod.valueOf(verificationMethod))
}
