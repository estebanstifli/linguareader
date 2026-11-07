import UIKit
import LinguaShared

/// Miniaturas de portada en disco (`Caches/LinguaReader/covers/<id>.jpg`), como `CoverThumbnails` en Android: la
/// portada real reducida o, para los documentos importados sin portada, una «página de texto» dibujada con el título y
/// las primeras líneas. Los libros de las bibliotecas graduadas siempre traen portada.
actor CoverThumbnails {
    static let shared = CoverThumbnails()

    private let directory: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("LinguaReader/covers", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()
    private var memory: [String: UIImage] = [:]
    private static let width: CGFloat = 240
    private static let textPageVersion = 1
    private static let textChars = 700

    /// - Parameter textPage: dibujar una página de texto si el libro no tiene portada (documentos importados).
    func load(_ book: BookSummary, textPage: Bool) async -> UIImage? {
        let key = book.cover != nil
            ? "c-\(book.id)"
            : (textPage ? "t-\(book.id)-\(book.wordCount)-\(Self.textPageVersion)" : "")
        if key.isEmpty { return nil }
        if let cached = memory[key] { return cached }
        let file = directory.appendingPathComponent(key.replacingOccurrences(of: "/", with: "_") + ".jpg")
        if let data = try? Data(contentsOf: file), let image = UIImage(data: data) {
            memory[key] = image
            return image
        }
        let image: UIImage?
        if let ref = book.cover {
            let data = try? await LinguaShared.shared.loadCover(bookId: book.id, cover: ref)
            image = data.flatMap { UIImage(data: $0) }?.preparingThumbnail(of: CGSize(width: Self.width, height: Self.width * 1.5))
        } else {
            let text = (try? await LinguaShared.shared.bookText(bookId: book.id)) ?? ""
            image = Self.drawTextPage(title: book.title, body: Self.body(of: text, title: book.title))
        }
        if let image {
            memory[key] = image
            if let jpeg = image.jpegData(compressionQuality: 0.85) { try? jpeg.write(to: file, options: .atomic) }
        }
        return image
    }

    /// Olvida la miniatura de un libro borrado o reimportado.
    func forget(_ bookId: String) {
        memory = memory.filter { !$0.key.contains(bookId) }
        if let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) {
            for f in files where f.lastPathComponent.contains(bookId.replacingOccurrences(of: "/", with: "_")) { try? FileManager.default.removeItem(at: f) }
        }
    }

    /// Primeras líneas del libro sin el título ni los autores que `bookText` antepone.
    private static func body(of text: String, title: String) -> String {
        var lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        if lines.first == title { lines.removeFirst() }
        let joined = lines.drop(while: { $0.trimmingCharacters(in: .whitespaces).isEmpty || $0 == title }).joined(separator: "\n")
        return String(joined.prefix(textChars))
    }

    /// Hoja 2:3 de papel con el título y las primeras líneas, como la primera página impresa del documento.
    static func drawTextPage(title: String, body: String) -> UIImage {
        let w = width, h = width * 1.5
        let margin = w * 0.1
        let inner = w - 2 * margin
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: w, height: h))
        return renderer.image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
            let titleStyle = NSMutableParagraphStyle()
            titleStyle.alignment = .center
            titleStyle.lineBreakMode = .byTruncatingTail
            let titleAttrs: [NSAttributedString.Key: Any] = [
                .font: ChapterLayout.serif(size: w * 0.062, weight: .bold, italic: false),
                .foregroundColor: UIColor(white: 0.125, alpha: 1), .paragraphStyle: titleStyle,
            ]
            let titleRect = CGRect(x: margin, y: margin, width: inner, height: w * 0.062 * 1.3 * 3)
            let titleHeight = min(titleRect.height, (title as NSString).boundingRect(with: CGSize(width: inner, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin], attributes: titleAttrs, context: nil).height)
            (title as NSString).draw(with: CGRect(x: margin, y: margin, width: inner, height: titleHeight), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: titleAttrs, context: nil)
            let bodyTop = margin + titleHeight + w * 0.05
            let bodyStyle = NSMutableParagraphStyle()
            bodyStyle.alignment = .justified
            bodyStyle.lineHeightMultiple = 1.15
            bodyStyle.lineBreakMode = .byTruncatingTail
            let bodyAttrs: [NSAttributedString.Key: Any] = [
                .font: ChapterLayout.serif(size: w * 0.036, weight: .regular, italic: false),
                .foregroundColor: UIColor(white: 0.33, alpha: 1), .paragraphStyle: bodyStyle,
            ]
            (body as NSString).draw(with: CGRect(x: margin, y: bodyTop, width: inner, height: h - bodyTop - margin), options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], attributes: bodyAttrs, context: nil)
        }
    }
}
