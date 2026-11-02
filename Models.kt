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

data class CallHistoryItem(
    val id: String,
    val personName: String,
    val status: String,
    val createdAtMillis: Long
)
