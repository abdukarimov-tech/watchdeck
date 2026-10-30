import SwiftUI
import Charts
import PulseCore

struct LocatedIncident: Identifiable {
    let server: ServerConfig
    let incident: Incident
    var id: String { "\(server.id):\(incident.id)" }
}
struct IncidentCenterView: View {
    @EnvironmentObject var model: AppModel
    let onLogs: (String, String, String) -> Void
    @State private var rows: [LocatedIncident] = []
    @State private var errors: [String] = []
    @State private var activeOnly = true
    @State private var loaded = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                Picker("incidents_title", selection: $activeOnly) {
                    Text("incidents_active").tag(true)
                    Text("incidents_all").tag(false)
                }.pickerStyle(.segmented)
                ForEach(errors, id: \.self) { Text($0).foregroundStyle(PulseTheme.error).font(.caption) }
                if !loaded { ProgressView() }
                if loaded && visible.isEmpty && errors.isEmpty { Text("incidents_empty").foregroundStyle(.secondary) }
                ForEach(visible) { row in
                    SectionCard {
                        Text("\(row.server.label) · \(row.incident.agent_id)").font(.caption).foregroundStyle(.secondary)
                        Text(LocalizedStringKey(incidentKey(row.incident.kind))).font(.headline)
                        if !row.incident.container_name.isEmpty { Text(row.incident.container_name) }
                        Text(row.incident.message).font(.footnote).textSelection(.enabled)
                        Text(row.incident.resolved_at == nil ? "incidents_active" : "incidents_resolved")
                            .font(.caption).foregroundStyle(row.incident.resolved_at == nil ? PulseTheme.error : PulseTheme.success)
                        Text(String(format: NSLocalizedString("incident_opened", comment: ""), dateText(row.incident.opened_at))).font(.caption)
                        if let at = row.incident.resolved_at {
                            Text(String(format: NSLocalizedString("incident_closed", comment: ""), dateText(at))).font(.caption)
                        }
                        Text(String(format: NSLocalizedString("incident_occurrences", comment: ""), row.incident.occurrences)).font(.caption)
                        if !row.incident.container_id.isEmpty {
                            Button("incident_logs") { onLogs(row.server.id, row.incident.agent_id, row.incident.container_id) }
                        }
                    }
                }
            }.padding(16)
        }
        .background(PulseTheme.background)
        .navigationTitle(Text("incidents_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            while !Task.isCancelled {
                await refresh()
                do { try await Task.sleep(for: .seconds(15)) } catch { return }
            }
        }
        .refreshable { await refresh() }
    }
    private var visible: [LocatedIncident] { rows.filter { !activeOnly || $0.incident.resolved_at == nil } }
    @MainActor private func refresh() async {
        var next: [LocatedIncident] = []; var failures: [String] = []; var seen = Set<String>()
        for server in model.servers {
            guard seen.insert(server.isDemo ? server.id : server.url).inserted else { continue }
            do {
                let incidents = server.isDemo ? MonitoringAPI.demoIncidents() : try await MonitoringAPI(endpoint: server.endpoint).incidents()
                next += incidents.map { LocatedIncident(server: server, incident: $0) }
            } catch is CancellationError { return }
            catch { failures.append("\(server.label): \(monitorError(error))") }
        }
        rows = next.sorted { a,b in
            if (a.incident.resolved_at == nil) != (b.incident.resolved_at == nil) { return a.incident.resolved_at == nil }
            return a.incident.last_at > b.incident.last_at
        }
        errors = failures; loaded = true
    }
}
private func incidentKey(_ kind: String) -> String {
    switch kind {
    case "CPU_HIGH": return "rule_cpu"
    case "MEMORY_HIGH": return "rule_memory"
    case "DISK_HIGH": return "rule_disk"
    case "SERVER_OFFLINE": return "incident_server_offline"
    case "OOM": return "incident_oom"
    case "RESTART_LOOP": return "incident_restart_loop"
    case "CONTAINER_UNHEALTHY": return "incident_unhealthy"
    default: return "incident_container_exit"
    }
}
func monitorError(_ error: Error) -> String {
    if let relay = error as? RelayAdminError, relay.status == 404 { return NSLocalizedString("monitor_update_relay", comment: "") }
    return AppModel.errorText(error)
}
private func dateText(_ at: Int64) -> String {
    Date(timeIntervalSince1970: Double(at)).formatted(date: .abbreviated, time: .shortened)
}

struct HistoryView: View {
    let server: ServerConfig
    let agent: String?
    @State private var hours = 24
    @State private var points: [MetricPoint] = []
    @State private var loading = true
    @State private var error: String?
    private var requestID: String { "\(server.id):\(agent ?? ""): \(hours)" }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Picker("history_title", selection: $hours) {
                    Text("range_hour").tag(1); Text("range_day").tag(24); Text("range_week").tag(168)
                }.pickerStyle(.segmented)
                if server.isDemo { Text("servers_demo_caption").font(.caption).foregroundStyle(.secondary) }
                if loading { ProgressView() }
                if let error { Text(error).foregroundStyle(PulseTheme.error) }
                if !loading && points.isEmpty && error == nil { Text("history_empty").font(.footnote).foregroundStyle(.secondary) }
                if !points.isEmpty {
                    SectionCard {
                        Text("history_percent").font(.headline)
                        Chart(points) { p in
                            LineMark(x: .value("Time", Date(timeIntervalSince1970: Double(p.at_unix))), y: .value("%", p.cpu_percent)).foregroundStyle(by: .value("Metric", "CPU"))
                            LineMark(x: .value("Time", Date(timeIntervalSince1970: Double(p.at_unix))), y: .value("%", p.memory_percent)).foregroundStyle(by: .value("Metric", "RAM"))
                            LineMark(x: .value("Time", Date(timeIntervalSince1970: Double(p.at_unix))), y: .value("%", p.disk_percent)).foregroundStyle(by: .value("Metric", NSLocalizedString("widget_disk", comment: "")))
                        }.chartYScale(domain: 0...100)
                            .chartForegroundStyleScale(range: [PulseTheme.accent, PulseTheme.success, PulseTheme.warn])
                            .frame(height: 200)
                    }
                    SectionCard {
                        Text("history_network").font(.headline)
                        Chart(points) { p in
                            LineMark(x: .value("Time", Date(timeIntervalSince1970: Double(p.at_unix))), y: .value("B/s", p.rx_bytes_per_sec)).foregroundStyle(by: .value("Metric", NSLocalizedString("history_rx", comment: "")))
                            LineMark(x: .value("Time", Date(timeIntervalSince1970: Double(p.at_unix))), y: .value("B/s", p.tx_bytes_per_sec)).foregroundStyle(by: .value("Metric", NSLocalizedString("history_tx", comment: "")))
                        }.chartForegroundStyleScale(range: [PulseTheme.accent, PulseTheme.success]).frame(height: 180)
                    }
                    Text("history_retention").font(.caption).foregroundStyle(.secondary)
                }
            }.padding(16)
        }.task(id: requestID) {
            loading = true; points = []; error = nil
            guard let agent else { loading = false; return }
            let api = MonitoringAPI(endpoint: server.endpoint)
            while !Task.isCancelled {
                do { points = server.isDemo ? MonitoringAPI.demoHistory(hours: hours) : try await api.history(agent: agent, hours: hours); error = nil }
                catch is CancellationError { return }
                catch { self.error = monitorError(error) }
                loading = false
                do { try await Task.sleep(for: .seconds(30)) } catch { return }
            }
        }
    }
}

struct AlertRulesView: View {
    let server: ServerConfig
    let agent: String?
    @State private var policy = AlertPolicy()
    @State private var loaded = false
    @State private var saving = false
    @State private var saved = false
    @State private var error: String?
    var body: some View {
        Form {
            Section { Text("rules_description").font(.footnote) }
            if let error { Section { Text(error).foregroundStyle(PulseTheme.error) } }
            if !loaded && error == nil { ProgressView() }
            if loaded {
                Toggle("rules_enabled", isOn: $policy.enabled)
                Section {
                    threshold("rule_cpu", value: $policy.cpu_percent)
                    threshold("rule_memory", value: $policy.memory_percent)
                    threshold("rule_disk", value: $policy.disk_percent)
                    Stepper(value: $policy.sustain_seconds, in: 0...3600, step: 30) {
                        Text("\(NSLocalizedString("rule_duration", comment: "")): \(policy.sustain_seconds)s")
                    }
                    Stepper(value: $policy.restart_count, in: 2...100) {
                        Text("\(NSLocalizedString("rule_restarts", comment: "")): \(policy.restart_count)")
                    }
                    Text("rules_dedupe").font(.caption).foregroundStyle(.secondary)
                }
                Section("maintenance_title") {
                    if policy.maintenance_until > Int64(Date().timeIntervalSince1970) {
                        Text(String(format: NSLocalizedString("maintenance_until", comment: ""), dateText(policy.maintenance_until)))
                    }
                    Button("maintenance_hour") { policy.maintenance_until = Int64(Date().timeIntervalSince1970)+3600 }
                    Button("maintenance_off") { policy.maintenance_until = 0 }
                }
                Button("rules_save") { Task { await save() } }.disabled(saving || agent == nil)
                if saved { Text("rules_saved").foregroundStyle(PulseTheme.success) }
            }
        }.onChange(of: policy) { _, _ in saved = false }
        .task(id: "\(server.id):\(agent ?? "")") {
            loaded = false; error = nil; saved = false
            guard let agent else { return }
            do {
                if server.isDemo {
                    let key = "demo-policy-\(server.id)-\(agent)"
                    policy = UserDefaults.standard.data(forKey: key).flatMap { try? JSONDecoder().decode(AlertPolicy.self, from: $0) } ?? AlertPolicy()
                } else { policy = try await MonitoringAPI(endpoint: server.endpoint).policy(agent: agent) }
                loaded = true
            } catch { self.error = monitorError(error) }
        }
    }
    private func threshold(_ key: LocalizedStringKey, value: Binding<Double>) -> some View {
        VStack(alignment: .leading) {
            HStack { Text(key); Spacer(); Text("\(Int(value.wrappedValue))%") }
            Slider(value: value, in: 1...100, step: 1)
        }
    }
    @MainActor private func save() async {
        guard let agent else { return }
        saving = true; saved = false
        defer { saving = false }
        do {
            if server.isDemo { UserDefaults.standard.set(try JSONEncoder().encode(policy), forKey: "demo-policy-\(server.id)-\(agent)") }
            else { try await MonitoringAPI(endpoint: server.endpoint).savePolicy(agent: agent, policy: policy) }
            error = nil; saved = true
        } catch { self.error = monitorError(error) }
    }
}

func healthKey(_ value: String) -> String {
    switch value { case "healthy": return "health_healthy"; case "unhealthy": return "health_unhealthy"; case "starting": return "health_starting"; default: return "health_none" }
}
struct GroupedContainersView: View {
    let containers: [Container]
    let onRestart: (Container) -> Void
    let onStart: (Container) -> Void
    let onLogs: (Container) -> Void
    @State private var search = ""
    @State private var unhealthyOnly = false
    private var visible: [Container] {
        containers.filter { c in
            (!unhealthyOnly || c.health == "unhealthy") && (search.isEmpty || [c.name,c.image,c.composeProject,c.composeService].contains { $0.localizedCaseInsensitiveContains(search) })
        }
    }
    private var groups: [String] { Array(Set(visible.map(\.composeProject))).sorted() }
    var body: some View {
        VStack(spacing: 8) {
            TextField("containers_search", text: $search).textFieldStyle(.roundedBorder).padding(.horizontal, 16)
            Toggle("health_unhealthy", isOn: $unhealthyOnly).font(.caption).padding(.horizontal, 16)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if visible.isEmpty { Text("containers_empty").foregroundStyle(.secondary) }
                    ForEach(groups, id: \.self) { project in
                        Text(project.isEmpty ? NSLocalizedString("compose_standalone", comment: "") : project).font(.subheadline).foregroundStyle(.secondary)
                        ForEach(visible.filter { $0.composeProject == project }) { c in
                            ContainerCard(container: c, onRestart: { onRestart(c) }, onStart: { onStart(c) })
                            Button("incident_logs") { onLogs(c) }.font(.caption)
                        }
                    }
                }.padding(16)
            }
        }
    }
}
