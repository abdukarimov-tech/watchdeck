import SwiftUI
import PulseCore

/// Фирменные цвета — перенесены из Android-темы.
enum PulseTheme {
    static let success = Color(red: 0.28, green: 0.74, blue: 0.59)
    static let warn = Color(red: 0.91, green: 0.71, blue: 0.36)
    static let error = Color(red: 0.94, green: 0.49, blue: 0.51)
    static let accent = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.42, green: 0.62, blue: 1, alpha: 1)
            : UIColor(red: 0.18, green: 0.36, blue: 0.74, alpha: 1)
    })
    static let background = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.063, green: 0.071, blue: 0.086, alpha: 1)
            : UIColor(red: 0.96, green: 0.97, blue: 0.98, alpha: 1)
    })
    static let surface = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.102, green: 0.118, blue: 0.145, alpha: 1)
            : UIColor.white
    })
    static let border = Color.primary.opacity(0.09)

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
        if percent < 0.6 { return accent }
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
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(valueText)
                .font(.system(.title2, design: .default).weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(.primary)
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
            .background(PulseTheme.surface, in: RoundedRectangle(cornerRadius: 22))
            .overlay(RoundedRectangle(cornerRadius: 22).stroke(PulseTheme.border, lineWidth: 1))
    }
}

/// Строка лога в темном терминальном стиле (как в Android LogsScreen).
struct LogRow: View {
    let line: LogLineUi

    private var textColor: Color {
        if line.level == "ERROR" { return PulseTheme.error }
        if line.level == "WARN" { return PulseTheme.warn }
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

struct MetricPanel: View {
    let title: String
    let subtitle: String
    let percent: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text("\(Int(percent * 100))%")
                .font(.system(size: 32, weight: .semibold)).monospacedDigit()
            SessionSparkline(value: percent)
                .frame(height: 48)
            Text("metric_session").font(.caption2).foregroundStyle(.secondary)
            ProgressView(value: min(max(percent, 0), 1))
                .tint(PulseTheme.load(percent))
            Text(subtitle).font(.caption).foregroundStyle(.secondary)
                .lineLimit(1).minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Реальные изменения показателя за время просмотра. Шкала фиксирована: 0–100%.
struct SessionSparkline: View {
    let value: Double
    @State private var samples: [Double] = []

    var body: some View {
        Canvas { context, size in
            let inset: CGFloat = 3
            let height = max(0, size.height - inset * 2)
            var baseline = Path()
            baseline.move(to: CGPoint(x: 0, y: size.height - inset))
            baseline.addLine(to: CGPoint(x: size.width, y: size.height - inset))
            context.stroke(baseline, with: .color(PulseTheme.accent.opacity(0.1)), lineWidth: 1)
            guard samples.count > 1 else { return }
            var line = Path()
            for (i, sample) in samples.enumerated() {
                let point = CGPoint(x: CGFloat(i) / CGFloat(samples.count - 1) * size.width,
                                    y: inset + (1 - sample) * height)
                if i == 0 { line.move(to: point) } else { line.addLine(to: point) }
            }
            context.stroke(line, with: .color(PulseTheme.accent),
                           style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            if let last = samples.last {
                let dot = CGRect(x: size.width - 3, y: inset + (1 - last) * height - 3, width: 6, height: 6)
                context.fill(Path(ellipseIn: dot), with: .color(PulseTheme.accent))
            }
        }
        .onChange(of: value, initial: true) { _, next in
            if next.isFinite { samples = Array((samples + [min(max(next, 0), 1)]).suffix(30)) }
        }
        .accessibilityLabel(Text("metric_session"))
    }
}
