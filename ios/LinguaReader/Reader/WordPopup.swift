import SwiftUI
import LinguaShared

/// Minitarjeta de traducción junto a la palabra tocada (como `WordPopup` en Android): la palabra (o la expresión que
/// la contiene) con su traducción, un trozo de la frase traducida y tres acciones: más información (hoja completa),
/// guardar en vocabulario y leer desde aquí. Se coloca debajo o encima de la palabra, donde haya más sitio.
struct WordPopupView: View {
    let card: WordCard
    /// Caja de la palabra en la página; nil = palabra fuera de la página (la tarjeta se centra).
    let anchor: CGRect?
    let area: CGSize
    @ObservedObject var model: ReaderModel
    @State private var size = CGSize(width: 240, height: 90)

    private let margin: CGFloat = 8
    private let gap: CGFloat = 6

    var body: some View {
        content
            .padding(.leading, 12).padding(.trailing, 4).padding(.top, 10).padding(.bottom, 2)
            .frame(minWidth: 160, maxWidth: 300, alignment: .leading)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
            .background(GeometryReader { geo in Color.clear.onAppear { size = geo.size }.onChange(of: geo.size) { _, s in size = s } })
            .position(position)
            .accessibilityIdentifier("word.popup")
            .transition(.opacity)
    }

    private var position: CGPoint {
        guard let anchor else { return CGPoint(x: area.width / 2, y: area.height / 2) }
        let above = anchor.minY
        let below = area.height - anchor.maxY
        let y = below >= above ? anchor.maxY + gap + size.height / 2 : anchor.minY - gap - size.height / 2
        let x = min(max(anchor.midX, margin + size.width / 2), max(margin + size.width / 2, area.width - margin - size.width / 2))
        return CGPoint(x: x, y: min(max(y, size.height / 2), max(size.height / 2, area.height - size.height / 2)))
    }

    private var content: some View {
        let lookup = card.lookup
        let expression = lookup.expression.flatMap { $0.isMultiToken ? $0 : nil }
        let result = expression != nil ? (lookup.expressionTranslation ?? lookup.word) : lookup.word
        return VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(expression?.surface ?? card.token.text).font(.headline).foregroundStyle(.tint).lineLimit(2)
                Text("→").font(.headline).foregroundStyle(.secondary)
                if let translated = result as? TranslationResultTranslated {
                    Text(translated.text).font(.headline.weight(.semibold)).lineLimit(2)
                } else {
                    Text(TranslationLabels.unavailable(result, target: model.translationLanguage)).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            .padding(.trailing, 8)
            if let sentence = lookup.sentenceTranslation as? TranslationResultTranslated {
                Text(WordPopupView.excerpt(sentence.text, around: position(of: lookup)))
                    .font(.footnote).italic().foregroundStyle(.secondary).lineLimit(2).padding(.trailing, 8)
            }
            HStack(spacing: 4) {
                Button { model.showFullCard = true } label: { Image(systemName: "info.circle") }
                    .accessibilityLabel("More information")
                    .accessibilityIdentifier("word.moreInfo")
                Button { Task { await model.saveWord(card) } } label: { Image(systemName: card.saved ? "star.fill" : "star") }
                    .disabled(card.saved)
                    .accessibilityLabel(card.saved ? Text("Saved in vocabulary") : Text("Save to vocabulary"))
                    .accessibilityIdentifier("word.save")
                Button {
                    Task { await model.readFrom(chapterOffset: model.layout?.chapterOffset(passageIndex: card.passageIndex, offset: Int(card.token.rangeStart)) ?? 0) }
                } label: { Image(systemName: "play.circle") }
                    .accessibilityLabel("Play from here")
                if TranslationLabels.isMachine(result) {
                    Spacer(minLength: 4)
                    Text("Machine translation").font(.caption2).foregroundStyle(.secondary).padding(.trailing, 8)
                }
            }
            .font(.title3)
            .buttonStyle(.borderless)
        }
    }

    /// Posición relativa (0..1) de la palabra en su frase: guía qué trozo de la traducción de la frase enseñar.
    private func position(of lookup: WordLookup) -> Double {
        let length = max(1, Int(lookup.sentence.rangeEnd) - Int(lookup.sentence.rangeStart))
        return min(1, max(0, Double(Int(lookup.token.rangeStart) - Int(lookup.sentence.rangeStart)) / Double(length)))
    }

    /// Trozo de unos `max` caracteres centrado en la posición relativa, cortado por palabras y con «…» en los extremos.
    static func excerpt(_ text: String, around fraction: Double, max: Int = 70) -> String {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let chars = Array(t)
        if chars.count <= max { return t }
        let center = Int((fraction * Double(chars.count)).rounded())
        var start = min(Swift.max(center - max / 2, 0), chars.count - max)
        var end = start + max
        if start > 0, let space = chars[start..<center].firstIndex(of: " ") { start = space + 1 }
        if end < chars.count, let space = chars[center..<end].lastIndex(of: " "), space > start { end = space }
        var body = String(chars[start..<end]).trimmingCharacters(in: .whitespaces)
        while let last = body.last, ",;:".contains(last) { body.removeLast() }
        return (start > 0 ? "…" : "") + body + (end < chars.count ? "…" : "")
    }
}
