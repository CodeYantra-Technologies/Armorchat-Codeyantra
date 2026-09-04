package com.codeyantra.armorchat.domain.model

/**
 * Reserved lifecycle for future outbound / inbound messages.
 * Network states must only be set by a real transport, never faked.
 */
enum class MessageStatus {
    QUEUED,
    ENCRYPTING,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}
