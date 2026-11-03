package com.callio.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callio.app.service.CallActionReceiver

/**
 * Dedicated lock-screen UI for incoming Callio audio calls.
 * The actual microphone/WebRTC start is handed to MainActivity only after
 * the user presses Answer, so Android sees a user-initiated foreground action.
 */
class IncomingCallActivity : ComponentActivity() {
    private var callId: String = ""
    private var callerName: String = "Callio friend"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        readIntent(intent)
        if (callId.isBlank()) {
            finish()
            return
        }
        setContent {
            MaterialTheme {
                IncomingCallLockScreen(
                    callerName = callerName,
                    onAccept = { acceptCall() },
                    onDecline = { declineCall() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(source: Intent?) {
        callId = source?.getStringExtra(MainActivity.EXTRA_CALL_ID).orEmpty()
        callerName = source?.getStringExtra(MainActivity.EXTRA_CALLER_NAME)
            ?.takeIf { it.isNotBlank() } ?: "Callio friend"
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun acceptCall() {
        val open = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_ACCEPT_INCOMING
            putExtra(MainActivity.EXTRA_CALL_ID, callId)
            putExtra(MainActivity.EXTRA_CALLER_NAME, callerName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(open)
        finish()
    }

    private fun declineCall() {
        sendBroadcast(Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE
            putExtra(MainActivity.EXTRA_CALL_ID, callId)
        })
        finish()
    }
}

@Composable
private fun IncomingCallLockScreen(
    callerName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val background = Color(0xFF0B0A12)
    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF30234D), background, background))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("CALLIO", color = Color(0xFFBDA7FF), fontSize = 13.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(58.dp))
            Surface(
                modifier = Modifier.size(124.dp),
                shape = CircleShape,
                color = Color(0xFFBDA7FF)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(callerName.take(1).uppercase(), color = Color(0xFF171222), fontSize = 48.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("INCOMING AUDIO CALL", color = Color(0xFFBDA7FF), fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(callerName, color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("is calling you", color = Color(0xFFB8B3C5), fontSize = 15.sp)
            Spacer(Modifier.height(76.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(58.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onDecline,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE34F68)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) { Text("✕", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(9.dp))
                    Text("Decline", color = Color.White, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onAccept,
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27C77B)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) { Text("✓", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(9.dp))
                    Text("Accept", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}
