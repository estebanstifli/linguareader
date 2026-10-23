import UIKit
import LinguaShared

/// Un tramo contiguo del texto de un pasaje dentro del texto atribuido del capítulo (offsets UTF-16).
struct ChapterSegment {
    let passageIndex: Int
    let passageOffset: Int
    let chapterOffset: Int
    let length: Int
}

/// Texto de un capítulo listo para TextKit (título + pasajes, con la traducción bajo cada frase si se pide) y la
/// correspondencia entre offsets del capítulo y offsets de cada pasaje. Equivale a `BookBlocks` en Android.
struct ChapterLayout {
    let chapterIndex: Int
    let attributed: NSAttributedString
    let segments: [ChapterSegment]
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
        var themeId = "light"
    }

    static func build(book: Book, chapterIndex: Int, style: Style, theme: ReaderTheme) -> ChapterLayout {
        let chapter = book.chapters[chapterIndex]
        let text = NSMutableAttributedString()
        var segments: [ChapterSegment] = []
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

        for passage in chapter.passages {
            let index = Int(passage.index)
            if style.showTranslations {
                for sentence in passage.sentences {
                    let start = Int(sentence.rangeStart), end = Int(sentence.rangeEnd)
                    let slice = utf16Slice(passage.text, start, end)
                    segments.append(ChapterSegment(passageIndex: index, passageOffset: start, chapterOffset: text.length, length: slice.utf16.count))
                    text.append(NSAttributedString(string: slice + "\n", attributes: bodyAttributes))
                    if let translation = sentence.translations[style.translationLanguage] {
                        text.append(NSAttributedString(string: translation + "\n", attributes: translationAttributes))
                    }
                }
            } else {
                segments.append(ChapterSegment(passageIndex: index, passageOffset: 0, chapterOffset: text.length, length: passage.text.utf16.count))
                text.append(NSAttributedString(string: passage.text + "\n", attributes: bodyAttributes))
            }
        }
        let key = "\(book.id)|\(chapterIndex)|\(style.fontScale)|\(style.lineHeight)|\(style.justification)|\(style.showTranslations)|\(style.translationLanguage)|\(style.themeId)"
        return ChapterLayout(chapterIndex: chapterIndex, attributed: text, segments: segments, key: key)
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
