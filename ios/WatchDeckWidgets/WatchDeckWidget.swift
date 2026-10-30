import SwiftUI
import WidgetKit
import AppIntents

struct ServerEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "widget_select"
    static var defaultQuery = ServerQuery()
    var id: String
    var label: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(label)") }
}
struct ServerQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [ServerEntity] {
        WidgetSnapshotCache.read().filter { identifiers.contains($0.id) }.map { ServerEntity(id: $0.id, label: $0.label) }
    }
    func suggestedEntities() async throws -> [ServerEntity] {
        WidgetSnapshotCache.read().map { ServerEntity(id: $0.id, label: $0.label) }
    }
}
struct ServerConfiguration: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "widget_title"
    static var description = IntentDescription("widget_select")
    @Parameter(title: "widget_select") var server: ServerEntity?
}
struct ServerEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetServerSnapshot?
}
struct ServerProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> ServerEntry { ServerEntry(date: .now, snapshot: nil) }
    func snapshot(for configuration: ServerConfiguration, in context: Context) async -> ServerEntry { entry(configuration) }
    func timeline(for configuration: ServerConfiguration, in context: Context) async -> Timeline<ServerEntry> {
        Timeline(entries: [entry(configuration)], policy: .after(Date().addingTimeInterval(15 * 60)))
    }
    private func entry(_ configuration: ServerConfiguration) -> ServerEntry {
        let rows = WidgetSnapshotCache.read()
        let selected = configuration.server.map { server in rows.first { $0.id == server.id } } ?? rows.first
        return ServerEntry(date: .now, snapshot: selected)
    }
}
struct ServerWidgetView: View {
    let entry: ServerEntry
    @Environment(\.widgetFamily) private var family
    private var stale: Bool { entry.snapshot.map { $0.at == 0 || entry.date.timeIntervalSince1970 - $0.at > 120 } ?? true }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let s = entry.snapshot {
                HStack {
                    Image(systemName: "server.rack").foregroundStyle(Color(red: 0.42, green: 0.62, blue: 1))
                    Text(s.label).font(.headline).lineLimit(1)
                }
                Text(stale ? "widget_stale" : (s.state == "connected" ? "conn_status_online" : "conn_status_offline"))
                    .font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                HStack(spacing: 12) {
                    Text("CPU \(Int(s.cpu))%")
                    Text("RAM \(Int(s.memory))%")
                }.font(.system(.subheadline, design: .rounded).weight(.semibold)).monospacedDigit()
                if !s.incident.isEmpty && family != .systemSmall {
                    Text(s.incident).font(.caption).foregroundStyle(Color(red: 0.94, green: 0.49, blue: 0.51)).lineLimit(1)
                }
                Spacer(minLength: 0)
                if s.at > 0 {
                    HStack(spacing: 4) { Text("widget_updated"); Text(Date(timeIntervalSince1970: s.at), style: .time) }.font(.caption2).foregroundStyle(.secondary)
                }
            } else {
                Text("WatchDeck").font(.headline)
                Text("widget_empty").font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .containerBackground(for: .widget) { Color(uiColor: .secondarySystemGroupedBackground) }
        .widgetURL(entry.snapshot.flatMap { URL(string: "watchdeck://server/\($0.id)") })
    }
}
@main
struct WatchDeckWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "WatchDeckServer", intent: ServerConfiguration.self, provider: ServerProvider()) { entry in ServerWidgetView(entry: entry) }
            .configurationDisplayName("widget_title")
            .description("widget_select")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}
