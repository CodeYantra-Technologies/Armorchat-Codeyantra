package com.codeyantra.armorchat.core.mesh

/**
 * Emergency mesh custody law.
 *
 * A sealed bundle does not live in the radio. With no peer nearby it stays on
 * the phone that admitted it until [ABSOLUTE_TTL_MS] after [createdAt]. A later
 * hop copies the same deadline. It never restarts the 4 hours.
 */
object MeshPolicy {
    const val ABSOLUTE_TTL_MS: Long = 4L * 60L * 60L * 1000L
    const val MAX_HOPS: Int = 6
    const val MAX_BUNDLES: Int = 48
    const val MAX_OFFERS_PER_ENCOUNTER: Int = 8
    const val MAX_PAYLOAD_BYTES: Int = 48 * 1024
    const val BLE_DIRECT_MAX_BYTES: Int = 8 * 1024
    const val PROTOCOL_VERSION: Int = 1
    const val SERVICE_UUID: String = "f3a1c0e4-5b2a-4d7f-9c31-8e6a4b0d12f5"
    const val FRAME_UUID: String = "f3a1c0e4-5b2a-4d7f-9c31-8e6a4b0d12f6"
    const val CLOCK_SKEW_MS: Long = 15L * 60L * 1000L

    /**
     * Mesh-1K-AS: custody pools derived from the stored hopCount (no schema change). OWN = rows this phone
     * sealed (hopCount 0: own messages + own receipts), FOREIGN = carried rows (hopCount >= 1).
     * OWN_MAX + FOREIGN_MAX == MAX_BUNDLES. A full pool REFUSES; valid custody is never evicted.
     * Resource-abuse limits only: none of these is keyed on an (unauthenticated) origin id, so they give
     * no Sybil resistance and no cryptographic guarantee.
     */
    const val OWN_MAX: Int = 16
    const val FOREIGN_MAX: Int = 32
    const val FOREIGN_PER_ENCOUNTER: Int = 8
    const val FOREIGN_RATE_MAX: Int = 16
    const val FOREIGN_RATE_WINDOW_MS: Long = 10L * 60L * 1000L

    /**
     * Manufacturer-data marker for the 4-byte encounter nonce. Not a Bluetooth
     * SIG company id. The nonce has to share a 31-byte legacy advert with the
     * 128-bit service UUID, which leaves no room for a second 128-bit field.
     */
    const val ADVERT_COMPANY_ID: Int = 0xAC7A

    fun expireAt(createdAtMs: Long): Long = createdAtMs + ABSOLUTE_TTL_MS
}
