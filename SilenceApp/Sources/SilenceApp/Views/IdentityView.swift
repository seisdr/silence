import SwiftUI
import CoreImage.CIFilterBuiltins

struct IdentityView: View {
    @ObservedObject var vm: AppViewModel
    @Binding var screen: ContentView.Screen
    @State private var urlText = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text("Silence").font(.largeTitle).foregroundColor(.green)
                Text("Your Identity").font(.title3).foregroundColor(.secondary)

                // QR code
                if let qrImage = generateQR(from: vm.identity.qrPayload) {
                    Image(uiImage: qrImage)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 280, height: 280)
                        .padding()
                        .background(Color.white)
                        .cornerRadius(16)
                }

                Text("Fingerprint").font(.caption).foregroundColor(.secondary)
                Text(vm.identity.fingerprint)
                    .font(.title2.monospaced())
                    .textSelection(.enabled)

                // Signaling URL
                Text("Signaling Server").font(.caption).foregroundColor(.secondary)
                TextField("WebSocket URL", text: $urlText)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.none)
                    .onAppear { urlText = vm.signalingUrl }
                    .onChange(of: urlText) { vm.saveSignalingUrl($0) }

                Button("Continue") {
                    vm.registerFingerprint()
                    screen = .contacts
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()
        }
    }

    private func generateQR(from string: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let context = CIContext()
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
