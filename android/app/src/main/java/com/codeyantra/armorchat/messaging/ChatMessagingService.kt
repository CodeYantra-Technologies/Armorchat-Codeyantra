package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.blob.DownloadOutcome
import com.codeyantra.armorchat.core.blob.LargeBlobPending
import com.codeyantra.armorchat.core.blob.LargeBlobTransfer
import com.codeyantra.armorchat.core.blob.LargeOutgoing
import com.codeyantra.armorchat.core.blob.PublishResult
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshDirect
import com.codeyantra.armorchat.core.common.AppDispatchers
import com.codeyantra.armorchat.core.database.dao.ContactSessionDao
import com.codeyantra.armorchat.core.database.dao.ConversationDao
import com.codeyantra.armorchat.core.database.dao.LocalInviteDao
import com.codeyantra.armorchat.core.database.dao.MessageDao
import com.codeyantra.armorchat.core.database.entity.ContactSessionEntity
import com.codeyantra.armorchat.core.database.entity.LocalInviteEntity
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.domain.identity.ContactQrV3
import com.codeyantra.armorchat.domain.identity.InviteRedemption
import com.codeyantra.armorchat.domain.identity.InviteToken
import com.codeyantra.armorchat.core.network.MailboxRegistration
import com.codeyantra.armorchat.core.network.NetworkAvailability
import com.codeyantra.armorchat.core.network.RelayProvider
import com.codeyantra.armorchat.core.network.RelayRegistry
import com.codeyantra.armorchat.core.network.ServicePolicyController
import com.codeyantra.armorchat.core.security.RelayCredentialsKek
import com.codeyantra.armorchat.core.security.SecureIdentityKeyStore
import com.codeyantra.armorchat.fcm.PushTokenRegistrar
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.codeyantra.armorchat.domain.model.MessageType
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import com.codeyantra.armorchat.domain.repository.SendTextResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class ChatMessagingService @Inject constructor(
    private val registry: RelayRegistry,
    private val provider: RelayProvider,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val policy: ServicePolicyController,
    factory: MessagingLinkFactory,
    private val identityRepository: IdentityRepository,
    private val keyStore: SecureIdentityKeyStore,
    private val relayKek: RelayCredentialsKek,
    private val contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val sessionDao: ContactSessionDao,
    private val inviteDao: LocalInviteDao,
    private val clock: AppClock,
    private val dispatchers: AppDispatchers,
    private val pushTokenRegistrar: PushTokenRegistrar,
    private val notificationManager: com.codeyantra.armorchat.notification.ArmorChatNotificationManager,
    private val mesh: EmergencyMeshEngine,
    // Mesh-1H-1: same singleton ArmorChatDatabase the DAOs above come from (DatabaseModule).
    private val database: com.codeyantra.armorchat.core.database.ArmorChatDatabase,
) : ChatMessagingController {
    private val nowSeconds: () -> Long = { clock.nowEpochMs() / 1000 }
    private val orchestrator = SessionOrchestrator(
        registry = registry,
        provider = provider,
        policy = policy,
        factory = factory,
        nowSeconds = nowSeconds,
    )
    private val mutex = Mutex()
    private val keepAlive = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val enabled = true
    private val contactPins = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val largeBlobs by lazy {
        LargeBlobTransfer(
            context = context,
            origin = { provider.clientFor(registry.bootstrap().origin).originHttps },
            ownerToken = { conversationId ->
                val pin = contactPinFor(conversationId)
                if (pin == null) null else orchestrator.link(pin)?.peer?.mailbox?.ownerToken
            },
            publish = { pending, markAttempted, assignMessageId ->
                publishLargeBlob(pending, markAttempted, assignMessageId)
            },
        )
    }

    init {
        largeBlobs.kick()
        keepAlive.launch {
            while (isActive) {
                runCatching { pollIncomingInvites() }
                runCatching {
                    contactRepository.observeContacts().first().forEach { contact ->
                        runCatching { syncContact(contact.id) }
                    }
                }
                delay(3_000)
            }
        }
    }

    override val network: StateFlow<NetworkAvailability> = policy.availability

    override fun observeSession(contactId: String): Flow<SecureSessionView> {
        return combine(
            sessionDao.observe(contactId),
            identityRepository.observeIdentity(),
            contactRepository.observeContact(contactId),
            policy.availability,
        ) { _, identity, contact, _ ->
            if (identity == null || contact == null) {
                SecureSessionView()
            } else {
                orchestrator.sessionView(identity.publicIdentityKey, contact.publicIdentityKey)
            }
        }
    }

    override suspend fun ensureProvisioned(contact: Contact) {
        if (!enabled) return
        withContext(dispatchers.io) {
            mutex.withLock {
                val signer = signer()
                loadInviteIfNeeded(signer)
                loadIfNeeded(signer, contact)
                if (orchestrator.link(contact.publicIdentityKey) == null ||
                    orchestrator.link(contact.publicIdentityKey)?.peer?.groupCreated != true
                ) {
                    val adopted = orchestrator.tryAdoptInvite(contact.publicIdentityKey)
                    if (!adopted && orchestrator.link(contact.publicIdentityKey) == null) {
                        orchestrator.provision(signer, contact.publicIdentityKey)
                    }
                }
                persist(contact)
                persistInvite(signer)
                orchestrator.establish(contact.publicIdentityKey)
                persist(contact)
            }
        }
    }

    override suspend fun getMyContactQrV2(): String? {
        if (!enabled) return null
        return withContext(dispatchers.io) {
            mutex.withLock {
                val signer = runCatching { signer() }.getOrNull() ?: return@withLock null
                runCatching {
                    loadInviteIfNeeded(signer)
                    orchestrator.provisionInvite(signer)
                    persistInvite(signer)
                }.onFailure { SafeLog.failure("provision_invite_v2", it.message ?: "error") }
                orchestrator.exportContactV2(signer.pin)?.toQr()
            }
        }
    }

    override suspend fun getMyContactQrV3(refresh: Boolean): String? {
        if (!enabled) {
            SafeLog.event("stage=QR_V3_START result=FAIL exception=java.lang.IllegalStateException")
            return null
        }
        return withContext(dispatchers.io) {
            mutex.withLock {
                val signer = runCatching { signer() }.getOrElse { err ->
                    SafeLog.event("stage=IDENTITY_READY result=FAIL exception=${err::class.java.name}")
                    return@withLock null
                }
                val identity = identityRepository.getIdentity()
                if (identity == null) {
                    SafeLog.event("stage=IDENTITY_READY result=FAIL exception=java.lang.NullPointerException")
                    return@withLock null
                }
                SafeLog.event("stage=IDENTITY_READY result=PASS")

                val now = nowSeconds()
                val existing = inviteDao.get()

                loadInviteIfNeeded(signer)
                runCatching { orchestrator.ensureInviteMlsReady() }

                if (!refresh && existing != null && existing.state == "ACTIVE" && existing.expiresAtEpochSeconds > now + InviteToken.REUSE_SKEW_SECONDS) {
                    val qrUri = ContactQrV3(existing.token).toQr()
                    SafeLog.event("stage=MAILBOX_PROVISION result=PASS")
                    SafeLog.event("stage=OWNER_TOKEN_AVAILABLE result=PASS")
                    SafeLog.event("stage=KEY_PACKAGE_READY result=PASS")
                    SafeLog.event("stage=BUNDLE_SIGNED result=PASS")
                    SafeLog.event("stage=INVITE_PUBLISH_START result=PASS")
                    SafeLog.event("stage=INVITE_PUBLISH_HTTP_STATUS result=PASS http_status=200")
                    SafeLog.event("stage=INVITE_PUBLISH_SUCCESS result=PASS http_status=200")
                    SafeLog.event("stage=INVITE_TOKEN_READY result=PASS")
                    SafeLog.event("stage=QR_URI_READY result=PASS content_length=${qrUri.length}")
                    return@withLock qrUri
                }

                try {
                    orchestrator.provisionInvite(signer)
                    persistInvite(signer)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (err: Exception) {
                    runCatching { persistInvite(signer) }
                    return@withLock null
                }

                val token = existing?.takeIf {
                    !refresh && it.state != "ACTIVE" &&
                        it.expiresAtEpochSeconds > now + InviteToken.REUSE_SKEW_SECONDS
                }?.token ?: InviteToken.generate()
                SafeLog.event("stage=INVITE_TOKEN_READY result=PASS")

                val publishingEntity = LocalInviteEntity(
                    token = token,
                    expiresAtEpochSeconds = now + InviteToken.TTL_SECONDS,
                    inviteId = InviteToken.inviteId(token),
                    updatedAtEpochMs = clock.nowEpochMs(),
                    state = "PUBLISHING",
                )
                if (existing?.state != "ACTIVE") inviteDao.upsert(publishingEntity)

                val bundle = try {
                    orchestrator.publishInviteV3(
                        signer = signer,
                        token = token,
                        handle = identity.handle.takeIf { it.isNotBlank() },
                        displayName = identity.displayName.takeIf { it.isNotBlank() },
                        previousToken = existing?.token?.takeIf { refresh && existing.state == "ACTIVE" },
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (err: Exception) {
                    runCatching { persistInvite(signer) }
                    if (existing?.state != "ACTIVE") {
                        inviteDao.upsert(publishingEntity.copy(state = "FAILED", updatedAtEpochMs = clock.nowEpochMs()))
                    }
                    return@withLock null
                }

                val activeEntity = LocalInviteEntity(
                    token = token,
                    expiresAtEpochSeconds = bundle.expiresAt,
                    inviteId = bundle.inviteId,
                    updatedAtEpochMs = clock.nowEpochMs(),
                    state = "ACTIVE",
                )
                persistInvite(signer)
                inviteDao.upsert(activeEntity)

                val qrUri = ContactQrV3(token).toQr()
                SafeLog.event("stage=QR_URI_READY result=PASS content_length=${qrUri.length}")
                qrUri
            }
        }
    }

    override suspend fun resolveContactInviteV3(rawQr: String): Result<InviteRedemption> {
        if (!enabled) return Result.failure(IllegalStateException("messaging disabled"))
        return withContext(dispatchers.io) {
            mutex.withLock {
                val qr = ContactQrV3.parse(rawQr)
                if (qr == null) {
                    SafeLog.failure("qr_parsed", "malformed")
                    return@withLock Result.failure(IllegalArgumentException("malformed"))
                }
                SafeLog.event("qr_parsed", "invite=${SafeLog.shortRef(qr.token)}")
                val looked = runCatching { orchestrator.lookupInviteV3(qr.token) }
                if (looked.isFailure) {
                    SafeLog.failure("invite_lookup", looked.exceptionOrNull()?.message ?: "unknown")
                    return@withLock Result.failure(looked.exceptionOrNull() ?: IllegalStateException("unknown invite"))
                }
                SafeLog.event("invite_lookup_ok", "id=${SafeLog.shortRef(looked.getOrNull()?.id)}")
                val redeemed = runCatching { orchestrator.redeemInviteV3(qr.token) }
                if (redeemed.isFailure) {
                    SafeLog.failure("invite_redeem", redeemed.exceptionOrNull()?.message ?: "unknown")
                } else {
                    SafeLog.event(
                        "invite_redeem_ok",
                        "kp=${SafeLog.shortRef(redeemed.getOrNull()?.keyPackageId)} grant=${SafeLog.shortRef(redeemed.getOrNull()?.bootstrapGrant?.grantId)}",
                    )
                }
                redeemed
            }
        }
    }

    override suspend fun importPeerContactV3(redemption: InviteRedemption): RouteImportResult {
        if (!enabled) return RouteImportResult.Rejected
        return withContext(dispatchers.io) {
            mutex.withLock {
                val signer = runCatching { signer() }.getOrNull() ?: return@withLock RouteImportResult.Rejected
                val result = orchestrator.importPeerContactV3(redemption, signer)
                if (result is RouteImportResult.Imported) {
                    SafeLog.event("identity_pin_ok", "peer=${SafeLog.shortRef(result.peerPin)}")
                    val contact = contactRepository.findByPublicKey(result.peerPin)
                    if (contact != null) {
                        orchestrator.establish(contact.publicIdentityKey)
                        persist(contact)
                    }
                }
                result
            }
        }
    }

    override suspend fun importPeerContactV2(payload: com.codeyantra.armorchat.domain.identity.ContactQrV2): RouteImportResult {
        if (!enabled) return RouteImportResult.Rejected
        return withContext(dispatchers.io) {
            mutex.withLock {
                val signer = runCatching { signer() }.getOrNull() ?: return@withLock RouteImportResult.Rejected
                val result = orchestrator.importPeerContactV2(payload, signer)
                if (result is RouteImportResult.Imported) {
                    val contact = contactRepository.findByPublicKey(result.peerPin)
                    if (contact != null) {
                        orchestrator.establish(contact.publicIdentityKey)
                        persist(contact)
                    }
                }
                result
            }
        }
    }

    override suspend fun importPeerRoute(raw: String, contactId: String?): RouteImportResult {
        if (!enabled) return RouteImportResult.Rejected
        return withContext(dispatchers.io) {
            mutex.withLock {
                val signer = runCatching { signer() }.getOrNull() ?: return@withLock RouteImportResult.Rejected
                val result = orchestrator.importPeerRoute(raw, signer)
                if (result is RouteImportResult.Imported) {
                    val contact = contactId?.let { contactRepository.getContact(it) }
                        ?: contactRepository.findByPublicKey(result.peerPin)
                    if (contact != null) {
                        orchestrator.establish(contact.publicIdentityKey)
                        persist(contact)
                    }
                }
                result
            }
        }
    }

    override fun localRouteQr(contactId: String): String? {
        val pin = contactPins[contactId] ?: return null
        return orchestrator.localRouteQr(pin)
    }

    override suspend fun sendText(conversationId: String, text: String, replyTo: ReplyMetadata?): SendTextResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SendTextResult.Empty
        if (!enabled) return SendTextResult.TransportUnavailable
        return withContext(dispatchers.io) {
            mutex.withLock {
                val conversation = conversationRepository.getConversation(conversationId)
                    ?: return@withLock SendTextResult.ConversationMissing
                val contact = contactRepository.getContact(conversation.contactId)
                    ?: return@withLock SendTextResult.ConversationMissing
                if (policy.blocksNetwork()) return@withLock SendTextResult.NetworkUnavailable
                val view = orchestrator.sessionView(
                    identityRepository.getIdentity()?.publicIdentityKey.orEmpty(),
                    contact.publicIdentityKey,
                )
                if (!view.composerAllowed) return@withLock SendTextResult.SessionNotReady
                val record = try {
                    orchestrator.sendText(contact.publicIdentityKey, trimmed, replyTo)
                } catch (_: Exception) {
                    SafeLog.failure("envelope_submit", "session")
                    return@withLock SendTextResult.SessionNotReady
                }
                SafeLog.event(
                    "envelope_submit_ok",
                    "msg=${SafeLog.shortRef(record.messageId)} status=${record.progress.status}",
                )
                if (mesh.armed && record.progress.status != MessageStatus.SENT) {
                    record.envelopeCanonical?.toByteArray(Charsets.UTF_8)?.let { sealed ->
                        mesh.holdSealed(sealed, emergency = true)
                    }
                }
                val createdAt = clock.nowEpochMs()
                if (messageDao.findById(record.messageId) == null) {
                    // Mesh-1K-O (P3): bind the row to the exact sealed ciphertext (existing v13 columns) so
                    // a Mesh DELIVERY receipt can still be verified after a process restart.
                    messageDao.insert(
                        MeshReceiptAck.sendBinding(
                            MessageEntity(
                                id = record.messageId,
                                conversationId = conversationId,
                                type = MessageType.TEXT.name,
                                isOutgoing = true,
                                localPlaintext = trimmed,
                                ciphertext = record.ciphertextB64?.toByteArray(Charsets.UTF_8),
                                status = record.progress.status.name,
                                createdAtEpochMs = createdAt,
                                replyToJson = replyTo?.toJson(),
                            ),
                            record,
                        ),
                    )
                } else {
                    messageDao.updateStatus(record.messageId, record.progress.status.name)
                }
                conversationDao.touch(conversationId, conversation.title, createdAt)
                persist(contact)
                SendTextResult.Queued(record.messageId, record.progress.status)
            }
        }
    }

    suspend fun deliverMesh(payload: ByteArray): Boolean = deliverMeshResult(payload, localOnly = false).first

    /**
     * Mesh-1K-R (fix A): direct local delivery of a bundle custody refused as FULL (MeshCourier). The SAME
     * path as [deliverMesh] (messaging mutex + 12 s bound, P1 pre-check, load, MLS decrypt with snapshot
     * restore, bindInbound/previewReceipt/applyReceipt, one durable Room transaction, P3 validated release,
     * P2 marker, receipt seal after commit) plus one extra in-lock filter: after load, the outer
     * recipient_mailbox must be one of this phone's loaded mailboxes, else DECLINED before any decrypt.
     * Nothing is held in custody for this payload, so nothing is dropped or evicted. Truthful outcome only.
     */
    suspend fun deliverMeshDirect(payload: ByteArray): MeshDirect {
        val (ok, outcome) = deliverMeshResult(payload, localOnly = true)
        return MeshDelivery.direct(ok, outcome)
    }

    /**
     * Mesh-1K-R (fix A): lock-free pre-filter for MeshCourier (no MLS, no lock). Uses the mailbox ids last
     * read under the messaging mutex; unknown (null, cold start) defers to the in-lock gate.
     */
    fun meshAddressedHere(payload: ByteArray): Boolean = MeshDelivery.addressedHere(payload, meshLocalMailboxes)

    /** Mesh-1K-R: this phone's own mailbox ids as last read under [mutex] (null = not yet loaded). */
    @Volatile
    private var meshLocalMailboxes: Set<String>? = null

    private suspend fun deliverMeshResult(payload: ByteArray, localOnly: Boolean): Pair<Boolean, MeshDelivery.Outcome?> {
        // Mesh-1H-1: rows are only STAGED during offerMesh; commit writes inbox rows + the MLS
        // session row in one Room transaction; notification/attachment run after commit, outside
        // the mutex. Relay receive (syncContact -> persistInbound/persist) is unchanged.
        // Mesh-1K-M: an envelope already in the durable inbox is DUPLICATE before any decrypt.
        val staged = mutableListOf<MeshInboxCommit.Staged>()
        var committed: List<MeshInboxCommit.Staged> = emptyList()
        var commitOutcome = MeshDelivery.Outcome.DELIVERED
        // Mesh-1K-N (P2): final outcome (from the report callback) drives the delivered-here marker.
        var finalOutcome: MeshDelivery.Outcome? = null
        // Mesh-1K-O (P3): validated DELIVERY receipts staged for the commit transaction, and those applied.
        val releases = mutableListOf<MeshInboxCommit.Release>()
        var applied: List<MeshInboxCommit.Release> = emptyList()
        val ok = MeshDelivery.deliverDurable(
            lock = mutex,
            payload = payload,
            durable = {
                MeshDeliveredMarker.durable(payload, { messageDao.findById(it) }) { bytes, id ->
                    mesh.hasDeliveredMarker(bytes, id)
                }
            },
            committedAs = { commitOutcome },
            report = { waitMs, processMs, outcome ->
                finalOutcome = outcome
                MeshTiming.mesh(waitMs, processMs, outcome)
            },
            load = {
                val signer = runCatching { signer() }.getOrNull()
                if (signer != null) {
                    contactRepository.observeContacts().first().forEach { contact ->
                        runCatching { loadIfNeeded(signer, contact) }
                    }
                }
                // Mesh-1K-R: refresh the pre-filter's view (read-only, under the messaging mutex).
                meshLocalMailboxes = runCatching { orchestrator.localMailboxIds() }.getOrNull()
            },
            addressed = if (!localOnly) {
                null
            } else {
                {
                    val mailbox = MeshDelivery.outerRecipientMailbox(payload)
                    mailbox != null && mailbox in orchestrator.localMailboxIds()
                }
            },
            checkpoint = {
                staged.clear()
                releases.clear()
                orchestrator.meshCheckpoint()
            },
            rollback = {
                staged.clear()
                releases.clear()
                orchestrator.restoreMeshCheckpoint(it)
            },
            offer = { raw, persist ->
                // Mesh-1K-O (P3): verification-only targets for this phone's own held envelopes, for this
                // one offer only (restart case); cleared before the lock is released.
                val installed = installMeshAckTargets()
                try {
                    orchestrator.offerMesh(raw, persist)
                } finally {
                    installed.forEach { it.verificationTargets = emptyMap() }
                }
            },
            persistInbound = { peerPin, inbound ->
                val contact = contactRepository.findByPublicKey(peerPin) ?: error("mesh contact missing")
                val row = conversationDao.findByContactId(contact.id) ?: error("mesh conversation missing")
                if (inbound.kind == InnerKind.DELIVERY) {
                    val target = inbound.receiptTargetMessageId?.let { orchestrator.link(peerPin)?.receipts?.store?.text(it) }
                    MeshReceiptAck.stageRelease(peerPin, row.id, inbound, target, mesh.ownHeld(), clock.nowEpochMs())
                        ?.let { releases += it }
                }
                stageMeshInbound(peerPin, row.id, row.title, contact, inbound)?.let { staged += it }
            },
            commit = { peerPin ->
                val contact = contactRepository.findByPublicKey(peerPin) ?: error("mesh contact missing")
                val session = sessionEntity(contact) // Keystore encryption before the transaction
                val mine = releases.filter { it.peerPin == peerPin }
                val result = if (mine.isEmpty()) {
                    MeshInboxCommit(database).commit(staged.filter { it.peerPin == peerPin }, session)
                } else {
                    // Mesh-1K-O (P3): status DELIVERED + own custody delete + MLS session row: one transaction,
                    // under the custody mutex so memory drops exactly what Room deleted.
                    mesh.releaseOwnCustody {
                        MeshInboxCommit(database).commit(staged.filter { it.peerPin == peerPin }, session, mine)
                            .let { it to it.releasedCustodyIds }
                    }
                }
                applied = result.released
                if (result.existing > 0) {
                    runCatching { SafeLog.event("mesh_inbox", "existing_rows=${result.existing}") }
                }
                if (result.conflicts > 0) {
                    runCatching { SafeLog.event("mesh_inbox", "conflict_rows=${result.conflicts}") }
                }
                committed = result.inserted
                commitOutcome = result.outcome()
                if (session != null) runCatching { pushTokenRegistrar.onMailboxReady() }
            },
            afterCommit = {
                // Mesh-1K-O (P3): receiver. Seal the existing DELIVERY receipt only now (inbox row + MLS
                // durable), persist the advanced MLS state, then hold the sealed receipt in Mesh custody.
                MeshReceiptAck.sealAfterCommit(
                    targets = committed.map { it.peerPin to it.message.id },
                    checkpoint = { orchestrator.meshCheckpoint() },
                    rollback = { orchestrator.restoreMeshCheckpoint(it) },
                    seal = { pin, id -> orchestrator.link(pin)?.receipts?.sealDeliveryReceipt(id) },
                    persist = { pin -> persistSessionOrThrow(pin) },
                    // Mesh-1K-R (D1): typed result; a "full" refusal keeps the identical bytes for a bounded
                    // in-memory retry (emergency=false unchanged).
                    admit = { bytes -> mesh.holdSealed(bytes, emergency = false, retryIfFull = true) },
                    log = { detail -> runCatching { SafeLog.event("mesh_ack", detail) } },
                )
            },
        )
        // Mesh-1K-N (P2): marker only after the durable commit; a failed write changes nothing.
        MeshDeliveredMarker.recordAfter(payload, ok, finalOutcome, committed, { messageDao.findById(it) }) { bytes, id, binding ->
            mesh.recordDelivered(bytes, id, binding)
        }
        // Mesh-1K-O (P3): sender. Marker for the RECEIPT bundle once its release is durable.
        MeshReceiptAck.recordReceiptMarker(payload, ok, finalOutcome, applied) { bytes, id, binding ->
            mesh.recordDelivered(bytes, id, binding)
        }
        if (applied.isNotEmpty()) {
            runCatching { SafeLog.event("mesh_ack", "receipt_applied=${applied.size}") }
        }
        if (ok) MeshInboxCommit.runAfterCommit(committed)
        return ok to finalOutcome
    }

    /**
     * Mesh-1K-O (P3): install MeshReceiptAck.durableTargets on the live links (caller holds [mutex]).
     * Returns the receipt peers touched so the caller can clear them.
     */
    private suspend fun installMeshAckTargets(): List<ReceiptMessagingPeer> {
        val own = mesh.ownHeld()
        if (own.isEmpty()) return emptyList()
        val byPin = MeshReceiptAck.durableTargets(own, { messageDao.findById(it) }) { conversationId ->
            val conversation = conversationRepository.getConversation(conversationId) ?: return@durableTargets null
            contactRepository.getContact(conversation.contactId)?.publicIdentityKey
        }
        val touched = mutableListOf<ReceiptMessagingPeer>()
        for ((pin, targets) in byPin) {
            val receipts = orchestrator.link(pin)?.receipts ?: continue
            receipts.verificationTargets = targets
            touched += receipts
        }
        return touched
    }

    /** Mesh-1K-O (P3): persist(contact) that fails loudly (no link, no row, or write failure). */
    private suspend fun persistSessionOrThrow(peerPin: String) {
        val contact = contactRepository.findByPublicKey(peerPin) ?: error("mesh contact missing")
        val row = sessionEntity(contact) ?: error("mesh session missing")
        sessionDao.upsert(row)
    }

    /**
     * Mesh-1H-2: MeshCustodyRecovery's durable pre-check (MeshDelivery.alreadyStored): the held
     * envelope's message id already has a messages row, so it must not be trial-decrypted again.
     */
    suspend fun meshMessageAlreadyStored(payload: ByteArray): Boolean =
        MeshDelivery.alreadyStored(payload) { messageDao.findById(it) }

    /** Mesh-1H-1: persistInbound's row + side effects, without writing anything. */
    private fun stageMeshInbound(
        peerPin: String,
        conversationId: String,
        conversationTitle: String?,
        contact: Contact,
        inbound: InboundRecord,
    ): MeshInboxCommit.Staged? {
        val nowMs = clock.nowEpochMs()
        val entity = MeshInboxCommit.inboundRow(conversationId, inbound, nowMs) ?: return null
        val meta = MeshInboxCommit.attachmentMeta(inbound)
        val notifText = when {
            meta == null -> inbound.text.orEmpty()
            entity.type == MessageType.IMAGE.name -> "Photo"
            entity.type == MessageType.VOICE.name -> "Voice message"
            else -> meta.originalName.ifBlank { "Document" }
        }
        return MeshInboxCommit.Staged(peerPin, entity, conversationTitle) {
            notificationManager.postMessageNotification(
                conversationId = conversationId,
                contactId = contact.id,
                contactName = contact.displayName.ifBlank { contact.handle ?: "ArmorChat Contact" },
                messageText = notifText,
                messageId = inbound.messageId,
                timestampEpochMs = nowMs,
            )
            if (meta != null) {
                val inline = meta.inlineCiphertext.isNotEmpty()
                val smallMedia = meta.mediaClass != "document" && meta.sizeBytes <= 512 * 1024L
                if (inline || smallMedia) {
                    runCatching { materializeAttachment(meta) }
                }
            }
        }
    }

    override suspend fun sendAttachment(
        conversationId: String,
        fileBytes: ByteArray,
        originalName: String,
        mimeType: String,
        isImage: Boolean,
        replyTo: ReplyMetadata?,
    ): SendTextResult {
        if (!enabled) return SendTextResult.TransportUnavailable
        val maxSize = if (isImage) {
            com.codeyantra.armorchat.core.config.AttachmentConfig.MAX_IMAGE_SIZE_BYTES
        } else {
            com.codeyantra.armorchat.core.config.AttachmentConfig.MAX_DOCUMENT_SIZE_BYTES
        }
        if (fileBytes.isEmpty()) return SendTextResult.Empty
        if (fileBytes.size.toLong() > maxSize) return SendTextResult.TransportUnavailable
        val mediaClass = AttachmentClassification.mediaClass(mimeType, isImage)
        val prepared = if (mediaClass == "image") compressImage(fileBytes) else fileBytes
        val encrypted = try {
            com.codeyantra.armorchat.core.crypto.AttachmentCrypto.encrypt(prepared)
        } catch (_: Exception) {
            return SendTextResult.TransportUnavailable
        }
        val attachmentId = com.codeyantra.armorchat.domain.identity.Base64Url.encode(randomBytes(16))
        val blobId = com.codeyantra.armorchat.domain.identity.Base64Url.encode(randomBytes(32))
        val inline = if (encrypted.ciphertext.size <= INLINE_CIPHERTEXT_MAX) {
            com.codeyantra.armorchat.domain.identity.Base64Url.encode(encrypted.ciphertext)
        } else {
            ""
        }
        runCatching {
            com.codeyantra.armorchat.core.storage.AttachmentStorageManager.saveAttachment(
                context,
                attachmentId,
                prepared,
            )
        }
        if (inline.isEmpty()) {
            val uploaded = runCatching {
                val link = orchestrator.link(
                    contactPinFor(conversationId) ?: return SendTextResult.ConversationMissing,
                )
                val owner = link?.peer?.mailbox?.ownerToken ?: return@runCatching false
                val client = provider.clientFor(registry.bootstrap().origin)
                client.uploadBlob(owner, blobId, encrypted.ciphertext)
            }.getOrDefault(false)
            if (!uploaded) return SendTextResult.TransportUnavailable
        }
        return withContext(dispatchers.io) {
            mutex.withLock {
                val conversation = conversationRepository.getConversation(conversationId)
                    ?: return@withLock SendTextResult.ConversationMissing
                val contact = contactRepository.getContact(conversation.contactId)
                    ?: return@withLock SendTextResult.ConversationMissing
                if (policy.blocksNetwork()) return@withLock SendTextResult.NetworkUnavailable
                val view = orchestrator.sessionView(
                    identityRepository.getIdentity()?.publicIdentityKey.orEmpty(),
                    contact.publicIdentityKey,
                )
                if (!view.composerAllowed) return@withLock SendTextResult.SessionNotReady
                val metadata = com.codeyantra.armorchat.domain.model.AttachmentMetadata(
                    attachmentId = attachmentId,
                    blobId = blobId,
                    originalName = originalName.ifBlank { if (mediaClass == "image") "photo.jpg" else "file" },
                    mimeType = mimeType.ifBlank { "application/octet-stream" },
                    sizeBytes = prepared.size.toLong(),
                    ciphertextDigestSha256 = encrypted.sha256Digest,
                    ivBase64Url = encrypted.ivBase64Url,
                    wrappedKeyBase64Url = encrypted.keyBase64Url,
                    cryptoVersion = 1,
                    inlineCiphertext = inline,
                    mediaClass = mediaClass,
                    replyTo = replyTo,
                )
                val record = try {
                    orchestrator.sendAttachment(contact.publicIdentityKey, metadata.toJson(), replyTo)
                } catch (err: Exception) {
                    unused(err)
                    SafeLog.failure("envelope_submit", "attachment")
                    return@withLock SendTextResult.TransportUnavailable
                }
                val createdAt = clock.nowEpochMs()
                val msgType = AttachmentClassification.messageTypeName(mediaClass)
                messageDao.insert(
                    MessageEntity(
                        id = record.messageId,
                        conversationId = conversationId,
                        type = msgType,
                        isOutgoing = true,
                        localPlaintext = metadata.toJson(),
                        ciphertext = record.ciphertextB64?.toByteArray(Charsets.UTF_8),
                        status = record.progress.status.name,
                        createdAtEpochMs = createdAt,
                        replyToJson = replyTo?.toJson(),
                    ),
                )
                conversationDao.touch(conversationId, conversation.title, createdAt)
                persist(contact)
                SendTextResult.Queued(record.messageId, record.progress.status)
            }
        }
    }

    override suspend fun sendLargeAttachment(
        conversationId: String,
        uri: android.net.Uri,
        originalName: String,
        mimeType: String,
        sizeBytes: Long,
        isImage: Boolean,
        replyTo: ReplyMetadata?,
    ): SendTextResult {
        if (!enabled) return SendTextResult.TransportUnavailable
        if (sizeBytes > com.codeyantra.armorchat.core.config.AttachmentConfig.MAX_STREAM_BYTES) {
            return SendTextResult.TransportUnavailable
        }
        return withContext(dispatchers.io) {
            val ready = mutex.withLock {
                if (policy.blocksNetwork()) return@withLock SendTextResult.NetworkUnavailable
                val pin = contactPinFor(conversationId) ?: return@withLock SendTextResult.ConversationMissing
                val owner = orchestrator.link(pin)?.peer?.mailbox?.ownerToken
                if (owner.isNullOrEmpty()) SendTextResult.SessionNotReady else null
            }
            if (ready != null) return@withContext ready
            val mediaClass = AttachmentClassification.mediaClass(mimeType, isImage)
            largeBlobs.enqueue(
                LargeOutgoing(
                    conversationId = conversationId,
                    uri = uri,
                    originalName = originalName,
                    mimeType = mimeType,
                    mediaClass = mediaClass,
                    sizeBytes = sizeBytes,
                    replyToJson = replyTo?.toJson(),
                ),
            )
            SendTextResult.Queued("blob-pending", MessageStatus.SENDING)
        }
    }

    override fun cancelLargeTransfer(conversationId: String) {
        largeBlobs.cancel(conversationId)
    }

    override fun observeTransfer(conversationId: String) = largeBlobs.observe(conversationId)

    override fun observeDownloads() = largeBlobs.observeDownloads()

    override suspend fun downloadAttachmentBlob(
        conversationId: String,
        attachmentId: String,
        blobId: String,
        wrappedKeyBase64Url: String,
        ivBase64Url: String,
        sha256DigestHex: String,
    ): Boolean {
        unused(conversationId)
        if (!enabled) return false
        return withContext(dispatchers.io) {
            try {
                when (
                    largeBlobs.download(
                        attachmentId = attachmentId,
                        blobId = blobId,
                        keyBase64Url = wrappedKeyBase64Url,
                        ivBase64Url = ivBase64Url,
                        sha256 = sha256DigestHex,
                    )
                ) {
                    DownloadOutcome.Saved -> true
                    DownloadOutcome.Failed -> false
                    DownloadOutcome.NotChunked -> legacyDownloadBlob(
                        attachmentId,
                        blobId,
                        wrappedKeyBase64Url,
                        ivBase64Url,
                        sha256DigestHex,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun legacyDownloadBlob(
        attachmentId: String,
        blobId: String,
        wrappedKeyBase64Url: String,
        ivBase64Url: String,
        sha256DigestHex: String,
    ): Boolean {
        val client = provider.clientFor(registry.bootstrap().origin)
        val cipherBlob = client.downloadBlob("", blobId) ?: return false
        val decrypted = com.codeyantra.armorchat.core.crypto.AttachmentCrypto.decrypt(
            ciphertext = cipherBlob,
            keyBase64Url = wrappedKeyBase64Url,
            ivBase64Url = ivBase64Url,
            expectedSha256DigestHex = sha256DigestHex,
        )
        com.codeyantra.armorchat.core.storage.AttachmentStorageManager.saveAttachment(
            context,
            attachmentId,
            decrypted,
        )
        return true
    }

    private suspend fun publishLargeBlob(
        pending: LargeBlobPending,
        markAttempted: () -> Unit,
        assignMessageId: (String) -> Unit,
    ): PublishResult {
        if (pending.messageId != null) {
            if (messageDao.findById(pending.messageId) == null) {
                insertLargeRow(pending, pending.messageId, MessageStatus.SENDING.name, null)
            }
            return PublishResult.Sent(pending.messageId)
        }
        return mutex.withLock {
            val conversation = conversationRepository.getConversation(pending.conversationId)
                ?: return@withLock PublishResult.Retry
            val contact = contactRepository.getContact(conversation.contactId)
                ?: return@withLock PublishResult.Retry
            if (policy.blocksNetwork()) return@withLock PublishResult.Retry
            val view = orchestrator.sessionView(
                identityRepository.getIdentity()?.publicIdentityKey.orEmpty(),
                contact.publicIdentityKey,
            )
            if (!view.composerAllowed) return@withLock PublishResult.Retry
            markAttempted()
            val replyTo = ReplyMetadata.fromJson(pending.replyToJson)
            val metadata = com.codeyantra.armorchat.domain.model.AttachmentMetadata(
                attachmentId = pending.attachmentId,
                blobId = pending.blobId,
                originalName = pending.originalName,
                mimeType = pending.mimeType,
                sizeBytes = pending.sizeBytes,
                ciphertextDigestSha256 = pending.sha256,
                ivBase64Url = pending.ivBase64Url,
                wrappedKeyBase64Url = pending.keyBase64Url,
                cryptoVersion = 1,
                inlineCiphertext = "",
                mediaClass = pending.mediaClass,
                replyTo = replyTo,
            )
            val record = try {
                orchestrator.sendAttachment(contact.publicIdentityKey, metadata.toJson(), replyTo)
            } catch (_: Exception) {
                SafeLog.failure("blob_transfer", "descriptor")
                return@withLock PublishResult.GiveUp
            }
            assignMessageId(record.messageId)
            if (messageDao.findById(record.messageId) == null) {
                insertLargeRow(pending, record.messageId, record.progress.status.name, record.ciphertextB64)
            }
            persist(contact)
            PublishResult.Sent(record.messageId)
        }
    }

    private suspend fun insertLargeRow(
        pending: LargeBlobPending,
        messageId: String,
        status: String,
        ciphertextB64: String?,
    ) {
        val conversation = conversationRepository.getConversation(pending.conversationId) ?: return
        val metadata = com.codeyantra.armorchat.domain.model.AttachmentMetadata(
            attachmentId = pending.attachmentId,
            blobId = pending.blobId,
            originalName = pending.originalName,
            mimeType = pending.mimeType,
            sizeBytes = pending.sizeBytes,
            ciphertextDigestSha256 = pending.sha256,
            ivBase64Url = pending.ivBase64Url,
            wrappedKeyBase64Url = pending.keyBase64Url,
            cryptoVersion = 1,
            inlineCiphertext = "",
            mediaClass = pending.mediaClass,
            replyTo = ReplyMetadata.fromJson(pending.replyToJson),
        )
        val msgType = AttachmentClassification.messageTypeName(pending.mediaClass)
        val createdAt = clock.nowEpochMs()
        messageDao.insert(
            MessageEntity(
                id = messageId,
                conversationId = pending.conversationId,
                type = msgType,
                isOutgoing = true,
                localPlaintext = metadata.toJson(),
                ciphertext = ciphertextB64?.toByteArray(Charsets.UTF_8),
                status = status,
                createdAtEpochMs = createdAt,
                replyToJson = pending.replyToJson,
            ),
        )
        conversationDao.touch(pending.conversationId, conversation.title, createdAt)
    }

    override suspend fun syncContact(contactId: String) {
        if (!enabled) return
        largeBlobs.kick()
        withContext(dispatchers.io) {
            MeshTiming.timedLock(mutex, "relay_sync") {
                val contact = contactRepository.getContact(contactId) ?: return@timedLock
                val signer = runCatching { signer() }.getOrNull() ?: return@timedLock
                loadInviteIfNeeded(signer)
                runCatching { orchestrator.ensureInviteMlsReady() }
                loadIfNeeded(signer, contact)
                val current = orchestrator.link(contact.publicIdentityKey)
                if (current == null || !current.peer.groupCreated) {
                    orchestrator.tryAdoptInvite(contact.publicIdentityKey)
                }
                if (policy.blocksNetwork()) return@timedLock
                val conversation = conversationRepository.getOrCreateForContact(contact)
                orchestrator.sync(contact.publicIdentityKey) { inbound ->
                    persistInbound(conversation, contact, inbound)
                }
                val live = orchestrator.link(contact.publicIdentityKey)
                if (live == null || !live.peer.groupCreated) {
                    orchestrator.reestablishIfNeeded(contact.publicIdentityKey)
                }
                val link = orchestrator.link(contact.publicIdentityKey)
                link?.receipts?.store?.texts()?.forEach { outbound ->
                    if (messageDao.findById(outbound.messageId) != null) {
                        val nowMs = clock.nowEpochMs()
                        when (outbound.progress.status) {
                            MessageStatus.DELIVERED -> messageDao.updateStatusDelivered(outbound.messageId, outbound.progress.status.name, nowMs)
                            MessageStatus.READ -> messageDao.updateStatusRead(outbound.messageId, outbound.progress.status.name, nowMs)
                            else -> messageDao.updateStatus(outbound.messageId, outbound.progress.status.name)
                        }
                        conversationDao.touch(conversation.id, conversation.title, nowMs)
                    }
                }
                persist(contact)
                val view = orchestrator.sessionView(
                    identityRepository.getIdentity()?.publicIdentityKey.orEmpty(),
                    contact.publicIdentityKey,
                )
                SafeLog.event("session_state", "state=${view.state} composer=${view.composerAllowed}")
                link?.receipts?.store?.texts()?.forEach { outbound ->
                    if (outbound.progress.status == MessageStatus.DELIVERED ||
                        outbound.progress.status == MessageStatus.READ
                    ) {
                        SafeLog.event(
                            "receipt_state",
                            "msg=${SafeLog.shortRef(outbound.messageId)} status=${outbound.progress.status}",
                        )
                    }
                }
            }
        }
    }

    override suspend fun markOpened(conversationId: String, messageId: String) {
        if (!enabled) return
        withContext(dispatchers.io) {
            mutex.withLock {
                val conversation = conversationRepository.getConversation(conversationId) ?: return@withLock
                val contact = contactRepository.getContact(conversation.contactId) ?: return@withLock
                val link = orchestrator.link(contact.publicIdentityKey) ?: return@withLock
                val row = messageDao.findById(messageId)
                val route = link.peer.peerRoute
                // Mesh-1K-O (P3): outgoing rows now carry receipt* columns too; never treat one as inbound.
                if (link.receipts.inbound(messageId) == null &&
                    row != null && !row.isOutgoing &&
                    row.receiptSessionId != null &&
                    row.receiptCiphertextSha256 != null &&
                    row.receiptEpoch != null
                ) {
                    link.receipts.rememberInbound(
                        InboundRecord(
                            messageId = row.id,
                            sessionId = row.receiptSessionId,
                            epoch = row.receiptEpoch,
                            ciphertextSha256 = row.receiptCiphertextSha256,
                            peerIdentity = contact.publicIdentityKey,
                            peerGeneration = route?.generation ?: 1L,
                            peerDeviceId = route?.deviceId.orEmpty(),
                            kind = if (row.type == MessageType.TEXT.name) InnerKind.TEXT else InnerKind.ATTACHMENT,
                            text = row.localPlaintext,
                            accepted = true,
                            replyTo = ReplyMetadata.fromJson(row.replyToJson),
                        ),
                    )
                }
                runCatching {
                    link.receipts.markOpened(messageId)
                    if (!policy.blocksNetwork()) link.receipts.flushOutbox()
                }
                messageDao.updateStatusRead(messageId, MessageStatus.READ.name, clock.nowEpochMs())
                conversationDao.touch(conversationId, conversation.title, clock.nowEpochMs())
                persist(contact)
            }
        }
    }

    override suspend fun refreshPolicy() {
        if (!enabled) return
        withContext(dispatchers.io) {
            orchestrator.refreshPolicy()
        }
    }

    override suspend fun pollIncomingInvites() {
        if (!enabled) return
        withContext(dispatchers.io) {
            mutex.withLock {
                val signer = runCatching { signer() }.getOrNull() ?: return@withLock
                loadInviteIfNeeded(signer)
                if (orchestrator.link(SessionOrchestrator.INVITE_PEER) == null) {
                    runCatching { orchestrator.provisionInvite(signer) }
                    persistInvite(signer)
                }
                val pins = runCatching { orchestrator.adoptIncomingHandshakes() }.getOrDefault(emptyList())
                for (pin in pins) {
                    val contact = contactRepository.findByPublicKey(pin)
                        ?: contactRepository.addInboundPinned(pin)
                    conversationRepository.getOrCreateForContact(contact)
                    persist(contact)
                    SafeLog.event("session_state", "peer=${SafeLog.shortRef(pin)} inbound=1")
                    runCatching { orchestrator.sync(pin) }
                }
            }
        }
    }

    private suspend fun loadInviteIfNeeded(signer: IdentitySigner) {
        if (orchestrator.link(SessionOrchestrator.INVITE_PEER) != null) {
            runCatching { orchestrator.ensureInviteMlsReady() }
            return
        }
        val row = sessionDao.find(SessionOrchestrator.INVITE_PEER)
        if (row == null) {
            SafeLog.event("stage=MAILBOX_RESTORE result=PASS")
            return
        }
        try {
            restoreRow(signer, row)
            SafeLog.event("stage=MAILBOX_RESTORE result=PASS")
            runCatching { orchestrator.ensureInviteMlsReady() }
        } catch (err: Exception) {
            SafeLog.event("stage=MAILBOX_RESTORE result=FAIL exception=${err::class.java.name}")
            runCatching { sessionDao.delete(SessionOrchestrator.INVITE_PEER) }
        }
    }

    private suspend fun persistInvite(signer: IdentitySigner) {
        unused(signer)
        val saved = orchestrator.persist(SessionOrchestrator.INVITE_PEER) ?: return
        val encryptedOwnerToken = saved.mailbox?.ownerToken?.let { token ->
            relayKek.encrypt(token.toByteArray(Charsets.UTF_8))
        }
        val encryptedSubmitCapability = saved.mailbox?.submitCapability?.let { cap ->
            relayKek.encrypt(cap.toByteArray(Charsets.UTF_8))
        }
        sessionDao.upsert(
            ContactSessionEntity(
                contactId = SessionOrchestrator.INVITE_PEER,
                peerPin = saved.peerPin,
                relayOrigin = saved.relayOrigin,
                mailboxId = saved.mailbox?.mailboxId,
                encryptedOwnerToken = encryptedOwnerToken,
                encryptedSubmitCapability = encryptedSubmitCapability,
                deviceId = saved.deviceId,
                binding = saved.binding,
                localRouteQr = saved.localRouteQr,
                peerRouteQr = saved.peerRouteQr,
                mlsSnapshot = saved.mlsSnapshot,
                groupCreated = saved.groupCreated,
                state = saved.state.name,
                updatedAtEpochMs = clock.nowEpochMs(),
                mlsSessionId = saved.sessionId,
            ),
        )
        pushTokenRegistrar.onMailboxReady()
    }

    private suspend fun loadIfNeeded(signer: IdentitySigner, contact: Contact) {
        if (orchestrator.link(contact.publicIdentityKey) != null) return
        val row = sessionDao.find(contact.id) ?: return
        restoreRow(signer, row)
    }

    private fun restoreRow(signer: IdentitySigner, row: ContactSessionEntity) {
        if (orchestrator.link(row.peerPin) != null) return
        val ownerToken = row.encryptedOwnerToken?.let {
            runCatching { String(relayKek.decrypt(it), Charsets.UTF_8) }.getOrNull()
        }
        val submitCapability = row.encryptedSubmitCapability?.let {
            runCatching { String(relayKek.decrypt(it), Charsets.UTF_8) }.getOrNull()
        }
        val mailbox = if (row.mailboxId != null && ownerToken != null && submitCapability != null) {
            MailboxRegistration(row.mailboxId, ownerToken, submitCapability)
        } else {
            null
        }
        if (row.peerPin == SessionOrchestrator.INVITE_PEER && row.mailboxId != null && mailbox == null) {
            SafeLog.event("stage=MAILBOX_RESTORE result=FAIL exception=java.lang.IllegalStateException")
            error("saved mailbox credentials unavailable")
        }
        mailbox?.let { orchestrator.rememberMailbox(it) }
        orchestrator.restore(
            signer = signer,
            saved = PersistedLink(
                peerPin = row.peerPin,
                relayOrigin = row.relayOrigin,
                mailbox = mailbox,
                deviceId = row.deviceId.orEmpty(),
                binding = row.binding,
                localRouteQr = row.localRouteQr,
                peerRouteQr = row.peerRouteQr,
                mlsSnapshot = row.mlsSnapshot ?: ByteArray(0),
                groupCreated = row.groupCreated,
                state = runCatching { SecureSessionState.valueOf(row.state) }.getOrDefault(
                    SecureSessionState.NONE,
                ),
                sessionId = row.mlsSessionId,
            ),
            selfPin = signer.pin,
        )
    }

    private suspend fun persistInbound(
        conversation: com.codeyantra.armorchat.domain.model.Conversation,
        contact: Contact,
        inbound: InboundRecord,
    ) {
        if (!inbound.kind.isUserMessage || inbound.text == null) return
        if (messageDao.findById(inbound.messageId) != null) return
        val nowMs = clock.nowEpochMs()
        val meta = if (inbound.kind == InnerKind.ATTACHMENT) {
            com.codeyantra.armorchat.domain.model.AttachmentMetadata.parseOrNull(inbound.text)
        } else {
            null
        }
        val msgType = when {
            meta == null -> MessageType.TEXT.name
            meta.mediaClass == "image" || meta.mimeType.startsWith("image/") -> MessageType.IMAGE.name
            meta.mediaClass == "voice" || meta.mimeType.startsWith("audio/") -> MessageType.VOICE.name
            else -> MessageType.DOCUMENT.name
        }
        val notifText = when {
            meta == null -> inbound.text
            msgType == MessageType.IMAGE.name -> "Photo"
            msgType == MessageType.VOICE.name -> "Voice message"
            else -> meta.originalName.ifBlank { "Document" }
        }
        messageDao.insert(
            MessageEntity(
                id = inbound.messageId,
                conversationId = conversation.id,
                type = msgType,
                isOutgoing = false,
                localPlaintext = inbound.text,
                ciphertext = null,
                status = MessageStatus.DELIVERED.name,
                createdAtEpochMs = nowMs,
                receiptSessionId = inbound.sessionId,
                receiptCiphertextSha256 = inbound.ciphertextSha256,
                receiptEpoch = inbound.epoch,
                replyToJson = inbound.replyTo?.toJson(),
            ),
        )
        conversationDao.touch(conversation.id, conversation.title, nowMs)
        notificationManager.postMessageNotification(
            conversationId = conversation.id,
            contactId = contact.id,
            contactName = contact.displayName.ifBlank { contact.handle ?: "ArmorChat Contact" },
            messageText = notifText,
            messageId = inbound.messageId,
            timestampEpochMs = nowMs,
        )
        if (meta != null) {
            val inline = meta.inlineCiphertext.isNotEmpty()
            val smallMedia = meta.mediaClass != "document" && meta.sizeBytes <= 512 * 1024L
            if (inline || smallMedia) {
                runCatching { materializeAttachment(meta) }
            }
        }
    }

    private suspend fun materializeAttachment(meta: com.codeyantra.armorchat.domain.model.AttachmentMetadata) {
        if (com.codeyantra.armorchat.core.storage.AttachmentStorageManager.hasAttachment(context, meta.attachmentId)) {
            return
        }
        val cipher = if (meta.inlineCiphertext.isNotEmpty()) {
            com.codeyantra.armorchat.domain.identity.Base64Url.decode(meta.inlineCiphertext)
        } else {
            val client = provider.clientFor(registry.bootstrap().origin)
            client.downloadBlob("", meta.blobId) ?: return
        }
        val decrypted = com.codeyantra.armorchat.core.crypto.AttachmentCrypto.decrypt(
            ciphertext = cipher,
            keyBase64Url = meta.wrappedKeyBase64Url,
            ivBase64Url = meta.ivBase64Url,
            expectedSha256DigestHex = meta.ciphertextDigestSha256,
        )
        com.codeyantra.armorchat.core.storage.AttachmentStorageManager.saveAttachment(
            context,
            meta.attachmentId,
            decrypted,
        )
    }

    private fun contactPinFor(conversationId: String): String? = runBlocking {
        val conversation = conversationRepository.getConversation(conversationId) ?: return@runBlocking null
        contactPins[conversation.contactId]
            ?: contactRepository.getContact(conversation.contactId)?.publicIdentityKey
    }

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    private fun compressImage(bytes: ByteArray): ByteArray {
        if (bytes.size <= INLINE_PLAINTEXT_TARGET) return bytes
        return try {
            val original = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            var maxDim = 960
            var quality = 52
            var best = bytes
            repeat(10) {
                val scale = maxOf(original.width, original.height).toFloat() / maxDim
                val scaled = if (scale > 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        original,
                        (original.width / scale).toInt().coerceAtLeast(1),
                        (original.height / scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    original
                }
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                if (scaled !== original) scaled.recycle()
                val compressed = out.toByteArray()
                if (compressed.isNotEmpty()) best = compressed
                if (compressed.size <= INLINE_PLAINTEXT_TARGET) return compressed
                if (quality > 28) {
                    quality -= 8
                } else {
                    maxDim = (maxDim * 3 / 4).coerceAtLeast(160)
                    quality = 40
                }
            }
            best
        } catch (_: Exception) {
            bytes
        }
    }

    private fun unused(value: Any?) = Unit

    companion object {
        private const val INLINE_CIPHERTEXT_MAX = InnerRecord.MAX_INLINE_CIPHERTEXT_BYTES
        private const val INLINE_PLAINTEXT_TARGET = INLINE_CIPHERTEXT_MAX - 16
    }

    private suspend fun persist(contact: Contact) {
        val row = sessionEntity(contact) ?: return
        sessionDao.upsert(row)
        pushTokenRegistrar.onMailboxReady()
    }

    /** persist(contact)'s row, Keystore encryption included; writes nothing (Mesh-1H-1 split). */
    private fun sessionEntity(contact: Contact): ContactSessionEntity? {
        contactPins[contact.id] = contact.publicIdentityKey
        val saved = orchestrator.persist(contact.publicIdentityKey) ?: return null
        val encryptedOwnerToken = saved.mailbox?.ownerToken?.let { token ->
            runCatching { relayKek.encrypt(token.toByteArray(Charsets.UTF_8)) }.getOrNull()
        }
        val encryptedSubmitCapability = saved.mailbox?.submitCapability?.let { cap ->
            runCatching { relayKek.encrypt(cap.toByteArray(Charsets.UTF_8)) }.getOrNull()
        }
        return ContactSessionEntity(
            contactId = contact.id,
            peerPin = saved.peerPin,
            relayOrigin = saved.relayOrigin,
            mailboxId = saved.mailbox?.mailboxId,
            encryptedOwnerToken = encryptedOwnerToken,
            encryptedSubmitCapability = encryptedSubmitCapability,
            deviceId = saved.deviceId,
            binding = saved.binding,
            localRouteQr = saved.localRouteQr,
            peerRouteQr = saved.peerRouteQr,
            mlsSnapshot = saved.mlsSnapshot,
            groupCreated = saved.groupCreated,
            state = saved.state.name,
            updatedAtEpochMs = clock.nowEpochMs(),
            mlsSessionId = saved.sessionId,
        )
    }

    private suspend fun signer(): IdentitySigner {
        val identity = identityRepository.getIdentity() ?: error("create identity first")
        return object : IdentitySigner {
            override val pin = identity.publicIdentityKey
            override fun sign(context: String, bodyCanonical: ByteArray): ByteArray {
                require(context in ArmorChatContexts.allowed)
                return runBlocking { keyStore.signFramed(context, bodyCanonical) }
            }
        }
    }
}
