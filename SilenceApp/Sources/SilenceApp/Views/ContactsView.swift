import SwiftUI

struct ContactsView: View {
    @ObservedObject var vm: AppViewModel
    @Binding var screen: ContentView.Screen

    var body: some View {
        NavigationStack {
            List {
                if vm.contacts.isEmpty {
                    Text("No contacts yet").foregroundColor(.secondary)
                }
                ForEach(vm.contacts) { contact in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(contact.name).font(.headline)
                            Text(contact.fingerprint).font(.caption).monospaced()
                        }
                        Spacer()
                        Button(action: { vm.callContact(contact) }) {
                            Image(systemName: "phone.fill").foregroundColor(.green)
                        }
                    }
                }
            }
            .navigationTitle("Silence")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { screen = .identity } label: {
                        Image(systemName: "qrcode")
                    }
                }
            }
        }
    }
}
