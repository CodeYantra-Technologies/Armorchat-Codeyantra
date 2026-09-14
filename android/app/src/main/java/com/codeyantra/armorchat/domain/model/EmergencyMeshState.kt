package com.codeyantra.armorchat.domain.model

enum class EmergencyMeshStatus(
    val displayTitle: String,
    val description: String,
) {
    UNAVAILABLE("Coming Soon", "Experimental — unavailable in this build"),
    STANDBY("Standby", "Monitoring network connectivity"),
    SEARCHING("Searching", "Searching for nearby secure routes"),
    CONNECTED("Connected", "Connected to local mesh route"),
    RELAYING("Relaying", "Relaying encrypted payload"),
    INTERNET_RESTORED("Primary Relay", "Internet restored — primary relay preferred"),
}

data class MeshHop(
    val nodeId: String,
    val label: String,
    val role: String,
    val isPrimaryRelay: Boolean = false,
)
