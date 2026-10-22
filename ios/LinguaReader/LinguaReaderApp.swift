import SwiftUI
import LinguaShared

@main
struct LinguaReaderApp: App {
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            Group {
                if store.prefs == nil {
                    ProgressView()
                } else if store.needsOnboarding {
                    OnboardingView()
                } else {
                    CatalogView()
                }
            }
            .environmentObject(store)
        }
    }
}
