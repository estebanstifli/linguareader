import SwiftUI
import UIKit
import LinguaShared

/// Resaltado actual en offsets del capítulo.
struct PageHighlight: Equatable {
    var sentence: NSRange?
    var word: NSRange?
    var style: HighlightStyle = .background
}

/// Página de un capítulo con TextKit 2: el capítulo se compone una vez en un contenedor de altura infinita y se
/// corta en páginas por líneas enteras; cada página se dibuja desplazando el contexto. El resaltado de frase y
/// palabra son atributos de dibujo (no cambian la composición). Toque → offset del capítulo.
struct ChapterPageView: UIViewRepresentable {
    let layout: ChapterLayout?
    let theme: ReaderTheme
    @Binding var pageIndex: Int
    let highlight: PageHighlight
    /// Offset del capítulo que debe quedar visible (tras un salto o al seguir a la voz); nil = ninguno.
    let showOffset: Int?
    let onShowOffsetApplied: () -> Void
    let onPages: ([Int]) -> Void
    let onTap: (Int?) -> Void

    func makeUIView(context: Context) -> PageTextView {
        let view = PageTextView()
        view.onTap = { offset in onTap(offset) }
        return view
    }

    func updateUIView(_ view: PageTextView, context: Context) {
        view.onTap = { offset in onTap(offset) }
        view.onPages = { pages in onPages(pages) }
        view.backgroundColor = theme.background
        if let layout { view.present(layout.attributed, key: layout.key) }
        view.apply(highlight: highlight, theme: theme)
        if let showOffset, let page = view.page(forOffset: showOffset) {
            DispatchQueue.main.async {
                if page != pageIndex { pageIndex = page }
                onShowOffsetApplied()
            }
        }
        if view.pageIndex != pageIndex {
            view.pageIndex = pageIndex
        }
    }
}

final class PageTextView: UIView {
    private let contentStorage = NSTextContentStorage()
    private let layoutManager = NSTextLayoutManager()
    private let container = NSTextContainer(size: CGSize(width: 320, height: CGFloat.greatestFiniteMagnitude))

    private(set) var layoutKey = ""
    private(set) var pageSize = CGSize.zero
    private var pageTops: [CGFloat] = []
    private(set) var pageOffsets: [Int] = []
    private var lines: [(rect: CGRect, start: Int)] = []
    private var currentHighlight = PageHighlight()
    private var highlighted: [NSTextRange] = []
    var onTap: ((Int?) -> Void)?
    var onPages: (([Int]) -> Void)?
    private var pending: (NSAttributedString, String)?

    /** Compone el texto en cuanto la vista tiene tamaño; hasta entonces lo deja pendiente. */
    func present(_ text: NSAttributedString, key: String) {
        guard key != layoutKey else { return }
        if bounds.width > 10, bounds.height > 10 {
            setText(text, key: key, pageSize: bounds.size)
            let pages = pageOffsets
            DispatchQueue.main.async { self.onPages?(pages) }
        } else {
            pending = (text, key)
        }
    }

    var pageIndex = 0 {
        didSet { if oldValue != pageIndex { setNeedsDisplay() } }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentStorage.addTextLayoutManager(layoutManager)
        layoutManager.textContainer = container
        container.lineFragmentPadding = 0
        isOpaque = true
        contentMode = .redraw
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped(_:))))
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 10, bounds.height > 10 else { return }
        if let (text, key) = pending {
            pending = nil
            setText(text, key: key, pageSize: bounds.size)
            let pages = pageOffsets
            DispatchQueue.main.async { self.onPages?(pages) }
        } else if bounds.size != pageSize, !layoutKey.isEmpty, let text = contentStorage.attributedString {
            setText(text, key: layoutKey, pageSize: bounds.size)
            let pages = pageOffsets
            DispatchQueue.main.async { self.onPages?(pages) }
        }
    }

    func setText(_ text: NSAttributedString, key: String, pageSize: CGSize) {
        layoutKey = key
        self.pageSize = pageSize
        highlighted = []
        currentHighlight = PageHighlight()
        contentStorage.performEditingTransaction { contentStorage.attributedString = text }
        container.size = CGSize(width: pageSize.width, height: CGFloat.greatestFiniteMagnitude)
        paginate()
        setNeedsDisplay()
    }

    /// Corta en páginas por líneas enteras y anota el primer carácter de cada página.
    private func paginate() {
        lines = []
        layoutManager.ensureLayout(for: layoutManager.documentRange)
        layoutManager.enumerateTextLayoutFragments(from: nil, options: [.ensuresLayout]) { fragment in
            let origin = fragment.layoutFragmentFrame.origin
            let elementStart = self.offset(of: fragment.rangeInElement.location)
            for line in fragment.textLineFragments {
                let rect = line.typographicBounds.offsetBy(dx: origin.x, dy: origin.y)
                self.lines.append((rect, elementStart + line.characterRange.location))
            }
            return true
        }
        var tops: [CGFloat] = [0]
        var offsets: [Int] = [lines.first?.start ?? 0]
        var top: CGFloat = 0
        for line in lines {
            if line.rect.maxY - top > pageSize.height, line.rect.minY > top {
                top = line.rect.minY
                tops.append(top)
                offsets.append(line.start)
            }
        }
        pageTops = tops
        pageOffsets = offsets
        if pageIndex >= tops.count { pageIndex = max(0, tops.count - 1) }
    }

    var pageCount: Int { pageTops.count }

    func page(forOffset offset: Int) -> Int? {
        guard !lines.isEmpty else { return nil }
        let line = lines.last { $0.start <= offset } ?? lines[0]
        let y = line.rect.minY
        var page = 0
        for (i, top) in pageTops.enumerated() where top <= y + 0.5 { page = i }
        return page
    }

    private func offset(of location: NSTextLocation) -> Int {
        contentStorage.offset(from: contentStorage.documentRange.location, to: location)
    }

    private func textRange(_ range: NSRange) -> NSTextRange? {
        guard let start = contentStorage.location(contentStorage.documentRange.location, offsetBy: range.location),
              let end = contentStorage.location(start, offsetBy: range.length) else { return nil }
        return NSTextRange(location: start, end: end)
    }

    func apply(highlight: PageHighlight, theme: ReaderTheme) {
        guard highlight != currentHighlight else { return }
        currentHighlight = highlight
        for range in highlighted { layoutManager.invalidateRenderingAttributes(for: range) }
        highlighted = []
        if let s = highlight.sentence, let range = textRange(s) {
            layoutManager.setRenderingAttributes([.backgroundColor: theme.sentenceHighlight], for: range)
            highlighted.append(range)
        }
        if let w = highlight.word, let range = textRange(w) {
            var attributes: [NSAttributedString.Key: Any]
            switch highlight.style {
            case .underline: attributes = [.underlineStyle: NSUnderlineStyle.thick.rawValue, .underlineColor: theme.wordHighlight]
            default: attributes = [.backgroundColor: theme.wordHighlight, .foregroundColor: theme.wordText]
            }
            layoutManager.setRenderingAttributes(attributes, for: range)
            highlighted.append(range)
        }
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), !pageTops.isEmpty else { return }
        let page = min(pageIndex, pageTops.count - 1)
        let top = pageTops[page]
        let bottom = page + 1 < pageTops.count ? pageTops[page + 1] : CGFloat.greatestFiniteMagnitude
        ctx.saveGState()
        ctx.clip(to: CGRect(x: 0, y: 0, width: bounds.width, height: min(bounds.height, bottom - top)))
        ctx.translateBy(x: 0, y: -top)
        layoutManager.enumerateTextLayoutFragments(from: nil, options: [.ensuresLayout]) { fragment in
            let frame = fragment.layoutFragmentFrame
            if frame.minY > bottom { return false }
            if frame.maxY >= top { fragment.draw(at: frame.origin, in: ctx) }
            return true
        }
        ctx.restoreGState()
    }

    @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
        let point = recognizer.location(in: self)
        onTap?(offset(at: point))
    }

    /// Offset del capítulo bajo un punto de la página, o nil si no hay texto.
    func offset(at point: CGPoint) -> Int? {
        guard !pageTops.isEmpty else { return nil }
        let page = min(pageIndex, pageTops.count - 1)
        let p = CGPoint(x: point.x, y: point.y + pageTops[page])
        guard let fragment = layoutManager.textLayoutFragment(for: p) else { return nil }
        let origin = fragment.layoutFragmentFrame.origin
        let local = CGPoint(x: p.x - origin.x, y: p.y - origin.y)
        let elementStart = offset(of: fragment.rangeInElement.location)
        for line in fragment.textLineFragments {
            let bounds = line.typographicBounds
            if local.y >= bounds.minY && local.y <= bounds.maxY {
                if local.x < bounds.minX - 4 || local.x > bounds.maxX + 4 { return nil }
                let index = line.characterIndex(for: local)
                if index < 0 { return nil }
                return elementStart + index
            }
        }
        return nil
    }
}
