package com.codeyantra.armorchat.fcm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codeyantra.armorchat.core.database.dao.ContactDao
import com.codeyantra.armorchat.messaging.ChatMessagingService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ArmorChatSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncWorkerEntryPoint {
        fun chatMessagingService(): ChatMessagingService
        fun contactDao(): ContactDao
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        android.util.Log.d(TAG, "WorkManager sync for FCM sync_hint")
        return@withContext try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                SyncWorkerEntryPoint::class.java,
            )
            val messagingService = entryPoint.chatMessagingService()
            val contactDao = entryPoint.contactDao()

            runCatching { messagingService.pollIncomingInvites() }

            val contacts = contactDao.observeAll().first()
            var failed = false
            for (contact in contacts) {
                val ok = runCatching { messagingService.syncContact(contact.id) }.isSuccess
                if (!ok) failed = true
            }

            android.util.Log.d(TAG, "WorkManager sync finished contacts=${contacts.size}")
            if (failed) Result.retry() else Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "WorkManager sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ArmorChatSyncWorker"
    }
}
