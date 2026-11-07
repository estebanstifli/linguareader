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
                if store.isLibraryBook(book.id) {
                    Text("\(Languages.name(book.learningLanguage)) · ≈\(Int(book.estimatedMinutes)) min").font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("\(Languages.name(book.learningLanguage)) · ≈\(Int(book.estimatedMinutes)) min · Imported").font(.caption).foregroundStyle(.secondary)
                }
                if let fraction = store.progressFraction(book.id) {
                    HStack(spacing: 8) {
                        ProgressView(value: fraction)
                        Text("\(Int(fraction * 100)) %").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                BookTranslationRow(bookId: book.id)
            }
            Spacer(minLength: 0)
            ShelfQuickIcons(bookId: book.id)
        }
        .padding(.vertical, 4)
    }
}

/// Iconos rápidos de la fila (como en Android): Para leer, Leídos y Favorito; un toque entra o sale de la estantería.
struct ShelfQuickIcons: View {
    @EnvironmentObject private var store: AppStore
    let bookId: String

    var body: some View {
        VStack(spacing: 10) {
            ForEach(ordered, id: \.id) { shelf in
                let member = shelf.bookIds.contains(bookId)
                Button { store.setInShelf(shelf.id, bookId, !member) } label: {
                    Image(systemName: Shelves.icon(shelf) + (member ? ".fill" : ""))
                        .font(.footnote)
                        .foregroundStyle(member ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Shelves.title(shelf))
                .accessibilityValue(member ? Text("On") : Text("Off"))
            }
        }
        .padding(.top, 2)
    }

    private var ordered: [Shelf] {
        let kinds = [LibraryRepository.companion.TO_READ, LibraryRepository.companion.READ, LibraryRepository.companion.FAVORITES]
        return kinds.compactMap { kind in (store.shelves?.shelves ?? []).first { $0.kind == kind } }
    }
}

struct BookGridCell: View {
    @EnvironmentObject private var store: AppStore
    let book: BookSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            BookCoverView(book: book)
            ProgressView(value: store.progressFraction(book.id) ?? 0).opacity(store.progressFraction(book.id) == nil ? 0 : 1)
            Text(book.title).font(.caption).lineLimit(2, reservesSpace: true)
        }
        .frame(maxHeight: .infinity, alignment: .top)
    }
}
