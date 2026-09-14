package com.codeyantra.armorchat.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayRegistryTest {
    @Test
    fun defaultBootstrapIsNode1ButNotTheOnlyPossibleOrigin() {
        val defaults = DefaultRelayRegistry()
        assertThat(defaults.bootstrap().origin).isEqualTo(CodeYantraRelays.NODE_1_ORIGIN)
        assertThat(defaults.bootstrap().id).isEqualTo(CodeYantraRelays.NODE_1_ID)
        assertThat(defaults.isAuthorizedOrigin("https://relay1.codeyantra.in")).isTrue()
        assertThat(defaults.isAuthorizedOrigin("https://relay-other.example")).isFalse()
    }

    @Test
    fun bootstrapCanBeADifferentAuthorizedNode() {
        val registry = DefaultRelayRegistry(
            listOf(
                RelayNode("node-x", "https://relay-x.example", RelayRole.BOOTSTRAP),
                RelayNode("node-y", "https://relay-y.example", RelayRole.AUTHORIZED),
            ),
        )
        assertThat(registry.bootstrap().origin).isEqualTo("https://relay-x.example")
        assertThat(registry.bootstrap().origin).isNotEqualTo(CodeYantraRelays.NODE_1_ORIGIN)
        assertThat(registry.isAuthorizedOrigin("https://relay-y.example")).isTrue()
        assertThat(registry.isAuthorizedOrigin(CodeYantraRelays.NODE_1_ORIGIN)).isFalse()
    }

    @Test
    fun providerSelectsByRegistryNotByHardCodedRelay1() {
        val network = InMemoryRelayNetwork("https://relay-x.example")
        val registry = DefaultRelayRegistry(
            listOf(RelayNode("node-x", "https://relay-x.example", RelayRole.BOOTSTRAP)),
        )
        val provider = DefaultRelayProvider(registry) { network.client() }
        assertThat(provider.bootstrapClient().originHttps).isEqualTo("https://relay-x.example")
        assertThat(provider.bootstrapClient().originHttps).isNotEqualTo(CodeYantraRelays.NODE_1_ORIGIN)
        assertThrows(IllegalArgumentException::class.java) {
            provider.clientFor(CodeYantraRelays.NODE_1_ORIGIN)
        }
    }

    @Test
    fun communityRelaysAreRejectedUntilImplemented() {
        assertThrows(IllegalArgumentException::class.java) {
            DefaultRelayRegistry(
                listOf(RelayNode("c1", "https://community.example", RelayRole.COMMUNITY)),
            )
        }
    }
}
