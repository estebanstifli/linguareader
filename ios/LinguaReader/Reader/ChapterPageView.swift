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
/// corta en columnas por líneas enteras; cada cara se dibuja desplazando el contexto. Con `columns = 2` (doble página
/// en horizontal, como `PagedReader.kt`) una «página» del lector es un pliego con dos caras. El resaltado de frase y
/// palabra son atributos de dibujo (no cambian la composición). Toque → offset del capítulo; pulsación larga →
/// offset para la hoja de frase.
struct ChapterPageView: UIViewRepresentable {
    let layout: ChapterLayout?
    let theme: ReaderTheme
    /// 1 = una página; 2 = libro abierto (dos caras por pliego).
    var columns: Int = 1
    @Binding var pageIndex: Int
    let highlight: PageHighlight
    /// Palabra que señala la mano (nil = mano desactivada o sin reproducción), y lo que necesita para moverse.
    let pointer: PointerPlan?
    let playing: Bool
    let reduceMotion: Bool
    /// Offset del capítulo que debe quedar visible (tras un salto o al seguir a la voz); nil = ninguno.
    let showOffset: Int?
    /// Rango de la palabra tocada: la vista devuelve su caja en la página (para colocar el popup), o nil si no se ve.
    let anchor: NSRange?
    let onAnchorRect: (CGRect?) -> Void
    let onShowOffsetApplied: () -> Void
    let onPages: ([Int]) -> Void
    let onTap: (Int?) -> Void
    let onLongPress: (Int?) -> Void

    func makeUIView(context: Context) -> PageTextView {
        let view = PageTextView()
        view.onTap = { offset in onTap(offset) }
        view.onLongPress = { offset in onLongPress(offset) }
        return view
    }

    func updateUIView(_ view: PageTextView, context: Context) {
        view.onTap = { offset in onTap(offset) }
        view.onLongPress = { offset in onLongPress(offset) }
        view.onPages = { pages in onPages(pages) }
        view.backgroundColor = theme.background
        view.columns = columns
        if let layout { view.present(layout.attributed, key: layout.key, keepTogether: layout.keepTogether) }
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
        view.apply(pointer: pointer, playing: playing, reduceMotion: reduceMotion)
        if let anchor {
            let rect = view.pageRect(for: anchor)
            if rect != view.lastAnchorRect {
                view.lastAnchorRect = rect
                DispatchQueue.main.async { onAnchorRect(rect) }
            }
        } else {
            view.lastAnchorRect = nil
        }
    }
}

/// Contenedor de texto que conoce la altura de una cara: las ilustraciones no pasan de una parte de ella (en
/// horizontal la cara es baja y una lámina a todo el ancho no cabría con su primera frase).
final class PagedTextContainer: NSTextContainer {
    var pageHeight: CGFloat = .greatestFiniteMagnitude
}

final class PageTextView: UIView {
    /// Separación entre las dos caras del libro abierto (la misma que Android).
    static let gutter: CGFloat = 56

    private let contentStorage = NSTextContentStorage()
    private let layoutManager = NSTextLayoutManager()
    private let container = PagedTextContainer(size: CGSize(width: 320, height: CGFloat.greatestFiniteMagnitude))

    private(set) var layoutKey = ""
    private(set) var pageSize = CGSize.zero
    private var laidOutColumns = 1
    /// Borde superior (en el documento) de cada cara; con dos columnas, las caras 2k y 2k+1 forman la página k.
    private var columnTops: [CGFloat] = []
    /// Primer carácter de cada página (pliego), lo que recibe el modelo.
    private(set) var pageOffsets: [Int] = []
    private var lines: [(rect: CGRect, start: Int)] = []
    private var keepTogether: [NSRange] = []
    private var currentHighlight = PageHighlight()
    private var highlighted: [NSTextRange] = []
    /// Cajas del marcador (estilo «Marker»): se pintan detrás del texto en `draw`, no como atributo.
    private var markerRects: [CGRect] = []
    private var markerColor = UIColor.yellow
    var onTap: ((Int?) -> Void)?
    var onLongPress: ((Int?) -> Void)?
    var onPages: (([Int]) -> Void)?
    var lastAnchorRect: CGRect?
    private var pending: (NSAttributedString, String)?
    private let hand = HandPointerView()
    private var pointerInputs: (plan: PointerPlan?, playing: Bool, reduceMotion: Bool) = (nil, false, false)

    /// Caras por página. Cambiarlo vuelve a cortar el capítulo (el modelo mantiene la posición por offset).
    var columns = 1 {
        didSet {
            guard columns != oldValue else { return }
            setNeedsLayout()
        }
    }

    /** Compone el texto en cuanto la vista tiene tamaño; hasta entonces lo deja pendiente. */
    func present(_ text: NSAttributedString, key: String, keepTogether: [NSRange]) {
        guard key != layoutKey else { return }
        self.keepTogether = keepTogether
        if bounds.width > 10, bounds.height > 10 {
            setText(text, key: key, pageSize: bounds.size)
            let pages = pageOffsets
            DispatchQueue.main.async { self.onPages?(pages) }
        } else {
            pending = (text, key)
        }
    }

    var pageIndex = 0 {
        didSet { if oldValue != pageIndex { setNeedsDisplay(); refreshPointer(); lastAnchorRect = nil } }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentStorage.addTextLayoutManager(layoutManager)
        layoutManager.textContainer = container
        container.lineFragmentPadding = 0
        isOpaque = true
        contentMode = .redraw
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        let long = UILongPressGestureRecognizer(target: self, action: #selector(longPressed(_:)))
        long.minimumPressDuration = 0.45
        addGestureRecognizer(tap)
        addGestureRecognizer(long)
        addSubview(hand)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        hand.frame = bounds
        guard bounds.width > 10, bounds.height > 10 else { return }
        if let (text, key) = pending {
            pending = nil
            setText(text, key: key, pageSize: bounds.size)
            let pages = pageOffsets
            DispatchQueue.main.async { self.onPages?(pages) }
        } else if (bounds.size != pageSize || columns != laidOutColumns), !layoutKey.isEmpty, let text = contentStorage.attributedString {
            setText(text, key: layoutKey, pageSize: bounds.size)
            let pages = pageOffsets
            DispatchQueue.main.async { self.onPages?(pages) }
        }
    }

    /// Ancho de una cara.
    private var columnWidth: CGFloat {
        laidOutColumns > 1 ? max(40, (pageSize.width - Self.gutter * CGFloat(laidOutColumns - 1)) / CGFloat(laidOutColumns)) : pageSize.width
    }

    func setText(_ text: NSAttributedString, key: String, pageSize: CGSize) {
        layoutKey = key
        self.pageSize = pageSize
        laidOutColumns = max(1, columns)
        highlighted = []
        markerRects = []
        currentHighlight = PageHighlight()
        lastAnchorRect = nil
        container.pageHeight = pageSize.height
        container.size = CGSize(width: columnWidth, height: CGFloat.greatestFiniteMagnitude)
        contentStorage.performEditingTransaction { contentStorage.attributedString = text }
        paginate()
        setNeedsDisplay()
        refreshPointer()
    }

    /// Señala con la mano la palabra del plan si está en la página visible; si no, la mano espera y se esconde.
    func apply(pointer: PointerPlan?, playing: Bool, reduceMotion: Bool) {
        pointerInputs = (pointer, playing, reduceMotion)
        refreshPointer()
    }

    private func refreshPointer() {
        guard let plan = pointerInputs.plan else { hand.clear(); return }
        hand.update(
            target: pageRect(for: plan.word), lead: plan.lead.flatMap { pageRect(for: $0) }, step: plan.step,
            playing: pointerInputs.playing, reduceMotion: pointerInputs.reduceMotion
        )
    }

    /// Caras de la página visible: tramo del documento que muestra cada una y su desplazamiento horizontal.
    private func faces() -> [(top: CGFloat, bottom: CGFloat, x: CGFloat)] {
        guard !columnTops.isEmpty else { return [] }
        let page = min(pageIndex, pageCount - 1)
        return (0..<laidOutColumns).compactMap { c in
            let i = page * laidOutColumns + c
            guard i < columnTops.count else { return nil }
            let bottom = i + 1 < columnTops.count ? columnTops[i + 1] : CGFloat.greatestFiniteMagnitude
            return (columnTops[i], bottom, CGFloat(c) * (columnWidth + Self.gutter))
        }
    }

    /// Caja de un rango del capítulo en coordenadas de la página visible, o nil si no está en ella.
    func pageRect(for range: NSRange) -> CGRect? {
        guard !columnTops.isEmpty, let textRange = textRange(range) else { return nil }
        var union: CGRect?
        layoutManager.enumerateTextSegments(in: textRange, type: .standard, options: [.rangeNotRequired]) { _, frame, _, _ in
            union = union.map { $0.union(frame) } ?? frame
            return true
        }
        guard let frame = union else { return nil }
        guard let face = faces().first(where: { frame.midY >= $0.top && frame.midY < $0.bottom }) else { return nil }
        return frame.offsetBy(dx: face.x, dy: -face.top)
    }

    /// Cajas por línea de un rango, en coordenadas del documento (para el marcador).
    private func segmentRects(for range: NSRange) -> [CGRect] {
        guard let textRange = textRange(range) else { return [] }
        var rects: [CGRect] = []
        layoutManager.enumerateTextSegments(in: textRange, type: .standard, options: [.rangeNotRequired]) { _, frame, _, _ in
            rects.append(frame)
            return true
        }
        return rects
    }

    /// Corta en caras por líneas enteras y anota el primer carácter de cada página.
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
        var pageFirst = 0
        for (i, line) in lines.enumerated() {
            if line.rect.maxY - top > pageSize.height, line.rect.minY > top {
                // Si el corte cae dentro de un bloque (frase + traducción, ilustración + primera frase), el bloque
                // entero pasa a la cara siguiente.
                var breakAt = i
                if let block = keepTogether.first(where: { $0.location < line.start && line.start < $0.location + $0.length }),
                   let j = lines.indices.first(where: { lines[$0].start >= block.location }), j > pageFirst, j < i {
                    breakAt = j
                }
                top = lines[breakAt].rect.minY
                tops.append(top)
                offsets.append(lines[breakAt].start)
                pageFirst = breakAt
            }
        }
        columnTops = tops
        pageOffsets = stride(from: 0, to: offsets.count, by: laidOutColumns).map { offsets[$0] }
        if pageIndex >= pageCount { pageIndex = max(0, pageCount - 1) }
    }

    var pageCount: Int { (columnTops.count + laidOutColumns - 1) / laidOutColumns }

    func page(forOffset offset: Int) -> Int? {
        guard !lines.isEmpty else { return nil }
        let line = lines.last { $0.start <= offset } ?? lines[0]
        let y = line.rect.minY
        var column = 0
        for (i, top) in columnTops.enumerated() where top <= y + 0.5 { column = i }
        return column / laidOutColumns
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
        markerRects = []
        if let s = highlight.sentence, let range = textRange(s) {
            layoutManager.setRenderingAttributes([.backgroundColor: theme.sentenceHighlight], for: range)
            highlighted.append(range)
        }
        if let w = highlight.word {
            switch highlight.style {
            case .marker:
                // Marcador fluorescente: franja semitransparente sobre la mitad inferior de la línea, detrás del texto
                // (como `drawBehind` en Android); el texto no cambia de color.
                markerRects = segmentRects(for: w)
                markerColor = theme.wordHighlight.withAlphaComponent(0.55)
            case .underline:
                if let range = textRange(w) {
                    layoutManager.setRenderingAttributes([.underlineStyle: NSUnderlineStyle.thick.rawValue, .underlineColor: theme.wordHighlight], for: range)
                    highlighted.append(range)
                }
            default:
                if let range = textRange(w) {
                    layoutManager.setRenderingAttributes([.backgroundColor: theme.wordHighlight, .foregroundColor: theme.wordText], for: range)
                    highlighted.append(range)
                }
            }
        }
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        for face in faces() {
            ctx.saveGState()
            ctx.clip(to: CGRect(x: face.x, y: 0, width: columnWidth, height: min(bounds.height, face.bottom - face.top)))
            ctx.translateBy(x: face.x, y: -face.top)
            if !markerRects.isEmpty {
                ctx.setFillColor(markerColor.cgColor)
                for r in markerRects where r.maxY >= face.top && r.minY <= face.bottom {
                    let band = CGRect(x: r.minX - 2, y: r.minY + r.height * 0.42, width: r.width + 4, height: r.height * 0.62)
                    ctx.addPath(UIBezierPath(roundedRect: band, cornerRadius: 3).cgPath)
                    ctx.fillPath()
                }
            }
            layoutManager.enumerateTextLayoutFragments(from: nil, options: [.ensuresLayout]) { fragment in
                let frame = fragment.layoutFragmentFrame
                if frame.minY > face.bottom { return false }
                if frame.maxY >= face.top { fragment.draw(at: frame.origin, in: ctx) }
                return true
            }
            ctx.restoreGState()
        }
    }

    @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
        let point = recognizer.location(in: self)
        onTap?(offset(at: point))
    }

    @objc private func longPressed(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began else { return }
        let point = recognizer.location(in: self)
        onLongPress?(offset(at: point))
    }

    /// Offset del capítulo bajo un punto de la página (en cualquiera de las dos caras), o nil si no hay texto.
    func offset(at point: CGPoint) -> Int? {
        let faces = faces()
        guard !faces.isEmpty else { return nil }
        let face = faces.last { point.x >= $0.x - Self.gutter / 2 } ?? faces[0]
        guard point.x - face.x <= columnWidth + 4 else { return nil }
        let p = CGPoint(x: point.x - face.x, y: point.y + face.top)
        guard p.y < face.bottom, let fragment = layoutManager.textLayoutFragment(for: p) else { return nil }
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
