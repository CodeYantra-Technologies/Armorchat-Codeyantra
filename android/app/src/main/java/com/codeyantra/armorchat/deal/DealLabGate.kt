package com.codeyantra.armorchat.deal

/**
 * Debug-lab visibility. The lab is shown only for a debug build while production
 * Armor Deal UI stays disabled. Release callers pass `debugBuild = false`.
 * This type has no lab copy, route, or action.
 */
object DealLabGate {
    fun visible(debugBuild: Boolean, productionUiEnabled: Boolean): Boolean {
        return debugBuild && !productionUiEnabled
    }
}
