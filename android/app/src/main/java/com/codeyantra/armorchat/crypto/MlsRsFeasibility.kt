package com.codeyantra.armorchat.crypto

/** Isolated mls-rs 0.56.0 JNI. Not wired to CryptoEngine or production chat. */
object MlsRsFeasibility {
    init {
        System.loadLibrary("armorchat_mlsrs")
    }

    external fun nativeProbe(): Int
    external fun transact(snapshot: ByteArray, request: String): ByteArray
}
