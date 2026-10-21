package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.deal.domain.DealStateException

/**
 * Phase 5.4D test helpers: record local review consent for the current
 * complete document, then perform the gated action. Test-only.
 *
 * Production actions take an explicit Deal ID. These no-argument wrappers
 * exist so single-deal tests keep calling the latest agreement only.
 */
private fun DealChatBridge.soleDealId(): String =
    signingSnapshot()?.dealId ?: throw DealStateException("The proposal is missing.")

internal suspend fun DealChatBridge.reviewBoundDeal(): String = reviewBoundDeal(soleDealId())

internal suspend fun DealChatBridge.confirmBoundName(legalName: String): String =
    confirmBoundName(soleDealId(), legalName)

internal suspend fun DealChatBridge.signBoundDeal(): String = signBoundDeal(soleDealId())

internal suspend fun DealChatBridge.confirmBoundClosure(): String = confirmBoundClosure(soleDealId())

internal fun DealChatBridge.recordBoundReview(): String = recordBoundReview(soleDealId())

internal suspend fun DealChatBridge.reviseBoundDeal(terms: String): String =
    reviseBoundDeal(soleDealId(), terms)

internal suspend fun DealChatBridge.rejectBoundDeal(note: String): String =
    rejectBoundDeal(soleDealId(), note)

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
