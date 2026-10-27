import Foundation
import Security

/// Пиннинг TLS-сертификата релея: храним PEM самоподписанного leaf-сертификата
/// и доверяем только ему (полный equality, как в Android-клиенте; hostname
/// не проверяем — якорем является сам сертификат).
public enum PinnedTrust {
    public static func certificate(fromPem pem: String) -> SecCertificate? {
        let body = pem
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("-----") }
            .joined()
        guard let der = Data(base64Encoded: body, options: [.ignoreUnknownCharacters]) else { return nil }
        return SecCertificateCreateWithData(nil, der as CFData)
    }

    /// true — доверять; false — разрывать соединение.
    public static func evaluate(serverTrust: SecTrust, pinnedPem: String?) -> Bool {
        guard let pem = pinnedPem, !pem.isEmpty else { return true } // без пина — системное доверие
        guard let cert = certificate(fromPem: pem) else { return false }
        SecTrustSetAnchorCertificates(serverTrust, [cert] as NSArray)
        SecTrustSetAnchorCertificatesOnly(serverTrust, true)
        // Политика SSL без hostname: якорем служит сам pinned-сертификат.
        let policy = SecPolicyCreateSSL(true, nil)
        SecTrustSetPolicies(serverTrust, policy)
        var cfError: CFError?
        return SecTrustEvaluateWithError(serverTrust, &cfError)
    }
}

/// Делегат для URLSession (и WebSocket, и REST): применяет пиннинг.
final class PinnedTrustDelegate: NSObject, URLSessionTaskDelegate, URLSessionWebSocketDelegate, @unchecked Sendable {
    let pinnedPem: String?
    var onOpen: (@Sendable () -> Void)?
    var onClose: (@Sendable (Error?) -> Void)?

    init(pinnedPem: String?) {
        self.pinnedPem = pinnedPem
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        if PinnedTrust.evaluate(serverTrust: trust, pinnedPem: pinnedPem) {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol protocol: String?) {
        onOpen?()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        onClose?(nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        // didComplete вызывается и после штатного close — трактуем как закрытие.
        onClose?(error)
    }
}

/// URLSession с пиннингом для REST-запросов к релею.
public final class PinnedHTTP: @unchecked Sendable {
    public let session: URLSession

    public init(certPem: String?) {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        self.session = URLSession(configuration: config,
                                  delegate: PinnedTrustDelegate(pinnedPem: certPem),
                                  delegateQueue: nil)
    }

    deinit { session.finishTasksAndInvalidate() }
}
