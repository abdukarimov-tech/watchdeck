import Foundation
import PulseCore
import SwiftUI

struct ToastMessage: Equatable {
    var text: String
    var isError: Bool
}

struct HostKeyPrompt: Identifiable {
    var id = UUID()
    var fingerprint: String
    var resume: CheckedContinuation<Bool, Never>?
}

enum InstallState: Equatable {
    case idle
    case running
    case done
    case failed(String)
}

/// Сессия приложения: мульти-серверные репозитории, агрегация состояния,
/// watchdog релея, алерты, инсталляция по SSH, демо-режим.
@MainActor
final class AppModel: ObservableObject {
    @Published var servers: [ServerConfig] = []
    @Published var activeId: String?
    @Published var widgetServerId: String?
    @Published var serverStates: [String: ConnectionState] = [:]
    @Published var agentsByServer: [String: [AgentInfo]] = [:]
    @Published var metricsByServer: [String: [String: MetricsReport]] = [:]
    @Published var containersByServer: [String: [String: [Container]]] = [:]
    @Published var eventsByServer: [String: [AlertUi]] = [:]
    @Published var selectedAgentByServer: [String: String] = [:]

    @Published var logLines: [LogLineUi] = []
    @Published var toast: ToastMessage?
    @Published var hostKeyPrompt: HostKeyPrompt?
    @Published var installState: InstallState = .idle
    @Published var installLog: [String] = []
    @Published var appLockEnabled = ServersStore.appLockEnabled {
        didSet { ServersStore.appLockEnabled = appLockEnabled }
    }
    @Published var isUnlocked = false

    /// Блокируем при уходе в фон (как биометрик-лок в Android на onResume).
    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .background:
            if appLockEnabled { isUnlocked = false }
        case .active:
            if appLockEnabled, !isUnlocked {
                // экран блокировки покажет RootView и запустит LocalAuthentication
            } else {
                isUnlocked = true
            }
        default:
            break
        }
    }

    private var registeredPush = Set<String>()
    private var widgetTimes: [String: Double] = [:]
    private var repos: [String: PulseRepository] = [:]
    private var activeLogStream: String?
    private var activeLogContainerId: String?
    private var activeLogServerId: String?
    private var activeLogAgentId: String?
    private var watchdogSuppressed = false
    private var wasConnected: Set<String> = []
    private var toastTask: Task<Void, Never>?
    private var installTask: Task<Void, Never>?

    // MARK: - Bootstrap

    func bootstrap() {
        guard servers.isEmpty, repos.isEmpty else { return }
        let stored = ServersStore.load()
        servers = stored
        activeId = ServersStore.activeId
        for server in servers {
            startRepo(for: server)
        }
        if activeId == nil, let first = servers.first?.id {
            activeId = first
        }
        LocalNotifier.shared.bootstrap()
    }

    // MARK: - Derived

    var hasNonDemoServers: Bool { servers.contains { !$0.isDemo } }

    func adminToken(of server: ServerConfig) -> String? {
        server.isDemo ? nil : server.adminToken
    }

    var activeServer: ServerConfig? {
        servers.first { $0.id == activeId }
    }

    var activeAgents: [AgentInfo] { agentsByServer[activeId ?? ""] ?? [] }

    var activeConnectionState: ConnectionState {
        serverStates[activeId ?? ""] ?? .disconnected
    }

    /// Агрегированные метрики активного сервера (средний CPU, суммарная память — как в Android).
    var activeAggregatedMetrics: MetricsUi {
        MetricsUi.aggregate(Array((metricsByServer[activeId ?? ""] ?? [:]).values))
    }

    func metrics(serverId: String) -> MetricsUi {
        MetricsUi.aggregate(Array((metricsByServer[serverId] ?? [:]).values))
    }

    func selectedAgent(serverId: String) -> String? {
        if let chosen = selectedAgentByServer[serverId], !chosen.isEmpty { return chosen }
        return agentsByServer[serverId]?.first?.agentId
    }

    func containers(serverId: String, agentId: String?) -> [Container] {
        guard let agentId else { return [] }
        return containersByServer[serverId]?[agentId] ?? []
    }

    // MARK: - Repo management

    private func startRepo(for server: ServerConfig) {
        let serverId = server.id
        let repo: PulseRepository = server.isDemo
            ? MockPulseRepository()
            : WsPulseRepository(endpoint: server.endpoint)
        repo.onEvent = { [weak self] event in
            Task { @MainActor [weak self] in
                self?.handle(event: event, serverId: serverId)
            }
        }
        repos[serverId] = repo
        repo.start()
    }

    private func stopRepo(_ serverId: String) {
        repos[serverId]?.shutdown()
        repos.removeValue(forKey: serverId)
    }

    // MARK: - Event handling

    private func handle(event: PulseEvent, serverId: String) {
        switch event {
        case .state(let state):
            serverStates[serverId] = state
            if state == .connected { registerAPNs() }
            publishWidget(force: true)
            handleWatchdog(serverId: serverId, state: state)

        case .agents(let agents):
            agentsByServer[serverId] = agents
            registerAPNs()
            if selectedAgentByServer[serverId] == nil, let first = agents.first {
                selectedAgentByServer[serverId] = first.agentId
            }

        case .metrics(_, let metrics):
            metricsByServer[serverId, default: [:]][agentOf(event: event) ?? ""] = metrics
            widgetTimes[serverId] = Date().timeIntervalSince1970
            publishWidget()

        case .containers(_, let containers):
            containersByServer[serverId, default: [:]][agentOf(event: event) ?? ""] = containers

        case .logs(let chunk):
            guard chunk.streamId == activeLogStream else { return }
            logLines.append(contentsOf: chunk.lines.map {
                LogLineUi(tsUnix: $0.tsUnix, isStderr: $0.isStderr, text: $0.text)
            })
            if logLines.count > 2000 {
                logLines.removeFirst(logLines.count - 2000)
            }

        case .alert(_, let alert):
            var events = eventsByServer[serverId] ?? []
            events.insert(AlertUi(kind: alert.kind, containerName: alert.containerName,
                                  message: alert.message, atUnix: alert.atUnix,
                                  agentId: agentOf(event: event) ?? ""), at: 0)
            if events.count > 200 { events.removeLast(events.count - 200) }
            eventsByServer[serverId] = events
            publishWidget(force: true)

            switch alert.kind {
            case .containerDied:
                showToast(text: String(format: NSLocalizedString("alert_toast", comment: ""),
                                       alert.containerName, alert.message), isError: true)
            case .oom:
                showToast(text: String(format: NSLocalizedString("alert_toast", comment: ""),
                                       alert.containerName, alert.message), isError: true)
            case .serverOffline:
                showToast(text: String(format: NSLocalizedString("alert_server_offline", comment: ""),
                                       agentName(serverId: serverId)), isError: true)
            case .serverOnline:
                showToast(text: String(format: NSLocalizedString("alert_server_online", comment: ""),
                                       agentName(serverId: serverId)), isError: false)
            default:
                break
            }
        }
    }

    private func agentOf(event: PulseEvent) -> String? {
        switch event {
        case .metrics(let agentId, _), .containers(let agentId, _),
             .alert(let agentId, _):
            return agentId
        default:
            return nil
        }
    }

    private func agentName(serverId: String) -> String {
        PulseFormat.shortAgentName(selectedAgent(serverId: serverId) ?? "")
    }

    // MARK: - Watchdog (рлей недоступен)

    private func handleWatchdog(serverId: String, state: ConnectionState) {
        if state == .connected {
            wasConnected.insert(serverId)
            return
        }
        if state == .disconnected, wasConnected.contains(serverId), !watchdogSuppressed {
            wasConnected.remove(serverId)
            LocalNotifier.shared.post(
                title: NSLocalizedString("notif_relay_down_title", comment: ""),
                body: NSLocalizedString("notif_relay_down_body", comment: ""),
                identifier: "relay-down-\(serverId)")
        }
    }

    // MARK: - Server management

    func select(server id: String) {
        guard id != activeId else { return }
        stopLogs()
        logLines = []
        activeId = id
        watchdogSuppressed = true
        ServersStore.activeId = id
        // снятие подавления после того, как текущая транзитная дисконнект-волна улеглась
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            self?.watchdogSuppressed = false
        }
    }

    func addManualServer(url: String, token: String) {
        let endpoint = PulseEndpoint(baseURL: url, token: token)
        var server = ServerConfig(
            id: UUID().uuidString,
            label: endpoint.hostForLabel,
            url: endpoint.baseURL,
            certPem: "",
            sshFp: "",
            sshPort: 22,
            sshUser: "root",
            sshAuthKind: "password",
            isDemo: false)
        server.token = token
        servers.append(server)
        ServersStore.save(servers)
        startRepo(for: server)
        select(server: server.id)
    }

    func addDemo() {
        guard !servers.contains(where: { $0.isDemo }) else {
            if let demo = servers.first(where: { $0.isDemo }) { select(server: demo.id) }
            return
        }
        let demo = ServerConfig.demo()
        servers.append(demo)
        ServersStore.save(servers)
        startRepo(for: demo)
        select(server: demo.id)
    }

    func removeServer(_ id: String) {
        watchdogSuppressed = true
        stopRepo(id)
        if let server = servers.first(where: { $0.id == id }) {
            for account in ["token", "admin", "sshSecret", "sshKeyPass"] {
                KeychainStore.delete(account: "server.\(server.id).\(account)")
            }
        }
        servers.removeAll { $0.id == id }
        widgetTimes.removeValue(forKey: id)
        publishWidget(force: true)
        ServersStore.save(servers)
        wasConnected.remove(id)
        if activeId == id {
            activeId = servers.first?.id
            ServersStore.activeId = activeId
        }
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            self?.watchdogSuppressed = false
        }
    }

    func resetAll() {
        watchdogSuppressed = true
        for id in repos.keys { stopRepo(id) }
        ServersStore.wipeAll()
        servers = []
        widgetTimes = [:]
        publishWidget(force: true)
        activeId = nil
        serverStates = [:]
        agentsByServer = [:]
        metricsByServer = [:]
        containersByServer = [:]
        eventsByServer = [:]
        selectedAgentByServer = [:]
        logLines = []
        activeLogStream = nil
    }

    // MARK: - Container actions / logs

    func performContainerAction(serverId: String? = nil, containerId: String, kind: ContainerActionKind) {
        guard let id = serverId ?? activeId,
              let repo = repos[id],
              let agentId = selectedAgent(serverId: id) else { return }
        Task { @MainActor [weak self] in
            do {
                try await repo.performAction(agentId: agentId, containerId: containerId, kind: kind)
            } catch {
                self?.showToast(text: Self.errorText(error), isError: true)
            }
        }
    }

    /// Запрос стрима логов; повторный вызов с тем же контейнером не пересоздает поток.
    func requestLogs(serverId: String? = nil, agentId: String, containerId: String) {
        guard let id = serverId ?? activeId, let repo = repos[id] else { return }
        if activeLogServerId == id && activeLogAgentId == agentId && activeLogContainerId == containerId && activeLogStream != nil { return }
        stopLogs()
        activeLogServerId = id; activeLogAgentId = agentId; activeLogContainerId = containerId
        let streamId = UUID().uuidString
        activeLogStream = streamId
        logLines = []
        repo.requestLogs(agentId: agentId, containerId: containerId, streamId: streamId)
    }

    func stopLogs() {
        if let id = activeLogServerId { repos[id]?.stopLogs() }
        activeLogStream = nil; activeLogServerId = nil; activeLogAgentId = nil; activeLogContainerId = nil
    }

    /// Первый запущенный контейнер (для автостарта стрима логов).
    func firstRunningContainer() -> Container? {
        guard let server = activeServer else { return nil }
        return containers(serverId: server.id, agentId: selectedAgent(serverId: server.id))
            .first { $0.state == .running }
    }

    // MARK: - SSH install / update

    func installServer(host: String, port: Int, username: String, auth: SSHAuth) {
        guard installState != .running else { return }
        installState = .running
        installLog = []
        log("[ssh] connecting to \(host):\(port)")
        let installer = SSHInstaller(params: SSHInstallParams(
            host: host, port: port, username: username, auth: auth))
        installer.onLog = { [weak self] line in
            Task { @MainActor [weak self] in self?.log("[ssh] \(line)") }
        }
        installer.onHostKeyPrompt = { [weak self] fingerprint in
            guard let self else { return false }
            return await self.promptHostKey(fingerprint)
        }
        installTask?.cancel()
        installTask = Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await installer.install()
                self.log("[ssh] done — \(result.relayUrl)")
                let server = self.makeServer(from: result, sshHost: host, sshPort: port)
                self.servers.append(server)
                ServersStore.save(self.servers)
                self.startRepo(for: server)
                self.select(server: server.id)
                self.installState = .done
            } catch {
                self.log("[ssh] error: \(String(describing: error))")
                self.installState = .failed(Self.errorText(error))
            }
        }
    }

    func updateAgent(serverId: String) {
        guard let server = servers.first(where: { $0.id == serverId }),
              let auth = server.sshAuth,
              let repo = repos[serverId] as? WsPulseRepository else {
            showToast(text: NSLocalizedString("agent_update_unavailable", comment: ""), isError: true)
            return
        }
        showToast(text: NSLocalizedString("agent_updating", comment: ""), isError: false)
        let installer = SSHInstaller(params: SSHInstallParams(
            host: server.sshHost ?? server.endpoint.hostForLabel,
            port: server.sshPort,
            username: server.sshUser,
            auth: auth))
        installer.onLog = { [weak self] line in
            Task { @MainActor [weak self] in self?.log("[ssh] \(line)") }
        }
        installTask?.cancel()
        installTask = Task { [weak self] in
            do {
                try await installer.updateAgent(expectedFp: server.sshFp)
                self?.showToast(text: NSLocalizedString("agent_updated", comment: ""), isError: false)
                _ = try? await repo.refreshAgents()
            } catch {
                self?.showToast(text: String(format: NSLocalizedString("ssh_connect_error", comment: ""),
                                             Self.errorText(error)), isError: true)
            }
        }
    }

    private func makeServer(from result: SSHInstallResult, sshHost: String, sshPort: Int) -> ServerConfig {
        var server = ServerConfig(
            id: UUID().uuidString,
            label: result.agentLabel,
            url: result.relayUrl,
            certPem: result.relayCertPem,
            sshFp: result.hostKeyFp,
            sshPort: 22,
            sshUser: "",
            sshAuthKind: "password",
            isDemo: false)
        server.token = result.clientToken
        server.adminToken = result.adminToken
        server.sshSecret = sshSecretForInstall
        server.sshKeyPass = sshKeyPassForInstall
        server.sshAuthKind = sshAuthKindForInstall
        server.sshUser = sshUserForInstall
        server.sshHost = sshHost
        server.sshPort = sshPort
        return server
    }

    // Контекст формы инсталляции (заполняется до вызова installServer).
    var sshUserForInstall = ""
    var sshSecretForInstall = ""
    var sshKeyPassForInstall = ""
    var sshAuthKindForInstall = "password"

    // MARK: - Host key dialog

    private func promptHostKey(_ fingerprint: String) async -> Bool {
        log("[ssh] host key: \(fingerprint)")
        return await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            hostKeyPrompt = HostKeyPrompt(fingerprint: fingerprint, resume: cont)
        }
    }

    func resolveHostKey(_ accepted: Bool) {
        guard let prompt = hostKeyPrompt else { return }
        hostKeyPrompt = nil
        prompt.resume?.resume(returning: accepted)
    }

    func registerAPNs() {
        guard let token = UserDefaults.standard.string(forKey: "apns_device_token") else { return }
        #if DEBUG
        let sandbox = true
        #else
        let sandbox = false
        #endif
        for server in servers where !server.isDemo {
            Task { @MainActor [weak self] in
                guard let self else { return }
                do {
                    let api = MonitoringAPI(endpoint: server.endpoint)
                    for agent in try await api.agentIDs() {
                        let key = "\(server.url):\(agent):\(token)"
                        guard !self.registeredPush.contains(key) else { continue }
                        try await api.registerAPNs(token: token, agent: agent, sandbox: sandbox)
                        self.registeredPush.insert(key)
                    }
                } catch { NSLog("WatchDeck push subscription failed: %@", String(describing: error)) }
            }
        }
    }

    private func publishWidget(force: Bool = false) {
        let snapshots = servers.map { server in
            let m = metrics(serverId: server.id)
            let latest = eventsByServer[server.id]?.filter { [.containerDied, .oom, .serverOffline, .resourceHigh, .containerUnhealthy, .restartLoop].contains($0.kind) }.max { $0.atUnix < $1.atUnix }
            let incident = latest.map { "\($0.containerName): \($0.message)" } ?? ""
            let state = serverStates[server.id] == .connected ? "connected" : "disconnected"
            return WidgetServerSnapshot(id: server.id, label: server.label, state: state,
                at: widgetTimes[server.id] ?? 0, cpu: m.cpuPercent,
                memory: m.memTotalBytes > 0 ? Double(m.memUsedBytes)/Double(m.memTotalBytes)*100 : 0,
                incident: incident)
        }
        WidgetSnapshotCache.write(snapshots, force: force)
    }

    // MARK: - Misc

    func showToast(text: String, isError: Bool) {
        toast = ToastMessage(text: text, isError: isError)
        toastTask?.cancel()
        toastTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            self?.toast = nil
        }
    }

    private func log(_ line: String) {
        installLog.append(line)
        if installLog.count > 200 { installLog.removeFirst(installLog.count - 200) }
    }

    nonisolated static func errorText(_ error: Error) -> String {
        switch error {
        case PulseError.offline:
            return NSLocalizedString("error_offline", comment: "")
        case PulseError.timeout:
            return NSLocalizedString("error_timeout", comment: "")
        case PulseError.relay(let reason):
            return String(format: NSLocalizedString("error_action_failed", comment: ""), reason)
        case let ssh as SSHError:
            switch ssh {
            case .hostKeyRejected:
                return NSLocalizedString("ssh_host_key_rejected", comment: "")
            case .unsupportedKeyFormat(let hint):
                return "\(NSLocalizedString("ssh_key_unsupported", comment: "")) (\(hint))"
            case .commandFailed(let detail):
                return detail
            }
        case let relayErr as RelayAdminError:
            return "HTTP \(relayErr.status)"
        default:
            return String(describing: error)
        }
    }
}
