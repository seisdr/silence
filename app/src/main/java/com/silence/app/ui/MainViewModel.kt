package com.silence.app.ui

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silence.app.data.Contact
import com.silence.app.data.ContactStore
import com.silence.app.data.SettingsStore
import com.silence.app.data.AuthState
import com.silence.app.data.AuthStore
import com.silence.app.identity.IdentityManager
import com.silence.app.signaling.SignalingClient
import com.silence.app.ui.screens.CallStateUi
import com.silence.app.webrtc.WebRtcEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    val identityManager: IdentityManager,
    val contactStore: ContactStore,
    private val signalingClient: SignalingClient,
    private val webRtcEngine: WebRtcEngine,
    private val settingsStore: SettingsStore,
    private val authStore: AuthStore
) : AndroidViewModel(application) {

    // ── State flows ────────────────────────────────────────────

    val identity = identityManager.identity.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val contacts = contactStore.contacts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())


    // ── Auth state ─────────────────────────────────────────────

    private val _authState = MutableStateFlow<AuthState?>(null)
    val authState: StateFlow<AuthState?> = _authState.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _authLoading = MutableStateFlow(false)
    val authLoading: StateFlow<Boolean> = _authLoading.asStateFlow()

    // Credentials captured at login/register time so they can be persisted once
    // the server confirms success. (The password is otherwise lost before the
    // LoggedIn/Registered event arrives, so auto-login never worked.)
    private var pendingCredentials: Pair<String, String>? = null
    private val _callState = MutableStateFlow(CallStateUi.IDLE)
    val callState: StateFlow<CallStateUi> = _callState.asStateFlow()

    private val _activeContact = MutableStateFlow<Contact?>(null)
    val activeContact: StateFlow<Contact?> = _activeContact.asStateFlow()

    private val _e2eeFingerprint = MutableStateFlow<String?>(null)
    val e2eeFingerprint: StateFlow<String?> = _e2eeFingerprint.asStateFlow()

    private val _e2eeVerified = MutableStateFlow(false)
    val e2eeVerified: StateFlow<Boolean> = _e2eeVerified.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _speakerOn = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private val _inCall = MutableStateFlow(false)
    val inCall: StateFlow<Boolean> = _inCall.asStateFlow()

    // ── Call timer ──────────────────────────────────────────────
    private val _callDuration = MutableStateFlow("")
    val callDuration: StateFlow<String> = _callDuration.asStateFlow()
    private var callTimerJob: kotlinx.coroutines.Job? = null

    // Incoming call tracking — set when server sends 'incoming' event
    private var _incomingRoomId: String? = null
    private var _incomingFromFingerprint: String? = null

    private val _signalingUrl = MutableStateFlow(SettingsStore.DEFAULT_SIGNALING_URL)
    val signalingUrl: StateFlow<String> = _signalingUrl.asStateFlow()

    // ── Navigation state ───────────────────────────────────────

    private val _navigateTo = MutableSharedFlow<NavTarget>(extraBufferCapacity = 4)
    val navigateTo: SharedFlow<NavTarget> = _navigateTo.asSharedFlow()

    sealed interface NavTarget {
        data object Identity : NavTarget
        data object Contacts : NavTarget
        data object Scan : NavTarget
        data class Call(val contactName: String) : NavTarget
    }

    init {
        // Observe stored signaling URL
        viewModelScope.launch {
            settingsStore.signalingUrl.collect { url ->
                _signalingUrl.value = url
            }
        }

        // Observe WebRTC engine events
        viewModelScope.launch {
            webRtcEngine.events.collect { event ->
                handleEngineEvent(event)
            }
        }

        // Observe signaling events
        viewModelScope.launch {
            signalingClient.events.collect { event ->
                handleSignalingEvent(event)
            }
        }

        // Initialize WebRTC
        webRtcEngine.initialize()

        // Load persisted auth state
        viewModelScope.launch {
            authStore.authState.collect { state ->
                _authState.value = state
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // SignalingClient and WebRtcEngine are singletons — they outlive the ViewModel.
        // The foreground service keeps the process alive for incoming calls.
        // Only clean up UI-observed state here.
    }

    // ── Actions ─────────────────────────────────────────────────

    fun startService() {
        viewModelScope.launch {
            val url = signalingUrl.value
            signalingClient.connect(url)
        }
    }

    // ── Auth actions ───────────────────────────────────────────

    fun login(username: String, password: String) {
        _authError.value = null
        _authLoading.value = true
        pendingCredentials = username to password
        signalingClient.login(username, password)
    }

    fun registerUser(username: String, password: String) {
        _authError.value = null
        _authLoading.value = true
        pendingCredentials = username to password
        viewModelScope.launch {
            val fp = identity.value?.fingerprint ?: return@launch
            signalingClient.registerUser(username, password, fp)
        }
    }

    fun logout() {
        pendingCredentials = null
        viewModelScope.launch {
            authStore.clearCredentials()
            signalingClient.disconnect()
            _authState.value = AuthState(null, null)
        }
    }

    private fun persistPendingCredentials(usernameOverride: String? = null) {
        val creds = pendingCredentials
        pendingCredentials = null
        if (creds == null) return
        val username = usernameOverride ?: creds.first
        viewModelScope.launch {
            authStore.saveCredentials(username, creds.second)
        }
    }

    fun setSignalingUrl(url: String) {
        _signalingUrl.value = url
        viewModelScope.launch { settingsStore.setSignalingUrl(url) }
    }

    /** Register this device's fingerprint with the signaling relay. Call after WebSocket is open. */
    fun registerWithSignaling() {
        viewModelScope.launch {
            val fp = identity.value?.fingerprint ?: return@launch
            signalingClient.register(fp)
        }
    }

    /** Make an outbound call to a contact by their identity fingerprint. */
    fun callContact(contact: Contact) {
        _activeContact.value = contact
        _callState.value = CallStateUi.DIALING
        _inCall.value = true
        _e2eeVerified.value = false
        _e2eeFingerprint.value = null

        // Compute target fingerprint from stored public key
        val targetPub = android.util.Base64.decode(contact.publicKeyB64, android.util.Base64.NO_WRAP)
        val targetFp = identityManager.fingerprint(targetPub)
        signalingClient.call(targetFp)
        viewModelScope.launch { _navigateTo.emit(NavTarget.Call(contact.name)) }
    }

    /** Call a user by username. Requires prior login. */
    fun callByUsername(username: String) {
        _activeContact.value = Contact(username, "")
        _callState.value = CallStateUi.DIALING
        _inCall.value = true
        _e2eeVerified.value = false
        _e2eeFingerprint.value = null
        signalingClient.callUser(username)
        viewModelScope.launch { _navigateTo.emit(NavTarget.Call(username)) }
    }

    fun acceptCall() {
        stopRing()
        signalingClient.accept(roomId = _incomingRoomId ?: return)
        _callState.value = CallStateUi.CONNECTED
    }

    // ── ringtone (synthesized, no assets/permissions) ─────────────
    private var toneGen: ToneGenerator? = null
    private val ringHandler = Handler(Looper.getMainLooper())
    private var ringRunnable: Runnable? = null
    private fun startRing() {
        stopRing()
        try { toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) } catch (e: Exception) { return }
        val r = object : Runnable {
            override fun run() {
                try { toneGen?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 1000) } catch (e: Exception) {}
                ringHandler.postDelayed(this, 4000) // 1s on, 3s off
            }
        }
        ringRunnable = r
        ringHandler.post(r)
        // Auto-stop after 45s to avoid ringing forever.
    }

    // ── call timer ──────────────────────────────────────────────
    private fun startCallTimer() {
        stopCallTimer()
        val start = System.currentTimeMillis()
        callTimerJob = viewModelScope.launch {
            while (true) {
                val elapsed = (System.currentTimeMillis() - start) / 1000
                val mm = (elapsed / 60).toString().padStart(2, '0')
                val ss = (elapsed % 60).toString().padStart(2, '0')
                _callDuration.value = "$mm:$ss"
                kotlinx.coroutines.delay(500)
            }
        }
    }
    private fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
        _callDuration.value = ""
    }
    private fun stopRing() {
        ringRunnable?.let { ringHandler.removeCallbacks(it) }
        ringRunnable = null
        try { toneGen?.release() } catch (e: Exception) {}
        toneGen = null
    }

    // ── auto-reconnect (exponential backoff, gated by authState) ────
    private var reconnectAttempt = 0
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        // Only reconnect while the user is signed in.
        if (_authState.value?.isLoggedIn != true) return
        reconnectJob = viewModelScope.launch {
            val delay = (1000L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(10000L)
            reconnectAttempt++
            kotlinx.coroutines.delay(delay)
            val url = signalingUrl.value
            signalingClient.connect(url)
            // Let the WS open, then re-register so we stay reachable for calls.
            kotlinx.coroutines.delay(800)
            val fp = identity.value?.fingerprint
            if (fp != null) signalingClient.register(fp)
        }
    }

    /**
     * Handle an incoming call launched from an FCM push notification.
     * The FCM data payload contains the room ID and caller fingerprint.
     *
     * Flow: connect WS → register → accept room → server replays queued
     * offer/ICE → WebRTC handles normally.
     */
    fun handleIncomingFcmCall(room: String, fromFingerprint: String) {
        // If already in an active call, reject silently (call waiting not yet supported)
        if (_inCall.value && _callState.value != CallStateUi.ENDED) return
        _incomingRoomId = room
        _incomingFromFingerprint = fromFingerprint

        // Look up caller in contacts
        val caller = contacts.value.find { contact ->
            val pub = android.util.Base64.decode(contact.publicKeyB64, android.util.Base64.NO_WRAP)
            identityManager.fingerprint(pub) == fromFingerprint
        }
        _activeContact.value = caller ?: Contact(fromFingerprint.take(8), "")
        _callState.value = CallStateUi.RINGING
        _inCall.value = true
        _e2eeVerified.value = false

        // Join the room — the server will replay any queued offer/ICE messages
        viewModelScope.launch {
            // Ensure WebSocket is connected and registered
            val url = signalingUrl.value
            signalingClient.connect(url)
            // Give the connection a moment, then register
            kotlinx.coroutines.delay(500)
            val fp = identity.value?.fingerprint ?: return@launch
            signalingClient.register(fp)
            kotlinx.coroutines.delay(300)
            signalingClient.accept(room)
        }
    }

    fun hangup() {
        stopRing()
        stopCallTimer()
        signalingClient.sendHangup()
        webRtcEngine.endCall()
        _callState.value = CallStateUi.ENDED
        _inCall.value = false
        _e2eeFingerprint.value = null
        _e2eeVerified.value = false

        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            _navigateTo.emit(NavTarget.Contacts)
        }
    }

    fun toggleMute() {
        val new = !_muted.value
        _muted.value = new
        webRtcEngine.toggleMute(new)
    }

    fun toggleSpeaker() {
        val new = !_speakerOn.value
        _speakerOn.value = new
        webRtcEngine.toggleSpeaker(new)
    }

    fun addContact(name: String, publicKeyB64: String) {
        viewModelScope.launch {
            contactStore.addContact(name, publicKeyB64)
            _navigateTo.emit(NavTarget.Contacts)
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch {
            contactStore.removeContact(contact.name)
        }
    }

    fun showIdentity() {
        viewModelScope.launch { _navigateTo.emit(NavTarget.Identity) }
    }

    fun showScanner() {
        viewModelScope.launch { _navigateTo.emit(NavTarget.Scan) }
    }

    fun showContacts() {
        viewModelScope.launch { _navigateTo.emit(NavTarget.Contacts) }
    }

    // ── Event handlers ─────────────────────────────────────────

    private fun handleEngineEvent(event: WebRtcEngine.EngineEvent) {
        when (event) {
            is WebRtcEngine.EngineEvent.LocalSdpGenerated -> {
                if (event.isOffer) {
                    signalingClient.sendOffer(event.sdp)
                } else {
                    signalingClient.sendAnswer(event.sdp)
                }
            }
            is WebRtcEngine.EngineEvent.IceConnected -> {
                _callState.value = CallStateUi.CONNECTED
                verifyCallIdentity()
                startCallTimer()
            }
            is WebRtcEngine.EngineEvent.CallEnded -> {
                stopCallTimer()
                _callState.value = CallStateUi.ENDED
            }
            is WebRtcEngine.EngineEvent.Error -> {
                // Surface error — in production, show a snackbar
            }
            is WebRtcEngine.EngineEvent.IceCandidate -> {
                signalingClient.sendIce(event.sdpMid, event.sdpMLineIndex, event.sdp)
            }
            else -> {}
        }
    }

    /**
     * Establish E2EE verification for the connected call.
     *
     * Computes the Short Authentication String from both identity keys (stable,
     * identical on both ends) and surfaces it for manual comparison. A call with
     * a known contact (identity exchanged out-of-band via QR) is trusted; calls
     * without a known remote identity key (e.g. by-username) stay unverified.
     */
    private fun verifyCallIdentity() {
        val contact = _activeContact.value
        if (contact == null || contact.publicKeyB64.isEmpty()) {
            _e2eeFingerprint.value = null
            _e2eeVerified.value = false
            return
        }
        _e2eeFingerprint.value = identityManager.sas(contact.publicKeyB64)
        _e2eeVerified.value = true
    }

    private fun handleSignalingEvent(event: SignalingClient.SignalingEvent) {
        when (event) {
            is SignalingClient.SignalingEvent.Registered -> {
                _authLoading.value = false
                // register_user auto-authenticates; persist the credentials we
                // captured so the session survives an app restart.
                persistPendingCredentials()
            }
            is SignalingClient.SignalingEvent.LoggedIn -> {
                _authLoading.value = false
                persistPendingCredentials(event.username)
            }
            is SignalingClient.SignalingEvent.Created -> {
                // Server created a room for our outbound call — create WebRTC offer
                webRtcEngine.createOffer()
            }
            is SignalingClient.SignalingEvent.Incoming -> {
                // Incoming call — look up caller fingerprint in contacts
                _incomingRoomId = event.roomId
                _incomingFromFingerprint = event.fromFingerprint

                // Find contact by fingerprint
                val caller = contacts.value.find { contact ->
                    val pub = android.util.Base64.decode(contact.publicKeyB64, android.util.Base64.NO_WRAP)
                    identityManager.fingerprint(pub) == event.fromFingerprint
                }
                _activeContact.value = caller ?: Contact("Unknown", "")
                _callState.value = CallStateUi.RINGING
                _inCall.value = true
                _e2eeVerified.value = false
                startRing()
                viewModelScope.launch { _navigateTo.emit(NavTarget.Call(_activeContact.value?.name ?: "Unknown")) }
            }
            is SignalingClient.SignalingEvent.PeerJoined -> {
                // Remote peer joined — WebRTC can proceed
            }
            is SignalingClient.SignalingEvent.Offer -> {
                webRtcEngine.handleOffer(event.sdp)
            }
            is SignalingClient.SignalingEvent.Answer -> {
                webRtcEngine.handleAnswer(event.sdp)
            }
            is SignalingClient.SignalingEvent.IceCandidate -> {
                webRtcEngine.addIceCandidate(event.sdpMid, event.sdpMLineIndex, event.sdp)
            }
            is SignalingClient.SignalingEvent.Hangup -> {
                webRtcEngine.endCall()
                stopRing()
                _callState.value = CallStateUi.ENDED
                _incomingRoomId = null
            }
            is SignalingClient.SignalingEvent.Error -> {
                _authLoading.value = false
                _authError.value = event.message
                pendingCredentials = null
                stopRing()
            }
            is SignalingClient.SignalingEvent.SearchResults -> { /* handled by UI */ }
            is SignalingClient.SignalingEvent.Disconnected -> {
                _authLoading.value = false
                if (_authState.value?.isLoggedIn != true) {
                    _authError.value = "Server connection lost. Check the relay URL or try again."
                }
                scheduleReconnect()
            }
        }
    }

}
