import Foundation

// MARK: - Enums

public enum ContainerState: Int, Sendable, CaseIterable {
    case unknown = 0
    case created = 1
    case running = 2
    case paused = 3
    case restarting = 4
    case exited = 5
    case dead = 6
}

public enum ContainerActionKind: Int, Sendable, CaseIterable {
    case unspecified = 0
    case start = 1
    case stop = 2
    case restart = 3
}

public enum EventKind: Int, Sendable, CaseIterable {
    case unknown = 0
    case containerDied = 1
    case containerStarted = 2
    case oom = 3
    case serverOffline = 4
    case serverOnline = 5
    case resourceHigh = 6
    case resourceRecovered = 7
    case containerUnhealthy = 8
    case restartLoop = 9

    /// Имена как в релеевской истории алертов (GET /api/alerts).
    public var wireName: String {
        switch self {
        case .containerDied: return "CONTAINER_DIED"
        case .containerStarted: return "CONTAINER_STARTED"
        case .oom: return "OOM"
        case .serverOffline: return "SERVER_OFFLINE"
        case .serverOnline: return "SERVER_ONLINE"
        case .resourceHigh: return "RESOURCE_HIGH"
        case .resourceRecovered: return "RESOURCE_RECOVERED"
        case .containerUnhealthy: return "CONTAINER_UNHEALTHY"
        case .restartLoop: return "RESTART_LOOP"
        case .unknown: return "EVENT_UNKNOWN"
        }
    }

    public static func from(wireName: String) -> EventKind {
        switch wireName {
        case "CONTAINER_DIED": return .containerDied
        case "CONTAINER_STARTED": return .containerStarted
        case "OOM": return .oom
        case "SERVER_OFFLINE": return .serverOffline
        case "SERVER_ONLINE": return .serverOnline
        case "RESOURCE_HIGH": return .resourceHigh
        case "RESOURCE_RECOVERED": return .resourceRecovered
        case "CONTAINER_UNHEALTHY": return .containerUnhealthy
        case "RESTART_LOOP": return .restartLoop
        default: return .unknown
        }
    }
}

// MARK: - Payload structs (pulse.v1)
//
// Каждый тип умеет encode(into:) для вложения в конверт Msg
// и статический decode(_:).

public struct Hello: Sendable, Equatable {
    public var agentVersion = ""
    public var hostname = ""
    public var os = ""
    public var kernel = ""
    public var cpuCores: UInt32 = 0
    public var totalMemory: UInt64 = 0

    public init() {}

    public func encode(into w: inout ProtoWriter) {
        w.stringField(1, agentVersion)
        w.stringField(2, hostname)
        w.stringField(3, os)
        w.stringField(4, kernel)
        w.varintField(5, UInt64(cpuCores))
        w.varintField(6, totalMemory)
    }

    public static func decode(_ r: inout ProtoReader) throws -> Hello {
        var m = Hello()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .lengthDelimited): m.agentVersion = try r.readString()
            case (2, .lengthDelimited): m.hostname = try r.readString()
            case (3, .lengthDelimited): m.os = try r.readString()
            case (4, .lengthDelimited): m.kernel = try r.readString()
            case (5, .varint): m.cpuCores = UInt32(truncatingIfNeeded: try r.readVarint())
            case (6, .varint): m.totalMemory = try r.readVarint()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct HelloAck: Sendable, Equatable {
    public var agentId = ""
    public var relayVersion = ""

    public init() {}
    public init(agentId: String, relayVersion: String) {
        self.agentId = agentId
        self.relayVersion = relayVersion
    }

    public func encode(into w: inout ProtoWriter) {
        w.stringField(1, agentId)
        w.stringField(2, relayVersion)
    }

    public static func decode(_ r: inout ProtoReader) throws -> HelloAck {
        var m = HelloAck()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .lengthDelimited): m.agentId = try r.readString()
            case (2, .lengthDelimited): m.relayVersion = try r.readString()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct DiskStat: Sendable, Equatable {
    public var mount = ""
    public var total: UInt64 = 0
    public var used: UInt64 = 0

    public init() {}
    public init(mount: String, total: UInt64, used: UInt64) {
        self.mount = mount
        self.total = total
        self.used = used
    }

    public func encode(into w: inout ProtoWriter) {
        w.stringField(1, mount)
        w.varintField(2, total)
        w.varintField(3, used)
    }

    public static func decode(_ r: inout ProtoReader) throws -> DiskStat {
        var m = DiskStat()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .lengthDelimited): m.mount = try r.readString()
            case (2, .varint): m.total = try r.readVarint()
            case (3, .varint): m.used = try r.readVarint()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct NetStat: Sendable, Equatable {
    public var rxBytesPerSec: UInt64 = 0
    public var txBytesPerSec: UInt64 = 0

    public init() {}
    public init(rxBytesPerSec: UInt64, txBytesPerSec: UInt64) {
        self.rxBytesPerSec = rxBytesPerSec
        self.txBytesPerSec = txBytesPerSec
    }

    public func encode(into w: inout ProtoWriter) {
        w.varintField(1, rxBytesPerSec)
        w.varintField(2, txBytesPerSec)
    }

    public static func decode(_ r: inout ProtoReader) throws -> NetStat {
        var m = NetStat()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .varint): m.rxBytesPerSec = try r.readVarint()
            case (2, .varint): m.txBytesPerSec = try r.readVarint()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct MetricsReport: Sendable, Equatable {
    public var cpuUsagePercent: Double = 0
    public var cpuCores: UInt32 = 0
    public var loadAvg: [Double] = []
    public var memTotal: UInt64 = 0
    public var memUsed: UInt64 = 0
    public var swapTotal: UInt64 = 0
    public var swapUsed: UInt64 = 0
    public var uptimeSeconds: UInt64 = 0
    public var disks: [DiskStat] = []
    public var net = NetStat()
    public var collectedAtUnix: UInt64 = 0

    public init() {}

    public func encode(into w: inout ProtoWriter) {
        w.doubleField(1, cpuUsagePercent)
        w.varintField(2, UInt64(cpuCores))
        w.packedDoubleField(3, loadAvg)
        w.varintField(4, memTotal)
        w.varintField(5, memUsed)
        w.varintField(6, swapTotal)
        w.varintField(7, swapUsed)
        w.varintField(8, uptimeSeconds)
        for d in disks { w.messageField(9) { d.encode(into: &$0) } }
        w.messageField(10) { net.encode(into: &$0) }
        w.varintField(11, collectedAtUnix)
    }

    public static func decode(_ r: inout ProtoReader) throws -> MetricsReport {
        var m = MetricsReport()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .fixed64): m.cpuUsagePercent = try r.readDouble()
            case (2, .varint): m.cpuCores = UInt32(truncatingIfNeeded: try r.readVarint())
            case (3, .lengthDelimited):
                // packed repeated double (дефолт proto3)
                var sub = try r.subReader()
                while sub.offset < sub.bytes.count {
                    m.loadAvg.append(try sub.readDouble())
                }
            case (3, .fixed64):
                // unpacked — тоже валидно по protobuf
                m.loadAvg.append(try r.readDouble())
            case (4, .varint): m.memTotal = try r.readVarint()
            case (5, .varint): m.memUsed = try r.readVarint()
            case (6, .varint): m.swapTotal = try r.readVarint()
            case (7, .varint): m.swapUsed = try r.readVarint()
            case (8, .varint): m.uptimeSeconds = try r.readVarint()
            case (9, .lengthDelimited):
                var sub = try r.subReader()
                m.disks.append(try DiskStat.decode(&sub))
            case (10, .lengthDelimited):
                var sub = try r.subReader()
                m.net = try NetStat.decode(&sub)
            case (11, .varint): m.collectedAtUnix = try r.readVarint()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct Container: Sendable, Equatable, Identifiable {
    public var id = ""
    public var name = ""
    public var image = ""
    public var state = ContainerState.unknown
    public var status = ""
    public var createdUnix: Int64 = 0
    public var cpuPercent: Double = 0
    public var memUsed: UInt64 = 0
    public var memLimit: UInt64 = 0
    public var health = "none"
    public var composeProject = ""
    public var composeService = ""

    public init() {}

    public func encode(into w: inout ProtoWriter) {
        w.stringField(1, id)
        w.stringField(2, name)
        w.stringField(3, image)
        w.enumField(4, state.rawValue)
        w.stringField(5, status)
        w.int64Field(6, createdUnix)
        w.doubleField(7, cpuPercent)
        w.varintField(8, memUsed)
        w.varintField(9, memLimit)
        w.stringField(10, health)
        w.stringField(11, composeProject)
        w.stringField(12, composeService)
    }

    public static func decode(_ r: inout ProtoReader) throws -> Container {
        var m = Container()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .lengthDelimited): m.id = try r.readString()
            case (2, .lengthDelimited): m.name = try r.readString()
            case (3, .lengthDelimited): m.image = try r.readString()
            case (4, .varint): m.state = ContainerState(rawValue: Int(try r.readVarint())) ?? .unknown
            case (5, .lengthDelimited): m.status = try r.readString()
            case (6, .varint): m.createdUnix = Int64(bitPattern: try r.readVarint())
            case (7, .fixed64): m.cpuPercent = try r.readDouble()
            case (8, .varint): m.memUsed = try r.readVarint()
            case (9, .varint): m.memLimit = try r.readVarint()
            case (10, .lengthDelimited): m.health = try r.readString()
            case (11, .lengthDelimited): m.composeProject = try r.readString()
            case (12, .lengthDelimited): m.composeService = try r.readString()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct ContainerList: Sendable, Equatable {
    public var containers: [Container] = []

    public init() {}
    public init(containers: [Container]) { self.containers = containers }

    public func encode(into w: inout ProtoWriter) {
        for c in containers { w.messageField(1) { c.encode(into: &$0) } }
    }

    public static func decode(_ r: inout ProtoReader) throws -> ContainerList {
        var m = ContainerList()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .lengthDelimited):
                var sub = try r.subReader()
                m.containers.append(try Container.decode(&sub))
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct ContainerAction: Sendable, Equatable {
    public var containerId = ""
    public var kind = ContainerActionKind.unspecified

    public init() {}
    public init(containerId: String, kind: ContainerActionKind) {
        self.containerId = containerId
        self.kind = kind
    }

    public func encode(into w: inout ProtoWriter) {
        w.stringField(1, containerId)
        w.enumField(2, kind.rawValue)
    }

    public static func decode(_ r: inout ProtoReader) throws -> ContainerAction {
        var m = ContainerAction()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .lengthDelimited): m.containerId = try r.readString()
            case (2, .varint): m.kind = ContainerActionKind(rawValue: Int(try r.readVarint())) ?? .unspecified
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct ContainerActionResult: Sendable, Equatable {
    public var containerId = ""
    public var kind = ContainerActionKind.unspecified
    public var ok = false
    public var error = ""

    public init() {}
    public init(containerId: String, kind: ContainerActionKind, ok: Bool, error: String) {
        self.containerId = containerId
        self.kind = kind
        self.ok = ok
        self.error = error
    }

    public func encode(into w: inout ProtoWriter) {
        w.stringField(1, containerId)
        w.enumField(2, kind.rawValue)
        w.boolField(3, ok)
        w.stringField(4, error)
    }

    public static func decode(_ r: inout ProtoReader) throws -> ContainerActionResult {
        var m = ContainerActionResult()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .lengthDelimited): m.containerId = try r.readString()
            case (2, .varint): m.kind = ContainerActionKind(rawValue: Int(try r.readVarint())) ?? .unspecified
            case (3, .varint): m.ok = try r.readBool()
            case (4, .lengthDelimited): m.error = try r.readString()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct LogsRequest: Sendable, Equatable {
    public var containerId = ""
    public var tailLines: UInt32 = 0
    public var follow = false
    public var streamId = ""
    public var cancel = false

    public init() {}
    public init(containerId: String, tailLines: UInt32, follow: Bool, streamId: String) {
        self.containerId = containerId
        self.tailLines = tailLines
        self.follow = follow
        self.streamId = streamId
    }

    public func encode(into w: inout ProtoWriter) {
        w.stringField(1, containerId)
        w.varintField(2, UInt64(tailLines))
        w.boolField(3, follow)
        w.stringField(4, streamId)
        w.boolField(5, cancel)
    }

    public static func decode(_ r: inout ProtoReader) throws -> LogsRequest {
        var m = LogsRequest()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .lengthDelimited): m.containerId = try r.readString()
            case (2, .varint): m.tailLines = UInt32(truncatingIfNeeded: try r.readVarint())
            case (3, .varint): m.follow = try r.readBool()
            case (4, .lengthDelimited): m.streamId = try r.readString()
            case (5, .varint): m.cancel = try r.readBool()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct LogLine: Sendable, Equatable {
    public var tsUnix: Int64 = 0
    public var isStderr = false
    public var text = ""

    public init() {}
    public init(tsUnix: Int64, isStderr: Bool, text: String) {
        self.tsUnix = tsUnix
        self.isStderr = isStderr
        self.text = text
    }

    public func encode(into w: inout ProtoWriter) {
        w.int64Field(1, tsUnix)
        w.boolField(2, isStderr)
        w.stringField(3, text)
    }

    public static func decode(_ r: inout ProtoReader) throws -> LogLine {
        var m = LogLine()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .varint): m.tsUnix = Int64(bitPattern: try r.readVarint())
            case (2, .varint): m.isStderr = try r.readBool()
            case (3, .lengthDelimited): m.text = try r.readString()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct LogsChunk: Sendable, Equatable {
    public var containerId = ""
    public var streamId = ""
    public var lines: [LogLine] = []
    public var eof = false

    public init() {}
    public init(containerId: String, streamId: String, lines: [LogLine], eof: Bool) {
        self.containerId = containerId
        self.streamId = streamId
        self.lines = lines
        self.eof = eof
    }

    public func encode(into w: inout ProtoWriter) {
        w.stringField(1, containerId)
        w.stringField(2, streamId)
        for l in lines { w.messageField(3) { l.encode(into: &$0) } }
        w.boolField(4, eof)
    }

    public static func decode(_ r: inout ProtoReader) throws -> LogsChunk {
        var m = LogsChunk()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .lengthDelimited): m.containerId = try r.readString()
            case (2, .lengthDelimited): m.streamId = try r.readString()
            case (3, .lengthDelimited):
                var sub = try r.subReader()
                m.lines.append(try LogLine.decode(&sub))
            case (4, .varint): m.eof = try r.readBool()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

public struct EventAlert: Sendable, Equatable {
    public var kind = EventKind.unknown
    public var containerId = ""
    public var containerName = ""
    public var atUnix: Int64 = 0
    public var message = ""

    public init() {}
    public init(kind: EventKind, containerId: String, containerName: String, atUnix: Int64, message: String) {
        self.kind = kind
        self.containerId = containerId
        self.containerName = containerName
        self.atUnix = atUnix
        self.message = message
    }

    public func encode(into w: inout ProtoWriter) {
        w.enumField(1, kind.rawValue)
        w.stringField(2, containerId)
        w.stringField(3, containerName)
        w.int64Field(4, atUnix)
        w.stringField(5, message)
    }

    public static func decode(_ r: inout ProtoReader) throws -> EventAlert {
        var m = EventAlert()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .varint): m.kind = EventKind(rawValue: Int(try r.readVarint())) ?? .unknown
            case (2, .lengthDelimited): m.containerId = try r.readString()
            case (3, .lengthDelimited): m.containerName = try r.readString()
            case (4, .varint): m.atUnix = Int64(bitPattern: try r.readVarint())
            case (5, .lengthDelimited): m.message = try r.readString()
            default: try r.skip(wire)
            }
        }
        return m
    }
}

// MARK: - Envelope (Msg)

public enum PulsePayload: Sendable, Equatable {
    case hello(Hello)
    case helloAck(HelloAck)
    case metrics(MetricsReport)
    case containerList(ContainerList)
    case containerAction(ContainerAction)
    case containerActionResult(ContainerActionResult)
    case logsRequest(LogsRequest)
    case logsChunk(LogsChunk)
    case eventAlert(EventAlert)
    case ping(seq: UInt64)
    case pong(seq: UInt64)
}

/// Конверт `pulse.v1.Msg`.
public struct PulseMsg: Sendable, Equatable {
    public var id: UInt64 = 0
    public var target = ""
    public var payload: PulsePayload?

    public init() {}
    public init(id: UInt64 = 0, target: String = "", payload: PulsePayload?) {
        self.id = id
        self.target = target
        self.payload = payload
    }

    public func encode() -> Data {
        var w = ProtoWriter()
        w.varintField(1, id)
        w.stringField(12, target)
        switch payload {
        case .hello(let m): w.messageField(101) { m.encode(into: &$0) }
        case .helloAck(let m): w.messageField(102) { m.encode(into: &$0) }
        case .metrics(let m): w.messageField(103) { m.encode(into: &$0) }
        case .containerList(let m): w.messageField(104) { m.encode(into: &$0) }
        case .containerAction(let m): w.messageField(105) { m.encode(into: &$0) }
        case .containerActionResult(let m): w.messageField(106) { m.encode(into: &$0) }
        case .logsRequest(let m): w.messageField(107) { m.encode(into: &$0) }
        case .logsChunk(let m): w.messageField(108) { m.encode(into: &$0) }
        case .eventAlert(let m): w.messageField(109) { m.encode(into: &$0) }
        case .ping(let seq): w.messageField(110) { $0.varintField(1, seq) }
        case .pong(let seq): w.messageField(111) { $0.varintField(1, seq) }
        case nil: break
        }
        return Data(w.bytes)
    }

    private static func decodeSequence(_ r: inout ProtoReader) throws -> UInt64 {
        var seq: UInt64 = 0
        while let (field, wire) = try r.nextTag() {
            if field == 1 && wire == .varint { seq = try r.readVarint() }
            else { try r.skip(wire) }
        }
        return seq
    }

    public static func decode(_ data: Data) throws -> PulseMsg {
        var r = ProtoReader(data)
        var m = PulseMsg()
        while let (field, wire) = try r.nextTag() {
            switch (field, wire) {
            case (1, .varint): m.id = try r.readVarint()
            case (12, .lengthDelimited): m.target = try r.readString()
            case (101, .lengthDelimited): var sub = try r.subReader(); m.payload = .hello(try Hello.decode(&sub))
            case (102, .lengthDelimited): var sub = try r.subReader(); m.payload = .helloAck(try HelloAck.decode(&sub))
            case (103, .lengthDelimited): var sub = try r.subReader(); m.payload = .metrics(try MetricsReport.decode(&sub))
            case (104, .lengthDelimited): var sub = try r.subReader(); m.payload = .containerList(try ContainerList.decode(&sub))
            case (105, .lengthDelimited): var sub = try r.subReader(); m.payload = .containerAction(try ContainerAction.decode(&sub))
            case (106, .lengthDelimited): var sub = try r.subReader(); m.payload = .containerActionResult(try ContainerActionResult.decode(&sub))
            case (107, .lengthDelimited): var sub = try r.subReader(); m.payload = .logsRequest(try LogsRequest.decode(&sub))
            case (108, .lengthDelimited): var sub = try r.subReader(); m.payload = .logsChunk(try LogsChunk.decode(&sub))
            case (109, .lengthDelimited): var sub = try r.subReader(); m.payload = .eventAlert(try EventAlert.decode(&sub))
            case (110, .lengthDelimited): var sub = try r.subReader(); m.payload = .ping(seq: try decodeSequence(&sub))
            case (111, .lengthDelimited): var sub = try r.subReader(); m.payload = .pong(seq: try decodeSequence(&sub))
            case (110, .varint): m.payload = .ping(seq: try r.readVarint())
            case (111, .varint): m.payload = .pong(seq: try r.readVarint())
            default: try r.skip(wire)
            }
        }
        return m
    }
}
