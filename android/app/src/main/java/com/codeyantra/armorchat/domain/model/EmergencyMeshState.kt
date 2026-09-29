package com.codeyantra.armorchat.domain.model

enum class EmergencyMeshStatus(
    val displayTitle: String,
    val description: String,
) {
    UNAVAILABLE("Unavailable", "This phone cannot open Bluetooth"),
    STANDBY("Standby", "Saved on this phone until you listen again"),
    SEARCHING("Listening", "Bluetooth is watching for a nearby ArmorChat device"),
    CONNECTED("In contact", "A nearby device is carrying sealed messages"),
    RELAYING("Carrying", "Handing a sealed message to a nearby device"),
    INTERNET_RESTORED("Relay", "Internet relay is preferred when it accepts the message"),
}

data class MeshHop(
    val nodeId: String,
    val label: String,
    val role: String,
    val isPrimaryRelay: Boolean = false,
)
