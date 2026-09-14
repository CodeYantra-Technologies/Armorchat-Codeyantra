package com.codeyantra.armorchat.fcm

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * FCM is a generic wake hint only. It is not message transport and must not
 * carry plaintext, sender names, ArmorChat IDs, mailbox IDs, MLS data,
 * credentials, or previews.
 */
object FcmWake {
    const val TYPE = "sync_hint"
    const val VERSION = "1"
    const val WORK_NAME = "armorchat_fcm_sync"

    fun isSyncHint(data: Map<String, String>): Boolean =
        data["type"] == TYPE && data["v"] == VERSION

    fun fingerprint(token: String): String {
        return try {
            val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { b -> "%02x".format(b) }.take(16)
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ArmorChatSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
