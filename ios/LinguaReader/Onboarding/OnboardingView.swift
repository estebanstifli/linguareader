import SwiftUI
import LinguaShared

/// Primera apertura, los mismos pasos que Android (la app es solo para aprender idiomas, así que no pregunta el propósito):
/// idiomas (uno o varios) y tu idioma → nivel → resumen de lo preparado. Cada paso queda guardado al completarlo, así que
/// cerrar la app a medias retoma donde se quedó y lo ya lanzado sigue descargándose.
struct OnboardingView: View {
    @EnvironmentObject private var store: AppStore

    private enum Step { case languages, level, summary }

    private var step: Step {
        guard let p = store.prefs else { return .languages }
        if !p.learnLanguageChosen || !p.translationSetupDone { return .languages }
        if !p.learnerLevelChosen { return .level }
        return .summary
    }

    private let totalSteps = 3

    private var stepIndex: Int {
        switch step {
        case .languages: return 1
        case .level: return 2
        case .summary: return 3
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                ProgressView(value: Double(stepIndex), total: Double(totalSteps))
                Text("Step \(stepIndex) of \(totalSteps)").font(.footnote).foregroundStyle(.secondary)
                switch step {
                case .languages:
                    if let p = store.prefs { LanguagesOnboardingStep(prefs: p) }
                case .level: level
                case .summary: summary
                }
            }
            .padding(24)
        }
        .accessibilityIdentifier("onboarding")
        .themedScreen()
        .task(id: store.prefs?.purpose == nil) {
            // Propósito fijo: aprender un idioma (modo con voz), sin preguntarlo.
            if store.prefs?.purpose == nil { try? await LinguaShared.shared.setPurpose(purpose: .learn) }
        }
    }

    // MARK: Pasos

    private var level: some View {
        let languages = store.learningLanguages
        return LevelStep(language: languages.count == 1 ? languages[0] : nil) { level in
            Task { try? await LinguaShared.shared.setLearnerLevel(level: level) }
        }
    }

    private var summary: some View {
        let native = store.prefs.map { Languages.primary($0.translationLanguage) } ?? "en"
        return VStack(alignment: .leading, spacing: 16) {
            Text("Here's what we've set up").font(.largeTitle.bold())
            Text("Anything still downloading continues in the background: you can start now.").foregroundStyle(.secondary)
            if store.learningLanguages.isEmpty {
                summaryRow("Language to learn", "Not chosen: you can add languages in Settings › Languages", "character.book.closed")
            }
            ForEach(store.learningLanguages, id: \.self) { lang in
                summaryRow("\(Languages.name(lang)) library", libraryStatus(lang), "books.vertical")
                if let translator = translatorStatus(lang, native: native) {
                    summaryRow("\(Languages.name(lang)) translator", translator, "character.bubble")
                }
                summaryRow("\(Languages.name(lang)) voice", "Ready (system voice)", "person.wave.2")
            }
            Button {
                Task { try? await LinguaShared.shared.finishOnboarding() }
            } label: {
                Text("Start reading").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
            .accessibilityIdentifier("onboarding.start")
        }
    }

    private func libraryStatus(_ lang: String) -> String {
        switch store.libraryStates[lang] {
        case let s as LibraryPackStateInstalled: return String(localized: "Ready · \(Int(s.books)) books")
        case is LibraryPackStateDownloading: return String(localized: "Downloading…")
        case let s as LibraryPackStateInstalling: return String(localized: "Installing \(Int(s.done))/\(Int(s.total))…")
        case let s as LibraryPackStateFailed: return PackErrors.text(s.error)
        default: return store.libraryCatalog[lang] == nil ? String(localized: "Books coming soon · voice and translation available") : String(localized: "Pending")
        }
    }

    /// Estado de los modelos de traducción entre el idioma y el nativo; nil si no hacen falta (mismo idioma, sin traductor).
    private func translatorStatus(_ lang: String, native: String) -> String? {
        guard let models = store.translationModels else { return nil }
        let codes = store.neededModels(lang, native: native)
        if codes.isEmpty { return nil }
        if codes.allSatisfy({ models.downloaded.contains($0) }) { return String(localized: "Ready, works offline") }
        if codes.contains(where: { models.downloads[$0] is TranslationModelDownloadRunning }) { return String(localized: "Downloading…") }
        if codes.contains(where: { models.downloads[$0] is TranslationModelDownloadFailed }) { return String(localized: "Download failed · retry in Settings › Languages") }
        return String(localized: "Pending")
    }

    private func summaryRow(_ title: String, _ detail: String, _ icon: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.title2).frame(width: 44, height: 44).background(Color.accentColor.opacity(0.15), in: Circle())
            VStack(alignment: .leading) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .padding().frame(maxWidth: .infinity, alignment: .leading).background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
    }
}

/// Paso 1: tu idioma, los idiomas a aprender (uno o varios, con casillas; primero los que tienen libros) y «Continue».
/// No guarda nada hasta pulsar el botón, como `LanguagesOnboardingContent` en Android; al pulsarlo arrancan las descargas.
private struct LanguagesOnboardingStep: View {
    @EnvironmentObject private var store: AppStore
    private let previous: [String]
    @State private var selected: Set<String>
    @State private var native: String
    @State private var wifiOnly: Bool
    @State private var showAll = false

    init(prefs p: UserPreferences) {
        previous = p.learningLanguages.map(Languages.primary)
        _selected = State(initialValue: Set(previous))
        let device = Locale.current.language.languageCode?.identifier ?? "en"
        _native = State(initialValue: p.translationSetupDone ? Languages.primary(p.translationLanguage) : device)
        _wifiOnly = State(initialValue: p.translationWifiOnly)
    }

    private var nativeClash: Bool { selected.contains(native) }

    var body: some View {
        let all = store.learnable()
        let shown = showAll ? all : all.filter { store.libraryCatalog[$0] != nil || selected.contains($0) }
        VStack(alignment: .leading, spacing: 16) {
            Text("Which languages do you want to learn?").font(.largeTitle.bold())
            Text("Choose one or several. We'll download their libraries of graded readers (A1 to B2) and the translators, so you can read and listen offline.")
                .foregroundStyle(.secondary)
            HStack {
                Text("Your language").font(.headline)
                Spacer()
                Picker("Your language", selection: $native) {
                    ForEach(store.nativeOptions, id: \.self) { Text(Languages.name($0)).tag($0) }
                }
                .pickerStyle(.menu)
                .accessibilityIdentifier("onboarding.native")
            }
            if nativeClash {
                Text("It's the same language you're going to learn: choose another one to be able to translate.").foregroundStyle(.red).font(.footnote)
            }
            Text("Languages you're learning").font(.headline)
            ForEach(shown, id: \.self) { lang in languageCard(lang) }
            if !showAll && shown.count < all.count {
                Button("Show \(all.count - shown.count) more languages (voice and translation, books coming soon)") { showAll = true }
                    .font(.subheadline)
            }
            Toggle("Download translators only over Wi-Fi (≈40 MB per language)", isOn: $wifiOnly)
            Button {
                // Los que ya se aprendían y siguen marcados conservan su orden; los nuevos van detrás.
                let learning = previous.filter { selected.contains($0) } + selected.subtracting(previous).sorted()
                Task { try? await LinguaShared.shared.chooseLanguages(learning: learning, native: native, wifiOnly: wifiOnly) }
            } label: { Text("Continue").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large)
                .disabled(selected.isEmpty || nativeClash)
                .accessibilityIdentifier("onboarding.continue")
        }
        .onAppear {
            let options = store.nativeOptions
            if !options.contains(native) { native = options.first ?? "en" }
        }
    }

    private func languageCard(_ lang: String) -> some View {
        let on = selected.contains(lang)
        return Button {
            if on { selected.remove(lang) } else { selected.insert(lang) }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: on ? "checkmark.circle.fill" : "circle").font(.title3).foregroundStyle(.tint)
                VStack(alignment: .leading, spacing: 2) {
                    Text(Languages.name(lang)).font(.title3.bold())
                    Text(libraryAvailability(store.libraryCatalog[lang])).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding().frame(maxWidth: .infinity)
            .background(on ? Color.accentColor.opacity(0.15) : Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("onboarding.language.\(lang)")
    }
}

/// Paso 2: nivel aproximado (A1–B2 o «I don't know»); con varios idiomas la pregunta es genérica, como en Android.
private struct LevelStep: View {
    let language: String?
    let choose: (CefrLevel?) -> Void
    @State private var selected: CefrLevel?
    @State private var unknown = false

    private let options: [(CefrLevel, String)] = [
        (.a1, String(localized: "I'm starting from scratch, or nearly")),
        (.a2, String(localized: "I understand simple, everyday sentences")),
        (.b1, String(localized: "I can get by on everyday topics")),
        (.b2, String(localized: "I read general texts quite fluently")),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let language {
                Text("What's your level of \(Languages.name(language))?").font(.largeTitle.bold())
            } else {
                Text("What's your level in the language?").font(.largeTitle.bold())
            }
            Text("We'll show you books at your level first. You can always see the others.").foregroundStyle(.secondary)
            ForEach(options, id: \.0) { level, hint in
                option(selected == level && !unknown, "\(level.name) · \(hint)") { selected = level; unknown = false }
                    .accessibilityIdentifier("onboarding.level.\(level.name)")
            }
            option(unknown, String(localized: "I don't know")) { unknown = true; selected = nil }
            Button {
                choose(unknown ? nil : selected)
            } label: { Text("Continue and download").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large).disabled(selected == nil && !unknown)
                .accessibilityIdentifier("onboarding.download")
            Text("The libraries and translators are downloading in the background; you can keep using the app.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private func option(_ on: Bool, _ text: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: on ? "largecircle.fill.circle" : "circle").foregroundStyle(.tint)
                Text(text)
                Spacer()
            }
        }
        .buttonStyle(.plain)
    }
}
