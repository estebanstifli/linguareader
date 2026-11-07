import SwiftUI
import LinguaShared

/// Secciones de preferencias de lectura compartidas por Ajustes › Reading and appearance y por la hoja de ajustes
/// del lector (misma lógica que las secciones de Android).
struct TextPreferencesSection: View {
    let p: UserPreferences

    var body: some View {
        Section("Text") {
            Picker("Theme", selection: Binding(get: { p.theme }, set: { t in Task { try? await LinguaShared.shared.setTheme(theme: t) } })) {
                Text("System").tag(ThemeMode.system); Text("Light").tag(ThemeMode.light); Text("Dark").tag(ThemeMode.dark); Text("Sepia").tag(ThemeMode.sepia)
            }
            HStack {
                Text("Size")
                Slider(value: Binding(get: { Double(p.fontScale) }, set: { v in Task { try? await LinguaShared.shared.setFontScale(scale: Float(v)) } }), in: 0.8...1.8, step: 0.1)
                Text("\(Int(p.fontScale * 100)) %").monospacedDigit().font(.footnote)
            }
            HStack {
                Text("Line spacing")
                Slider(value: Binding(get: { Double(p.lineHeight) }, set: { v in Task { try? await LinguaShared.shared.setLineHeight(lineHeight: Float(v)) } }), in: 1.2...2.2, step: 0.1)
            }
            Picker("Alignment", selection: Binding(get: { p.textJustification }, set: { j in Task { try? await LinguaShared.shared.setTextJustification(justification: j) } })) {
                Text("Left").tag(TextJustification.left); Text("Justified").tag(TextJustification.justified); Text("Justified + hyphens").tag(TextJustification.justifiedHyphenated)
            }
        }.themedRows()
    }
}

struct ReadingPreferencesSection: View {
    let p: UserPreferences

    var body: some View {
        Section("Reading") {
            Picker("Translation", selection: Binding(get: { p.displayMode }, set: { m in Task { try? await LinguaShared.shared.setDisplayMode(mode: m) } })) {
                Text("On tap").tag(DisplayMode.translationOnTap); Text("Under each sentence").tag(DisplayMode.translationBelow)
            }
            Picker("Highlight", selection: Binding(get: { p.highlightStyle }, set: { h in Task { try? await LinguaShared.shared.setHighlightStyle(style: h) } })) {
                Text("Background").tag(HighlightStyle.background); Text("Underline").tag(HighlightStyle.underline); Text("Marker").tag(HighlightStyle.marker)
            }
            Toggle("Turn pages automatically", isOn: Binding(get: { p.autoScroll }, set: { v in Task { try? await LinguaShared.shared.setAutoScroll(enabled: v) } }))
            Toggle("Keep screen on", isOn: Binding(get: { p.keepScreenOn }, set: { v in Task { try? await LinguaShared.shared.setKeepScreenOn(enabled: v) } }))
            Toggle("Pause when looking up a word", isOn: Binding(get: { p.pauseOnWordTap }, set: { v in Task { try? await LinguaShared.shared.setPauseOnWordTap(enabled: v) } }))
            Toggle("Continue with the next passage", isOn: Binding(get: { p.autoContinue }, set: { v in Task { try? await LinguaShared.shared.setAutoContinue(enabled: v) } }))
            Toggle("Pointing finger over the word", isOn: Binding(get: { p.fingerPointerEnabled }, set: { v in Task { try? await LinguaShared.shared.setFingerPointer(enabled: v) } }))
                .accessibilityIdentifier("settings.fingerPointer")
            Toggle("Reduce motion", isOn: Binding(get: { p.reduceMotion }, set: { v in Task { try? await LinguaShared.shared.setReduceMotion(enabled: v) } }))
        }.themedRows()
    }
}

/// Preferencias de voz que no dependen del libro abierto. `sourceLanguage` es el idioma del libro (o el de lectura)
/// para descargar el traductor que falte al cambiar «Translate to».
struct VoicePreferencesRows: View {
    @EnvironmentObject private var store: AppStore
    let p: UserPreferences
    let sourceLanguage: String?

    var body: some View {
        Toggle("Learning mode with voice", isOn: Binding(get: { p.ttsMode }, set: { v in Task { try? await LinguaShared.shared.setTtsMode(enabled: v) } }))
        Picker("Translate to", selection: Binding(get: { Languages.primary(p.translationLanguage) }, set: { l in Task {
            try? await LinguaShared.shared.setTranslationLanguage(tag: l, wifiOnly: p.translationWifiOnly)
            LinguaShared.shared.downloadTranslationModelsFor(sourceLanguage: sourceLanguage, targetLanguage: l)
        } })) {
            ForEach(store.nativeOptions, id: \.self) { Text(Languages.name($0)).tag($0) }
        }
        HStack {
            Text("Pause between sentences")
            Slider(value: Binding(get: { Double(p.sentencePauseMs) }, set: { v in Task { try? await LinguaShared.shared.setSentencePause(ms: Int32(v)) } }), in: 200...1500, step: 50)
            Text("\(Double(p.sentencePauseMs) / 1000, specifier: "%.1f") s").font(.footnote).monospacedDigit()
        }
    }
}

/// Ajustes › Reading and appearance: las mismas opciones que la hoja del lector, sin tener un libro abierto.
struct ReadingSettingsView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        Form {
            if let p = store.prefs {
                TextPreferencesSection(p: p)
                ReadingPreferencesSection(p: p)
                Section("Voice") {
                    VoicePreferencesRows(p: p, sourceLanguage: p.readingLanguage)
                }.themedRows()
            }
        }
        .themedScreen()
        .navigationTitle("Reading and appearance")
    }
}
