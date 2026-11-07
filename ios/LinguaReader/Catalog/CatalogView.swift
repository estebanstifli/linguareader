import SwiftUI
import LinguaShared

/// Apartados de la biblioteca (el cajón de Android): filtros sobre el mismo catálogo.
enum CatalogSection: Hashable, Identifiable {
    case reading, books, learn(String), shelf(Int64), trash

    var id: String {
        switch self {
        case .reading: return "reading"
        case .books: return "books"
        case .learn(let lang): return "learn-\(lang)"
        case .shelf(let id): return "shelf-\(id)"
        case .trash: return "trash"
        }
    }
}

/// Pantallas a las que se navega desde el catálogo: ficha del libro, lector (con o sin voz al abrir), idiomas, vocabulario,
/// ajustes y edición de un libro.
enum CatalogDestination: Hashable {
    case detail(String), reader(String, Bool), languages, vocabulary, settings, edit(String)

    @ViewBuilder var view: some View {
        switch self {
        case .detail(let bookId): BookDetailView(bookId: bookId)
        case .reader(let bookId, let listen): ReaderView(bookId: bookId, listen: listen)
        case .languages: LanguagesView()
        case .vocabulary: VocabularyView()
        case .settings: SettingsView()
        case .edit(let bookId): BookEditView(bookId: bookId)
        }
    }
}

struct CatalogView: View {
    @EnvironmentObject private var store: AppStore
    @State private var section: CatalogSection? = .books
    @State private var columns = NavigationSplitViewVisibility.doubleColumn

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            SectionsList(selection: $section)
        } detail: {
            NavigationStack {
                BookListView(section: section ?? .books)
                    .navigationDestination(for: CatalogDestination.self) { $0.view }
            }
        }
        .task(id: store.learningLanguages.first) {
            // Tras la primera apertura, la biblioteca del primer idioma que se aprende es la pantalla de inicio.
            if let lang = store.learningLanguages.first, section == .books, store.books.isEmpty == false || store.libraryStates.isEmpty == false {
                section = .learn(lang)
            }
        }
    }
}

private struct SectionsList: View {
    @EnvironmentObject private var store: AppStore
    @Binding var selection: CatalogSection?

    var body: some View {
        List(selection: $selection) {
            Section {
                row(.reading, "Reading now", "clock.arrow.circlepath", count: store.books.filter { store.progress[$0.id] != nil && !trashed($0.id) }.count)
                row(.books, "Books", "books.vertical", count: store.books.filter { !trashed($0.id) }.count)
            }.themedRows()
            Section {
                ForEach(store.learningLanguages, id: \.self) { lang in
                    row(.learn(lang), "Learn \(Languages.name(lang))", "character.book.closed", count: store.books.filter { Languages.primary($0.learningLanguage) == lang && !trashed($0.id) }.count)
                }
                NavigationLink(value: CatalogDestination.languages) { Label("Learn other languages", systemImage: "globe") }
                    .accessibilityIdentifier("section.learn-other")
            }.themedRows()
            Section("Shelves") {
                ForEach(store.shelves?.shelves ?? [], id: \.id) { shelf in
                    row(.shelf(shelf.id), shelfName(shelf), shelfIcon(shelf), count: shelf.bookIds.count)
                        .swipeActions {
                            if shelf.isCustom {
                                Button(role: .destructive) { store.deleteCollection(shelf.id) } label: { Label("Delete", systemImage: "trash") }
                            }
                        }
                }.themedRows()
                row(.trash, "Trash", "trash", count: store.shelves?.trash.count ?? 0)
            }.themedRows()
            Section {
                NavigationLink(value: CatalogDestination.vocabulary) { Label("Vocabulary", systemImage: "textformat.abc") }
                    .accessibilityIdentifier("nav.vocabulary")
                NavigationLink(value: CatalogDestination.settings) { Label("Settings", systemImage: "gearshape") }
                    .accessibilityIdentifier("nav.settings")
            }.themedRows()
        }
        .themedScreen()
        .navigationTitle("LingoAudioBook")
        .navigationDestination(for: CatalogDestination.self) { $0.view }
    }

    private func trashed(_ bookId: String) -> Bool { store.shelves?.trashedIds.contains(bookId) ?? false }

    private func shelfName(_ shelf: Shelf) -> String {
        switch shelf.kind {
        case LibraryRepository.companion.FAVORITES: return "Favorites"
        case LibraryRepository.companion.TO_READ: return "To read"
        case LibraryRepository.companion.READ: return "Read"
        default: return shelf.name
        }
    }

    private func shelfIcon(_ shelf: Shelf) -> String {
        switch shelf.kind {
        case LibraryRepository.companion.FAVORITES: return "star"
        case LibraryRepository.companion.TO_READ: return "clock"
        case LibraryRepository.companion.READ: return "checkmark.circle"
        default: return "folder"
        }
    }

    private func row(_ section: CatalogSection, _ title: String, _ icon: String, count: Int) -> some View {
        NavigationLink(value: section) {
            Label {
                HStack {
                    Text(title)
                    Spacer()
                    if count > 0 { Text("\(count)").foregroundStyle(.secondary).font(.footnote) }
                }
            } icon: { Image(systemName: icon) }
        }
        .accessibilityIdentifier("section.\(section.id)")
    }
}

/// Lista o cuadrícula de libros de un apartado, con filtros de nivel, búsqueda y menú por libro.
struct BookListView: View {
    @EnvironmentObject private var store: AppStore
    let section: CatalogSection
    @State private var level: CefrLevel?
    @State private var query = ""
    @State private var newCollectionFor: BookSummary?

    private var grid: Bool { store.prefs?.libraryGrid ?? true }

    private var visible: [BookSummary] {
        let trashed = store.shelves?.trashedIds ?? []
        var list = store.books.filter { book in
            switch section {
            case .trash: return trashed.contains(book.id)
            case .reading: return store.progress[book.id] != nil && !trashed.contains(book.id)
            case .books: return !trashed.contains(book.id)
            case .learn(let lang): return Languages.primary(book.learningLanguage) == lang && !trashed.contains(book.id)
            case .shelf(let id): return (store.shelves?.shelves.first { $0.id == id }?.bookIds.contains(book.id) ?? false) && !trashed.contains(book.id)
            }
        }
        if let level { list = list.filter { $0.cefrLevel == level } }
        if !query.isEmpty {
            list = list.filter { $0.title.localizedCaseInsensitiveContains(query) || $0.authors.contains { $0.localizedCaseInsensitiveContains(query) } }
        }
        switch section {
        case .reading: return list.sorted { (store.progress[$0.id]?.updatedAt ?? 0) > (store.progress[$1.id]?.updatedAt ?? 0) }
        case .learn: return list.sorted { ($0.cefrLevel.ordinal, $0.title) < ($1.cefrLevel.ordinal, $1.title) }
        default: return list
        }
    }

    var body: some View {
        Group {
            if visible.isEmpty {
                ContentUnavailableView(emptyTitle, systemImage: "books.vertical", description: Text(emptyHint))
            } else if grid {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 12)], spacing: 16) {
                        ForEach(visible, id: \.id) { book in cell(book) }.themedRows()
                    }
                    .padding()
                }
            } else {
                List(visible, id: \.id) { book in cell(book) }
                    .listStyle(.plain)
            }
        }
        .accessibilityIdentifier("catalog.list")
        .themedScreen()
        .navigationTitle(title)
        .searchable(text: $query, prompt: "Title or author")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if case .learn(let lang) = section { translateToMenu(lang) }
                Button { store.setLibraryGrid(!grid) } label: { Image(systemName: grid ? "list.bullet" : "square.grid.2x2") }
                    .accessibilityIdentifier("catalog.layout")
            }
        }
        .safeAreaInset(edge: .top) {
            if case .trash = section {
                EmptyView()
            } else {
                VStack(spacing: 0) {
                    levelChips
                    if case .learn(let lang) = section {
                        LibraryBanner(lang: lang)
                        StartHereCard(lang: lang)
                    }
                }
            }
        }
        .sheet(item: $newCollectionFor) { book in
            NewCollectionSheet { name in store.createCollection(name, bookId: book.id) }
        }
    }

    /// «Translate to» del apartado de un idioma, como en Android: cambia tu idioma y baja los traductores que falten.
    private func translateToMenu(_ lang: String) -> some View {
        let current = Languages.primary(store.prefs?.translationLanguage ?? "en")
        return Menu {
            ForEach(store.nativeOptions, id: \.self) { code in
                Button {
                    Task { try? await LinguaShared.shared.setNativeLanguage(code: code) }
                } label: { Label(Languages.name(code), systemImage: code == current ? "checkmark" : "") }
            }
        } label: { Label("Translate to", systemImage: "character.bubble") }
        .accessibilityIdentifier("catalog.translateTo")
    }

    private var levelChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip("All levels", selected: level == nil) { level = nil }
                ForEach([CefrLevel.a1, .a2, .b1, .b2, .c1, .c2], id: \.self) { l in
                    chip(l.name, selected: level == l) { level = l }
                }.themedRows()
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
        }
        .themedBar()
    }

    private func chip(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text)
                .font(.subheadline)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(selected ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.12), in: Capsule())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func cell(_ book: BookSummary) -> some View {
        if case .trash = section {
            TrashRow(book: book)
        } else {
            NavigationLink(value: CatalogDestination.detail(book.id)) {
                if grid { BookGridCell(book: book) } else { BookRow(book: book) }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("catalog.row")
            .contextMenu { BookMenu(book: book, newCollection: { newCollectionFor = book }) }
        }
    }

    private var title: String {
        switch section {
        case .reading: return "Reading now"
        case .books: return "Books"
        case .learn(let lang): return "Learn \(Languages.name(lang))"
        case .shelf(let id): return store.shelves?.shelves.first { $0.id == id }.map { s in
            switch s.kind {
            case LibraryRepository.companion.FAVORITES: return "Favorites"
            case LibraryRepository.companion.TO_READ: return "To read"
            case LibraryRepository.companion.READ: return "Read"
            default: return s.name
            }
        } ?? "Shelf"
        case .trash: return "Trash"
        }
    }

    private var emptyTitle: String {
        switch section {
        case .trash: return "The trash is empty"
        case .reading: return "Nothing started yet"
        case .learn(let lang) where store.libraryStates[lang] is LibraryPackStateNotInstalled || store.libraryStates[lang] == nil:
            return "No \(Languages.name(lang)) books yet"
        default: return "No books here"
        }
    }

    private var emptyHint: String {
        if case .learn(let lang) = section, store.libraryCatalog[lang] != nil {
            return "Download the \(Languages.name(lang)) library in Settings › Languages."
        }
        return query.isEmpty ? "Books you add will appear here." : "Nothing matches your search."
    }
}

private struct TrashRow: View {
    @EnvironmentObject private var store: AppStore
    let book: BookSummary
    @State private var confirmDelete = false

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(book.title).font(.headline)
                Text(book.authors.joined(separator: ", ")).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            Button("Restore") { store.restore(book.id) }.buttonStyle(.bordered)
            if store.canDeleteForever(book.id) {
                Button(role: .destructive) { confirmDelete = true } label: { Image(systemName: "trash") }
                    .buttonStyle(.bordered)
            }
        }
        .confirmationDialog("Delete \"\(book.title)\" from this device?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete forever", role: .destructive) { store.deleteForever(book.id) }
        }
    }
}

private struct BookMenu: View {
    @EnvironmentObject private var store: AppStore
    let book: BookSummary
    let newCollection: () -> Void
    @State private var text: String?

    var body: some View {
        ForEach(store.shelves?.shelves ?? [], id: \.id) { shelf in
            let inShelf = shelf.bookIds.contains(book.id)
            Button {
                store.setInShelf(shelf.id, book.id, !inShelf)
            } label: {
                Label(shelfTitle(shelf), systemImage: inShelf ? "checkmark" : "")
            }
        }.themedRows()
        Button { newCollection() } label: { Label("New collection…", systemImage: "folder.badge.plus") }
        Divider()
        NavigationLink(value: CatalogDestination.edit(book.id)) { Label("Edit", systemImage: "pencil") }
        if let target = translateTarget {
            Button {
                LinguaShared.shared.startBookTranslation(bookId: book.id, target: target)
            } label: { Label("Translate the whole book into \(Languages.name(target))", systemImage: "character.bubble") }
        }
        Button {
            Task {
                if let txt = try? await LinguaShared.shared.bookText(bookId: book.id) {
                    let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(book.title).txt")
                    try? txt.write(to: url, atomically: true, encoding: .utf8)
                    await MainActor.run {
                        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
                        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController?.present(controller, animated: true)
                    }
                }
            }
        } label: { Label("Share as text", systemImage: "square.and.arrow.up") }
        Divider()
        Button(role: .destructive) { store.trash(book) } label: { Label("Move to trash", systemImage: "trash") }
    }

    /// Idioma al que traducir el libro entero: si no está ya en él, no trae esa traducción y no hay una en marcha o hecha.
    private var translateTarget: String? {
        let target = Languages.primary(store.prefs?.translationLanguage ?? "en")
        guard store.isImported(book.id), Languages.primary(book.learningLanguage) != target,
              !book.translationLanguages.contains(where: { Languages.primary($0) == target }),
              store.bookTranslations[book.id] == nil,
              store.translationStatus[book.id]?.done.contains(target) != true else { return nil }
        return target
    }

    private func shelfTitle(_ shelf: Shelf) -> String {
        switch shelf.kind {
        case LibraryRepository.companion.FAVORITES: return "Favorite"
        case LibraryRepository.companion.TO_READ: return "To read"
        case LibraryRepository.companion.READ: return "Read"
        default: return shelf.name
        }
    }
}

/// Descarga de la biblioteca de un idioma en la cabecera de su apartado, como `LibraryDownloadBanner` en Android.
private struct LibraryBanner: View {
    @EnvironmentObject private var store: AppStore
    let lang: String

    var body: some View {
        switch store.libraryStates[lang] {
        case let s as LibraryPackStateDownloading:
            banner { ProgressView(value: s.total > 0 ? Double(s.bytes) / Double(s.total) : 0) { Text("Downloading the \(Languages.name(lang)) library…").font(.footnote) } }
        case let s as LibraryPackStateInstalling:
            banner { ProgressView(value: s.total > 0 ? Double(s.done) / Double(s.total) : 0) { Text("Installing books \(s.done)/\(s.total)…").font(.footnote) } }
        case let s as LibraryPackStateFailed:
            banner {
                HStack {
                    Text(PackErrors.text(s.error)).font(.footnote).foregroundStyle(.red)
                    Spacer()
                    Button("Retry") { store.downloadLibrary(lang) }.buttonStyle(.bordered).font(.footnote)
                }
            }
        default:
            EmptyView()
        }
    }

    private func banner<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content().padding(.horizontal).padding(.vertical, 8).frame(maxWidth: .infinity).themedBar()
    }
}

/// «Where do I start?»: si no se ha empezado ningún libro del idioma, propone el más corto (como en Android).
private struct StartHereCard: View {
    @EnvironmentObject private var store: AppStore
    let lang: String

    var body: some View {
        let trashed = store.shelves?.trashedIds ?? []
        let books = store.books.filter { Languages.primary($0.learningLanguage) == lang && !trashed.contains($0.id) }
        if !books.isEmpty, !books.contains(where: { store.progress[$0.id] != nil }),
           let shortest = books.min(by: { ($0.estimatedMinutes, $0.cefrLevel.ordinal) < ($1.estimatedMinutes, $1.cefrLevel.ordinal) }) {
            NavigationLink(value: CatalogDestination.detail(shortest.id)) {
                HStack(spacing: 12) {
                    Image(systemName: "sparkles").font(.title3).foregroundStyle(.tint)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Where do I start?").font(.subheadline.bold())
                        Text("Try the shortest one: \(shortest.title) (\(shortest.cefrLevel.name), ≈ \(shortest.estimatedMinutes) min).")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.secondary)
                }
                .padding(.horizontal).padding(.vertical, 8)
            }
            .buttonStyle(.plain)
            .themedBar()
            .accessibilityIdentifier("catalog.startHere")
        }
    }
}

private struct NewCollectionSheet: View {
    @Environment(\.dismiss) private var dismiss
    let create: (String) -> Void
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Collection name", text: $name)
            }
            .themedScreen()
            .navigationTitle("New collection")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create and add") { create(name); dismiss() }.disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }
}
