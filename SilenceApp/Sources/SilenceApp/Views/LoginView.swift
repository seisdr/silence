import SwiftUI

struct LoginView: View {
    @ObservedObject var vm: AppViewModel
    @Binding var screen: ContentView.Screen
    @State private var username = ""
    @State private var password = ""

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Text("Silence").font(.largeTitle).foregroundColor(.green)
            Text("Sign in").font(.title3).foregroundColor(.secondary)

            if let err = vm.authError {
                Text(err).font(.caption).foregroundColor(.red)
            }

            TextField("Username", text: $username)
                .textFieldStyle(.roundedBorder)
                .autocapitalization(.none)

            SecureField("Password", text: $password)
                .textFieldStyle(.roundedBorder)

            Button("Sign In") {
                vm.login(username: username, password: password)
            }
            .buttonStyle(.borderedProminent)
            .disabled(username.isEmpty || password.isEmpty || vm.authLoading)

            if vm.authLoading { ProgressView() }

            Button("Create account") { screen = .register }
            Spacer()
        }
        .padding()
    }
}

struct RegisterView: View {
    @ObservedObject var vm: AppViewModel
    @Binding var screen: ContentView.Screen
    @State private var username = ""
    @State private var password = ""
    @State private var confirm = ""

    var mismatch: Bool { !password.isEmpty && !confirm.isEmpty && password != confirm }

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Text("Silence").font(.largeTitle).foregroundColor(.green)
            Text("Create account").font(.title3)

            if let err = vm.authError {
                Text(err).font(.caption).foregroundColor(.red)
            }

            TextField("Username", text: $username)
                .textFieldStyle(.roundedBorder)
                .autocapitalization(.none)

            SecureField("Password", text: $password)
                .textFieldStyle(.roundedBorder)

            SecureField("Confirm password", text: $confirm)
                .textFieldStyle(.roundedBorder)

            if mismatch { Text("Passwords don't match").font(.caption).foregroundColor(.red) }

            Button("Create Account") {
                vm.registerUser(username: username, password: password)
            }
            .buttonStyle(.borderedProminent)
            .disabled(username.isEmpty || password.isEmpty || mismatch || vm.authLoading)

            if vm.authLoading { ProgressView() }

            Button("Already have an account? Sign in") { screen = .login }
            Spacer()
        }
        .padding()
    }
}
