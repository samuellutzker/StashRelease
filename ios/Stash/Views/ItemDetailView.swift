import SwiftUI
import AVKit
import UIKit

struct ItemDetailView: View {
    @EnvironmentObject var store: StashStore
    @EnvironmentObject var sync: SyncClient
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    private let itemId: String
    @State private var editedText = ""
    @State private var isEditingText = false
    @State private var editedComment = ""
    @State private var isEditingComment = false
    @State private var showCopied = false
    @State private var showDeleteConfirm = false

    init(item: SyncItem) { self.itemId = item.id }

    /// Always read the live copy from the store so download/edit state stays current.
    private var item: SyncItem? { store.item(syncId: itemId) }

    var body: some View {
        Group {
            if let item {
                content(for: item)
            } else {
                Text("Item no longer available").foregroundStyle(.secondary)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let item {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { togglePin(item) } label: {
                        Image(systemName: item.isPinned ? "pin.slash" : "pin")
                    }
                    Button(role: .destructive) { showDeleteConfirm = true } label: {
                        Image(systemName: "trash")
                    }
                }
            }
        }
        .confirmationDialog("Delete this item?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            if let item {
                Button("Delete", role: .destructive) { delete(item) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $isEditingText) {
            if let item {
                NavigationStack {
                    TextEditor(text: $editedText)
                        .padding()
                        .navigationTitle("Edit note")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Cancel") { isEditingText = false }
                            }
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Save") { saveTextEdit(item) }
                                    .disabled(editedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            }
                        }
                }
            }
        }
        .sheet(isPresented: $isEditingComment) {
            if let item {
                NavigationStack {
                    Form {
                        TextField("Comment", text: $editedComment, axis: .vertical)
                            .lineLimit(3...6)
                    }
                    .navigationTitle("Edit comment")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { isEditingComment = false }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Save") { saveCommentEdit(item) }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func content(for item: SyncItem) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                preview(for: item)

                Text(displayTitle(for: item)).font(.title3).bold()

                Label(ContentTypes.label(for: item.type), systemImage: symbol(for: item.type))
                    .font(.caption)
                    .foregroundStyle(typeColor(for: item.type))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(typeColor(for: item.type).opacity(0.15), in: Capsule())

                Text(metaLine(for: item)).font(.caption).foregroundStyle(.secondary)

                if let p = sync.progress[item.id] {
                    if p < 0 {
                        Button { retry(item) } label: {
                            Label("Transfer failed — tap to retry", systemImage: "exclamationmark.triangle")
                        }.foregroundStyle(.red)
                    } else {
                        ProgressView(value: p) { Text("Transferring… \(Int(p * 100))%") }
                    }
                }

                switch item.type {
                case "link":  linkSection(item)
                case "text":  textSection(item)
                default:      fileSection(for: item)
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .overlay(alignment: .bottom) {
            if showCopied {
                Text("Copied ✓")
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.bottom, 20)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: showCopied)
    }

    // MARK: - Inline preview (image / video / audio)

    @ViewBuilder
    private func preview(for item: SyncItem) -> some View {
        switch item.type {
        case "image":
            if let img = previewImage(for: item, store: store) {
                Image(uiImage: img).resizable().scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: 320)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        case "video":
            if let url = store.namedFileURL(for: item) {
                InlinePlayer(url: url)
                    .frame(height: 240)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            } else if let img = previewImage(for: item, store: store) {
                Image(uiImage: img).resizable().scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: 320)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay {
                        Image(systemName: "play.circle.fill")
                            .font(.largeTitle).foregroundStyle(.white.opacity(0.9))
                    }
            }
        case "audio":
            if let url = store.namedFileURL(for: item) {
                InlinePlayer(url: url).frame(height: 90)
            }
        default:
            EmptyView()
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private func linkSection(_ item: SyncItem) -> some View {
        Text(ContentTypes.linkSubtype(item.content))
            .font(.headline)
        Text(item.content)
            .font(.caption.monospaced())
            .foregroundStyle(.secondary)
            .lineLimit(2)
        HStack(spacing: 12) {
            Button {
                if let u = URL(string: item.content) { openURL(u) }
            } label: {
                Label("Open", systemImage: "safari")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Button {
                UIPasteboard.general.string = item.content
                showCopyFeedback()
            } label: {
                Label("Copy", systemImage: "doc.on.doc")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }

    @ViewBuilder
    private func textSection(_ item: SyncItem) -> some View {
        Text(item.content)
        HStack(spacing: 12) {
            Button { editedText = item.content; isEditingText = true } label: {
                Label("Edit", systemImage: "pencil")
            }
            Button {
                UIPasteboard.general.string = item.content
                showCopyFeedback()
            } label: {
                Label("Copy", systemImage: "doc.on.doc")
            }
        }
    }

    @ViewBuilder
    private func fileSection(for item: SyncItem) -> some View {
        let c = fileComment(for: item)
        if c.isEmpty {
            Button {
                editedComment = c
                isEditingComment = true
            } label: {
                Label("Add a comment…", systemImage: "pencil")
                    .foregroundStyle(Color.secondary)
            }
        } else {
            Label(c, systemImage: "pencil")
                .foregroundStyle(Color.primary)
                .onTapGesture {
                    editedComment = c
                    isEditingComment = true
                }
        }

        if let url = store.namedFileURL(for: item) {
            ShareLink(item: url) {
                Label("Open / Share", systemImage: "square.and.arrow.up")
            }
        } else if sync.progress[item.id] == nil {
            Button { sync.download(item) } label: {
                Label("Download", systemImage: "icloud.and.arrow.down")
            }
        }
    }

    private func metaLine(for item: SyncItem) -> String {
        let head = item.isLinkOrText ? item.type.capitalized
                                     : (item.mimeType.isEmpty ? "File" : item.mimeType)
        return "\(head) · \(longDate(item.createdAt))"
    }

    // MARK: - Copy feedback

    private func showCopyFeedback() {
        showCopied = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { showCopied = false }
    }

    // MARK: - Actions

    private func togglePin(_ item: SyncItem) {
        var updated = item
        updated.isPinned.toggle()
        updated.updatedAt = nowMillis()
        store.localUpsert(updated)
        sync.pushItem(updated, uploadFile: false)
    }

    private func saveTextEdit(_ item: SyncItem) {
        let newContent = editedText
        let firstLine = newContent.split(separator: "\n").first.map(String.init) ?? newContent
        var updated = item
        updated.content = newContent
        updated.title = String(firstLine.prefix(60))
        updated.updatedAt = nowMillis()
        store.localUpsert(updated)
        sync.pushItem(updated, uploadFile: false)
        isEditingText = false
    }

    private func saveCommentEdit(_ item: SyncItem) {
        let trimmed = editedComment.trimmingCharacters(in: .whitespacesAndNewlines)
        var updated = item
        // Empty comment → blank title (the cross-platform "no comment" sentinel). The filename
        // lives in `content`, so it's never lost.
        updated.title = trimmed
        updated.updatedAt = nowMillis()
        store.localUpsert(updated)
        sync.pushItem(updated, uploadFile: false)
        isEditingComment = false
    }

    private func delete(_ item: SyncItem) {
        sync.deleteItem(syncId: item.id)
        dismiss()
    }

    private func retry(_ item: SyncItem) {
        if store.hasLocalFile(item) { sync.pushItem(item) } else { sync.download(item) }
    }
}

/// Inline AV player that owns a stable AVPlayer for the lifetime of the view.
/// Shows the first video frame as a poster until the asset finishes loading.
struct InlinePlayer: View {
    let url: URL
    @State private var player: AVPlayer?
    @State private var thumbnail: UIImage?
    @State private var videoReady = false
    @State private var statusObserver: NSKeyValueObservation?

    var body: some View {
        ZStack {
            VideoPlayer(player: player)
            if !videoReady, let img = thumbnail {
                Image(uiImage: img)
                    .resizable().scaledToFill()
                    .clipped()
                    .allowsHitTesting(false)
            }
        }
        .onAppear {
            guard player == nil else { return }
            let p = AVPlayer(url: url)
            player = p
            statusObserver = p.currentItem?.observe(\.status, options: [.new]) { item, _ in
                if item.status == .readyToPlay {
                    DispatchQueue.main.async { videoReady = true }
                }
            }
        }
        .onDisappear {
            player?.pause()
            statusObserver?.invalidate()
            statusObserver = nil
        }
        .task { thumbnail = await firstVideoFrame(from: url) }
    }
}

private func firstVideoFrame(from url: URL) async -> UIImage? {
    let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
    gen.appliesPreferredTrackTransform = true
    gen.maximumSize = CGSize(width: 1280, height: 720)
    return await withCheckedContinuation { cont in
        gen.generateCGImagesAsynchronously(forTimes: [NSValue(time: .zero)]) { _, cgImage, _, result, _ in
            cont.resume(returning: result == .succeeded ? cgImage.map { UIImage(cgImage: $0) } : nil)
        }
    }
}
