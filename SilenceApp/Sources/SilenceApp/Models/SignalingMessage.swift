import Foundation
import MessagePack

/// Signaling protocol: same message types as Android, encoded as MessagePack.
/// Uses `pack()` / `unpack()` and `MessagePackValue` from MessagePack.swift.
enum SignalingMessage {
    case register(fingerprint: String, fcmToken: String?)
    case registerUser(username: String, password: String, fingerprint: String, fcmToken: String?)
    case login(username: String, password: String)
    case call(target: String)
    case callUser(username: String)
    case accept(room: String)
    case offer(sdp: String, room: String)
    case answer(sdp: String, room: String)
    case ice(sdpMid: String, sdpMLineIndex: Int, candidate: String, room: String)
    case hangup(room: String)
    case searchUser(query: String)

    /// Encode as MessagePack binary (same format as the Android client sends).
    func encode() -> Data {
        let map: MessagePackValue
        switch self {
        case .register(let fp, let tok):
            var dict: [MessagePackValue: MessagePackValue] = [
                "type": "register",
                "fingerprint": .string(fp),
            ]
            if let t = tok { dict["fcm_token"] = .string(t) }
            map = .map(dict)

        case .registerUser(let u, let p, let fp, let tok):
            var dict: [MessagePackValue: MessagePackValue] = [
                "type": "register_user",
                "username": .string(u),
                "password": .string(p),
                "fingerprint": .string(fp),
            ]
            if let t = tok { dict["fcm_token"] = .string(t) }
            map = .map(dict)

        case .login(let u, let p):
            map = .map(["type": "login", "username": .string(u), "password": .string(p)])

        case .call(let target):
            map = .map(["type": "call", "target": .string(target)])

        case .callUser(let u):
            map = .map(["type": "call_user", "username": .string(u)])

        case .accept(let room):
            map = .map(["type": "accept", "room": .string(room)])

        case .offer(let sdp, let room):
            map = .map(["type": "offer", "sdp": .string(sdp), "room": .string(room)])

        case .answer(let sdp, let room):
            map = .map(["type": "answer", "sdp": .string(sdp), "room": .string(room)])

        case .ice(let mid, let idx, let cand, let room):
            map = .map([
                "type": "ice",
                "sdpMid": .string(mid),
                "sdpMLineIndex": .int(Int64(idx)),
                "candidate": .string(cand),
                "room": .string(room),
            ])

        case .hangup(let room):
            map = .map(["type": "hangup", "room": .string(room)])

        case .searchUser(let query):
            map = .map(["type": "search_user", "query": .string(query)])
        }
        return MessagePack.pack(map)
    }
}
