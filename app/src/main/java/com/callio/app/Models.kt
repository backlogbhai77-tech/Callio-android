package com.callio.app.data

data class CallioFriend(
    val uid: String,
    val displayName: String,
    val username: String = "",
    val friendshipId: String
)

data class IncomingCall(
    val id: String,
    val callerId: String,
    val callerName: String,
    val offerType: String,
    val offerSdp: String
)

data class FriendRequestItem(
    val id: String,
    val fromUid: String,
    val fromName: String,
    val fromUsername: String = ""
)

data class CallHistoryItem(
    val id: String,
    val otherUid: String,
    val personName: String,
    val outgoing: Boolean,
    val status: String,
    val createdAtMillis: Long,
    val durationSeconds: Long = 0L
)
