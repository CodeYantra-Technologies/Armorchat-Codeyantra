package com.codeyantra.armorchat.deal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DealLabGateTest {
    @Test
    fun releaseBuildHidesTheLabEvenWhenProductionUiIsOff() {
        assertFalse(DealLabGate.visible(debugBuild = false, productionUiEnabled = false))
        assertFalse(DealLabGate.visible(debugBuild = false, productionUiEnabled = true))
    }

    @Test
    fun productionFlagHidesTheLabEvenInADebugBuild() {
        assertFalse(DealLabGate.visible(debugBuild = true, productionUiEnabled = true))
    }

    @Test
    fun debugLabIsVisibleOnlyWhileProductionUiStaysOff() {
        assertFalse(DealFeature.PRODUCTION_UI_ENABLED)
        assertTrue(DealLabGate.visible(debugBuild = true, productionUiEnabled = DealFeature.PRODUCTION_UI_ENABLED))
    }
}
