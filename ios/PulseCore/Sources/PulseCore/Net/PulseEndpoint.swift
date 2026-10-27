import Foundation

/// Параметры подключения к релею одного сервера.
public struct PulseEndpoint: Sendable, Equatable {
    /// База URL релея: ws://host:8080, wss://host:8443, wss://relay.example.com
    /// (допускается мусор вида ".../ws/client" на конце — срезается).
    public var baseURL: String
    /// Клиентский токен (`pulse_...`).
    public var token: String
    /// PEM TLS-сертификата релея для пиннинга (пусто — системное доверие).
    public var certPem: String

    public init(baseURL: String, token: String, certPem: String = "") {
        self.baseURL = baseURL
        self.token = token
        self.certPem = certPem
    }

    /// Нормализованный WebSocket URL: `<base>/ws/client`.
    public var webSocketURL: URL? {
        var s = normalizedBase
        s += "/ws/client"
        return URL(string: s)
    }

    /// REST-база: ws→http, wss→https.
    public var restBase: String {
        var s = normalizedBase
        if s.hasPrefix("wss://") { s = "https://" + s.dropFirst(6) }
        else if s.hasPrefix("ws://") { s = "http://" + s.dropFirst(5) }
        return s
    }

    private var normalizedBase: String {
        var s = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        if s.hasSuffix("/ws/client") { s.removeLast("/ws/client".count) }
        return s
    }

    public var hostForLabel: String {
        if let url = URL(string: normalizedBase), let host = url.host { return host }
        return normalizedBase
    }
}
