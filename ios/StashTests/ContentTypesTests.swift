import XCTest
@testable import Stash

final class ContentTypesTests: XCTestCase {

    func testAllTypesOrder() {
        XCTAssertEqual(ContentTypes.allTypes, [
            "link", "image", "video", "audio", "document",
            "text", "archive", "apk", "contact", "other"
        ])
    }

    func testLabelForEachKnownType() {
        let expected: [String: String] = [
            "link": "Links",
            "image": "Images",
            "video": "Videos",
            "audio": "Audio",
            "document": "Documents",
            "text": "Text",
            "archive": "Archives",
            "apk": "Apps",
            "contact": "Contacts",
            "other": "Other",
        ]
        for (type, label) in expected {
            XCTAssertEqual(ContentTypes.label(for: type), label, "label(for: \"\(type)\")")
        }
    }

    func testLabelForUnknownType() {
        XCTAssertEqual(ContentTypes.label(for: "zzz"), "Other")
    }

    func testExtractDomainStripsWww() {
        XCTAssertEqual(ContentTypes.extractDomain("https://www.youtube.com/watch?v=abc"), "youtube.com")
    }

    func testExtractDomainNoWww() {
        XCTAssertEqual(ContentTypes.extractDomain("https://github.com/user/repo"), "github.com")
    }

    func testExtractDomainInvalid() {
        XCTAssertEqual(ContentTypes.extractDomain("not a url"), "")
    }

    func testLinkSubtypeYouTube() {
        XCTAssertEqual(ContentTypes.linkSubtype("https://youtu.be/dQw4w9WgXcQ"), "YouTube")
        XCTAssertEqual(ContentTypes.linkSubtype("https://www.youtube.com/watch?v=abc"), "YouTube")
    }

    func testLinkSubtypeGitHub() {
        XCTAssertEqual(ContentTypes.linkSubtype("https://github.com/octocat/Hello-World"), "GitHub")
    }

    func testLinkSubtypeTwitterX() {
        XCTAssertEqual(ContentTypes.linkSubtype("https://x.com/user"), "Twitter/X")
        XCTAssertEqual(ContentTypes.linkSubtype("https://twitter.com/user"), "Twitter/X")
    }

    func testLinkSubtypeUnknown() {
        XCTAssertEqual(ContentTypes.linkSubtype("https://example.com/page"), "example.com")
    }

    func testLinkSubtypeSuffixMatchSubdomain() {
        // hasSuffix(".youtube.com") path — not an exact match, so exercises the second half of matches()
        XCTAssertEqual(ContentTypes.linkSubtype("https://m.youtube.com/watch?v=abc"), "YouTube")
        XCTAssertEqual(ContentTypes.linkSubtype("https://open.spotify.com/track/123"), "Spotify")
        XCTAssertEqual(ContentTypes.linkSubtype("https://gist.github.com/user/abc"), "GitHub")
        XCTAssertEqual(ContentTypes.linkSubtype("https://mobile.twitter.com/user"), "Twitter/X")
    }

    func testLinkSubtypeMaps() {
        XCTAssertEqual(ContentTypes.linkSubtype("https://www.google.com/maps/place/Eiffel+Tower"), "Maps")
        XCTAssertEqual(ContentTypes.linkSubtype("https://maps.google.com/"), "Maps")
        // google.com without "maps" in the path is not Maps
        XCTAssertNotEqual(ContentTypes.linkSubtype("https://www.google.com/search?q=test"), "Maps")
    }

    func testLinkSubtypeOtherBrands() {
        XCTAssertEqual(ContentTypes.linkSubtype("https://www.instagram.com/p/abc"), "Instagram")
        XCTAssertEqual(ContentTypes.linkSubtype("https://www.reddit.com/r/swift"), "Reddit")
        XCTAssertEqual(ContentTypes.linkSubtype("https://www.tiktok.com/@user"), "TikTok")
    }

    func testExtractDomainEmptyString() {
        XCTAssertEqual(ContentTypes.extractDomain(""), "")
    }
}
