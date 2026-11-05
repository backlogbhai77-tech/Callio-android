package com.callio.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.callio.app.calling.AudioCallClient
import com.callio.app.calling.IceServerProvider
import com.callio.app.data.CallHistoryItem
import com.callio.app.data.CallioFriend
import com.callio.app.data.CallioRepository
import com.callio.app.data.FriendRequestItem
import com.callio.app.data.IncomingCall
import com.callio.app.service.CallioActiveCallService
import com.callio.app.ui.CallioApp
import com.callio.app.ui.UpdateUiActions
import com.callio.app.update.UpdateInstallReceiver
import com.callio.app.update.UpdateManager
import com.callio.app.update.UpdateUiState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.ListenerRegistration

class MainActivity : ComponentActivity() {
    private val repository by lazy { CallioRepository() }
    private val updater by lazy { UpdateManager(this) }
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var friendsListener: ListenerRegistration? = null
    private var requestsListener: ListenerRegistration? = null
    private var incomingListener: ListenerRegistration? = null
    private var historyListener: ListenerRegistration? = null
    private var callClient: AudioCallClient? = null
    private var ringtone: Ringtone? = null
    private var activeUid: String? = null
    private var pendingIncomingId: String? = null
    private var pendingAutoAcceptCallId: String? = null
    private var lastIncomingNotificationId: String? = null
    private val dismissedCallIds = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ringTimeout: Runnable? = null
    private var incomingTimeout: Runnable? = null
    private var callSession = 0
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var currentUser by mutableStateOf<FirebaseUser?>(null)
    private var friends by mutableStateOf<List<CallioFriend>>(emptyList())
    private var requests by mutableStateOf<List<FriendRequestItem>>(emptyList())
    private var history by mutableStateOf<List<CallHistoryItem>>(emptyList())
    private var incomingCall by mutableStateOf<IncomingCall?>(null)
    private var callState by mutableStateOf("")
    private var callPerson by mutableStateOf("")
    private var callMuted by mutableStateOf(false)
    private var callSpeaker by mutableStateOf(false)
    private var callConnectedAt by mutableLongStateOf(0L)
    private var toastMessage by mutableStateOf("")
    private var profileName by mutableStateOf("")
    private var profileUsername by mutableStateOf("")
    private var updateState by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()

        updater.onState = { state -> updateState = state }
        UpdateInstallReceiver.listener = { status, message -> runOnUiThread { updater.onInstallResult(status, message) } }

        // FirebaseAuth already knows the persisted user: use it for the very first frame so a signed-in
        // user never sees the login screen flash on launch.
        authListener = repository.listenAuth { user -> runOnUiThread { onUserChanged(user) } }
        currentUser = repository.currentUser()
        currentUser?.let { onUserChanged(it) }
        handleCallIntent(intent)

        setContent {
            CallioApp(
                user = currentUser,
                savedDisplayName = profileName,
                savedUsername = profileUsername,
                friends = friends,
                requests = requests,
                history = history,
                incomingCall = incomingCall,
                callState = callState,
                callPerson = callPerson,
                callMuted = callMuted,
                callSpeaker = callSpeaker,
                callConnectedAt = callConnectedAt,
                toastMessage = toastMessage,
                versionName = updater.currentVersionName,
                updateState = updateState,
                updateActions = UpdateUiActions(
                    onCheck = { updater.check(manual = true) { callState.isBlank() && incomingCall == null } },
                    onUpdateNow = { (updateState as? UpdateUiState.Available)?.let { updater.downloadAndInstall(it.info) } },
                    onDismiss = { updater.dismiss() },
                    onOpenInstallSettings = {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                ),
                onSignIn = { email, password -> repository.signIn(email, password) { showMessage(it ?: "Welcome back") } },
                onSignUp = { name, email, password -> repository.signUp(name, email, password) { showMessage(it ?: "Account created") } },
                onSignOut = { callClient?.end("ended"); finishLocalCall(); repository.signOut() },
                onCall = { friend -> startCall(friend) },
                onCallBack = { item ->
                    val friend = friends.firstOrNull { it.uid == item.otherUid }
                    if (friend != null) startCall(friend) else showMessage("Add ${item.personName} as a friend to call back")
                },
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
                onClearMessage = { toastMessage = "" }
            )
        }
        // Load the heavy WebRTC native library off the main thread, after the first frame.
        Thread { try { AudioCallClient.prewarm(applicationContext) } catch (_: Throwable) { } }.start()
    }

    override fun onResume() {
        super.onResume()
        updater.resumePendingIfAllowed()
        updater.check(manual = false) { callState.isBlank() && incomingCall == null }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent)
    }

    private fun onUserChanged(user: FirebaseUser?) {
        if (user?.uid == activeUid && (user == null || callClient != null)) { currentUser = user; return }
        activeUid = user?.uid
        currentUser = user
        friendsListener?.remove(); requestsListener?.remove(); incomingListener?.remove(); historyListener?.remove()
        callClient?.close(); callClient = null
        incomingCall = null; friends = emptyList(); requests = emptyList(); history = emptyList(); callState = ""
        if (user == null) return
        repository.registerPushToken()
        repository.cleanupStaleCalls(user.uid)
        IceServerProvider.prefetch()
        pendingIncomingId?.let { loadIncomingFromNotification(it) }
        friendsListener = repository.listenFriends(user.uid, { rows -> runOnUiThread { friends = rows } }, { showMessage(it) })
        requestsListener = repository.listenIncomingRequests(user.uid) { rows -> runOnUiThread { requests = rows } }
        historyListener = repository.listenCallHistory(user.uid) { rows -> runOnUiThread { history = rows } }
        incomingListener = repository.listenIncomingCalls(user.uid) { call -> runOnUiThread { handleIncoming(call) } }
        repository.loadProfile(user.uid) { name, username -> runOnUiThread { profileName = name; profileUsername = username } }
        profileName = user.displayName ?: ""
        callClient = AudioCallClient(this).also { client ->
            client.onState = { state -> runOnUiThread { onCallState(state) } }
            client.onError = { message -> runOnUiThread { showMessage(message); client.end("failed"); finishLocalCall() } }
            client.onCallCreated = { id -> repository.notifyCallServer(id) { error -> if (error != null) showMessage("Call started; push alert issue: $error") } }
        }
        maybeAutoAcceptIncomingCall()
    }

    private fun onCallState(state: String) {
        callState = state
        if (state == "connected" && callConnectedAt == 0L) callConnectedAt = System.currentTimeMillis()
        ringTimeout?.let { mainHandler.removeCallbacks(it) }; ringTimeout = null
        if (state in listOf("preparing", "ringing", "connecting", "reconnecting")) {
            val timeout = Runnable {
                if (callState == state) {
                    val status = if (state == "ringing") "missed" else "failed"
                    callClient?.end(status); finishLocalCall()
                    showMessage(if (status == "missed") "No answer — call marked as missed" else "Could not connect. Check your network or TURN server.")
                }
            }
            ringTimeout = timeout
            mainHandler.postDelayed(timeout, if (state == "ringing") 40_000L else 25_000L)
        }
        if (state in listOf("ended", "rejected", "missed", "failed")) {
            if (state == "rejected") showMessage("Call declined")
            finishLocalCall()
        }
    }

    private fun handleIncoming(raw: IncomingCall?) {
        val call = raw?.takeIf { it.id !in dismissedCallIds }
        incomingCall = call
        if (call != null) {
            if (lastIncomingNotificationId != call.id) scheduleIncomingTimeout(call.id)
            lastIncomingNotificationId = call.id
            if (callState.isBlank()) startRingtone()
        } else {
            lastIncomingNotificationId?.let { NotificationManagerCompat.from(this).cancel(it.hashCode()) }
            lastIncomingNotificationId = null
            incomingTimeout?.let { mainHandler.removeCallbacks(it) }; incomingTimeout = null
            stopRingtone()
        }
    }

    /** A call nobody answers must stop ringing on its own. */
    private fun scheduleIncomingTimeout(id: String) {
        incomingTimeout?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (incomingCall?.id == id && callState.isBlank()) {
                dismissedCallIds.add(id)
                incomingCall = null
                stopRingtone()
                NotificationManagerCompat.from(this).cancel(id.hashCode())
            }
        }
        incomingTimeout = r
        mainHandler.postDelayed(r, 45_000L)
    }

    private fun beginCallAudio() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener { _ -> }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun endCallAudio() {
        focusRequest?.let { audioManager.abandonAudioFocus(it) }
        focusRequest = null
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
    }

    private fun startCall(friend: CallioFriend) {
        val user = currentUser ?: return
        if (!hasMicrophonePermission()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC); showMessage("Allow microphone, then tap Call again"); return }
        if (callState.isNotBlank()) { showMessage("You already have an active call"); return }
        callPerson = friend.displayName
        callMuted = false
        callConnectedAt = 0L
        callState = "preparing"
        onCallState("preparing")
        val session = ++callSession
        beginCallAudio()
        ContextCompat.startForegroundService(this, Intent(this, CallioActiveCallService::class.java).setAction(CallioActiveCallService.ACTION_START))
        IceServerProvider.load { servers ->
            if (session != callSession || callState.isBlank()) return@load // cancelled while preparing
            val client = callClient
            if (client == null) { showMessage("Calling engine is not ready"); finishLocalCall(); return@load }
            client.iceServers = servers
            client.startOutgoing(user.uid, user.displayName ?: "Callio friend", friend.uid, friend.displayName, friend.friendshipId)
        }
    }

    private fun acceptIncomingCall() {
        val call = incomingCall ?: return
        if (!hasMicrophonePermission()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC); showMessage("Allow microphone, then tap Accept again"); return }
        stopRingtone()
        incomingTimeout?.let { mainHandler.removeCallbacks(it) }; incomingTimeout = null
        NotificationManagerCompat.from(this).cancel(call.id.hashCode())
        callPerson = call.callerName
        callMuted = false
        callConnectedAt = 0L
        callState = "connecting"
        onCallState("connecting")
        incomingCall = null
        val session = ++callSession
        beginCallAudio()
        ContextCompat.startForegroundService(this, Intent(this, CallioActiveCallService::class.java).setAction(CallioActiveCallService.ACTION_START))
        IceServerProvider.load { servers ->
            if (session != callSession || callState.isBlank()) return@load
            val client = callClient
            if (client == null) { showMessage("Calling engine is not ready"); finishLocalCall(); return@load }
            client.iceServers = servers
            client.answerIncoming(call.id, call.callerId, call.callerName, call.offerType, call.offerSdp)
        }
    }

    private fun rejectIncomingCall() {
        val call = incomingCall ?: return
        dismissedCallIds.add(call.id)
        repository.callDocument(call.id).update(mapOf("status" to "rejected", "endedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
        incomingCall = null
        NotificationManagerCompat.from(this).cancel(call.id.hashCode())
        stopRingtone()
    }

    private fun endCall() { callClient?.end("ended"); finishLocalCall() }

    private fun finishLocalCall() {
        callSession++
        ringTimeout?.let { mainHandler.removeCallbacks(it) }; ringTimeout = null
        stopRingtone()
        callClient?.close()
        endCallAudio()
        stopService(Intent(this, CallioActiveCallService::class.java).setAction(CallioActiveCallService.ACTION_STOP))
        callState = ""
        callMuted = false
        callSpeaker = false
        callPerson = ""
        callConnectedAt = 0L
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
        val action = intent?.action
        if (action != ACTION_OPEN_INCOMING && action != ACTION_ACCEPT_INCOMING) return
        val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        pendingIncomingId = id
        pendingAutoAcceptCallId = if (action == ACTION_ACCEPT_INCOMING) id else null
        if (currentUser != null) loadIncomingFromNotification(id)
    }

    private fun loadIncomingFromNotification(id: String) {
        repository.fetchIncomingCall(id) { call -> runOnUiThread {
            pendingIncomingId = null
            if (call != null && call.id !in dismissedCallIds) {
                incomingCall = call
                lastIncomingNotificationId = call.id
                scheduleIncomingTimeout(call.id)
                if (pendingAutoAcceptCallId == id) maybeAutoAcceptIncomingCall() else startRingtone()
            } else {
                pendingAutoAcceptCallId = null
                showMessage("This call is no longer available")
            }
        } }
    }

    private fun maybeAutoAcceptIncomingCall() {
        val expectedId = pendingAutoAcceptCallId ?: return
        val call = incomingCall ?: return
        if (call.id != expectedId || callClient == null) return
        pendingAutoAcceptCallId = null
        mainHandler.post { acceptIncomingCall() }
    }

    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }
    private fun showMessage(message: String?) { if (!message.isNullOrBlank()) runOnUiThread { toastMessage = message } }

    override fun onDestroy() {
        authListener?.let { FirebaseAuth.getInstance().removeAuthStateListener(it) }
        friendsListener?.remove(); requestsListener?.remove(); incomingListener?.remove(); historyListener?.remove()
        mainHandler.removeCallbacksAndMessages(null)
        if (isFinishing) {
            // Task swiped away: never leave a zombie call / foreground service behind.
            if (callState.isNotBlank()) { callClient?.end("ended"); finishLocalCall() }
            UpdateInstallReceiver.listener = null
        }
        stopRingtone()
        if (callState.isBlank()) { callClient?.close(); endCallAudio() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN_INCOMING = "com.callio.app.OPEN_INCOMING_CALL"
        const val ACTION_ACCEPT_INCOMING = "com.callio.app.ACCEPT_INCOMING_CALL"
        const val EXTRA_CALL_ID = "callio_call_id"
        const val EXTRA_CALLER_NAME = "callio_caller_name"
        private const val REQUEST_MIC = 41
        private const val REQUEST_NOTIFICATIONS = 42
    }
}
