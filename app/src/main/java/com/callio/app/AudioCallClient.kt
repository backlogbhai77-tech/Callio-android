package com.callio.app.calling

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.callio.app.BuildConfig
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.MediaStream
import java.util.concurrent.atomic.AtomicBoolean

/** Audio-only WebRTC signalling uses the same Firestore call document/subcollections as Callio PWA. */
class AudioCallClient(context: Context) {
    private val appContext = context.applicationContext
    private val db = FirebaseFirestore.getInstance()
    private var factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var callId: String? = null
    private var role: String = ""
    private var callListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var candidateListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var muted = false
    private var connectedAtMillis: Long? = null
    private val closed = AtomicBoolean(false)
    private val pendingCandidates = mutableListOf<IceCandidate>()
    var onState: ((String) -> Unit)? = null
    var onCallCreated: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        synchronized(WebRtcBootstrap) {
            if (!WebRtcBootstrap.initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
                )
                WebRtcBootstrap.initialized = true
            }
        }
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private fun createPeer(targetCollection: String): PeerConnection {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        if (BuildConfig.CALLIO_TURN_URL.isNotBlank()) {
            servers.add(PeerConnection.IceServer.builder(BuildConfig.CALLIO_TURN_URL)
                .setUsername(BuildConfig.CALLIO_TURN_USERNAME)
                .setPassword(BuildConfig.CALLIO_TURN_CREDENTIAL)
                .createIceServer())
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val constraints = MediaConstraints()
        val created = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        if (connectedAtMillis == null) connectedAtMillis = System.currentTimeMillis()
                        onState?.invoke("connected")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> onState?.invoke("reconnecting")
                    PeerConnection.IceConnectionState.FAILED -> onError?.invoke("Connection failed. Check your internet or TURN server.")
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                val id = callId
                if (id == null) pendingCandidates.add(candidate) else writeCandidate(id, targetCollection, candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) = Unit
            override fun onTrack(transceiver: RtpTransceiver) = Unit
        }) ?: throw IllegalStateException("Could not create audio connection")
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = factory.createAudioSource(audioConstraints)
        audioSource = source
        val track = factory.createAudioTrack("callio_audio_track", source).apply { setEnabled(true) }
        audioTrack = track
        created.addTrack(track, listOf("callio_stream"))
        peer = created
        closed.set(false)
        return created
    }

    fun startOutgoing(callerUid: String, callerName: String, calleeUid: String, calleeName: String, friendshipId: String) {
        role = "caller"
        onState?.invoke("preparing")
        val connection = createPeer("callerCandidates")
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val data = hashMapOf<String, Any>(
                            "callerId" to callerUid,
                            "callerName" to callerName,
                            "calleeId" to calleeUid,
                            "calleeName" to calleeName,
                            "friendshipId" to friendshipId,
                            "status" to "ringing",
                            "offer" to mapOf("type" to description.type.canonicalForm(), "sdp" to description.description),
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                        db.collection("calls").add(data).addOnSuccessListener { ref ->
                            callId = ref.id
                            onCallCreated?.invoke(ref.id)
                            flushCandidates(ref.id, "callerCandidates")
                            listenForAnswer(ref.id, connection)
                            onState?.invoke("ringing")
                        }.addOnFailureListener { onError?.invoke(it.localizedMessage ?: "Could not create call") }
                    }
                    override fun onSetFailure(error: String) { onError?.invoke(error) }
                    override fun onCreateSuccess(description: SessionDescription) = Unit
                    override fun onCreateFailure(error: String) = Unit
                }, description)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { onError?.invoke(error) }
            override fun onSetFailure(error: String) = Unit
        }, MediaConstraints())
    }

    private fun listenForAnswer(id: String, connection: PeerConnection) {
        callListener = db.collection("calls").document(id).addSnapshotListener { snapshot, error ->
            if (error != null) { onError?.invoke(error.localizedMessage ?: "Call listener failed"); return@addSnapshotListener }
            val data = snapshot?.data ?: return@addSnapshotListener
            val status = data["status"] as? String ?: ""
            if (status in listOf("rejected", "ended", "missed", "failed")) { onState?.invoke(status); close(); return@addSnapshotListener }
            val answer = data["answer"] as? Map<*, *> ?: return@addSnapshotListener
            if (connection.remoteDescription == null) {
                val type = answer["type"] as? String ?: "answer"
                val sdp = answer["sdp"] as? String ?: return@addSnapshotListener
                connection.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() { listenCandidates(id, "calleeCandidates", connection); if (connectedAtMillis == null) connectedAtMillis = System.currentTimeMillis(); onState?.invoke("connected") }
                    override fun onSetFailure(error: String) { onError?.invoke(error) }
                    override fun onCreateSuccess(description: SessionDescription) = Unit
                    override fun onCreateFailure(error: String) = Unit
                }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
            }
        }
    }

    fun answerIncoming(callId: String, callerUid: String, callerName: String, offerType: String, offerSdp: String) {
        this.callId = callId
        role = "callee"
        onState?.invoke("connecting")
        val connection = createPeer("calleeCandidates")
        val offer = SessionDescription(SessionDescription.Type.fromCanonicalForm(offerType), offerSdp)
        connection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                listenCandidates(callId, "callerCandidates", connection)
                connection.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription) {
                        connection.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                db.collection("calls").document(callId).update(
                                    mapOf(
                                        "answer" to mapOf("type" to description.type.canonicalForm(), "sdp" to description.description),
                                        "status" to "connected",
                                        "acceptedAt" to FieldValue.serverTimestamp()
                                    )
                                ).addOnSuccessListener { flushCandidates(callId, "calleeCandidates"); listenForEnd(callId); onState?.invoke("connecting") }
                                    .addOnFailureListener { onError?.invoke(it.localizedMessage ?: "Could not answer") }
                            }
                            override fun onSetFailure(error: String) { onError?.invoke(error) }
                            override fun onCreateSuccess(description: SessionDescription) = Unit
                            override fun onCreateFailure(error: String) = Unit
                        }, description)
                    }
                    override fun onSetSuccess() = Unit
                    override fun onCreateFailure(error: String) { onError?.invoke(error) }
                    override fun onSetFailure(error: String) = Unit
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String) { onError?.invoke(error) }
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
        }, offer)
    }

    private fun listenForEnd(id: String) {
        callListener?.remove()
        callListener = db.collection("calls").document(id).addSnapshotListener { snap, _ ->
            val status = snap?.getString("status") ?: return@addSnapshotListener
            if (status in listOf("ended", "missed", "rejected", "failed")) { onState?.invoke(status); close() }
        }
    }

    private fun listenCandidates(id: String, collection: String, connection: PeerConnection) {
        candidateListener?.remove()
        candidateListener = db.collection("calls").document(id).collection(collection).addSnapshotListener { snap, _ ->
            snap?.documentChanges?.forEach { change ->
                if (change.type.name != "ADDED") return@forEach
                val data = change.document.data
                val candidate = data["candidate"] as? String ?: return@forEach
                val mid = data["sdpMid"] as? String
                val line = (data["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                connection.addIceCandidate(IceCandidate(mid, line, candidate))
            }
        }
    }

    private fun writeCandidate(id: String, collection: String, candidate: IceCandidate) {
        db.collection("calls").document(id).collection(collection).add(mapOf(
            "candidate" to candidate.sdp,
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex
        ))
    }

    private fun flushCandidates(id: String, collection: String) {
        val copy = pendingCandidates.toList(); pendingCandidates.clear()
        copy.forEach { writeCandidate(id, collection, it) }
    }

    fun setMuted(value: Boolean) { muted = value; audioTrack?.setEnabled(!value) }

    fun end(status: String = "ended") {
        val id = callId
        if (id != null) {
            val duration = connectedAtMillis?.let { ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L) } ?: 0L
            db.collection("calls").document(id).update(mapOf(
                "status" to status,
                "endedAt" to FieldValue.serverTimestamp(),
                "durationSeconds" to duration
            ))
        }
        close()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        callListener?.remove(); callListener = null
        candidateListener?.remove(); candidateListener = null
        peer?.close(); peer?.dispose(); peer = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        factory.dispose()
        callId = null
        connectedAtMillis = null
        pendingCandidates.clear()
    }

    private object WebRtcBootstrap { @Volatile var initialized: Boolean = false }
}
