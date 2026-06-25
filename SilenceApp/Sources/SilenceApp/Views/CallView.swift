import SwiftUI

struct CallView: View {
    @ObservedObject var vm: AppViewModel
    @Binding var screen: ContentView.Screen

    var body: some View {
        ZStack {
            bgColor.ignoresSafeArea()

            VStack(spacing: 24) {
                Spacer()

                // Avatar
                Text(vm.activeContact?.name.prefix(1).uppercased() ?? "?")
                    .font(.largeTitle)
                    .frame(width: 80, height: 80)
                    .background(Color.white.opacity(0.15))
                    .clipShape(Circle())

                Text(vm.activeContact?.name ?? "Unknown")
                    .font(.title2).foregroundColor(.white)

                // Status
                Text(statusText).foregroundColor(.white.opacity(0.85))

                // E2EE fingerprint
                if let fp = vm.e2eeFingerprint, vm.callState == .connected {
                    VStack {
                        Text("Security code").font(.caption).foregroundColor(.white.opacity(0.7))
                        Text(fp).font(.body.monospaced()).foregroundColor(.white)
                    }
                    .padding()
                    .background(Color.white.opacity(0.1))
                    .cornerRadius(12)
                }

                if vm.e2eeVerified {
                    Label("Verified end-to-end encrypted", systemImage: "checkmark.shield.fill")
                        .foregroundColor(.green)
                }

                Spacer()

                // Controls
                HStack(spacing: 40) {
                    Button(action: vm.toggleMute) {
                        VStack {
                            Image(systemName: vm.muted ? "mic.slash.fill" : "mic.fill")
                                .font(.title2)
                            Text(vm.muted ? "Unmute" : "Mute").font(.caption)
                        }
                        .foregroundColor(.white)
                    }

                    Button(action: vm.hangup) {
                        Image(systemName: "phone.down.fill")
                            .font(.title)
                            .foregroundColor(.white)
                            .padding()
                            .background(Color.red)
                            .clipShape(Circle())
                    }

                    Button(action: {}) {
                        VStack {
                            Image(systemName: "speaker.wave.2.fill")
                                .font(.title2)
                            Text("Speaker").font(.caption)
                        }
                        .foregroundColor(.white)
                    }
                }

                Spacer().frame(height: 40)
            }
        }
    }

    private var bgColor: Color {
        if vm.e2eeVerified { return Color.green.opacity(0.8) }
        if vm.callState == .connected { return Color.blue.opacity(0.8) }
        return Color(.darkGray)
    }

    private var statusText: String {
        switch vm.callState {
        case .dialing: return "Calling…"
        case .ringing: return "Incoming call"
        case .ended: return "Call ended"
        case .connected where vm.e2eeVerified: return "E2E Encrypted · Verified"
        case .connected: return "Encrypted · Identity unknown"
        default: return ""
        }
    }
}
