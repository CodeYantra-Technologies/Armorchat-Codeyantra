package com.codeyantra.armorchat.core.mesh

sealed interface AdmitResult {
    /** [evicted]: the row removed to make room (Mesh-1D-C, so the caller can delete it from Room). */
    data class Held(val bundle: CustodyBundle, val evicted: CustodyBundle? = null) : AdmitResult
    data class Duplicate(val bundle: CustodyBundle) : AdmitResult
    data class Rejected(val reason: String) : AdmitResult
}

sealed interface AcceptResult {
    /** [evicted]: the row removed to make room (Mesh-1D-C, so the caller can delete it from Room). */
    data class Stored(val bundle: CustodyBundle, val evicted: CustodyBundle? = null) : AcceptResult
    data class Duplicate(val bundle: CustodyBundle) : AcceptResult
    data class Rejected(val reason: String) : AcceptResult
}

/**
 * In-memory custody set. The 4-hour deadline is absolute and is not refreshed
 * when a bundle is copied onto another phone.
 */
class CustodyLedger(
    bundles: List<CustodyBundle> = emptyList(),
) {
    private val rows = linkedMapOf<String, CustodyBundle>()

    init {
        bundles.forEach { rows[it.idHex] = it }
    }

    fun snapshot(): List<CustodyBundle> = rows.values.toList()

    fun admit(payload: ByteArray, nowMs: Long, emergency: Boolean): AdmitResult {
        if (payload.isEmpty() || payload.size > MeshPolicy.MAX_PAYLOAD_BYTES) {
            return AdmitResult.Rejected("size")
        }
        val id = MeshIds.idFor(payload)
        val existing = rows[MeshIds.hex(id)]
        if (existing != null) {
            if (!existing.live(nowMs)) {
                rows.remove(existing.idHex)
            } else {
                return AdmitResult.Duplicate(existing)
            }
        }
        // Mesh-1K-AS: own pool (hopCount 0) is reserved; foreign rows never consume it. Refuse, never evict.
        if (ownCount() >= MeshPolicy.OWN_MAX) return AdmitResult.Rejected("full")
        val bundle = CustodyBundle(
            id = id,
            createdAtMs = nowMs,
            expireAtMs = MeshPolicy.expireAt(nowMs),
            hopCount = 0,
            emergency = emergency,
            payload = payload,
        )
        rows[bundle.idHex] = bundle
        return AdmitResult.Held(bundle)
    }

    fun accept(bundle: CustodyBundle, nowMs: Long): AcceptResult {
        if (bundle.expireAtMs != MeshPolicy.expireAt(bundle.createdAtMs)) {
            return AcceptResult.Rejected("ttl")
        }
        if (bundle.createdAtMs > nowMs + MeshPolicy.CLOCK_SKEW_MS) {
            return AcceptResult.Rejected("future")
        }
        if (bundle.expireAtMs <= nowMs) return AcceptResult.Rejected("expired")
        if (bundle.hopCount >= MeshPolicy.MAX_HOPS) return AcceptResult.Rejected("hops")
        if (!bundle.id.contentEquals(MeshIds.idFor(bundle.payload))) return AcceptResult.Rejected("id")
        val existing = rows[bundle.idHex]
        if (existing != null) {
            if (existing.expireAtMs != bundle.expireAtMs) return AcceptResult.Rejected("ttl")
            return AcceptResult.Duplicate(existing)
        }
        // Mesh-1K-AS: foreign pool cap; the (untrusted) emergency bit plays no part. Refuse, never evict.
        if (foreignCount() >= MeshPolicy.FOREIGN_MAX) return AcceptResult.Rejected("full")
        val stored = bundle.copy(hopCount = bundle.hopCount + 1)
        rows[stored.idHex] = stored
        return AcceptResult.Stored(stored)
    }

    /** Expired bundles leave the phone. A peer that arrives after this finds nothing. */
    fun sweep(nowMs: Long): List<CustodyBundle> {
        val dead = rows.values.filter { it.expireAtMs <= nowMs }
        dead.forEach { rows.remove(it.idHex) }
        return dead
    }

    fun offer(remoteIds: Set<String>, nowMs: Long): List<CustodyBundle> {
        return rows.values
            .asSequence()
            .filter { it.live(nowMs) && it.idHex !in remoteIds }
            // Mesh-1K-AS: emergency orders only this phone's OWN rows; foreign rows by deadline only.
            .sortedWith(compareBy({ !(it.emergency && it.hopCount == 0) }, { it.expireAtMs }, { it.idHex }))
            .take(MeshPolicy.MAX_OFFERS_PER_ENCOUNTER)
            .toList()
    }

    fun markForwarded(idHex: String) {
        val row = rows[idHex] ?: return
        rows[idHex] = row.copy(forwardedCount = row.forwardedCount + 1)
    }

    fun drop(idHex: String) {
        rows.remove(idHex)
    }

    fun soonestExpireAt(nowMs: Long): Long? =
        rows.values.filter { it.live(nowMs) }.minOfOrNull { it.expireAtMs }

    fun contains(idHex: String): Boolean = rows.containsKey(idHex)

    /** Mesh-1K-AS: pool membership is derived from the stored hopCount (legacy rows included). */
    fun ownCount(): Int = rows.values.count { it.hopCount == 0 }

    fun foreignCount(): Int = rows.values.count { it.hopCount >= 1 }
}
