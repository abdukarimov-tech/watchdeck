import Foundation
import WidgetKit

/// Shared cache contains display data only. Credentials stay in the app Keychain.
struct WidgetServerSnapshot: Codable, Identifiable {
    var id: String
    var label: String
    var state: String
    var at: Double
    var cpu: Double
    var memory: Double
    var incident: String
}
enum WidgetSnapshotCache {
    static let group = "group.app.pulse.watchdeck"
    private static var lastReload = Date.distantPast
    static func read() -> [WidgetServerSnapshot] {
        guard let data = UserDefaults(suiteName: group)?.data(forKey: "snapshots") else { return [] }
        return (try? JSONDecoder().decode([WidgetServerSnapshot].self, from: data)) ?? []
    }
    @MainActor static func write(_ snapshots: [WidgetServerSnapshot], force: Bool = false) {
        guard let data = try? JSONEncoder().encode(snapshots) else { return }
        UserDefaults(suiteName: group)?.set(data, forKey: "snapshots")
        if force || Date().timeIntervalSince(lastReload) >= 60 {
            lastReload = Date()
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}
