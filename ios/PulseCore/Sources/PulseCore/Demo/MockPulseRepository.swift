import Foundation

/// Демо-режим без сервера: стабильные фейковые данные для скриншотов и store review.
public final class MockPulseRepository: PulseRepository, @unchecked Sendable {
    public static let demoAgentId = "demo-vps"
    public static let demoServerId = "demo"

    public var onEvent: (@Sendable (PulseEvent) -> Void)?

    private let lock = NSLock()
    private var metricsTask: Task<Void, Never>?
    private var logsTask: Task<Void, Never>?
    private var closed = false
    private var cpu = 24.0
    private var memUsed = UInt64(3 * 1024 * 1024 * 1024)
    private var batchWorkerRunning = false

    private var containers: [Container] {
        func c(_ id: String, _ name: String, _ image: String, _ state: ContainerState,
               _ status: String, _ cpu: Double, _ mem: UInt64) -> Container {
            var container = Container()
            container.id = id
            container.name = name
            container.image = image
            container.state = state
            container.status = status
            container.createdUnix = Int64(Date.now.timeIntervalSince1970) - 3 * 86_400
            container.cpuPercent = cpu
            container.memUsed = mem
            container.memLimit = 2 * 1024 * 1024 * 1024
            return container
        }
        return [
            c("demo-nginx", "web-nginx", "nginx:1.27-alpine", .running, "Up 3 days (healthy)", 2.1, 128 * 1024 * 1024),
            c("demo-batch", "batch-worker", "python:3.12-slim",
              batchWorkerRunning ? .running : .exited,
              batchWorkerRunning ? "Up 2 seconds" : "Exited (137) 2 hours ago", 0, 0),
            c("demo-pg", "postgres", "postgres:16-alpine", .running, "Up 3 days", 1.4, 640 * 1024 * 1024),
            c("demo-redis", "redis", "redis:7-alpine", .running, "Up 5 days", 0.6, 64 * 1024 * 1024),
        ]
    }

    public init() {}

    public func start() {
        lock.lock()
        closed = false
        lock.unlock()
        emit(.state(.connected))
        emit(.agents([AgentInfo(agentId: Self.demoAgentId, hostname: "demo-server", agentVersion: "1.0.0")]))
        emit(.containers(agentId: Self.demoAgentId, containers))
        emitMetrics()
        metricsTask?.cancel()
        metricsTask = Task { [weak self] in await self?.metricsLoop() }
        logsTask?.cancel()
        logsTask = Task { [weak self] in await self?.logsLoop() }
    }

    public func shutdown() {
        lock.lock()
        closed = true
        lock.unlock()
        metricsTask?.cancel()
        logsTask?.cancel()
    }

    deinit {
        metricsTask?.cancel()
        logsTask?.cancel()
    }

    public func performAction(agentId: String, containerId: String, kind: ContainerActionKind) async throws {
        guard kind == .restart else {
            throw PulseError.relay("demo mode: action not supported")
        }
        try await Task.sleep(nanoseconds: 600_000_000)
        lock.lock()
        batchWorkerRunning = true
        let list = containers
        lock.unlock()
        emit(.containers(agentId: Self.demoAgentId, list))
    }

    public func requestLogs(agentId: String, containerId: String, streamId: String) {
        // живой поток уже шлёт чанки; при смене streamId UI сам сбрасывает строки
    }

    public func refreshAgents() async throws -> [AgentInfo] {
        [AgentInfo(agentId: Self.demoAgentId, hostname: "demo-server", agentVersion: "1.0.0")]
    }

    // MARK: - Loops

    private func metricsLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if Task.isCancelled { return }
            emitMetrics()
        }
    }

    private func emitMetrics() {
        lock.lock()
        cpu = min(97, max(2, cpu + Double.random(in: -14...14)))
        let memMin = UInt64(1024 * 1024 * 1024)
        let memMax = UInt64(6 * 1024 * 1024 * 1024)
        memUsed = min(memMax, max(memMin, UInt64(Int64(memUsed) + Int64.random(in: -300 * 1024 * 1024...300 * 1024 * 1024))))
        var metrics = MetricsReport()
        metrics.cpuUsagePercent = cpu
        metrics.cpuCores = 4
        metrics.loadAvg = [cpu / 100 * 4, cpu / 100 * 3.2, cpu / 100 * 2.7]
        metrics.memTotal = 16 * 1024 * 1024 * 1024
        metrics.memUsed = memUsed
        metrics.swapTotal = 2 * 1024 * 1024 * 1024
        metrics.swapUsed = 256 * 1024 * 1024
        metrics.uptimeSeconds = UInt64(Date.now.timeIntervalSince1970) - 1_036_800 // ~12 суток
        metrics.disks = [
            DiskStat(mount: "/", total: 80 * 1024 * 1024 * 1024, used: 43 * 1024 * 1024 * 1024),
            DiskStat(mount: "/var/lib/docker", total: 40 * 1024 * 1024 * 1024, used: 31 * 1024 * 1024 * 1024),
        ]
        metrics.net = NetStat(rxBytesPerSec: UInt64.random(in: 1024...921_600),
                              txBytesPerSec: UInt64.random(in: 1024...307_200))
        metrics.collectedAtUnix = UInt64(Date.now.timeIntervalSince1970)
        lock.unlock()
        emit(.metrics(agentId: Self.demoAgentId, metrics))
    }

    private func logsLoop() async {
        let samples: [(Bool, String)] = [
            (false, "172.17.0.1 - - [GET /api/health] 200 12ms"),
            (false, "connected to postgres db=demo"),
            (true, "WARN cache miss rate 42% above threshold"),
            (false, "scheduled job queue-drain finished in 812ms"),
            (true, "ERROR upstream timeout after 3000ms, retrying"),
        ]
        var index = 0
        var ts = UInt64(Date.now.timeIntervalSince1970)
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 700_000_000)
            if Task.isCancelled { return }
            ts += 1
            let (stderr, text) = samples[index % samples.count]
            index += 1
            let chunk = LogsChunk(containerId: "demo-nginx", streamId: "demo",
                                  lines: [LogLine(tsUnix: Int64(ts), isStderr: stderr, text: text)], eof: false)
            emit(.logs(chunk))
        }
    }

    private func emit(_ event: PulseEvent) {
        onEvent?(event)
    }
}
