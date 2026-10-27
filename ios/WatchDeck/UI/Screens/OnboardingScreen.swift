import SwiftUI
import PulseCore

struct OnboardingScreen: View {
    @EnvironmentObject var model: AppModel

    enum AuthKind: String, CaseIterable {
        case password, key
    }

    // SSH-форма
    @State private var host = ""
    @State private var port = "22"
    @State private var username = "root"
    @State private var authKind: AuthKind = .password
    @State private var password = ""
    @State private var privateKey = ""
    @State private var keyPassphrase = ""

    // Ручное подключение (уже настроенный релей)
    @State private var showManual = false
    @State private var relayUrl = ""
    @State private var relayToken = ""
    @State private var manualError: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                brand

                SectionCard {
                    sshForm
                }

                if model.installState == .running || !model.installLog.isEmpty {
                    progressConsole
                }

                if case .failed(let message) = model.installState {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(PulseTheme.error)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                SectionCard {
                    manualForm
                }

                Button {
                    model.addDemo()
                } label: {
                    Text("or_try_demo")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                privacyNote
            }
            .padding(16)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .alert(
            NSLocalizedString("ssh_host_key_title", comment: ""),
            isPresented: Binding(
                get: { model.hostKeyPrompt != nil },
                set: { present in
                    if !present { model.resolveHostKey(false) }
                })
        ) {
            Button("ssh_host_key_confirm") { model.resolveHostKey(true) }
            Button("settings_cancel", role: .cancel) { model.resolveHostKey(false) }
        } message: {
            VStack {
                Text("ssh_host_key_text")
                Text(model.hostKeyPrompt?.fingerprint ?? "")
                    .font(.system(.caption, design: .monospaced))
            }
        }
    }

    // MARK: - Sections

    private var brand: some View {
        VStack(spacing: 10) {
            ZStack {
                Circle()
                    .fill(RadialGradient(colors: [PulseTheme.accent.opacity(0.35), PulseTheme.accent.opacity(0.05)],
                                         center: .center, startRadius: 4, endRadius: 52))
                Circle()
                    .stroke(PulseTheme.accent.opacity(0.7), lineWidth: 2)
                    .frame(width: 64, height: 64)
                Image(systemName: "chart.line.uptrend.xyaxis")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(PulseTheme.accent)
            }
            .frame(width: 104, height: 104)
            Text("WatchDeck")
                .font(.title.bold())
            Text("onboarding_tagline")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 24)
    }

    private var sshValid: Bool {
        !host.trimmingCharacters(in: .whitespaces).isEmpty
            && (authKind == .password ? !password.isEmpty : !privateKey.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    private var sshForm: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("ssh_section")
                .font(.subheadline.bold())
            TextField("ssh_host", text: $host)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            HStack(spacing: 8) {
                TextField("ssh_port", text: $port)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad)
                    .frame(width: 90)
                TextField("ssh_user", text: $username)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Picker("", selection: $authKind) {
                Text("auth_password").tag(AuthKind.password)
                Text("auth_key").tag(AuthKind.key)
            }
            .pickerStyle(.segmented)

            switch authKind {
            case .password:
                SecureField("ssh_password", text: $password)
                    .textFieldStyle(.roundedBorder)
            case .key:
                TextField("ssh_key", text: $privateKey, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(.caption, design: .monospaced))
                    .lineLimit(4...8)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("ssh_key_passphrase", text: $keyPassphrase)
                    .textFieldStyle(.roundedBorder)
            }

            Button {
                install()
            } label: {
                HStack {
                    if model.installState == .running {
                        ProgressView().tint(.white)
                    }
                    Text(model.installState == .running ? "install_running" : "install_button")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .padding(.vertical, 12)
                .background(sshValid ? PulseTheme.accent : Color.primary.opacity(0.12),
                            in: RoundedRectangle(cornerRadius: 12))
                .foregroundStyle(sshValid ? .white : .secondary)
            }
            .disabled(!sshValid || model.installState == .running)
        }
    }

    private var progressConsole: some View {
        SectionCard {
            Text("install_running")
                .font(.footnote.bold())
            VStack(alignment: .leading, spacing: 3) {
                let lines = model.installLog.suffix(8)
                if lines.isEmpty {
                    Text("…")
                        .foregroundStyle(.secondary)
                }
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(line)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(line.contains("error") || line.contains("fail")
                                         ? PulseTheme.error
                                         : line.contains("active") || line.contains("done")
                                         ? PulseTheme.success
                                         : PulseTheme.logText)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var manualForm: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation { showManual.toggle() }
            } label: {
                HStack {
                    Text("onboarding_relay_url")
                        .font(.subheadline.bold())
                        .foregroundStyle(.primary)
                    Spacer()
                    Image(systemName: showManual ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if showManual {
                Text("relay_hint")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("onboarding_relay_url_hint", text: $relayUrl)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                SecureField("onboarding_token", text: $relayToken)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(.footnote, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if let manualError {
                    Text(manualError)
                        .font(.caption)
                        .foregroundStyle(PulseTheme.error)
                }
                Button {
                    connectManual()
                } label: {
                    Text("onboarding_connect")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(PulseTheme.accent, in: RoundedRectangle(cornerRadius: 12))
                        .foregroundStyle(.white)
                }
            }
        }
    }

    private var privacyNote: some View {
        Text("onboarding_privacy")
            .font(.caption2)
            .foregroundStyle(.tertiary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 8)
            .padding(.bottom, 24)
    }

    // MARK: - Actions

    private func install() {
        guard let portNumber = Int(port), (1...65535).contains(portNumber) else { return }
        let trimmedKey = privateKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let auth: SSHAuth
        switch authKind {
        case .password:
            auth = .password(password)
        case .key:
            auth = .key(pem: trimmedKey, passphrase: keyPassphrase.isEmpty ? nil : keyPassphrase)
        }
        model.sshUserForInstall = username
        model.sshAuthKindForInstall = authKind == .key ? "key" : "password"
        model.sshSecretForInstall = authKind == .key ? trimmedKey : password
        model.sshKeyPassForInstall = keyPassphrase
        model.installServer(host: host.trimmingCharacters(in: .whitespaces),
                            port: portNumber,
                            username: username,
                            auth: auth)
    }

    private func connectManual() {
        let url = relayUrl.trimmingCharacters(in: .whitespaces)
        let token = relayToken.trimmingCharacters(in: .whitespaces)
        if !url.hasPrefix("wss://") && !url.hasPrefix("ws://") {
            manualError = NSLocalizedString("onboarding_error_url", comment: "")
            return
        }
        if token.isEmpty {
            manualError = NSLocalizedString("onboarding_error_token", comment: "")
            return
        }
        manualError = nil
        model.addManualServer(url: url, token: token)
    }
}
