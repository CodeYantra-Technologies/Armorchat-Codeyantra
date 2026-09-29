package com.codeyantra.armorchat.core.mesh

import com.codeyantra.armorchat.domain.model.EmergencyMeshStatus

data class MeshRuntime(
    val armed: Boolean = false,
    val status: EmergencyMeshStatus = EmergencyMeshStatus.STANDBY,
    val holding: Int = 0,
    val emergencyHolding: Int = 0,
    val soonestExpireAtMs: Long? = null,
    val peersSeen: Int = 0,
    val forwarded: Int = 0,
    val deliveredHere: Int = 0,
    val lastPath: String = "Bluetooth",
    val radioDetail: String = "",
    val note: String = "Off. A message stays on this phone only after Emergency Mesh is on.",
)
