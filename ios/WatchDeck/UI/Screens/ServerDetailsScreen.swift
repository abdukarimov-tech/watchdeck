import SwiftUI
import PulseCore

struct ServerDetailsScreen: View {
    @EnvironmentObject var model: AppModel
    let serverId: String

    enum Tab: String, CaseIterable {
        case dashboard, containers, logs, terminal
        var title: LocalizedStringKey {
            switch self {
            case .dashboard: return "tab_dashboard"
            case .containers: return "tab_containers"
            case .logs: return "tab_logs"
            case .terminal: return "details_tab_terminal"
            }
        }
    }

    @State private var tab: Tab = .dashboard
    @State private var showRemoveConfirm = false
    @State private var restartTarget: Container?

    var body: some View {
        VStack(spacing: 0) {
            header
            if agents.count > 1 {
                agentChips
            }
            Picker("", selection: $tab) {
                ForEach(Tab.allCases, id: \.self) { t in
                    Text(t.title).tag(t)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            switch tab {
            case .dashboard: DashboardView(metrics: metrics, agents: agents)
            case .containers: containersTab
            case .logs: LogsView()
            case .terminal: TerminalView(server: server)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle(server.label)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    if !server.isDemo && server.sshSecret != nil {
                        Button {
                            model.updateAgent(serverId: serverId)
                        } label: {
                            Label("details_update_agent", systemImage: "arrow.clockwise.circle")
                        }
                    }
                    Button(role: .destructive) {
                        showRemoveConfirm = true
                    } label: {
                        Label("details_remove", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .alert("details_remove", isPresented: $showRemoveConfirm) {
            Button("details_remove_confirm", role: .destructive) {
                model.removeServer(serverId)
            }
            Button("settings_cancel", role: .cancel) {}
        } message: {
            Text("details_remove_text")
        }
        .alert(item: $restartTarget) { container in
            Alert(
                title: Text(String(format: NSLocalizedString("containers_restart_title", comment: ""), container.name)),
                message: Text("containers_restart_body"),
                primaryButton: .destructive(Text("containers_restart_confirm")) {
                    model.performContainerAction(containerId: container.id, kind: .restart)
                },
                secondaryButton: .cancel(Text("containers_cancel")))
        }
    }

    // MARK: - Derived

    private var server: ServerConfig {
        model.servers.first { $0.id == serverId }
            ?? ServerConfig(id: serverId, label: "", url: "", certPem: "",
                            sshFp: "", sshPort: 22, sshUser: "", sshAuthKind: "password", isDemo: true)
    }

    private var state: ConnectionState {
        model.serverStates[serverId] ?? .disconnected
    }

    private var agents: [AgentInfo] {
        model.agentsByServer[serverId] ?? []
    }

    private var metrics: MetricsUi {
        model.metrics(serverId: serverId)
    }

    private var selectedAgent: String? {
        model.selectedAgent(serverId: serverId)
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                StatusDot(color: PulseTheme.connection(state))
                Text(state.localizedText)
                    .font(.footnote)
                    .foregroundStyle(state == .connected ? PulseTheme.success : .secondary)
                if let version = agents.first(where: { $0.agentId == selectedAgent })?.agentVersion,
                   !version.isEmpty, !server.isDemo {
                    Text(String(format: NSLocalizedString("agent_version_fmt", comment: ""), version))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            if !server.isDemo {
                Text(server.url)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 10)
    }

    private var agentChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(agents) { agent in
                    let isSelected = agent.agentId == selectedAgent
                    Button {
                        model.selectedAgentByServer[serverId] = agent.agentId
                    } label: {
                        HStack(spacing: 6) {
                            Text(PulseFormat.shortAgentName(agent.displayName))
                                .font(.footnote)
                            if !agent.agentVersion.isEmpty {
                                Text(agent.agentVersion)
                                    .font(.system(size: 9, design: .monospaced))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(isSelected ? PulseTheme.accent.opacity(0.18) : Color.primary.opacity(0.06),
                                    in: Capsule())
                        .overlay(Capsule().stroke(isSelected ? PulseTheme.accent : .clear, lineWidth: 1))
                        .foregroundStyle(isSelected ? PulseTheme.accent : .primary)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
    }

    // MARK: - Containers tab

    private var containersTab: some View {
        let list = model.containers(serverId: serverId, agentId: selectedAgent)
        return ScrollView {
            LazyVStack(spacing: 10) {
                if list.isEmpty {
                    Text(state == .connected ? "dashboard_loading" : "conn_offline")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.top, 40)
                }
                ForEach(list) { container in
                    ContainerCard(
                        container: container,
                        onRestart: { restartTarget = container },
                        onStart: {
                            model.performContainerAction(containerId: container.id, kind: .start)
                        })
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
    }
}

struct ContainerCard: View {
    let container: Container
    let onRestart: () -> Void
    let onStart: () -> Void

    var body: some View {
        SectionCard {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(container.name)
                        .font(.subheadline.bold())
                        .foregroundStyle(.primary)
                    Text(container.image)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    if container.state == .running && container.memLimit > 0 {
                        Text("\(PulseFormat.bytes(container.memUsed)) / \(PulseFormat.bytes(container.memLimit)) · \(String(format: "%.1f%%", container.cpuPercent)) CPU")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
                Spacer()
                actionButtons
            }
            HStack(spacing: 6) {
                StatusDot(color: PulseTheme.containerState(container.state), size: 7)
                Text(container.status)
                    .font(.caption)
                    .foregroundStyle(PulseTheme.containerState(container.state))
                    .lineLimit(1)
                Spacer()
            }
        }
    }

    @ViewBuilder
    private var actionButtons: some View {
        if container.state == .running {
            Button(action: onRestart) {
                Image(systemName: "arrow.clockwise")
                    .font(.subheadline)
                    .foregroundStyle(PulseTheme.accent)
            }
            .accessibilityLabel(Text("cd_restart"))
        } else if container.state == .exited || container.state == .dead || container.state == .created {
            Button(action: onStart) {
                Image(systemName: "play.fill")
                    .font(.subheadline)
                    .foregroundStyle(PulseTheme.success)
            }
            .accessibilityLabel(Text("cd_start"))
        }
    }
}

// MARK: - Dashboard

struct DashboardView: View {
    let metrics: MetricsUi
    let agents: [AgentInfo]

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if metrics.memTotalBytes == 0 && metrics.cpuPercent == 0 {
                    Text("dashboard_loading")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.top, 40)
                }
                HStack(spacing: 8) {
                    SectionCard {
                        VStack(spacing: 6) {
                            RingGauge(title: "CPU",
                                      subtitle: coreText,
                                      percent: metrics.cpuPercent / 100)
                        }
                    }
                    SectionCard {
                        RingGauge(title: NSLocalizedString("dashboard_memory", comment: ""),
                                  subtitle: "\(PulseFormat.bytes(metrics.memUsedBytes)) / \(PulseFormat.bytes(metrics.memTotalBytes))",
                                  percent: ramPercent)
                    }
                }

                SectionCard {
                    Text(String(format: NSLocalizedString("dashboard_network_uptime", comment: ""),
                                PulseFormat.uptime(metrics.uptimeSeconds)))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    HStack(spacing: 16) {
                        networkRow(icon: "arrow.down", text: PulseFormat.bytes(metrics.rxBytesPerSec) + "/s",
                                   color: PulseTheme.success)
                        networkRow(icon: "arrow.up", text: PulseFormat.bytes(metrics.txBytesPerSec) + "/s",
                                   color: PulseTheme.accent)
                        Spacer()
                    }
                }

                ForEach(metrics.disks) { disk in
                    SectionCard {
                        HStack {
                            Text(disk.mount)
                                .font(.system(.footnote, design: .monospaced))
                            Spacer()
                            Text("\(PulseFormat.bytes(disk.usedBytes)) / \(PulseFormat.bytes(disk.totalBytes))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Color.primary.opacity(0.08))
                                Capsule()
                                    .fill(PulseTheme.load(disk.percent))
                                    .frame(width: max(0, min(1, disk.percent)) * geo.size.width)
                            }
                        }
                        .frame(height: 5)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
    }

    private var coreText: String {
        String.localizedStringWithFormat(NSLocalizedString("cpu_cores", comment: ""),
                                         max(metrics.cpuCores, 1))
    }

    private var ramPercent: Double {
        metrics.memTotalBytes > 0
            ? Double(metrics.memUsedBytes) / Double(metrics.memTotalBytes)
            : 0
    }

    private func networkRow(icon: String, text: String, color: Color) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.caption)
                .foregroundStyle(color)
            Text(text)
                .font(.system(.footnote, design: .monospaced))
        }
    }
}
