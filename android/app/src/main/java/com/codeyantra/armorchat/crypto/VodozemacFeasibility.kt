package com.codeyantra.armorchat.crypto

/** Isolated local harness; never wired to CryptoEngine or transport.
 * Native output contains both staged state and output. The caller must commit them
 * together before exposing output. Message type and full packet come from vodozemac.
 */
object VodozemacFeasibility {
    init { System.loadLibrary("armorchat_vodozemac") }
    external fun transact(snapshot: ByteArray, request: String): ByteArray
}
