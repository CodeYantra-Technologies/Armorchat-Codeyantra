package com.codeyantra.armorchat.domain.model

enum class EmergencyMeshStatus(
    val displayTitle: String,
    val description: String,
) {
    UNAVAILABLE("Unavailable", "This phone cannot open Bluetooth"),
    STANDBY("Standby", "Saved on this phone until you listen again"),
    SEARCHING("Searching", "Emergency Mesh is on. Bluetooth is starting."),
    LISTENING("Listening", "Bluetooth is watching for a nearby ArmorChat device"),
    CONNECTING("Connecting", "A nearby device was found. Opening the Bluetooth link."),
    CONNECTED("In contact", "A Bluetooth link to a nearby device is open."),
    RELAYING("Carrying", "Handing a sealed message to a nearby device"),
    INTERNET_RESTORED("Relay", "Internet relay is preferred when it accepts the message"),
}

data class MeshHop(
    val nodeId: String,
    val label: String,
    val role: String,
    val isPrimaryRelay: Boolean = false,
)
