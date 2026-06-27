import XCTest
@testable import Stash

final class SyncItemTests: XCTestCase {

    func testEffectiveUpdatedAtFallsBackToCreatedAt() {
        let item = SyncItem(id: "x", title: "", content: "", type: "text",
                            createdAt: 900, updatedAt: 0)
        XCTAssertEqual(item.effectiveUpdatedAt, 900,
            "effectiveUpdatedAt must return createdAt when updatedAt is 0")
    }

    func testEffectiveUpdatedAtPrefersUpdatedAt() {
        let item = SyncItem(id: "x", title: "", content: "", type: "text",
                            createdAt: 500, updatedAt: 800)
        XCTAssertEqual(item.effectiveUpdatedAt, 800)
    }

    func testIsLinkOrTextTrueForLinkAndText() {
        XCTAssertTrue(SyncItem(id: "a", title: "", content: "", type: "link",
                               createdAt: 0, updatedAt: 0).isLinkOrText)
        XCTAssertTrue(SyncItem(id: "b", title: "", content: "", type: "text",
                               createdAt: 0, updatedAt: 0).isLinkOrText)
    }

    func testIsLinkOrTextFalseForFileTypes() {
        for type in ["image", "video", "audio", "document", "archive", "apk", "contact", "other"] {
            XCTAssertFalse(SyncItem(id: type, title: "", content: "", type: type,
                                    createdAt: 0, updatedAt: 0).isLinkOrText,
                           "isLinkOrText must be false for type '\(type)'")
        }
    }

    func testDecodeToleratesMissingFields() throws {
        // A minimal JSON with only the required `id` field must decode without throwing,
        // using the graceful defaults from the custom init(from:).
        let data = Data(#"{"id":"min-id"}"#.utf8)
        let item = try JSONDecoder().decode(SyncItem.self, from: data)
        XCTAssertEqual(item.id, "min-id")
        XCTAssertEqual(item.type, "other", "missing type must default to 'other'")
        XCTAssertEqual(item.title, "")
        XCTAssertEqual(item.content, "")
        XCTAssertFalse(item.isPinned)
        XCTAssertEqual(item.createdAt, 0)
        XCTAssertEqual(item.updatedAt, 0)
        XCTAssertFalse(item.hasFile)
        XCTAssertEqual(item.fileHash, "")
        XCTAssertEqual(item.thumbnailB64, "")
    }

    func testDecodeCodingKeysSnakeCase() throws {
        // Verifies that the wire-format snake_case keys map correctly to Swift properties.
        // A regression here would silently desync iOS from the Rust/Android wire format.
        let json = #"""
        {
            "id": "wire-id",
            "title": "My Title",
            "content": "some content",
            "type": "image",
            "mime_type": "image/jpeg",
            "is_pinned": true,
            "created_at": 1234567890,
            "updated_at": 9876543210,
            "has_file": true,
            "file_hash": "abcdef",
            "thumbnail_b64": "base64data"
        }
        """#
        let item = try JSONDecoder().decode(SyncItem.self, from: Data(json.utf8))
        XCTAssertEqual(item.id, "wire-id")
        XCTAssertEqual(item.mimeType, "image/jpeg")
        XCTAssertTrue(item.isPinned)
        XCTAssertEqual(item.createdAt, 1234567890)
        XCTAssertEqual(item.updatedAt, 9876543210)
        XCTAssertTrue(item.hasFile)
        XCTAssertEqual(item.fileHash, "abcdef")
        XCTAssertEqual(item.thumbnailB64, "base64data")
    }

    func testEncodeProducesSnakeCaseKeys() throws {
        let item = SyncItem(id: "enc-id", title: "T", content: "C", type: "link",
                            mimeType: "text/html", isPinned: true,
                            createdAt: 111, updatedAt: 222,
                            hasFile: true, fileHash: "hash", thumbnailB64: "tb64")
        let data = try JSONEncoder().encode(item)
        let obj = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertNotNil(obj["mime_type"],   "must encode as mime_type")
        XCTAssertNotNil(obj["is_pinned"],   "must encode as is_pinned")
        XCTAssertNotNil(obj["created_at"],  "must encode as created_at")
        XCTAssertNotNil(obj["updated_at"],  "must encode as updated_at")
        XCTAssertNotNil(obj["has_file"],    "must encode as has_file")
        XCTAssertNotNil(obj["file_hash"],   "must encode as file_hash")
        XCTAssertNotNil(obj["thumbnail_b64"], "must encode as thumbnail_b64")
        XCTAssertNil(obj["mimeType"],       "camelCase key must not appear")
    }
}
