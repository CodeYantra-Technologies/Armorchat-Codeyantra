package com.codeyantra.armorchat.messaging

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.entity.ContactSessionEntity
import com.codeyantra.armorchat.core.database.entity.ConversationEntity
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.mesh.BundleCodec
import com.codeyantra.armorchat.core.mesh.BundleDecode
import com.codeyantra.armorchat.core.mesh.CustodyLedger
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshCourier
import com.codeyantra.armorchat.core.mesh.MeshDeliveredStore
import com.codeyantra.armorchat.core.mesh.MeshFrames
import com.codeyantra.armorchat.core.mesh.MeshIds
import com.codeyantra.armorchat.core.mesh.MeshPolicy
import com.codeyantra.armorchat.core.mesh.RadioPath
import com.codeyantra.armorchat.core.network.DefaultRelayProvider
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.InMemoryRelayNetwork
import com.codeyantra.armorchat.core.network.RelayNode
import com.codeyantra.armorchat.core.network.RelayRole
import com.codeyantra.armorchat.core.network.ServicePolicyController
import com.codeyantra.armorchat.core.network.ServicePolicyTrust
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mesh-1K-N (P2): durable bounded delivered-here markers (tombstones).
 *
 * REAL production code: MeshDeliveredStore (file format, atomic write, quarantine, pruning, clamp,
 * selection) on a real temp directory; EmergencyMeshEngine.phaseFor/offers/holdSealed/ingest/
 * recordDelivered/hasDeliveredMarker/dropDelivered on in-memory Room `mesh_custody`;
 * MeshFrames.encodePhase/decodePhase/encodeBundles (wire unchanged); CustodyLedger.offer;
 * MeshCourier.ingest (exact EmergencyMeshService.ingest code); MeshDeliveredMarker.durable/
 * recordAfter (exact ChatMessagingService.deliverMesh decisions); MeshDelivery.deliverDurable;
 * MeshInboxCommit on Room; SessionOrchestrator.offerMesh + ReceiptMessagingPeer.processLeased.
 *
 * SIMULATED (stated explicitly):
 *  - ChatMessagingService is not constructible on the JVM; [wiring] mirrors deliverMesh after
 *    Mesh-1K-N line for line (durable / report / recordAfter). Contact/conversation lookup is a
 *    constant; Keystore encryption of contact_sessions is identity.
 *  - MLS is FakeMls + per-ciphertext decrypt counter: NOT evidence of native mls-rs behaviour.
 *  - "Restart" = fresh SessionOrchestrator + fresh EmergencyMeshEngine + fresh MeshDeliveredStore
 *    over the same Room database and the same marker file; NOT real process death.
 *  - "Deleted inbox row" uses raw SQL (no production message-delete DAO exists today).
 *  - Each engine gets its own marker file (two engines share one Robolectric app context).
 *  - Interrupted write = injected Io that leaves a partial temp file and throws before rename.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshDeliveredTombstoneTest {

    @get:Rule val tmp = TemporaryFolder()

    private class TinkSigner : IdentitySigner {
        private val handle: KeysetHandle
        override val pin: String

        init {
            SignatureConfig.register()
            handle = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
            val pub = (handle.publicKeysetHandle.primary.key as Ed25519PublicKey).publicKeyBytes.toByteArray()
            pin = Base64Url.encode(pub)
        }

        override fun sign(context: String, bodyCanonical: ByteArray): ByteArray {
            require(context in ArmorChatContexts.allowed)
            return RawIdentitySignature.sign(handle, bodyCanonical)
        }
    }

    private class CountingMls {
        val decrypts: MutableMap<String, Int> = Collections.synchronizedMap(mutableMapOf())
        fun transact(state: ByteArray, request: String): ByteArray {
            val req = CanonicalJson.parseObject(request)
            if (req["op"] == "Decrypt") {
                val ct = req["ciphertext"] as String
                synchronized(decrypts) { decrypts[ct] = (decrypts[ct] ?: 0) + 1 }
            }
            return FakeMls.transact(state, request)
        }
    }

    /** Fault-injecting Io around the production FileIo. */
    private class FaultIo : MeshDeliveredStore.Io {
        var failWrite = false
        var interruptWrite = false
        var failRead = false
        var writes = 0
        override fun read(file: File): ByteArray? {
            if (failRead) throw IOException("simulated read failure")
            return MeshDeliveredStore.FileIo.read(file)
        }
        override fun writeAtomic(file: File, bytes: ByteArray) {
            writes++
            if (failWrite) throw IOException("simulated write failure")
            if (interruptWrite) {
                File(file.parentFile, file.name + MeshDeliveredStore.TMP_SUFFIX).writeBytes(bytes.copyOf(bytes.size / 2))
                throw IOException("simulated crash before rename")
            }
            MeshDeliveredStore.FileIo.writeAtomic(file, bytes)
        }
        override fun rename(file: File, target: File): Boolean = MeshDeliveredStore.FileIo.rename(file, target)
    }

    private var nowMs = 1_800_000_000_000L
    private val clock = AppClock { nowMs }
    private val origin = "https://relay-mesh-1kn.example"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(listOf(RelayNode("node-1kn", origin, RelayRole.BOOTSTRAP)))
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val bobMls = CountingMls()
    private val lock = Mutex()
    private val databases = mutableListOf<ArmorChatDatabase>()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun newDb(): ArmorChatDatabase =
        Room.inMemoryDatabaseBuilder(app, ArmorChatDatabase::class.java).allowMainThreadQueries().build().also { databases += it }
    private val db: ArmorChatDatabase = newDb()
    private val aliceDb: ArmorChatDatabase = newDb()
    private val outcomes: MutableList<MeshDelivery.Outcome> = Collections.synchronizedList(mutableListOf())
    private val contactId = "contact-alice"
    private val conversationId = "conversation-alice"

    private fun orch(mls: (ByteArray, String) -> ByteArray) = SessionOrchestrator(
        registry = registry,
        provider = provider,
        policy = ServicePolicyController(ServicePolicyTrust()),
        factory = MessagingLinkFactory { signer, relay, clock, engine ->
            val peer = TwoPhonePeer(signer, relay, clock, mls, engine)
            LiveLink(peer, ReceiptMessagingPeer(peer, clock, jitter = { 0 }))
        },
        nowSeconds = { nowMs / 1000 },
    )

    private fun store(name: String, io: MeshDeliveredStore.Io = MeshDeliveredStore.FileIo) =
        MeshDeliveredStore(File(tmp.root, name), io)

    private fun engine(dbx: ArmorChatDatabase, markerFile: String, io: MeshDeliveredStore.Io = MeshDeliveredStore.FileIo) =
        EmergencyMeshEngine(app, dbx.meshCustodyDao(), clock).also { it.delivered = store(markerFile, io) }

    private inner class Harness(val bobIo: FaultIo = FaultIo()) {
        val alice = TinkSigner()
        val bob = TinkSigner()
        val aliceOrch = orch(FakeMls::transact)
        var bobOrch = orch(bobMls::transact)
        val aliceEngine = engine(aliceDb, "alice.markers")
        var bobEngine = engine(db, "bob.markers", bobIo)
        var failSessionUpsert = false
        val notifications: MutableList<String> = Collections.synchronizedList(mutableListOf())

        init {
            runBlocking {
                aliceOrch.provision(alice, bob.pin)
                bobOrch.provision(bob, alice.pin)
                aliceOrch.importPeerRoute(bobOrch.localRouteQr(alice.pin)!!, alice)
                confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)
                db.conversationDao().insert(ConversationEntity(conversationId, contactId, "Alice", nowMs))
                db.contactSessionDao().upsert(sessionRow(bobOrch.persist(alice.pin)!!))
            }
        }

        private val savedMailbox = bobOrch.link(alice.pin)?.peer?.mailbox

        fun sessionRow(saved: PersistedLink) = ContactSessionEntity(
            contactId = contactId,
            peerPin = saved.peerPin,
            relayOrigin = saved.relayOrigin,
            mailboxId = saved.mailbox?.mailboxId,
            encryptedOwnerToken = saved.mailbox?.ownerToken?.toByteArray(),
            encryptedSubmitCapability = saved.mailbox?.submitCapability?.toByteArray(),
            deviceId = saved.deviceId,
            binding = saved.binding,
            localRouteQr = saved.localRouteQr,
            peerRouteQr = saved.peerRouteQr,
            mlsSnapshot = saved.mlsSnapshot,
            groupCreated = saved.groupCreated,
            state = saved.state.name,
            updatedAtEpochMs = nowMs,
            mlsSessionId = saved.sessionId,
        )

        suspend fun load() {
            if (bobOrch.link(alice.pin) != null) return
            val row = db.contactSessionDao().find(contactId) ?: return
            bobOrch.restore(
                bob,
                PersistedLink(
                    peerPin = row.peerPin,
                    relayOrigin = row.relayOrigin,
                    mailbox = savedMailbox,
                    deviceId = row.deviceId ?: "",
                    binding = row.binding,
                    localRouteQr = row.localRouteQr,
                    peerRouteQr = row.peerRouteQr,
                    mlsSnapshot = row.mlsSnapshot ?: ByteArray(0),
                    groupCreated = row.groupCreated,
                    state = SecureSessionState.valueOf(row.state),
                    sessionId = row.mlsSessionId,
                ),
                bob.pin,
            )
        }

        /** Process "restart" of Bob: fresh orchestrator, engine and marker store on the same file. */
        fun restartBob() {
            bobOrch = orch(bobMls::transact)
            bobEngine = engine(db, "bob.markers", bobIo)
        }

        suspend fun rowIds(): List<String> = db.messageDao().observeAllMessages().first().filter { !it.isOutgoing }.map { it.id }
        fun deleteRow(id: String) = db.openHelper.writableDatabase.execSQL("DELETE FROM messages WHERE id = ?", arrayOf(id))

        fun seal(text: String): Triple<String, ByteArray, String> = runBlocking {
            val r = aliceOrch.sendText(bob.pin, text)
            val c = r.envelopeCanonical!!
            Triple(r.messageId, c.toByteArray(Charsets.UTF_8), JSONObject(c).getString("encrypted_payload"))
        }

        fun custodyHex(payload: ByteArray) = MeshIds.hex(MeshIds.idFor(payload))

        // WIRING: mirrors ChatMessagingService.deliverMesh after Mesh-1K-N --------------------------
        suspend fun deliver(payload: ByteArray): Boolean {
            val staged = mutableListOf<MeshInboxCommit.Staged>()
            var committed: List<MeshInboxCommit.Staged> = emptyList()
            var commitOutcome = MeshDelivery.Outcome.DELIVERED
            var finalOutcome: MeshDelivery.Outcome? = null
            val engineNow = bobEngine
            val ok = MeshDelivery.deliverDurable(
                lock = lock,
                payload = payload,
                durable = {
                    MeshDeliveredMarker.durable(payload, { db.messageDao().findById(it) }) { bytes, id ->
                        engineNow.hasDeliveredMarker(bytes, id)
                    }
                },
                committedAs = { commitOutcome },
                report = { _, _, o ->
                    finalOutcome = o
                    outcomes += o
                },
                load = { load() },
                checkpoint = { staged.clear(); bobOrch.meshCheckpoint() },
                rollback = { staged.clear(); bobOrch.restoreMeshCheckpoint(it) },
                offer = { raw, cb -> bobOrch.offerMesh(raw, cb) },
                persistInbound = { pin, r ->
                    MeshInboxCommit.inboundRow(conversationId, r, nowMs)?.let { e ->
                        staged += MeshInboxCommit.Staged(pin, e, "Alice") { notifications += e.id }
                    }
                },
                commit = { pin ->
                    val session = sessionRow(bobOrch.persist(pin)!!)
                    val result = MeshInboxCommit(db, beforeSessionUpsert = {
                        if (failSessionUpsert) throw IOException("simulated contact_sessions failure")
                    }).commit(staged.filter { it.peerPin == pin }, session)
                    committed = result.inserted
                    commitOutcome = result.outcome()
                },
            )
            lastRecord = MeshDeliveredMarker.recordAfter(payload, ok, finalOutcome, committed, { db.messageDao().findById(it) }) { bytes, id, binding ->
                engineNow.recordDelivered(bytes, id, binding)
            }
            if (ok) MeshInboxCommit.runAfterCommit(committed)
            return ok
        }

        var lastRecord: Boolean? = null

        /** One BLE encounter Alice -> Bob with the real phase/offer/courier code (no radio). */
        suspend fun encounter(): Int {
            val bobPhase = MeshFrames.decodePhase(MeshFrames.encodePhase(bobEngine.phaseFor(emptySet(), false)))!!
            val offers = aliceEngine.offers(bobPhase.ids)
            val sent = offers.mapNotNull { (BundleCodec.decode(it) as? BundleDecode.Ok)?.bundle?.id }
            if (sent.isNotEmpty()) aliceEngine.markForwarded(sent, RadioPath.BLE)
            MeshCourier.ingest(
                bytes = MeshFrames.encodeBundles(offers),
                path = RadioPath.BLE,
                store = { encoded, via -> bobEngine.ingest(encoded, via) },
                deliver = { deliver(it) },
                dropDelivered = { bobEngine.dropDelivered(it) },
            )
            return offers.size
        }

        suspend fun bobAdvertises(payload: ByteArray): Boolean =
            bobEngine.phaseFor(emptySet(), false).ids.any { it.contentEquals(MeshIds.idFor(payload)) }
    }

    @After
    fun close() {
        databases.forEach { it.close() }
    }

    // 1 First delivery -------------------------------------------------------------------------------
    @Test
    fun firstDeliveryRecordsMarkerAndStopsTheResendStormWithoutTouchingSenderCustody() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("first")
        h.aliceEngine.holdSealed(s1, emergency = true)
        assertEquals(1, h.encounter())
        assertEquals(listOf(MeshDelivery.Outcome.DELIVERED), outcomes)
        assertEquals(true, h.lastRecord)
        assertEquals(listOf(m1), h.rowIds())
        assertTrue("receiver custody copy dropped as before", db.meshCustodyDao().all().isEmpty())
        assertTrue("delivered id now advertised in the existing ids frame", h.bobAdvertises(s1))
        // Next encounters: Alice skips the bundle (offer filter only); nothing re-sent, nothing decrypted.
        repeat(5) { assertEquals(0, h.encounter()) }
        assertEquals(1, bobMls.decrypts[ct1])
        assertEquals(1, outcomes.size)
        // Sender custody untouched: advertisement is not an ACK.
        val held = aliceDb.meshCustodyDao().all().single()
        assertEquals(h.custodyHex(s1), held.id)
        assertEquals(1, held.forwardedCount)
        val beacon = h.bobEngine.phaseFor(emptySet(), false).beacon
        assertEquals("beacon still counts held rows only", 0, beacon.holding)
        val entry = h.bobEngine.delivered.snapshot(nowMs).single()
        assertEquals(MeshDeliveredStore.messageKey(m1), entry.messageKey)
        assertEquals(held.expireAtMs + MeshDeliveredStore.MARGIN_MS, entry.retainUntilMs)
        val stored = String(File(tmp.root, "bob.markers").readBytes(), Charsets.UTF_8)
        assertFalse("no clear message id on disk", stored.contains(m1))
        assertFalse("no clear peer key on disk", stored.contains(h.alice.pin))
    }

    // 2 Duplicate delivery -------------------------------------------------------------------------------
    @Test
    fun duplicateDeliveryIsDuplicateAndKeepsOneMarker() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("dup")
        assertTrue(h.deliver(s1))
        val before = h.bobEngine.delivered.snapshot(nowMs)
        nowMs += 1_000
        assertTrue(h.deliver(s1))
        assertEquals(listOf(MeshDelivery.Outcome.DELIVERED, MeshDelivery.Outcome.DUPLICATE), outcomes)
        assertEquals(true, h.lastRecord)
        assertEquals(1, bobMls.decrypts[ct1])
        assertEquals(listOf(m1), h.rowIds())
        val after = h.bobEngine.delivered.snapshot(nowMs)
        assertEquals(1, after.size)
        assertEquals("recordedAt not refreshed by a duplicate", before.single().recordedAtMs, after.single().recordedAtMs)
    }

    // 3 Deleted inbox row followed by repeat -----------------------------------------------------------
    @Test
    fun deletedInboxRowThenRepeatIsDuplicateViaMarkerWithoutDecryptOrResurrection() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("deleted later")
        assertTrue(h.deliver(s1))
        h.deleteRow(m1)
        assertTrue(h.rowIds().isEmpty())
        val durableBefore = db.contactSessionDao().find(contactId)!!
        assertTrue(h.deliver(s1))
        assertEquals(MeshDelivery.Outcome.DUPLICATE, outcomes.last())
        assertNull("marker-only duplicate records nothing new", h.lastRecord)
        assertEquals(1, bobMls.decrypts[ct1])
        assertTrue("row not resurrected", h.rowIds().isEmpty())
        assertArrayEquals(durableBefore.mlsSnapshot, db.contactSessionDao().find(contactId)!!.mlsSnapshot)
        assertEquals(listOf(m1), h.notifications)
    }

    // 4 Restart with persisted tombstone -----------------------------------------------------------------
    @Test
    fun restartKeepsMarkerOnDiskAdvertisedAndHonoured() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("restart")
        h.aliceEngine.holdSealed(s1, emergency = true)
        assertEquals(1, h.encounter())
        h.restartBob()
        assertEquals(MeshDeliveredStore.LoadState.NOT_LOADED, h.bobEngine.delivered.loadState)
        assertTrue(h.bobAdvertises(s1))
        assertEquals(MeshDeliveredStore.LoadState.OK, h.bobEngine.delivered.loadState)
        assertEquals(0, h.encounter())
        h.deleteRow(m1)
        assertTrue(h.deliver(s1))
        assertEquals(MeshDelivery.Outcome.DUPLICATE, outcomes.last())
        assertNull("answered before any session load", h.bobOrch.link(h.alice.pin))
        assertEquals(1, bobMls.decrypts[ct1])
    }

    // 5 Expiry and pruning -------------------------------------------------------------------------------
    @Test
    fun markerExpiresAtCustodyTtlPlusMarginAndIsPrunedOnNextWrite() = runBlocking {
        val h = Harness()
        val (_, s1, _) = h.seal("expiring")
        h.aliceEngine.holdSealed(s1, emergency = true)
        val expireAt = aliceDb.meshCustodyDao().all().single().expireAtMs
        assertEquals(1, h.encounter())
        nowMs = expireAt + MeshDeliveredStore.MARGIN_MS - 1
        assertTrue(h.bobAdvertises(s1))
        nowMs = expireAt + MeshDeliveredStore.MARGIN_MS
        assertFalse(h.bobAdvertises(s1))
        assertFalse(h.bobEngine.hasDeliveredMarker(s1, MeshDelivery.outerMessageId(s1)!!))
        // Expired entry stays on disk until the next write prunes it.
        val file = File(tmp.root, "bob.markers")
        assertEquals(listOf(h.custodyHex(s1)), MeshDeliveredStore.decode(file.readBytes())!!.map { it.custodyIdHex })
        assertTrue(h.bobEngine.delivered.record(hex(42), "later", hex(43), null, nowMs))
        assertEquals(listOf(hex(42)), MeshDeliveredStore.decode(file.readBytes())!!.map { it.custodyIdHex })
    }

    // 6 More than 512 entries ---------------------------------------------------------------------------
    @Test
    fun moreThan512EntriesPrunesExpiredFirstThenOldestDeterministically() {
        val s = store("cap.markers")
        repeat(10) { i -> assertTrue(s.record(hex(i), "old-$i", hex(9_000 + i), nowMs + 1_000, nowMs)) } // short-lived
        val t2 = nowMs + 20L * 60_000L // the 10 short-lived markers are expired by now
        repeat(600) { i -> assertTrue(s.record(hex(100 + i), "m-$i", hex(10_000 + i), null, t2 + i)) }
        val now = t2 + 600
        val live = s.snapshot(now)
        assertEquals(MeshDeliveredStore.MAX_ENTRIES, live.size)
        assertTrue("expired entries went first", live.none { it.custodyIdHex in (0 until 10).map(::hex) })
        assertEquals("oldest dropped", (100 + 88 until 100 + 600).map(::hex).toSet(), live.map { it.custodyIdHex }.toSet())
        val reloaded = store("cap.markers").snapshot(now)
        assertEquals(live.toSet(), reloaded.toSet())
        assertTrue(File(tmp.root, "cap.markers").length() < 80_000)
    }

    // 7 More than 64 encounter candidates ---------------------------------------------------------------
    @Test
    fun atMost64MarkersAreAdvertisedNewestFirstAndLegacyDecoderReadsThem() = runBlocking {
        val h = Harness()
        val bob = h.bobEngine
        repeat(100) { i -> assertTrue(bob.delivered.record(hex(i), "m-$i", hex(5_000 + i), null, nowMs + i)) }
        nowMs += 100
        // Bob also holds 3 bundles of his own; held ids come first, markers are appended.
        val own = (0 until 3).map { h.seal("own $it").second }
        own.forEach { bob.holdSealed(it, emergency = true) }
        val phase = bob.phaseFor(emptySet(), false)
        assertEquals(3 + MeshDeliveredStore.MAX_ADVERTISED, phase.ids.size)
        assertEquals(3, phase.beacon.holding)
        val markers = phase.ids.drop(3).map { MeshIds.hex(it) }
        assertEquals((99 downTo 36).map(::hex), markers)
        assertEquals(markers, bob.phaseFor(emptySet(), false).ids.drop(3).map { MeshIds.hex(it) })
        // Unchanged decoder (same code as e19e30f6) accepts the frame; ids round-trip exactly.
        val decoded = MeshFrames.decodePhase(MeshFrames.encodePhase(phase))!!
        assertEquals(phase.ids.map { MeshIds.hex(it) }, decoded.ids.map { MeshIds.hex(it) })
        assertTrue(MeshFrames.encodePhase(phase).size <= 26 + (3 + 64) * 16)
        // A held id that also has a marker is advertised once.
        val dupHex = MeshIds.hex(MeshIds.idFor(own[0]))
        assertTrue(bob.delivered.record(dupHex, "x", hex(7), null, nowMs + 1))
        val ids2 = bob.phaseFor(emptySet(), false).ids.map { MeshIds.hex(it) }
        assertEquals(1, ids2.count { it == dupHex })
    }

    // 8 Corrupt store ----------------------------------------------------------------------------------
    @Test
    fun corruptStoreIsQuarantinedByRenameTreatedAsEmptyAndNeverDeleted() {
        val file = File(tmp.root, "corrupt.markers")
        val garbage = "ACMT1\nn=1\nnot-a-valid-line\nsum=00\n".toByteArray()
        file.writeBytes(garbage)
        val s = store("corrupt.markers")
        assertTrue(s.advertised(nowMs, emptySet()).isEmpty())
        assertEquals(MeshDeliveredStore.LoadState.CORRUPT_QUARANTINED, s.loadState)
        val q1 = File(tmp.root, "corrupt.markers.corrupt-1")
        assertArrayEquals("quarantined byte-identical", garbage, q1.readBytes())
        assertTrue(s.record(hex(1), "m1", hex(2), null, nowMs))
        assertEquals(listOf(hex(1)), store("corrupt.markers").advertised(nowMs, emptySet()))
        // Checksum mismatch (one flipped digit) is also corrupt.
        val good = file.readBytes()
        val flipped = good.copyOf().also { it[12] = if (it[12] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte() }
        file.writeBytes(flipped)
        val s2 = store("corrupt.markers")
        assertFalse(s2.contains(hex(1), "m1", nowMs))
        assertArrayEquals(flipped, File(tmp.root, "corrupt.markers.corrupt-2").readBytes())
        // All quarantine slots taken: corrupt file stays in place, markers disabled, nothing overwritten.
        File(tmp.root, "corrupt.markers.corrupt-3").writeBytes(byteArrayOf(1))
        file.writeBytes(garbage)
        val s3 = store("corrupt.markers")
        assertFalse(s3.record(hex(3), "m3", hex(4), null, nowMs))
        assertEquals(MeshDeliveredStore.LoadState.CORRUPT_IN_PLACE, s3.loadState)
        assertArrayEquals(garbage, file.readBytes())
        assertArrayEquals(byteArrayOf(1), File(tmp.root, "corrupt.markers.corrupt-3").readBytes())
        // Missing store = empty, no file created by reads.
        val missing = store("missing.markers")
        assertTrue(missing.advertised(nowMs, emptySet()).isEmpty())
        assertEquals(MeshDeliveredStore.LoadState.MISSING, missing.loadState)
        assertFalse(File(tmp.root, "missing.markers").exists())
        // Unreadable: empty, no write (never clobbers), recovers when readable again.
        val io = FaultIo()
        val r = store("corrupt.markers.ok", io)
        assertTrue(r.record(hex(5), "m5", hex(6), null, nowMs))
        val r2 = store("corrupt.markers.ok", io)
        io.failRead = true
        assertFalse(r2.record(hex(7), "m7", hex(8), null, nowMs))
        assertEquals(MeshDeliveredStore.LoadState.UNREADABLE, r2.loadState)
        io.failRead = false
        assertTrue(r2.contains(hex(5), "m5", nowMs))
    }

    // 9 Interrupted write ------------------------------------------------------------------------------
    @Test
    fun interruptedWriteLeavesPreviousFileAndClaimsNothing() {
        val io = FaultIo()
        val s = store("crash.markers", io)
        assertTrue(s.record(hex(1), "m1", hex(11), null, nowMs))
        val before = File(tmp.root, "crash.markers").readBytes()
        io.interruptWrite = true
        assertFalse(s.record(hex(2), "m2", hex(12), null, nowMs))
        assertFalse("memory unchanged after failed write", s.contains(hex(2), "m2", nowMs))
        assertEquals(listOf(hex(1)), s.advertised(nowMs, emptySet()))
        assertArrayEquals(before, File(tmp.root, "crash.markers").readBytes())
        assertTrue(File(tmp.root, "crash.markers" + MeshDeliveredStore.TMP_SUFFIX).exists())
        val reloaded = store("crash.markers")
        assertEquals(listOf(hex(1)), reloaded.advertised(nowMs, emptySet()))
        // Partial bytes are never accepted as a store.
        assertNull(MeshDeliveredStore.decode(before.copyOf(before.size - 5)))
        io.interruptWrite = false
        assertTrue(s.record(hex(2), "m2", hex(12), null, nowMs))
        assertEquals(setOf(hex(1), hex(2)), store("crash.markers").advertised(nowMs, emptySet()).toSet())
    }

    // 10 Clock rollback -------------------------------------------------------------------------------
    @Test
    fun clockRollbackNeverStretchesRetentionBeyondTheMaximumWindow() {
        val s = store("clock.markers")
        val t0 = nowMs
        assertTrue(s.record(hex(1), "m1", hex(2), t0 + MeshPolicy.ABSOLUTE_TTL_MS, t0))
        // Wall clock jumps back one day.
        val back = t0 - 24L * 3_600_000L
        val e = s.snapshot(back).single()
        assertEquals(back + MeshDeliveredStore.MAX_RETAIN_MS, e.retainUntilMs)
        assertEquals(listOf(hex(1)), s.advertised(back, emptySet()))
        // Recording while rolled back clamps a future bundle deadline too.
        assertTrue(s.record(hex(3), "m3", hex(4), t0 + MeshPolicy.ABSOLUTE_TTL_MS, back))
        assertTrue(s.snapshot(back).all { it.retainUntilMs <= back + MeshDeliveredStore.MAX_RETAIN_MS })
        // A forged far-future deadline cannot extend retention either.
        assertTrue(s.record(hex(5), "m5", hex(6), Long.MAX_VALUE / 4, t0))
        assertEquals(
            t0 + MeshPolicy.ABSOLUTE_TTL_MS + MeshPolicy.CLOCK_SKEW_MS + MeshDeliveredStore.MARGIN_MS,
            s.snapshot(t0).first { it.custodyIdHex == hex(5) }.retainUntilMs,
        )
        // Forward jump: everything expires (fail open), nothing advertised.
        assertTrue(s.advertised(t0 + MeshDeliveredStore.MAX_RETAIN_MS + 1, emptySet()).isEmpty())
    }

    // 11 Wrong peer / session -------------------------------------------------------------------------
    @Test
    fun markerIsBoundToExactEnvelopeAndMessageNotToAnotherPeerOrSession() = runBlocking {
        val h = Harness()
        val (m1, s1, _) = h.seal("bob's real message")
        assertTrue(h.deliver(s1))
        val carol = TinkSigner()
        val dave = TinkSigner()
        val carolOrch = orch(FakeMls::transact)
        val daveOrch = orch(FakeMls::transact)
        carolOrch.provision(carol, dave.pin)
        daveOrch.provision(dave, carol.pin)
        carolOrch.importPeerRoute(daveOrch.localRouteQr(carol.pin)!!, carol)
        confirmSharedMls(carolOrch, daveOrch, carol.pin, dave.pin)
        val foreign = carolOrch.sendText(dave.pin, "for dave").envelopeCanonical!!.toByteArray(Charsets.UTF_8)
        val relabelled = JSONObject(String(foreign, Charsets.UTF_8)).put("message_id", m1).toString().toByteArray()
        assertFalse(h.bobEngine.hasDeliveredMarker(relabelled, m1))
        assertFalse(h.bobEngine.hasDeliveredMarker(foreign, m1))
        h.deleteRow(m1)
        assertFalse("other session's envelope never suppressed by Bob's marker", h.deliver(relabelled))
        assertEquals(MeshDelivery.Outcome.DECLINED, outcomes.last())
        assertTrue(h.rowIds().isEmpty())
        // Same custody id with another message id is not a match, and is refused as a conflicting record.
        assertFalse(h.bobEngine.hasDeliveredMarker(s1, "$m1-other"))
        assertFalse(h.bobEngine.delivered.record(h.custodyHex(s1), "$m1-other", hex(1), null, nowMs))
        // Binding hashes separate conversation / session / message.
        val b = MeshDeliveredStore.bindingOf("c", "s", "m")
        assertFalse(b == MeshDeliveredStore.bindingOf("c2", "s", "m"))
        assertFalse(b == MeshDeliveredStore.bindingOf("c", "s2", "m"))
        assertFalse(b == MeshDeliveredStore.bindingOf("c", "s", "m2"))
    }

    // 12 Forged advertised id (and inventory flooding) --------------------------------------------------
    @Test
    fun forgedAdvertisedIdOnlySuppressesTowardThatPeerAndNeverDeletesSenderCustody() = runBlocking {
        val h = Harness()
        val (_, s1, _) = h.seal("held by alice")
        h.aliceEngine.holdSealed(s1, emergency = true)
        val held = aliceDb.meshCustodyDao().all().single()
        // Mallory advertises Alice's bundle id plus 4,000 random ids (max frame) as "held/delivered".
        val forged = listOf(MeshIds.idFor(s1)) + List(4_000) { i -> MeshIds.idFor("noise-$i".toByteArray()) }
        val frame = MeshFrames.encodePhase(com.codeyantra.armorchat.core.mesh.MeshPhase(h.aliceEngine.phaseFor(emptySet(), false).beacon, forged))
        val parsed = MeshFrames.decodePhase(frame)!!
        assertTrue(h.aliceEngine.offers(parsed.ids).isEmpty())
        val after = aliceDb.meshCustodyDao().all().single()
        assertEquals("sender custody untouched", held.id, after.id)
        assertEquals(held.expireAtMs, after.expireAtMs)
        assertArrayEquals(held.payload, after.payload)
        // Another peer (Bob, no marker) still gets the offer in the next encounter.
        assertEquals(1, h.encounter())
        assertEquals(MeshDelivery.Outcome.DELIVERED, outcomes.last())
        assertEquals(1, aliceDb.meshCustodyDao().all().size)
        // Pure ledger: offers filtered, rows intact.
        val ledger = CustodyLedger(listOf())
        ledger.admit(s1, nowMs, true)
        assertTrue(ledger.offer(setOf(h.custodyHex(s1)), nowMs).isEmpty())
        assertEquals(1, ledger.snapshot().size)
    }

    // 13 Concurrent delivery ----------------------------------------------------------------------------
    @Test
    fun concurrentDeliveriesYieldOneDeliveredOneMarker() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("concurrent")
        val start = CompletableDeferred<Unit>()
        val jobs = List(6) { async(Dispatchers.Default) { start.await(); h.deliver(s1) } }
        start.complete(Unit)
        assertTrue(withTimeout(20_000) { jobs.awaitAll() }.all { it })
        assertEquals(1, outcomes.count { it == MeshDelivery.Outcome.DELIVERED })
        assertEquals(5, outcomes.count { it == MeshDelivery.Outcome.DUPLICATE })
        assertEquals(1, bobMls.decrypts[ct1])
        assertEquals(listOf(m1), h.rowIds())
        assertEquals(listOf(h.custodyHex(s1)), h.bobEngine.delivered.snapshot(nowMs).map { it.custodyIdHex })
        assertEquals(listOf(h.custodyHex(s1)), MeshDeliveredStore.decode(File(tmp.root, "bob.markers").readBytes())!!.map { it.custodyIdHex })
    }

    // 14 Inbox commit failure ----------------------------------------------------------------------------
    @Test
    fun inboxCommitFailureWritesNoMarker() = runBlocking {
        val h = Harness()
        val (m1, s1, _) = h.seal("commit fails")
        h.failSessionUpsert = true
        assertFalse(h.deliver(s1))
        assertEquals(MeshDelivery.Outcome.PERSIST_FAILED, outcomes.last())
        assertNull(h.lastRecord)
        assertEquals(0, h.bobIo.writes)
        assertFalse(h.bobAdvertises(s1))
        assertFalse(File(tmp.root, "bob.markers").exists())
        h.failSessionUpsert = false
        assertTrue(h.deliver(s1))
        assertEquals(true, h.lastRecord)
        assertEquals(listOf(m1), h.rowIds())
        assertTrue(h.bobAdvertises(s1))
    }

    // 15 Tombstone write failure ---------------------------------------------------------------------------
    @Test
    fun markerWriteFailureStillDeliversButNeverClaimsSuppression() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("write fails")
        h.aliceEngine.holdSealed(s1, emergency = true)
        h.bobIo.failWrite = true
        assertEquals(1, h.encounter())
        assertEquals(MeshDelivery.Outcome.DELIVERED, outcomes.last())
        assertEquals(false, h.lastRecord)
        assertEquals(listOf(m1), h.rowIds())
        assertTrue("receiver custody cleanup unchanged", db.meshCustodyDao().all().isEmpty())
        assertFalse("nothing advertised", h.bobAdvertises(s1))
        assertFalse(h.bobEngine.hasDeliveredMarker(s1, m1))
        // Sender re-offers (pre-P2 behaviour); P1 answers DUPLICATE; marker lands once writes work.
        h.bobIo.failWrite = false
        assertEquals(1, h.encounter())
        assertEquals(MeshDelivery.Outcome.DUPLICATE, outcomes.last())
        assertEquals(true, h.lastRecord)
        assertEquals(0, h.encounter())
        assertEquals(1, bobMls.decrypts[ct1])
    }

    // Non-row records and failed outcomes never create markers ------------------------------------------
    @Test
    fun recordAfterOnlyForDurableInboundRowsWithMatchingDigest() = runBlocking {
        val h = Harness()
        val (m1, s1, _) = h.seal("pure")
        var calls = 0
        val rec: suspend (ByteArray, String, String) -> Boolean = { _, _, _ -> calls++; true }
        val digest = MeshDelivery.outerCiphertextDigest(s1)!!
        val row = MeshInboxCommit.inboundRow(
            conversationId,
            InboundRecord(m1, "sess", 0, digest, h.alice.pin, 1, "dev", InnerKind.TEXT, "t", true),
            nowMs,
        )!!
        val staged = listOf(MeshInboxCommit.Staged("pin", row, "Alice") {})
        val none: suspend (String) -> MessageEntity? = { null }
        assertNull(MeshDeliveredMarker.recordAfter(s1, false, MeshDelivery.Outcome.DELIVERED, staged, none, rec))
        assertNull(MeshDeliveredMarker.recordAfter(s1, true, MeshDelivery.Outcome.DELIVERED, emptyList(), none, rec))
        assertNull(MeshDeliveredMarker.recordAfter(s1, true, MeshDelivery.Outcome.CONFLICT, staged, none, rec))
        assertNull(MeshDeliveredMarker.recordAfter(s1, true, MeshDelivery.Outcome.DUPLICATE, staged, none, rec))
        val outgoing = listOf(MeshInboxCommit.Staged("pin", row.copy(isOutgoing = true), "Alice") {})
        assertNull(MeshDeliveredMarker.recordAfter(s1, true, MeshDelivery.Outcome.DELIVERED, outgoing, none, rec))
        val otherDigest = listOf(MeshInboxCommit.Staged("pin", row.copy(receiptCiphertextSha256 = "x"), "Alice") {})
        assertNull(MeshDeliveredMarker.recordAfter(s1, true, MeshDelivery.Outcome.DELIVERED, otherDigest, none, rec))
        assertEquals(0, calls)
        assertEquals(true, MeshDeliveredMarker.recordAfter(s1, true, MeshDelivery.Outcome.DELIVERED, staged, none, rec))
        assertEquals(true, MeshDeliveredMarker.recordAfter(s1, true, MeshDelivery.Outcome.DUPLICATE, emptyList(), { row }, rec))
        assertEquals(2, calls)
        val boom: suspend (ByteArray, String, String) -> Boolean = { _, _, _ -> throw IOException("x") }
        assertEquals(false, MeshDeliveredMarker.recordAfter(s1, true, MeshDelivery.Outcome.DELIVERED, staged, none, boom))
    }

    private fun hex(i: Int): String = MeshIds.hex(MeshIds.idFor("id-$i".toByteArray()))
}
