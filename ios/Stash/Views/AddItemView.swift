import SwiftUI

/// Add a text note or link. (Adding files happens via the system share sheet → share
/// extension; see README. A document-picker import could be added here later.)
struct AddItemView: View {
    @EnvironmentObject var store: StashStore
    @EnvironmentObject var sync: SyncClient
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .padding()
                .navigationTitle("New item")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { save() }.disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
        }
    }

    private func save() {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let isURL = looksLikeURL(trimmed)
        let now = nowMillis()
        let item = SyncItem(
            id: UUID().uuidString,
            title: String(trimmed.prefix(60)),
            content: trimmed,
            type: isURL ? "link" : "text",
            createdAt: now,
            updatedAt: now
        )
        store.localUpsert(item)
        sync.pushItem(item, uploadFile: false)
        dismiss()
    }

    private func looksLikeURL(_ s: String) -> Bool {
        guard !s.contains(" "), let url = URL(string: s) else { return false }
        return url.scheme == "http" || url.scheme == "https"
    }
}
