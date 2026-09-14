package com.codeyantra.armorchat.messaging

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class SecretLoggingTest {
    @Test
    fun safeLogRejectsPrivateMaterial() {
        SafeLog.assertSafe("outbound SENT epoch=2")
        assertThrows(IllegalArgumentException::class.java) { SafeLog.assertSafe("owner_token=abc") }
        assertThrows(IllegalArgumentException::class.java) { SafeLog.assertSafe("submit_capability leaked") }
        assertThrows(IllegalArgumentException::class.java) { SafeLog.assertSafe("identity private_key dump") }
    }

    @Test
    fun messagingSourcesDoNotLogSecrets() {
        val root = File("src/main/java/com/codeyantra/armorchat")
        assertThat(root.isDirectory).isTrue()
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("import ")) {
                    return@forEachIndexed
                }
                val lower = line.lowercase()
                val logs = lower.contains("android.util.log") ||
                    Regex("""\bLog\.(d|i|w|e|v|wtf)\s*\(""").containsMatchIn(line)
                if (!logs) return@forEachIndexed
                if (listOf("owner_token", "submit_capability", "private_key", "privatekey", "hpke", "fcm_token").any {
                        lower.contains(it)
                    }
                ) {
                    hits += "${file.name}:${index + 1}:$trimmed"
                }
            }
        }
        assertThat(hits).isEmpty()
    }
}
