import Foundation
import MessagePack

/// WebSocket signaling client — same protocol as Android app.
/// Connects to the Go relay, exchanges MessagePack binary frames.
@MainActor
class SignalingClient: ObservableObject {
    private var task: URLSessionWebSocketTask?
    private var roomId: String?

    @Published var events: AsyncStream<SignalingEvent>
    private var eventContinuation: AsyncStream<SignalingEvent>.Continuation?

    enum SignalingEvent {
        case registered
        case loggedIn(username: String)
        case created(roomId: String)
        case incoming(roomId: String, from: String)
        case peerJoined(roomId: String)
        case offer(sdp: String)
        case answer(sdp: String)
        case ice(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        case hangup(roomId: String)
        case searchResults([String])
        case error(String)
        case disconnected
    }

    init() {
        // Create the event stream ONCE. Replacing `events` on connect (as the
        // old code did) orphaned observers that captured the stream at
        // AppViewModel init time, so signaling events were never delivered.
        var continuation: AsyncStream<SignalingEvent>.Continuation!
        events = AsyncStream { cont in continuation = cont }
        eventContinuation = continuation
    }

    func connect(url: String) {
        disconnect()
        guard let wsURL = URL(string: url) else { return }
        task = URLSession.shared.webSocketTask(with: wsURL)
        task?.resume()
        receiveLoop()
    }

    func register(fingerprint: String, fcmToken: String?) {
        SignalingMessage.register(fingerprint: fingerprint, fcmToken: fcmToken).send(using: task)
    }

    func registerUser(username: String, password: String, fingerprint: String, fcmToken: String?) {
        SignalingMessage.registerUser(username: username, password: password, fingerprint: fingerprint, fcmToken: fcmToken).send(using: task)
    }

    func login(username: String, password: String) {
        SignalingMessage.login(username: username, password: password).send(using: task)
    }

    func call(target: String) { SignalingMessage.call(target: target).send(using: task) }
    func callUser(username: String) { SignalingMessage.callUser(username: username).send(using: task) }

    func accept(roomId: String) {
        self.roomId = roomId
        SignalingMessage.accept(room: roomId).send(using: task)
    }

    func sendOffer(sdp: String) {
        SignalingMessage.offer(sdp: sdp, room: roomId ?? "").send(using: task)
    }

    func sendAnswer(sdp: String) {
        SignalingMessage.answer(sdp: sdp, room: roomId ?? "").send(using: task)
    }

    func sendIce(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        SignalingMessage.ice(sdpMid: sdpMid, sdpMLineIndex: sdpMLineIndex, candidate: candidate, room: roomId ?? "").send(using: task)
    }

    func sendHangup() {
        SignalingMessage.hangup(room: roomId ?? "").send(using: task)
    }

    func disconnect() {
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        roomId = nil
    }

    // MARK: - Internal

    private func receiveLoop() {
        task?.receive { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let message):
                switch message {
                case .data(let data): self.handle(data)
                case .string(let text): self.handle(Data(text.utf8))
                @unknown default: break
                }
                self.receiveLoop()
            case .failure:
                self.eventContinuation?.yield(.disconnected)
            }
        }
    }

    private func handle(_ data: Data) {
        guard let value = try? unpack(data),
              case .map(let dict) = value,
              let type = dict["type"]?.stringValue
        else { return }

        switch type {
        case "registered": eventContinuation?.yield(.registered)
        case "logged_in":
            eventContinuation?.yield(.loggedIn(username: dict["username"]?.stringValue ?? ""))
        case "created":
            roomId = dict["room"]?.stringValue
            if let r = roomId { eventContinuation?.yield(.created(roomId: r)) }
        case "incoming":
            roomId = dict["room"]?.stringValue
            if let r = roomId { eventContinuation?.yield(.incoming(roomId: r, from: dict["from"]?.stringValue ?? "")) }
        case "joined":
            eventContinuation?.yield(.peerJoined(roomId: dict["room"]?.stringValue ?? ""))
        case "offer":
            if let sdp = dict["sdp"]?.stringValue { eventContinuation?.yield(.offer(sdp: sdp)) }
        case "answer":
            if let sdp = dict["sdp"]?.stringValue { eventContinuation?.yield(.answer(sdp: sdp)) }
        case "ice":
            eventContinuation?.yield(.ice(
                sdpMid: dict["sdpMid"]?.stringValue ?? "",
                sdpMLineIndex: Int(dict["sdpMLineIndex"]?.intValue ?? 0),
                candidate: dict["candidate"]?.stringValue ?? ""
            ))
        case "hangup":
            eventContinuation?.yield(.hangup(roomId: dict["room"]?.stringValue ?? roomId ?? ""))
        case "error":
            eventContinuation?.yield(.error(dict["message"]?.stringValue ?? "unknown"))
        case "search_results":
            if let arr = dict["results"]?.arrayValue {
                eventContinuation?.yield(.searchResults(arr.compactMap { $0.stringValue }))
            }
        default: break
        }
    }
}

private extension SignalingMessage {
    func send(using task: URLSessionWebSocketTask?) {
        task?.send(.data(encode())) { _ in }
    }
}
