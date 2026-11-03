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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

private val Ink = Color(0xFF090811)
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
                else -> MainShell(
                    user, savedDisplayName, savedUsername, friends, requests, toastMessage,
                    onSignOut, onCall, onSearchAndAdd, onAcceptRequest, onSaveProfile, onClearMessage
                )
            }
        }
    }
}

@Composable
private fun MainShell(
    user: FirebaseUser, savedDisplayName: String, savedUsername: String,
    friends: List<CallioFriend>, requests: List<Pair<String, String>>, toast: String,
    onSignOut: () -> Unit, onCall: (CallioFriend) -> Unit, onSearchAndAdd: (String) -> Unit,
    onAcceptRequest: (String) -> Unit, onSaveProfile: (String, String) -> Unit, onClearMessage: () -> Unit
) {
    var selectedTab by remember(user.uid) { mutableIntStateOf(0) }
    androidx.compose.material3.Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF12101B), contentColor = Lavender) {
                val tabs = listOf(
                    Triple("Home", Icons.Default.Home, 0),
                    Triple("Friends", Icons.Default.Group, 1),
                    Triple("Requests", Icons.Default.Notifications, 2),
                    Triple("Profile", Icons.Default.Person, 3)
                )
                tabs.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { androidx.compose.material3.Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 10.sp) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets)
                .background(Brush.verticalGradient(listOf(Color(0xFF1B1730), Ink)))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandMark(Modifier.weight(1f))
                Box(Modifier.size(40.dp).clip(CircleShape).background(Panel2), contentAlignment = Alignment.Center) {
                    Text(initialOf(savedDisplayName.ifBlank { user.displayName ?: user.email ?: "C" }), color = Lavender, fontWeight = FontWeight.Bold)
                }
            }
            if (toast.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2340)),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { onClearMessage() }
                ) {
                    Text(toast, color = Lavender, fontSize = 12.sp, modifier = Modifier.padding(13.dp))
                }
                Spacer(Modifier.height(14.dp))
            }
            when (selectedTab) {
                0 -> HomeTab(user, friends, requests, onCall, onAcceptRequest, onGoFriends = { selectedTab = 1 })
                1 -> FriendsTab(friends, onCall, onSearchAndAdd)
                2 -> RequestsTab(requests, onAcceptRequest)
                else -> ProfileTab(user, savedDisplayName, savedUsername, onSaveProfile, onSignOut)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeTab(
    user: FirebaseUser, friends: List<CallioFriend>, requests: List<Pair<String, String>>,
    onCall: (CallioFriend) -> Unit, onAcceptRequest: (String) -> Unit, onGoFriends: () -> Unit
) {
    val firstName = user.displayName?.substringBefore(" ")?.ifBlank { "there" } ?: "there"
    Text("YOUR PRIVATE CIRCLE", color = Lavender, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    Spacer(Modifier.height(7.dp))
    Text("Hey, $firstName.", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
    Text("A little closer, every day.", color = Muted, fontSize = 14.sp)
    Spacer(Modifier.height(22.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel2),
        shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF3A2B60), Color(0xFF211D32)))).padding(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Lavender), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(Icons.Default.Headphones, null, tint = Ink, modifier = Modifier.size(23.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Callio Audio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Just voices. Just your people.", color = Muted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(23.dp))
                Text("Good conversations", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("are one tap away.", color = Lavender, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(Icons.Default.Wifi, null, tint = Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Private audio calling", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(23.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Your friends", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${friends.size} people in your circle", color = Muted, fontSize = 12.sp)
        }
        TextButton(onClick = onGoFriends) { Text("See all", color = Lavender) }
    }
    if (friends.isEmpty()) {
        EmptyCard(
            title = "Your circle starts here",
            detail = "Open Friends and search using a Callio username."
        )
    } else {
        friends.take(4).forEach { FriendRow(it, onCall) }
    }
    if (requests.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Text("Friend requests · ${requests.size}", color = Lavender, fontWeight = FontWeight.SemiBold)
        requests.take(2).forEach { (id, fromUid) ->
            RequestRow(id, fromUid, onAcceptRequest)
        }
    }
}

@Composable
private fun FriendsTab(friends: List<CallioFriend>, onCall: (CallioFriend) -> Unit, onSearchAndAdd: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    Text("Your people", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
    Text("Find friends and start an audio call.", color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(20.dp))
    AddFriendField(onSearchAndAdd)
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = search, onValueChange = { search = it },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text("Filter your friends") }, shape = RoundedCornerShape(15.dp)
    )
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("ALL FRIENDS", color = Lavender, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
        Text("${friends.size}", color = Muted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))
    if (friends.isEmpty()) {
        EmptyCard("No friends yet", "Ask a friend for their username, then send a request above.")
    } else {
        friends.filter { it.displayName.contains(search, true) || it.username.contains(search, true) }
            .forEach { FriendRow(it, onCall) }
    }
}

@Composable
private fun RequestsTab(requests: List<Pair<String, String>>, onAcceptRequest: (String) -> Unit) {
    Text("Friend requests", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
    Text("People who want to join your circle.", color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(20.dp))
    if (requests.isEmpty()) {
        EmptyCard("All caught up", "New friend requests will appear here.")
    } else {
        requests.forEach { (id, fromUid) -> RequestRow(id, fromUid, onAcceptRequest) }
    }
}

@Composable
private fun ProfileTab(
    user: FirebaseUser, savedDisplayName: String, savedUsername: String,
    onSaveProfile: (String, String) -> Unit, onSignOut: () -> Unit
) {
    var username by remember(user.uid, savedUsername) { mutableStateOf(savedUsername) }
    var displayName by remember(user.uid, savedDisplayName) { mutableStateOf(savedDisplayName.ifBlank { user.displayName ?: "" }) }
    Text("Your profile", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
    Text("Make Callio feel like yours.", color = Muted, fontSize = 13.sp)
    Spacer(Modifier.height(24.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(82.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Lavender, Violet))), contentAlignment = Alignment.Center) {
                Text(initialOf(displayName.ifBlank { user.email ?: "C" }), color = Ink, fontSize = 31.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(displayName.ifBlank { "Callio friend" }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(user.email.orEmpty(), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
            InputField("Display name", displayName, { displayName = it })
            InputField("Unique username", username, { username = it.removePrefix("@").lowercase() })
            Text("Username: 3–20 letters, numbers or underscores", color = Muted, fontSize = 10.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Save profile") { onSaveProfile(displayName, username) }
        }
    }
    Spacer(Modifier.height(18.dp))
    TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
        Text("Sign out", color = Danger, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AddFriendField(onSearchAndAdd: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = search, onValueChange = { search = it.removePrefix("@") },
            modifier = Modifier.weight(1f), singleLine = true,
            placeholder = { Text("Search @username") }, shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { if (search.isNotBlank()) { onSearchAndAdd(search); search = "" } },
            enabled = search.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Violet),
            shape = RoundedCornerShape(15.dp), modifier = Modifier.height(54.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 15.dp)
        ) {
            androidx.compose.material3.Icon(Icons.Default.PersonAdd, contentDescription = "Add friend")
        }
    }
}

@Composable
private fun RequestRow(id: String, fromUid: String, onAcceptRequest: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(initialOf(fromUid), size = 42)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Callio member", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("ID ${fromUid.take(8)}", color = Muted, fontSize = 10.sp)
            }
            Button(onClick = { onAcceptRequest(id) }, colors = ButtonDefaults.buttonColors(containerColor = Violet), shape = RoundedCornerShape(13.dp)) {
                androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = "Accept")
                Spacer(Modifier.width(5.dp)); Text("Accept")
            }
        }
    }
}

@Composable
private fun FriendRow(friend: CallioFriend, onCall: (CallioFriend) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(19.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(initialOf(friend.displayName), size = 46)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(friend.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(if (friend.username.isBlank()) "Callio member" else "@${friend.username}", color = Muted, fontSize = 11.sp)
            }
            Button(
                onClick = { onCall(friend) }, colors = ButtonDefaults.buttonColors(containerColor = Violet),
                shape = CircleShape, modifier = Modifier.size(46.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                androidx.compose.material3.Icon(Icons.Default.Call, contentDescription = "Call", tint = Color.White)
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(21.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Panel2), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(Icons.Default.Group, null, tint = Lavender, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(detail, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Avatar(initial: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Lavender, Violet))), contentAlignment = Alignment.Center) {
        Text(initial, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
            Spacer(Modifier.height(38.dp))
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
private fun IncomingScreen(call: IncomingCall, onAccept: () -> Unit, onReject: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF282044), Ink, Ink))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
            BrandMark()
            Spacer(Modifier.height(70.dp))
            Avatar(initialOf(call.callerName), 118)
            Spacer(Modifier.height(28.dp))
            Text("INCOMING AUDIO CALL", color = Lavender, letterSpacing = 2.sp, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(call.callerName, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("is calling you", color = Muted)
            Spacer(Modifier.height(76.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundAction(Danger, Icons.Default.CallEnd, "Decline", onReject)
                RoundAction(Mint, Icons.Default.Call, "Accept", onAccept, foreground = Ink)
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
            Avatar(initialOf(person), 122)
            Spacer(Modifier.height(25.dp))
            Text(person, color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Text(when (state) { "connected" -> "Connected · Audio call"; "ringing" -> "Calling…"; "reconnecting" -> "Reconnecting…"; else -> "Connecting…" }, color = if (state == "connected") Mint else Muted)
            if (state == "reconnecting") { Spacer(Modifier.height(18.dp)); CircularProgressIndicator(color = Lavender) }
            Spacer(Modifier.height(92.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundAction(if (muted) Lavender else Panel2, if (muted) Icons.Default.MicOff else Icons.Default.Mic, if (muted) "Unmute" else "Mute", onMute)
                RoundAction(Danger, Icons.Default.CallEnd, "End", onEnd, size = 68)
                RoundAction(if (speaker) Lavender else Panel2, Icons.Default.Speaker, if (speaker) "Speaker on" else "Speaker", onSpeaker)
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
    OutlinedTextField(
        value = value, onValueChange = onValue, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(16.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Lavender, contentColor = Ink)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

private fun initialOf(value: String): String = value.trim().firstOrNull()?.uppercase() ?: "C"
