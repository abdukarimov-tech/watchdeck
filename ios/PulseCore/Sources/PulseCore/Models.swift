import Foundation

public enum PulseError: Error, Sendable {
    case offline
    case timeout
    case relay(String)
}

public struct AgentInfo: Sendable, Equatable, Identifiable {
    public var agentId: String
    public var hostname: String
    public var agentVersion: String

    public var id: String { agentId }
    public var displayName: String { hostname.isEmpty ? agentId : hostname }

    public init(agentId: String, hostname: String, agentVersion: String) {
        self.agentId = agentId
        self.hostname = hostname
        self.agentVersion = agentVersion
    }
}

public struct RelayTokenInfo: Sendable, Equatable, Identifiable {
    public var id: Int64
    public var label: String
    public var role: String
    public var revoked: Bool

    public init(id: Int64, label: String, role: String, revoked: Bool) {
        self.id = id
        self.label = label
        self.role = role
        self.revoked = revoked
    }
}

/// Метрики, приведённые к UI-виду.
public struct MetricsUi: Sendable, Equatable {
    public var cpuPercent: Double = 0
    public var cpuCores: Int = 0
    public var memTotalBytes: UInt64 = 0
    public var memUsedBytes: UInt64 = 0
    public var disks: [DiskUi] = []
    public var rxBytesPerSec: UInt64 = 0
    public var txBytesPerSec: UInt64 = 0
    public var uptimeSeconds: UInt64 = 0

    public init() {}
    public init(from m: MetricsReport) {
        cpuPercent = m.cpuUsagePercent
        cpuCores = Int(m.cpuCores)
        memTotalBytes = m.memTotal
        memUsedBytes = m.memUsed
        disks = m.disks.map { DiskUi(mount: $0.mount, totalBytes: $0.total, usedBytes: $0.used) }
        rxBytesPerSec = m.net.rxBytesPerSec
        txBytesPerSec = m.net.txBytesPerSec
        uptimeSeconds = m.uptimeSeconds
    }

    public static func aggregate(_ reports: [MetricsReport]) -> MetricsUi {
        guard !reports.isEmpty else { return MetricsUi() }
        var ui = MetricsUi()
        let n = Double(reports.count)
        ui.cpuPercent = reports.reduce(0.0) { $0 + $1.cpuUsagePercent } / n
        ui.cpuCores = reports.reduce(0) { $0 + Int($1.cpuCores) }
        ui.memTotalBytes = reports.reduce(0) { $0 + $1.memTotal }
        ui.memUsedBytes = reports.reduce(0) { $0 + $1.memUsed }
        ui.rxBytesPerSec = reports.reduce(0) { $0 + $1.net.rxBytesPerSec }
        ui.txBytesPerSec = reports.reduce(0) { $0 + $1.net.txBytesPerSec }
        ui.uptimeSeconds = reports.map(\.uptimeSeconds).min() ?? 0
        // диски берём у первого агента с непустым списком
        if let first = reports.first(where: { !$0.disks.isEmpty }) {
            ui.disks = first.disks.map { DiskUi(mount: $0.mount, totalBytes: $0.total, usedBytes: $0.used) }
        }
        return ui
    }
}

public struct DiskUi: Sendable, Equatable, Identifiable {
    public var mount: String
    public var totalBytes: UInt64
    public var usedBytes: UInt64

    public var id: String { mount }
    public var percent: Double { totalBytes > 0 ? Double(usedBytes) / Double(totalBytes) : 0 }

    public init(mount: String, totalBytes: UInt64, usedBytes: UInt64) {
        self.mount = mount
        self.totalBytes = totalBytes
        self.usedBytes = usedBytes
    }
}

public struct ContainerUi: Sendable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var image: String
    public var state: ContainerState
    public var status: String
    public var cpuPercent: Double
    public var memUsedBytes: UInt64
    public var memLimitBytes: UInt64

    public init(id: String, name: String, image: String, state: ContainerState,
                status: String, cpuPercent: Double, memUsedBytes: UInt64, memLimitBytes: UInt64) {
        self.id = id
        self.name = name
        self.image = image
        self.state = state
        self.status = status
        self.cpuPercent = cpuPercent
        self.memUsedBytes = memUsedBytes
        self.memLimitBytes = memLimitBytes
    }
}

public struct LogLineUi: Sendable, Equatable, Identifiable {
    public var id = UUID()
    public var tsUnix: Int64
    public var isStderr: Bool
    public var text: String

    public init(tsUnix: Int64, isStderr: Bool, text: String) {
        self.tsUnix = tsUnix
        self.isStderr = isStderr
        self.text = text
    }
}

private enum LogLevelPatterns {
    static let error = try! NSRegularExpression(pattern: "\\b(ERROR|FATAL|PANIC)\\b", options: .caseInsensitive)
    static let warn = try! NSRegularExpression(pattern: "\\bWARN(ING)?\\b", options: .caseInsensitive)
}
public extension LogLineUi {
    var level: String {
        if LogLevelPatterns.error.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil { return "ERROR" }
        if LogLevelPatterns.warn.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil { return "WARN" }
        return isStderr ? "ERROR" : "INFO"
    }
}

public struct AlertUi: Sendable, Equatable, Identifiable {
    public var id = UUID()
    public var kind: EventKind
    public var containerName: String
    public var message: String
    public var atUnix: Int64
    public var agentId: String

    public init(kind: EventKind, containerName: String, message: String, atUnix: Int64, agentId: String) {
        self.kind = kind
        self.containerName = containerName
        self.message = message
        self.atUnix = atUnix
        self.agentId = agentId
    }
}

// MARK: - Formatters

public enum PulseFormat {
    /// Человекочитаемый размер: B / KiB / MiB / GiB / TiB.
    public static func bytes(_ value: UInt64) -> String {
        var v = Double(value)
        let units = ["B", "KiB", "MiB", "GiB", "TiB"]
        var unit = 0
        while v >= 1024 && unit < units.count - 1 {
            v /= 1024
            unit += 1
        }
        if unit == 0 {
            return String(format: "%.0f B", locale: localeUS, arguments: [v])
        }
        let s = String(format: "%.1f", locale: localeUS, arguments: [v])
        return (s.hasSuffix(".0") ? String(s.dropLast(2)) : s) + " " + units[unit]
    }

    /// Аптайм в виде "12d 4h", "3h 12m", "45m 10s".
    public static func uptime(_ seconds: UInt64) -> String {
        let s = Int(seconds)
        let days = s / 86400
        let hours = (s % 86400) / 3600
        let minutes = (s % 3600) / 60
        let secs = s % 60
        if days > 0 { return "\(days)d \(hours)h" }
        if hours > 0 { return "\(hours)h \(minutes)m" }
        if minutes > 0 { return "\(minutes)m \(secs)s" }
        return "\(secs)s"
    }

    public static func clockTime(_ unix: Int64) -> String {
        guard unix > 0 else { return "--:--:--" }
        let date = Date(timeIntervalSince1970: TimeInterval(unix))
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        return formatter.string(from: date)
    }

    private static let localeUS = Locale(identifier: "en_US_POSIX")

    /// Короткое имя агента для чипов: hostname без домена.
    public static func shortAgentName(_ hostname: String) -> String {
        hostname.split(separator: ".").first.map(String.init) ?? hostname
    }
}
