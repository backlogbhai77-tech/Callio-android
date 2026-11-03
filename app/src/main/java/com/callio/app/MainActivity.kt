package com.callio.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.callio.app.calling.AudioCallClient
import com.callio.app.data.CallioFriend
import com.callio.app.data.CallioRepository
import com.callio.app.data.IncomingCall
import com.callio.app.service.CallioActiveCallService
import com.callio.app.ui.CallioApp
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : ComponentActivity() {
    private val repository by lazy { CallioRepository() }
    private var authListener: com.google.firebase.auth.FirebaseAuth.AuthStateListener? = null
    private var friendsListener: ListenerRegistration? = null
    private var requestsListener: ListenerRegistration? = null
    private var incomingListener: ListenerRegistration? = null
    private var callClient: AudioCallClient? = null
    private var ringtone: Ringtone? = null
    private var pendingIncomingId: String? = null
    private var lastIncomingNotificationId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ringTimeout: Runnable? = null
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var currentUser by androidx.compose.runtime.mutableStateOf<FirebaseUser?>(null)
    private var friends by androidx.compose.runtime.mutableStateOf<List<CallioFriend>>(emptyList())
    private var requests by androidx.compose.runtime.mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var incomingCall by androidx.compose.runtime.mutableStateOf<IncomingCall?>(null)
    private var callState by androidx.compose.runtime.mutableStateOf("")
    private var callPerson by androidx.compose.runtime.mutableStateOf("")
    private var callMuted by androidx.compose.runtime.mutableStateOf(false)
    private var callSpeaker by androidx.compose.runtime.mutableStateOf(false)
    private var toastMessage by androidx.compose.runtime.mutableStateOf("")
    private var profileName by androidx.compose.runtime.mutableStateOf("")
    private var profileUsername by androidx.compose.runtime.mutableStateOf("")
    private val updateManager by lazy { CallioUpdateManager(applicationContext) }
    private var updateInfo by androidx.compose.runtime.mutableStateOf(CallioUpdateInfo())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        authListener = repository.listenAuth { user -> runOnUiThread { onUserChanged(user) } }
        handleCallIntent(intent)
        setContent {
            CallioApp(
                user = currentUser,
                savedDisplayName = profileName,
                savedUsername = profileUsername,
                friends = friends,
                requests = requests,
                incomingCall = incomingCall,
                callState = callState,
                callPerson = callPerson,
                callMuted = callMuted,
                callSpeaker = callSpeaker,
                toastMessage = toastMessage,
                onSignIn = { email, password -> repository.signIn(email, password) { showMessage(it ?: "Welcome back") } },
                onSignUp = { name, email, password -> repository.signUp(name, email, password) { showMessage(it ?: "Account created") } },
                onSignOut = { callClient?.end("ended"); finishLocalCall(); repository.signOut() },
                onCall = { friend -> startCall(friend) },
                onSearchAndAdd = { username -> repository.searchUsername(username) { friend, error ->
                    if (friend != null) repository.sendFriendRequest(friend.uid) { result -> showMessage(result ?: "Friend request sent") }
                    else showMessage(error ?: "User not found")
                } },
                onAcceptRequest = { id -> repository.acceptFriendRequest(id) { showMessage(it ?: "Friend added") } },
                onSaveProfile = { name, username -> repository.saveUsername(username, name) { showMessage(it ?: "Profile saved") } },
                onAcceptCall = { acceptIncomingCall() },
                onRejectCall = { rejectIncomingCall() },
                onEndCall = { endCall() },
                onToggleMute = { toggleMute() },
                onToggleSpeaker = { toggleSpeaker() },
                onClearMessage = { toastMessage = "" },
                updateInfo = updateInfo,
                onCheckUpdates = { checkForUpdates() },
                onOpenUpdate = { openUpdatePage() }
            )
        }
        checkForUpdates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent)
    }

    private fun checkForUpdates() {
        updateManager.check { result -> updateInfo = result }
    }

    private fun openUpdatePage() {
        val target = updateInfo.updateUrl.ifBlank { CallioUpdateManager.RELEASES_URL }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CallioUpdateManager.RELEASES_URL)))
        }
    }

    private fun onUserChanged(user: FirebaseUser?) {
        currentUser = user
        friendsListener?.remove(); requestsListener?.remove(); incomingListener?.remove()
        callClient?.close(); callClient = null
        incomingCall = null; friends = emptyList(); requests = emptyList(); callState = ""
        if (user == null) return
        repository.registerPushToken()
        pendingIncomingId?.let { loadIncomingFromNotification(it) }
        friendsListener = repository.listenFriends(user.uid, { rows -> runOnUiThread { friends = rows } }, { showMessage(it) })
        requestsListener = repository.listenIncomingRequests(user.uid) { rows -> runOnUiThread { requests = rows } }
        incomingListener = repository.listenIncomingCalls(user.uid) { call ->
            runOnUiThread {
                incomingCall = call
                if (call != null) {
                    lastIncomingNotificationId = call.id
                    if (callState.isBlank()) startRingtone()
                } else {
                    lastIncomingNotificationId?.let { NotificationManagerCompat.from(this).cancel(it.hashCode()) }
                    lastIncomingNotificationId = null
                    stopRingtone()
                }
            }
        }
        repository.loadProfile(user.uid) { name, username -> runOnUiThread { profileName = name; profileUsername = username } }
        profileName = user.displayName ?: ""
        callClient = AudioCallClient(this).also { client ->
            client.onState = { state -> runOnUiThread {
                callState = state
                if (state in listOf("ringing", "connecting", "reconnecting")) {
                    ringTimeout?.let { mainHandler.removeCallbacks(it) }
                    val timeoutState = state
                    val timeout = Runnable {
                        if (callState == timeoutState) {
                            val status = if (timeoutState == "ringing") "missed" else "failed"
                            callClient?.end(status); finishLocalCall()
                            showMessage(if (status == "missed") "No answer — call marked as missed" else "Could not connect. Check your network or TURN server.")
                        }
                    }
                    ringTimeout = timeout
                    mainHandler.postDelayed(timeout, if (state == "ringing") 40_000L else 25_000L)
                } else {
                    ringTimeout?.let { mainHandler.removeCallbacks(it) }; ringTimeout = null
                }
                if (state in listOf("ended", "rejected", "missed", "failed")) finishLocalCall()
            } }
            client.onError = { message -> runOnUiThread { showMessage(message); client.end("failed"); finishLocalCall() } }
            client.onCallCreated = { id -> repository.notifyCallServer(id) { error -> if (error != null) showMessage("Call started; push alert issue: $error") } }
        }
    }

    private fun startCall(friend: CallioFriend) {
        val user = currentUser ?: return
        if (!hasMicrophonePermission()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC); showMessage("Allow microphone, then tap Call again"); return }
        if (callState.isNotBlank()) { showMessage("You already have an active call"); return }
        callPerson = friend.displayName
        callMuted = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        ContextCompat.startForegroundService(this, Intent(this, CallioActiveCallService::class.java).setAction(CallioActiveCallService.ACTION_START))
        callClient?.startOutgoing(user.uid, user.displayName ?: "Callio friend", friend.uid, friend.displayName, friend.friendshipId)
            ?: showMessage("Calling engine is not ready")
    }

    private fun acceptIncomingCall() {
        val call = incomingCall ?: return
        if (!hasMicrophonePermission()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC); showMessage("Allow microphone, then tap Accept again"); return }
        stopRingtone()
        NotificationManagerCompat.from(this).cancel(call.id.hashCode())
        callPerson = call.callerName
        callMuted = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        ContextCompat.startForegroundService(this, Intent(this, CallioActiveCallService::class.java).setAction(CallioActiveCallService.ACTION_START))
        callClient?.answerIncoming(call.id, call.callerId, call.callerName, call.offerType, call.offerSdp)
            ?: showMessage("Calling engine is not ready")
        incomingCall = null
    }

    private fun rejectIncomingCall() {
        val call = incomingCall ?: return
        repository.callDocument(call.id).update(mapOf("status" to "rejected", "endedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
        incomingCall = null
        NotificationManagerCompat.from(this).cancel(call.id.hashCode())
        stopRingtone()
    }

    private fun endCall() { callClient?.end("ended"); finishLocalCall() }

    private fun finishLocalCall() {
        ringTimeout?.let { mainHandler.removeCallbacks(it) }; ringTimeout = null
        stopRingtone()
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        stopService(Intent(this, CallioActiveCallService::class.java).setAction(CallioActiveCallService.ACTION_STOP))
        callState = ""
        callMuted = false
        callSpeaker = false
        callPerson = ""
        incomingCall = null
    }

    private fun toggleMute() { callMuted = !callMuted; callClient?.setMuted(callMuted) }
    private fun toggleSpeaker() { callSpeaker = !callSpeaker; audioManager.isSpeakerphoneOn = callSpeaker }

    private fun startRingtone() {
        if (ringtone?.isPlaying == true) return
        try {
            val uri = Uri.parse("android.resource://$packageName/${R.raw.callio_ringtone}")
            ringtone = RingtoneManager.getRingtone(this, uri).also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isLooping = true
                it.play()
            }
        } catch (_: Exception) { }
    }
    private fun stopRingtone() { try { ringtone?.stop() } catch (_: Exception) { }; ringtone = null }

    private fun handleCallIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_INCOMING) return
        val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        pendingIncomingId = id
        if (currentUser != null) loadIncomingFromNotification(id)
    }

    private fun loadIncomingFromNotification(id: String) {
        repository.fetchIncomingCall(id) { call -> runOnUiThread {
            pendingIncomingId = null
            if (call != null) { incomingCall = call; startRingtone() }
            else showMessage("This call is no longer available")
        } }
    }

    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }
    private fun showMessage(message: String?) { if (!message.isNullOrBlank()) runOnUiThread { toastMessage = message } }

    override fun onDestroy() {
        authListener?.let { com.google.firebase.auth.FirebaseAuth.getInstance().removeAuthStateListener(it) }
        friendsListener?.remove(); requestsListener?.remove(); incomingListener?.remove()
        stopRingtone(); if (callState.isBlank()) callClient?.close()
        if (callState.isBlank()) audioManager.mode = AudioManager.MODE_NORMAL
        updateManager.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN_INCOMING = "com.callio.app.OPEN_INCOMING_CALL"
        const val EXTRA_CALL_ID = "callio_call_id"
        const val EXTRA_CALLER_NAME = "callio_caller_name"
        private const val REQUEST_MIC = 41
        private const val REQUEST_NOTIFICATIONS = 42
    }
}
