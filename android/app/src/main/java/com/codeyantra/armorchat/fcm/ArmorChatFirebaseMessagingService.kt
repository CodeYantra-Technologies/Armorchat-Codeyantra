package com.codeyantra.armorchat.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class ArmorChatFirebaseMessagingService : FirebaseMessagingService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FcmEntryPoint {
        fun pushTokenRegistrar(): PushTokenRegistrar
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val fingerprint = FcmWake.fingerprint(token)
        android.util.Log.d(TAG, "New FCM token. fp=$fingerprint")
        entryPoint().pushTokenRegistrar().onNewToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        android.util.Log.d(TAG, "FCM data keys=${remoteMessage.data.keys}")
        if (FcmWake.isSyncHint(remoteMessage.data)) {
            android.util.Log.d(TAG, "Received type=$SYNC_HINT. Enqueue WorkManager sync.")
            FcmWake.enqueue(applicationContext)
        }
    }

    private fun entryPoint(): FcmEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, FcmEntryPoint::class.java)

    companion object {
        private const val TAG = "ArmorChatFCM"
        const val SYNC_HINT = FcmWake.TYPE
        const val WORK_NAME = FcmWake.WORK_NAME

        fun getSha256Fingerprint(token: String): String = FcmWake.fingerprint(token)
    }
}
