import Foundation

enum ContentTypes {
    static let allTypes = ["link", "image", "video", "audio", "document", "text", "archive", "apk", "contact", "other"]

    static func label(for type: String) -> String {
        switch type {
        case "link":     return "Links"
        case "image":    return "Images"
        case "video":    return "Videos"
        case "audio":    return "Audio"
        case "document": return "Documents"
        case "text":     return "Text"
        case "archive":  return "Archives"
        case "apk":      return "Apps"
        case "contact":  return "Contacts"
        default:         return "Other"
        }
    }

    static func extractDomain(_ url: String) -> String {
        guard let host = URL(string: url)?.host else { return "" }
        if host.hasPrefix("www.") { return String(host.dropFirst(4)) }
        return host
    }

    static func linkSubtype(_ url: String) -> String {
        let domain = extractDomain(url)
        func matches(_ base: String) -> Bool {
            domain == base || domain.hasSuffix("." + base)
        }
        if matches("youtube.com") || matches("youtu.be") { return "YouTube" }
        if matches("google.com") && url.contains("maps")  { return "Maps" }
        if matches("twitter.com") || matches("x.com")     { return "Twitter/X" }
        if matches("instagram.com")                        { return "Instagram" }
        if matches("reddit.com")                           { return "Reddit" }
        if matches("github.com")                           { return "GitHub" }
        if matches("spotify.com")                          { return "Spotify" }
        if matches("tiktok.com")                           { return "TikTok" }
        return domain
    }
}
