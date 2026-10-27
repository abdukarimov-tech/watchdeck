import Foundation

/// Минимальный кодек protobuf wire format — ровно то подмножество, которое
/// используется контрактом proto/pulse.proto (proto3, без unknown-group логики).
/// Пишем сами вместо SwiftProtobuf: набор сообщений фиксирован и мал,
/// а внешних зависимостей у проекта быть не должно.
public enum WireType: UInt8, Sendable {
    case varint = 0
    case fixed64 = 1
    case lengthDelimited = 2
    case fixed32 = 5
}

public enum ProtoError: Error, Sendable {
    case truncated
    case invalidWireType(UInt64)
    case invalidUTF8
}

public struct ProtoWriter: Sendable {
    public private(set) var bytes = [UInt8]()

    public init() {}

    /// Дописать сырые байты (для ручной сборки в тестах).
    public mutating func appendRaw(_ raw: [UInt8]) {
        bytes.append(contentsOf: raw)
    }

    public mutating func rawVarint(_ value: UInt64) {
        var v = value
        while true {
            if v < 0x80 {
                bytes.append(UInt8(truncatingIfNeeded: v))
                return
            }
            bytes.append(UInt8(truncatingIfNeeded: v & 0x7F) | 0x80)
            v >>= 7
        }
    }

    mutating func tag(_ field: Int, _ wire: WireType) {
        rawVarint(UInt64(field) << 3 | UInt64(wire.rawValue))
    }

    /// proto3: значение по умолчанию не пишется.
    public mutating func varintField(_ field: Int, _ value: UInt64) {
        guard value != 0 else { return }
        tag(field, .varint)
        rawVarint(value)
    }

    public mutating func int64Field(_ field: Int, _ value: Int64) {
        guard value != 0 else { return }
        tag(field, .varint)
        rawVarint(UInt64(bitPattern: value))
    }

    public mutating func boolField(_ field: Int, _ value: Bool) {
        guard value else { return }
        tag(field, .varint)
        rawVarint(1)
    }

    public mutating func enumField(_ field: Int, _ value: Int) {
        guard value != 0 else { return }
        tag(field, .varint)
        rawVarint(UInt64(value))
    }

    public mutating func doubleField(_ field: Int, _ value: Double) {
        guard value != 0 else { return }
        tag(field, .fixed64)
        withUnsafeBytes(of: value.bitPattern.littleEndian) { bytes.append(contentsOf: $0) }
    }

    public mutating func packedDoubleField(_ field: Int, _ values: [Double]) {
        guard !values.isEmpty else { return }
        tag(field, .lengthDelimited)
        var payload = ProtoWriter()
        for v in values {
            withUnsafeBytes(of: v.bitPattern.littleEndian) { payload.bytes.append(contentsOf: $0) }
        }
        rawVarint(UInt64(payload.bytes.count))
        bytes.append(contentsOf: payload.bytes)
    }

    public mutating func stringField(_ field: Int, _ value: String) {
        guard !value.isEmpty else { return }
        bytesField(field, Array(value.utf8))
    }

    public mutating func bytesField(_ field: Int, _ value: [UInt8]) {
        guard !value.isEmpty else { return }
        tag(field, .lengthDelimited)
        rawVarint(UInt64(value.count))
        bytes.append(contentsOf: value)
    }

    /// Вложенное сообщение пишется всегда, даже пустое (в proto3 присутствие
    /// под-сообщения значимо, кодировать надо tag + len=0).
    public mutating func messageField(_ field: Int, _ encode: (inout ProtoWriter) -> Void) {
        tag(field, .lengthDelimited)
        var payload = ProtoWriter()
        encode(&payload)
        rawVarint(UInt64(payload.bytes.count))
        bytes.append(contentsOf: payload.bytes)
    }
}

public struct ProtoReader: Sendable {
    public let bytes: [UInt8]
    public var offset = 0

    public init(_ data: Data) {
        self.bytes = [UInt8](data)
    }

    public init(_ bytes: [UInt8]) {
        self.bytes = bytes
    }

    /// Возвращает nil, когда вход дочитан до конца.
    public mutating func nextTag() throws -> (field: Int, wire: WireType)? {
        guard offset < bytes.count else { return nil }
        let key = try readVarint()
        guard let wire = WireType(rawValue: UInt8(truncatingIfNeeded: key & 7)) else {
            throw ProtoError.invalidWireType(key & 7)
        }
        return (Int(key >> 3), wire)
    }

    public mutating func readVarint() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while true {
            guard offset < bytes.count else { throw ProtoError.truncated }
            let b = bytes[offset]
            offset += 1
            result |= UInt64(b & 0x7F) << shift
            if b < 0x80 { return result }
            shift += 7
            if shift >= 64 { throw ProtoError.truncated }
        }
    }

    public mutating func readFixed64() throws -> UInt64 {
        guard offset + 8 <= bytes.count else { throw ProtoError.truncated }
        var v: UInt64 = 0
        for i in (0..<8).reversed() {
            v = (v << 8) | UInt64(bytes[offset + i])
        }
        offset += 8
        return v
    }

    public mutating func readFixed32() throws -> UInt32 {
        guard offset + 4 <= bytes.count else { throw ProtoError.truncated }
        var v: UInt32 = 0
        for i in (0..<4).reversed() {
            v = (v << 8) | UInt32(bytes[offset + i])
        }
        offset += 4
        return v
    }

    public mutating func readBytes() throws -> [UInt8] {
        let length = Int(try readVarint())
        guard length >= 0, offset + length <= bytes.count else { throw ProtoError.truncated }
        let slice = Array(bytes[offset..<offset + length])
        offset += length
        return slice
    }

    public mutating func subReader() throws -> ProtoReader {
        ProtoReader(try readBytes())
    }

    public mutating func skip(_ wire: WireType) throws {
        switch wire {
        case .varint: _ = try readVarint()
        case .fixed64: _ = try readFixed64()
        case .lengthDelimited: _ = try readBytes()
        case .fixed32: _ = try readFixed32()
        }
    }

    public mutating func readDouble() throws -> Double {
        Double(bitPattern: try readFixed64())
    }

    public mutating func readString() throws -> String {
        guard let s = String(bytes: try readBytes(), encoding: .utf8) else {
            throw ProtoError.invalidUTF8
        }
        return s
    }

    public mutating func readBool() throws -> Bool {
        try readVarint() != 0
    }
}
