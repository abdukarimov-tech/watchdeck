import Citadel
import Crypto
import Foundation
import NIOCore
import NIOSSH

public enum SSHError: Error, Sendable, Equatable {
    case hostKeyRejected
    case unsupportedKeyFormat(String)
    case commandFailed(String)
}

/// Пароль или приватный ключ (OpenSSH PEM: ed25519 / RSA).
public enum SSHAuth: Sendable, Equatable {
    case password(String)
    case key(pem: String, passphrase: String?)
}

extension SSHAuth {
    func authenticationMethod(username: String) throws -> SSHAuthenticationMethod {
        switch self {
        case .password(let password):
            return .passwordBased(username: username, password: password)
        case .key(let pem, let passphrase):
            let decryptionKey = passphrase.map { Data($0.utf8) }
            if let key = try? Curve25519.Signing.PrivateKey(sshEd25519: pem, decryptionKey: decryptionKey) {
                return .ed25519(username: username, privateKey: key)
            }
            if let key = try? Insecure.RSA.PrivateKey(sshRsa: pem, decryptionKey: decryptionKey) {
                return .rsa(username: username, privateKey: key)
            }
            throw SSHError.unsupportedKeyFormat("ed25519 or RSA OpenSSH key required")
        }
    }
}

/// Отпечаток host key в формате OpenSSH (ssh-keygen -lf): SHA256 + base64 без паддинга
/// по SSH wire-кодировке публичного ключа.
public enum SSHHostKeyFingerprint {
    public static func of(_ hostKey: NIOSSHPublicKey) -> String? {
        var buffer = ByteBufferAllocator().buffer(capacity: 512)
        _ = hostKey.write(to: &buffer)
        guard let bytes = buffer.readBytes(length: buffer.readableBytes), !bytes.isEmpty else { return nil }
        let digest = SHA256.hash(data: Data(bytes))
        let base64 = Data(digest).base64EncodedString()
        return "SHA256:" + base64.trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }
}

/// Валидатор host key с пиннингом отпечатка и опциональным интерактивным
/// подтверждением (диалог в UI — как в OpenSSH: ждем решения пользователя).
final class SSHHostKeyDelegate: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
    let pinnedFingerprint: String
    let prompt: (@Sendable (String) async -> Bool)?
    private let lock = NSLock()
    private var accepted: String?

    /// - Parameters:
    ///   - pinnedFingerprint: непустой — строгая сверка с пином.
    ///   - prompt: колбэк подтверждения для первой встречи с сервером (пустой пин).
    init(pinnedFingerprint: String, prompt: (@Sendable (String) async -> Bool)?) {
        self.pinnedFingerprint = pinnedFingerprint
        self.prompt = prompt
    }

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        let fingerprint = SSHHostKeyFingerprint.of(hostKey) ?? ""
        if !pinnedFingerprint.isEmpty {
            if fingerprint == pinnedFingerprint {
                storeAccepted(fingerprint)
                validationCompletePromise.succeed(())
            } else {
                validationCompletePromise.fail(SSHError.hostKeyRejected)
            }
            return
        }
        guard let prompt else {
            // без пина и без промпта — принимаем (терминал, как в Android)
            storeAccepted(fingerprint)
            validationCompletePromise.succeed(())
            return
        }
        Task {
            let ok = await prompt(fingerprint)
            if ok {
                self.storeAccepted(fingerprint)
                validationCompletePromise.succeed(())
            } else {
                validationCompletePromise.fail(SSHError.hostKeyRejected)
            }
        }
    }

    private func storeAccepted(_ fp: String) {
        lock.lock()
        accepted = fp
        lock.unlock()
    }

    var acceptedFingerprint: String? {
        lock.lock()
        defer { lock.unlock() }
        return accepted
    }
}

enum SSHShell {
    static func quote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
