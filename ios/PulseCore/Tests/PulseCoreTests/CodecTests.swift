import XCTest
@testable import PulseCore

final class CodecTests: XCTestCase {
    func testVarintRoundtrip() throws {
        let values: [UInt64] = [0, 1, 127, 128, 300, 880, 0xFFFFFFFF, UInt64.max]
        for v in values {
            var w = ProtoWriter()
            w.rawVarint(v)
            var r = ProtoReader(Data(w.bytes))
            XCTAssertEqual(try r.readVarint(), v)
            XCTAssertEqual(r.offset, w.bytes.count, "хвост после varint не ожидался")
        }
    }

    /// Ping{seq:1} — поле 110, wire varint: tag 880 → F0 06, затем 01.
    func testPingWireVector() {
        let msg = PulseMsg(payload: .ping(seq: 1))
        let data = msg.encode()
        XCTAssertEqual([UInt8](data), [0xF0, 0x06, 0x01])
    }

    func testEnvelopeRoundtrip() throws {
        var metrics = MetricsReport()
        metrics.cpuUsagePercent = 42.5
        metrics.cpuCores = 8
        metrics.loadAvg = [0.5, 1.5, 2.25]
        metrics.memTotal = 17_179_869_184
        metrics.memUsed = 8_589_934_592
        metrics.swapTotal = 2_147_483_648
        metrics.swapUsed = 1_073_741_824
        metrics.uptimeSeconds = 1_048_576
        metrics.disks = [DiskStat(mount: "/", total: 85_899_345_920, used: 46_179_869_184),
                         DiskStat(mount: "/var/lib/docker", total: 42_949_672_960, used: 33_285_996_544)]
        metrics.net = NetStat(rxBytesPerSec: 921_600, txBytesPerSec: 307_200)
        metrics.collectedAtUnix = 1_700_000_000

        var container = Container()
        container.id = "abc123"
        container.name = "web-nginx"
        container.image = "nginx:latest"
        container.state = .running
        container.status = "Up 3 days (healthy)"
        container.createdUnix = 1_699_000_000
        container.cpuPercent = 12.5
        container.memUsed = 536_870_912
        container.memLimit = 2_147_483_648

        let msg = PulseMsg(
            id: 7,
            target: "demo-vps",
            payload: .containerList(ContainerList(containers: [container]))
        )
        let decoded = try PulseMsg.decode(msg.encode())
        XCTAssertEqual(decoded, msg)

        let metricsMsg = PulseMsg(id: 3, target: "demo-vps", payload: .metrics(metrics))
        let decodedMetrics = try PulseMsg.decode(metricsMsg.encode())
        XCTAssertEqual(decodedMetrics, metricsMsg)
    }

    func testDefaultsOmitted() {
        var action = ContainerAction(containerId: "c1", kind: .restart)
        action.containerId = "c1"
        let data = [UInt8](PulseMsg(id: 1, target: "a", payload: .containerAction(action)).encode())
        // id=1: tag 0x08 0x01; target "a": tag 0x62 len 0x61; payload: tag (105<<3|2)=842 → F2 06 len…
        // kind=restart(3) не ноль → пишется; containerId "c1" пишется.
        XCTAssertFalse(data.isEmpty)
        let decoded = try! PulseMsg.decode(Data(data))
        XCTAssertEqual(decoded.id, 1)
        XCTAssertEqual(decoded.target, "a")
        guard case .containerAction(let a) = decoded.payload else { return XCTFail() }
        XCTAssertEqual(a.containerId, "c1")
        XCTAssertEqual(a.kind, .restart)
    }

    func testLogsChunkRoundtrip() throws {
        let chunk = LogsChunk(
            containerId: "web",
            streamId: "s-1",
            lines: [
                LogLine(tsUnix: 1_700_000_001, isStderr: false, text: "GET / 200"),
                LogLine(tsUnix: 1_700_000_002, isStderr: true, text: "ERROR boom"),
            ],
            eof: false
        )
        let msg = PulseMsg(id: 9, target: "agent-1", payload: .logsChunk(chunk))
        XCTAssertEqual(try PulseMsg.decode(msg.encode()), msg)
    }

    func testEventAlertRoundtrip() throws {
        let alert = EventAlert(kind: .containerDied, containerId: "batch",
                               containerName: "batch-worker", atUnix: 1_700_000_003, message: "exit code 137")
        let msg = PulseMsg(id: 0, target: "agent-1", payload: .eventAlert(alert))
        XCTAssertEqual(try PulseMsg.decode(msg.encode()), msg)
    }

    func testUnknownFieldsSkipped() throws {
        // Валидный protobuf с неизвестным полем (field 999, varint) до payload.
        var w = ProtoWriter()
        w.tag(999, .varint); w.rawVarint(5)
        var payload = ProtoWriter()
        var ack = HelloAck(agentId: "a1", relayVersion: "0.1.0")
        ack.encode(into: &payload)
        w.tag(102, .lengthDelimited)
        w.rawVarint(UInt64(payload.bytes.count))
        w.appendRaw(payload.bytes)
        let decoded = try PulseMsg.decode(Data(w.bytes))
        guard case .helloAck(let a) = decoded.payload else { return XCTFail() }
        XCTAssertEqual(a.agentId, "a1")
        XCTAssertEqual(a.relayVersion, "0.1.0")
    }

    func testFormatters() {
        XCTAssertEqual(PulseFormat.bytes(512), "512 B")
        XCTAssertEqual(PulseFormat.bytes(1024), "1 KiB")
        XCTAssertEqual(PulseFormat.bytes(1_572_864), "1.5 MiB")
        XCTAssertEqual(PulseFormat.bytes(2_147_483_648), "2 GiB")
        XCTAssertEqual(PulseFormat.uptime(45), "45s")
        XCTAssertEqual(PulseFormat.uptime(3_100), "51m 40s")
        XCTAssertEqual(PulseFormat.uptime(108_000), "1d 6h")
        XCTAssertEqual(PulseFormat.uptime(1_040_000), "12d 0h")
    }
}
