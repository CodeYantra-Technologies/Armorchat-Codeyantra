package com.codeyantra.armorchat.domain.identity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArmorChatHandleTest {
    @Test
    fun normalizesDisplayAndCanonicalForms() {
        val handle = ArmorChatHandle.parse("@Neeraj-Veltrix")
        assertThat(handle).isNotNull()
        assertThat(handle!!.canonical).isEqualTo("neeraj-veltrix")
        assertThat(handle.display()).isEqualTo("@neeraj-veltrix")
    }

    @Test
    fun rejectsReservedUnicodeSpacesAndConfusableScripts() {
        assertThat(ArmorChatHandle.parse("admin")).isNull()
        assertThat(ArmorChatHandle.parse("armorchat")).isNull()
        assertThat(ArmorChatHandle.parse("n eeraj")).isNull()
        assertThat(ArmorChatHandle.parse("неeraj")).isNull()
        assertThat(ArmorChatHandle.parse("neeraj--veltrix")).isNull()
        assertThat(ArmorChatHandle.parse("-neeraj")).isNull()
        assertThat(ArmorChatHandle.parse("neeraj-")).isNull()
        assertThat(ArmorChatHandle.parse("ab")).isNull()
    }

    @Test
    fun suggestsFromDisplayName() {
        assertThat(ArmorChatHandle.suggest("Neeraj Veltrix")).isEqualTo("neeraj-veltrix")
        assertThat(ArmorChatHandle.suggest("Neeraj")).isEqualTo("neeraj")
    }

    @Test
    fun handleIsNotDerivedFromCryptographicIdentity() {
        val handle = ArmorChatHandle.parse("neeraj-veltrix")!!.canonical
        val pk = ByteArray(32) { 7 }
        val id = ArmorChatId.derive(pk)
        assertThat(id).doesNotContain(handle)
        assertThat(handle).doesNotContain("ac1")
    }
}
