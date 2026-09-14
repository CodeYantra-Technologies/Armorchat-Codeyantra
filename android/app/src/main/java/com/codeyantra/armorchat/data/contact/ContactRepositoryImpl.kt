package com.codeyantra.armorchat.data.contact

import com.codeyantra.armorchat.core.common.AppDispatchers
import com.codeyantra.armorchat.core.database.dao.ContactDao
import com.codeyantra.armorchat.core.database.entity.ContactEntity
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.RelayRegistry
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.identity.ContactQrParser
import com.codeyantra.armorchat.domain.identity.ParsedContactQr
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
    private val relayRegistry: RelayRegistry = DefaultRelayRegistry(),
) : ContactRepository {
    override fun observeContacts() = contactDao.observeAll().map { rows -> rows.map { it.domain() } }

    override fun observeContact(id: String) = contactDao.observeById(id).map { it?.domain() }

    override suspend fun getContact(id: String): Contact? = withContext(dispatchers.io) {
        contactDao.findById(id)?.domain()
    }

    override suspend fun findByPublicKey(publicIdentityKey: String): Contact? = withContext(dispatchers.io) {
        contactDao.findByPublicIdentityKey(publicIdentityKey)?.domain()
    }

    override suspend fun inspectQr(raw: String): ContactExchangeResult = withContext(dispatchers.io) {
        val parsed = ContactQrParser.parse(raw = raw, relayRegistry = relayRegistry).getOrNull()
            ?: return@withContext ContactExchangeResult.Invalid
        if (parsed is ParsedContactQr.V3Invite) return@withContext ContactExchangeResult.Ready(parsed)
        val localId = contactDao.localArmorChatId() ?: return@withContext ContactExchangeResult.NoLocalIdentity
        if (parsed.armorChatId == localId) return@withContext ContactExchangeResult.Self
        val existing = contactDao.findByArmorChatId(parsed.armorChatId)
        if (existing != null) existing.resultFor(parsed) else ContactExchangeResult.Ready(parsed)
    }

    override suspend fun addQr(raw: String, localName: String): ContactExchangeResult = withContext(dispatchers.io) {
        require(localName.length <= 100) { "Local name is too long" }
        val check = inspectQr(raw)
        if (check !is ContactExchangeResult.Ready) return@withContext check
        insertParsed(check.parsed, localName)
    }

    override suspend fun addVerifiedBundle(
        parsed: ParsedContactQr,
        localName: String,
    ): ContactExchangeResult = withContext(dispatchers.io) {
        require(localName.length <= 100) { "Local name is too long" }
        if (parsed is ParsedContactQr.V3Invite) return@withContext ContactExchangeResult.Invalid
        val localId = contactDao.localArmorChatId() ?: return@withContext ContactExchangeResult.NoLocalIdentity
        if (parsed.armorChatId == localId) return@withContext ContactExchangeResult.Self
        insertParsed(parsed, localName)
    }

    override suspend fun rename(id: String, localName: String) = withContext(dispatchers.io) {
        require(localName.length <= 100)
        contactDao.rename(id, localName.trim())
    }

    override suspend fun deleteContact(id: String) = withContext(dispatchers.io) {
        contactDao.deleteById(id)
    }

    override suspend fun addInboundPinned(
        publicIdentityKey: String,
        displayName: String,
        handle: String?,
    ): Contact = withContext(dispatchers.io) {
        contactDao.findByPublicIdentityKey(publicIdentityKey)?.let { return@withContext it.domain() }
        val pk = Base64Url.decode(publicIdentityKey)
        val armorChatId = ArmorChatId.derive(pk)
        contactDao.findByArmorChatId(armorChatId)?.let { return@withContext it.domain() }
        val row = ContactEntity(
            id = UUID.randomUUID().toString(),
            armorChatId = armorChatId,
            publicIdentityKey = publicIdentityKey,
            displayName = displayName.trim(),
            createdAtEpochMs = System.currentTimeMillis(),
            verificationMethod = "QR_SCAN",
            handle = handle,
        )
        contactDao.insert(row)
        row.domain()
    }

    private suspend fun insertParsed(parsed: ParsedContactQr, localName: String): ContactExchangeResult {
        val existing = contactDao.findByArmorChatId(parsed.armorChatId)
        if (existing != null) return existing.resultFor(parsed)
        val signedName = parsed.displayName?.trim().orEmpty()
        val name = localName.trim().ifBlank { signedName }
        val row = ContactEntity(
            id = UUID.randomUUID().toString(),
            armorChatId = parsed.armorChatId,
            publicIdentityKey = parsed.publicKeyBase64Url,
            displayName = name,
            createdAtEpochMs = System.currentTimeMillis(),
            verificationMethod = "QR_SCAN",
            handle = parsed.handle,
        )
        return if (contactDao.insert(row) != -1L) ContactExchangeResult.Saved(row.domain(), parsed)
        else contactDao.findByArmorChatId(row.armorChatId)?.resultFor(parsed)
            ?: ContactExchangeResult.Conflict
    }

    private suspend fun ContactEntity.resultFor(parsed: ParsedContactQr): ContactExchangeResult {
        if (publicIdentityKey != parsed.publicKeyBase64Url) return ContactExchangeResult.Conflict
        val newHandle = parsed.handle
        val newName = parsed.displayName?.trim().orEmpty()
        val handleChanged = !newHandle.isNullOrBlank() && newHandle != handle
        if (handleChanged || (newName.isNotBlank() && newName != displayName)) {
            val previous = if (handleChanged) handle else previousHandle
            val changedAt = if (handleChanged) System.currentTimeMillis() else handleChangedAtEpochMs
            contactDao.updateSignedProfile(
                id = id,
                displayName = newName.ifBlank { displayName },
                handle = newHandle ?: handle,
                previousHandle = previous,
                changedAt = changedAt,
            )
            val updated = contactDao.findById(id)?.domain() ?: domain()
            return if (handleChanged) {
                ContactExchangeResult.HandleChanged(updated, previous, parsed)
            } else {
                ContactExchangeResult.Duplicate(updated, parsed)
            }
        }
        return ContactExchangeResult.Duplicate(domain(), parsed)
    }

    private fun ContactEntity.domain() = Contact(
        id = id,
        armorChatId = armorChatId,
        publicIdentityKey = publicIdentityKey,
        displayName = displayName,
        createdAtEpochMs = createdAtEpochMs,
        verificationMethod = VerificationMethod.valueOf(verificationMethod),
        handle = handle,
        previousHandle = previousHandle,
        handleChangedAtEpochMs = handleChangedAtEpochMs,
    )
}
