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

    fun expireAt(createdAtMs: Long): Long = createdAtMs + ABSOLUTE_TTL_MS
}
