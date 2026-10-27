import SwiftUI
import PulseCore

/// Стриминг логов контейнера (темный терминальный список, фильтр All/Issues).
struct LogsView: View {
    @EnvironmentObject var model: AppModel

    enum Filter: String, CaseIterable {
        case all, issues
    }

    @State private var filter: Filter = .all
    @State private var autoScroll = true

    private var filtered: [LogLineUi] {
        guard filter == .issues else { return model.logLines }
        return model.logLines.filter { $0.isStderr || $0.text.contains("ERROR") || $0.text.contains("WARN") }
    }

    var body: some View {
        VStack(spacing: 8) {
            Picker("", selection: $filter) {
                Text("logs_filter_all").tag(Filter.all)
                Text("logs_filter_issues").tag(Filter.issues)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)

            if filtered.isEmpty {
                Spacer()
                Text("dashboard_loading")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Spacer()
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            ForEach(filtered) { line in
                                LogRow(line: line)
                                    .id(line.id)
                            }
                        }
                        .padding(.vertical, 8)
                    }
                    .background(PulseTheme.terminalBg,
                                in: RoundedRectangle(cornerRadius: 12))
                    .padding(.horizontal, 16)
                    .onChange(of: model.logLines.count) { _, _ in
                        guard autoScroll, let last = filtered.last else { return }
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                    .onTapGesture { autoScroll = true }
                }
                Spacer(minLength: 0)
            }
        }
        .task {
            // автостарт стрима первого запущенного контейнера — как в Android
            if let container = model.firstRunningContainer(),
               let agentId = model.selectedAgent(serverId: model.activeId ?? "") {
                model.requestLogs(agentId: agentId, containerId: container.id)
            }
        }
    }
}

/// SSH-терминал: прямое соединение с сервером (релею не нужен).
struct TerminalView: View {
    @EnvironmentObject var model: AppModel
    let server: ServerConfig

    @State private var output = ""
    @State private var command = ""
    @State private var statusText: String?
    @State private var terminal: SSHTerminal?
    @State private var connectTask: Task<Void, Never>?
    @FocusState private var inputFocused: Bool

    private let quickCommands = ["docker ps", "docker stats --no-stream", "df -h", "free -m", "uptime"]
    private static let maxOutputChars = 20_000

    var body: some View {
        VStack(spacing: 8) {
            ScrollViewReader { proxy in
                ScrollView {
                    Text(output.isEmpty ? placeholderText : output)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(output.isEmpty ? Color.white.opacity(0.35) : PulseTheme.terminalFg)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                        .textSelection(.enabled)
                    Color.clear.frame(height: 1).id("bottom")
                }
                .background(PulseTheme.terminalBg, in: RoundedRectangle(cornerRadius: 12))
                .onChange(of: output.count) { _, _ in
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
            }
            .padding(.horizontal, 16)

            if let statusText {
                Text(statusText)
                    .font(.caption)
                    .foregroundStyle(PulseTheme.error)
                    .padding(.horizontal, 16)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(quickCommands, id: \.self) { cmd in
                        Button(cmd) {
                            send(cmd)
                        }
                        .font(.system(.caption, design: .monospaced))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color.primary.opacity(0.07), in: Capsule())
                        .disabled(terminal == nil)
                    }
                }
                .padding(.horizontal, 16)
            }

            HStack(spacing: 8) {
                TextField("terminal_placeholder", text: $command)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(.footnote, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($inputFocused)
                    .onSubmit { send(command) }
                Button {
                    send(command)
                } label: {
                    Image(systemName: "paperplane.fill")
                }
                .disabled(command.trimmingCharacters(in: .whitespaces).isEmpty || terminal == nil)
                .accessibilityLabel(Text("terminal_send"))
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
        .onAppear(perform: connect)
        .onDisappear(perform: disconnect)
    }

    private var placeholderText: String {
        if server.isDemo || server.sshSecret == nil {
            return NSLocalizedString("terminal_unavailable", comment: "")
        }
        return NSLocalizedString("terminal_connecting", comment: "")
    }

    private func connect() {
        guard !server.isDemo, let auth = server.sshAuth else { return }
        let endpoint = server.endpoint
        let host = endpoint.hostForLabel
        let port = server.sshPort
        let user = server.sshUser
        let fp = server.sshFp
        let term = SSHTerminal()
        terminal = term
        term.onOutput = { text in
            Task { @MainActor in
                self.output.append(text)
                if self.output.count > Self.maxOutputChars {
                    self.output = String(self.output.suffix(Self.maxOutputChars))
                }
            }
        }
        term.onError = { message in
            Task { @MainActor in
                self.statusText = String(
                    format: NSLocalizedString("terminal_connect_error", comment: ""), message)
            }
        }
        connectTask = Task { @MainActor in
            do {
                try await term.connect(host: host, port: port, username: user,
                                       auth: auth, pinnedFingerprint: fp)
                self.statusText = nil
            } catch {
                self.statusText = String(
                    format: NSLocalizedString("terminal_connect_error", comment: ""),
                    AppModel.errorText(error))
            }
        }
    }

    private func disconnect() {
        terminal?.shutdown()
        terminal = nil
        connectTask?.cancel()
    }

    private func send(_ raw: String) {
        let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !line.isEmpty, let terminal else { return }
        output.append("$ \(line)\n")
        terminal.send(line: line)
        command = ""
        inputFocused = true
    }
}
