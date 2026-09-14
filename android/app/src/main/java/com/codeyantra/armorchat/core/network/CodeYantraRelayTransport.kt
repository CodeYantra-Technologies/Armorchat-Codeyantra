package com.codeyantra.armorchat.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.codeyantra.armorchat.core.json.CanonicalJson

/**
 * HTTPS client for the CodeYantra Privacy Relay.
 *
 * [isAvailable] stays false so constructing this class cannot enable chat
 * send by itself. The composer is gated on a verified MLS session instead.
 */
class CodeYantraRelayTransport(
    val origin: HttpUrl,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
    private val submitCapability: String? = null,
    private val ownerToken: String? = null,
) : MessageTransport, RelayClient {
    override val originHttps: String =
        origin.newBuilder().encodedPath("/").query(null).fragment(null).build()
            .toString()
            .trimEnd('/')
    init {
        require(origin.scheme == "https") { "relay origin must be https" }
        require(origin.query == null && origin.fragment == null) {
            "relay origin must not include query or fragment"
        }
        val path = origin.encodedPath
        require(path == "/" || path.isEmpty()) { "relay origin must not include a path" }
    }

    override val isAvailable: Flow<Boolean> = flowOf(false)

    override suspend fun send(envelope: OutboundEnvelope): TransportResult {
        unused(envelope)
        return TransportResult.Unavailable
    }

    override fun incoming(): Flow<InboundEnvelope> = emptyFlow()

    override suspend fun registerMailbox(): MailboxRegistration {
        val response = execute(
            path = "/v1/mailboxes",
            method = "POST",
            token = null,
            json = """{"protocol_version":1}""",
        )
        require(response.code == 201) { errorCode(response.body) }
        forbidDeliveryWords(response.body)
        val obj = CanonicalJson.parseObject(response.body)
        return MailboxRegistration(
            mailboxId = obj["mailbox_id"] as String,
            ownerToken = obj["owner_token"] as String,
            submitCapability = obj["submit_capability"] as String,
        )
    }

    override suspend fun putKeyPackages(ownerToken: String, packagesJson: String) {
        val response = execute("/v1/key-packages", "PUT", ownerToken, packagesJson)
        require(response.code == 200) { errorCode(response.body) }
        forbidDeliveryWords(response.body)
    }

    suspend fun claimKeyPackage(submitToken: String, claimId: String): ClaimedKeyPackage {
        val json = CanonicalJson.of(mapOf("claim_id" to claimId, "protocol_version" to 1L))
        val response = execute("/v1/key-packages/claim", "POST", submitToken, json)
        require(response.code == 200) { errorCode(response.body) }
        forbidDeliveryWords(response.body)
        val obj = CanonicalJson.parseObject(response.body)
        val pkg = obj["key_package"] as Map<*, *>
        return ClaimedKeyPackage(
            keyPackageId = pkg["key_package_id"] as String,
            binding = pkg["binding"] as String,
            expiresAt = (pkg["expires_at_epoch_ms"] as Number).toLong(),
        )
    }

    override suspend fun submitEnvelope(
        token: String,
        envelope: ImmutableRelayEnvelope,
    ): RelaySubmitResult {
        val json = envelope.canonicalJson()
        val response = execute(
            path = "/v1/envelopes",
            method = "POST",
            token = token,
            json = json,
            extraHeaders = mapOf("Idempotency-Key" to envelope.messageId),
        )
        forbidDeliveryWords(response.body)
        if (response.code == 429) {
            return RelaySubmitResult.ServiceSuspended(response.retryAfterSeconds)
        }
        if (response.code != 200 && response.code != 201) {
            return RelaySubmitResult.Rejected(errorCode(response.body))
        }
        val obj = CanonicalJson.parseObject(response.body)
        return RelaySubmitResult.Accepted(
            messageId = obj["message_id"] as String,
            recipientMailbox = obj["recipient_mailbox"] as String,
            envelopeSha256 = obj["envelope_sha256"] as String,
            acceptedAt = epochNumber(obj, "accepted_at", "accepted_at_epoch_ms"),
            expiresAt = epochNumber(obj, "expires_at", "expires_at_epoch_ms"),
            state = parseRelayQueueState(obj["state"] as String),
            replay = obj["replay"] == 1L,
        )
    }

    override suspend fun fetchInbox(
        token: String,
        cursor: String?,
        limit: Int,
    ): RelayFetchResult {
        val map = mutableMapOf<String, Any>(
            "limit" to limit.toLong(),
            "protocol_version" to 1L,
        )
        if (cursor != null) map["cursor"] = cursor
        val response = execute("/v1/inbox/fetch", "POST", token, CanonicalJson.of(map))
        forbidDeliveryWords(response.body)
        if (response.code == 404 || response.code == 400) return RelayFetchResult.Rejected(errorCode(response.body))
        if (response.code != 200) return RelayFetchResult.Unavailable
        val obj = CanonicalJson.parseObject(response.body)
        val rawEnvelopes = obj["envelopes"] as List<*>
        val envelopes = rawEnvelopes.map { raw ->
            val env = raw as Map<*, *>
            LeasedRelayEnvelope(
                messageId = env["message_id"] as String,
                envelopeSha256 = env["envelope_sha256"] as String,
                encryptedPayloadB64 = env["encrypted_payload"] as String,
                payloadType = env["payload_type"] as String,
                expiresAt = (env["expires_at"] as Number).toLong(),
                leaseToken = env["lease_token"] as String,
                leaseUntil = (env["lease_until"] as Number).toLong(),
                senderCredentialCanonical = (env["sender_credential"] as? Map<*, *>)?.let {
                    @Suppress("UNCHECKED_CAST")
                    CanonicalJson.of(it as Map<String, Any?>)
                },
            )
        }
        return RelayFetchResult.Leased(envelopes = envelopes, cursor = obj["cursor"] as String)
    }

    override suspend fun consumeInbox(
        token: String,
        items: List<RelayConsumeItem>,
    ): RelayConsumeResult {
        val json = CanonicalJson.of(
            mapOf(
                "protocol_version" to 1L,
                "items" to items.map {
                    mapOf(
                        "message_id" to it.messageId,
                        "envelope_sha256" to it.envelopeSha256,
                        "lease_token" to it.leaseToken,
                    )
                },
            ),
        )
        val response = execute("/v1/inbox/consume", "POST", token, json)
        forbidDeliveryWords(response.body)
        if (response.code != 200) return RelayConsumeResult.Rejected(errorCode(response.body))
        val obj = CanonicalJson.parseObject(response.body)
        val rawResults = obj["results"] as List<*>
        val states = rawResults.map { item ->
            @Suppress("UNCHECKED_CAST")
            val entry = item as Map<String, Any?>
            parseRelayQueueState(entry["status"] as String)
        }
        return RelayConsumeResult.Done(states = states)
    }

    override suspend fun putInvite(
        ownerToken: String,
        token: String,
        bundleJson: String,
        expiresAt: Long,
    ): Boolean {
        val json = CanonicalJson.of(
            mapOf(
                "protocol_version" to 1L,
                "token" to token,
                "bundle" to bundleJson,
                "expires_at" to expiresAt,
            ),
        )
        com.codeyantra.armorchat.messaging.SafeLog.event("stage=INVITE_PUBLISH_START result=PASS")
        val response = try {
            execute("/v1/invites", "POST", ownerToken, json)
        } catch (err: Exception) {
            com.codeyantra.armorchat.messaging.SafeLog.event(
                "stage=INVITE_PUBLISH_HTTP_STATUS result=FAIL exception=${err::class.java.name}",
            )
            throw err
        }
        forbidDeliveryWords(response.body)
        val ok = response.code == 200 || response.code == 201
        val error = if (ok) "" else {
            val code = runCatching { errorCode(response.body) }.getOrDefault("unknown_error")
                .replace(Regex("[^A-Za-z0-9_]"), "").take(40)
            " error=$code"
        }
        com.codeyantra.armorchat.messaging.SafeLog.event(
            "stage=INVITE_PUBLISH_HTTP_STATUS result=${if (ok) "PASS" else "FAIL"} http_status=${response.code}$error",
        )
        if (ok) {
            com.codeyantra.armorchat.messaging.SafeLog.event(
                "stage=INVITE_PUBLISH_SUCCESS result=PASS http_status=${response.code}",
            )
        }
        return ok
    }

    override suspend fun lookupInvite(token: String): String? {
        val json = CanonicalJson.of(mapOf("token" to token, "protocol_version" to 1L))
        val response = execute("/v1/invites/lookup", "POST", null, json)
        forbidDeliveryWords(response.body)
        if (response.code == 404) return null
        require(response.code == 200) { errorCode(response.body) }
        val obj = CanonicalJson.parseObject(response.body)
        return (obj["bundle"] as? String) ?: (obj["bundle_json"] as? String)
    }

    override suspend fun revokeInvite(ownerToken: String, token: String): Boolean {
        val json = CanonicalJson.of(mapOf("token" to token, "protocol_version" to 1L))
        val response = execute("/v1/invites/revoke", "POST", ownerToken, json)
        forbidDeliveryWords(response.body)
        return response.code == 200
    }

    override suspend fun redeemInvite(token: String, claimId: String): InviteRedeemResult? {
        val json = CanonicalJson.of(
            mapOf(
                "token" to token,
                "claim_id" to claimId,
                "protocol_version" to 1L,
            ),
        )
        val response = execute("/v1/invites/redeem", "POST", null, json)
        forbidDeliveryWords(response.body)
        if (response.code == 404 || response.code == 410) return null
        require(response.code == 200 || response.code == 201) { errorCode(response.body) }
        val obj = CanonicalJson.parseObject(response.body)
        val bundle = (obj["bundle"] as? String) ?: (obj["bundle_json"] as? String)
            ?: error("invite redeem missing bundle")
        val grantMap = (obj["bootstrap_grant"] as? Map<*, *>) ?: (obj["grant"] as? Map<*, *>)
            ?: error("invite redeem missing grant")
        val replay = (obj["replay"] as? Boolean) ?: (response.code == 200)
        return InviteRedeemResult(
            bundleJson = bundle,
            keyPackageId = obj["key_package_id"] as String,
            binding = obj["binding"] as String,
            grant = parseGrant(grantMap),
            replay = replay,
        )
    }

    override suspend fun createPeerGrant(ownerToken: String, expiresAt: Long): SubmitGrant? {
        val json = CanonicalJson.of(
            mapOf("expires_at_epoch_ms" to expiresAt, "protocol_version" to 1L),
        )
        val response = execute("/v1/grants", "POST", ownerToken, json)
        if (response.code != 200) return null
        forbidDeliveryWords(response.body)
        return parseGrant(CanonicalJson.parseObject(response.body))
    }

    override suspend fun rotatePeerGrant(ownerToken: String, grantId: String): SubmitGrant? {
        val json = CanonicalJson.of(
            mapOf("grant_id" to grantId, "protocol_version" to 1L),
        )
        val response = execute("/v1/grants/rotate", "POST", ownerToken, json)
        if (response.code != 200) return null
        forbidDeliveryWords(response.body)
        return parseGrant(CanonicalJson.parseObject(response.body))
    }

    override suspend fun revokeGrant(ownerToken: String, grantId: String): Boolean {
        val json = CanonicalJson.of(mapOf("grant_id" to grantId, "protocol_version" to 1L))
        val response = execute("/v1/grants/revoke", "POST", ownerToken, json)
        forbidDeliveryWords(response.body)
        return response.code == 200
    }

    override suspend fun rotateSubmitCapability(ownerToken: String): String? {
        val json = CanonicalJson.of(mapOf("protocol_version" to 1L))
        val response = execute("/v1/mailboxes/rotate-submit", "POST", ownerToken, json)
        if (response.code != 200) return null
        forbidDeliveryWords(response.body)
        return CanonicalJson.parseObject(response.body)["submit_capability"] as? String
    }

    override suspend fun registerPush(ownerToken: String, deviceId: String, fcmToken: String): Boolean {
        val json = CanonicalJson.of(
            mapOf(
                "device_id" to deviceId,
                "fcm_token" to fcmToken,
                "protocol_version" to 1L,
            ),
        )
        val response = execute("/v1/push/register", "POST", ownerToken, json)
        forbidDeliveryWords(response.body)
        require(!response.body.contains(fcmToken)) { "relay must not echo FCM token" }
        return response.code == 200
    }

    override suspend fun unregisterPush(ownerToken: String, deviceId: String): Boolean {
        val json = CanonicalJson.of(mapOf("device_id" to deviceId, "protocol_version" to 1L))
        val response = execute("/v1/push/unregister", "POST", ownerToken, json)
        forbidDeliveryWords(response.body)
        return response.code == 200
    }

    override suspend fun uploadBlob(ownerToken: String, blobId: String, ciphertext: ByteArray): Boolean {
        val url = origin.newBuilder().encodedPath("/v1/blobs/$blobId").build()
        val request = Request.Builder()
            .url(url)
            .put(ciphertext.toRequestBody("application/octet-stream".toMediaType()))
            .header("Authorization", "Bearer $ownerToken")
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            response.code == 200 || response.code == 201
        }
    }

    override suspend fun downloadBlob(token: String, blobId: String): ByteArray? {
        unused(token)
        val url = origin.newBuilder().encodedPath("/v1/blobs/$blobId").build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/octet-stream")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }

    override suspend fun deleteBlob(ownerToken: String, blobId: String): Boolean {
        unused(ownerToken)
        unused(blobId)
        return false
    }

    override suspend fun fetchNetworkPolicy(): String? {
        val url = origin.newBuilder().encodedPath("/v1/network-policy").query(null).fragment(null).build()
        require(url.scheme == "https")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 405) return null
            val body = response.body?.string().orEmpty()
            if (response.code != 200) return null
            forbidDeliveryWords(body)
            return body
        }
    }

    private data class HttpResult(val code: Int, val body: String, val retryAfterSeconds: Long)

    private fun execute(
        path: String,
        method: String = "POST",
        token: String?,
        json: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResult {
        val url = origin.newBuilder().encodedPath(path).query(null).fragment(null).build()
        require(url.scheme == "https")
        val request = Request.Builder()
            .url(url)
            .method(method, json.toRequestBody(JSON))
            .header("Accept", "application/json")
            .apply {
                if (token != null) header("Authorization", "Bearer $token")
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val retry = response.header("Retry-After")?.toLongOrNull() ?: 60L
            return HttpResult(response.code, body, retry)
        }
    }

    private fun parseGrant(map: Map<*, *>): SubmitGrant {
        val typed = map.entries.associate { it.key.toString() to it.value }
        return SubmitGrant(
            grantId = typed["grant_id"] as String,
            token = typed["token"] as String,
            kind = typed["kind"] as String,
            mailboxId = typed["mailbox_id"] as String,
            expiresAt = epochNumber(typed, "expires_at", "expires_at_epoch_ms"),
        )
    }

    private fun forbidDeliveryWords(body: String) {
        val lower = body.lowercase()
        require(!lower.contains("delivered")) { "relay body must not say delivered" }
        require(!lower.contains("read_receipt")) { "relay body must not say read_receipt" }
    }

    private fun errorCode(body: String): String =
        (CanonicalJson.parseObject(body)["error"] as? String) ?: "unknown_error"

    private fun unused(value: Any?) = Unit

    private fun epochNumber(obj: Map<String, Any?>, vararg keys: String): Long {
        for (key in keys) {
            val value = obj[key]
            if (value is Number) return value.toLong()
        }
        error("relay acceptance missing ${keys.joinToString("/")}")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun parseOrigin(value: String): HttpUrl {
            require(value.startsWith("https://")) { "relay origin must be https" }
            val url = value.toHttpUrl()
            require(url.scheme == "https") { "relay origin must be https" }
            return url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        }
    }
}
