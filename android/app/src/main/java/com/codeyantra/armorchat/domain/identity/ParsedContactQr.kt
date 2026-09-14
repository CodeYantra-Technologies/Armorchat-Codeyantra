package com.codeyantra.armorchat.domain.identity

import com.codeyantra.armorchat.core.network.RelayRegistry

sealed interface ParsedContactQr {
    val armorChatId: String
    val publicKeyBase64Url: String
    val version: Int
    val handle: String? get() = null
    val displayName: String? get() = null

    data class V3(val bundle: ContactBundleV3) : ParsedContactQr {
        override val armorChatId: String get() = bundle.id
        override val publicKeyBase64Url: String get() = bundle.publicKeyBase64Url
        override val version: Int get() = 3
        override val handle: String? get() = bundle.handle
        override val displayName: String? get() = bundle.displayName
    }

    data class V3Invite(val qr: ContactQrV3) : ParsedContactQr {
        override val armorChatId: String get() = ""
        override val publicKeyBase64Url: String get() = ""
        override val version: Int get() = 3
    }

    data class V2(val payload: ContactQrV2) : ParsedContactQr {
        override val armorChatId: String get() = payload.id
        override val publicKeyBase64Url: String get() = payload.publicKeyBase64Url
        override val version: Int get() = 2
    }

    data class V1(val payload: IdentityQrPayload) : ParsedContactQr {
        override val armorChatId: String get() = payload.id
        override val publicKeyBase64Url: String get() = payload.publicKeyBase64Url
        override val version: Int get() = 1
    }
}

object ContactQrParser {
    fun parse(
        raw: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        relayRegistry: RelayRegistry? = null,
    ): Result<ParsedContactQr> = runCatching {
        val v3 = ContactQrV3.parse(raw)
        if (v3 != null) return@runCatching ParsedContactQr.V3Invite(v3)
        runCatching {
            val v2 = ContactQrV2.parse(raw, nowEpochSeconds, relayRegistry)
            ParsedContactQr.V2(v2)
        }.getOrElse { v2Error ->
            val v1 = IdentityQrPayload.parse(raw).getOrNull()
            if (v1 != null) {
                ParsedContactQr.V1(v1)
            } else {
                throw v2Error
            }
        }
    }
}
