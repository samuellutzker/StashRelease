import XCTest
@testable import Stash

final class FilteringTests: XCTestCase {

    private func makeItem(id: String, type: String, title: String = "", content: String = "") -> SyncItem {
        SyncItem(id: id, title: title, content: content, type: type,
                 mimeType: "", isPinned: false, createdAt: 1000, updatedAt: 1000,
                 hasFile: false, fileHash: "", thumbnailB64: "")
    }

    private var sampleItems: [SyncItem] {
        [
            makeItem(id: "1", type: "link",  title: "SwiftUI Docs", content: "https://developer.apple.com"),
            makeItem(id: "2", type: "image", title: "Photo"),
            makeItem(id: "3", type: "text",  title: "My Note",      content: "Hello world"),
            makeItem(id: "4", type: "video", title: "Clip"),
            makeItem(id: "5", type: "weirdtype", title: "Unknown"),
        ]
    }

    func testNilTypeBlankSearchReturnsAll() {
        let result = filterItems(sampleItems, type: nil, search: "")
        XCTAssertEqual(result.count, sampleItems.count)
    }

    func testFilterByTypeMatchesExact() {
        let result = filterItems(sampleItems, type: "link", search: "")
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.first?.id, "1")
    }

    func testOtherChipMatchesUnknownTypes() {
        let result = filterItems(sampleItems, type: "other", search: "")
        // "weirdtype" is not in ContentTypes.allTypes, so it matches "other"
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.first?.id, "5")
    }

    func testSearchMatchesTitleCaseInsensitive() {
        let result = filterItems(sampleItems, type: nil, search: "swiftui")
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.first?.id, "1")
    }

    func testSearchMatchesContent() {
        let result = filterItems(sampleItems, type: nil, search: "hello")
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.first?.id, "3")
    }

    func testTypeAndSearchCombined() {
        let result = filterItems(sampleItems, type: "text", search: "note")
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.first?.id, "3")
    }

    func testTypeAndSearchCombinedNoMatch() {
        // type=link but search matches a text item → no results
        let result = filterItems(sampleItems, type: "link", search: "hello")
        XCTAssertTrue(result.isEmpty)
    }

    func testOtherChipExcludesKnownTypes() {
        // "other" filter must NOT return link/image/text/video — only truly unknown types
        let result = filterItems(sampleItems, type: "other", search: "")
        let ids = result.map { $0.id }
        XCTAssertFalse(ids.contains("1"), "link must not appear under 'other' chip")
        XCTAssertFalse(ids.contains("2"), "image must not appear under 'other' chip")
        XCTAssertFalse(ids.contains("3"), "text must not appear under 'other' chip")
        XCTAssertTrue(ids.contains("5"),  "weirdtype must appear under 'other' chip")
    }
}
