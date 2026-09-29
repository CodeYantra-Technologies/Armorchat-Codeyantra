package com.codeyantra.armorchat.core.mesh

sealed interface AdmitResult {
    data class Held(val bundle: CustodyBundle) : AdmitResult
    data class Duplicate(val bundle: CustodyBundle) : AdmitResult
    data class Rejected(val reason: String) : AdmitResult
}

sealed interface AcceptResult {
    data class Stored(val bundle: CustodyBundle) : AcceptResult
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
        evictIfFull(emergency)
        if (rows.size >= MeshPolicy.MAX_BUNDLES) return AdmitResult.Rejected("full")
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
        evictIfFull(bundle.emergency)
        if (rows.size >= MeshPolicy.MAX_BUNDLES) return AcceptResult.Rejected("full")
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
            .sortedWith(compareBy({ !it.emergency }, { it.expireAtMs }, { it.idHex }))
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

    private fun evictIfFull(incomingEmergency: Boolean) {
        if (rows.size < MeshPolicy.MAX_BUNDLES) return
        val victim = rows.values
            .sortedWith(compareBy({ it.emergency }, { it.expireAtMs }))
            .firstOrNull()
            ?: return
        if (victim.emergency && incomingEmergency) return
        if (victim.emergency && !incomingEmergency) return
        rows.remove(victim.idHex)
    }
}
