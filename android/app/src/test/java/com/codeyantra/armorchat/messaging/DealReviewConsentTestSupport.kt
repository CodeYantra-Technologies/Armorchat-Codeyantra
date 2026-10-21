package com.codeyantra.armorchat.messaging

/**
 * Phase 5.4D test helpers: record local review consent for the current
 * complete document, then perform the gated action. Test-only.
 */
internal suspend fun DealChatBridge.reviewWithConsent(): String {
    recordBoundReview()
    return reviewBoundDeal()
}

internal suspend fun DealChatBridge.confirmNameWithConsent(legalName: String): String {
    recordBoundReview()
    return confirmBoundName(legalName)
}

internal suspend fun DealChatBridge.signWithConsent(): String {
    recordBoundReview()
    return signBoundDeal()
}
