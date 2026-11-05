package com.callio.app.calling

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audio-only WebRTC. Signalling uses the same Firestore call document/subcollections as the Callio PWA.
 * One instance can place/answer many calls: the native factory/peer are created per call and fully
 * released in [close].
 */
class AudioCallClient(context: Context) {
    private val appContext = context.applicationContext
    private val db = FirebaseFirestore.getInstance()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var callId: String? = null
    private var callListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var candidateListener: com.google.firebase.firestore.ListenerRegistration? = null
    @Volatile private var connectedAtMillis: Long? = null
    /** true while no native resources are alive */
    private val closed = AtomicBoolean(true)
    private val pendingCandidates = mutableListOf<IceCandidate>()

    var iceServers: List<PeerConnection.IceServer> = emptyList()
    var onState: ((String) -> Unit)? = null
    var onCallCreated: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private fun createPeer(targetCollection: String): PeerConnection {
        prewarm(appContext)
        val f = PeerConnectionFactory.builder().createPeerConnectionFactory()
        factory = f
        val servers = iceServers.ifEmpty { IceServerProvider.fallback() }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val created = f.createPeerConnection(config, object : PeerConnection.Observer {
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
                synchronized(pendingCandidates) {
                    val id = callId
                    if (id == null) pendingCandidates.add(candidate) else writeCandidate(id, targetCollection, candidate)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) = Unit
            override fun onTrack(transceiver: RtpTransceiver) = Unit
        }) ?: run { f.dispose(); factory = null; throw IllegalStateException("Could not create audio connection") }

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = f.createAudioSource(audioConstraints)
        audioSource = source
        val track = f.createAudioTrack("callio_audio_track", source).apply { setEnabled(true) }
        audioTrack = track
        created.addTrack(track, listOf("callio_stream"))
        peer = created
        connectedAtMillis = null
        closed.set(false)
        return created
    }

    fun startOutgoing(callerUid: String, callerName: String, calleeUid: String, calleeName: String, friendshipId: String) {
        onState?.invoke("preparing")
        val connection = try { createPeer("callerCandidates") } catch (e: Exception) {
            onError?.invoke(e.localizedMessage ?: "Could not start call"); return
        }
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
                            if (closed.get()) { // user cancelled while the call document was being created
                                db.collection("calls").document(ref.id).update(mapOf("status" to "ended", "endedAt" to FieldValue.serverTimestamp(), "durationSeconds" to 0L))
                                return@addOnSuccessListener
                            }
                            synchronized(pendingCandidates) {
                                callId = ref.id
                                val copy = pendingCandidates.toList(); pendingCandidates.clear()
                                copy.forEach { writeCandidate(ref.id, "callerCandidates", it) }
                            }
                            onCallCreated?.invoke(ref.id)
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
            if (status in TERMINAL) { onState?.invoke(status); close(); return@addSnapshotListener }
            val answer = data["answer"] as? Map<*, *> ?: return@addSnapshotListener
            if (peer === connection && connection.remoteDescription == null) {
                val type = answer["type"] as? String ?: "answer"
                val sdp = answer["sdp"] as? String ?: return@addSnapshotListener
                connection.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        listenCandidates(id, "calleeCandidates", connection)
                        onState?.invoke("connecting") // becomes "connected" once ICE really connects
                    }
                    override fun onSetFailure(error: String) { onError?.invoke(error) }
                    override fun onCreateSuccess(description: SessionDescription) = Unit
                    override fun onCreateFailure(error: String) = Unit
                }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
            }
        }
    }

    fun answerIncoming(callId: String, callerUid: String, callerName: String, offerType: String, offerSdp: String) {
        this.callId = callId
        onState?.invoke("connecting")
        val connection = try { createPeer("calleeCandidates") } catch (e: Exception) {
            onError?.invoke(e.localizedMessage ?: "Could not answer call"); return
        }
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
                                ).addOnSuccessListener {
                                    synchronized(pendingCandidates) {
                                        val copy = pendingCandidates.toList(); pendingCandidates.clear()
                                        copy.forEach { writeCandidate(callId, "calleeCandidates", it) }
                                    }
                                    listenForEnd(callId)
                                }.addOnFailureListener { onError?.invoke(it.localizedMessage ?: "Could not answer") }
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
            if (status in TERMINAL) { onState?.invoke(status); close() }
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
                if (peer === connection) connection.addIceCandidate(IceCandidate(mid, line, candidate))
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

    fun setMuted(value: Boolean) { audioTrack?.setEnabled(!value) }

    fun end(status: String = "ended") {
        val id = callId
        if (id != null && !closed.get()) {
            val duration = connectedAtMillis?.let { ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L) } ?: 0L
            db.collection("calls").document(id).update(mapOf(
                "status" to status,
                "endedAt" to FieldValue.serverTimestamp(),
                "durationSeconds" to duration
            ))
        }
        close()
    }

    /** Releases every native resource. Safe to call repeatedly; the client can be reused for the next call. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        callListener?.remove(); callListener = null
        candidateListener?.remove(); candidateListener = null
        try { peer?.close(); peer?.dispose() } catch (_: Exception) { }
        peer = null
        try { audioTrack?.dispose() } catch (_: Exception) { }
        audioTrack = null
        try { audioSource?.dispose() } catch (_: Exception) { }
        audioSource = null
        try { factory?.dispose() } catch (_: Exception) { }
        factory = null
        callId = null
        connectedAtMillis = null
        synchronized(pendingCandidates) { pendingCandidates.clear() }
    }

    companion object {
        private val TERMINAL = listOf("rejected", "ended", "missed", "failed")
        private val initLock = Any()
        @Volatile private var initialized = false

        /** Loads the WebRTC native library. Call from a background thread at app start to keep the UI smooth. */
        fun prewarm(context: Context) {
            if (initialized) return
            synchronized(initLock) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
                )
                initialized = true
            }
        }
    }
}
