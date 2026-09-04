package com.codeyantra.armorchat.core.common

import javax.inject.Inject
import javax.inject.Singleton

fun interface AppClock {
    fun nowEpochMs(): Long
}

@Singleton
class SystemAppClock @Inject constructor() : AppClock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}
