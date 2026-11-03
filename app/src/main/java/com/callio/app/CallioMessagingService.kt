package com.callio.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.callio.app.MainActivity
import com.callio.app.R
import com.callio.app.data.CallioRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class CallioMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        CallioRepository().savePushToken(uid, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val callId = message.data["callId"] ?: return
        val callerName = message.data["callerName"] ?: message.data["title"] ?: "Callio friend"
        showIncomingCall(callId, callerName)
    }

    private fun showIncomingCall(callId: String, callerName: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val sound = Uri.parse("android.resource://$packageName/${R.raw.callio_ringtone}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val channel = NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Callio audio call alerts"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(sound, attributes)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 800)
                }
                manager.createNotificationChannel(channel)
            }
        }

        // Full-screen notification opens a dedicated lock-screen call UI.
        val fullScreenIntent = Intent(this, com.callio.app.IncomingCallActivity::class.java).apply {
            action = ACTION_SHOW_INCOMING_CALL
            putExtra(MainActivity.EXTRA_CALL_ID, callId)
            putExtra(MainActivity.EXTRA_CALLER_NAME, callerName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, callId.hashCode(), fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Notification's Answer action goes to MainActivity with an explicit auto-answer request.
        val answerIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_ACCEPT_INCOMING
            putExtra(MainActivity.EXTRA_CALL_ID, callId)
            putExtra(MainActivity.EXTRA_CALLER_NAME, callerName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val answerPending = PendingIntent.getActivity(
            this, callId.hashCode() + 2, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE
            putExtra(MainActivity.EXTRA_CALL_ID, callId)
        }
        val declinePending = PendingIntent.getBroadcast(this, callId.hashCode() + 1, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val person = Person.Builder().setName(callerName).setImportant(true).build()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_callio)
            .setContentTitle("Incoming Call")
            .setContentText("$callerName is calling you on Callio")
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setTimeoutAfter(45_000L)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(Notification.CallStyle.forIncomingCall(person, declinePending, answerPending))
        } else {
            builder.addAction(Notification.Action.Builder(null, "Decline", declinePending).build())
            builder.addAction(Notification.Action.Builder(null, "Answer", answerPending).build())
        }
        NotificationManagerCompat.from(this).notify(callId.hashCode(), builder.build())
    }

    companion object {
        // v2 forces Android to create a fresh high-importance channel after upgrading
        // from the earlier build whose notification channel settings are already stored.
        const val CHANNEL_ID = "callio_incoming_calls_v2"
        const val ACTION_SHOW_INCOMING_CALL = "com.callio.app.SHOW_INCOMING_CALL"
    }
}
