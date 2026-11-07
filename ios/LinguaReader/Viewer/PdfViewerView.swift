import SwiftUI
import PDFKit
import LinguaShared

/// Visor del PDF original de un libro importado (como `PdfViewerScreen` en Android): páginas tal cual con zoom,
/// indicador «página p de N» con «Ir a página», recuerda la última página y ofrece abrir el libro en modo lectura.
struct PdfViewerView: View {
    @EnvironmentObject private var store: AppStore
    let bookId: String
    @State private var document: PDFDocument?
    @State private var missing = false
    @State private var locked = false
    @State private var page = 0
    @State private var askPage = false
    @State private var pageText = ""

    private var book: BookSummary? { store.books.first { $0.id == bookId } }
    private var pageCount: Int { document?.pageCount ?? 0 }

    var body: some View {
        Group {
            if let document {
                PdfKitView(document: document, page: $page, highlights: [], onTap: nil, onUserScrolled: nil)
                    .ignoresSafeArea(edges: .bottom)
                    .overlay(alignment: .bottom) {
                        Button { pageText = "\(page + 1)"; askPage = true } label: {
                            Text("Page \(page + 1) of \(pageCount)").font(.footnote.monospacedDigit())
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(.thinMaterial, in: Capsule())
                        }
                        .padding(.bottom, 12)
                        .accessibilityLabel(Text("Page \(page + 1) of \(pageCount). Tap to jump to another page"))
                        .accessibilityIdentifier("pdf.page")
                    }
            } else if locked {
                ContentUnavailableView("This PDF is password-protected and can't be shown.", systemImage: "lock.doc")
            } else if missing {
                ContentUnavailableView("The original PDF can't be found. It may have been moved or deleted.", systemImage: "doc.questionmark")
            } else {
                ProgressView()
            }
        }
        .themedScreen()
        .navigationTitle(book?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: CatalogDestination.reader(bookId, false)) { Label("Open in reading mode", systemImage: "text.book.closed") }
                    .accessibilityIdentifier("pdf.openReader")
            }
        }
        .alert("Go to page", isPresented: $askPage) {
            TextField("1", text: $pageText).keyboardType(.numberPad)
            Button("Cancel", role: .cancel) {}
            Button("Go") {
                if let n = Int(pageText.trimmingCharacters(in: .whitespaces)), n >= 1, n <= pageCount { page = n - 1 }
            }
        }
        .task { await open() }
        .onChange(of: page) { _, p in UserDefaults.standard.set(p, forKey: "pdf-page.\(bookId)") }
    }

    private func open() async {
        guard let path = try? await LinguaShared.shared.originalPdfPath(bookId: bookId) else { missing = true; return }
        guard let doc = PDFDocument(url: URL(fileURLWithPath: path)) else { missing = true; return }
        if doc.isLocked, !doc.unlock(withPassword: "") { locked = true; return }
        page = min(UserDefaults.standard.integer(forKey: "pdf-page.\(bookId)"), max(0, doc.pageCount - 1))
        document = doc
    }
}

/// Caja a resaltar sobre una página (fracciones de la CropBox sin girar, y desde arriba), con su color.
struct PdfHighlight: Equatable {
    let page: Int
    let left: CGFloat, top: CGFloat, right: CGFloat, bottom: CGFloat
    let color: UIColor
    let rounded: Bool
}

/// `PDFView` con página enlazada, resaltados dibujados en una capa encima (no anotaciones) y toque → fracciones de
/// página. La capa se redibuja al cambiar de página y al desplazarse o hacer zoom (scroll interno).
struct PdfKitView: UIViewRepresentable {
    let document: PDFDocument
    @Binding var page: Int
    let highlights: [PdfHighlight]
    /// Toque: página y punto en fracciones (x, y desde arriba), o nil si fue fuera de una página.
    let onTap: ((Int, CGFloat, CGFloat) -> Void)?
    /// El usuario cambió de página con el dedo (no la app).
    let onUserScrolled: ((Int) -> Void)?

    func makeUIView(context: Context) -> PdfContainer {
        let container = PdfContainer()
        container.pdfView.document = document
        container.pdfView.autoScales = true
        container.pdfView.displayMode = .singlePageContinuous
        container.pdfView.displayDirection = .vertical
        container.pdfView.maxScaleFactor = 4
        container.pdfView.minScaleFactor = container.pdfView.scaleFactorForSizeToFit
        container.onPageChanged = { p in
            if p != page { DispatchQueue.main.async { page = p; onUserScrolled?(p) } }
        }
        container.onTap = onTap
        if let target = document.page(at: page) { container.pdfView.go(to: target) }
        return container
    }

    func updateUIView(_ container: PdfContainer, context: Context) {
        if container.pdfView.document !== document { container.pdfView.document = document }
        container.onTap = onTap
        container.onPageChanged = { p in
            if p != page { DispatchQueue.main.async { page = p; onUserScrolled?(p) } }
        }
        if let current = container.pdfView.currentPage, document.index(for: current) != page, let target = document.page(at: page) {
            container.programmatic = true
            container.pdfView.go(to: target)
        }
        container.setHighlights(highlights)
    }
}

final class PdfContainer: UIView {
    let pdfView = PDFView()
    private let overlay = HighlightOverlay()
    var onPageChanged: ((Int) -> Void)?
    var onTap: ((Int, CGFloat, CGFloat) -> Void)?
    var programmatic = false
    private var observers: [NSObjectProtocol] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        addSubview(pdfView)
        overlay.isUserInteractionEnabled = false
        overlay.backgroundColor = .clear
        overlay.pdfView = pdfView
        addSubview(overlay)
        observers.append(NotificationCenter.default.addObserver(forName: .PDFViewPageChanged, object: pdfView, queue: .main) { [weak self] _ in
            guard let self, let page = self.pdfView.currentPage, let document = self.pdfView.document else { return }
            self.overlay.setNeedsDisplay()
            if self.programmatic { self.programmatic = false; return }
            self.onPageChanged?(document.index(for: page))
        })
        observers.append(NotificationCenter.default.addObserver(forName: .PDFViewScaleChanged, object: pdfView, queue: .main) { [weak self] _ in self?.overlay.setNeedsDisplay() })
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        pdfView.addGestureRecognizer(tap)
        // El scroll interno avisa del desplazamiento: la capa sigue a las páginas.
        if let scroll = pdfView.subviews.compactMap({ $0 as? UIScrollView }).first {
            scroll.addObserver(self, forKeyPath: "contentOffset", options: [.new], context: nil)
            observedScroll = scroll
        }
    }

    private weak var observedScroll: UIScrollView?

    required init?(coder: NSCoder) { fatalError() }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observedScroll?.removeObserver(self, forKeyPath: "contentOffset")
    }

    override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey: Any]?, context: UnsafeMutableRawPointer?) {
        overlay.setNeedsDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        pdfView.frame = bounds
        overlay.frame = bounds
        if observedScroll == nil, let scroll = pdfView.subviews.compactMap({ $0 as? UIScrollView }).first {
            scroll.addObserver(self, forKeyPath: "contentOffset", options: [.new], context: nil)
            observedScroll = scroll
        }
    }

    func setHighlights(_ highlights: [PdfHighlight]) {
        guard highlights != overlay.highlights else { return }
        overlay.highlights = highlights
        overlay.setNeedsDisplay()
    }

    @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
        guard let onTap, let document = pdfView.document else { return }
        let point = recognizer.location(in: pdfView)
        guard let page = pdfView.page(for: point, nearest: false) else { onTap(-1, 0, 0); return }
        let inPage = pdfView.convert(point, to: page)
        let crop = page.bounds(for: .cropBox)
        guard crop.width > 0, crop.height > 0 else { return }
        let x = (inPage.x - crop.minX) / crop.width
        let y = 1 - (inPage.y - crop.minY) / crop.height
        onTap(document.index(for: page), x, y)
    }
}

/// Capa transparente que pinta las cajas de los tokens convertidas a coordenadas de la vista.
final class HighlightOverlay: UIView {
    weak var pdfView: PDFView?
    var highlights: [PdfHighlight] = []

    override func draw(_ rect: CGRect) {
        guard let pdfView, let document = pdfView.document, let ctx = UIGraphicsGetCurrentContext(), !highlights.isEmpty else { return }
        for h in highlights {
            guard let page = document.page(at: h.page) else { continue }
            let crop = page.bounds(for: .cropBox)
            let pageRect = CGRect(
                x: crop.minX + h.left * crop.width,
                y: crop.minY + (1 - h.bottom) * crop.height,
                width: (h.right - h.left) * crop.width,
                height: (h.bottom - h.top) * crop.height
            )
            let viewRect = pdfView.convert(pageRect, from: page)
            let local = convert(viewRect, from: pdfView)
            guard local.intersects(bounds) else { continue }
            ctx.setFillColor(h.color.cgColor)
            ctx.addPath(UIBezierPath(roundedRect: local.insetBy(dx: -1.5, dy: -1), cornerRadius: h.rounded ? 3 : 0).cgPath)
            ctx.fillPath()
        }
    }
}
