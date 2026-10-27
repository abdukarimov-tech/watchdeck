import SwiftUI
import PulseCore

struct ServerListScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 12) {
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
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle(Text("WatchDeck"))
            .toolbar {
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
            .sheet(isPresented: $showSettings) {
                SettingsScreen()
            }
            .safeAreaInset(edge: .top) {
                Text(pluralServers(model.servers.count))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
            }
        }
    }

    private func pluralServers(_ n: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("servers_count", comment: ""), n)
    }
}

/// Карточка сервера: статус, лейбл, бейдж active, мини-виджеты CPU/RAM/Disk.
struct ServerCard: View {
    @EnvironmentObject var model: AppModel
    let server: ServerConfig

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                StatusDot(color: PulseTheme.connection(state))
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
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 16))
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
            Text("servers_connecting_caption")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func pluralAgents(_ n: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("agents_count", comment: ""), n)
    }
}
