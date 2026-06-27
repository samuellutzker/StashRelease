import SwiftUI
import UIKit
import ImageIO

/// Epoch milliseconds, matching the server/Android timestamps.
func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

func symbol(for type: String) -> String {
    switch type {
    case "link": return "link"
    case "text": return "doc.text"
    case "image": return "photo"
    case "video": return "video"
    case "audio": return "music.note"
    case "document": return "doc.richtext"
    case "archive": return "archivebox"
    case "apk": return "shippingbox"
    case "contact": return "person.crop.circle"
    default: return "doc"
    }
}

/// Per-type accent colour — matches the Android palette so both apps read the same.
func typeColor(for type: String) -> Color {
    switch type {
    case "link": return Color(hex: 0x1976D2)
    case "image": return Color(hex: 0x7B1FA2)
    case "video": return Color(hex: 0xC62828)
    case "audio": return Color(hex: 0x00838F)
    case "document": return Color(hex: 0xE65100)
    case "text": return Color(hex: 0x2E7D32)
    case "archive": return Color(hex: 0x4527A0)
    case "apk": return Color(hex: 0x558B2F)
    case "contact": return Color(hex: 0x00695C)
    default: return Color(hex: 0x546E7A)
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}

// MARK: - Display model (files: filename = content, comment = title)

/// The headline name. For files that's the filename (carried in `content`); links/text use title.
func displayTitle(for item: SyncItem) -> String {
    if item.isLinkOrText { return item.title.isEmpty ? item.content : item.title }
    return item.content.isEmpty ? item.type.capitalized : item.content
}

/// The user's comment for a file (stored in `title`). Empty / equal-to-filename means "no comment".
func fileComment(for item: SyncItem) -> String {
    guard !item.isLinkOrText else { return "" }
    if item.title.isEmpty || item.title == item.content { return "" }
    return item.title
}

// MARK: - Dates

private let shortDateFormatter: DateFormatter = {
    let f = DateFormatter(); f.dateFormat = "MMM d, yyyy"; return f
}()
private let longDateFormatter: DateFormatter = {
    let f = DateFormatter(); f.dateStyle = .medium; f.timeStyle = .short; return f
}()
func shortDate(_ millis: Int64) -> String {
    shortDateFormatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
}
func longDate(_ millis: Int64) -> String {
    longDateFormatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
}

// MARK: - Thumbnails / previews

private func downsampledImage(atPath path: String, maxPixel: CGFloat) -> UIImage? {
    guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else { return nil }
    let opts: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: maxPixel,
    ]
    guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
    return UIImage(cgImage: cg)
}

private func decodedThumbB64(_ b64: String) -> UIImage? {
    guard !b64.isEmpty, let data = Data(base64Encoded: b64) else { return nil }
    return UIImage(data: data)
}

/// Small list thumbnail for image/video items, if we have pixels for it.
func rowThumbnail(for item: SyncItem, store: StashStore) -> UIImage? {
    if item.type == "image", store.hasLocalFile(item),
       let img = downsampledImage(atPath: store.fileURL(for: item.id).path, maxPixel: 160) {
        return img
    }
    return decodedThumbB64(item.thumbnailB64)
}

/// Larger preview image for the detail screen (local images full-ish; otherwise the server thumb).
func previewImage(for item: SyncItem, store: StashStore) -> UIImage? {
    if item.type == "image", store.hasLocalFile(item),
       let img = downsampledImage(atPath: store.fileURL(for: item.id).path, maxPixel: 2000) {
        return img
    }
    return decodedThumbB64(item.thumbnailB64)
}

// MARK: - Filtering (top-level so tests can import it directly)

/// Filter and search items. Both conditions are applied together (AND).
/// Items with a type not in `ContentTypes.allTypes` are treated as "other" for the "other" chip.
func filterItems(_ items: [SyncItem], type: String?, search: String) -> [SyncItem] {
    var result = items

    if let type {
        result = result.filter { item in
            if type == "other" {
                return item.type == "other" || !ContentTypes.allTypes.contains(item.type)
            }
            return item.type == type
        }
    }

    if !search.isEmpty {
        let q = search.lowercased()
        result = result.filter {
            $0.title.lowercased().contains(q) || $0.content.lowercased().contains(q)
        }
    }

    return result
}

// MARK: - ShareSheet

struct ShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// MARK: - Screens

struct ContentView: View {
    @EnvironmentObject var store: StashStore
    @EnvironmentObject var sync: SyncClient
    @State private var showSettings = false
    @State private var showAdd = false
    @State private var filterType: String? = nil
    @State private var searchText = ""
    @State private var itemToDelete: SyncItem? = nil
    @State private var shareItem: SyncItem? = nil

    private var displayedItems: [SyncItem] {
        let filtered = filterItems(store.items, type: filterType, search: searchText)
        return filtered.sorted {
            if $0.isPinned != $1.isPinned { return $0.isPinned }
            return $0.createdAt > $1.createdAt
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if !SyncSettings.isConfigured {
                    ContentUnavailableState(
                        title: "Not configured",
                        message: "Set the server address and password in Settings.",
                        systemImage: "gearshape"
                    )
                } else if store.items.isEmpty {
                    ContentUnavailableState(
                        title: "Nothing stashed yet",
                        message: "Add a note or link with +, or share into Stash.",
                        systemImage: "tray"
                    )
                } else {
                    VStack(spacing: 0) {
                        chipBar
                        if displayedItems.isEmpty {
                            ContentUnavailableState(
                                title: "No matches",
                                message: "Try a different search or filter.",
                                systemImage: "magnifyingglass"
                            )
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        } else {
                            List {
                                ForEach(displayedItems) { item in
                                    NavigationLink(value: item) { ItemRow(item: item) }
                                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                            Button(role: .destructive) {
                                                itemToDelete = item
                                            } label: {
                                                Label("Delete", systemImage: "trash")
                                            }
                                        }
                                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                            Button {
                                                shareItem = item
                                            } label: {
                                                Label("Share", systemImage: "square.and.arrow.up")
                                            }
                                            .tint(.green)
                                        }
                                }
                            }
                            .refreshable {
                                await withCheckedContinuation { cont in
                                    sync.restart()
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                                        cont.resume()
                                    }
                                }
                            }
                            .confirmationDialog("Delete this item?", isPresented: Binding(
                                get: { itemToDelete != nil },
                                set: { if !$0 { itemToDelete = nil } }
                            ), titleVisibility: .visible) {
                                Button("Delete", role: .destructive) {
                                    if let item = itemToDelete {
                                        sync.deleteItem(syncId: item.id)
                                    }
                                    itemToDelete = nil
                                }
                                Button("Cancel", role: .cancel) { itemToDelete = nil }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Stash")
            .navigationDestination(for: SyncItem.self) { ItemDetailView(item: $0) }
            .searchable(text: $searchText, prompt: "Search…")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    HStack(spacing: 8) {
                        StashLogo()
                            .frame(width: 26, height: 26)
                            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                        Circle()
                            .fill(sync.isConnected ? Color.green : Color.gray)
                            .frame(width: 9, height: 9)
                            .accessibilityLabel(sync.isConnected ? "Connected" : "Disconnected")
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { showAdd = true } label: { Image(systemName: "plus") }
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .sheet(isPresented: $showAdd) { AddItemView() }
            .sheet(item: $shareItem) { item in
                ShareSheet(activityItems: shareActivityItems(for: item))
            }
        }
    }

    @ViewBuilder private var chipBar: some View {
        if SyncSettings.isConfigured && !store.items.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    FilterChip(label: "All", color: .accentColor, isSelected: filterType == nil) {
                        filterType = nil
                    }
                    ForEach(ContentTypes.allTypes, id: \.self) { type in
                        FilterChip(
                            label: ContentTypes.label(for: type),
                            color: typeColor(for: type),
                            isSelected: filterType == type
                        ) {
                            filterType = (filterType == type) ? nil : type
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            Divider()
        }
    }

    private func shareActivityItems(for item: SyncItem) -> [Any] {
        if item.isLinkOrText {
            if item.type == "link", let url = URL(string: item.content) { return [url] }
            return [item.content]
        }
        if let url = store.namedFileURL(for: item) { return [url] }
        return [item.content]
    }
}

// MARK: - Filter chip

private struct FilterChip: View {
    let label: String
    let color: Color
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.caption)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(isSelected ? color.opacity(0.2) : Color.clear)
                .foregroundStyle(isSelected ? color : Color.secondary)
                .overlay(
                    Capsule().stroke(isSelected ? color : Color.secondary.opacity(0.4), lineWidth: 1)
                )
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct ItemRow: View {
    @EnvironmentObject var store: StashStore
    @EnvironmentObject var sync: SyncClient
    let item: SyncItem

    var body: some View {
        HStack(spacing: 12) {
            thumbnail
            VStack(alignment: .leading, spacing: 2) {
                Text(displayTitle(for: item)).lineLimit(1)
                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 8)
            if item.isPinned {
                Image(systemName: "pin.fill").font(.caption2).foregroundStyle(.orange)
            }
            transferIndicator
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder private var thumbnail: some View {
        if let img = rowThumbnail(for: item, store: store) {
            Image(uiImage: img).resizable().scaledToFill()
                .frame(width: 44, height: 44)
                .clipShape(RoundedRectangle(cornerRadius: 9))
                .overlay(alignment: .bottomTrailing) {
                    if item.type == "video" {
                        Image(systemName: "play.circle.fill")
                            .font(.caption).foregroundStyle(.white).padding(1)
                    }
                }
        } else {
            RoundedRectangle(cornerRadius: 9)
                .fill(typeColor(for: item.type))
                .frame(width: 44, height: 44)
                .overlay(Image(systemName: symbol(for: item.type)).foregroundStyle(.white))
        }
    }

    private var subtitle: String {
        if let p = sync.progress[item.id] {
            return p < 0 ? "Transfer failed" : "Transferring \(Int(p * 100))%"
        }
        let base: String
        if item.isLinkOrText {
            base = item.type == "link" ? "Link" : "Text"
        } else {
            let c = fileComment(for: item)
            base = c.isEmpty ? (store.hasLocalFile(item) ? "On device" : "In cloud") : c
        }
        return "\(base) · \(shortDate(item.createdAt))"
    }

    @ViewBuilder private var transferIndicator: some View {
        if let p = sync.progress[item.id] {
            if p < 0 {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
            } else {
                ProgressView(value: p).frame(width: 54)
            }
        } else if !item.isLinkOrText && !store.hasLocalFile(item) {
            Image(systemName: "icloud.and.arrow.down").foregroundStyle(.secondary)
        }
    }
}

/// The Stash mark, drawn to match the Android launcher icon: an item dropping into a tray, on
/// navy. Pure SwiftUI (no asset) so it scales crisply wherever it's placed.
struct StashLogo: View {
    var body: some View {
        GeometryReader { geo in
            // Map a centred 84-unit window of the 108 design viewport into the frame.
            let origin = 12.0, span = 84.0
            let k = min(geo.size.width, geo.size.height) / span
            let p = { (x: Double, y: Double) -> CGPoint in CGPoint(x: (x - origin) * k, y: (y - origin) * k) }
            ZStack {
                Color(hex: 0x1A237E)
                Path { path in
                    let pts: [(Double, Double)] = [(47,26),(61,26),(61,46),(69,46),(54,67),(39,46),(47,46)]
                    path.move(to: p(pts[0].0, pts[0].1))
                    for q in pts.dropFirst() { path.addLine(to: p(q.0, q.1)) }
                    path.closeSubpath()
                }
                .fill(LinearGradient(colors: [Color(hex: 0xFFC246), Color(hex: 0xFF8F00)],
                                     startPoint: .top, endPoint: .bottom))
                Path { path in
                    let pts: [(Double, Double)] = [(38,69),(44,81),(64,81),(70,69)]
                    path.move(to: p(pts[0].0, pts[0].1))
                    for q in pts.dropFirst() { path.addLine(to: p(q.0, q.1)) }
                }
                .stroke(Color.white, style: StrokeStyle(lineWidth: 6 * k, lineCap: .round, lineJoin: .round))
            }
        }
    }
}

/// Minimal stand-in for ContentUnavailableView (which is iOS 17+).
struct ContentUnavailableState: View {
    let title: String
    let message: String
    let systemImage: String
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage).font(.largeTitle).foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(message).font(.subheadline).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).padding(.horizontal, 40)
        }
    }
}
