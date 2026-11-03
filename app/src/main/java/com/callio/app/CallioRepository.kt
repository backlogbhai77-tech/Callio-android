package com.callio.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging

class CallioRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    fun currentUser(): FirebaseUser? = auth.currentUser
    fun listenAuth(callback: (FirebaseUser?) -> Unit): FirebaseAuth.AuthStateListener {
        val listener = FirebaseAuth.AuthStateListener { callback(it.currentUser) }
        auth.addAuthStateListener(listener)
        return listener
    }

    fun signIn(email: String, password: String, done: (String?) -> Unit) {
        auth.signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(it.localizedMessage ?: "Sign in failed") }
    }

    fun signUp(name: String, email: String, password: String, done: (String?) -> Unit) {
        auth.createUserWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { result ->
                val user = result.user ?: run { done("Account could not be created"); return@addOnSuccessListener }
                val profile = hashMapOf(
                    "uid" to user.uid,
                    "displayName" to name.trim().ifBlank { "New friend" },
                    "username" to "",
                    "photoURL" to "",
                    "createdAt" to FieldValue.serverTimestamp()
                )
                db.collection("users").document(user.uid).set(profile, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener { done(null) }
                    .addOnFailureListener { done(it.localizedMessage ?: "Profile save failed") }
            }
            .addOnFailureListener { done(it.localizedMessage ?: "Sign up failed") }
    }

    fun signOut() {
        val user = auth.currentUser ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            db.collection("users").document(user.uid).collection("devices").document(token).delete()
                .addOnCompleteListener { auth.signOut() }
        }.addOnFailureListener { auth.signOut() }
    }

    fun loadProfile(uid: String, done: (String, String) -> Unit) {
        db.collection("users").document(uid).get().addOnSuccessListener { profile ->
            done(profile.getString("displayName") ?: auth.currentUser?.displayName.orEmpty(), profile.getString("username") ?: "")
        }.addOnFailureListener { done(auth.currentUser?.displayName.orEmpty(), "") }
    }

    fun registerPushToken() {
        val user = auth.currentUser ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> savePushToken(user.uid, token) }
    }

    fun savePushToken(uid: String, token: String) {
        db.collection("users").document(uid).collection("devices").document(token).set(mapOf(
            "token" to token, "platform" to "android",
            "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()
        ), com.google.firebase.firestore.SetOptions.merge())
    }

    fun notifyCallServer(callId: String, done: (String?) -> Unit) {
        val user = auth.currentUser ?: run { done("Sign in again to place a call"); return }
        user.getIdToken(false).addOnSuccessListener { tokenResult ->
            Thread {
                try {
                    val url = java.net.URL(com.callio.app.BuildConfig.CALLIO_SERVER_URL.trimEnd('/') + "/api/calls/notify")
                    val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"; connectTimeout = 10000; readTimeout = 10000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", "Bearer ${tokenResult.token}")
                    }
                    connection.outputStream.use { it.write("{\"callId\":\"$callId\"}".toByteArray()) }
                    val code = connection.responseCode
                    connection.disconnect()
                    done(if (code in 200..299) null else "Push server returned HTTP $code")
                } catch (e: Exception) { done(e.localizedMessage ?: "Push request failed") }
            }.start()
        }.addOnFailureListener { done(it.localizedMessage ?: "Could not authenticate push request") }
    }

    /**
     * Listen to both friendship documents and accepted friend-request documents.
     * Older Callio web builds may have one representation but not the other, so
     * merge both sources instead of silently showing an empty friends list.
     */
    fun listenFriends(uid: String, callback: (List<CallioFriend>) -> Unit, error: (String) -> Unit): ListenerRegistration {
        val lock = Any()
        val friendshipRows = linkedMapOf<String, String>()
        val acceptedRows = linkedMapOf<String, String>()
        var friendshipSnapshotReady = false
        var outgoingSnapshotReady = false
        var incomingSnapshotReady = false

        fun publish() {
            val merged = synchronized(lock) {
                (acceptedRows + friendshipRows).toMap()
            }
            if (!friendshipSnapshotReady && !outgoingSnapshotReady && !incomingSnapshotReady) return
            if (merged.isEmpty()) {
                callback(emptyList())
                return
            }
            val result = java.util.concurrent.ConcurrentHashMap<String, CallioFriend>()
            val remaining = java.util.concurrent.atomic.AtomicInteger(merged.size)
            merged.forEach { (friendUid, friendshipId) ->
                db.collection("users").document(friendUid).get()
                    .addOnSuccessListener { profile ->
                        if (profile.exists()) {
                            result[friendUid] = CallioFriend(
                                uid = friendUid,
                                displayName = profile.getString("displayName")
                                    ?: profile.getString("name")
                                    ?: "Callio friend",
                                username = profile.getString("username") ?: "",
                                friendshipId = friendshipId
                            )
                        }
                    }
                    .addOnFailureListener { /* Keep other friends visible if one profile is private/unavailable. */ }
                    .addOnCompleteListener {
                        if (remaining.decrementAndGet() == 0) {
                            callback(result.values.sortedBy { it.displayName.lowercase() })
                        }
                    }
            }
        }

        val friendshipListener = db.collection("friendships")
            .whereArrayContains("memberUids", uid)
            .limit(100)
            .addSnapshotListener { snapshot, exception ->
                if (exception != null) error(exception.localizedMessage ?: "Friends unavailable")
                if (snapshot != null) {
                    synchronized(lock) {
                        friendshipRows.clear()
                        snapshot.documents.forEach { doc ->
                            val members = doc.get("memberUids") as? List<*> ?: emptyList<Any>()
                            val otherUid = members.filterIsInstance<String>().firstOrNull { it != uid }
                            if (!otherUid.isNullOrBlank()) friendshipRows[otherUid] = doc.id
                        }
                        friendshipSnapshotReady = true
                    }
                    publish()
                }
            }

        val outgoingListener = db.collection("friendRequests")
            .whereEqualTo("fromUid", uid)
            .limit(100)
            .addSnapshotListener { snapshot, exception ->
                if (exception != null) error(exception.localizedMessage ?: "Accepted friends unavailable")
                if (snapshot != null) {
                    synchronized(lock) {
                        acceptedRows.entries.removeAll { it.value.startsWith("out:") }
                        snapshot.documents.filter { it.getString("status") == "accepted" }.forEach { doc ->
                            val otherUid = doc.getString("toUid").orEmpty()
                            if (otherUid.isNotBlank()) acceptedRows[otherUid] = "out:${doc.id}"
                        }
                        outgoingSnapshotReady = true
                    }
                    publish()
                }
            }

        val incomingListener = db.collection("friendRequests")
            .whereEqualTo("toUid", uid)
            .limit(100)
            .addSnapshotListener { snapshot, exception ->
                if (exception != null) error(exception.localizedMessage ?: "Accepted friends unavailable")
                if (snapshot != null) {
                    synchronized(lock) {
                        acceptedRows.entries.removeAll { it.value.startsWith("in:") }
                        snapshot.documents.filter { it.getString("status") == "accepted" }.forEach { doc ->
                            val otherUid = doc.getString("fromUid").orEmpty()
                            if (otherUid.isNotBlank()) acceptedRows[otherUid] = "in:${doc.id}"
                        }
                        incomingSnapshotReady = true
                    }
                    publish()
                }
            }

        return object : ListenerRegistration {
            override fun remove() {
                friendshipListener.remove()
                outgoingListener.remove()
                incomingListener.remove()
            }
        }
    }

    fun searchUsername(username: String, done: (CallioFriend?, String?) -> Unit) {
        val clean = username.trim().removePrefix("@").lowercase()
        if (!clean.matches(Regex("[a-z0-9_]{3,20}"))) { done(null, "Username must be 3–20 letters, numbers or underscores"); return }
        db.collection("usernames").document(clean).get().addOnSuccessListener { snap ->
            if (!snap.exists()) { done(null, "No Callio user found with that username"); return@addOnSuccessListener }
            val uid = snap.getString("uid") ?: ""
            val me = auth.currentUser?.uid
            if (uid.isBlank() || uid == me) { done(null, "You cannot add yourself"); return@addOnSuccessListener }
            db.collection("users").document(uid).get().addOnSuccessListener { profile ->
                if (!profile.exists()) done(null, "User profile not found")
                else done(CallioFriend(uid, profile.getString("displayName") ?: "Callio member", clean, ""), null)
            }.addOnFailureListener { done(null, it.localizedMessage) }
        }.addOnFailureListener { done(null, it.localizedMessage) }
    }

    fun sendFriendRequest(friendUid: String, done: (String?) -> Unit) {
        val me = auth.currentUser?.uid ?: run { done("Please sign in again"); return }
        val outgoing = db.collection("friendRequests").document("${me}_${friendUid}")
        val incoming = db.collection("friendRequests").document("${friendUid}_${me}")
        db.runTransaction { transaction ->
            val sent = transaction.get(outgoing)
            val received = transaction.get(incoming)
            if (sent.getString("status") == "pending") throw IllegalStateException("Friend request already sent")
            if (sent.getString("status") == "accepted" || received.getString("status") == "accepted") throw IllegalStateException("You are already friends")
            if (received.getString("status") == "pending") throw IllegalStateException("This person has already sent you a request; accept it below")
            transaction.set(outgoing, mapOf(
                "fromUid" to me, "toUid" to friendUid, "status" to "pending", "createdAt" to FieldValue.serverTimestamp()
            ))
        }.addOnSuccessListener { done(null) }.addOnFailureListener { done(it.localizedMessage ?: "Could not send friend request") }
    }

    fun listenIncomingRequests(uid: String, callback: (List<Pair<String, String>>) -> Unit): ListenerRegistration {
        return db.collection("friendRequests").whereEqualTo("toUid", uid).limit(30)
            .addSnapshotListener { snapshot, _ ->
                val rows = snapshot?.documents?.filter { it.getString("status") == "pending" }?.map { it.id to (it.getString("fromUid") ?: "") } ?: emptyList()
                callback(rows)
            }
    }

    fun acceptFriendRequest(requestId: String, done: (String?) -> Unit) {
        val ref = db.collection("friendRequests").document(requestId)
        db.runTransaction { transaction ->
            val request = transaction.get(ref)
            val from = request.getString("fromUid") ?: throw IllegalStateException("Invalid request")
            val to = request.getString("toUid") ?: throw IllegalStateException("Invalid request")
            transaction.update(ref, mapOf("status" to "accepted", "respondedAt" to FieldValue.serverTimestamp()))
            transaction.set(db.collection("friendships").document(requestId), mapOf(
                "requestId" to requestId, "memberUids" to listOf(from, to), "createdAt" to FieldValue.serverTimestamp()
            ))
        }.addOnSuccessListener { done(null) }.addOnFailureListener { done(it.localizedMessage) }
    }

    fun listenIncomingCalls(uid: String, callback: (IncomingCall?) -> Unit): ListenerRegistration {
        return db.collection("calls").whereEqualTo("calleeId", uid).limit(50)
            .addSnapshotListener { snapshot, _ ->
                val doc = snapshot?.documents?.firstOrNull { it.getString("status") == "ringing" }
                val offer = doc?.get("offer") as? Map<*, *>
                callback(if (doc == null || offer == null) null else IncomingCall(
                    id = doc.id,
                    callerId = doc.getString("callerId") ?: "",
                    callerName = doc.getString("callerName") ?: "Callio friend",
                    offerType = offer["type"] as? String ?: "offer",
                    offerSdp = offer["sdp"] as? String ?: ""
                ))
            }
    }

    fun fetchIncomingCall(callId: String, done: (IncomingCall?) -> Unit) {
        db.collection("calls").document(callId).get().addOnSuccessListener { doc ->
            val offer = doc.get("offer") as? Map<*, *>
            done(if (!doc.exists() || offer == null || doc.getString("status") != "ringing") null else IncomingCall(
                id = doc.id, callerId = doc.getString("callerId") ?: "",
                callerName = doc.getString("callerName") ?: "Callio friend",
                offerType = offer["type"] as? String ?: "offer", offerSdp = offer["sdp"] as? String ?: ""
            ))
        }.addOnFailureListener { done(null) }
    }

    fun saveUsername(username: String, displayName: String, done: (String?) -> Unit) {
        val uid = auth.currentUser?.uid ?: run { done("Please sign in again"); return }
        val clean = username.trim().removePrefix("@").lowercase()
        if (!clean.matches(Regex("[a-z0-9_]{3,20}"))) { done("Use 3–20 lowercase letters, numbers or underscores"); return }
        val usernameRef = db.collection("usernames").document(clean)
        val profileRef = db.collection("users").document(uid)
        db.runTransaction { transaction ->
            val existing = transaction.get(usernameRef)
            val profile = transaction.get(profileRef)
            if (existing.exists() && existing.getString("uid") != uid) throw IllegalStateException("Username already taken")
            val oldUsername = profile.getString("username") ?: ""
            if (oldUsername.isNotBlank() && oldUsername != clean) {
                val oldRef = db.collection("usernames").document(oldUsername)
                val oldDoc = transaction.get(oldRef)
                if (oldDoc.getString("uid") == uid) transaction.delete(oldRef)
            }
            transaction.set(usernameRef, mapOf("uid" to uid, "username" to clean))
            transaction.set(profileRef, mapOf(
                "uid" to uid, "displayName" to displayName.trim().ifBlank { "Callio friend" },
                "username" to clean, "photoURL" to ""
            ), com.google.firebase.firestore.SetOptions.merge())
        }.addOnSuccessListener {
            val profileUpdate = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                .setDisplayName(displayName.trim().ifBlank { "Callio friend" }).build()
            auth.currentUser?.updateProfile(profileUpdate)?.addOnCompleteListener { done(if (it.isSuccessful) null else it.exception?.localizedMessage) }
                ?: done(null)
        }.addOnFailureListener { done(it.localizedMessage ?: "Could not save username") }
    }

    fun callDocument(callId: String) = db.collection("calls").document(callId)
    fun firestore() = db
}
