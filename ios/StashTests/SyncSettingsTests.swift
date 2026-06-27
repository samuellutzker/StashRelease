import XCTest
@testable import Stash

final class SyncSettingsTests: XCTestCase {

    private let d = UserDefaults.standard

    override func setUp() {
        // Start each test with a clean slate for all keys we touch.
        d.removeObject(forKey: "stash.autodownload")
        d.removeObject(forKey: "stash.tls")
        d.removeObject(forKey: "stash.certfp")
        d.removeObject(forKey: "stash.host")
        d.removeObject(forKey: "stash.pwhash")
        d.removeObject(forKey: "stash.port")
    }

    override func tearDown() {
        d.removeObject(forKey: "stash.autodownload")
        d.removeObject(forKey: "stash.tls")
        d.removeObject(forKey: "stash.certfp")
        d.removeObject(forKey: "stash.host")
        d.removeObject(forKey: "stash.pwhash")
        d.removeObject(forKey: "stash.port")
    }

    func testAutoDownloadDefaultsFalse() {
        XCTAssertFalse(SyncSettings.autoDownload)
    }

    func testAutoDownloadPersists() {
        SyncSettings.autoDownload = true
        XCTAssertTrue(SyncSettings.autoDownload)
        SyncSettings.autoDownload = false
        XCTAssertFalse(SyncSettings.autoDownload)
    }

    func testUseTlsClearsCertFingerprintWhenDisabled() {
        SyncSettings.certFingerprint = "AA:BB:CC"
        SyncSettings.useTls = true
        XCTAssertEqual(SyncSettings.certFingerprint, "AA:BB:CC", "fingerprint must survive enabling TLS")
        SyncSettings.useTls = false
        XCTAssertEqual(SyncSettings.certFingerprint, "", "fingerprint must be cleared when TLS is disabled")
    }

    func testWebSocketURLUsesWssWhenTLSEnabled() {
        SyncSettings.host = "192.168.1.10"
        SyncSettings.port = 9876
        SyncSettings.useTls = true
        XCTAssertEqual(SyncSettings.webSocketURL?.scheme, "wss")
    }

    func testWebSocketURLUsesWsWhenTLSDisabled() {
        SyncSettings.host = "192.168.1.10"
        SyncSettings.port = 9876
        SyncSettings.useTls = false
        XCTAssertEqual(SyncSettings.webSocketURL?.scheme, "ws")
    }

    func testWebSocketURLDefaultPort() {
        SyncSettings.host = "myserver"
        // port key not set — default is 9876
        XCTAssertEqual(SyncSettings.webSocketURL?.port, 9876)
    }

    func testWebSocketURLNilWhenHostEmpty() {
        XCTAssertNil(SyncSettings.webSocketURL)
    }

    func testIsConfiguredFalseWhenHostEmpty() {
        SyncSettings.passwordHash = "somehash"
        XCTAssertFalse(SyncSettings.isConfigured)
    }

    func testIsConfiguredFalseWhenPasswordEmpty() {
        SyncSettings.host = "myserver"
        XCTAssertFalse(SyncSettings.isConfigured)
    }

    func testIsConfiguredTrueWhenBothSet() {
        SyncSettings.host = "myserver"
        SyncSettings.passwordHash = "somehash"
        XCTAssertTrue(SyncSettings.isConfigured)
    }
}
