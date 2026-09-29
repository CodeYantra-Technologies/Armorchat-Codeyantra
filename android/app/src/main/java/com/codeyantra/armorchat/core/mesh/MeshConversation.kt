package com.codeyantra.armorchat.core.mesh

/**
 * Ordered encounter so two phones never wait on the same write.
 * The higher nonce is the initiator. Bundles move only after both phases
 * have been seen, so each side can skip ids the other already holds.
 */
object MeshConversation {
    suspend fun run(
        initiator: Boolean,
        localPhase: ByteArray,
        send: suspend (ByteArray) -> Unit,
        receive: suspend () -> ByteArray,
        bundlesFor: suspend (remotePhase: ByteArray) -> ByteArray,
    ): Pair<ByteArray, ByteArray> {
        return if (initiator) {
            send(localPhase)
            val remotePhase = receive()
            val localBundles = bundlesFor(remotePhase)
            send(localBundles)
            remotePhase to receive()
        } else {
            val remotePhase = receive()
            send(localPhase)
            val localBundles = bundlesFor(remotePhase)
            val remoteBundles = receive()
            send(localBundles)
            remotePhase to remoteBundles
        }
    }
}
