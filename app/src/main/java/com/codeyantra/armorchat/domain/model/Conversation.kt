package com.codeyantra.armorchat.domain.model

data class Conversation(
    val id: String,
    val title: String?,
    val updatedAtEpochMs: Long,
)
