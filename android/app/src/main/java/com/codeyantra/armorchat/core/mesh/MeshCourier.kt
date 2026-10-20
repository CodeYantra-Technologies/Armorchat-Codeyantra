package com.codeyantra.armorchat.core.mesh

/** Mesh-1B: why custody admission refused a received bundle. */
enum class MeshRejection {
    MALFORMED,
    DEADLINE,
    FUTURE,
    EXPIRED,
    HOPS,
    ID,
    FULL,

    /** Mesh-1K-AS: foreign admission rate window exhausted (memory-only, not origin-keyed). */
    RATE,
    UNKNOWN,
    ;

    companion object {
        /** Maps CustodyLedger.AcceptResult.Rejected reasons. Unknown reasons stay rejected. */
        fun fromLedger(reason: String): MeshRejection = when (reason) {
            "ttl" -> DEADLINE
            "future" -> FUTURE
            "expired" -> EXPIRED
            "hops" -> HOPS
            "id" -> ID
            "full" -> FULL
            "rate" -> RATE
            else -> UNKNOWN
        }
    }
}

/** Mesh-1B: typed custody admission result (replaces the old Boolean). */
sealed interface MeshAdmission {
    /** First time this phone stored the bundle. Only this result may be offered to messaging. */
    data class Admitted(val idHex: String) : MeshAdmission

    /**
     * Already held in custody. The ledger cannot tell "pending for this phone" from "carried for
     * someone else", and delivered bundles are deleted, so a duplicate is never delivered (fail closed).
     */
    data class Duplicate(val idHex: String) : MeshAdmission

    data class Rejected(val reason: MeshRejection) : MeshAdmission
}

/**
 * Mesh-1K-R (fix A): truthful outcome of one direct local delivery attempt (a bundle custody refused
 * as FULL, addressed to one of this phone's own mailboxes, handed to messaging WITHOUT a custody slot).
 * Logged as counts only (never ids, mailbox ids or content).
 */
enum class MeshDirect {
    /** Authenticated (existing MLS path) and durably committed here (inbox row and/or validated ACK release). */
    DELIVERED,

    /** Already durably in this inbox (P1 pre-check / P2 marker): nothing decrypted, staged or receipted. */
    DUPLICATE,

    /** Not for this phone after all, conflicting, undecryptable or failed an inner binding check. Nothing changed. */
    REJECTED,

    /** Lock timeout, cancelled or durable commit failure: nothing changed; the peer re-offers next encounter. */
    FAILED,

    /** Locally addressed but the per-ingest direct budget was already used up: not attempted. */
    BUDGET_EXHAUSTED,
}

/**
 * Mesh-1A.1/1B: courier ingest decision used by EmergencyMeshService.ingest. Order per bundle:
 * decode (skip invalid), admit to custody, offer to messaging only if [MeshAdmission.Admitted],
 * drop this phone's copy only when messaging accepted it.
 *
 * Mesh-1K-R (fix A): when [deliverDirect] and [addressedHere] are supplied and custody refuses a bundle
 * ONLY because it is FULL (every other header check already passed in CustodyLedger.accept), a bundle
 * whose cleartext outer recipient_mailbox is one of this phone's own mailboxes ([addressedHere], an
 * UNAUTHENTICATED routing hint that only selects work) is handed to [deliverDirect] = the same
 * ChatMessagingService.deliverMesh path (messaging mutex + 12 s lock bound, P1 durable pre-check, MLS
 * decrypt with snapshot restore, bindInbound/previewReceipt/applyReceipt, durable Room transaction,
 * P3 validated release). At most [directBudget] attempts per ingest call; further locally addressed
 * FULL bundles are counted BUDGET_EXHAUSTED and skipped. Nothing is stored, evicted or dropped on this
 * path (no custody slot was ever allocated), and a transit/foreign bundle never takes it.
 * Duplicate (already held) and non-FULL rejections are unchanged (never delivered here).
 */
internal object MeshCourier {
    /** Mesh-1K-R: per-ingest bound on direct (slot-less) delivery attempts (= MAX_OFFERS_PER_ENCOUNTER). */
    const val MAX_DIRECT_PER_INGEST: Int = MeshPolicy.MAX_OFFERS_PER_ENCOUNTER

    suspend fun ingest(
        bytes: ByteArray,
        path: RadioPath,
        store: suspend (encoded: ByteArray, path: RadioPath) -> MeshAdmission,
        deliver: suspend (payload: ByteArray) -> Boolean,
        dropDelivered: suspend (idHex: String) -> Unit,
        addressedHere: ((payload: ByteArray) -> Boolean)? = null,
        deliverDirect: (suspend (payload: ByteArray) -> MeshDirect)? = null,
        directBudget: Int = MAX_DIRECT_PER_INGEST,
        reportDirect: (Map<MeshDirect, Int>) -> Unit = {},
    ) {
        val direct = linkedMapOf<MeshDirect, Int>()
        var attempts = 0
        var admitted = 0
        for (encoded in MeshFrames.decodeBundles(bytes)) {
            val decoded = BundleCodec.decode(encoded) as? BundleDecode.Ok ?: continue
            // Mesh-1K-AS: at most FOREIGN_PER_ENCOUNTER new custody admissions per ingest (one encounter's
            // frame batch). Further bundles are not stored this encounter; the peer may re-offer them later.
            if (admitted >= MeshPolicy.FOREIGN_PER_ENCOUNTER) continue
            val admission = store(encoded, path)
            if (admission is MeshAdmission.Admitted) admitted++
            if (admission !is MeshAdmission.Admitted) {
                if (deliverDirect == null || addressedHere == null) continue
                if (admission !is MeshAdmission.Rejected || admission.reason != MeshRejection.FULL) continue
                val payload = decoded.bundle.payload
                val local = try {
                    addressedHere(payload)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (_: Exception) {
                    false
                }
                if (!local) continue
                if (attempts >= directBudget) {
                    direct[MeshDirect.BUDGET_EXHAUSTED] = (direct[MeshDirect.BUDGET_EXHAUSTED] ?: 0) + 1
                    continue
                }
                attempts++
                val outcome = try {
                    deliverDirect(payload)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (_: Exception) {
                    MeshDirect.FAILED
                }
                direct[outcome] = (direct[outcome] ?: 0) + 1
                continue
            }
            if (deliver(decoded.bundle.payload)) {
                dropDelivered(decoded.bundle.idHex)
            }
        }
        if (direct.isNotEmpty()) runCatching { reportDirect(direct) }
    }
}
