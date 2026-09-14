package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.domain.identity.IdentityQrPayload
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RouteInvitationTest {
    @Test
    fun identityQrParserRejectsRouteInvite() {
        val route = """{"p":"armorchat-route","v":1,"route":{"body":{},"signature":"x"}}"""
        val result = IdentityQrPayload.parse(route)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun httpsOriginRequiredOnIssue() {
        assertThrows(IllegalArgumentException::class.java) {
            val signer = object : IdentitySigner {
                override val pin = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                override fun sign(context: String, bodyCanonical: ByteArray) = ByteArray(64)
            }
            RouteInvitation.issue(
                signer = signer,
                relayOrigin = "http://relay.example",
                mailboxId = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                submitCapability = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                deviceId = "AAAAAAAAAAAAAAAAAAAAAA",
                recipientPin = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                keyPackageBinding = "{}",
                nowEpochSeconds = 1_700_000_000,
            )
        }
    }
}
