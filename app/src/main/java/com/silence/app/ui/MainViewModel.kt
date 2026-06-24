package com.silence.app.ui

import android.app.Application
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
        signalingClient.login(username, password)
    }

    fun registerUser(username: String, password: String) {
        _authError.value = null
        _authLoading.value = true
        viewModelScope.launch {
            val fp = identity.value?.fingerprint ?: return@launch
            signalingClient.registerUser(username, password, fp)
        }
    }

    fun logout() {
        viewModelScope.launch {
            authStore.clearCredentials()
            signalingClient.disconnect()
            _authState.value = AuthState(null, null)
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
    }

    /** Call a user by username. Requires prior login. */
    fun callByUsername(username: String) {
        _activeContact.value = Contact(username, "")
        _callState.value = CallStateUi.DIALING
        _inCall.value = true
        _e2eeVerified.value = false
        _e2eeFingerprint.value = null
        signalingClient.callUser(username)
    }

    /** Accept an incoming call. */
    fun acceptCall() {
        signalingClient.accept(roomId = _incomingRoomId ?: return)
        _callState.value = CallStateUi.CONNECTED
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
                _e2eeFingerprint.value = event.fingerprint
                // If fingerprint matches stored contact, auto-verify
                val contact = _activeContact.value
                if (contact != null && event.fingerprint != null) {
                    val contactFp = identityManager.fingerprint(
                        android.util.Base64.decode(contact.publicKeyB64, android.util.Base64.NO_WRAP)
                    )
                    if (event.fingerprint.startsWith(contactFp, ignoreCase = true)) {
                        _e2eeVerified.value = true
                    }
                }
            }
            is WebRtcEngine.EngineEvent.CallEnded -> {
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

    private fun handleSignalingEvent(event: SignalingClient.SignalingEvent) {
        when (event) {
            is SignalingClient.SignalingEvent.Registered -> {
                _authLoading.value = false
                // Auto-login: save credentials if this was a register_user call
            }
            is SignalingClient.SignalingEvent.LoggedIn -> {
                _authLoading.value = false
                // Persist credentials
                viewModelScope.launch {
                    authStore.saveCredentials(
                        event.username,
                        _authState.value?.password ?: ""
                    )
                }
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
                _callState.value = CallStateUi.ENDED
                _incomingRoomId = null
            }
            is SignalingClient.SignalingEvent.Error -> {
                _authLoading.value = false
                _authError.value = event.message
            }
            is SignalingClient.SignalingEvent.SearchResults -> { /* handled by UI */ }
            is SignalingClient.SignalingEvent.Disconnected -> { /* reconnect handled elsewhere */ }
        }
    }
}
