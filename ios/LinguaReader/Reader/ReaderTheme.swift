import SwiftUI
import UIKit
import LinguaShared

/// Tema elegido aplicado a una pantalla fuera del lector (catálogo, ajustes, hojas): fondo, barra de navegación y
/// color de acento según `UserPreferences.theme`, como las pantallas de Android. Se aplica junto a `navigationTitle`.
struct ThemedScreen: ViewModifier {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        let mode = store.prefs?.theme
        let theme = ReaderTheme.resolve(mode, systemDark: colorScheme == .dark)
        content
            .scrollContentBackground(.hidden)
            .background(Color(theme.background).ignoresSafeArea())
            .toolbarBackground(Color(theme.background), for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .tint(mode == .sepia ? Color(red: 0.55, green: 0.33, blue: 0.10) : nil)
    }
}

/// Fondo de las filas de una `Section` según el tema: en sepia, crema; en los demás, el del sistema.
struct ThemedRows: ViewModifier {
    @EnvironmentObject private var store: AppStore

    func body(content: Content) -> some View {
        content.listRowBackground(store.prefs?.theme == .sepia ? Color(ReaderTheme.sepiaCard) : nil)
    }
}

/// Fondo de una barra (filtros, cabeceras) con el color del tema en vez del material del sistema.
struct ThemedBar: ViewModifier {
    @EnvironmentObject private var store: AppStore
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content.background(Color(ReaderTheme.resolve(store.prefs?.theme, systemDark: colorScheme == .dark).background))
    }
}

extension View {
    func themedScreen() -> some View { modifier(ThemedScreen()) }
    func themedRows() -> some View { modifier(ThemedRows()) }
    func themedBar() -> some View { modifier(ThemedBar()) }
}

extension ThemeMode {
    /// Esquema claro/oscuro que fuerza el tema (nil = el del sistema).
    var colorScheme: ColorScheme? {
        switch self {
        case .dark: return .dark
        case .light, .sepia: return .light
        default: return nil
        }
    }
}

/// Colores del lector según el tema elegido (sistema, claro, oscuro, sepia), como `ReaderColors` en Android.
struct ReaderTheme {
    let background: UIColor
    let text: UIColor
    let secondary: UIColor
    let sentenceHighlight: UIColor
    let wordHighlight: UIColor
    let wordText: UIColor
    let dark: Bool

    static func resolve(_ mode: ThemeMode?, systemDark: Bool) -> ReaderTheme {
        switch mode {
        case .some(.light): return light
        case .some(.dark): return dark
        case .some(.sepia): return sepia
        default: return systemDark ? dark : light
        }
    }

    static let light = ReaderTheme(
        background: UIColor(red: 0.99, green: 0.98, blue: 0.96, alpha: 1), text: UIColor(white: 0.12, alpha: 1), secondary: UIColor(white: 0.42, alpha: 1),
        sentenceHighlight: UIColor(red: 1.0, green: 0.93, blue: 0.70, alpha: 0.55), wordHighlight: UIColor(red: 1.0, green: 0.80, blue: 0.30, alpha: 0.95),
        wordText: UIColor(white: 0.1, alpha: 1), dark: false)
    static let dark = ReaderTheme(
        background: UIColor(white: 0.09, alpha: 1), text: UIColor(white: 0.90, alpha: 1), secondary: UIColor(white: 0.62, alpha: 1),
        sentenceHighlight: UIColor(red: 0.35, green: 0.33, blue: 0.20, alpha: 0.7), wordHighlight: UIColor(red: 0.85, green: 0.65, blue: 0.20, alpha: 0.95),
        wordText: UIColor(white: 0.05, alpha: 1), dark: true)
    /// Tarjetas y filas de lista sobre el fondo sepia.
    static let sepiaCard = UIColor(red: 0.99, green: 0.965, blue: 0.90, alpha: 1)

    static let sepia = ReaderTheme(
        background: UIColor(red: 0.96, green: 0.92, blue: 0.82, alpha: 1), text: UIColor(red: 0.27, green: 0.20, blue: 0.12, alpha: 1), secondary: UIColor(red: 0.5, green: 0.42, blue: 0.3, alpha: 1),
        sentenceHighlight: UIColor(red: 0.95, green: 0.82, blue: 0.55, alpha: 0.6), wordHighlight: UIColor(red: 0.90, green: 0.65, blue: 0.25, alpha: 0.95),
        wordText: UIColor(red: 0.2, green: 0.12, blue: 0.05, alpha: 1), dark: false)
}
