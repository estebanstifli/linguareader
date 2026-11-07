import SwiftUI
import LinguaShared
import UIKit

/// Orientaciones admitidas: en iPhone las que elige el usuario (Ajustes o el botón del lector); en iPad todas, porque
/// iPadOS exige las cuatro para la multitarea y allí el giro libre es lo esperado.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        OrientationLock.isPhone ? OrientationLock.mask : .all
    }
}

/// Bloqueo de orientación según `UserPreferences.orientation` (por nombre: PORTRAIT, LANDSCAPE, AUTO).
enum OrientationLock {
    static var isPhone: Bool { UIDevice.current.userInterfaceIdiom == .phone }
    @MainActor static var mask: UIInterfaceOrientationMask = .portrait

    @MainActor static func apply(_ name: String?) {
        let wanted: UIInterfaceOrientationMask = switch name {
        case "LANDSCAPE": .landscape
        case "AUTO": .allButUpsideDown
        default: .portrait
        }
        guard isPhone, wanted != mask else { return }
        mask = wanted
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: wanted)) { _ in }
        }
    }

    static func icon(_ name: String) -> String {
        switch name {
        case "LANDSCAPE": "rectangle"
        case "AUTO": "arrow.triangle.2.circlepath"
        default: "rectangle.portrait"
        }
    }

    static func label(_ name: String) -> String {
        switch name {
        case "LANDSCAPE": String(localized: "Landscape")
        case "AUTO": String(localized: "Automatic")
        default: String(localized: "Portrait")
        }
    }
}

@main
struct LinguaReaderApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
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
            // Orientación elegida (vertical / horizontal / automática) para toda la app en iPhone.
            .onChange(of: store.prefs?.orientation.name, initial: true) { _, name in OrientationLock.apply(name) }
        }
    }
}
