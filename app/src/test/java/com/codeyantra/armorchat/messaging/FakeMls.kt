package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.domain.identity.Base64Url

/**
 * Deterministic stand-in for mls-rs JNI. Used only to exercise session
 * orchestration without loading native libraries.
 *
 * KeyPackages are unique per GenerateKeyPackage call. Groups are independent
 * 1:1 sessions; ciphertext is bound to the session id so material cannot be
 * reused across peers.
 */
object FakeMls {
    fun transact(state: ByteArray, request: String): ByteArray {
        val req = CanonicalJson.parseObject(request)
        val op = req["op"] as String
        val current = if (state.isEmpty()) mutableMapOf<String, Any?>() else {
            CanonicalJson.parseObject(String(state, Charsets.UTF_8)).toMutableMap()
        }
        @Suppress("UNCHECKED_CAST")
        val groups = ((current["groups"] as? Map<String, Any?>) ?: emptyMap()).toMutableMap()
        var active = current["active"] as? String
        req["session_id"]?.let { sid ->
            val id = sid as String
            require(groups.containsKey(id)) { "unknown session" }
            active = id
        }
        val output: Map<String, Any?> = when (op) {
            "Create" -> {
                val pin = req["pin"] as String
                current["pin"] = pin
                current["kp_n"] = 1L
                val kp = uniqueKp(pin, 1L)
                current["kp"] = kp
                current["kps"] = listOf(kp)
                current["epoch"] = 0L
                current["session_id"] = ""
                current["group"] = 0L
                current["groups"] = emptyMap<String, Any?>()
                current.remove("active")
                mapOf(
                    "body" to mapOf(
                        "identity_key" to pin,
                        "key_package" to kp,
                        "suite" to "ac-mls-v1",
                        "v" to 1L,
                    ),
                    "pin" to pin,
                )
            }
            "GenerateKeyPackage" -> {
                val pin = current["pin"] as String
                val n = (current["kp_n"] as Long) + 1L
                current["kp_n"] = n
                val kp = uniqueKp(pin, n)
                current["kp"] = kp
                @Suppress("UNCHECKED_CAST")
                val kps = ((current["kps"] as? List<String>) ?: emptyList()).toMutableList()
                require(kp !in kps) { "key package reused" }
                kps += kp
                current["kps"] = kps
                mapOf(
                    "body" to mapOf(
                        "identity_key" to pin,
                        "key_package" to kp,
                        "suite" to "ac-mls-v1",
                        "v" to 1L,
                    ),
                    "pin" to pin,
                )
            }
            "Bind" -> mapOf("ok" to 1L)
            "CreateGroup" -> {
                val sid = Base64Url.encode(("G-${current["pin"]}-${groups.size}").toByteArray())
                groups[sid] = mapOf("epoch" to 1L)
                active = sid
                current["session_id"] = sid
                current["epoch"] = 1L
                current["group"] = 1L
                mapOf("epoch" to 1L, "session_id" to sid)
            }
            "AddMember" -> {
                val sid = active ?: error("group required")
                val welcome = Base64Url.encode(
                    "WELCOME|$sid|${req["peer_pin"]}|${req["peer_binding"]}".toByteArray(),
                )
                mapOf(
                    "welcome" to welcome,
                    "epoch" to 1L,
                    "session_id" to sid,
                )
            }
            "Join" -> {
                val welcome = String(Base64Url.decode(req["welcome"] as String), Charsets.UTF_8)
                val parts = welcome.split("|")
                val sid = parts.getOrElse(1) { error("welcome session") }
                groups[sid] = mapOf("epoch" to 1L)
                active = sid
                current["session_id"] = sid
                current["epoch"] = 1L
                current["group"] = 1L
                mapOf("epoch" to 1L, "session_id" to sid)
            }
            "Encrypt" -> {
                val sid = active ?: error("group required")
                val pt = req["plaintext"] as String
                val ct = Base64Url.encode("E|$sid|$pt".toByteArray())
                require(ct != pt)
                mapOf(
                    "ciphertext" to ct,
                    "epoch" to 1L,
                    "session_id" to sid,
                )
            }
            "Decrypt" -> {
                val sid = active ?: error("group required")
                val raw = String(Base64Url.decode(req["ciphertext"] as String), Charsets.UTF_8)
                require(raw.startsWith("E|"))
                val parts = raw.split("|", limit = 3)
                require(parts.size == 3 && parts[1] == sid) { "ciphertext session mismatch" }
                mapOf(
                    "plaintext" to parts[2],
                    "epoch" to 1L,
                    "session_id" to sid,
                )
            }
            "Session", "Epoch" -> {
                val sid = active ?: error("group required")
                mapOf("epoch" to 1L, "session_id" to sid)
            }
            else -> error("unknown op")
        }
        current["groups"] = groups
        if (active != null) {
            current["active"] = active
        } else {
            current.remove("active")
        }
        val packed = CanonicalJson.of(current).toByteArray()
        val encoded = CanonicalJson.of(
            mapOf(
                "output" to output,
                "state" to Base64Url.encode(packed),
            ),
        )
        return encoded.toByteArray()
    }

    private fun uniqueKp(pin: String, n: Long): String = Base64Url.encode("KP-$pin-$n".toByteArray())
}
