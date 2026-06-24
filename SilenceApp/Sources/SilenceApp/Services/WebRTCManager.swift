import Foundation
import WebRTC

/// WebRTC engine — same role as Android's `WebRtcEngine`.
/// Creates PeerConnections, manages audio, verifies DTLS fingerprint.
@MainActor
class WebRTCManager: NSObject, ObservableObject {
    private var peerConnectionFactory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?
    private var audioTrack: RTCAudioTrack?
    private var remoteFingerprint: String?

    @Published var events: AsyncStream<WebRTCEvent> = AsyncStream { _ in }
    private var eventContinuation: AsyncStream<WebRTCEvent>.Continuation?

    enum WebRTCEvent {
        case localSdpGenerated(sdp: String, isOffer: Bool)
        case iceCandidate(sdpMid: String, sdpMLineIndex: Int32, sdp: String)
        case iceConnected(fingerprint: String?)
        case callEnded
        case error(String)
    }

    override init() {
        super.init()
        RTCPeerConnectionFactory.initialize()
        peerConnectionFactory = RTCPeerConnectionFactory()

        let constraints = RTCMediaConstraints(mandatoryConstraints: [
            "googEchoCancellation": "true",
            "googAutoGainControl": "true",
            "googNoiseSuppression": "true",
        ], optionalConstraints: nil)

        let audioSource = peerConnectionFactory!.audioSource(with: constraints)
        audioTrack = peerConnectionFactory!.audioTrack(with: audioSource, trackId: "audio")
    }

    func createOffer() {
        let pc = createPeerConnection()
        let constraints = RTCMediaConstraints(mandatoryConstraints: [
            "OfferToReceiveAudio": "true",
            "OfferToReceiveVideo": "false"
        ], optionalConstraints: nil)

        pc?.offer(for: constraints) { [weak self] sdp, error in
            guard let self = self, let sdp = sdp else { return }
            let tuned = self.tuneSdp(sdp.sdp)
            let tunedSdp = RTCSessionDescription(type: sdp.type, sdp: tuned)
            pc?.setLocalDescription(tunedSdp) { _ in }
            self.eventContinuation?.yield(.localSdpGenerated(sdp: tuned, isOffer: true))
        }
    }

    func handleOffer(sdp: String) {
        let pc = createPeerConnection()
        remoteFingerprint = parseFingerprint(sdp)
        let offer = RTCSessionDescription(type: .offer, sdp: sdp)
        pc?.setRemoteDescription(offer) { [weak self] _ in
            guard let self = self else { return }
            let constraints = RTCMediaConstraints(mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": "false"
            ], optionalConstraints: nil)
            pc?.answer(for: constraints) { sdp, error in
                guard let sdp = sdp else { return }
                let tuned = self.tuneSdp(sdp.sdp)
                let tunedSdp = RTCSessionDescription(type: sdp.type, sdp: tuned)
                pc?.setLocalDescription(tunedSdp) { _ in }
                self.eventContinuation?.yield(.localSdpGenerated(sdp: tuned, isOffer: false))
            }
        }
    }

    func handleAnswer(sdp: String) {
        remoteFingerprint = parseFingerprint(sdp)
        let answer = RTCSessionDescription(type: .answer, sdp: sdp)
        peerConnection?.setRemoteDescription(answer) { _ in }
    }

    func addIceCandidate(sdpMid: String, sdpMLineIndex: Int32, sdp: String) {
        let candidate = RTCIceCandidate(sdp: sdp, sdpMLineIndex: sdpMLineIndex, sdpMid: sdpMid)
        peerConnection?.add(candidate)
    }

    func endCall() {
        peerConnection?.close()
        peerConnection = nil
        remoteFingerprint = nil
        eventContinuation?.yield(.callEnded)
    }

    func toggleMute(_ muted: Bool) {
        audioTrack?.isEnabled = !muted
    }

    // MARK: - Internal

    private func createPeerConnection() -> RTCPeerConnection? {
        if peerConnection != nil { return peerConnection }
        let config = RTCConfiguration()
        config.iceServers = [RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])]
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually

        var localContinuation: AsyncStream<WebRTCEvent>.Continuation!
        events = AsyncStream { cont in localContinuation = cont }
        eventContinuation = localContinuation

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection = peerConnectionFactory?.peerConnection(
            with: config, constraints: constraints, delegate: self
        )
        if let track = audioTrack {
            peerConnection?.add(track, streamIds: ["stream"])
        }
        return peerConnection
    }

    static func parseFingerprint(_ sdp: String) -> String? {
        for line in sdp.components(separatedBy: "\n") {
            if line.hasPrefix("a=fingerprint:sha-256") {
                return line.components(separatedBy: "sha-256 ").last?
                    .trimmingCharacters(in: .whitespaces)
                    .replacingOccurrences(of: ":", with: "")
                    .lowercased()
            }
        }
        return nil
    }

    static func tuneSdp(_ sdp: String) -> String {
        var lines = sdp.components(separatedBy: "\r\n")
        var opusPt: String?
        for line in lines {
            if line.contains("opus/48000/2"), let pt = line.components(separatedBy: " ").first {
                opusPt = pt.replacingOccurrences(of: "a=rtpmap:", with: "")
                break
            }
        }
        guard let pt = opusPt else { return sdp }
        let fmtpLine = "a=fmtp:\(pt) minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=24000;stereo=0"
        return lines.map { $0.hasPrefix("a=fmtp:\(pt)") ? fmtpLine : $0 }.joined(separator: "\r\n") + "\r\n"
    }
}

// MARK: - RTCPeerConnectionDelegate

extension WebRTCManager: RTCPeerConnectionDelegate {
    func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        eventContinuation?.yield(.iceCandidate(
            sdpMid: candidate.sdpMid ?? "",
            sdpMLineIndex: candidate.sdpMLineIndex,
            sdp: candidate.sdp
        ))
    }

    func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        if newState == .connected {
            eventContinuation?.yield(.iceConnected(fingerprint: remoteFingerprint))
        }
    }

    func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
