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
            Toggle("Show illustrations", isOn: Binding(get: { p.showIllustrations }, set: { v in Task { try? await LinguaShared.shared.setShowIllustrations(enabled: v) } }))
                .accessibilityIdentifier("settings.illustrations")
            Toggle("Double page in landscape", isOn: Binding(get: { p.doublePage }, set: { v in Task { try? await LinguaShared.shared.setDoublePage(enabled: v) } }))
                .accessibilityIdentifier("settings.doublePage")
            if OrientationLock.isPhone {
                Picker("Screen orientation", selection: Binding(get: { p.orientation.name }, set: { v in Task { try? await LinguaShared.shared.setOrientation(name: v) } })) {
                    Text("Portrait").tag("PORTRAIT"); Text("Landscape").tag("LANDSCAPE"); Text("Automatic").tag("AUTO")
                }
            }
            Picker("Open PDFs as", selection: Binding(get: { p.originalOpenMode }, set: { m in Task { try? await LinguaShared.shared.setOriginalOpenMode(mode: m) } })) {
                Text("Ask").tag(OriginalOpenMode.ask); Text("Reading mode").tag(OriginalOpenMode.reader); Text("Original").tag(OriginalOpenMode.original)
            }
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
            Text("Speed")
            Slider(value: Binding(get: { Double(p.speechRate) }, set: { v in Task { try? await LinguaShared.shared.setSpeechRate(rate: Float(v)) } }), in: 0.5...1.5, step: 0.05)
            Text(String(format: "%.2f×", p.speechRate)).font(.footnote).monospacedDigit()
        }
        HStack {
            Text("Pitch")
            Slider(value: Binding(get: { Double(p.ttsPitch) }, set: { v in Task { try? await LinguaShared.shared.setTtsPitch(pitch: Float(v)) } }), in: 0.7...1.3, step: 0.05)
            Text(String(format: "%.2f", p.ttsPitch)).font(.footnote).monospacedDigit()
        }
        HStack {
            Text("Pause between sentences")
            Slider(value: Binding(get: { Double(p.sentencePauseMs) }, set: { v in Task { try? await LinguaShared.shared.setSentencePause(ms: Int32(v)) } }), in: 200...1500, step: 50)
            Text("\(Double(p.sentencePauseMs) / 1000, specifier: "%.1f") s").font(.footnote).monospacedDigit()
        }
        HStack {
            Text("Pause between paragraphs")
            Slider(value: Binding(get: { Double(p.paragraphPauseMs) }, set: { v in Task { try? await LinguaShared.shared.setParagraphPause(ms: Int32(v)) } }), in: 300...3000, step: 100)
            Text("\(Double(p.paragraphPauseMs) / 1000, specifier: "%.1f") s").font(.footnote).monospacedDigit()
        }
    }
}

/// Voces del sistema para un idioma, con «Hear sample» (la frase de prueba en ese idioma) y la voz elegida por idioma.
struct VoiceListSection: View {
    @EnvironmentObject private var store: AppStore
    let p: UserPreferences
    let language: String
    @State private var voices: [VoiceInfo] = []
    @State private var playing: String?

    var body: some View {
        Section {
            if voices.isEmpty {
                Text("No voice installed for \(Languages.nameInSentence(language)). Add one in iOS Settings › Accessibility › Spoken Content › Voices.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            let selected = p.voiceByLanguage[language]
            if !voices.isEmpty {
                voiceRow(id: nil, title: String(localized: "Automatic"), detail: String(localized: "The best installed voice for the language"), selected: selected == nil)
            }
            ForEach(voices, id: \.id) { v in
                let quality = (v.quality?.intValue ?? 0) >= 400 ? String(localized: "enhanced") : String(localized: "standard")
                voiceRow(id: v.id, title: v.displayName, detail: "\(v.languageTag) · \(quality)", selected: selected == v.id)
            }
        } header: {
            Text("\(Languages.name(language)) voices")
        } footer: {
            Text("Voices are Apple's, downloaded from iOS Settings › Accessibility › Spoken Content. The sample uses the speed and pitch above.")
        }
        .themedRows()
        .task(id: language) { voices = (try? await LinguaShared.shared.listVoices(languageTag: language)) ?? [] }
        .onDisappear { LinguaShared.shared.stopVoiceSample() }
    }

    private func voiceRow(id: String?, title: String, detail: String, selected: Bool) -> some View {
        HStack {
            Button {
                Task { try? await LinguaShared.shared.setVoice(languageTag: language, voiceId: id) }
            } label: {
                HStack {
                    Image(systemName: selected ? "largecircle.fill.circle" : "circle").foregroundStyle(.tint)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                        Text(detail).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .buttonStyle(.plain)
            Spacer()
            Button {
                Task {
                    playing = id ?? "auto"
                    _ = try? await LinguaShared.shared.playVoiceSample(languageTag: language, voiceId: id, speed: p.speechRate)
                    try? await Task.sleep(for: .seconds(4))
                    if playing == (id ?? "auto") { playing = nil }
                }
            } label: { Image(systemName: playing == (id ?? "auto") ? "speaker.wave.3.fill" : "play.circle") }
            .buttonStyle(.borderless)
            .accessibilityLabel("Hear sample")
        }
        .accessibilityIdentifier("voice.\(id ?? "auto")")
    }
}

/// Caché de audio generado (frases ya sintetizadas): uso, límite y vaciar, como la tarjeta de Android.
struct AudioCacheSection: View {
    let p: UserPreferences
    @State private var bytes: Int64 = 0
    private let limits: [Int32] = [128, 256, 512, 1024]

    var body: some View {
        Section {
            Text("In use: \(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)) of \(Int(p.audioCacheMb)) MB").font(.subheadline)
            Picker("Limit", selection: Binding(get: { p.audioCacheMb }, set: { mb in Task { try? await LinguaShared.shared.setAudioCacheLimit(mb: mb) } })) {
                ForEach(limits, id: \.self) { Text(verbatim: "\($0) MB").tag($0) }
            }
            .pickerStyle(.segmented)
            Button("Clear audio cache", role: .destructive) {
                LinguaShared.shared.clearAudioCache()
                bytes = LinguaShared.shared.audioCacheBytes()
            }
            .disabled(bytes == 0)
            .accessibilityIdentifier("settings.clearAudioCache")
        } header: {
            Text("Audio cache")
        } footer: {
            Text("Sentences already synthesized are kept so they play instantly next time. The oldest ones are removed when the limit is reached.")
        }
        .themedRows()
        .task { bytes = LinguaShared.shared.audioCacheBytes() }
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
                ForEach(store.learningLanguages, id: \.self) { lang in
                    VoiceListSection(p: p, language: lang)
                }
                NeuralVoiceSection(p: p)
                AudioCacheSection(p: p)
            }
        }
        .themedScreen()
        .navigationTitle("Reading and appearance")
    }
}
