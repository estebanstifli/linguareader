import SwiftUI
import LinguaShared

/// Portada real (reducida) o generada a partir del título, con la etiqueta de nivel.
struct BookCoverView: View {
    @EnvironmentObject private var store: AppStore
    let book: BookSummary
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .topLeading) {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                generated
            }
            Text(book.cefrLevel.name)
                .font(.caption2.bold())
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(.thinMaterial, in: Capsule())
                .padding(6)
        }
        .aspectRatio(2 / 3, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .task(id: book.id) { image = await store.cover(for: book) }
    }

    private var generated: some View {
        let hue = Double(abs(book.title.hashValue) % 360) / 360
        return ZStack(alignment: .bottomLeading) {
            LinearGradient(colors: [Color(hue: hue, saturation: 0.45, brightness: 0.55), Color(hue: hue, saturation: 0.6, brightness: 0.35)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 4) {
                Text(book.title).font(.system(.subheadline, design: .serif).bold()).foregroundStyle(.white).lineLimit(4)
                Text(Languages.name(book.learningLanguage)).font(.caption2).foregroundStyle(.white.opacity(0.8))
            }
            .padding(8)
        }
    }
}

struct BookRow: View {
    @EnvironmentObject private var store: AppStore
    let book: BookSummary

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            BookCoverView(book: book).frame(width: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text(book.title).font(.headline)
                if !book.authors.isEmpty {
                    Text(book.authors.joined(separator: ", ")).font(.subheadline).foregroundStyle(.secondary)
                }
                Text("\(Languages.name(book.learningLanguage)) · ≈\(book.estimatedMinutes) min\(store.isLibraryBook(book.id) ? "" : " · Imported")")
                    .font(.caption).foregroundStyle(.secondary)
                if let fraction = store.progressFraction(book.id) {
                    HStack(spacing: 8) {
                        ProgressView(value: fraction)
                        Text("\(Int(fraction * 100)) %").font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }
}

struct BookGridCell: View {
    @EnvironmentObject private var store: AppStore
    let book: BookSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            BookCoverView(book: book)
            if let fraction = store.progressFraction(book.id) { ProgressView(value: fraction) }
            Text(book.title).font(.caption).lineLimit(2)
        }
    }
}
