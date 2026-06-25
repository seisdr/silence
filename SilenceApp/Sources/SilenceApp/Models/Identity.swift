import Foundation
import Tink

/// Long-term identity: X25519 keypair + SHA-256 fingerprint.
/// Same identity model as the Android `IdentityManager`.
struct Identity {
    let publicKey: Data   // 32 bytes
    let privateKey: Data  // 32 bytes
    let fingerprint: String // 16 hex chars (first 8 bytes of SHA-256)

    var publicKeyBase64: String { publicKey.base64EncodedString() }
    var qrPayload: String { "silence://\(publicKeyBase64)" }

    static func generate() -> Identity {
        // Generate X25519 keypair via Tink
        let privateKey = try! Tink.X25519.generatePrivateKey()
        let publicKey = privateKey.publicKey()

        let pubData = publicKey.x509EncodedKey!
        let privData = privateKey.keyData!

        let fp = Self.fingerprint(of: pubData)
        return Identity(publicKey: pubData, privateKey: privData, fingerprint: fp)
    }

    static func fingerprint(of pubKey: Data) -> String {
        let hash = SHA256.hash(data: pubKey)
        return hash.prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    /// Symmetric Short Authentication String: SHA-256 over the sorted
    /// concatenation of both identity public keys (base64). Both ends compute
    /// the identical string, so it is valid for manual comparison. Stable across
    /// calls, unlike the per-call DTLS-SRTP certificate fingerprint.
    static func sas(local: String, remote: String) -> String {
        let (a, b) = local <= remote ? (local, remote) : (remote, local)
        let hash = SHA256.hash(data: Data((a + b).utf8))
        return hash.prefix(16).map { String(format: "%02x", $0) }.joined() // 128-bit
    }
}
