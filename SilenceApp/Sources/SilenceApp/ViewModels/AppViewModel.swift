import Foundation
import SwiftUI

/// Full state machine — mirrors Android's `MainViewModel`.
/// Manages auth, signaling, WebRTC, contacts, and navigation.
@MainActor
class AppViewModel: ObservableObject {
    // MARK: - Services
    let identity = Identity.generate()
    let signaling = SignalingClient()
    let webrtc = WebRTCManager()

    // MARK: - Published state
    @Published var contacts: [Contact] = []
    @Published var callState: CallState = .idle
    @Published var activeContact: Contact?
    @Published var e2eeFingerprint: String?
    @Published var e2eeVerified = false
    @Published var muted = false
    @Published var speakerOn = false
    @Published var signalingUrl = "ws://10.0.2.2:8080/ws"

    // Auth
    @Published var authState: AuthState?
    @Published var authError: String?
    @Published var authLoading = false
    // Captured at login/register time so the real password can be persisted once
    // the server confirms success (mirrors the Android client).
    private var pendingUsername: String?
    private var pendingPassword: String?

    // Incoming
    private var incomingRoomId: String?
    private var incomingFingerprint: String?

    enum CallState { case idle, dialing, ringing, connected, ended }

    struct AuthState: Codable {
        let username: String
        let password: String
        var isLoggedIn: Bool { true }
    }

    init() {
        loadContacts()
        loadSettings()
        observeSignaling()
        observeWebRTC()
    }

    // MARK: - Service start

    func connect() {
        signaling.connect(url: signalingUrl)
    }

    func registerFingerprint() {
        signaling.register(fingerprint: identity.fingerprint, fcmToken: nil)
    }

    // MARK: - Auth

    func login(username: String, password: String) {
        authError = nil; authLoading = true
        pendingUsername = username; pendingPassword = password
        signaling.login(username: username, password: password)
    }
    func registerUser(username: String, password: String) {
        authError = nil; authLoading = true
        pendingUsername = username; pendingPassword = password
        signaling.registerUser(username: username, password: password, fingerprint: identity.fingerprint, fcmToken: nil)
    }

    func logout() {
        pendingUsername = nil; pendingPassword = nil
        authState = nil
        UserDefaults.standard.removeObject(forKey: "authState")
        signaling.disconnect()
    }

    // MARK: - Calls

    func callContact(_ contact: Contact) {
        activeContact = contact
        callState = .dialing
        e2eeVerified = false
        e2eeFingerprint = nil
        signaling.call(target: contact.fingerprint)
    }

    func acceptCall() {
        guard let room = incomingRoomId else { return }
        signaling.accept(roomId: room)
        callState = .connected
    }

    func hangup() {
        signaling.sendHangup()
        webrtc.endCall()
        callState = .ended
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            self.callState = .idle
        }
    }

    func toggleMute() {
        muted.toggle()
        webrtc.toggleMute(muted)
    }

    // MARK: - Contacts

    func addContact(name: String, publicKeyBase64: String) {
        let contact = Contact(id: publicKeyBase64, name: name, publicKeyBase64: publicKeyBase64)
        contacts.append(contact)
        saveContacts()
    }

    private func loadContacts() {
        guard let data = UserDefaults.standard.data(forKey: "contacts"),
              let saved = try? JSONDecoder().decode([Contact].self, from: data)
        else { return }
        contacts = saved
    }

    private func saveContacts() {
        if let data = try? JSONEncoder().encode(contacts) {
            UserDefaults.standard.set(data, forKey: "contacts")
        }
    }

    private func loadSettings() {
        if let url = UserDefaults.standard.string(forKey: "signalingUrl") {
            signalingUrl = url
        }
        if let data = UserDefaults.standard.data(forKey: "authState"),
           let saved = try? JSONDecoder().decode(AuthState.self, from: data) {
            authState = saved
        }
    }

    func saveSignalingUrl(_ url: String) {
        signalingUrl = url
        UserDefaults.standard.set(url, forKey: "signalingUrl")
    }

    func saveAuth() {
        guard let auth = authState else { return }
        if let data = try? JSONEncoder().encode(auth) {
            UserDefaults.standard.set(data, forKey: "authState")
        }
    }

    // MARK: - Event observers

    private func observeSignaling() {
        Task {
            for await event in signaling.events {
                switch event {
                case .registered:
                    authLoading = false
                    // register_user auto-authenticates; persist real credentials.
                    if let user = pendingUsername {
                        authState = AuthState(username: user, password: pendingPassword ?? "")
                        pendingUsername = nil; pendingPassword = nil
                        saveAuth()
                    }
                case .loggedIn(let u):
                    authLoading = false
                    authState = AuthState(username: u, password: pendingPassword ?? "")
                    pendingUsername = nil; pendingPassword = nil
                    saveAuth()
                case .created:
                    webrtc.createOffer()
                case .incoming(let room, let from):
                    incomingRoomId = room; incomingFingerprint = from
                    activeContact = contacts.first { $0.fingerprint == from }
                    callState = .ringing
                case .offer(let sdp): webrtc.handleOffer(sdp: sdp)
                case .answer(let sdp): webrtc.handleAnswer(sdp: sdp)
                case .ice(let mid, let idx, let cand): webrtc.addIceCandidate(sdpMid: mid, sdpMLineIndex: Int32(idx), sdp: cand)
                case .hangup:
                    webrtc.endCall(); callState = .ended
                case .error(let msg):
                    authLoading = false; authError = msg
                    pendingUsername = nil; pendingPassword = nil
                case .searchResults: break
                case .peerJoined, .disconnected: break
                }
            }
        }
    }

    private func observeWebRTC() {
        Task {
            for await event in webrtc.events {
                switch event {
                case .localSdpGenerated(let sdp, let isOffer):
                    if isOffer { signaling.sendOffer(sdp: sdp) }
                    else { signaling.sendAnswer(sdp: sdp) }
                case .iceCandidate(let mid, let idx, let sdp):
                    signaling.sendIce(sdpMid: mid, sdpMLineIndex: Int(idx), candidate: sdp)
                case .iceConnected:
                    callState = .connected
                    verifyCallIdentity()
                case .callEnded: callState = .ended
                case .error: break
                }
            }
        }
    }

    /// Establish E2EE verification for the connected call.
    /// Computes the SAS from both identity keys (stable, identical on both ends)
    /// for manual comparison. A call with a known contact (identity exchanged
    /// via QR) is trusted; calls without a known remote key stay unverified.
    private func verifyCallIdentity() {
        guard let contact = activeContact, !contact.publicKeyBase64.isEmpty else {
            e2eeFingerprint = nil
            e2eeVerified = false
            return
        }
        e2eeFingerprint = Identity.sas(local: identity.publicKeyBase64, remote: contact.publicKeyBase64)
        e2eeVerified = true
    }
}
