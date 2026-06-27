import SwiftUI
import Combine

/// Owns the long-lived store + sync client. Constructed once as a @StateObject default,
/// which runs in SwiftUI's main-actor context (both types are @MainActor-isolated).
@MainActor
final class AppModel: ObservableObject {
    let store: StashStore
    let sync: SyncClient
    init() {
        let store = StashStore()
        self.store = store
        self.sync = SyncClient(store: store)
    }
}

@main
struct StashApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model.store)
                .environmentObject(model.sync)
                .onAppear { model.sync.start() }
        }
        .onChange(of: scenePhase) { phase in
            // iOS won't keep a WebSocket alive in the background — connect while active.
            // (A future BGTaskScheduler / background-URLSession path can handle uploads
            // initiated from the share extension; see README.)
            switch phase {
            case .active: model.sync.start()
            case .background: model.sync.stop()
            default: break
            }
        }
    }
}
