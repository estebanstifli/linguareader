import SwiftUI
import UIKit
import LinguaShared

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
    static let sepia = ReaderTheme(
        background: UIColor(red: 0.96, green: 0.92, blue: 0.82, alpha: 1), text: UIColor(red: 0.27, green: 0.20, blue: 0.12, alpha: 1), secondary: UIColor(red: 0.5, green: 0.42, blue: 0.3, alpha: 1),
        sentenceHighlight: UIColor(red: 0.95, green: 0.82, blue: 0.55, alpha: 0.6), wordHighlight: UIColor(red: 0.90, green: 0.65, blue: 0.25, alpha: 0.95),
        wordText: UIColor(red: 0.2, green: 0.12, blue: 0.05, alpha: 1), dark: false)
}
