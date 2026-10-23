import SwiftUI
import LinguaShared

/// Primera apertura (los mismos pasos que Android, sin el de examinar archivos, que no existe en iOS):
/// propósito → idioma a aprender (descarga su biblioteca) → tu idioma → nivel → resumen.
struct OnboardingView: View {
    @EnvironmentObject private var store: AppStore

    private enum Step { case purpose, learnLanguage, nativeLanguage, level, summary }

    private var step: Step {
        guard let p = store.prefs else { return .purpose }
        if p.purpose == nil { return .purpose }
        if !p.learnLanguageChosen { return .learnLanguage }
        if !p.translationSetupDone { return .nativeLanguage }
        if !p.learnerLevelChosen { return .level }
        return .summary
    }

    private var totalSteps: Int { store.prefs?.purpose == AppPurpose.read ? 2 : 5 }

    private var stepIndex: Int {
        switch step {
        case .purpose: return 1
        case .learnLanguage: return 2
        case .nativeLanguage: return 3
        case .level: return 4
        case .summary: return totalSteps
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if step != .purpose {
                    ProgressView(value: Double(stepIndex), total: Double(totalSteps))
                    Text("Step \(stepIndex) of \(totalSteps)").font(.footnote).foregroundStyle(.secondary)
                }
                switch step {
                case .purpose: purpose
                case .learnLanguage: learnLanguage
                case .nativeLanguage: nativeLanguage
                case .level: level
                case .summary: summary
                }
            }
            .padding(24)
        }
        .accessibilityIdentifier("onboarding")
    }

    // MARK: Pasos

    private var purpose: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("What will you use LinguaReader for?").font(.largeTitle.bold())
            Text("We'll set up the app based on your choice. You can change it in Settings at any time.").foregroundStyle(.secondary)
            choiceCard("Read my books and documents", "Opens EPUB, FB2, Word, TXT… files in a comfortable reader, without voice.", "book") { set(.read) }
                .accessibilityIdentifier("onboarding.read")
            choiceCard("Learn a language", "Graded readers by level, a voice that reads with the word highlighted, and translation on tap.", "character.book.closed") { set(.learn) }
                .accessibilityIdentifier("onboarding.learn")
            choiceCard("Both", "Sets up the language you want to learn and lets you add your own documents.", "books.vertical") { set(.both) }
                .accessibilityIdentifier("onboarding.both")
        }
    }

    private func set(_ purpose: AppPurpose) {
        Task { try? await LinguaShared.shared.setPurpose(purpose: purpose) }
    }

    private var learnLanguage: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Which language do you want to learn?").font(.largeTitle.bold())
            Text("We'll download its library of graded readers (A1 to B2) so you can read and listen offline.").foregroundStyle(.secondary)
            ForEach(store.libraryCatalog.keys.sorted(), id: \.self) { lang in
                let info = store.libraryCatalog[lang]!
                Button {
                    Task {
                        try? await LinguaShared.shared.setReadingLanguage(tag: lang)
                    }
                } label: {
                    HStack {
                        Text(Languages.name(lang)).font(.title3.bold())
                        Spacer()
                        Text("\(info.books) books · \(ByteCountFormatter.string(fromByteCount: info.sizeBytes, countStyle: .file))").foregroundStyle(.secondary)
                    }
                    .padding().frame(maxWidth: .infinity).background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("onboarding.language.\(lang)")
            }
            Button("Not now") { Task { try? await LinguaShared.shared.setReadingLanguage(tag: nil); try? await LinguaShared.shared.setTranslationLanguage(tag: store.prefs?.translationLanguage ?? "en", wifiOnly: true) } }
                .accessibilityIdentifier("onboarding.notNow")
        }
    }

    private var nativeLanguage: some View {
        NativeLanguageStep(learning: store.prefs?.readingLanguage, initial: store.prefs?.translationLanguage ?? "en")
    }

    private var level: some View {
        LevelStep(language: store.prefs?.readingLanguage ?? "en") { level in
            Task {
                try? await LinguaShared.shared.setLearnerLevel(level: level)
                if let lang = store.prefs?.readingLanguage { store.downloadLibrary(Languages.primary(lang)) }
            }
        }
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Here's what we've set up").font(.largeTitle.bold())
            Text("Anything still downloading continues in the background: you can start now.").foregroundStyle(.secondary)
            if let lang = store.prefs?.readingLanguage.map(Languages.primary) {
                summaryRow("\(Languages.name(lang)) library", libraryStatus(lang), "books.vertical")
                summaryRow("\(Languages.name(lang)) voice", "Ready (system voice)", "person.wave.2")
            } else {
                summaryRow("Language to learn", "Not chosen: you can download libraries in Settings › Libraries", "character.book.closed")
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
        case let s as LibraryPackStateInstalled: return "Ready · \(s.books) books"
        case is LibraryPackStateDownloading: return "Downloading…"
        case let s as LibraryPackStateInstalling: return "Installing \(s.done)/\(s.total)…"
        case let s as LibraryPackStateFailed: return PackErrors.text(s.error)
        default: return "Pending"
        }
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

    private func choiceCard(_ title: String, _ detail: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: icon).font(.title2).frame(width: 44, height: 44).background(Color.accentColor.opacity(0.15), in: Circle())
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline)
                    Text(detail).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .padding().frame(maxWidth: .infinity).background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
}

private struct NativeLanguageStep: View {
    let learning: String?
    @State var initial: String
    @State private var wifiOnly = true

    private var sameAsLearning: Bool { learning.map { Languages.primary($0) == Languages.primary(initial) } ?? false }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("What's your language?").font(.largeTitle.bold())
            Text("Words and sentences will be translated into this language.").foregroundStyle(.secondary)
            Picker("Your language", selection: $initial) {
                ForEach(Languages.translationTargets, id: \.self) { Text(Languages.name($0)).tag($0) }
            }
            .pickerStyle(.menu)
            if sameAsLearning { Text("Choose a language different from the one you are learning.").foregroundStyle(.red).font(.footnote) }
            Toggle("Download translators only over Wi-Fi", isOn: $wifiOnly)
            Button {
                Task {
                    try? await LinguaShared.shared.setTranslationLanguage(tag: initial, wifiOnly: wifiOnly)
                    LinguaShared.shared.downloadTranslationModelsFor(sourceLanguage: learning, targetLanguage: initial)
                }
            } label: { Text("Continue").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large).disabled(sameAsLearning)
                .accessibilityIdentifier("onboarding.continue")
        }
        .onAppear {
            if sameAsLearning { initial = Languages.translationTargets.first { $0 != Languages.primary(learning ?? "") } ?? "en" }
        }
    }
}

private struct LevelStep: View {
    let language: String
    let choose: (CefrLevel?) -> Void
    @State private var selected: CefrLevel?
    @State private var unknown = false

    private let options: [(CefrLevel, String)] = [
        (.a1, "I'm starting from scratch, or nearly"),
        (.a2, "I understand simple, everyday sentences"),
        (.b1, "I can get by on everyday topics"),
        (.b2, "I read general texts quite fluently"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("What's your level of \(Languages.name(language))?").font(.largeTitle.bold())
            Text("We'll show you books at your level first. You can always see the others.").foregroundStyle(.secondary)
            ForEach(options, id: \.0) { level, hint in
                option(selected == level && !unknown, "\(level.name) · \(hint)") { selected = level; unknown = false }
                    .accessibilityIdentifier("onboarding.level.\(level.name)")
            }
            option(unknown, "I don't know") { unknown = true; selected = nil }
            Button {
                choose(unknown ? nil : selected)
            } label: { Text("Continue and download").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large).disabled(selected == nil && !unknown)
                .accessibilityIdentifier("onboarding.download")
            Text("When you continue, the library starts downloading in the background; you can keep using the app.")
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
