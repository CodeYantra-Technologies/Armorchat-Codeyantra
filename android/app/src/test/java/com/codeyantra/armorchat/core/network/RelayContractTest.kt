package com.codeyantra.armorchat.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayContractTest {
    @Test
    fun canonicalEnvelopeMatchesRelayJcs() {
        val envelope = ImmutableRelayEnvelope(
            messageId = "AAAAAAAAAAAAAAAAAAAAAA",
            recipientMailbox = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            expiresAt = 2_000_000_000L,
            payloadType = ImmutableRelayEnvelope.PAYLOAD_APPLICATION,
            encryptedPayloadB64 = "SGVsbG8gQXJtb3JDaGF0",
        )
        assertThat(envelope.canonicalJson()).isEqualTo(
            """{"encrypted_payload":"SGVsbG8gQXJtb3JDaGF0","expires_at":2000000000,"message_id":"AAAAAAAAAAAAAAAAAAAAAA","payload_type":"application","protocol_version":1,"recipient_mailbox":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","suite":"ac-mls-v1"}""",
        )
    }

    @Test
    fun queueStatesNeverIncludeDeliveredOrRead() {
        assertThat(RelayQueueState.entries.map { it.name }).containsExactly(
            "ACCEPTED",
            "CONSUMED",
            "EXPIRED",
            "UNKNOWN",
        ).inOrder()
        assertThrows(IllegalStateException::class.java) { parseRelayQueueState("delivered") }
        assertThrows(IllegalStateException::class.java) { parseRelayQueueState("read") }
    }

    @Test
    fun httpsOriginRequired() {
        assertThrows(IllegalArgumentException::class.java) {
            CodeYantraRelayTransport.parseOrigin("http://relay.example")
        }
        val url = CodeYantraRelayTransport.parseOrigin("https://relay.example")
        assertThat(url.scheme).isEqualTo("https")
        assertThat(url.encodedPath).isEqualTo("/")
    }

    @Test
    fun noopAndRelayKeepChatSendDisabled() = runTest {
        val noop = NoOpMessageTransport()
        assertThat(noop.isAvailable.first()).isFalse()
        assertThat(noop.submitEnvelope(sample())).isEqualTo(RelaySubmitResult.Unavailable)
        val relay = CodeYantraRelayTransport(
            origin = CodeYantraRelayTransport.parseOrigin("https://relay.example"),
        )
        assertThat(relay.isAvailable.first()).isFalse()
        assertThat(relay.send(OutboundEnvelope("conv-local", ByteArray(0))))
            .isEqualTo(TransportResult.Unavailable)
        assertThat(relay.submitEnvelope(sample())).isEqualTo(RelaySubmitResult.Unavailable)
    }

    @Test
    fun pushRegisterMatchesRelayContractAndDoesNotEchoToken() = runTest {
        val token = "test-fcm-token-APA91b-not-a-real-device-token"
        val deviceId = "AAAAAAAAAAAAAAAAAAAAAA"
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertThat(request.url.encodedPath).isEqualTo("/v1/push/register")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.header("Authorization")).isEqualTo("Bearer test-owner")
            assertThat(request.url.query).isNull()
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            val raw = buffer.readUtf8()
            val obj = com.codeyantra.armorchat.core.json.CanonicalJson.parseObject(raw)
            assertThat(obj.keys).containsExactly("protocol_version", "device_id", "fcm_token")
            assertThat(obj["protocol_version"]).isEqualTo(1L)
            assertThat(obj["device_id"]).isEqualTo(deviceId)
            assertThat(obj["fcm_token"]).isEqualTo(token)
            assertThat(raw).isEqualTo(com.codeyantra.armorchat.core.json.CanonicalJson.of(obj))
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null, """{"protocol_version":1,"state":"accepted"}"""))
                .build()
        }.build()
        val transport = CodeYantraRelayTransport(
            origin = CodeYantraRelayTransport.parseOrigin("https://relay.example"),
            client = client,
        )
        assertThat(transport.registerPush("test-owner", deviceId, token)).isTrue()
    }

    @Test
    fun envelopeSubmitUsesProvenEnvelopesPathNotInboxSubmit() = runTest {
        var submittedPath: String? = null
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            submittedPath = request.url.encodedPath
            assertThat(request.url.encodedPath).isEqualTo("/v1/envelopes")
            assertThat(request.url.encodedPath).isNotEqualTo("/v1/inbox/submit")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.header("Authorization")).isEqualTo("Bearer test-submit")
            assertThat(request.header("Idempotency-Key")).isEqualTo(sample().messageId)
            assertThat(request.url.query).isNull()
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(400).message("Bad Request")
                .body(
                    okhttp3.ResponseBody.create(
                        null,
                        """{"error":"MALFORMED","protocol_version":1}""",
                    ),
                )
                .build()
        }.build()
        val transport = CodeYantraRelayTransport(
            origin = CodeYantraRelayTransport.parseOrigin("https://relay.example"),
            client = client,
        )
        val result = transport.submitEnvelope("test-submit", sample())
        assertThat(submittedPath).isEqualTo("/v1/envelopes")
        assertThat(result).isEqualTo(RelaySubmitResult.Rejected("MALFORMED"))
    }

    @Test
    fun blobMethodsUseDedicatedPathAndNeverEnvelopes() = runTest {
        val seen = mutableListOf<String>()
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen += "${request.method} ${request.url.encodedPath}"
            assertThat(request.url.encodedPath).startsWith("/v1/blobs/")
            assertThat(request.url.encodedPath).doesNotContain("envelopes")
            assertThat(request.url.query).isNull()
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(404).message("Not Found")
                .body(okhttp3.ResponseBody.create(null, """{"error":"NOT_FOUND","protocol_version":1}"""))
                .build()
        }.build()
        val transport = CodeYantraRelayTransport(
            origin = CodeYantraRelayTransport.parseOrigin("https://relay.example"),
            client = client,
        )
        assertThat(transport.uploadBlob("owner", "blob_1", byteArrayOf(1, 2, 3))).isFalse()
        assertThat(transport.downloadBlob("token", "blob_1")).isNull()
        assertThat(transport.deleteBlob("owner", "blob_1")).isFalse()
        assertThat(seen).containsExactly("PUT /v1/blobs/blob_1", "GET /v1/blobs/blob_1")
    }

    @Test
    fun invitePublishMatchesRelayContract() = runTest {
        val token = com.codeyantra.armorchat.domain.identity.InviteToken.generate()
        val bundle = """{"v":3}"""
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertThat(request.url.encodedPath).isEqualTo("/v1/invites")
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.header("Authorization")).isEqualTo("Bearer test-owner")
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            val raw = buffer.readUtf8()
            val obj = com.codeyantra.armorchat.core.json.CanonicalJson.parseObject(raw)
            assertThat(obj).containsKey("protocol_version")
            assertThat(obj).containsKey("token")
            assertThat(obj).containsKey("bundle")
            assertThat(obj).containsKey("expires_at")
            assertThat((obj["protocol_version"] as Number).toLong()).isEqualTo(1L)
            assertThat(obj["token"]).isEqualTo(token)
            assertThat(obj["bundle"]).isEqualTo(bundle)
            assertThat((obj["expires_at"] as Number).toLong()).isEqualTo(2_000_000_000L)
            assertThat(raw).isEqualTo(com.codeyantra.armorchat.core.json.CanonicalJson.of(obj))
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(201).message("Created")
                .body(okhttp3.ResponseBody.create(null, """{"protocol_version":1,"state":"accepted"}"""))
                .build()
        }.build()
        val transport = CodeYantraRelayTransport(
            origin = CodeYantraRelayTransport.parseOrigin("https://relay.example"),
            client = client,
        )
        assertThat(transport.putInvite("test-owner", token, bundle, 2_000_000_000L)).isTrue()
    }

    private fun sample() = ImmutableRelayEnvelope(
        messageId = "AAAAAAAAAAAAAAAAAAAAAA",
        recipientMailbox = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        expiresAt = 2_000_000_000L,
        payloadType = ImmutableRelayEnvelope.PAYLOAD_APPLICATION,
        encryptedPayloadB64 = "SGVsbG8gQXJtb3JDaGF0",
    )
}
