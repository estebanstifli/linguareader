import SwiftUI
import LinguaShared

struct CatalogView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        List {
            if !store.status.isEmpty {
                Text(store.status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("catalog.status")
            }
            ForEach(store.books, id: \.id) { book in
                NavigationLink(value: book.id) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(book.title).font(.headline)
                        Text("\(book.cefrLevel.name) · \(book.learningLanguage) · \(book.wordCount) words · ≈\(book.estimatedMinutes) min")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityIdentifier("catalog.row")
            }
        }
        .accessibilityIdentifier("catalog.list")
        .navigationTitle("Library")
        .navigationDestination(for: String.self) { bookId in
            ReaderView(bookId: bookId)
        }
        .overlay {
            if store.books.isEmpty && store.status.isEmpty {
                ContentUnavailableView("No books yet", systemImage: "books.vertical")
            }
        }
    }
}
