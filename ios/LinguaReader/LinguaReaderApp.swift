import SwiftUI
import LinguaShared

@main
struct LinguaReaderApp: App {
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                CatalogView()
            }
            .environmentObject(store)
        }
    }
}
