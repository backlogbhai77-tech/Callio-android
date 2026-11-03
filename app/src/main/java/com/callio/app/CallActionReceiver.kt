package com.callio.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.callio.app.MainActivity
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(MainActivity.EXTRA_CALL_ID) ?: return
        if (intent.action == ACTION_DECLINE) {
            val pending = goAsync()
            FirebaseFirestore.getInstance().collection("calls").document(callId).update(
                mapOf("status" to "rejected", "endedAt" to FieldValue.serverTimestamp())
            ).addOnCompleteListener {
                NotificationManagerCompat.from(context).cancel(callId.hashCode())
                pending.finish()
            }
        }
    }
    companion object { const val ACTION_DECLINE = "com.callio.app.ACTION_DECLINE_CALL" }
}
