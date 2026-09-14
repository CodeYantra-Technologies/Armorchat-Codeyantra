package com.codeyantra.armorchat.fcm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FcmWakeTest {
    @Test
    fun onlyGenericSyncHintIsAccepted() {
        assertThat(FcmWake.isSyncHint(mapOf("type" to "sync_hint", "v" to "1"))).isTrue()
        assertThat(FcmWake.isSyncHint(mapOf("type" to "sync_hint"))).isFalse()
        assertThat(FcmWake.isSyncHint(mapOf("v" to "1"))).isFalse()
        assertThat(FcmWake.isSyncHint(mapOf("type" to "message", "v" to "1"))).isFalse()
        assertThat(FcmWake.isSyncHint(mapOf("event" to "relay_data_available", "v" to "1"))).isFalse()
    }

    @Test
    fun extraFcmKeysAreIgnoredAndDoNotBecomeTransport() {
        val data = mapOf(
            "type" to "sync_hint",
            "v" to "1",
            "preview" to "hello",
            "mailbox_id" to "should-be-ignored",
        )
        assertThat(FcmWake.isSyncHint(data)).isTrue()
        assertThat(data["type"]).isEqualTo("sync_hint")
        assertThat(data["v"]).isEqualTo("1")
    }

    @Test
    fun fingerprintIsNotTheToken() {
        val token = "test-fcm-token-APA91b-not-a-real-device-token"
        val fp = FcmWake.fingerprint(token)
        assertThat(fp).hasLength(16)
        assertThat(fp).isNotEqualTo(token)
        assertThat(token.contains(fp)).isFalse()
    }
}
