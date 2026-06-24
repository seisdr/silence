import Foundation

struct Contact: Identifiable, Codable {
    let id: String // publicKeyBase64 is unique
    let name: String
    let publicKeyBase64: String

    var fingerprint: String {
        let pub = Data(base64Encoded: publicKeyBase64)!
        return Identity.fingerprint(of: pub)
    }
}
