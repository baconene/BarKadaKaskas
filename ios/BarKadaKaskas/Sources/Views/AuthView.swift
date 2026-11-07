import SwiftUI

struct AuthView: View {
    @EnvironmentObject var store: AppStore
    @State private var registering = false
    @State private var name = ""
    @State private var email = ""
    @State private var password = ""
    @State private var confirmation = ""
    @State private var server = ""
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Image(systemName: "leaf.fill").font(.largeTitle).foregroundStyle(Theme.forest)
                        Text("BarKadaKaskas").font(.largeTitle.bold())
                        Text("Good trips. Great company. Fair shares.").foregroundStyle(.secondary)
                    }.padding(.vertical, 16)
                }.listRowBackground(Color.clear)
                Section(registering ? "Join the barkada" : "Welcome back") {
                    if registering { TextField("Your name", text: $name).textContentType(.name) }
                    TextField("Email", text: $email).keyboardType(.emailAddress).textContentType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Password", text: $password).textContentType(registering ? .newPassword : .password)
                    if registering { SecureField("Confirm password", text: $confirmation).textContentType(.newPassword) }
                    Button(registering ? "Create account" : "Log in") {
                        Task {
                            if registering && password != confirmation { store.error = "Passwords do not match."; return }
                            await store.perform {
                                try store.repository.configure(serverURL: server)
                                store.user = registering
                                    ? try await store.repository.register(name: name, email: email, password: password)
                                    : try await store.repository.login(email: email, password: password)
                                password = ""; confirmation = ""
                            }
                        }
                    }.disabled(store.busy || email.isEmpty || password.isEmpty || (registering && name.isEmpty))
                }
                Section {
                    Button(registering ? "Already have an account? Log in" : "New here? Create an account") { registering.toggle() }
                }
                Section("Connect to your server") {
                    TextField("https://your-server.com", text: $server).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Text("Use the same BarKadaKaskas server as your friends. For development, the server must be reachable from this device.").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .scrollContentBackground(.hidden).background(Theme.canvas)
            .onAppear { server = store.repository.serverURL }
        }
    }
}
