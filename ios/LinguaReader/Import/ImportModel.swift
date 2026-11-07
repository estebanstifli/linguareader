import Foundation
import SwiftUI
import UniformTypeIdentifiers
import LinguaShared

/// Qué se quiere importar: un fichero (selector de archivos, «Abrir en…» desde otra app), una página web o texto pegado.
enum ImportSource {
    case file(URL)
    case webPage(String)
    case text(String, title: String?)
}

/// Importación en dos pasos, como `ImportViewModel` en Android: leer (progreso) → revisar título, autor, idioma, nivel
/// y traducción → guardar. Los errores llegan tipados desde Kotlin (`ImportOutcome.Failed`) y se traducen aquí.
@MainActor
final class ImportModel: ObservableObject {
    struct Review {
        let draft: ImportDraft
        var title: String
        var author: String
        var language: String
        var level: CefrLevel
        let languageOptions: [String]
        /// Idioma al que se traduce (preferencia del usuario).
        let translationTarget: String
        /// true = traducir el libro entero al importarlo; false = traducir mientras se lee.
        var translateNow = false
        var saving = false

        /// Solo tiene sentido ofrecer traducción si el idioma del libro y el de traducción difieren.
        var canTranslate: Bool { Languages.primary(language) != Languages.primary(translationTarget) }
    }

    enum State {
        case idle
        case reading(String)
        case review(Review)
        /// `message`: el motivo, traducido. `detail`: el mensaje técnico del importador, si aporta algo.
        case failed(message: String, detail: String?)
        case done(bookId: String)
    }

    @Published private(set) var state = State.idle
    private var task: Task<Void, Never>?
    /// Copia en la caché del fichero que se está importando; se borra al terminar o cancelar.
    private var staged: URL?

    var isPresented: Bool {
        if case .idle = state { return false }
        return true
    }

    /// - Parameter translationTarget: idioma al que traduce el usuario (`prefs.translationLanguage`).
    func start(_ source: ImportSource, translationTarget: String) {
        task?.cancel()
        cleanup()
        switch source {
        case .file(let url): state = .reading(String(localized: "Reading the document…"))
        case .webPage(let url): state = .reading(String(localized: "Downloading \(url)…"))
        case .text: state = .reading(String(localized: "Preparing the text…"))
        }
        task = Task { [weak self] in
            guard let self else { return }
            let outcome: ImportOutcome?
            switch source {
            case .file(let url):
                guard let copy = DocumentInbox.stage(url) else {
                    state = .failed(message: String(localized: "The file couldn't be read."), detail: url.lastPathComponent)
                    return
                }
                staged = copy.url
                outcome = try? await LinguaShared.shared.readDocument(path: copy.url.path, displayName: copy.name, mimeType: copy.mimeType)
            case .webPage(let url):
                outcome = try? await LinguaShared.shared.readWebPage(url: url)
            case .text(let text, let title):
                outcome = try? await LinguaShared.shared.readText(text: text, title: title)
            }
            if Task.isCancelled { return }
            switch outcome {
            case let draft as ImportOutcomeDraft:
                let d = draft.draft
                let options = (try? await LinguaShared.shared.importLanguageOptions(detected: d.detectedLanguage)) ?? ["en"]
                state = .review(Review(
                    draft: d,
                    title: d.document.title ?? "",
                    author: d.document.authors.joined(separator: ", "),
                    language: options.first ?? "en",
                    level: d.document.level ?? .b1,
                    languageOptions: options,
                    translationTarget: translationTarget
                ))
            case let failed as ImportOutcomeFailed:
                state = .failed(message: Self.message(for: failed.reason), detail: failed.detail)
                cleanup()
            default:
                state = .failed(message: String(localized: "The file couldn't be read."), detail: nil)
                cleanup()
            }
        }
    }

    func edit(_ transform: (inout Review) -> Void) {
        guard case .review(var review) = state, !review.saving else { return }
        transform(&review)
        state = .review(review)
    }

    func confirm() {
        guard case .review(var review) = state, !review.saving else { return }
        review.saving = true
        state = .review(review)
        task = Task { [weak self] in
            guard let self else { return }
            do {
                let id = try await LinguaShared.shared.commitImport(
                    draft: review.draft, title: review.title, author: review.author, language: review.language, level: review.level,
                    translateNow: review.translateNow && review.canTranslate
                )
                state = .done(bookId: id)
            } catch {
                state = .failed(message: String(localized: "The book couldn't be saved."), detail: error.localizedDescription)
            }
            cleanup()
        }
    }

    func dismiss() {
        task?.cancel()
        cleanup()
        state = .idle
    }

    private func cleanup() {
        if let staged { try? FileManager.default.removeItem(at: staged) }
        staged = nil
    }

    static func message(for reason: ImportException.Reason?) -> String {
        switch reason {
        case .some(.unsupportedFormat): return String(localized: "This file format can't be imported.")
        case .some(.drmProtected): return String(localized: "The file is protected (DRM or password). Only unprotected files can be imported.")
        case .some(.noText): return String(localized: "The document has no readable text.")
        case .some(.corrupt): return String(localized: "The file couldn't be read: it may be damaged.")
        default: return String(localized: "The file couldn't be read.")
        }
    }
}

/// Copia a la caché de la app el fichero que llega del selector de archivos o de otra app («Abrir en…»), con el
/// acceso con ámbito de seguridad que iOS exige, y lo entrega al importador compartido con su nombre y tipo.
enum DocumentInbox {
    struct Staged {
        let url: URL
        let name: String
        let mimeType: String?
    }

    static var directory: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("LinguaReader/import-inbox", isDirectory: true)
    }

    static func stage(_ source: URL) -> Staged? {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = source.lastPathComponent.isEmpty ? "document" : source.lastPathComponent
        let target = directory.appendingPathComponent(UUID().uuidString + "-" + name)
        var copyError: NSError?
        var copied = false
        // El coordinador fuerza la descarga de iCloud Drive si el fichero no está en el dispositivo.
        NSFileCoordinator().coordinate(readingItemAt: source, options: [.withoutChanges], error: &copyError) { url in
            copied = (try? FileManager.default.copyItem(at: url, to: target)) != nil
        }
        guard copied else { return nil }
        let type = UTType(filenameExtension: source.pathExtension)
        return Staged(url: target, name: name, mimeType: type?.preferredMIMEType)
    }

    /// Tipos que el selector de archivos deja elegir: los del importador compartido (el PDF llega con la fase 4).
    static var contentTypes: [UTType] {
        var types: [UTType] = [.plainText, .html, .rtf, .text]
        for identifier in ["org.idpf.epub-container", "net.daringfireball.markdown", "org.openxmlformats.wordprocessingml.document", "org.oasis-open.opendocument.text"] {
            if let t = UTType(identifier) { types.append(t) }
        }
        for imported in ["com.lingoaudiobook.fb2", "com.lingoaudiobook.mobi", "com.lingoaudiobook.subrip", "com.lingoaudiobook.webvtt"] {
            if let t = UTType(imported) { types.append(t) }
        }
        return types
    }
}
