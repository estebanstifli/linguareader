import SwiftUI
import LinguaShared

/// Ficha de un libro, como `BookDetailScreen` en Android: portada, datos, progreso y lo que se puede dejar preparado
/// antes de abrirlo (la traducción completa y, si el libro la trae, la narración de estudio). «Read» abre el lector;
/// «Read and listen» lo abre y arranca la voz. El audio generado del libro entero llegará a iOS con la voz neural.
struct BookDetailView: View {
    @EnvironmentObject private var store: AppStore
    let bookId: String
    @State private var narration: LinguaSharedNarrationInfo?

    private var book: BookSummary? { store.books.first { $0.id == bookId } }

    var body: some View {
        Group {
            if let book {
                ScrollView {
                    content(book).padding(.horizontal, 16).padding(.bottom, 24)
                }
            } else {
                ContentUnavailableView("This book is no longer available", systemImage: "book.closed")
            }
        }
        .themedScreen()
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("detail")
        // Al abrir, al empezar o terminar la descarga de la narración.
        .task(id: "\(store.narrationDownloading.contains(bookId))|\(store.narrationReady ?? "")") {
            narration = try? await LinguaShared.shared.narrationInfo(bookId: bookId)
        }
    }

    private func content(_ book: BookSummary) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 16) {
                BookCoverView(book: book).frame(width: 132)
                VStack(alignment: .leading, spacing: 4) {
                    Text(book.title).font(.title2.bold())
                    if !book.authors.isEmpty {
                        Text(book.authors.joined(separator: ", ")).font(.subheadline).foregroundStyle(.secondary)
                    }
                    Text("\(Languages.name(book.learningLanguage)) · \(book.cefrLevel.name)").font(.subheadline)
                    Text("≈ \(book.estimatedMinutes) min · \(book.wordCount) words · \(book.passageCount) passages")
                        .font(.caption).foregroundStyle(.secondary)
                    if !store.isLibraryBook(book.id) { Text("Imported").font(.caption).foregroundStyle(.secondary) }
                    if let fraction = store.progressFraction(book.id) {
                        HStack(spacing: 8) {
                            ProgressView(value: fraction)
                            Text("\(Int(fraction * 100)) %").font(.caption2).foregroundStyle(.secondary)
                        }
                        .padding(.top, 4)
                    }
                }
            }
            if let description = book.description, !description.isEmpty { Text(description).font(.body) }

            HStack(spacing: 10) {
                NavigationLink(value: CatalogDestination.reader(book.id, false)) {
                    if store.progressFraction(book.id) != nil {
                        Label("Continue reading", systemImage: "book").frame(maxWidth: .infinity)
                    } else {
                        Label("Read", systemImage: "book").frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.bordered).controlSize(.large)
                .accessibilityIdentifier("detail.read")
                NavigationLink(value: CatalogDestination.reader(book.id, true)) {
                    Label("Read and listen", systemImage: "play.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).controlSize(.large)
                .accessibilityIdentifier("detail.listen")
            }

            TranslationCard(book: book)
            if let narration { NarrationCard(bookId: book.id, info: narration) }
        }
        .padding(.top, 8)
    }
}

/// Traducción completa del libro al idioma elegido: estado y empezar / continuar / cancelar.
private struct TranslationCard: View {
    @EnvironmentObject private var store: AppStore
    let book: BookSummary

    var body: some View {
        let target = Languages.primary(store.prefs?.translationLanguage ?? "en")
        let job = store.bookTranslations[book.id]
        let status = store.translationStatus[book.id]
        VStack(alignment: .leading, spacing: 8) {
            Label("Translation into \(Languages.name(target))", systemImage: "character.bubble").font(.headline)
            if Languages.primary(book.learningLanguage) == target {
                Text("The book is already in this language: there is nothing to translate.").foregroundStyle(.secondary)
            } else if book.translationLanguages.contains(where: { Languages.primary($0) == target }) {
                Text("This book includes this translation.").foregroundStyle(.secondary)
            } else if let job {
                TranslationJobView(bookId: book.id, job: job)
            } else if status?.done.contains(target) == true {
                Text("Fully translated into \(Languages.nameInSentence(target)): translations appear instantly while you read.").foregroundStyle(.secondary)
            } else if let pause = status?.paused {
                TranslationPausedView(bookId: book.id, pause: pause)
            } else {
                Text("Translate the whole book now so no sentence has to wait while you read. It runs in the background and can be stopped and resumed.")
                    .foregroundStyle(.secondary)
                Button("Translate the whole book") { LinguaShared.shared.startBookTranslation(bookId: book.id, target: target) }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("detail.translate")
            }
        }
        .font(.subheadline)
        .detailCard()
    }
}

/// Libro con narración pregrabada (voz de estudio y tiempos por palabra del servidor): no hay nada que generar.
private struct NarrationCard: View {
    let bookId: String
    let info: LinguaSharedNarrationInfo

    var body: some View {
        let size = ByteCountFormatter.string(fromByteCount: info.bytes, countStyle: .file)
        VStack(alignment: .leading, spacing: 8) {
            Label("Book audio", systemImage: "waveform").font(.headline)
            if info.ready {
                Text("Audio ready: studio narration with word timings (\(size))")
                ProgressView(value: 1)
                Text("This book comes with a professional narration downloaded from the library. There is nothing to generate on the phone.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else if info.downloading {
                Text("Downloading the narration…")
                ProgressView()
            } else {
                Text("This book comes with a professional narration with word timings (\(size)). It downloads when you start listening, or right now.")
                    .foregroundStyle(.secondary)
                Button("Download now") { Task { try? await LinguaShared.shared.downloadNarration(bookId: bookId, retry: true) } }
                    .buttonStyle(.bordered)
            }
        }
        .font(.subheadline)
        .detailCard()
    }
}

/// Fila de estado de la traducción completa de un libro (lista y ficha): en marcha, fallida o en pausa; nada si no hay.
struct BookTranslationRow: View {
    @EnvironmentObject private var store: AppStore
    let bookId: String

    var body: some View {
        if let job = store.bookTranslations[bookId] {
            TranslationJobView(bookId: bookId, job: job)
        } else if let pause = store.translationStatus[bookId]?.paused {
            TranslationPausedView(bookId: bookId, pause: pause)
        }
    }
}

struct TranslationJobView: View {
    let bookId: String
    let job: BookTranslationState

    var body: some View {
        let language = Languages.nameInSentence(job.target)
        VStack(alignment: .leading, spacing: 4) {
            switch job {
            case is BookTranslationStatePreparing:
                Text("Preparing the translation into \(language)…").font(.caption)
                ProgressView()
            case let running as BookTranslationStateRunning:
                let fraction = running.total == 0 ? 0 : Double(running.done) / Double(running.total)
                Text("Translating into \(language): \(Int(fraction * 100))% (\(running.done)/\(running.total) sentences)").font(.caption)
                ProgressView(value: fraction)
            case let failed as BookTranslationStateFailed:
                Text(TranslationErrors.text(failed.error)).font(.caption).foregroundStyle(.red)
            default:
                EmptyView()
            }
            HStack(spacing: 16) {
                if job is BookTranslationStateFailed {
                    Button("Retry") { LinguaShared.shared.startBookTranslation(bookId: bookId, target: job.target) }
                    Button("Close") { LinguaShared.shared.dismissBookTranslationError(bookId: bookId) }
                } else {
                    Button("Cancel") { LinguaShared.shared.cancelBookTranslation(bookId: bookId) }
                }
            }
            .buttonStyle(.borderless).font(.caption)
        }
        .accessibilityIdentifier("translation.job.\(bookId)")
    }
}

struct TranslationPausedView: View {
    let bookId: String
    let pause: ImportedBookRepositoryTranslationPause

    var body: some View {
        let language = Languages.nameInSentence(pause.target)
        let fraction = pause.total == 0 ? 0 : Double(pause.done) / Double(pause.total)
        VStack(alignment: .leading, spacing: 4) {
            if pause.total == 0 {
                Text("Translation into \(language) paused").font(.caption)
            } else {
                Text("Translation into \(language) paused: \(Int(fraction * 100))% (\(pause.done)/\(pause.total) sentences)").font(.caption)
                ProgressView(value: fraction)
            }
            Button("Continue translation") { LinguaShared.shared.startBookTranslation(bookId: bookId, target: pause.target) }
                .buttonStyle(.borderless).font(.caption)
        }
    }
}

/// Texto de los errores tipados de la pretraducción (`TranslationError`), como `PackErrors` para las descargas.
enum TranslationErrors {
    static func text(_ error: TranslationError) -> String {
        switch error {
        case let e as TranslationErrorFailed: return String(localized: "Translation failed: \(e.detail)")
        case is TranslationErrorPairUnsupported: return String(localized: "This language pair can't be translated on the device.")
        case let e as TranslationErrorModelFailed: return String(localized: "The \(Languages.name(e.code)) translator could not be downloaded.")
        case let e as TranslationErrorPartial: return String(localized: "\(Int(e.failures)) of \(Int(e.total)) sentences could not be translated.")
        default: return String(localized: "Translation failed.")
        }
    }
}

/// Tarjeta de la ficha con el color del tema (crema en sepia).
private struct DetailCard: ViewModifier {
    @EnvironmentObject private var store: AppStore

    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(store.prefs?.theme == .sepia ? Color(ReaderTheme.sepiaCard) : Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
    }
}

private extension View {
    func detailCard() -> some View { modifier(DetailCard()) }
}
