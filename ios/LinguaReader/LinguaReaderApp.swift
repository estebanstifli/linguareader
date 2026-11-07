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
            .preferredColorScheme(store.prefs?.theme.colorScheme)
            // «Abrir en LingoAudioBook» desde Archivos, Mail, WhatsApp, Telegram o Safari: el catálogo lo importa.
            .onOpenURL { url in store.openedDocument = url }
        }
    }
}
