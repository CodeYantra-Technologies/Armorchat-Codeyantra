package com.codeyantra.armorchat.deal.lab

import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealKeyProtector
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.crypto.PartyWrapTarget
import com.codeyantra.armorchat.deal.crypto.SealedDocument
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.DealEvent
import com.codeyantra.armorchat.deal.domain.DealException
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealFinality
import com.codeyantra.armorchat.deal.domain.DealIntegrityException
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealParty
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.ImmutableVersionException
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.recovery.DealRecovery
import com.codeyantra.armorchat.deal.storage.DealRepository

/**
 * In-memory Armor Deal Lab session.
 *
 * Party A is a synthetic key generated on this device. Party B is another
 * synthetic key on this same device and is always labeled simulated.
 * Both signatures are real Ed25519 signatures. Nothing is sent to a network.
 * A backup is marked verified only after [DealRecovery.open] returns a
 * document that matches this session. Execution stays off.
 *
 * Displayed agreement fields are written into canonical version 2. An
 * incomplete form has no document hash and cannot be signed.
 */
class DealLabController(
    private val repository: DealRepository,
    private val protector: DealKeyProtector,
    private val recovery: DealRecovery = DealRecovery(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { DealIds.generate() },
) {
    private val sessions = LinkedHashMap<String, LabSession>()
    private var tick = 0L
    var storedReview: StoredReview? = null
        private set

    fun summaries(): List<LabSummary> {
        val memory = sessions.values.map { session ->
            val summary = session.summary()
            val proven = provenFinal(session)
            val claims = DealLifecycle.claimsFinality(DealStatus.valueOf(session.status))
            summary.copy(
                statusLabel = storedStatusLabel(session.status, claims, proven),
                backupVerified = session.backupVerified && proven,
            )
        }
        val known = sessions.keys
        val stored = repository.listDeals().filter { it.dealId !in known }.map { row ->
            val proven = storedFinalityProven(row.dealId)
            val claims = runCatching { DealLifecycle.claimsFinality(DealStatus.valueOf(row.status)) }.getOrDefault(false)
            LabSummary(
                dealId = row.dealId,
                title = "Saved demo agreement",
                status = row.status,
                statusLabel = storedStatusLabel(row.status, claims, proven),
                persisted = true,
                backupVerified = proven && row.status == DealStatusNames.BACKUP_PENDING,
                inSession = false,
            )
        }
        return memory + stored
    }

    fun snapshot(dealId: String): LabDealSnapshot = buildSnapshot(require(dealId))

    fun openedDocument(dealId: String): CanonicalDeal? {
        val session = sessions[dealId] ?: return null
        return tryCanonical(session)
    }

    internal fun unverifiedPackage(dealId: String): ByteArray? = sessions[dealId]?.backupPackage?.copyOf()

    fun history(dealId: String): List<LabAudit> {
        val session = sessions[dealId]
        val memory = session?.audits.orEmpty()
        val stored = if (session?.persisted == true || session == null) {
            repository.audits(dealId).map { row ->
                LabAudit(row.action, row.outcome, row.createdAtEpochMs, detail = "")
            }
        } else {
            emptyList()
        }
        return (memory + stored)
            .distinctBy { "${it.createdAtEpochMs}:${it.action}:${it.outcome}:${it.detail}" }
            .sortedWith(compareBy({ it.createdAtEpochMs }, { it.action }))
    }

    fun createDraft(terms: String): String = createSession(LabAgreement.sample(terms), requireValid = true)

    fun beginBlank(): String = createSession(LabForm(), requireValid = false)

    fun updateForm(dealId: String, form: LabForm): LabResult {
        val session = require(dealId)
        if (session.signatures.isNotEmpty()) {
            audit(session, "draft_edit_rejected", "rejected", "A signed version cannot be edited.")
            throw ImmutableVersionException()
        }
        if (!DealLifecycle.allowsRevision(DealStatus.valueOf(session.status))) {
            audit(session, "draft_edit_rejected", "rejected", "This deal can no longer be revised.")
            throw DealStateException("This deal can no longer be revised.")
        }
        val incoming = form.trimmed()
        val problems = LabAgreement.problems(incoming)
        if (problems.isNotEmpty()) {
            audit(session, "draft_edit_rejected", "rejected", problems.first())
            return LabResult.Rejected(problems.joinToString("\n"))
        }
        val previous = session.form
        val previousStatus = session.status
        val previousA = session.proposer.label
        val previousB = session.counterparty.label
        session.form = incoming
        session.proposer.label = incoming.partyAName
        session.counterparty.label = incoming.partyBName
        try {
            canonical(session)
        } catch (e: DealException) {
            session.form = previous
            session.proposer.label = previousA
            session.counterparty.label = previousB
            throw e
        }
        if (incoming != previous && previousStatus != DealStatusNames.DRAFT) {
            session.status = DealStatusNames.DRAFT
            audit(
                session,
                "draft_reopened",
                "ok",
                "Returned to draft because the agreement changed. Review it again before signing.",
            )
        }
        audit(session, "draft_edited", "ok", "Draft saved in this session. Still not saved to the database.")
        return LabResult.Ok("Draft saved in this session. It is not written to the database until both parties sign.")
    }

    fun updateTerms(dealId: String, terms: String): LabResult {
        val session = require(dealId)
        val result = updateForm(dealId, session.form.copy(terms = terms))
        if (result is LabResult.Rejected) throw com.codeyantra.armorchat.deal.domain.DealFormatException(result.message.lineSequence().first())
        return result
    }

    fun apply(dealId: String, event: DealEvent): LabResult {
        val session = require(dealId)
        if (event == DealEvent.PROPOSE && tryCanonical(session) == null) {
            return reject(
                session,
                "transition_rejected",
                "This agreement is incomplete. Finish the required details before anyone signs.",
            )
        }
        val status = DealStatus.valueOf(session.status)
        val next = try {
            DealLifecycle.transition(status, event)
        } catch (e: DealStateException) {
            return reject(session, "transition_rejected", e.message ?: "Transition refused.")
        }
        if (event == DealEvent.CLOSE) {
            return reject(
                session,
                "transition_rejected",
                "Closure needs both parties to confirm. This lab will not mark the agreement closed. Status stays ${session.status}.",
            )
        }
        if (event == DealEvent.EXECUTE ||
            event == DealEvent.MARK_BACKUP_PENDING ||
            event == DealEvent.PARTIAL_SIGN ||
            event == DealEvent.FULL_SIGN
        ) {
            return reject(
                session,
                "transition_rejected",
                "Cloud execution and remote backup receipts are disabled. Status stays ${session.status}.",
            )
        }
        session.status = next.name
        val detail = when (event) {
            DealEvent.PROPOSE -> "Marked ready to sign. Nobody has signed yet."
            DealEvent.CANCEL -> "Agreement cancelled."
            else -> "Status is ${next.name}."
        }
        audit(session, "transition_${event.name.lowercase()}", "ok", detail)
        return LabResult.Ok(detail)
    }

    fun signLocal(dealId: String): LabResult = sign(dealId, simulated = false)

    fun signSimulatedCounterparty(dealId: String): LabResult = sign(dealId, simulated = true)

    fun sealLocalPackage(dealId: String, secret: ByteArray): LabResult {
        val session = require(dealId)
        val sealedDocument = session.sealed
        if (!session.persisted || sealedDocument == null || session.status != DealStatusNames.FULLY_SIGNED) {
            return reject(session, "backup_rejected", "Local backup is not verified. Seal the saved fully signed deal first.")
        }
        if (session.backupVerified) {
            return reject(session, "backup_rejected", "A local backup is already verified. This demo will not replace it.")
        }
        val agreement = canonical(session)
        val stored = repository.sealedDocument(session.dealId, session.versionId)
        if (stored == null || !stored.contentEquals(sealedDocument.encode())) {
            return reject(session, "backup_rejected", "Stored ciphertext does not match this session. Backup is not verified.")
        }
        val packaged = try {
            recovery.seal(
                partyId = session.proposer.partyId,
                secret = secret,
                keys = session.proposer.keys,
                sealedDocument = sealedDocument,
                deal = agreement,
                signatures = session.signatures,
            )
        } catch (e: DealException) {
            session.backupVerified = false
            return reject(session, "backup_seal_failed", "Local backup is not verified. ${e.message}")
        }
        session.backupPackage = packaged
        session.backupVerified = false
        audit(session, "backup_sealed", "ok", "Local package sealed. Not verified until recovery succeeds.")
        return LabResult.Ok("Local package sealed. Not verified until recovery succeeds.")
    }

    fun verifyLocalPackage(dealId: String, secret: ByteArray): LabResult {
        val session = require(dealId)
        val packaged = session.backupPackage
            ?: return reject(session, "backup_verify_failed", "Local backup is not verified. No local package is waiting.")
        val before = packaged.copyOf()
        val opened = try {
            recovery.open(packaged, secret)
        } catch (e: DealException) {
            check(before.contentEquals(session.backupPackage)) { "Recovery failure changed the package bytes." }
            session.backupVerified = false
            return reject(session, "backup_verify_failed", "Local backup is not verified. ${e.message}")
        }
        check(before.contentEquals(session.backupPackage)) { "Recovery changed the package bytes." }
        val agreement = canonical(session)
        val matches = opened.partyId == session.proposer.partyId &&
            opened.deal.documentHash().contentEquals(agreement.documentHash()) &&
            opened.deal.terms == agreement.terms &&
            opened.deal.amountMinor == agreement.amountMinor &&
            opened.deal.details?.title == agreement.details?.title &&
            opened.deal.details?.deadlineDate == agreement.details?.deadlineDate &&
            opened.signatures.size == 2
        if (!matches) {
            session.backupVerified = false
            return reject(session, "backup_verify_failed", "Local backup is not verified. Recovered bytes did not match.")
        }
        repository.recordMilestone(
            dealId = session.dealId,
            status = DealStatusNames.BACKUP_PENDING,
            action = "local_backup_verified",
            outcome = "ok",
            protector = protector,
        )
        session.status = DealStatusNames.BACKUP_PENDING
        session.backupVerified = true
        audit(session, "backup_verified", "ok", VERIFIED_BACKUP)
        return LabResult.Ok(VERIFIED_BACKUP, verified = true)
    }

    fun openStored(dealId: String): LabResult {
        if (sessions.containsKey(dealId)) {
            storedReview = null
            return LabResult.Ok("Opened the in-session deal.")
        }
        val row = repository.listDeals().firstOrNull { it.dealId == dealId }
            ?: return LabResult.Rejected("That deal is not in the local Armor Deal database.")
        val partyId = repository.storedLocalPartyId(dealId)
            ?: return LabResult.Rejected("No local key is stored for this deal.")
        val deal = try {
            repository.loadCanonical(dealId, partyId, protector)
        } catch (e: DealIntegrityException) {
            return LabResult.Rejected("Stored finality evidence does not verify. The saved row was not changed.")
        } catch (e: DealException) {
            return LabResult.Rejected("Stored deal could not be opened. ${e.message}")
        }
        val proven = repository.assessStored(dealId, partyId, protector).proven
        val claims = runCatching { DealLifecycle.claimsFinality(DealStatus.valueOf(row.status)) }.getOrDefault(false)
        if (claims && !proven) {
            return LabResult.Rejected("Stored finality evidence does not verify. The saved row was not changed.")
        }
        val verified = proven && row.status == DealStatusNames.BACKUP_PENDING
        val proposer = deal.parties.firstOrNull { it.role == PartyRole.PROPOSER }
        val counterparty = deal.parties.firstOrNull { it.role == PartyRole.COUNTERPARTY }
        storedReview = StoredReview(
            dealId = dealId,
            status = row.status,
            statusLabel = storedStatusLabel(row.status, claims, proven),
            terms = deal.terms,
            title = deal.details?.title,
            deadline = deal.details?.deadlineDate,
            paymentConditions = deal.details?.paymentConditions,
            partyAName = proposer?.label,
            partyARole = deal.details?.proposerRole,
            partyBName = counterparty?.label,
            partyBRole = deal.details?.counterpartyRole,
            amountMinor = deal.amountMinor,
            currency = deal.currency,
            documentHashHex = deal.documentHash().toDealHex(),
            backupVerified = verified,
            audits = history(dealId),
        )
        val message = if (verified) {
            VERIFIED_BACKUP
        } else {
            "Opened the encrypted local row. Local backup is not verified. No cloud backup is claimed."
        }
        return LabResult.Ok(message, verified = verified)
    }

    private fun createSession(form: LabForm, requireValid: Boolean): String {
        val trimmed = form.trimmed()
        val proposer = newParty(
            PartyRole.PROPOSER,
            trimmed.partyAName.ifBlank { "Local synthetic" },
            LabPartyOrigin.LOCAL_SYNTHETIC,
        )
        val counterparty = newParty(
            PartyRole.COUNTERPARTY,
            trimmed.partyBName.ifBlank { "Simulated party" },
            LabPartyOrigin.SIMULATED_COUNTERPARTY,
        )
        val session = LabSession(
            dealId = newId(),
            versionId = newId(),
            createdAtEpochMs = clock(),
            form = trimmed,
            proposer = proposer,
            counterparty = counterparty,
            status = DealStatusNames.DRAFT,
        )
        if (requireValid) canonical(session)
        sessions[session.dealId] = session
        audit(session, "draft_created", "ok", "Synthetic draft created in this session. Not saved.")
        return session.dealId
    }

    private fun sign(dealId: String, simulated: Boolean): LabResult {
        val session = require(dealId)
        if (tryCanonical(session) == null) {
            return reject(session, "signature_rejected", "This agreement is incomplete and cannot be signed.")
        }
        val party = if (simulated) session.counterparty else session.proposer
        if (session.signatures.any { it.partyId == party.partyId && it.action == SignAction.SIGN }) {
            return reject(session, "signature_duplicate", "This party already signed this version.")
        }
        val allowed = session.status == DealStatusNames.PROPOSED ||
            session.status == DealStatusNames.NEGOTIATING ||
            session.status == DealStatusNames.PARTIALLY_SIGNED
        if (!allowed) {
            return reject(session, "signature_rejected", "Signing is refused while the deal is ${session.status}.")
        }
        val agreement = canonical(session)
        val signature = DealSignatures.sign(
            party.keys.signingHandle(),
            agreement,
            party.partyId,
            SignAction.SIGN,
        )
        DealSignatures.verify(
            agreement,
            signature,
            SignAction.SIGN,
            agreement.dealId,
            agreement.versionId,
            party.keys.signingPublic,
        )
        val signatures = session.signatures + signature
        val completing = signatures.size == 2
        val next = if (completing) {
            if (session.status != DealStatusNames.PARTIALLY_SIGNED) {
                return reject(session, "signature_rejected", "The second signature needs a partially signed deal.")
            }
            DealLifecycle.transition(DealStatus.PARTIALLY_SIGNED, DealEvent.FULL_SIGN)
        } else {
            DealLifecycle.transition(DealStatus.valueOf(session.status), DealEvent.PARTIAL_SIGN)
        }
        if (!completing) {
            session.signatures = signatures
            session.status = next.name
            val kind = if (simulated) SIMULATED_SIGN else LOCAL_SIGN
            audit(session, if (simulated) "simulated_signature" else "local_signature", "ok", kind)
            return LabResult.Ok(kind)
        }
        val sealed = DealCipher.encrypt(
            dealId = agreement.dealId,
            versionId = agreement.versionId,
            canonical = agreement.canonicalBytes(),
            recipients = listOf(
                PartyWrapTarget(session.proposer.partyId, session.proposer.keys.encryptionPublicHandle()),
                PartyWrapTarget(session.counterparty.partyId, session.counterparty.keys.encryptionPublicHandle()),
            ),
        )
        repository.saveVerified(
            deal = agreement,
            sealed = sealed,
            signatures = signatures,
            localPartyId = session.proposer.partyId,
            localKeys = session.proposer.keys,
            protector = protector,
            status = DealStatusNames.FULLY_SIGNED,
        )
        session.signatures = signatures
        session.status = DealStatusNames.FULLY_SIGNED
        session.sealed = sealed
        session.persisted = true
        val kind = if (simulated) SIMULATED_SIGN else LOCAL_SIGN
        audit(session, if (simulated) "simulated_signature" else "local_signature", "ok", kind)
        audit(session, "local_save", "ok", "Encrypted local row saved. No network call.")
        return LabResult.Ok("$kind Encrypted local row saved. No network call.")
    }

    private fun canonical(session: LabSession): CanonicalDeal {
        val form = session.form.trimmed()
        val problems = LabAgreement.problems(form)
        if (problems.isNotEmpty()) {
            throw com.codeyantra.armorchat.deal.domain.DealFormatException(problems.first())
        }
        session.proposer.label = form.partyAName
        session.counterparty.label = form.partyBName
        return CanonicalDeal.create(
            dealId = session.dealId,
            versionId = session.versionId,
            parentVersionId = null,
            createdAtEpochMs = session.createdAtEpochMs,
            currency = LabAgreement.CURRENCY,
            amountMinor = InrMoney.paiseOrNull(form.amountText),
            parties = listOf(session.proposer.asParty(), session.counterparty.asParty()),
            ancestry = emptyList(),
            terms = form.terms,
            details = LabAgreement.details(form),
        )
    }

    private fun tryCanonical(session: LabSession): CanonicalDeal? = try {
        canonical(session)
    } catch (e: DealException) {
        null
    }

    private fun buildSnapshot(session: LabSession): LabDealSnapshot {
        val form = session.form.trimmed()
        val problems = LabAgreement.problems(form)
        val agreement = if (problems.isEmpty()) tryCanonical(session) else null
        val localSigned = session.signatures.any { it.partyId == session.proposer.partyId && it.action == SignAction.SIGN }
        val simulatedSigned = session.signatures.any {
            it.partyId == session.counterparty.partyId && it.action == SignAction.SIGN
        }
        val signable = agreement != null && (
            session.status == DealStatusNames.PROPOSED ||
                session.status == DealStatusNames.NEGOTIATING ||
                session.status == DealStatusNames.PARTIALLY_SIGNED
            )
        val editable = session.signatures.isEmpty() &&
            DealLifecycle.allowsRevision(DealStatus.valueOf(session.status))
        val proven = agreement != null && DealFinality.assess(
            deal = agreement,
            signatures = session.signatures,
            pinnedSigningKeys = agreement.parties.associate { it.partyId to it.signingPublicKey },
        ).proven
        val claims = DealLifecycle.claimsFinality(DealStatus.valueOf(session.status))
        val versionLabel = when {
            agreement == null -> "Incomplete agreement"
            session.signatures.isNotEmpty() -> "Signed version"
            else -> "Editable draft"
        }
        return LabDealSnapshot(
            dealId = session.dealId,
            versionId = session.versionId,
            status = session.status,
            statusLabel = storedStatusLabel(session.status, claims, proven),
            finalityProven = proven,
            versionLabel = versionLabel,
            form = form,
            terms = form.terms,
            problems = problems,
            complete = agreement != null,
            localPartyId = session.proposer.partyId,
            counterpartyId = session.counterparty.partyId,
            documentHashHex = agreement?.documentHash()?.toDealHex(),
            amountMinor = agreement?.amountMinor,
            currency = agreement?.currency,
            localSigned = localSigned,
            simulatedSigned = simulatedSigned,
            localSignedAtEpochMs = signedAt(session, "local_signature"),
            simulatedSignedAtEpochMs = signedAt(session, "simulated_signature"),
            persisted = session.persisted,
            backupVerified = session.backupVerified && proven,
            backupPrepared = session.backupPackage != null,
            actions = LabActions(
                canEdit = editable,
                canPropose = editable && agreement != null && session.status == DealStatusNames.DRAFT,
                canSignLocal = signable && !localSigned,
                canSignSimulated = signable && !simulatedSigned,
                canSealBackup = session.persisted && proven && !session.backupVerified,
                canVerifyBackup = session.backupPackage != null && !session.backupVerified,
                canExecute = false,
            ),
        )
    }

    private fun provenFinal(session: LabSession): Boolean {
        val agreement = tryCanonical(session) ?: return false
        return DealFinality.assess(
            deal = agreement,
            signatures = session.signatures,
            pinnedSigningKeys = agreement.parties.associate { it.partyId to it.signingPublicKey },
        ).proven
    }

    private fun storedFinalityProven(dealId: String): Boolean {
        val partyId = repository.storedLocalPartyId(dealId) ?: return false
        return runCatching { repository.assessStored(dealId, partyId, protector).proven }.getOrDefault(false)
    }

    private fun storedStatusLabel(status: String, claims: Boolean, proven: Boolean): String = when {
        claims && !proven -> DealStatusNames.plain(DealStatusNames.FINALITY_FAILURE)
        proven && !claims -> DealStatusNames.plain(DealStatusNames.FULLY_SIGNED)
        else -> DealStatusNames.plain(status)
    }

    private fun signedAt(session: LabSession, action: String): Long? =
        session.audits.lastOrNull { it.action == action && it.outcome == "ok" }?.createdAtEpochMs

    private fun newParty(role: PartyRole, label: String, origin: LabPartyOrigin): LabParty {
        val keys = DealKeyMaterial.generate()
        return LabParty(newId(), role, label, origin, keys)
    }

    private fun require(dealId: String): LabSession =
        sessions[dealId] ?: throw DealStateException("That draft is not in this lab session.")

    private fun reject(session: LabSession, action: String, message: String): LabResult {
        audit(session, action, "rejected", message)
        return LabResult.Rejected(message)
    }

    private fun audit(session: LabSession, action: String, outcome: String, detail: String) {
        tick += 1
        session.audits.add(LabAudit(action, outcome, clock() + tick, detail))
    }

    companion object {
        const val LOCAL_SIGN =
            "Real Ed25519 signature by the local synthetic party. Not a legal eSign."
        const val SIMULATED_SIGN =
            "Real Ed25519 signature by a simulated counterparty key held on this device. No remote party was contacted."
        const val VERIFIED_BACKUP = "Local encrypted backup verified on this device."
    }
}

enum class LabPartyOrigin {
    LOCAL_SYNTHETIC,
    SIMULATED_COUNTERPARTY,
}

class LabParty(
    val partyId: String,
    val role: PartyRole,
    var label: String,
    val origin: LabPartyOrigin,
    val keys: DealKeyMaterial,
) {
    fun asParty(): DealParty = DealParty(partyId, role, label, keys.signingPublic, keys.encryptionPublic)
}

class LabSession(
    val dealId: String,
    val versionId: String,
    val createdAtEpochMs: Long,
    var form: LabForm,
    val proposer: LabParty,
    val counterparty: LabParty,
    var status: String,
) {
    var signatures: List<DealSignature> = emptyList()
    var sealed: SealedDocument? = null
    var persisted: Boolean = false
    var backupPackage: ByteArray? = null
    var backupVerified: Boolean = false
    val audits: MutableList<LabAudit> = mutableListOf()

    fun summary(): LabSummary = LabSummary(
        dealId = dealId,
        title = form.title.ifBlank { "Untitled demo agreement" },
        status = status,
        statusLabel = DealStatusNames.plain(status),
        persisted = persisted,
        backupVerified = backupVerified,
        inSession = true,
    )
}

data class LabSummary(
    val dealId: String,
    val title: String,
    val status: String,
    val statusLabel: String,
    val persisted: Boolean,
    val backupVerified: Boolean,
    val inSession: Boolean,
)

data class LabDealSnapshot(
    val dealId: String,
    val versionId: String,
    val status: String,
    val statusLabel: String,
    val finalityProven: Boolean,
    val versionLabel: String,
    val form: LabForm,
    val terms: String,
    val problems: List<String>,
    val complete: Boolean,
    val localPartyId: String,
    val counterpartyId: String,
    val documentHashHex: String?,
    val amountMinor: Long?,
    val currency: String?,
    val localSigned: Boolean,
    val simulatedSigned: Boolean,
    val localSignedAtEpochMs: Long?,
    val simulatedSignedAtEpochMs: Long?,
    val persisted: Boolean,
    val backupVerified: Boolean,
    val backupPrepared: Boolean,
    val actions: LabActions,
)

data class LabActions(
    val canEdit: Boolean,
    val canPropose: Boolean,
    val canSignLocal: Boolean,
    val canSignSimulated: Boolean,
    val canSealBackup: Boolean,
    val canVerifyBackup: Boolean,
    val canExecute: Boolean,
)

data class LabAudit(
    val action: String,
    val outcome: String,
    val createdAtEpochMs: Long,
    val detail: String,
) {
    fun summary(): String = when (action) {
        "draft_created" -> "Draft started"
        "draft_edited" -> "Agreement details changed"
        "draft_reopened" -> "Returned to draft"
        "transition_propose" -> "Marked ready to sign"
        "transition_cancel" -> "Cancelled"
        "local_signature" -> "Party A signed"
        "simulated_signature" -> "Party B signed (simulated)"
        "local_save" -> "Saved encrypted on this device"
        "backup_sealed" -> "Local recovery package created"
        "backup_verified" -> "Local backup verified"
        "backup_verify_failed" -> "Local backup check failed"
        "backup_rejected" -> "Backup refused"
        "backup_seal_failed" -> "Backup could not be created"
        "transition_rejected" -> "Step refused"
        "signature_rejected" -> "Signing refused"
        "signature_duplicate" -> "That party had already signed"
        "draft_edit_rejected" -> "Edit refused"
        else -> action.replace('_', ' ')
    }
}

data class StoredReview(
    val dealId: String,
    val status: String,
    val statusLabel: String,
    val terms: String,
    val title: String?,
    val deadline: String?,
    val paymentConditions: String?,
    val partyAName: String?,
    val partyARole: String?,
    val partyBName: String?,
    val partyBRole: String?,
    val amountMinor: Long?,
    val currency: String?,
    val documentHashHex: String?,
    val backupVerified: Boolean,
    val audits: List<LabAudit>,
)

sealed class LabResult {
    abstract val message: String
    abstract val verified: Boolean

    data class Ok(override val message: String, override val verified: Boolean = false) : LabResult()
    data class Rejected(override val message: String) : LabResult() {
        override val verified: Boolean = false
    }
}

/**
 * Status names stored in the deal database. Kept as strings here so the debug
 * lab does not grow a second lifecycle.
 */
object DealStatusNames {
    const val DRAFT = "DRAFT"
    const val PROPOSED = "PROPOSED"
    const val NEGOTIATING = "NEGOTIATING"
    const val PARTIALLY_SIGNED = "PARTIALLY_SIGNED"
    const val FINALITY_FAILURE = "FINALITY_FAILURE"
    const val FULLY_SIGNED = "FULLY_SIGNED"
    const val BACKUP_PENDING = "BACKUP_PENDING"

    fun plain(status: String): String = when (status) {
        DRAFT -> "Draft"
        PROPOSED -> "Ready to sign"
        NEGOTIATING -> "Being revised"
        PARTIALLY_SIGNED -> "One party has signed"
        FULLY_SIGNED -> "Both parties have signed"
        FINALITY_FAILURE -> "Finality not proven"
        BACKUP_PENDING -> "Local backup verified"
        "CANCELLED" -> "Cancelled"
        "REJECTED" -> "Rejected"
        "EXPIRED" -> "Expired"
        "EXECUTED" -> "Executed"
        "CLOSED" -> "Closed"
        else -> status
    }
}

internal fun ByteArray.toDealHex(): String = joinToString("") { "%02x".format(it) }
