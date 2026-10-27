import Citadel
import Foundation
import NIOCore
import NIOSSH

/// Интерактивный SSH-терминал (PTY) — прямое соединение с сервером, релею не нужен.
/// Пиннинг host key по сохраненному отпечатку (пустой = принять).
public final class SSHTerminal: @unchecked Sendable {
    public var onOutput: (@Sendable (String) -> Void)?
    public var onError: (@Sendable (String) -> Void)?

    private let lock = NSLock()
    private var sessionTask: Task<Void, Never>?
    private var writer: TTYStdinWriter?
    private var client: SSHClient?
    private var closed = false

    public init() {}

    /// Подключается и запускает shell в PTY (vt100, 120x32 — как в Android).
    public func connect(host: String, port: Int, username: String, auth: SSHAuth,
                        pinnedFingerprint: String) async throws {
        let method = try auth.authenticationMethod(username: username)
        let delegate = SSHHostKeyDelegate(pinnedFingerprint: pinnedFingerprint, prompt: nil)
        var settings = SSHClientSettings(
            host: host,
            port: port,
            authenticationMethod: { method },
            hostKeyValidator: .custom(delegate)
        )
        settings.connectTimeout = TimeAmount.seconds(15)
        let client = try await SSHClient.connect(to: settings)
        lock.lock()
        self.client = client
        lock.unlock()

        let request = SSHChannelRequestEvent.PseudoTerminalRequest(
            wantReply: true,
            term: "vt100",
            terminalCharacterWidth: 120,
            terminalRowHeight: 32,
            terminalPixelWidth: 0,
            terminalPixelHeight: 0,
            terminalModes: SSHTerminalModes([:])
        )

        let task = Task { [weak self] in
            do {
                try await client.withPTY(request) { output, stdin in
                    self?.setWriter(stdin)
                    for try await event in output {
                        switch event {
                        case .stdout(let buffer):
                            self?.emit(String(decoding: buffer.readableBytesView, as: UTF8.self))
                        case .stderr(let buffer):
                            self?.emit(String(decoding: buffer.readableBytesView, as: UTF8.self))
                        }
                    }
                }
                self?.finish(error: nil)
            } catch {
                self?.finish(error: error)
            }
        }
        lock.lock()
        sessionTask = task
        lock.unlock()
    }

    /// Строка команды: терминалу отправляется с переводом строки.
    public func send(line: String) {
        lock.lock()
        let writer = writer
        lock.unlock()
        guard let writer else { return }
        let text = line + "\r\n"
        Task {
            try? await writer.write(ByteBuffer(string: text))
        }
    }

    public func shutdown() {
        lock.lock()
        if closed { lock.unlock(); return }
        closed = true
        let client = client
        self.client = nil
        lock.unlock()
        sessionTask?.cancel()
        if let client {
            Task { try? await client.close() }
        }
    }

    private func setWriter(_ w: TTYStdinWriter) {
        lock.lock()
        writer = w
        lock.unlock()
    }

    private func emit(_ text: String) {
        onOutput?(text)
    }

    private func finish(error: Error?) {
        if let error {
            onError?(String(describing: error))
        }
        lock.lock()
        closed = true
        lock.unlock()
    }
}
