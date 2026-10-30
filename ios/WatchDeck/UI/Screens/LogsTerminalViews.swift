import SwiftUI
import PulseCore
import UniformTypeIdentifiers

struct LogExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws { text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self) }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

struct LogsView: View {
    @EnvironmentObject var model: AppModel
    let serverId: String
    var initialContainerId: String? = nil
    @State private var selectedId = ""
    @State private var filter = "ALL"
    @State private var search = ""
    @State private var paused = false
    @State private var frozen: [LogLineUi] = []
    @State private var exporting = false
    @State private var exportText = ""
    @State private var exportError: String?
    private var agent: String? { model.selectedAgent(serverId: serverId) }
    private var containers: [Container] { model.containers(serverId: serverId, agentId: agent) }
    private var filtered: [LogLineUi] {
        (paused ? frozen : model.logLines).filter { line in
            return (filter == "ALL" || line.level == filter)
                && (search.isEmpty || line.text.localizedCaseInsensitiveContains(search))
        }
    }
    private var visibleText: String { filtered.map { "\(PulseFormat.clockTime($0.tsUnix)) \($0.text)" }.joined(separator: "\n") }
    var body: some View {
        VStack(spacing: 8) {
            Menu {
                ForEach(containers) { c in Button(c.name) { selectedId = c.id } }
            } label: {
                HStack { Text(containers.first(where: { $0.id == selectedId })?.name ?? NSLocalizedString("logs_choose", comment: "")); Spacer(); Image(systemName: "chevron.down") }
            }.padding(.horizontal, 16)
            TextField("logs_search", text: $search).textFieldStyle(.roundedBorder).padding(.horizontal, 16)
            Picker("logs_filter_all", selection: $filter) {
                Text("logs_filter_all").tag("ALL"); Text("ERROR").tag("ERROR"); Text("WARN").tag("WARN")
            }.pickerStyle(.segmented).padding(.horizontal, 16)
            HStack(spacing: 16) {
                    Button(paused ? "logs_resume" : "logs_pause") { if !paused { frozen = model.logLines }; paused.toggle() }
                    Button("logs_copy") { UIPasteboard.general.string = visibleText }.disabled(filtered.isEmpty)
                    Button("logs_export") { exportText = visibleText; exporting = true }.disabled(filtered.isEmpty)
            }.font(.caption).padding(.horizontal, 16)
            Text(String(format: NSLocalizedString("logs_buffer", comment: ""), filtered.count)).font(.caption2).foregroundStyle(.secondary)
            if let exportError { Text(exportError).font(.caption).foregroundStyle(PulseTheme.error) }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        if filtered.isEmpty { Text("logs_empty").font(.footnote).foregroundStyle(PulseTheme.logText).padding() }
                        ForEach(filtered) { line in LogRow(line: line).id(line.id) }
                    }.padding(.vertical, 8)
                }
                .background(PulseTheme.terminalBg, in: RoundedRectangle(cornerRadius: 16))
                .onChange(of: model.logLines.last?.id) { _, _ in
                    if !paused, let last = filtered.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }.padding(.horizontal, 12)
        }
        .fileExporter(isPresented: $exporting, document: LogExportDocument(text: exportText), contentType: .plainText, defaultFilename: "watchdeck-logs") { result in
            if case .failure(let error) = result { exportError = error.localizedDescription }
        }
        .task(id: containers.map(\.id).joined(separator: ":")) {
            if selectedId.isEmpty { selectedId = initialContainerId ?? containers.first(where: { $0.state == .running })?.id ?? containers.first?.id ?? "" }
        }
        .task(id: "\(agent ?? ""):\(selectedId)") {
            paused = false
            if let agent, !selectedId.isEmpty { model.requestLogs(serverId: serverId, agentId: agent, containerId: selectedId) }
        }
        .onDisappear { model.stopLogs() }
    }
}

/// SSH-терминал: прямое соединение с сервером (релею не нужен).
struct TerminalView: View {
    @EnvironmentObject var model: AppModel
    let server: ServerConfig

    @State private var output = ""
    @State private var command = ""
    @State private var pendingCommand: String?
    @AppStorage("diagnostic_commands") private var savedCommandsJSON = "[]"
    @State private var statusText: String?
    @State private var terminal: SSHTerminal?
    @State private var connectTask: Task<Void, Never>?
    @FocusState private var inputFocused: Bool

    private let quickCommands = ["docker ps", "docker stats --no-stream", "df -h", "free -m", "uptime", "ps -eo pid,comm,%cpu,%mem --sort=-%cpu | head -15", "systemctl --failed --no-pager"]
    private var savedCommands: [String] { (try? JSONDecoder().decode([String].self, from: Data(savedCommandsJSON.utf8))) ?? [] }
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
                    ForEach(quickCommands + savedCommands, id: \.self) { cmd in
                        Button(cmd) {
                            pendingCommand = cmd
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

            if !command.isEmpty && command.count <= 1024 && !quickCommands.contains(command) && !savedCommands.contains(command) {
                Button("diagnostic_save") {
                    if !savedCommands.contains(command), let data = try? JSONEncoder().encode(Array((savedCommands + [command]).prefix(30))) { savedCommandsJSON = String(decoding: data, as: UTF8.self) }
                }.font(.caption)
            }
            if !savedCommands.isEmpty { Button("diagnostic_clear") { savedCommandsJSON = "[]" }.font(.caption) }
            HStack(spacing: 8) {
                TextField("terminal_placeholder", text: $command)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(.footnote, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($inputFocused)
                    .onSubmit { pendingCommand = command }
                Button {
                    pendingCommand = command
                } label: {
                    Image(systemName: "paperplane.fill")
                }
                .disabled(command.trimmingCharacters(in: .whitespaces).isEmpty || terminal == nil)
                .accessibilityLabel(Text("terminal_send"))
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
        .alert("diagnostic_confirm", isPresented: Binding(get: { pendingCommand != nil }, set: { if !$0 { pendingCommand = nil } })) {
            Button("diagnostic_run") { if let pendingCommand { send(pendingCommand) }; pendingCommand = nil }
            Button("settings_cancel", role: .cancel) { pendingCommand = nil }
        } message: {
            Text("\(server.sshUser)@\(server.sshHost ?? server.endpoint.hostForLabel):\(server.sshPort)\n\n\(pendingCommand ?? "")")
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
        let host = server.sshHost ?? endpoint.hostForLabel
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
