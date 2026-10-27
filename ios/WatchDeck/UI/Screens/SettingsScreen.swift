import SwiftUI
import PulseCore

struct SettingsScreen: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var showResetConfirm = false
    @State private var relayManageServer: ServerConfig?
    @State private var document: DocumentRoute?

    var body: some View {
        NavigationStack {
            List {
                aboutSection
                legalSection
                if hasAdminServers {
                    securitySection
                }
                dataSection
            }
            .navigationTitle(Text("settings_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
            }
            .sheet(item: $relayManageServer) { server in
                RelayManageScreen(server: server)
            }
            .navigationDestination(for: DocumentRoute.self) { route in
                DocumentScreen(route: route)
            }
            .alert("settings_reset", isPresented: $showResetConfirm) {
                Button("settings_reset_confirm", role: .destructive) {
                    model.resetAll()
                    dismiss()
                }
                Button("settings_cancel", role: .cancel) {}
            } message: {
                Text("settings_reset_text")
            }
        }
    }

    private var hasAdminServers: Bool {
        model.servers.contains { !$0.isDemo && model.adminToken(of: $0) != nil }
    }

    // MARK: - Sections

    private var aboutSection: some View {
        Section {
            LabeledRow(title: "settings_version",
                       value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0")
            if let url = URL(string: "https://github.com/watchdeck-app/watchdeck") {
                Link(destination: url) {
                    HStack {
                        Text("settings_source")
                        Spacer()
                        Image(systemName: "arrow.up.right")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            if let url = URL(string: "https://watchdeck.app") {
                Link(destination: url) {
                    HStack {
                        Text("settings_site")
                        Spacer()
                        Image(systemName: "arrow.up.right")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            if let url = URL(string: "mailto:support@watchdeck.app") {
                Link(destination: url) {
                    Text("settings_contact")
                }
            }
        } header: {
            Text("settings_about")
        }
    }

    private var legalSection: some View {
        Section {
            NavigationLink(value: DocumentRoute.privacy) { Text("settings_privacy") }
            NavigationLink(value: DocumentRoute.terms) { Text("settings_terms") }
            NavigationLink(value: DocumentRoute.licenses) { Text("settings_licenses") }
        } header: {
            Text("settings_docs")
        }
    }

    private var securitySection: some View {
        Section {
            Toggle("settings_app_lock", isOn: $model.appLockEnabled)
            Button {
                if let server = model.servers.first(where: { !$0.isDemo && model.adminToken(of: $0) != nil }) {
                    relayManageServer = server
                }
            } label: {
                HStack {
                    Text("settings_relay_manage")
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .foregroundStyle(.primary)
        } header: {
            Text("settings_security")
        }
    }

    private var dataSection: some View {
        Section {
            Button(role: .destructive) {
                showResetConfirm = true
            } label: {
                Text("settings_reset")
            }
        } header: {
            Text("settings_data")
        }
    }
}

private struct LabeledRow: View {
    let title: LocalizedStringKey
    let value: String

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
        }
    }
}

enum DocumentRoute: String, Hashable {
    case privacy, terms, licenses
}

struct DocumentScreen: View {
    let route: DocumentRoute

    var body: some View {
        ScrollView {
            Group {
                switch route {
                case .privacy:
                    Text(fileText("privacy_policy"))
                case .terms:
                    Text(fileText("terms_of_service"))
                case .licenses:
                    licensesText
                }
            }
            .font(.footnote)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle(Text(titleKey))
        .navigationBarTitleDisplayMode(.inline)
    }

    private var titleKey: LocalizedStringKey {
        switch route {
        case .privacy: return "document_title_privacy"
        case .terms: return "document_title_terms"
        case .licenses: return "settings_licenses"
        }
    }

    private var licensesText: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("settings_licenses_intro")
            VStack(alignment: .leading, spacing: 8) {
                licenseRow(name: "Citadel + SwiftNIO SSH", detail: "MIT / Apache-2.0 — orlandos-nl, apple/swift-nio-ssh")
                licenseRow(name: "SwiftNIO", detail: "Apache-2.0 — apple/swift-nio")
                licenseRow(name: "swift-crypto", detail: "Apache-2.0 — apple/swift-crypto")
                licenseRow(name: "SwiftUI / Foundation / Security", detail: "© Apple Inc.")
            }
        }
    }

    private func licenseRow(name: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(name).font(.footnote.bold())
            Text(detail).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func fileText(_ name: String) -> String {
        guard let url = Bundle.main.url(forResource: name, withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return text
    }
}

// MARK: - Relay token management

/// Список токенов релея активного сервера + отзыв (X-Admin-Token).
struct RelayManageScreen: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let server: ServerConfig

    enum LoadState: Equatable {
        case loading
        case loaded([RelayTokenInfo])
        case error(String)
    }

    @State private var state: LoadState = .loading

    var body: some View {
        NavigationStack {
            Group {
                switch state {
                case .loading:
                    ProgressView()
                case .error(let message):
                    VStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle")
                            .foregroundStyle(PulseTheme.warn)
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding()
                case .loaded(let tokens):
                    if tokens.isEmpty {
                        Text("relay_empty")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else {
                        List(tokens) { token in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(token.label)
                                        .font(.subheadline)
                                    Text("\(token.role)\(token.revoked ? " · " + NSLocalizedString("relay_revoked_suffix", comment: "") : "")")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if !token.revoked {
                                    Button("relay_revoke") {
                                        revoke(token)
                                    }
                                    .font(.caption.bold())
                                    .buttonStyle(.bordered)
                                    .tint(.red)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(Text("relay_tokens_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
            }
            .task { await load() }
        }
    }

    private func load() async {
        state = .loading
        do {
            let admin = RelayAdmin(http: PinnedHTTP(certPem: server.certPem))
            let tokens = try await admin.tokens(
                adminToken: model.adminToken(of: server) ?? "",
                restBase: server.endpoint.restBase)
            state = .loaded(tokens)
        } catch {
            state = .error(AppModel.errorText(error))
        }
    }

    private func revoke(_ token: RelayTokenInfo) {
        Task {
            do {
                let admin = RelayAdmin(http: PinnedHTTP(certPem: server.certPem))
                try await admin.revoke(adminToken: model.adminToken(of: server) ?? "",
                                       restBase: server.endpoint.restBase, id: token.id)
                await load()
            } catch {
                state = .error(AppModel.errorText(error))
            }
        }
    }
}
