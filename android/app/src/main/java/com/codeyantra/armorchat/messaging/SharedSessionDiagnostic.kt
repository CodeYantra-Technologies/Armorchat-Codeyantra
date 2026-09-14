package com.codeyantra.armorchat.messaging

/** Debug-only, fixed-vocabulary metadata. Never accepts payloads or credentials as details. */
internal object SharedSessionDiagnostic {
    enum class Stage {
        fresh_kp_published, fresh_route_imported, initiator_selected, group_created,
        add_member_ok, welcome_submitted, welcome_received, welcome_sender_verified,
        welcome_keypackage_match, welcome_join_started, welcome_joined,
        setup_route_sent, setup_route_received, session_id_match, session_confirmed,
    }

    fun stage(stage: Stage, peer: String?, attempt: String, pass: Boolean = true, category: Category? = null) {
        if (!com.codeyantra.armorchat.BuildConfig.DEBUG) return
        val suffix = category?.let { " category=${it.name}" }.orEmpty()
        SafeLog.event("shared_mls", "stage=${stage.name} result=${if (pass) "PASS" else "FAIL"} peer=${SafeLog.shortRef(peer)} attempt=${SafeLog.shortRef(attempt)}$suffix")
    }

    enum class Category {
        key_package_not_found, fresh_pool_mismatch, signature, confirmation_tag,
        ratchet_tree_missing, cipher_suite, snapshot, session_selection, decode,
        rejected, native_other, diagnostic_unavailable,
    }

    fun category(err: Throwable): Category {
        val text = (err.message.orEmpty() + " " + err.cause?.message.orEmpty()).lowercase()
        return when {
            "key package not found" in text -> Category.key_package_not_found
            "invalid signature" in text -> Category.signature
            "confirmation tag" in text -> Category.confirmation_tag
            "ratchet tree" in text -> Category.ratchet_tree_missing
            "cipher suite" in text -> Category.cipher_suite
            "restore:" in text || "persist_decode" in text || "empty_snapshot" in text -> Category.snapshot
            "select_session" in text -> Category.session_selection
            "decode" in text -> Category.decode
            err is SecurityException -> Category.rejected
            else -> Category.native_other
        }
    }
}
