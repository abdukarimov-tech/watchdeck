import SwiftUI
import PulseCore

private struct LogDestination: Hashable {
    let server: String
    let agent: String
    let container: String
}
struct ServerListScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var showSettings = false
    @State private var showAdd = false
    @State private var showIncidents = false
    @State private var logDestination: LogDestination?

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 12) {
                    Text("fleet_title")
                        .font(.largeTitle.bold())
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 12)
                    HStack {
                        Text("fleet_servers").font(.headline)
                        Spacer()
                        Text("\(model.servers.count)").font(.caption).foregroundStyle(.secondary)
                    }.padding(.top, 12)
                    ForEach(model.servers) { server in
                        NavigationLink(value: server.id) {
                            ServerCard(server: server)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            if server.id != model.activeId {
                                Button {
                                    model.select(server: server.id)
                                } label: {
                                    Label("details_make_active", systemImage: "star")
                                }
                            }
                            Button(role: .destructive) {
                                model.removeServer(server.id)
                            } label: {
                                Label("details_remove", systemImage: "trash")
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
            }
            .background(PulseTheme.background)
            .navigationTitle(Text("WatchDeck"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showIncidents = true } label: { Image(systemName: "bell.badge") }
                        .accessibilityLabel(Text("incidents_title"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showAdd = true } label: {
                        Image(systemName: "plus")
                    }.accessibilityLabel(Text("add_server"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .navigationDestination(for: String.self) { serverId in
                ServerDetailsScreen(serverId: serverId)
            }
            .navigationDestination(item: $model.widgetServerId) { id in ServerDetailsScreen(serverId: id) }
            .sheet(isPresented: $showIncidents, onDismiss: { logDestination = nil }) {
                NavigationStack {
                    IncidentCenterView { server, agent, container in
                        model.select(server: server)
                        logDestination = LogDestination(server: server, agent: agent, container: container)
                    }
                    .navigationDestination(item: $logDestination) { d in
                        ServerDetailsScreen(serverId: d.server, initialAgentId: d.agent, initialContainerId: d.container)
                    }
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("settings_cancel") { showIncidents = false } } }
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsScreen()
            }
            .sheet(isPresented: $showAdd) {
                NavigationStack {
                    OnboardingScreen()
                        .navigationTitle(Text("add_server"))
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                Button("settings_cancel") { showAdd = false }
                            }
                        }
                }
            }
            .onChange(of: model.servers.count) { old, new in
                if new > old { showAdd = false }
            }
        }
    }


}

/// Карточка сервера: статус, лейбл, бейдж active, мини-виджеты CPU/RAM/Disk.
struct ServerCard: View {
    @EnvironmentObject var model: AppModel
    let server: ServerConfig

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "server.rack")
                    .font(.system(size: 20))
                    .foregroundStyle(PulseTheme.accent)
                    .frame(width: 42, height: 42)
                    .background(PulseTheme.accent.opacity(0.1), in: RoundedRectangle(cornerRadius: 13))
                Text(server.label)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                if server.id == model.activeId {
                    Text("servers_active_badge")
                        .font(.caption2.bold())
                        .foregroundStyle(PulseTheme.accent)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(PulseTheme.accent.opacity(0.15), in: Capsule())
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }

            caption
            HStack(spacing: 6) {
                StatusDot(color: PulseTheme.connection(state), size: 6)
                Text(state.localizedText).font(.caption).foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.top, 4)
            Divider().overlay(PulseTheme.border).padding(.vertical, 4)

            HStack(spacing: 12) {
                MiniStat(label: NSLocalizedString("widget_cpu", comment: ""),
                         percent: metrics.cpuPercent / 100,
                         valueText: String(format: "%.0f%%", metrics.cpuPercent))
                MiniStat(label: NSLocalizedString("widget_ram", comment: ""),
                         percent: ramPercent,
                         valueText: String(format: "%.0f%%", ramPercent * 100))
                MiniStat(label: NSLocalizedString("widget_disk", comment: ""),
                         percent: metrics.disks.first?.percent ?? 0,
                         valueText: String(format: "%.0f%%", (metrics.disks.first?.percent ?? 0) * 100))
            }
        }
        .padding(18)
        .background(PulseTheme.surface, in: RoundedRectangle(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(
            PulseTheme.border,
            lineWidth: 1))
    }

    private var state: ConnectionState {
        model.serverStates[server.id] ?? .disconnected
    }

    private var metrics: MetricsUi {
        model.metrics(serverId: server.id)
    }

    private var ramPercent: Double {
        metrics.memTotalBytes > 0
            ? Double(metrics.memUsedBytes) / Double(metrics.memTotalBytes)
            : 0
    }

    @ViewBuilder
    private var caption: some View {
        if server.isDemo {
            Text("servers_demo_caption")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else if state == .connected {
            Text(pluralAgents(model.agentsByServer[server.id]?.count ?? 0))
                .font(.caption)
                .foregroundStyle(.secondary)
        } else {
            Text(state.localizedText)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func pluralAgents(_ n: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("agents_count", comment: ""), n)
    }
}
