package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.ImmutableRelayEnvelope
import com.codeyantra.armorchat.core.network.MailboxRegistration
import com.codeyantra.armorchat.core.network.RelayClient
import com.codeyantra.armorchat.core.network.RelayFetchResult
import com.codeyantra.armorchat.core.network.RelayProvider
import com.codeyantra.armorchat.core.network.RelayRegistry
import com.codeyantra.armorchat.core.network.ServicePolicyController
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.codeyantra.armorchat.messaging.ReplyMetadata

data class LiveLink(
    val peer: TwoPhonePeer,
    val receipts: ReceiptMessagingPeer,
)

fun interface MessagingLinkFactory {
    fun create(
        signer: IdentitySigner,
        relay: RelayClient,
        nowSeconds: () -> Long,
        engine: MlsEngine?,
    ): LiveLink
}

object DefaultMessagingLinkFactory : MessagingLinkFactory {
    override fun create(
        signer: IdentitySigner,
        relay: RelayClient,
        nowSeconds: () -> Long,
        engine: MlsEngine?,
    ): LiveLink {
        val peer = TwoPhonePeer(signer, relay, nowSeconds, engine = engine)
        return LiveLink(peer, ReceiptMessagingPeer(peer, nowSeconds))
    }
}

data class PersistedLink(
    val peerPin: String,
    val relayOrigin: String,
    val mailbox: MailboxRegistration?,
    val deviceId: String,
    val binding: String?,
    val localRouteQr: String?,
    val peerRouteQr: String?,
    val mlsSnapshot: ByteArray,
    val groupCreated: Boolean,
    val state: SecureSessionState,
    val sessionId: String? = null,
)

/**
 * Automates mailbox, KeyPackage, signed route, MLS setup, send/fetch/receipts.
 * Bootstrap origin comes from [RelayRegistry]; established sessions use the
 * origin on the verified peer route.
 */
class SessionOrchestrator(
    private val registry: RelayRegistry,
    private val provider: RelayProvider,
    private val policy: ServicePolicyController,
    private val factory: MessagingLinkFactory,
    private val nowSeconds: () -> Long,
) {
    private val links = linkedMapOf<String, LiveLink>()
    private var sharedMailbox: MailboxRegistration? = null
    private var sharedEngine: MlsEngine? = null
    private val lastEstablishAtMs = HashMap<String, Long>()
    private val establishBackoffMs = HashMap<String, Long>()

    fun link(peerPin: String): LiveLink? = links[peerPin]

    fun sessionView(selfPin: String, peerPin: String): SecureSessionView {
        val link = links[peerPin]
        val blocked = policy.blocksNetwork()
        if (link == null) {
            return SecureSessionView(
                state = SecureSessionState.NONE,
                composerAllowed = false,
                setupHint = "Securing chat…",
            )
        }
        val state = stateOf(link)
        val composer = state == SecureSessionState.ESTABLISHED && !blocked
        val hint = when {
            blocked -> null
            state == SecureSessionState.ESTABLISHED -> null
            else -> "Securing chat…"
        }
        return SecureSessionView(
            state = state,
            localRouteQr = null,
            composerAllowed = composer,
            setupHint = hint,
            relayOrigin = link.peer.relayOrigin,
        )
    }

    suspend fun provision(signer: IdentitySigner, peerPin: String): LiveLink {
        links[peerPin]?.let { return it }
        val relay = provider.bootstrapClient()
        SafeLog.assertSafe("provision via ${relay.originHttps}")
        val link = factory.create(signer, relay, nowSeconds, engine = sharedEngine)
        sharedEngine = link.peer.engine
        link.peer.pinnedPeer = peerPin
        if (link.peer.snapshot().isEmpty()) {
            link.peer.createMlsDevice()
        }
        sharedMailbox?.let { link.peer.restoreMailbox(it) }
        if (sharedMailbox == null) {
            links.values.firstOrNull()?.peer?.mailbox?.let { existing ->
                link.peer.restoreMailbox(existing)
            }
        }
        link.peer.registerAndPublish()
        sharedMailbox = link.peer.mailbox
        links[peerPin] = link
        return link
    }

    suspend fun provisionInvite(signer: IdentitySigner): LiveLink {
        links[INVITE_PEER]?.let {
            ensureInviteMlsReady()
            return it
        }
        val relay = provider.bootstrapClient()
        SafeLog.assertSafe("provision invite via ${relay.originHttps}")
        val link = factory.create(signer, relay, nowSeconds, engine = sharedEngine)
        sharedEngine = link.peer.engine
        link.peer.pinnedPeer = INVITE_PEER
        SafeLog.qr("relay_origin=${relay.originHttps}")
        try {
            link.peer.createMlsDevice()
        } catch (err: Exception) {
            SafeLog.qr("step=mls_device_create result=FAIL")
            throw err
        }
        sharedMailbox?.let { link.peer.restoreMailbox(it) }
        if (sharedMailbox == null) {
            links.values.firstOrNull()?.peer?.mailbox?.let { existing ->
                link.peer.restoreMailbox(existing)
            }
        }
        // Retain partial provisioning so Retry cannot register another mailbox.
        links[INVITE_PEER] = link
        try {
            link.peer.registerAndPublish()
        } finally {
            sharedMailbox = link.peer.mailbox
        }
        return link
    }

    fun rememberMailbox(mailbox: MailboxRegistration) {
        if (sharedMailbox == null) sharedMailbox = mailbox
    }

    fun attach(peerPin: String, link: LiveLink) {
        link.peer.pinnedPeer = peerPin
        links[peerPin] = link
    }

    fun localRouteQr(peerPin: String): String? {
        val link = links[peerPin] ?: return null
        return runCatching { link.peer.exportRoute(peerPin).toQr() }.getOrNull()
    }

    fun exportContactV2(selfPin: String): com.codeyantra.armorchat.domain.identity.ContactQrV2? {
        unused(selfPin)
        val link = links[INVITE_PEER] ?: links.values.firstOrNull() ?: return null
        return runCatching { link.peer.exportContactV2() }.getOrNull()
    }

    suspend fun publishInviteV3(
        signer: IdentitySigner,
        token: String,
        handle: String?,
        displayName: String?,
        previousToken: String? = null,
    ): com.codeyantra.armorchat.domain.identity.ContactBundleV3 {
        val link = provisionInvite(signer)
        val box = link.peer.mailbox ?: error("mailbox required")
        val client = provider.clientFor(link.peer.relayOrigin)
        link.peer.registerAndPublish()
        val bundle = try {
            link.peer.exportContactBundleV3(token, handle, displayName).also {
                SafeLog.event("stage=BUNDLE_SIGNED result=PASS")
            }
        } catch (err: Exception) {
            SafeLog.event("stage=BUNDLE_SIGNED result=FAIL exception=${err::class.java.name}")
            throw err
        }
        val ok = client.putInvite(box.ownerToken, token, bundle.toJson(), bundle.expiresAt)
        check(ok) { "invite publish failed" }
        previousToken?.let {
            runCatching { client.revokeInvite(box.ownerToken, it) }
        }
        return bundle
    }

    suspend fun lookupInviteV3(token: String): com.codeyantra.armorchat.domain.identity.ContactBundleV3 {
        val canonical = com.codeyantra.armorchat.domain.identity.InviteToken.parseCanonical(token)
        val raw = provider.bootstrapClient().lookupInvite(canonical)
            ?: error("unknown invite")
        return com.codeyantra.armorchat.domain.identity.ContactBundleV3.parse(
            raw = raw,
            token = canonical,
            nowEpochSeconds = nowSeconds(),
            relayRegistry = registry,
        )
    }

    suspend fun redeemInviteV3(token: String, claimId: String? = null): com.codeyantra.armorchat.domain.identity.InviteRedemption {
        val canonical = com.codeyantra.armorchat.domain.identity.InviteToken.parseCanonical(token)
        val claim = claimId ?: com.codeyantra.armorchat.domain.identity.Base64Url.encode(
            ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
        )
        val raw = provider.bootstrapClient().redeemInvite(canonical, claim)
            ?: error("unknown invite")
        val bundle = com.codeyantra.armorchat.domain.identity.ContactBundleV3.parse(
            raw = raw.bundleJson,
            token = canonical,
            nowEpochSeconds = nowSeconds(),
            relayRegistry = registry,
        )
        verifyKeyPackageBinding(raw.binding, bundle.publicKeyBase64Url)
        return com.codeyantra.armorchat.domain.identity.InviteRedemption(
            bundle = bundle,
            keyPackageId = raw.keyPackageId,
            keyPackageBinding = raw.binding,
            bootstrapGrant = raw.grant,
        )
    }

    suspend fun importPeerContactV3(
        redemption: com.codeyantra.armorchat.domain.identity.InviteRedemption,
        signer: IdentitySigner,
    ): RouteImportResult {
        val bundle = redemption.bundle
        if (!registry.isAuthorizedOrigin(bundle.relayOrigin)) {
            return RouteImportResult.Rejected
        }
        val inviterPin = bundle.publicKeyBase64Url
        verifyKeyPackageBinding(redemption.keyPackageBinding, inviterPin)
        val route = RouteInvitation(
            relayOrigin = bundle.relayOrigin,
            mailboxId = bundle.mailboxId,
            submitCapability = redemption.bootstrapGrant.token,
            deviceId = bundle.deviceId,
            generation = 1,
            inviterPin = inviterPin,
            recipientPin = signer.pin,
            revision = 1,
            issuedAt = bundle.issuedAt,
            expiresAt = redemption.bootstrapGrant.expiresAt,
            keyPackageBinding = redemption.keyPackageBinding,
            signature = bundle.signature,
        )
        val existing = links[inviterPin]
        existing?.peer?.markGroupUnusable()
        val link = existing ?: provision(signer, inviterPin)
        return try {
            link.peer.importRouteObj(route, inviterPin, freshKp = true)
            RouteImportResult.Imported(inviterPin, bundle.relayOrigin)
        } catch (_: Exception) {
            RouteImportResult.Rejected
        }
    }

    suspend fun adoptIncomingHandshakes(): List<String> {
        val invite = links[INVITE_PEER] ?: return emptyList()
        if (policy.blocksNetwork()) return emptyList()
        val fetched = when (val result = invite.peer.fetchInboxResult()) {
            is com.codeyantra.armorchat.core.network.RelayFetchResult.Leased -> {
                SafeLog.event("envelope_fetch_ok", "count=${result.envelopes.size}")
                result.envelopes
            }
            else -> {
                SafeLog.event("envelope_fetch_empty")
                return emptyList()
            }
        }
        val adopted = mutableListOf<String>()
        val handshakes = fetched.filter {
            it.payloadType == ImmutableRelayEnvelope.PAYLOAD_HANDSHAKE &&
                it.senderCredentialCanonical != null
        }
        for (item in handshakes) {
            val cred = item.senderCredentialCanonical ?: continue
            val pin = runCatching { pinFromKeyPackageBinding(cred) }.getOrElse { err ->
                SafeLog.event("stage=MLS_ADOPT_TRACE result=FAIL reason=pin_derive exception=${err::class.java.name}")
                null
            } ?: continue
            val verified = runCatching {
                verifyKeyPackageBinding(cred, pin)
                true
            }.getOrElse { err ->
                SafeLog.event("stage=MLS_ADOPT_TRACE result=FAIL reason=binding_verify peer=${SafeLog.shortRef(pin)} exception=${err::class.java.name}")
                false
            }
            if (!verified) {
                SafeLog.failure("mls_join", "binding")
                continue
            }
            if (links[pin]?.peer?.sessionConfirmed == true) {
                SafeLog.event("stage=MLS_ADOPT_TRACE result=SKIP reason=already_confirmed peer=${SafeLog.shortRef(pin)}")
                continue
            }
            SafeLog.event("stage=MLS_ADOPT_TRACE result=PASS reason=attempting peer=${SafeLog.shortRef(pin)}")
            if (tryAdoptInvite(pin)) {
                SafeLog.event("mls_join_ok", "peer=${SafeLog.shortRef(pin)}")
                adopted += pin
            } else {
                SafeLog.event("stage=MLS_ADOPT_TRACE result=FAIL reason=try_adopt_returned_false peer=${SafeLog.shortRef(pin)}")
            }
        }
        return adopted
    }

    suspend fun tryAdoptInvite(peerPin: String): Boolean {
        val invite = links[INVITE_PEER] ?: return false
        if (policy.blocksNetwork()) return false
        val fetched = when (val result = invite.peer.fetchInboxResult()) {
            is com.codeyantra.armorchat.core.network.RelayFetchResult.Leased -> result.envelopes
            else -> return false
        }
        return try {
            val existing = links[peerPin]
            if (existing?.peer?.sessionConfirmed == true) return true
            val link = existing ?: factory.create(
                invite.peer.signer,
                provider.clientFor(invite.peer.relayOrigin),
                nowSeconds,
                engine = invite.peer.engine,
            ).also { created ->
                invite.peer.mailbox?.let { created.peer.restoreMailbox(it) }
                invite.peer.binding?.let { created.peer.restoreBinding(it) }
                created.peer.restoreDeviceId(invite.peer.deviceId)
                created.peer.pinnedPeer = peerPin
                links[peerPin] = created
            }
            val joined = joinOrRecover(link, fetched, peerPin)
            if (!joined) {
                if (fetched.any { it.payloadType == ImmutableRelayEnvelope.PAYLOAD_HANDSHAKE }) {
                    SafeLog.event("stage=MLS_ADOPT_TRACE result=FAIL reason=join_or_recover_no_match peer=${SafeLog.shortRef(peerPin)}")
                }
                existing?.let { links[peerPin] = it }
                return false
            }
            SafeLog.event("mls_join_ok", "peer=${SafeLog.shortRef(peerPin)}")
            for (item in fetched.filter { it.payloadType == ImmutableRelayEnvelope.PAYLOAD_APPLICATION }) {
                runCatching {
                    link.receipts.processLeased(item, consumeOnSuccess = true)
                }
            }
            if (link.peer.peerRoute != null) {
                runCatching { link.receipts.sendSetupRoute() }
            }
            true
        } catch (err: Exception) {
            SafeLog.failure("mls_join", mlsCategory(err))
            false
        }
    }

    suspend fun importPeerContactV2(
        payload: com.codeyantra.armorchat.domain.identity.ContactQrV2,
        signer: IdentitySigner,
    ): RouteImportResult {
        if (!registry.isAuthorizedOrigin(payload.relayOrigin)) {
            return RouteImportResult.Rejected
        }
        val inviterPin = payload.publicKeyBase64Url
        val route = RouteInvitation(
            relayOrigin = payload.relayOrigin,
            mailboxId = payload.mailboxId,
            submitCapability = payload.submitCapability,
            deviceId = payload.deviceId,
            generation = 1,
            inviterPin = inviterPin,
            recipientPin = signer.pin,
            revision = 1,
            issuedAt = payload.issuedAt,
            expiresAt = payload.expiresAt,
            keyPackageBinding = payload.keyPackageBinding,
            signature = payload.signature,
        )
        val link = links[inviterPin] ?: provision(signer, inviterPin)
        return try {
            link.peer.importRouteObj(route, inviterPin, freshKp = true)
            RouteImportResult.Imported(inviterPin, payload.relayOrigin)
        } catch (_: Exception) {
            RouteImportResult.Rejected
        }
    }

    suspend fun importPeerRoute(raw: String, signer: IdentitySigner): RouteImportResult {
        if (!RouteInvitation.looksLike(raw)) return RouteImportResult.Rejected
        val parsed = try {
            RouteInvitation.parse(raw, signer.pin, nowSeconds())
        } catch (_: Exception) {
            return RouteImportResult.Rejected
        }
        if (!registry.isAuthorizedOrigin(parsed.relayOrigin)) {
            return RouteImportResult.Rejected
        }
        val link = links[parsed.inviterPin] ?: provision(signer, parsed.inviterPin)
        return try {
            link.peer.importRoute(raw, parsed.inviterPin, freshKp = true)
            RouteImportResult.Imported(parsed.inviterPin, parsed.relayOrigin)
        } catch (_: Exception) {
            RouteImportResult.Rejected
        }
    }

    suspend fun establish(peerPin: String) {
        if (policy.blocksNetwork()) return
        val link = links[peerPin] ?: return
        refreshPolicy(link)
        if (policy.blocksNetwork()) return
        sync(peerPin)
        if (link.peer.sessionConfirmed) return
        reestablishIfNeeded(peerPin, force = true)
    }

    suspend fun ensureInviteMlsReady() {
        val link = links[INVITE_PEER] ?: return
        val emptyBefore = link.peer.mlsSnapshotEmpty()
        link.peer.ensureMlsDevice()
        if (emptyBefore) {
            SafeLog.event("mls_device", "invite=fresh")
        }
        if (link.peer.needsKeyPackagePublish()) {
            runCatching { link.peer.registerAndPublish() }
        }
    }

    suspend fun reestablishIfNeeded(peerPin: String, force: Boolean = false) {
        if (policy.blocksNetwork()) return
        val link = links[peerPin] ?: return
        if (link.peer.sessionConfirmed) return
        if (link.peer.groupCreated) return
        if (!link.peer.shouldInitiateMls()) return
        ensureLocalMlsDevice(link)
        if (!force && !canAttemptEstablish(peerPin)) return
        try {
            link.peer.initiateGroup()
            noteEstablishAttempt(peerPin, ok = true)
            SafeLog.event(
                "mls_establish_ok",
                "peer=${SafeLog.shortRef(peerPin)} fp=${link.peer.sessionFingerprint()}",
            )
            runCatching { link.receipts.sendSetupRoute() }
        } catch (err: Exception) {
            SafeLog.failure("mls_establish", mlsCategory(err))
            noteEstablishAttempt(peerPin, ok = false)
        }
    }

    suspend fun sync(
        peerPin: String,
        persistInbound: (suspend (InboundRecord) -> Unit)? = null,
    ): List<String> {
        val link = links[peerPin] ?: return emptyList()
        if (policy.blocksNetwork()) return emptyList()
        refreshPolicy(link)
        if (policy.blocksNetwork()) return emptyList()
        val fetched = when (val result = link.peer.fetchInboxResult()) {
            is RelayFetchResult.Leased -> {
                policy.noteTransportAvailable()
                result.envelopes
            }
            is RelayFetchResult.ServiceSuspended -> {
                policy.noteTransportSuspended()
                SafeLog.event("envelope_fetch_empty", "suspended")
                return emptyList()
            }
            else -> {
                SafeLog.event("envelope_fetch_empty")
                return emptyList()
            }
        }
        if (!link.peer.groupCreated || !link.peer.sessionConfirmed) {
            val joined = runCatching { joinOrRecover(link, fetched, peerPin) }
                .onFailure { SafeLog.failure("mls_join", mlsCategory(it)) }
                .getOrDefault(false)
            if (!joined && !link.peer.groupCreated) {
                tryAdoptInvite(peerPin)
            }
            if (!link.peer.groupCreated) {
                reestablishIfNeeded(peerPin)
            }
        }
        val remaining = if (link.peer.groupCreated) {
            fetched.filter { it.payloadType == ImmutableRelayEnvelope.PAYLOAD_APPLICATION }
        } else {
            emptyList()
        }
        val before = link.receipts.acceptedPlaintexts()
        for (item in remaining) {
            runCatching {
                link.receipts.processLeased(item, consumeOnSuccess = true, persistInbound = persistInbound)
            }
        }
        if (!policy.blocksNetwork()) {
            runCatching { link.receipts.flushOutbox() }
            if (link.peer.groupCreated && link.peer.peerRoute != null) {
                runCatching { link.receipts.sendSetupRoute() }
            }
        }
        if (link.peer.groupCreated && !link.peer.sessionConfirmed) {
            runCatching { link.receipts.sendSetupRoute() }
        }
        return link.receipts.acceptedPlaintexts() - before.toSet()
    }

    suspend fun sendText(peerPin: String, text: String, replyTo: ReplyMetadata? = null): OutboundRecord {
        check(!policy.blocksNetwork()) { "network unavailable" }
        val link = links[peerPin] ?: error("session missing")
        check(link.peer.sessionConfirmed && link.peer.peerRoute != null) { "session not ready" }
        val record = link.receipts.sendText(text, replyTo = replyTo)
        SafeLog.assertSafe("outbound ${record.progress.status}")
        return record
    }

    suspend fun sendAttachment(peerPin: String, metadataJson: String, replyTo: ReplyMetadata? = null): OutboundRecord {
        check(!policy.blocksNetwork()) { "network unavailable" }
        val link = links[peerPin] ?: error("session missing")
        check(link.peer.sessionConfirmed && link.peer.peerRoute != null) { "session not ready" }
        val record = link.receipts.sendAttachment(metadataJson, replyTo = replyTo)
        SafeLog.assertSafe("outbound ${record.progress.status}")
        return record
    }

    suspend fun flush(peerPin: String) {
        val link = links[peerPin] ?: return
        if (policy.blocksNetwork()) return
        link.receipts.flushOutbox()
    }

    fun status(peerPin: String, messageId: String): MessageStatus? {
        val link = links[peerPin] ?: return null
        return runCatching { link.receipts.status(messageId) }.getOrNull()
    }

    fun persist(peerPin: String): PersistedLink? {
        val link = links[peerPin] ?: return null
        return PersistedLink(
            peerPin = peerPin,
            relayOrigin = link.peer.relayOrigin,
            mailbox = link.peer.mailbox,
            deviceId = link.peer.deviceId,
            binding = link.peer.binding,
            localRouteQr = runCatching { link.peer.exportRoute(peerPin).toQr() }.getOrNull(),
            peerRouteQr = link.peer.peerRoute?.toQr(),
            mlsSnapshot = link.peer.snapshot(),
            groupCreated = link.peer.groupCreated,
            state = stateOf(link),
            sessionId = link.peer.sessionId,
        )
    }

    fun restore(signer: IdentitySigner, saved: PersistedLink, selfPin: String) {
        val relay = provider.clientFor(saved.relayOrigin)
        val engine = if (saved.peerPin == INVITE_PEER) null else sharedEngine
        val link = factory.create(signer, relay, nowSeconds, engine = engine)
        if (saved.peerPin == INVITE_PEER) sharedEngine = link.peer.engine
        link.peer.pinnedPeer = saved.peerPin
        val sharingInviteEngine = saved.peerPin != INVITE_PEER && engine != null
        if (saved.mlsSnapshot.isNotEmpty() && (!sharingInviteEngine || saved.groupCreated)) {
            link.peer.restore(saved.mlsSnapshot)
        }
        saved.mailbox?.let {
            link.peer.restoreMailbox(it)
            rememberMailbox(it)
        }
        saved.binding?.let { link.peer.restoreBinding(it) }
        link.peer.restoreDeviceId(saved.deviceId)
        val usableGroup = saved.groupCreated && saved.mlsSnapshot.isNotEmpty() && !link.peer.mlsSnapshotEmpty()
        val confirmed = usableGroup && saved.state == SecureSessionState.ESTABLISHED
        link.peer.restoreGroupCreated(usableGroup, confirmed)
        if (saved.groupCreated && !usableGroup) {
            link.peer.markGroupUnusable()
            SafeLog.event("mls_stale_session", "peer=${SafeLog.shortRef(saved.peerPin)}")
        }
        if (usableGroup) {
            saved.sessionId?.let { link.peer.sessionId = it }
        }
        if (confirmed) {
            SafeLog.event("mls_shared_fp", "fp=${link.peer.sessionFingerprint()} restored=1")
        }
        saved.peerRouteQr?.let {
            val route = RouteInvitation.parseUnverified(it, selfPin)
            link.peer.importRouteObj(route, saved.peerPin)
        }
        links[saved.peerPin] = link
        unused(selfPin)
    }

    suspend fun refreshPolicy(link: LiveLink? = null) {
        val client = link?.let { provider.clientFor(it.peer.relayOrigin) } ?: provider.bootstrapClient()
        val raw = runCatching { client.fetchNetworkPolicy() }.getOrNull() ?: return
        try {
            policy.applySignedDocument(raw, nowSeconds())
        } catch (_: Exception) {
            policy.rejectUnsignedOrTampered()
        }
    }

    private fun stateOf(link: LiveLink): SecureSessionState = when {
        link.peer.sessionConfirmed && link.peer.groupCreated && link.peer.peerRoute != null ->
            SecureSessionState.ESTABLISHED
        link.peer.peerRoute != null || link.peer.groupCreated -> SecureSessionState.ESTABLISHING
        link.peer.mailbox != null -> SecureSessionState.AWAITING_PEER
        else -> SecureSessionState.PROVISIONING
    }

    private suspend fun joinOrRecover(
        link: LiveLink,
        fetched: List<com.codeyantra.armorchat.core.network.LeasedRelayEnvelope>,
        peerPin: String,
    ): Boolean {
        val welcome = fetched.firstOrNull { item ->
            item.payloadType == ImmutableRelayEnvelope.PAYLOAD_HANDSHAKE &&
                item.senderCredentialCanonical != null &&
                runCatching {
                    verifyKeyPackageBinding(item.senderCredentialCanonical!!, peerPin)
                    true
                }.onSuccess {
                    SharedSessionDiagnostic.stage(SharedSessionDiagnostic.Stage.welcome_sender_verified, peerPin, item.messageId)
                }.onFailure {
                    SharedSessionDiagnostic.stage(SharedSessionDiagnostic.Stage.welcome_sender_verified, peerPin, item.messageId, false, SharedSessionDiagnostic.category(it))
                }.getOrDefault(false)
        }
        if (welcome == null) return false
        return try {
            link.peer.joinWelcome(listOf(welcome), peerPin)
            SafeLog.event("mls_join_ok", "peer=${SafeLog.shortRef(peerPin)} fp=${link.peer.sessionFingerprint()}")
            runCatching { link.receipts.sendSetupRoute() }
            true
        } catch (err: Exception) {
            SafeLog.failure("mls_join", mlsCategory(err))
            runCatching { link.peer.discardHandshake(welcome) }
            false
        }
    }

    private suspend fun ensureLocalMlsDevice(link: LiveLink) {
        val emptyBefore = link.peer.mlsSnapshotEmpty()
        link.peer.ensureMlsDevice()
        if (emptyBefore) {
            SafeLog.event("mls_device", "peer=${SafeLog.shortRef(link.peer.pinnedPeer)}")
        }
        val publisher = links[INVITE_PEER]?.peer ?: link.peer
        if (publisher.needsKeyPackagePublish()) {
            runCatching { publisher.registerAndPublish() }
        }
    }

    private fun canAttemptEstablish(peerPin: String): Boolean {
        val now = nowSeconds() * 1000
        val last = lastEstablishAtMs[peerPin] ?: 0L
        val backoff = establishBackoffMs[peerPin] ?: ESTABLISH_BACKOFF_MIN_MS
        return now - last >= backoff
    }

    private fun noteEstablishAttempt(peerPin: String, ok: Boolean) {
        lastEstablishAtMs[peerPin] = nowSeconds() * 1000
        if (ok) {
            establishBackoffMs.remove(peerPin)
        } else {
            val next = ((establishBackoffMs[peerPin] ?: ESTABLISH_BACKOFF_MIN_MS) * 2)
                .coerceAtMost(ESTABLISH_BACKOFF_MAX_MS)
            establishBackoffMs[peerPin] = next
        }
    }

    private fun mlsCategory(err: Throwable): String {
        val raw = (err.message.orEmpty() + " " + (err.cause?.message.orEmpty())).lowercase()
        return when {
            "empty_snapshot" in raw -> "empty_snapshot"
            "persist_decode" in raw || raw.contains("restore:") -> "snapshot"
            "generate_key_package" in raw || "key_package" in raw -> "key_package"
            "welcome" in raw -> "welcome"
            "join" in raw -> "join"
            "group required" in raw || "group already" in raw -> "group"
            err is SecurityException -> "rejected"
            else -> err::class.java.simpleName ?: "error"
        }
    }

    private fun unused(value: Any?) = Unit

    companion object {
        const val INVITE_PEER = "__local_invite__"
        private const val ESTABLISH_BACKOFF_MIN_MS = 15_000L
        private const val ESTABLISH_BACKOFF_MAX_MS = 120_000L
    }
}
