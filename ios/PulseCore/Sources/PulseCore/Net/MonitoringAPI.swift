import Foundation

public struct Incident: Codable, Sendable, Identifiable {
    public let id: Int64
    public let agent_id: String
    public let kind: String
    public let container_id: String
    public let container_name: String
    public let message: String
    public let opened_at: Int64
    public let last_at: Int64
    public let resolved_at: Int64?
    public let occurrences: Int
}
public struct MetricPoint: Codable, Sendable, Identifiable {
    public var id: Int64 { at_unix }
    public let at_unix: Int64
    public let cpu_percent: Double
    public let memory_percent: Double
    public let disk_percent: Double
    public let rx_bytes_per_sec: Double
    public let tx_bytes_per_sec: Double
}
public struct AlertPolicy: Codable, Sendable, Equatable {
    public var enabled = true
    public var cpu_percent = 90.0
    public var memory_percent = 85.0
    public var disk_percent = 90.0
    public var sustain_seconds = 300
    public var restart_count = 3
    public var maintenance_until: Int64 = 0
    public init() {}
}
public struct MonitoringAPI: Sendable {
    private let endpoint: PulseEndpoint
    private let http: PinnedHTTP
    public init(endpoint: PulseEndpoint) { self.endpoint = endpoint; self.http = PinnedHTTP(certPem: endpoint.certPem) }
    private func request(_ path: String, agent: String = "", hours: Int? = nil, body: Data? = nil, method: String = "PUT") async throws -> Data {
        guard var url = URLComponents(string: endpoint.restBase + path) else { throw URLError(.badURL) }
        url.queryItems = [URLQueryItem(name: "agent_id", value: agent)]
        if let hours { url.queryItems?.append(URLQueryItem(name: "hours", value: String(hours))) }
        guard let target = url.url else { throw URLError(.badURL) }
        var request = URLRequest(url: target)
        request.setValue("Bearer \(endpoint.token)", forHTTPHeaderField: "Authorization")
        if let body { request.httpMethod = method; request.httpBody = body; request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await http.session.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw RelayAdminError(status: code) }
        return data
    }
    public func agentIDs() async throws -> [String] {
        try JSONDecoder().decode([String].self, from: await request("/api/agent-ids"))
    }
    public func incidents(agent: String = "") async throws -> [Incident] {
        try JSONDecoder().decode([Incident].self, from: await request("/api/incidents", agent: agent))
    }
    public func history(agent: String, hours: Int) async throws -> [MetricPoint] {
        try JSONDecoder().decode([MetricPoint].self, from: await request("/api/metrics", agent: agent, hours: hours))
    }
    public func policy(agent: String) async throws -> AlertPolicy {
        try JSONDecoder().decode(AlertPolicy.self, from: await request("/api/alert-policy", agent: agent))
    }
    public func savePolicy(agent: String, policy: AlertPolicy) async throws {
        _ = try await request("/api/alert-policy", agent: agent, body: JSONEncoder().encode(policy))
    }
    public func registerAPNs(token: String, agent: String, sandbox: Bool) async throws {
        let body = try JSONSerialization.data(withJSONObject: ["apns_token": token, "agent_id": agent, "sandbox": sandbox])
        _ = try await request("/api/devices", body: body, method: "POST")
    }
    /// Deterministic, explicitly demo-only data; never used for a configured relay.
    public static func demoIncidents() -> [Incident] {
        [Incident(id: 1, agent_id: "demo-vps", kind: "CONTAINER_DIED", container_id: "demo-batch", container_name: "batch-worker", message: "exit code 137", opened_at: Int64(Date().timeIntervalSince1970)-120, last_at: Int64(Date().timeIntervalSince1970)-120, resolved_at: nil, occurrences: 1)]
    }
    public static func demoHistory(hours: Int) -> [MetricPoint] {
        let now = Int64(Date().timeIntervalSince1970)
        var result: [MetricPoint] = []
        for i in 0...100 {
            let x = Double(i)
            let at = now - Int64(hours) * 3600 + Int64(i * hours * 36)
            let cpu = 20.0 + 10.0 * sin(x / 5.0)
            let memory = 31.0 + 3.0 * sin(x / 10.0)
            let rx = 240000.0 + 100000.0 * sin(x / 4.0)
            let tx = 90000.0 + 30000.0 * cos(x / 6.0)
            result.append(MetricPoint(at_unix: at, cpu_percent: cpu, memory_percent: memory, disk_percent: 53, rx_bytes_per_sec: rx, tx_bytes_per_sec: tx))
        }
        return result
    }
}
