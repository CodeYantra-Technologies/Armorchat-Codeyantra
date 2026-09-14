package com.codeyantra.armorchat.messaging

internal suspend fun confirmSharedMls(
    initiator: SessionOrchestrator,
    joiner: SessionOrchestrator,
    initiatorPin: String,
    joinerPin: String,
) {
    initiator.establish(joinerPin)
    joiner.tryAdoptInvite(initiatorPin)
    joiner.sync(initiatorPin)
    initiator.sync(joinerPin)
    joiner.sync(initiatorPin)
    initiator.sync(joinerPin)
}
