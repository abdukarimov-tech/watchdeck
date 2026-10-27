import Foundation

public enum ConnectionState: Sendable, Equatable {
    case connecting
    case connected
    case disconnected
}

/// События репозитория — всё, что летит в UI-слой.
public enum PulseEvent: Sendable {
    case state(ConnectionState)
    case agents([AgentInfo])
    case metrics(agentId: String, MetricsReport)
    case containers(agentId: String, [Container])
    case logs(LogsChunk)
    case alert(agentId: String, EventAlert)
}

public protocol PulseRepository: AnyObject, Sendable {
    /// Вызывается один раз до start(). Колбэки приходят с не-main очереди.
    var onEvent: (@Sendable (PulseEvent) -> Void)? { get set }
    func start()
    func shutdown()
    func performAction(agentId: String, containerId: String, kind: ContainerActionKind) async throws
    func requestLogs(agentId: String, containerId: String, streamId: String)
    func refreshAgents() async throws -> [AgentInfo]
}

/// Асинхронная обертка с таймаутом.
func withTimeout<T: Sendable>(seconds: Double, _ operation: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw PulseError.timeout
        }
        guard let first = try await group.next() else { throw PulseError.timeout }
        group.cancelAll()
        return first
    }
}

/// Живой WebSocket-репозиторий к релею: авто-реконнект с backoff,
/// app-level Ping каждые 45 c (релей рвет простаивающих через 120 c),
/// refresh /api/agents каждые 60 c, экшены с ожиданием результата 10 c.
public final class WsPulseRepository: PulseRepository, @unchecked Sendable {
    public var onEvent: (@Sendable (PulseEvent) -> Void)?

    private let endpoint: PulseEndpoint
    private let lock = NSLock()
    private var connectTask: Task<Void, Never>?
    private var refreshTask: Task<Void, Never>?
    private var pendingActions: [UInt64: CheckedContinuation<ContainerActionResult, Error>] = [:]
    private var nextMsgId: UInt64 = 1
    private var socketTask: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private var closed = false

    public init(endpoint: PulseEndpoint) {
        self.endpoint = endpoint
    }

    deinit {
        connectTask?.cancel()
        refreshTask?.cancel()
    }

    public func start() {
        lock.lock()
        closed = false
        lock.unlock()
        connectTask?.cancel()
        connectTask = Task { [weak self] in await self?.runLoop() }
        refreshTask?.cancel()
        refreshTask = Task { [weak self] in await self?.agentRefreshLoop() }
    }

    public func shutdown() {
        lock.lock()
        closed = true
        let pending = pendingActions
        pendingActions.removeAll()
        let ws = socketTask
        socketTask = nil
        lock.unlock()
        pending.values.forEach { $0.resume(throwing: PulseError.offline) }
        if let ws {
            // Релей обрабатывает разрыв как обычный дисконнект; graceful close
            // в этом SDK недоступен (метод close(with:) конфликтует перегрузок).
            ws.cancel()
        }
        connectTask?.cancel()
        refreshTask?.cancel()
    }

    // MARK: - Requests

    public func performAction(agentId: String, containerId: String,
                              kind: ContainerActionKind) async throws {
        let result = try await performActionDetailed(
            agentId: agentId, containerId: containerId, kind: kind)
        guard result.ok else { throw PulseError.relay(result.error) }
    }

    public func performActionDetailed(agentId: String, containerId: String,
                                      kind: ContainerActionKind) async throws -> ContainerActionResult {
        let id = nextId()
        let msg = PulseMsg(id: id, target: agentId,
                           payload: .containerAction(ContainerAction(containerId: containerId, kind: kind)))
        return try await withTimeout(seconds: 10) { [self] in
            try await sendAwaitingResult(msg)
        }
    }

    private func sendAwaitingResult(_ msg: PulseMsg) async throws -> ContainerActionResult {
        try await withCheckedThrowingContinuation { cont in
            lock.lock()
            guard !closed, let task = socketTask else {
                lock.unlock()
                cont.resume(throwing: PulseError.offline)
                return
            }
            pendingActions[msg.id] = cont
            lock.unlock()
            Task { [weak self] in
                do {
                    try await task.send(.data(msg.encode()))
                } catch {
                    self?.failPending(msg.id, error)
                }
            }
        }
    }

    private func failPending(_ id: UInt64, _ error: Error) {
        lock.lock()
        let cont = pendingActions.removeValue(forKey: id)
        lock.unlock()
        cont?.resume(throwing: error)
    }

    public func requestLogs(agentId: String, containerId: String, streamId: String) {
        let id = nextId()
        let msg = PulseMsg(id: id, target: agentId, payload: .logsRequest(
            LogsRequest(containerId: containerId, tailLines: 200, follow: true, streamId: streamId)))
        lock.lock()
        let task = socketTask
        lock.unlock()
        guard let task else { return }
        Task { try? await task.send(.data(msg.encode())) }
    }

    public func refreshAgents() async throws -> [AgentInfo] {
        let admin = RelayAdmin(http: PinnedHTTP(certPem: endpoint.certPem))
        return try await admin.agents(clientToken: endpoint.token, restBase: endpoint.restBase)
    }

    // MARK: - Internals

    private func isClosed() -> Bool {
        lock.lock(); defer { lock.unlock() }
        return closed
    }

    private func nextId() -> UInt64 {
        lock.lock(); defer { lock.unlock() }
        nextMsgId += 1
        return nextMsgId
    }

    private func emit(_ event: PulseEvent) {
        onEvent?(event)
    }

    private func runLoop() async {
        var attempt = 0
        while !isClosed() {
            emit(.state(.connecting))
            var pingTask: Task<Void, Never>?
            do {
                let (session, task, delegate) = Self.makeSocket(endpoint: endpoint)
                lock.lock()
                urlSession = session
                socketTask = task
                lock.unlock()

                try await withTimeout(seconds: 15) { [delegate] in
                    try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                        let box = ResumeOnce()
                        delegate.onOpen = {
                            guard box.resume() else { return }
                            cont.resume(returning: ())
                        }
                        delegate.onClose = { error in
                            guard box.resume() else { return }
                            cont.resume(throwing: error ?? URLError(.networkConnectionLost))
                        }
                        task.resume()
                    }
                }

                attempt = 0
                emit(.state(.connected))

                pingTask = Task { [weak self] in await self?.pingLoop() }
                try await receiveLoop(task)
            } catch {
                // падаем в реконнект
            }
            pingTask?.cancel()

            lock.lock()
            let pending = pendingActions
            pendingActions.removeAll()
            let session = urlSession
            urlSession = nil
            socketTask = nil
            lock.unlock()
            pending.values.forEach { $0.resume(throwing: PulseError.offline) }
            session?.finishTasksAndInvalidate()

            if isClosed() { break }
            emit(.state(.disconnected))
            let delay = min(60.0, pow(2.0, Double(attempt)))
            attempt += 1
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        }
    }

    /// Гарантирует однократный resume continuation из двух колбэков.
    private final class ResumeOnce: @unchecked Sendable {
        private let lock = NSLock()
        private var done = false
        func resume() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            if done { return false }
            done = true
            return true
        }
    }

    private func pingLoop() async {
        while !Task.isCancelled, !isClosed() {
            try? await Task.sleep(nanoseconds: 45_000_000_000)
            if Task.isCancelled || isClosed() { return }
            let seq = nextId()
            let msg = PulseMsg(id: seq, payload: .ping(seq: seq))
            lock.lock()
            let task = socketTask
            lock.unlock()
            guard let task else { return }
            try? await task.send(.data(msg.encode()))
        }
    }

    private func receiveLoop(_ task: URLSessionWebSocketTask) async throws {
        while !isClosed() {
            let message = try await task.receive()
            switch message {
            case .data(let data):
                handleFrame(data)
            case .string(let text):
                handleFrame(Data(text.utf8))
            @unknown default:
                continue
            }
        }
    }

    private func handleFrame(_ data: Data) {
        guard let msg = try? PulseMsg.decode(data), !msg.target.isEmpty else { return }
        switch msg.payload {
        case .metrics(let m):
            emit(.metrics(agentId: msg.target, m))
        case .containerList(let list):
            emit(.containers(agentId: msg.target, list.containers))
        case .containerActionResult(let result):
            lock.lock()
            let cont = pendingActions.removeValue(forKey: msg.id)
            lock.unlock()
            cont?.resume(returning: result)
        case .logsChunk(let chunk):
            emit(.logs(chunk))
        case .eventAlert(let alert):
            emit(.alert(agentId: msg.target, alert))
        case .pong, .ping, .hello, .helloAck, .logsRequest, .containerAction, nil:
            break
        }
    }

    private func agentRefreshLoop() async {
        let http = PinnedHTTP(certPem: endpoint.certPem)
        let admin = RelayAdmin(http: http)
        while !Task.isCancelled {
            if !isClosed(),
               let agents = try? await admin.agents(clientToken: endpoint.token, restBase: endpoint.restBase) {
                emit(.agents(agents))
            }
            try? await Task.sleep(nanoseconds: 60_000_000_000)
        }
    }

    private static func makeSocket(endpoint: PulseEndpoint)
        -> (URLSession, URLSessionWebSocketTask, PinnedTrustDelegate) {
        let delegate = PinnedTrustDelegate(pinnedPem: endpoint.certPem)
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
        var request = URLRequest(url: endpoint.webSocketURL!)
        request.setValue("Bearer \(endpoint.token)", forHTTPHeaderField: "Authorization")
        let task = session.webSocketTask(with: request)
        return (session, task, delegate)
    }
}
