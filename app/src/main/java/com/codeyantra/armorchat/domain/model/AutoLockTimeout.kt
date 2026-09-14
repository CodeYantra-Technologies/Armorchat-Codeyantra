package com.codeyantra.armorchat.domain.model

enum class AutoLockTimeout(val timeoutMs: Long) {
    IMMEDIATELY(0L),
    AFTER_1_MIN(60_000L),
    AFTER_5_MIN(300_000L),
    AFTER_15_MIN(900_000L),
}
