import Foundation

public struct RelayAdminError: Error, Sendable {
    public let status: Int
    public init(status: Int) { self.status = status }
}

/// REST API релея: агенты, управление токенами, история алертов.
public struct RelayAdmin: Sendable {
    public let http: PinnedHTTP

    public init(http: PinnedHTTP) {
        self.http = http
    }

    // MARK: - Client token endpoints

    /// GET /api/agents — только сейчас подключённые агенты.
    public func agents(clientToken: String, restBase: String) async throws -> [AgentInfo] {
        var request = URLRequest(url: URL(string: restBase + "/api/agents")!)
        request.setValue("Bearer \(clientToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await http.session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw RelayAdminError(status: status) }
        struct Row: Decodable {
            let agent_id: String
            let hostname: String?
            let agent_version: String?
        }
        let rows = try JSONDecoder().decode([Row].self, from: data)
        return rows.map { AgentInfo(agentId: $0.agent_id, hostname: $0.hostname ?? "",
                                    agentVersion: $0.agent_version ?? "") }
    }

    /// GET /api/alerts — история алертов (новые первыми).
    public func alerts(clientToken: String, restBase: String, agentId: String = "", limit: Int = 50) async throws -> [AlertUi] {
        var components = URLComponents(string: restBase + "/api/alerts")!
        var query = [URLQueryItem(name: "limit", value: String(limit))]
        if !agentId.isEmpty { query.append(URLQueryItem(name: "agent_id", value: agentId)) }
        components.queryItems = query
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(clientToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await http.session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw RelayAdminError(status: status) }
        struct Row: Decodable {
            let agent_id: String
            let kind: String
            let container_name: String?
            let message: String?
            let at_unix: Int64
        }
        let rows = try JSONDecoder().decode([Row].self, from: data)
        return rows.map {
            AlertUi(kind: EventKind.from(wireName: $0.kind), containerName: $0.container_name ?? "",
                    message: $0.message ?? "", atUnix: $0.at_unix, agentId: $0.agent_id)
        }
    }

    // MARK: - Admin token endpoints

    /// GET /api/tokens
    public func tokens(adminToken: String, restBase: String) async throws -> [RelayTokenInfo] {
        var request = URLRequest(url: URL(string: restBase + "/api/tokens")!)
        request.setValue(adminToken, forHTTPHeaderField: "X-Admin-Token")
        let (data, response) = try await http.session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw RelayAdminError(status: status) }
        struct Row: Decodable {
            let id: Int64
            let label: String
            let role: String
            let revoked: Bool
        }
        let rows = try JSONDecoder().decode([Row].self, from: data)
        return rows.map { RelayTokenInfo(id: $0.id, label: $0.label, role: $0.role, revoked: $0.revoked) }
    }

    /// POST /api/tokens → возвращает выпущенный токен.
    public func issueToken(adminToken: String, restBase: String, label: String, role: String) async throws -> String {
        var request = URLRequest(url: URL(string: restBase + "/api/tokens")!)
        request.httpMethod = "POST"
        request.setValue(adminToken, forHTTPHeaderField: "X-Admin-Token")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(["label": label, "role": role])
        let (data, response) = try await http.session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw RelayAdminError(status: status) }
        struct Row: Decodable { let token: String }
        return try JSONDecoder().decode(Row.self, from: data).token
    }

    /// POST /api/tokens/revoke
    public func revoke(adminToken: String, restBase: String, id: Int64) async throws {
        var request = URLRequest(url: URL(string: restBase + "/api/tokens/revoke")!)
        request.httpMethod = "POST"
        request.setValue(adminToken, forHTTPHeaderField: "X-Admin-Token")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["id": id])
        let (_, response) = try await http.session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw RelayAdminError(status: status) }
    }
}
