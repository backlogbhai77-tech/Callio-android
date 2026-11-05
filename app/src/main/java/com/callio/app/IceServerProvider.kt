package com.callio.app.calling

import android.os.Handler
import android.os.Looper
import com.callio.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.net.HttpURLConnection
import java.net.URL

/**
 * ICE servers for WebRTC.
 * 1) Preferred: short-lived STUN/TURN credentials from the Callio backend (`/api/calls/ice`).
 * 2) Fallback: STUN + optional static TURN from gradle.properties (CALLIO_TURN_*).
 */
object IceServerProvider {
    @Volatile private var cached: List<PeerConnection.IceServer>? = null
    @Volatile private var cachedAt = 0L
    private const val TTL_MS = 30L * 60 * 1000
    private val main = Handler(Looper.getMainLooper())

    fun fallback(): List<PeerConnection.IceServer> {
        val list = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val urls = BuildConfig.CALLIO_TURN_URL.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (urls.isNotEmpty()) {
            list.add(
                PeerConnection.IceServer.builder(urls)
                    .setUsername(BuildConfig.CALLIO_TURN_USERNAME)
                    .setPassword(BuildConfig.CALLIO_TURN_CREDENTIAL)
                    .createIceServer()
            )
        }
        return list
    }

    /** Result is delivered on the main thread. Never fails: falls back to static servers. */
    fun load(done: (List<PeerConnection.IceServer>) -> Unit) {
        val c = cached
        if (c != null && System.currentTimeMillis() - cachedAt < TTL_MS) { done(c); return }
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || BuildConfig.CALLIO_SERVER_URL.contains("YOUR-CALLIO")) { done(fallback()); return }
        user.getIdToken(false).addOnSuccessListener { token ->
            Thread {
                val fetched = try { fetchRemote(token.token.orEmpty()) } catch (_: Exception) { null }
                if (!fetched.isNullOrEmpty()) { cached = fetched; cachedAt = System.currentTimeMillis() }
                main.post { done(fetched?.takeIf { it.isNotEmpty() } ?: fallback()) }
            }.start()
        }.addOnFailureListener { done(fallback()) }
    }

    fun prefetch() = load { }

    private fun fetchRemote(idToken: String): List<PeerConnection.IceServer> {
        val conn = (URL(BuildConfig.CALLIO_SERVER_URL.trimEnd('/') + "/api/calls/ice").openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000; readTimeout = 4_000
            setRequestProperty("Authorization", "Bearer $idToken")
        }
        try {
            if (conn.responseCode !in 200..299) return emptyList()
            val arr: JSONArray = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("iceServers")
            val out = mutableListOf<PeerConnection.IceServer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val urls = when (val u = o.get("urls")) {
                    is JSONArray -> (0 until u.length()).map { u.getString(it) }
                    else -> listOf(u.toString())
                }
                val b = PeerConnection.IceServer.builder(urls)
                if (o.has("username")) b.setUsername(o.getString("username"))
                if (o.has("credential")) b.setPassword(o.getString("credential"))
                out.add(b.createIceServer())
            }
            return out
        } finally { conn.disconnect() }
    }
}
