package com.codeyantra.armorchat.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.codeyantra.armorchat.MainActivity
import com.codeyantra.armorchat.R
import com.codeyantra.armorchat.core.preferences.SettingsDataStore
import com.codeyantra.armorchat.domain.model.NotificationPrivacy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Singleton
class ArmorChatNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
) {
    @Volatile
    private var activeConversationId: String? = null

    @Volatile
    private var appForeground: Boolean = true

    @Volatile
    private var cachedNotificationPrivacy: NotificationPrivacy = NotificationPrivacy.SHOW_ALL

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    init {
        scope.launch {
            settingsDataStore.notificationPrivacy.collect { privacy ->
                cachedNotificationPrivacy = privacy
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> appForeground = true
                    Lifecycle.Event.ON_STOP -> appForeground = false
                    else -> Unit
                }
            },
        )
    }

    fun setActiveConversation(conversationId: String?) {
        activeConversationId = conversationId
        if (conversationId != null) {
            clearNotification(conversationId)
        }
    }

    fun getActiveConversationId(): String? = activeConversationId

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_messages_name)
            val descriptionText = context.getString(R.string.notification_channel_messages_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH

            val channel = NotificationChannel(CHANNEL_MESSAGES, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel $CHANNEL_MESSAGES created successfully.")
        }
    }

    fun postMessageNotification(
        conversationId: String,
        contactId: String,
        contactName: String,
        messageText: String,
        messageId: String,
        timestampEpochMs: Long,
    ) {
        // Only suppress when this conversation is actually visible in the foreground.
        // Backgrounding the chat (Home / FCM wake) must still alert.
        if (appForeground && activeConversationId == conversationId) {
            Log.d(TAG, "Skipping visible notification as conversation is in the foreground.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.d(TAG, "Skipping notification; POST_NOTIFICATIONS not granted.")
                return
            }
        }

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.d(TAG, "Skipping notification; notifications disabled.")
            return
        }

        val privacy = cachedNotificationPrivacy
        val displayTitle: String
        val displayBody: String
        when (privacy) {
            NotificationPrivacy.SHOW_ALL -> {
                displayTitle = contactName
                displayBody = messageText
            }
            NotificationPrivacy.SENDER_ONLY -> {
                displayTitle = contactName
                displayBody = "New message"
            }
            NotificationPrivacy.GENERIC -> {
                displayTitle = "ArmorChat"
                displayBody = "New ArmorChat message"
            }
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val person = Person.Builder()
            .setName(contactName)
            .setKey(contactId)
            .build()
        val shortcutId = "shortcut_conv_$conversationId"
        val shortcut = runCatching {
            val info = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(contactName)
                .setLongLabel(contactName)
                .setPerson(person)
                .setIntent(tapIntent)
                .setIsConversation()
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, info)
            info
        }.getOrNull()

        val notificationId = conversationId.hashCode()
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(displayTitle)
            .setContentText(displayBody)
            .setStyle(
                NotificationCompat.MessagingStyle(person)
                    .setConversationTitle(displayTitle)
                    .addMessage(displayBody, timestampEpochMs, person),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        if (shortcut != null) {
            builder.setShortcutInfo(shortcut)
            runCatching {
                builder.setBubbleMetadata(
                    NotificationCompat.BubbleMetadata.Builder(shortcutId)
                        .setDesiredHeight(600)
                        .build(),
                )
            }
        }

        try {
            manager.notify(notificationId, builder.build())
            Log.d(TAG, "Posted notification for conversation=$conversationId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post message notification", e)
        }
    }

    fun clearNotification(conversationId: String) {
        try {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(conversationId.hashCode())
            Log.d(TAG, "Cleared notification for conversation=$conversationId")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing notification for conversation=$conversationId", e)
        }
    }

    companion object {
        private const val TAG = "ArmorChatNotif"
        const val CHANNEL_MESSAGES = "armorchat_messages"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
