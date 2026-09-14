package com.codeyantra.armorchat.messaging

enum class SecureSessionState {
    NONE,
    PROVISIONING,
    AWAITING_PEER,
    ESTABLISHING,
    ESTABLISHED,
    FAILED,
}

data class SecureSessionView(
    val state: SecureSessionState = SecureSessionState.NONE,
    val localRouteQr: String? = null,
    val composerAllowed: Boolean = false,
    val setupHint: String? = null,
    val relayOrigin: String? = null,
)

sealed interface RouteImportResult {
    data class Imported(val peerPin: String, val relayOrigin: String) : RouteImportResult
    data object NeedsIdentityPin : RouteImportResult
    data object Rejected : RouteImportResult
}
