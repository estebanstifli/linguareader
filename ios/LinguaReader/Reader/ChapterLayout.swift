import UIKit
import LinguaShared

/// Un tramo contiguo del texto de un pasaje dentro del texto atribuido del capítulo (offsets UTF-16).
struct ChapterSegment {
    let passageIndex: Int
    let passageOffset: Int
    let chapterOffset: Int
    let length: Int
}

/// Texto de un capítulo listo para TextKit (título + pasajes, con la traducción bajo cada frase si se pide y la
/// ilustración del pasaje si la tiene) y la correspondencia entre offsets del capítulo y offsets de cada pasaje.
/// Equivale a `BookBlocks` en Android.
struct ChapterLayout {
    let chapterIndex: Int
    let attributed: NSAttributedString
    let segments: [ChapterSegment]
    /// Bloques que no se parten entre páginas (frase + su traducción; ilustración + primera frase), en offsets del capítulo.
    let keepTogether: [NSRange]
    let key: String

    func chapterOffset(passageIndex: Int, offset: Int) -> Int? {
        guard let seg = segments.last(where: { $0.passageIndex == passageIndex && $0.passageOffset <= offset && offset <= $0.passageOffset + $0.length }) else {
            return segments.first { $0.passageIndex == passageIndex }.map { $0.chapterOffset }
        }
        return seg.chapterOffset + (offset - seg.passageOffset)
    }

    func location(chapterOffset: Int) -> (passageIndex: Int, offset: Int)? {
        guard let seg = segments.last(where: { $0.chapterOffset <= chapterOffset }) else { return segments.first.map { ($0.passageIndex, $0.passageOffset) } }
        let inside = min(max(chapterOffset - seg.chapterOffset, 0), seg.length)
        return (seg.passageIndex, seg.passageOffset + inside)
    }

    func chapterRange(passageIndex: Int, start: Int, end: Int) -> NSRange? {
        guard let s = chapterOffset(passageIndex: passageIndex, offset: start), let e = chapterOffset(passageIndex: passageIndex, offset: end), e > s else { return nil }
        return NSRange(location: s, length: e - s)
    }

    struct Style: Equatable {
        var fontScale: CGFloat = 1
        var lineHeight: CGFloat = 1.5
        var justification: TextJustification = .left
        var showTranslations = false
        var translationLanguage = "es"
        /// Traducciones automáticas por id de frase, para las frases sin traducción incluida en el libro.
        var machineTranslations: [String: String] = [:]
        var themeId = "light"
        /// Pasajes cuya ilustración ya está cargada (índice global del pasaje); vacío = sin ilustraciones.
        var illustratedPassages: [Int] = []
    }

    static func build(book: Book, chapterIndex: Int, style: Style, theme: ReaderTheme, images: [Int: UIImage] = [:]) -> ChapterLayout {
        let chapter = book.chapters[chapterIndex]
        let text = NSMutableAttributedString()
        var segments: [ChapterSegment] = []
        var keepTogether: [NSRange] = []
        let language = Languages.primary(style.translationLanguage)
        let size = 20 * style.fontScale
        let body = serif(size: size, weight: .regular, italic: false)
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineHeightMultiple = max(0.9, style.lineHeight / 1.25)
        paragraph.paragraphSpacing = size * 0.7
        switch style.justification {
        case .justified: paragraph.alignment = .justified
        case .justifiedHyphenated: paragraph.alignment = .justified; paragraph.hyphenationFactor = 0.9
        default: paragraph.alignment = .natural
        }
        let bodyAttributes: [NSAttributedString.Key: Any] = [.font: body, .foregroundColor: theme.text, .paragraphStyle: paragraph]

        let titleStyle = NSMutableParagraphStyle()
        titleStyle.alignment = .center
        titleStyle.paragraphSpacing = size * 1.2
        text.append(NSAttributedString(string: chapter.title + "\n", attributes: [.font: serif(size: 26 * style.fontScale, weight: .bold, italic: false), .foregroundColor: theme.text, .paragraphStyle: titleStyle]))

        let translationAttributes: [NSAttributedString.Key: Any] = [
            .font: serif(size: 16 * style.fontScale, weight: .regular, italic: true), .foregroundColor: theme.secondary, .paragraphStyle: paragraph,
        ]
        let imageStyle = NSMutableParagraphStyle()
        imageStyle.alignment = .center
        imageStyle.paragraphSpacing = size * 0.6
        imageStyle.paragraphSpacingBefore = size * 0.3

        for passage in chapter.passages {
            let index = Int(passage.index)
            // Ilustración al inicio del pasaje, entera (ancho de página, proporción conservada), unida a su primera frase.
            let imageStart = text.length
            if style.illustratedPassages.contains(index), let image = images[index] {
                let attachment = IllustrationAttachment(image: image)
                let line = NSMutableAttributedString(attachment: attachment)
                line.addAttributes([.paragraphStyle: imageStyle], range: NSRange(location: 0, length: line.length))
                line.append(NSAttributedString(string: "\n", attributes: [.paragraphStyle: imageStyle, .font: body]))
                text.append(line)
            }
            let hasImage = text.length > imageStart
            if style.showTranslations {
                for (n, sentence) in passage.sentences.enumerated() {
                    let start = Int(sentence.rangeStart), end = Int(sentence.rangeEnd)
                    let slice = utf16Slice(passage.text, start, end)
                    let blockStart = (n == 0 && hasImage) ? imageStart : text.length
                    segments.append(ChapterSegment(passageIndex: index, passageOffset: start, chapterOffset: text.length, length: slice.utf16.count))
                    text.append(NSAttributedString(string: slice + "\n", attributes: bodyAttributes))
                    let translation = sentence.translations[language] ?? sentence.translations[style.translationLanguage] ?? style.machineTranslations[sentence.id]
                    if let translation {
                        text.append(NSAttributedString(string: translation + "\n", attributes: translationAttributes))
                    }
                    keepTogether.append(NSRange(location: blockStart, length: text.length - blockStart))
                }
            } else {
                segments.append(ChapterSegment(passageIndex: index, passageOffset: 0, chapterOffset: text.length, length: passage.text.utf16.count))
                text.append(NSAttributedString(string: passage.text + "\n", attributes: bodyAttributes))
                if hasImage, let first = passage.sentences.first {
                    // La imagen y la primera frase del pasaje van juntas (la frase entera: el párrafo sigue en la página siguiente).
                    let firstEnd = text.length - passage.text.utf16.count - 1 + Int(first.rangeEnd)
                    keepTogether.append(NSRange(location: imageStart, length: max(1, firstEnd - imageStart)))
                }
            }
        }
        let key = "\(book.id)|\(chapterIndex)|\(style.fontScale)|\(style.lineHeight)|\(style.justification)|\(style.showTranslations)|\(style.translationLanguage)|\(style.machineTranslations.count)|\(style.themeId)|\(style.illustratedPassages)"
        return ChapterLayout(chapterIndex: chapterIndex, attributed: text, segments: segments, keepTogether: keepTogether, key: key)
    }

    static func serif(size: CGFloat, weight: UIFont.Weight, italic: Bool) -> UIFont {
        var descriptor = UIFont.systemFont(ofSize: size, weight: weight).fontDescriptor.withDesign(.serif) ?? UIFont.systemFont(ofSize: size, weight: weight).fontDescriptor
        if italic { descriptor = descriptor.withSymbolicTraits(.traitItalic) ?? descriptor }
        return UIFont(descriptor: descriptor, size: size)
    }

    static func utf16Slice(_ text: String, _ start: Int, _ end: Int) -> String {
        let utf16 = text.utf16
        guard start >= 0, end <= utf16.count, start <= end else { return "" }
        let s = utf16.index(utf16.startIndex, offsetBy: start)
        let e = utf16.index(utf16.startIndex, offsetBy: end)
        return String(text[s..<e])
    }
}

/// Ilustración de un pasaje como adjunto de texto: ocupa el ancho de la línea y conserva la proporción (nunca se
/// recorta); como mucho, la altura de 3/4 del ancho y el 60 % de la cara para que quepa con su primera frase.
final class IllustrationAttachment: NSTextAttachment {
    private let aspect: CGFloat

    init(image: UIImage) {
        aspect = image.size.width > 0 ? image.size.height / image.size.width : 0.75
        super.init(data: nil, ofType: nil)
        self.image = image
    }

    required init?(coder: NSCoder) { fatalError() }

    override func attachmentBounds(for textContainer: NSTextContainer?, proposedLineFragment lineFrag: CGRect, glyphPosition position: CGPoint, characterIndex charIndex: Int) -> CGRect {
        let width = max(40, lineFrag.width)
        let pageHeight = (textContainer as? PagedTextContainer)?.pageHeight ?? .greatestFiniteMagnitude
        let maxHeight = max(60, min(width * 0.75, pageHeight * 0.6))
        var height = width * aspect
        var drawWidth = width
        if height > maxHeight {
            height = maxHeight
            drawWidth = aspect > 0 ? height / aspect : width
        }
        return CGRect(x: 0, y: 0, width: drawWidth, height: height)
    }
}
