import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var sync: SyncClient
    @Environment(\.dismiss) private var dismiss

    @State private var syncEnabled = SyncSettings.syncEnabled
    @State private var host = SyncSettings.host
    @State private var port = String(SyncSettings.port)
    @State private var password = ""
    @State private var useTls = SyncSettings.useTls
    @State private var autoDownload = SyncSettings.autoDownload
    @State private var certFingerprint = SyncSettings.certFingerprint
    @State private var showScanner = false
    @State private var scanStatus = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button(action: { showScanner = true }) {
                        Label("Scan QR Code", systemImage: "qrcode.viewfinder")
                    }
                    if !scanStatus.isEmpty {
                        Text(scanStatus).font(.caption).foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Quick Setup")
                } footer: {
                    Text("Scan the QR code from the Stash server setup page to fill in connection details automatically.")
                }

                Section("Server") {
                    TextField("Host (e.g. 192.168.1.10)", text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("Port", text: $port).keyboardType(.numberPad)
                }
                Section("Password") {
                    SecureField(SyncSettings.passwordHash.isEmpty ? "Password" : "Change password", text: $password)
                    if !SyncSettings.passwordHash.isEmpty {
                        Text("A password is set. Leave blank to keep it.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                Section("Sync") {
                    Toggle("Enable sync", isOn: $syncEnabled)
                }
                Section("Files") {
                    Toggle("Download all files automatically", isOn: $autoDownload)
                }
                Section("Security") {
                    Toggle("Use TLS (wss://)", isOn: $useTls)
                    if useTls {
                        if certFingerprint.isEmpty {
                            Text("The server's certificate is pinned on first connect. Verify it matches the fingerprint the server logs.")
                                .font(.caption).foregroundStyle(.secondary)
                        } else {
                            Text("Pinned certificate:\n\(certFingerprint)")
                                .font(.caption.monospaced()).foregroundStyle(.secondary)
                            Button("Reset pinned certificate", role: .destructive) {
                                SyncSettings.certFingerprint = ""
                                certFingerprint = ""
                            }
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { save() } }
            }
            .sheet(isPresented: $showScanner) {
                QRScannerSheet { scanned in
                    applyQrResult(scanned)
                }
            }
        }
    }

    private func applyQrResult(_ text: String) {
        // Format: stash://HOST:PORT?tls=1
        guard let url = URL(string: text), url.scheme == "stash",
              let host = url.host, !host.isEmpty else {
            scanStatus = "Not a valid Stash QR code"
            return
        }
        self.host = host
        self.port = url.port.map(String.init) ?? "9876"
        let components = URLComponents(string: text)
        self.useTls = components?.queryItems?.first(where: { $0.name == "tls" })?.value == "1"
        scanStatus = "QR scanned — enter password and save"
    }

    private func save() {
        SyncSettings.syncEnabled = syncEnabled
        SyncSettings.host = host.trimmingCharacters(in: .whitespaces)
        SyncSettings.port = Int(port) ?? 9876
        if !password.isEmpty { SyncSettings.setPassword(password) }
        SyncSettings.useTls = useTls   // clears the pin when turned off (see SyncSettings)
        SyncSettings.autoDownload = autoDownload
        if syncEnabled { sync.restart() } else { sync.stop() }
        dismiss()
    }
}
