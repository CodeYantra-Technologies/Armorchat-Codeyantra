package com.codeyantra.armorchat

import android.app.Application
import android.util.Log
import com.codeyantra.armorchat.fcm.FcmWake
import com.codeyantra.armorchat.fcm.PushTokenRegistrar
import com.codeyantra.armorchat.notification.ArmorChatNotificationManager
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ArmorChatApplication : Application() {
    @Inject lateinit var pushTokenRegistrar: PushTokenRegistrar
    @Inject lateinit var notificationManager: ArmorChatNotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannels()
        initFirebase()
    }

    private fun initFirebase() {
        try {
            FirebaseApp.initializeApp(this)
            Log.d(TAG, "Firebase initialized successfully.")
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Fetching FCM registration token failed")
                    return@addOnCompleteListener
                }
                val token = task.result ?: return@addOnCompleteListener
                val fingerprint = FcmWake.fingerprint(token)
                Log.i(TAG, "FCM token ready. fp=$fingerprint")
                pushTokenRegistrar.onNewToken(token)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization failed", e)
        }
    }

    companion object {
        private const val TAG = "ArmorChatApp"
    }
}
