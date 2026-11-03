package com.callio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callio.app.data.CallioFriend
import com.callio.app.data.IncomingCall
import com.google.firebase.auth.FirebaseUser

private val Ink = Color(0xFF0B0A12)
private val Panel = Color(0xFF171522)
private val Panel2 = Color(0xFF211D32)
private val Lavender = Color(0xFFB49AFF)
private val Violet = Color(0xFF8062D9)
private val Muted = Color(0xFFAAA4B8)
private val Mint = Color(0xFF6FE0B0)
private val Danger = Color(0xFFFF7189)

@Composable
fun CallioApp(
    user: FirebaseUser?, savedDisplayName: String, savedUsername: String, friends: List<CallioFriend>, requests: List<Pair<String, String>>,
    incomingCall: IncomingCall?, callState: String, callPerson: String, callMuted: Boolean, callSpeaker: Boolean,
    toastMessage: String, onSignIn: (String, String) -> Unit, onSignUp: (String, String, String) -> Unit,
    onSignOut: () -> Unit, onCall: (CallioFriend) -> Unit, onSearchAndAdd: (String) -> Unit,
    onAcceptRequest: (String) -> Unit, onSaveProfile: (String, String) -> Unit,
    onAcceptCall: () -> Unit, onRejectCall: () -> Unit, onEndCall: () -> Unit,
    onToggleMute: () -> Unit, onToggleSpeaker: () -> Unit, onClearMessage: () -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Lavender, secondary = Violet, background = Ink, surface = Panel,
        onPrimary = Ink, onBackground = Color.White, onSurface = Color.White
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            when {
                user == null -> AuthScreen(onSignIn, onSignUp)
                incomingCall != null && callState.isBlank() -> IncomingScreen(incomingCall, onAcceptCall, onRejectCall)
                callState.isNotBlank() -> ActiveCallScreen(callPerson, callState, callMuted, callSpeaker, onToggleMute, onToggleSpeaker, onEndCall)
                else -> HomeScreen(user, savedDisplayName, savedUsername, friends, requests, toastMessage, onSignOut, onCall, onSearchAndAdd, onAcceptRequest, onSaveProfile, onClearMessage)
            }
        }
    }
}

@Composable
private fun AuthScreen(onSignIn: (String, String) -> Unit, onSignUp: (String, String, String) -> Unit) {
    var signup by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF211A38), Ink, Ink)))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
            BrandMark()
            Spacer(Modifier.height(34.dp))
            Text(if (signup) "Find your people." else "Good to hear\nyou again.", fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("A little closer, every day.", color = Muted, fontSize = 15.sp)
            Spacer(Modifier.height(28.dp))
            if (signup) InputField("Your name", name, { name = it })
            InputField("Email address", email, { email = it })
            InputField("Password", password, { password = it }, secret = true)
            Spacer(Modifier.height(14.dp))
            PrimaryButton(if (signup) "Create account" else "Sign in") {
                if (email.isNotBlank() && password.length >= 6) {
                    if (signup) onSignUp(name, email, password) else onSignIn(email, password)
                }
            }
            TextButton(onClick = { signup = !signup }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (signup) "Already have an account? Sign in" else "New to Callio? Create an account", color = Lavender)
            }
            Text("Your existing Callio email and password account works here.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HomeScreen(
    user: FirebaseUser, savedDisplayName: String, savedUsername: String, friends: List<CallioFriend>, requests: List<Pair<String, String>>, toast: String,
    onSignOut: () -> Unit, onCall: (CallioFriend) -> Unit, onSearchAndAdd: (String) -> Unit,
    onAcceptRequest: (String) -> Unit, onSaveProfile: (String, String) -> Unit, onClearMessage: () -> Unit
) {
    var username by remember(user.uid, savedUsername) { mutableStateOf(savedUsername) }
    var displayName by remember(user.uid, savedDisplayName) { mutableStateOf(savedDisplayName.ifBlank { user.displayName ?: "" }) }
    var search by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1C1730), Ink))).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(Modifier.weight(1f))
            TextButton(onClick = onSignOut) { Text("Sign out", color = Muted) }
        }
        Text("YOUR CIRCLE", color = Lavender, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Hey, ${user.displayName?.substringBefore(" ")?.ifBlank { "there" } ?: "there"}.", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Good conversations start here.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Lavender, Violet))), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(Icons.Default.Headphones, null, tint = Ink, modifier = Modifier.size(25.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Callio audio", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Private conversations, just a tap away", color = Muted, fontSize = 11.sp)
                }
                androidx.compose.material3.Icon(Icons.Default.Wifi, null, tint = Mint)
            }
        }
        if (toast.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF28213A)).clickable { onClearMessage() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(toast, color = Lavender, modifier = Modifier.weight(1f), fontSize = 12.sp)
                Text("×", color = Muted)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Find a friend", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Search @username") }, shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSearchAndAdd(search); search = "" }, colors = ButtonDefaults.buttonColors(containerColor = Violet), shape = RoundedCornerShape(15.dp), modifier = Modifier.height(54.dp)) {
                androidx.compose.material3.Icon(Icons.Default.PersonAdd, contentDescription = "Add")
            }
        }
        if (requests.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Friend requests · ${requests.size}", color = Lavender, fontWeight = FontWeight.SemiBold)
            requests.take(3).forEach { (id, fromUid) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Callio member · ${fromUid.take(7)}", color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { onAcceptRequest(id) }) { Text("Accept", color = Mint) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Your friends", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${friends.size} connected", color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (friends.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your circle starts with one friend", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp)); Text("Search using their unique Callio username.", color = Muted, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(friends, key = { it.uid }) { friend -> FriendRow(friend, onCall) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(Icons.Default.Settings, null, tint = Lavender)
                    Spacer(Modifier.width(8.dp)); Text("Your Callio profile", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = displayName, onValueChange = { displayName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Display name") })
                OutlinedTextField(value = username, onValueChange = { username = it.removePrefix("@").lowercase() }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Unique username") }, placeholder = { Text("e.g. callio_friend") })
                TextButton(onClick = { onSaveProfile(displayName, username) }) { Text("Save profile", color = Lavender) }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun FriendRow(friend: CallioFriend, onCall: (CallioFriend) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(19.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF3A3156)), contentAlignment = Alignment.Center) {
                Text(friend.displayName.take(1).uppercase(), color = Lavender, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(friend.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(if (friend.username.isBlank()) "Callio member" else "@${friend.username}", color = Muted, fontSize = 11.sp)
            }
            Button(onClick = { onCall(friend) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B52B8)), shape = CircleShape, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                androidx.compose.material3.Icon(Icons.Default.Call, contentDescription = "Call", tint = Color.White)
            }
        }
    }
}

@Composable
private fun IncomingScreen(call: IncomingCall, onAccept: () -> Unit, onReject: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF282044), Ink, Ink))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
            BrandMark()
            Spacer(Modifier.height(70.dp))
            Box(Modifier.size(118.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Lavender, Violet))), contentAlignment = Alignment.Center) {
                Text(call.callerName.take(1).uppercase(), color = Ink, fontSize = 46.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(28.dp))
            Text("INCOMING AUDIO CALL", color = Lavender, letterSpacing = 2.sp, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(call.callerName, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("is calling you", color = Muted)
            Spacer(Modifier.height(76.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RoundAction(Danger, Icons.Default.CallEnd, "Decline", onReject)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RoundAction(Mint, Icons.Default.Call, "Accept", onAccept)
                }
            }
        }
    }
}

@Composable
private fun ActiveCallScreen(person: String, state: String, muted: Boolean, speaker: Boolean, onMute: () -> Unit, onSpeaker: () -> Unit, onEnd: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF292044), Ink, Ink))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(26.dp)) {
            BrandMark()
            Spacer(Modifier.height(70.dp))
            Box(Modifier.size(122.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Lavender, Violet))), contentAlignment = Alignment.Center) {
                Text(person.take(1).uppercase(), color = Ink, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(25.dp))
            Text(person, color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Text(when (state) { "connected" -> "Connected · Audio call"; "ringing" -> "Calling…"; "reconnecting" -> "Reconnecting…"; else -> "Connecting…" }, color = if (state == "connected") Mint else Muted)
            if (state == "reconnecting") { Spacer(Modifier.height(18.dp)); CircularProgressIndicator(color = Lavender) }
            Spacer(Modifier.height(92.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundAction(if (muted) Lavender else Panel2, if (muted) Icons.Default.MicOff else Icons.Default.Mic, if (muted) "Unmute" else "Mute", onMute, foreground = Color.White)
                RoundAction(Danger, Icons.Default.CallEnd, "End", onEnd, size = 68)
                RoundAction(if (speaker) Lavender else Panel2, Icons.Default.Speaker, if (speaker) "Speaker on" else "Speaker", onSpeaker, foreground = Color.White)
            }
            Spacer(Modifier.height(40.dp))
            Text("Callio · Private audio", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RoundAction(color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, size: Int = 58, foreground: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(color).clickable { onClick() }, contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, contentDescription = label, tint = foreground, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(8.dp)); Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(Lavender, Violet))), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(Icons.Default.PhoneInTalk, null, tint = Ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("callio.", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Text("YOUR PEOPLE, CLOSER", color = Muted, fontSize = 8.sp, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun InputField(label: String, value: String, onValue: (String) -> Unit, secret: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onValue, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(16.dp), visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Lavender, contentColor = Ink)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
