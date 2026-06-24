package com.silence.app.webrtc

import android.content.Context
import android.media.AudioManager
import com.silence.app.identity.IdentityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var remoteFingerprint: String? = null
    private var engineScope: CoroutineScope? = null

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    sealed interface EngineEvent {
        data class LocalSdpGenerated(val sdp: String, val isOffer: Boolean) : EngineEvent
        data class IceCandidate(val sdpMid: String, val sdpMLineIndex: Int, val sdp: String) : EngineEvent
        data class IceConnected(val fingerprint: String?) : EngineEvent
        data object CallEnded : EngineEvent
        data class Error(val message: String) : EngineEvent
    }

    fun initialize() {
        // Must be set before creating the audio module — Android requires
        // MODE_IN_COMMUNICATION for VoIP calls to route audio correctly.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val audioModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
        val ac = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        audioSource = peerConnectionFactory?.createAudioSource(ac)
        audioTrack = peerConnectionFactory?.createAudioTrack("audio", audioSource)
    }

    fun destroy() {
        endCall()
        audioTrack?.dispose()
        audioSource?.dispose()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
    }

    fun createOffer() {
        val pc = createPeerConnection() ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sd: SessionDescription?) {
                sd ?: return
                val tuned = SessionDescription(sd.type, tuneSdp(sd.description))
                pc.setLocalDescription(noopSdpObserver, tuned)
                _events.tryEmit(EngineEvent.LocalSdpGenerated(tuned.description, true))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { _events.tryEmit(EngineEvent.Error("offer: $err")) }
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    fun handleOffer(sdp: String) {
        val pc = createPeerConnection() ?: return
        remoteFingerprint = parseFingerprint(sdp)
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(noopSdpObserver, offer)

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sd: SessionDescription?) {
                sd ?: return
                val tuned = SessionDescription(sd.type, tuneSdp(sd.description))
                pc.setLocalDescription(noopSdpObserver, tuned)
                _events.tryEmit(EngineEvent.LocalSdpGenerated(tuned.description, false))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { _events.tryEmit(EngineEvent.Error("answer: $err")) }
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    fun handleAnswer(sdp: String) {
        remoteFingerprint = parseFingerprint(sdp)
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(noopSdpObserver, answer)
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
    }

    fun endCall() {
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        remoteFingerprint = null
        engineScope?.cancel()
        engineScope = null
        _events.tryEmit(EngineEvent.CallEnded)
    }

    fun toggleMute(muted: Boolean) { audioTrack?.setEnabled(!muted) }

    fun toggleSpeaker(speakerOn: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (speakerOn) {
                val sp = am.availableCommunicationDevices
                    .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (sp != null) am.setCommunicationDevice(sp)
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = speakerOn
        }
    }

    private fun createPeerConnection(): PeerConnection? {
        if (peerConnection != null) return peerConnection
        if (peerConnectionFactory == null) return null
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                _events.tryEmit(EngineEvent.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.CONNECTED) verifyIdentity()
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onIceCandidateError(p0: IceCandidateErrorEvent?) {}
            override fun onStandardizedIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onConnectionChange(p0: PeerConnection.PeerConnectionState?) {}
        })
        audioTrack?.let { peerConnection?.addTrack(it, listOf()) }
        return peerConnection
    }

    private fun verifyIdentity() {
        _events.tryEmit(EngineEvent.IceConnected(remoteFingerprint))
    }

    companion object {
        val noopSdpObserver = object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }

        fun parseFingerprint(sdp: String): String? = sdp.lineSequence()
            .firstOrNull { it.startsWith("a=fingerprint:sha-256") }
            ?.substringAfter("sha-256 ")?.trim()?.replace(":", "")?.lowercase()

        fun tuneSdp(sdp: String): String {
            val opusPt = sdp.lineSequence()
                .firstOrNull { it.contains("opus/48000/2") }
                ?.substringBefore(" ")?.removePrefix("a=rtpmap:") ?: return sdp
            val fmtp = "a=fmtp:$opusPt minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=24000;stereo=0"
            return sdp.lineSequence().joinToString("\r\n") { line ->
                if (line.startsWith("a=fmtp:$opusPt")) fmtp else line
            } + "\r\n"
        }
    }
}
