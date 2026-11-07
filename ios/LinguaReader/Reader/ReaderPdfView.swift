import SwiftUI
import PDFKit
import LinguaShared

/// El PDF original dentro del lector (como `ReaderPdfContent` en Android): las páginas tal cual con la frase y la
/// palabra que suenan resaltadas encima, toque en una palabra para traducirla y la vista siguiendo a la voz. Todo lo
/// demás (reproductor, fichas, progreso) es del lector, como en el modo texto.
struct ReaderPdfView: View {
    @ObservedObject var model: ReaderModel
    let theme: ReaderTheme

    var body: some View {
        ZStack(alignment: .top) {
            if let document = model.pdfDocument {
                PdfKitView(
                    document: document,
                    page: $model.pdfPage,
                    highlights: model.pdfHighlights,
                    onTap: { page, x, y in Task { await model.pdfTapped(page: page, x: x, y: y) } },
                    onUserScrolled: { page in model.pdfUserScrolled(page) }
                )
            } else if model.pdfMissing {
                ContentUnavailableView("The original PDF can't be found. It may have been moved or deleted.", systemImage: "doc.questionmark")
            } else {
                ProgressView()
            }
            status
        }
        .accessibilityIdentifier("reader.pdf")
    }

    /// Estado de la correspondencia texto ↔ páginas (preparando, fallida, cobertura baja).
    @ViewBuilder
    private var status: some View {
        if let progress = model.pdfLayoutProgress {
            VStack(spacing: 4) {
                Text("Preparing voice and translation over the PDF…").font(.footnote)
                ProgressView(value: progress)
            }
            .padding(10).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10)).padding(.top, 100).padding(.horizontal, 24)
        } else if model.pdfLayoutFailed {
            Text("The text couldn't be placed over the pages: you can read, but without highlighting or translation on tap.")
                .font(.footnote).padding(10).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10)).padding(.top, 100).padding(.horizontal, 24)
        } else if model.pdfLowCoverage {
            Text("This PDF barely matches its text (complex layout): highlighting may be missing in places.")
                .font(.footnote).padding(10).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10)).padding(.top, 100).padding(.horizontal, 24)
        }
    }
}
