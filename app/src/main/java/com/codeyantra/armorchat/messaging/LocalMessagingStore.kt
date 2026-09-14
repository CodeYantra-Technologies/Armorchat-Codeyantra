package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.ImmutableRelayEnvelope

data class OutboundRecord(
    val messageId: String,
    val kind: InnerKind,
    val sessionId: String? = null,
    val epoch: Long? = null,
    val ciphertextB64: String? = null,
    val ciphertextSha256: String? = null,
    val envelope: ImmutableRelayEnvelope? = null,
    val envelopeCanonical: String? = null,
    val expiresAt: Long,
    val createdAt: Long,
    val peerIdentity: String,
    val peerDeviceId: String,
    val peerGeneration: Long,
    val localPlaintext: String? = null,
    val clientSequence: Long? = null,
    val progress: MessageProgress = MessageProgress.initial(),
    val target: InnerBody.Receipt? = null,
    val replyTo: ReplyMetadata? = null,
)

data class InboundRecord(
    val messageId: String,
    val sessionId: String,
    val epoch: Long,
    val ciphertextSha256: String,
    val peerIdentity: String,
    val peerGeneration: Long,
    val peerDeviceId: String,
    val kind: InnerKind,
    val text: String? = null,
    val accepted: Boolean,
    val displayed: Boolean = false,
    val clientSequence: Long? = null,
    val replyTo: ReplyMetadata? = null,
)

data class SeenEnvelope(
    val digest: String,
    val accepted: Boolean,
)

/**
 * In-memory transactional store for the Phase 1D.4 harness.
 * Commit copies state, applies mutations, then publishes the copy.
 * A thrown block or [failNextCommit] leaves the previous snapshot intact.
 */
class LocalMessagingStore {
    data class Snapshot(
        val texts: Map<String, OutboundRecord> = emptyMap(),
        val receiptOutbox: Map<String, OutboundRecord> = emptyMap(),
        val inbound: Map<String, InboundRecord> = emptyMap(),
        val seen: Map<String, SeenEnvelope> = emptyMap(),
        val nextClientSequence: Long = 1,
    )

    private var data = Snapshot()
    var failNextCommit: Boolean = false

    @Synchronized
    fun <T> commit(block: MutableStore.() -> T): T {
        val working = MutableStore(data)
        val result = working.block()
        if (failNextCommit) {
            failNextCommit = false
            error("injected commit failure")
        }
        data = working.snapshot()
        return result
    }

    @Synchronized
    fun snapshot(): Snapshot = data

    @Synchronized
    fun restore(snapshot: Snapshot) {
        data = snapshot
    }

    @Synchronized
    fun text(messageId: String): OutboundRecord? = data.texts[messageId]

    @Synchronized
    fun texts(): List<OutboundRecord> = data.texts.values.toList()

    @Synchronized
    fun inbound(messageId: String): InboundRecord? = data.inbound[messageId]

    @Synchronized
    fun inbounds(): List<InboundRecord> = data.inbound.values.toList()

    @Synchronized
    fun receipt(messageId: String): OutboundRecord? = data.receiptOutbox[messageId]

    @Synchronized
    fun receipts(): List<OutboundRecord> = data.receiptOutbox.values.toList()

    @Synchronized
    fun seen(key: String): SeenEnvelope? = data.seen[key]

    @Synchronized
    fun nextSequence(): Long = data.nextClientSequence

    class MutableStore(snapshot: Snapshot) {
        private val texts = snapshot.texts.toMutableMap()
        private val receiptOutbox = snapshot.receiptOutbox.toMutableMap()
        private val inbound = snapshot.inbound.toMutableMap()
        private val seen = snapshot.seen.toMutableMap()
        var nextClientSequence: Long = snapshot.nextClientSequence

        fun putText(record: OutboundRecord) {
            texts[record.messageId] = record
        }

        fun putReceipt(record: OutboundRecord) {
            receiptOutbox[record.messageId] = record
        }

        fun putInbound(record: InboundRecord) {
            inbound[record.messageId] = record
        }

        fun putSeen(key: String, value: SeenEnvelope) {
            seen[key] = value
        }

        fun removeSeen(key: String) {
            seen.remove(key)
        }

        fun text(messageId: String): OutboundRecord? = texts[messageId]
        fun receipt(messageId: String): OutboundRecord? = receiptOutbox[messageId]
        fun inbound(messageId: String): InboundRecord? = inbound[messageId]
        fun seen(key: String): SeenEnvelope? = seen[key]
        fun texts(): Collection<OutboundRecord> = texts.values
        fun receipts(): Collection<OutboundRecord> = receiptOutbox.values

        fun takeSequence(): Long {
            val value = nextClientSequence
            nextClientSequence += 1
            return value
        }

        fun snapshot(): Snapshot = Snapshot(
            texts = texts.toMap(),
            receiptOutbox = receiptOutbox.toMap(),
            inbound = inbound.toMap(),
            seen = seen.toMap(),
            nextClientSequence = nextClientSequence,
        )
    }

    companion object {
        fun seenKey(peerIdentity: String, peerGeneration: Long, sessionId: String, messageId: String): String =
            "$peerIdentity|$peerGeneration|$sessionId|$messageId"
    }
}
