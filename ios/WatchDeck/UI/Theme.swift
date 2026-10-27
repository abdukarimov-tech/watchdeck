import SwiftUI
import PulseCore

/// Фирменные цвета — перенесены из Android-темы.
enum PulseTheme {
    static let success = Color(red: 0x4A / 255, green: 0xDE / 255, blue: 0x80 / 255) // #4ADE80
    static let warn = Color(red: 0xFB / 255, green: 0xBF / 255, blue: 0x24 / 255)    // #FBBF24
    static let error = Color(red: 0xFF / 255, green: 0x6B / 255, blue: 0x6B / 255)   // #FF6B6B
    static let accent = Color(red: 0x5A / 255, green: 0xC8 / 255, blue: 0xFF / 255)  // #5AC8FF

    static let terminalBg = Color(red: 0x0D / 255, green: 0x11 / 255, blue: 0x17 / 255) // #0D1117
    static let terminalFg = Color(red: 0xC9 / 255, green: 0xD4 / 255, blue: 0xE5 / 255) // #C9D4E5
    static let logTime = Color(red: 0x4B / 255, green: 0x55 / 255, blue: 0x63 / 255)    // #4B5563
    static let logText = Color(red: 0x8A / 255, green: 0x93 / 255, blue: 0xA6 / 255)    // #8A93A6

    static func connection(_ state: ConnectionState) -> Color {
        switch state {
        case .connected: return success
        case .connecting: return warn
        case .disconnected: return error
        }
    }

    static func containerState(_ state: ContainerState) -> Color {
        switch state {
        case .running: return success
        case .created, .paused, .restarting: return warn
        case .exited, .dead: return error
        case .unknown: return .secondary
        }
    }

    static func load(_ percent: Double) -> Color {
        if percent < 0.6 { return success }
        if percent < 0.85 { return warn }
        return error
    }
}

// MARK: - Components

struct StatusDot: View {
    let color: Color
    var size: CGFloat = 9

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .shadow(color: color.opacity(0.6), radius: 3)
    }
}

/// Кольцевой индикатор (CPU / Memory) с большим значением в центре.
struct RingGauge: View {
    let title: String
    let subtitle: String
    let percent: Double

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                Circle()
                    .stroke(Color.primary.opacity(0.1), lineWidth: 10)
                Circle()
                    .trim(from: 0, to: CGFloat(min(max(percent, 0), 1)))
                    .stroke(PulseTheme.load(percent), style: StrokeStyle(lineWidth: 10, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.easeOut(duration: 0.5), value: percent)
                VStack(spacing: 2) {
                    Text("\(Int(percent * 100))%")
                        .font(.system(.title2, design: .monospaced).bold())
                    Text(title)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: 116, height: 116)
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Мини-виджет на карточке сервера: подпись, процент, полоска загрузки.
struct MiniStat: View {
    let label: String
    let percent: Double
    let valueText: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(valueText)
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.primary.opacity(0.08))
                    Capsule()
                        .fill(PulseTheme.load(percent))
                        .frame(width: max(0, min(1, percent)) * geo.size.width)
                }
            }
            .frame(height: 3)
        }
    }
}

/// Карточка-секция как в Android-версии.
struct SectionCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(uiColor: .secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 16))
    }
}

/// Строка лога в темном терминальном стиле (как в Android LogsScreen).
struct LogRow: View {
    let line: LogLineUi

    private var textColor: Color {
        if line.isStderr || line.text.contains("ERROR") { return PulseTheme.error }
        if line.text.contains("WARN") { return PulseTheme.warn }
        return PulseTheme.logText
    }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(PulseFormat.clockTime(line.tsUnix))
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(PulseTheme.logTime)
            Text(stripAnsi(line.text))
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(textColor)
                .textSelection(.enabled)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 2)
    }

    private func stripAnsi(_ text: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: "\u{1B}\\[[0-9;?]*[a-zA-Z]") else { return text }
        let range = NSRange(text.startIndex..., in: text)
        return regex.stringByReplacingMatches(in: text, range: range, withTemplate: "")
    }
}

extension ConnectionState {
    var localizedText: String {
        switch self {
        case .connected: return NSLocalizedString("conn_status_online", comment: "")
        case .connecting: return NSLocalizedString("conn_status_connecting", comment: "")
        case .disconnected: return NSLocalizedString("conn_status_offline", comment: "")
        }
    }
}

extension ContainerState {
    var localizedText: String {
        switch self {
        case .running: return "running"
        case .created: return "created"
        case .paused: return "paused"
        case .restarting: return "restarting"
        case .exited: return "exited"
        case .dead: return "dead"
        case .unknown: return "unknown"
        }
    }
}
