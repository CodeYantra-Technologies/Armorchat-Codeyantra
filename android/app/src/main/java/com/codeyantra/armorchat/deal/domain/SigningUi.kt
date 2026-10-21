package com.codeyantra.armorchat.deal.domain

/**
 * Which debug signing actions the current stage still allows.
 * The screen is not the authority. The workflow checks the same events again.
 */
object SigningUi {
    fun canReview(stage: SigningStage, role: PartyRole): Boolean =
        role == PartyRole.COUNTERPARTY && SigningWorkflow.allows(stage, SigningEvent.PARTY_B_VALIDATED)

    fun canConfirmName(stage: SigningStage, ownNameConfirmed: Boolean): Boolean =
        !ownNameConfirmed && SigningWorkflow.allows(stage, SigningEvent.CONFIRM_OWN_NAME)

    fun canSign(stage: SigningStage, ownSignature: Boolean, proven: Boolean): Boolean =
        !ownSignature &&
            !proven &&
            (stage == SigningStage.READY_TO_SIGN || stage == SigningStage.PARTIALLY_SIGNED) &&
            SigningWorkflow.allows(stage, SigningEvent.SIGN)

    fun canRevise(stage: SigningStage, role: PartyRole, proven: Boolean): Boolean =
        role == PartyRole.PROPOSER &&
            !proven &&
            stage != SigningStage.FULLY_SIGNED &&
            SigningWorkflow.allows(stage, SigningEvent.REVISE)

    fun canReject(stage: SigningStage, proven: Boolean): Boolean =
        !proven &&
            stage != SigningStage.FULLY_SIGNED &&
            SigningWorkflow.allows(stage, SigningEvent.REJECT)
}
