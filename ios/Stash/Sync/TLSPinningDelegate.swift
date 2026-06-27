import Foundation

/// URLSession delegate that pins the server's self-signed certificate by SHA-256 fingerprint,
/// trust-on-first-use — the iOS counterpart of the Android `TlsTrust`. Home servers have no CA,
/// so we authenticate the exact certificate: the first one seen is remembered, and every later
/// connection must present the same cert (otherwise it's rejected — basic MITM protection). The
/// user can verify the pinned value against the fingerprint the server logs / shows in its web UI.
///
/// For plain `ws://` no server-trust challenge occurs, so this delegate is simply never consulted.
final class TLSPinningDelegate: NSObject, URLSessionDelegate {
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        guard let leaf = leafCertificate(trust) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let der = SecCertificateCopyData(leaf) as Data
        let fingerprint = StashCrypto.certFingerprint(der)
        let pinned = SyncSettings.certFingerprint

        if pinned.isEmpty {
            SyncSettings.certFingerprint = fingerprint                       // trust-on-first-use
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else if pinned.caseInsensitiveCompare(fingerprint) == .orderedSame {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)          // changed cert → refuse
        }
    }

    private func leafCertificate(_ trust: SecTrust) -> SecCertificate? {
        if #available(iOS 15.0, *) {
            return (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first
        } else {
            return SecTrustGetCertificateAtIndex(trust, 0)
        }
    }
}
