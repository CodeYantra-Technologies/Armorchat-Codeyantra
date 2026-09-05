package com.codeyantra.armorchat.domain.model

/**
 * Local 1:1 conversation shell. Messaging transport and E2EE are not enabled yet.
 */
data class Conversation(
    val id: String,
    val contactId: String,
    val title: String?,
    val updatedAtEpochMs: Long,
)
