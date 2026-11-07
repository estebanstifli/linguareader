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
    case detail(String), reader(String, Bool), readerOriginal(String), viewer(String), online, languages, vocabulary, settings, edit(String)

    @ViewBuilder var view: some View {
        switch self {
        case .detail(let bookId): BookDetailView(bookId: bookId)
        case .reader(let bookId, let listen): ReaderView(bookId: bookId, listen: listen)
        case .readerOriginal(let bookId): ReaderView(bookId: bookId, original: true)
        case .viewer(let bookId): PdfViewerView(bookId: bookId)
        case .online: OnlineLibraryView()
        case .languages: LanguagesView()
        case .vocabulary: VocabularyView()
        case .settings: SettingsView()
        case .edit(let bookId): BookEditView(bookId: bookId)
        }
    }
}

/// Nombres e iconos de las estanterías fijas (Favoritos, Para leer, Leídos) y de las colecciones del usuario.
enum Shelves {
    static func title(_ shelf: Shelf) -> String {
        switch shelf.kind {
        case LibraryRepository.companion.FAVORITES: return String(localized: "Favorites")
        case LibraryRepository.companion.TO_READ: return String(localized: "To read")
        case LibraryRepository.companion.READ: return String(localized: "Read")
        default: return shelf.name
        }
    }

    static func icon(_ shelf: Shelf) -> String {
        switch shelf.kind {
        case LibraryRepository.companion.FAVORITES: return "star"
        case LibraryRepository.companion.TO_READ: return "clock"
        case LibraryRepository.companion.READ: return "checkmark.circle"
        default: return "folder"
        }
    }

    static func isKind(_ shelf: Shelf, _ kind: String) -> Bool { shelf.kind == kind }
}

struct CatalogView: View {
    @EnvironmentObject private var store: AppStore
    @State private var section: CatalogSection? = .books
    @State private var columns = NavigationSplitViewVisibility.doubleColumn
    @StateObject private var importModel = ImportModel()
    @State private var path = NavigationPath()

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            SectionsList(selection: $section)
        } detail: {
            NavigationStack(path: $path) {
                BookListView(section: section ?? .books, importModel: importModel)
                    .navigationDestination(for: CatalogDestination.self) { $0.view }
            }
        }
        .task(id: store.learningLanguages.first) {
            // Tras la primera apertura, la biblioteca del primer idioma que se aprende es la pantalla de inicio.
            if let lang = store.learningLanguages.first, section == .books, store.books.isEmpty == false || store.libraryStates.isEmpty == false {
                section = .learn(lang)
            }
        }
        .sheet(isPresented: Binding(get: { importModel.isPresented }, set: { if !$0 { importModel.dismiss() } })) {
            ImportSheet(model: importModel) { bookId in
                // El libro importado se abre en su ficha, dentro de «Books».
                section = .books
                path = NavigationPath()
                path.append(CatalogDestination.detail(bookId))
            }
        }
        .onChange(of: store.openedDocument) { _, url in
            guard let url else { return }
            store.openedDocument = nil
            importModel.start(.file(url), translationTarget: store.prefs?.translationLanguage ?? "en")
        }
        .task {
            if let url = store.openedDocument {
                store.openedDocument = nil
                importModel.start(.file(url), translationTarget: store.prefs?.translationLanguage ?? "en")
            }
        }
    }
}

private struct SectionsList: View {
    @EnvironmentObject private var store: AppStore
    @Binding var selection: CatalogSection?
    @State private var deleteCollection: Shelf?

    var body: some View {
        List(selection: $selection) {
            Section {
                row(.reading, String(localized: "Reading now"), "clock.arrow.circlepath", count: store.books.filter { store.progress[$0.id] != nil && !trashed($0.id) }.count)
                row(.books, String(localized: "Books"), "books.vertical", count: store.books.filter { !trashed($0.id) }.count)
            }.themedRows()
            Section {
                ForEach(store.learningLanguages, id: \.self) { lang in
                    row(.learn(lang), String(localized: "Learn \(Languages.name(lang))"), "character.book.closed", count: store.books.filter { Languages.primary($0.learningLanguage) == lang && !trashed($0.id) }.count)
                }
                NavigationLink(value: CatalogDestination.languages) { Label("Learn other languages", systemImage: "globe") }
                    .accessibilityIdentifier("section.learn-other")
            }.themedRows()
            Section {
                NavigationLink(value: CatalogDestination.online) { Label("Library", systemImage: "building.columns") }
                    .accessibilityIdentifier("section.online")
            } footer: {
                Text("Free books online: search, add them to your library and read them offline.")
            }.themedRows()
            Section("Shelves") {
                ForEach(store.shelves?.shelves ?? [], id: \.id) { shelf in
                    row(.shelf(shelf.id), Shelves.title(shelf), Shelves.icon(shelf), count: shelf.bookIds.count)
                        .swipeActions {
                            if shelf.isCustom {
                                Button(role: .destructive) { deleteCollection = shelf } label: { Label("Delete", systemImage: "trash") }
                            }
                        }
                        .contextMenu {
                            if shelf.isCustom {
                                Button(role: .destructive) { deleteCollection = shelf } label: { Label("Delete collection", systemImage: "trash") }
                            }
                        }
                }.themedRows()
                row(.trash, String(localized: "Trash"), "trash", count: store.shelves?.trash.count ?? 0)
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
        .confirmationDialog(Text("Delete the collection “\(deleteCollection?.name ?? "")”? The books stay in the library."), isPresented: Binding(get: { deleteCollection != nil }, set: { if !$0 { deleteCollection = nil } }), titleVisibility: .visible) {
            Button("Delete collection", role: .destructive) {
                if let shelf = deleteCollection {
                    if selection == .shelf(shelf.id) { selection = .books }
                    store.deleteCollection(shelf.id)
                }
                deleteCollection = nil
            }
        }
    }

    private func trashed(_ bookId: String) -> Bool { store.shelves?.trashedIds.contains(bookId) ?? false }

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

/// Estado de lectura de un libro, para filtrar en la búsqueda (como `ReadStatus` en Android).
enum ReadStatus: CaseIterable, Hashable {
    case all, notStarted, reading, finished

    var label: String {
        switch self {
        case .all: return String(localized: "All")
        case .notStarted: return String(localized: "Not started")
        case .reading: return String(localized: "Reading")
        case .finished: return String(localized: "Finished")
        }
    }
}

enum CatalogSortOrder: CaseIterable, Hashable {
    /// El orden de cada apartado (importados recientes primero; en «Leyendo ahora», el último leído).
    case standard, title, author, level, recent, shortest

    var label: String {
        switch self {
        case .standard: return String(localized: "Default")
        case .title: return String(localized: "Title")
        case .author: return String(localized: "Author")
        case .level: return String(localized: "Level")
        case .recent: return String(localized: "Recently read")
        case .shortest: return String(localized: "Shortest")
        }
    }
}

/// Búsqueda de la biblioteca (lupa de la cabecera). Vacía = sin filtrar. Misma lógica que `CatalogSearch` en Android.
struct CatalogSearch: Equatable {
    var text = ""
    var status = ReadStatus.all
    var sort = CatalogSortOrder.standard
    /// Idioma del apartado («Books», estanterías) cuando tiene libros en varios; nil = todos.
    var language: String?

    var isActive: Bool { !text.trimmingCharacters(in: .whitespaces).isEmpty || status != .all || sort != .standard || language != nil }

    /// Minúsculas y sin tildes: «Karénina» se encuentra escribiendo «karenina».
    static func fold(_ s: String) -> String { s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil) }

    func apply(_ books: [BookSummary], progress: (String) -> (fraction: Double, updatedAt: Int64)?, finished: Set<String>) -> [BookSummary] {
        let needle = Self.fold(text.trimmingCharacters(in: .whitespaces))
        func statusOf(_ b: BookSummary) -> ReadStatus {
            let p = progress(b.id)
            if finished.contains(b.id) || (p != nil && p!.fraction >= 1) { return .finished }
            return p != nil ? .reading : .notStarted
        }
        let filtered = books.filter { b in
            (needle.isEmpty || Self.fold(b.title).contains(needle) || b.authors.contains { Self.fold($0).contains(needle) })
                && (status == .all || statusOf(b) == status)
                && (language == nil || Languages.primary(b.learningLanguage) == language)
        }
        let byTitle: (BookSummary, BookSummary) -> Bool = { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        switch sort {
        case .standard: return filtered
        case .title: return filtered.sorted(by: byTitle)
        case .author:
            return filtered.sorted { a, b in
                // Sin autor, al final.
                if a.authors.isEmpty != b.authors.isEmpty { return b.authors.isEmpty }
                let c = (a.authors.first ?? "").localizedStandardCompare(b.authors.first ?? "")
                return c == .orderedSame ? byTitle(a, b) : c == .orderedAscending
            }
        case .level: return filtered.sorted { a, b in a.cefrLevel.ordinal == b.cefrLevel.ordinal ? byTitle(a, b) : a.cefrLevel.ordinal < b.cefrLevel.ordinal }
        case .recent:
            return filtered.sorted { a, b in
                let ua = progress(a.id)?.updatedAt ?? Int64.min, ub = progress(b.id)?.updatedAt ?? Int64.min
                return ua == ub ? byTitle(a, b) : ua > ub
            }
        case .shortest: return filtered.sorted { a, b in a.estimatedMinutes == b.estimatedMinutes ? byTitle(a, b) : a.estimatedMinutes < b.estimatedMinutes }
        }
    }

    /// «"tolstoi" · Reading · English · Sort: title — 12».
    func summary(resultCount: Int) -> String {
        var parts: [String] = []
        let t = text.trimmingCharacters(in: .whitespaces)
        if !t.isEmpty { parts.append("“\(t)”") }
        if status != .all { parts.append(status.label) }
        if let language { parts.append(Languages.name(language)) }
        if sort != .standard { parts.append(String(localized: "Sort: \(sort.label)")) }
        return parts.joined(separator: " · ") + " — \(resultCount)"
    }
}

/// Lista o cuadrícula de libros de un apartado, con filtros de nivel, búsqueda, importación y menú por libro.
struct BookListView: View {
    /// En iPad (ancho regular) las portadas son algo más grandes: 4–6 columnas en vez de muchas pequeñas.
    @Environment(\.horizontalSizeClass) private var sizeClass
    @EnvironmentObject private var store: AppStore
    let section: CatalogSection
    @ObservedObject var importModel: ImportModel
    @State private var level: CefrLevel?
    @State private var collection: Int64?
    @State private var search = CatalogSearch()
    @State private var showSearch = false
    @State private var newCollectionFor: BookSummary?

    private var grid: Bool { store.prefs?.libraryGrid ?? true }

    /// Libros del apartado antes de la búsqueda (con el filtro de nivel y de colección).
    private var inSection: [BookSummary] {
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
        if let collection, let shelf = store.shelves?.shelves.first(where: { $0.id == collection }) { list = list.filter { shelf.bookIds.contains($0.id) } }
        switch section {
        case .reading: return list.sorted { (store.progress[$0.id]?.updatedAt ?? 0) > (store.progress[$1.id]?.updatedAt ?? 0) }
        case .learn: return list.sorted { ($0.cefrLevel.ordinal, $0.title) < ($1.cefrLevel.ordinal, $1.title) }
        default: return list
        }
    }

    private var visible: [BookSummary] {
        let finished = Set((store.shelves?.shelves ?? []).first { Shelves.isKind($0, LibraryRepository.companion.READ) }?.bookIds ?? [])
        return search.apply(inSection, progress: { id in
            guard let p = store.progress[id], let fraction = store.progressFraction(id) else { return nil }
            return (fraction, p.updatedAt)
        }, finished: finished)
    }

    /// Idiomas de los libros del apartado (el filtro de idioma solo sale si hay más de uno).
    private var languages: [String] {
        Array(Set(inSection.map { Languages.primary($0.learningLanguage) })).sorted { Languages.name($0) < Languages.name($1) }
    }

    private var collections: [Shelf] { (store.shelves?.shelves ?? []).filter { $0.isCustom } }

    var body: some View {
        Group {
            if visible.isEmpty {
                ContentUnavailableView(emptyTitle, systemImage: "books.vertical", description: Text(emptyHint))
            } else if grid {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: sizeClass == .regular ? 150 : 110), spacing: sizeClass == .regular ? 20 : 12)], spacing: 16) {
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
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if case .learn(let lang) = section { translateToMenu(lang) }
                if section != .trash { ImportMenu(model: importModel) }
                if section != .trash {
                    Button { showSearch = true } label: { Image(systemName: search.isActive ? "line.3.horizontal.decrease.circle.fill" : "magnifyingglass") }
                        .accessibilityLabel(search.isActive ? Text("Search (a search is active)") : Text("Search"))
                        .accessibilityIdentifier("catalog.search")
                }
                Button { store.setLibraryGrid(!grid) } label: { Image(systemName: grid ? "list.bullet" : "square.grid.2x2") }
                    .accessibilityIdentifier("catalog.layout")
                    .accessibilityLabel(grid ? Text("Show as list") : Text("Show as grid"))
            }
        }
        .safeAreaInset(edge: .top) {
            if case .trash = section {
                EmptyView()
            } else {
                VStack(spacing: 0) {
                    levelChips
                    if !collections.isEmpty, section == .books || section == .reading { collectionChips }
                    if search.isActive { activeSearchBar }
                    if case .learn(let lang) = section {
                        LibraryBanner(lang: lang)
                        StartHereCard(lang: lang)
                    }
                }
            }
        }
        .sheet(isPresented: $showSearch) {
            CatalogSearchSheet(search: $search, languages: languages, resultCount: visible.count)
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
                chip(String(localized: "All levels"), selected: level == nil) { level = nil }
                ForEach([CefrLevel.a1, .a2, .b1, .b2, .c1, .c2], id: \.self) { l in
                    chip(l.name, selected: level == l) { level = l }
                }.themedRows()
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
        }
        .themedBar()
    }

    /// Colecciones del usuario como chips (un toque filtra; otro quita el filtro), como en Android.
    private var collectionChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(collections, id: \.id) { shelf in
                    chip(shelf.name, selected: collection == shelf.id) { collection = collection == shelf.id ? nil : shelf.id }
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 6)
        }
        .themedBar()
    }

    /// Línea con la búsqueda activa: tocarla la reabre; la ✕ la quita.
    private var activeSearchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").font(.footnote)
            Text(search.summary(resultCount: visible.count)).font(.subheadline).lineLimit(1)
            Spacer()
            Button { search = CatalogSearch() } label: { Image(systemName: "xmark.circle.fill") }
                .accessibilityLabel("Remove search")
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal).padding(.bottom, 6)
        .contentShape(Rectangle())
        .onTapGesture { showSearch = true }
        .themedBar()
        .accessibilityIdentifier("catalog.activeSearch")
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
        case .reading: return String(localized: "Reading now")
        case .books: return String(localized: "Books")
        case .learn(let lang): return String(localized: "Learn \(Languages.name(lang))")
        case .shelf(let id): return store.shelves?.shelves.first { $0.id == id }.map(Shelves.title) ?? String(localized: "Shelf")
        case .trash: return String(localized: "Trash")
        }
    }

    private var emptyTitle: String {
        switch section {
        case .trash: return String(localized: "The trash is empty")
        case .reading: return String(localized: "Nothing started yet")
        case .learn(let lang) where store.libraryStates[lang] is LibraryPackStateNotInstalled || store.libraryStates[lang] == nil:
            return String(localized: "No \(Languages.name(lang)) books yet")
        default: return String(localized: "No books here")
        }
    }

    private var emptyHint: String {
        if search.isActive { return String(localized: "Nothing matches your search.") }
        if case .learn(let lang) = section, store.libraryCatalog[lang] != nil {
            return String(localized: "Download the \(Languages.name(lang)) library in Settings › Languages.")
        }
        return String(localized: "Books you add will appear here. Use + to import a file, a web page or copied text.")
    }
}

/// Tarjeta de búsqueda de la biblioteca: texto, estado de lectura, idioma (si el apartado tiene varios) y orden. Los
/// cambios se aplican al momento y el botón dice cuántos libros quedan (como `CatalogSearchCard` en Android).
struct CatalogSearchSheet: View {
    @Binding var search: CatalogSearch
    let languages: [String]
    let resultCount: Int
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("Title or author", text: $search.text)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .submitLabel(.search)
                            .onSubmit { dismiss() }
                            .focused($focused)
                            .accessibilityIdentifier("search.text")
                        if !search.text.isEmpty {
                            Button { search.text = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                                .accessibilityLabel("Clear text")
                        }
                    }
                    .padding(10)
                    .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))

                    group("Status") {
                        ForEach(ReadStatus.allCases, id: \.self) { st in
                            chip(st.label, selected: search.status == st) { search.status = st }
                        }
                    }
                    if languages.count > 1 {
                        group("Language") {
                            chip(String(localized: "All"), selected: search.language == nil) { search.language = nil }
                            ForEach(languages, id: \.self) { tag in
                                chip(Languages.name(tag), selected: search.language == tag) { search.language = search.language == tag ? nil : tag }
                            }
                        }
                    }
                    group("Sort by") {
                        ForEach(CatalogSortOrder.allCases, id: \.self) { order in
                            chip(order.label, selected: search.sort == order) { search.sort = order }
                        }
                    }
                }
                .padding(20)
            }
            .themedScreen()
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Clear") { search = CatalogSearch() }.disabled(!search.isActive)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: {
                        if resultCount == 0 { Text("No results") } else { Text("See \(resultCount) books") }
                    }
                    .accessibilityIdentifier("search.done")
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium, .large])
    }

    private func group<Content: View>(_ title: LocalizedStringKey, @ViewBuilder _ chips: () -> Content) -> some View {
        let content = chips()
        return VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.subheadline.bold()).foregroundStyle(.tint)
            FlowLayout(spacing: 8) { content }
        }
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
}

/// Chips en filas que saltan de línea cuando no caben (como `FlowRow`).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 320
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: width, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX { x = bounds.minX; y += rowHeight + spacing; rowHeight = 0 }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
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
                    .accessibilityLabel("Delete forever")
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

    var body: some View {
        ForEach(store.shelves?.shelves ?? [], id: \.id) { shelf in
            let inShelf = shelf.bookIds.contains(book.id)
            Button {
                store.setInShelf(shelf.id, book.id, !inShelf)
            } label: {
                Label(Shelves.title(shelf), systemImage: inShelf ? "checkmark" : "")
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
        Button { store.shareText(of: book) } label: { Label("Share as text", systemImage: "square.and.arrow.up") }
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
            banner { ProgressView(value: s.total > 0 ? Double(s.done) / Double(s.total) : 0) { Text("Installing books \(Int(s.done))/\(Int(s.total))…").font(.footnote) } }
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
                        Text("Try the shortest one: \(shortest.title) (\(shortest.cefrLevel.name), ≈ \(Int(shortest.estimatedMinutes)) min).")
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

struct NewCollectionSheet: View {
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
