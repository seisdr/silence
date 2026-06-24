package com.silence.app.signaling

import com.google.firebase.messaging.FirebaseMessaging
import com.silence.app.service.FcmService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.ByteString
import org.msgpack.core.MessagePack
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * WebSocket client using MessagePack binary encoding for compact signaling.
 *
 * On first registration, includes the FCM device token so the relay
 * can deliver incoming call pushes when the WebSocket is disconnected.
 */
@Singleton
class SignalingClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val _events = Channel<SignalingEvent>(Channel.BUFFERED)
    val events: Flow<SignalingEvent> = _events.receiveAsFlow()

    private var roomId: String? = null
    private var fcmToken: String? = null

    sealed interface SignalingEvent {
        data object Registered : SignalingEvent
        data class LoggedIn(val username: String) : SignalingEvent
        data class Created(val roomId: String) : SignalingEvent
        data class Incoming(val roomId: String, val fromFingerprint: String) : SignalingEvent
        data class PeerJoined(val roomId: String) : SignalingEvent
        data class Offer(val sdp: String) : SignalingEvent
        data class Answer(val sdp: String) : SignalingEvent
        data class IceCandidate(val sdpMid: String, val sdpMLineIndex: Int, val sdp: String) : SignalingEvent
        data class Hangup(val roomId: String) : SignalingEvent
        data class SearchResults(val results: List<String>) : SignalingEvent
        data class Error(val message: String) : SignalingEvent
        data object Disconnected : SignalingEvent
    }

    fun connect(serverUrl: String) {
        disconnect()
        val request = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {}
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(okio.ByteString.of(*text.toByteArray(Charsets.UTF_8)))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _events.trySend(SignalingEvent.Disconnected)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _events.trySend(SignalingEvent.Disconnected)
            }
        })
    }

    /**
     * Register with the signaling relay, including the FCM device token.
     * The relay uses the token to push incoming call notifications when
     * this device's WebSocket is disconnected.
     */
    suspend fun register(fingerprint: String) {
        // Fetch FCM token if not already cached
        if (fcmToken == null) {
            fcmToken = fetchFcmToken()
        }

        val msg = mutableMapOf<String, Any>(
            "type" to "register",
            "fingerprint" to fingerprint
        )
        fcmToken?.let { msg["fcm_token"] = it }

        send(msg)
    }

    fun call(targetFingerprint: String) {
        send(mapOf("type" to "call", "target" to targetFingerprint))
    }

    /** Create a new account on the relay. Auto-authenticates on success. */
    suspend fun registerUser(username: String, password: String, fingerprint: String) {
        if (fcmToken == null) fcmToken = fetchFcmToken()
        val msg = mutableMapOf<String, Any>(
            "type" to "register_user",
            "username" to username,
            "password" to password,
            "fingerprint" to fingerprint
        )
        fcmToken?.let { msg["fcm_token"] = it }
        send(msg)
    }

    /** Authenticate with existing credentials. */
    fun login(username: String, password: String) {
        send(mapOf("type" to "login", "username" to username, "password" to password))
    }

    /** Initiate a call by username. Requires prior login. */
    fun callUser(username: String) {
        send(mapOf("type" to "call_user", "username" to username))
    }

    /** Search for users by username prefix. */
    fun searchUser(query: String) {
        send(mapOf("type" to "search_user", "query" to query))
    }

    fun accept(roomId: String) {
        this.roomId = roomId
        send(mapOf("type" to "accept", "room" to roomId))
    }

    fun sendOffer(sdp: String) {
        send(mapOf("type" to "offer", "sdp" to sdp, "room" to (roomId ?: "")))
    }

    fun sendAnswer(sdp: String) {
        send(mapOf("type" to "answer", "sdp" to sdp, "room" to (roomId ?: "")))
    }

    fun sendIce(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        send(mapOf(
            "type" to "ice", "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex, "candidate" to sdp,
            "room" to (roomId ?: "")
        ))
    }

    fun sendHangup() {
        send(mapOf("type" to "hangup", "room" to (roomId ?: "")))
    }

    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        roomId = null
    }

    // ── internal ──────────────────────────────────────────────

    private suspend fun fetchFcmToken(): String? {
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && task.result != null) {
                    cont.resume(task.result)
                } else {
                    // Try the callback path (onNewToken may not have fired yet)
                    FcmService.onToken { token ->
                        if (cont.isActive) cont.resume(token)
                    }
                    // Also try the synchronous path
                    val cached = task.result
                    if (cached != null && cont.isActive) cont.resume(cached)
                }
            }
        }
    }

    private fun send(map: Map<String, Any>) {
        val packer = MessagePack.newDefaultBufferPacker()
        packMap(packer, map)
        packer.close()
        ws?.send(ByteString.of(*packer.toByteArray()))
    }

    private fun packMap(packer: org.msgpack.core.MessagePacker, map: Map<String, Any>) {
        packer.packMapHeader(map.size)
        for ((k, v) in map) {
            packer.packString(k)
            when (v) {
                is String -> packer.packString(v)
                is Int -> packer.packInt(v)
                is Boolean -> packer.packBoolean(v)
                else -> packer.packString(v.toString())
            }
        }
    }

    private fun handleMessage(bytes: ByteString) {
        try {
            val unpacker = MessagePack.newDefaultUnpacker(bytes.toByteArray())
            val map = unpackMap(unpacker)

            val type = map["type"] as? String ?: return
            when (type) {
                "registered" -> _events.trySend(SignalingEvent.Registered)
                "logged_in" -> {
                    val uname = map["username"] as? String ?: ""
                    _events.trySend(SignalingEvent.LoggedIn(uname))
                }
                "created" -> {
                    roomId = map["room"] as? String
                    roomId?.let { _events.trySend(SignalingEvent.Created(it)) }
                }
                "incoming" -> {
                    roomId = map["room"] as? String
                    val from = map["from"] as? String ?: ""
                    roomId?.let { _events.trySend(SignalingEvent.Incoming(it, from)) }
                }
                "joined" -> {
                    val rid = map["room"] as? String ?: ""
                    _events.trySend(SignalingEvent.PeerJoined(rid))
                }
                "offer" -> {
                    val sdp = map["sdp"] as? String ?: return
                    _events.trySend(SignalingEvent.Offer(sdp))
                }
                "answer" -> {
                    val sdp = map["sdp"] as? String ?: return
                    _events.trySend(SignalingEvent.Answer(sdp))
                }
                "ice" -> {
                    _events.trySend(SignalingEvent.IceCandidate(
                        sdpMid = map["sdpMid"] as? String ?: "",
                        sdpMLineIndex = (map["sdpMLineIndex"] as? Int) ?: 0,
                        sdp = map["candidate"] as? String ?: return
                    ))
                }
                "hangup" -> {
                    _events.trySend(SignalingEvent.Hangup(map["room"] as? String ?: roomId ?: ""))
                }
                "error" -> {
                    _events.trySend(SignalingEvent.Error(map["message"] as? String ?: "unknown"))
                }
                "search_results" -> {
                    val results = map["results"] as? List<*> 
                    _events.trySend(SignalingEvent.SearchResults(
                        results?.mapNotNull { it as? String } ?: emptyList()
                    ))
                }
            }
        } catch (e: Exception) {
            _events.trySend(SignalingEvent.Error("decode: ${e.message}"))
        }
    }

    private fun unpackMap(unpacker: org.msgpack.core.MessageUnpacker): Map<String, Any> {
        val size = unpacker.unpackMapHeader()
        val map = mutableMapOf<String, Any>()
        for (i in 0 until size) {
            val key = unpacker.unpackString()
            val value: Any = when (unpacker.nextFormat.valueType) {
                org.msgpack.value.ValueType.STRING -> unpacker.unpackString()
                org.msgpack.value.ValueType.INTEGER -> unpacker.unpackInt()
                org.msgpack.value.ValueType.BOOLEAN -> unpacker.unpackBoolean()
                org.msgpack.value.ValueType.NIL -> { unpacker.unpackNil(); "" }
                else -> unpacker.unpackValue().toString()
            }
            map[key] = value
        }
        return map
    }
}
