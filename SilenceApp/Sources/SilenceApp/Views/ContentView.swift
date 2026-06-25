import SwiftUI

/// Root view — routes based on auth state.
struct ContentView: View {
    @StateObject var vm = AppViewModel()
    @State private var screen: Screen = .login

    enum Screen { case login, register, identity, contacts, call }

    var body: some View {
        Group {
            if vm.authState == nil {
                // Not authenticated
                if screen == .register {
                    RegisterView(vm: vm, screen: $screen)
                } else {
                    LoginView(vm: vm, screen: $screen)
                }
            } else if screen == .identity {
                IdentityView(vm: vm, screen: $screen)
            } else if screen == .call {
                CallView(vm: vm, screen: $screen)
            } else {
                ContactsView(vm: vm, screen: $screen)
            }
        }
        .onAppear { vm.connect() }
    }
}
