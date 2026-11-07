import SwiftUI
import LinguaShared

/// Estado de la biblioteca online («Library»), como `OnlineLibraryViewModel` en Android: búsqueda con filtros,
/// páginas, ficha de un libro y «Añadir a Mis libros» por el importador común.
@MainActor
final class OnlineLibraryModel: ObservableObject {
    @Published private(set) var query = ""
    @Published private(set) var language = ""
    @Published private(set) var category = ""
    @Published private(set) var level = ""
    @Published private(set) var books: [OnlineBook] = []
    @Published private(set) var categories: [CatalogCategory] = []
    @Published private(set) var languages: [CatalogLanguage] = []
    @Published private(set) var loading = true
    @Published private(set) var error = false
    @Published private(set) var page = 0
    @Published private(set) var pages = 0
    @Published private(set) var total = 0
    @Published var detail: OnlineBook?
    @Published private(set) var detailLoading = false
    @Published private(set) var detailError = false
    @Published private(set) var importing = false
    @Published private(set) var importError = false
    /// Id local del libro de la ficha si ya está en la biblioteca.
    @Published private(set) var localId: String?
    @Published private(set) var covers: [String: UIImage] = [:]
    private var listing: Task<Void, Never>?
    private var details: Task<Void, Never>?
    private var started = false

    var providerName: String { LinguaShared.shared.onlineCatalog.name }
    private var filter: OnlineCatalogFilter { OnlineCatalogFilter(query: query, language: language, category: category, level: level) }

    func start() {
        guard !started else { return }
        started = true
        load(append: false, debounce: false)
    }

    func setQuery(_ value: String) { query = value; load(append: false, debounce: true) }
    func setLanguage(_ value: String) { language = value; load(append: false, debounce: false) }
    func setCategory(_ value: String) { category = value; load(append: false, debounce: false) }
    func setLevel(_ value: String) { level = value; load(append: false, debounce: false) }
    func reload() { load(append: false, debounce: false) }
    func more() { if !loading, page < pages { load(append: true, debounce: false) } }

    private func load(append: Bool, debounce: Bool) {
        listing?.cancel()
        if !append { books = []; page = 0; pages = 0 }
        loading = true
        error = false
        listing = Task { [weak self] in
            guard let self else { return }
            if debounce { try? await Task.sleep(for: .milliseconds(350)) }
            if Task.isCancelled { return }
            do {
                let shared = LinguaShared.shared
                let next = append ? page + 1 : 1
                async let result = shared.catalogBooks(filter: filter, page: Int32(next))
                if categories.isEmpty { categories = (try? await shared.catalogCategories()) ?? [] }
                if languages.isEmpty { languages = (try? await shared.catalogLanguages()) ?? [] }
                let pageResult = try await result
                if Task.isCancelled { return }
                var merged = append ? books + pageResult.data : pageResult.data
                var seen = Set<Int32>()
                merged = merged.filter { seen.insert($0.id).inserted }
                books = merged
                page = Int(pageResult.pagination.page)
                pages = Int(pageResult.pagination.pages)
                total = Int(pageResult.pagination.total)
                loading = false
            } catch {
                if Task.isCancelled { return }
                loading = false
                self.error = true
            }
        }
    }

    func select(_ book: OnlineBook) {
        details?.cancel()
        detail = book
        detailLoading = true
        detailError = false
        localId = nil
        importError = false
        details = Task { [weak self] in
            guard let self else { return }
            do {
                let full = try await LinguaShared.shared.catalogBook(id: book.id)
                let local = try await LinguaShared.shared.catalogLocalId(book: full)
                if Task.isCancelled { return }
                detail = full
                localId = local
                detailLoading = false
            } catch {
                if Task.isCancelled { return }
                detailLoading = false
                detailError = true
            }
        }
    }

    func retryDetail() { if let detail { select(detail) } }

    func closeDetail() {
        guard !importing else { return }
        details?.cancel()
        detail = nil
        detailLoading = false
        detailError = false
        importError = false
    }

    /// Descarga e importa el libro de la ficha; devuelve el id local (o nil si falló).
    func add(level: CefrLevel) async -> String? {
        guard let book = detail, !importing else { return nil }
        importing = true
        importError = false
        defer { importing = false }
        do {
            let id = try await LinguaShared.shared.addCatalogBook(book: book, level: level)
            localId = id
            return id
        } catch {
            importError = true
            return nil
        }
    }

    func cover(for book: OnlineBook) async -> UIImage? {
        guard let url = book.cover_url else { return nil }
        if let cached = covers[url] { return cached }
        guard let data = try? await LinguaShared.shared.catalogCover(url: url), let image = UIImage(data: data)?.preparingThumbnail(of: CGSize(width: 400, height: 600)) else { return nil }
        covers[url] = image
        return image
    }
}

extension OnlineBook: Identifiable {}

/// «Library»: catálogo online de libros gratuitos (como `OnlineLibraryScreen` en Android): búsqueda, categorías,
/// idiomas, niveles, «Más populares» con paginación y ficha con «Añadir a Mis libros».
struct OnlineLibraryView: View {
    @EnvironmentObject private var store: AppStore
    @StateObject private var model = OnlineLibraryModel()
    @State private var pending: CatalogDestination?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                Text(model.providerName).font(.subheadline.bold()).foregroundStyle(.tint)
                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Search books or authors", text: Binding(get: { model.query }, set: { model.setQuery($0) }))
                        .textInputAutocapitalization(.never).autocorrectionDisabled().submitLabel(.search)
                        .accessibilityIdentifier("online.search")
                }
                .padding(10).background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
                Text("Categories").font(.footnote.bold())
                chips {
                    chip(String(localized: "All categories"), selected: model.category.isEmpty) { model.setCategory("") }
                    ForEach(model.categories, id: \.slug) { c in chip(c.name, selected: model.category == c.slug) { model.setCategory(c.slug) } }
                }
                Text("Languages").font(.footnote.bold())
                chips {
                    chip(String(localized: "All languages"), selected: model.language.isEmpty) { model.setLanguage("") }
                    ForEach(model.languages, id: \.code) { l in chip(Languages.name(l.code), selected: model.language == l.code) { model.setLanguage(l.code) } }
                }
                chips {
                    chip(String(localized: "All levels"), selected: model.level.isEmpty) { model.setLevel("") }
                    ForEach([CefrLevel.a1, .a2, .b1, .b2, .c1, .c2], id: \.self) { l in
                        chip(l.name, selected: model.level == l.name) { model.setLevel(l.name) }.accessibilityIdentifier("online.level.\(l.name)")
                    }
                }
                Text("Most popular").font(.title3.bold()).padding(.top, 4)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 16)], spacing: 20) {
                    ForEach(model.books) { book in
                        Button { model.select(book) } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                OnlineCoverView(book: book, model: model).aspectRatio(0.67, contentMode: .fit)
                                Text(book.title).font(.subheadline.bold()).lineLimit(2).multilineTextAlignment(.leading)
                                Text(book.author_names ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("online.book.\(book.id)")
                    }
                }
                footer
            }
            .padding(16)
        }
        .accessibilityIdentifier("online.books")
        .themedScreen()
        .navigationTitle("Library")
        .task { model.start() }
        .sheet(isPresented: Binding(get: { model.detail != nil }, set: { if !$0 { model.closeDetail() } })) {
            OnlineBookSheet(model: model) { id in
                model.closeDetail()
                pending = .detail(id)
            }
            .environmentObject(store)
        }
        .navigationDestination(item: $pending) { $0.view }
    }

    @ViewBuilder
    private var footer: some View {
        HStack {
            Spacer()
            if model.loading {
                ProgressView()
            } else if model.error {
                VStack(spacing: 6) {
                    Text("Could not connect to the library. Check your connection and retry.").font(.footnote).multilineTextAlignment(.center)
                    Button("Retry") { model.reload() }
                }
            } else if model.books.isEmpty {
                Text("No books match these filters. Try another search or language.").font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            } else if model.page < model.pages {
                Button("Load more") { model.more() }.buttonStyle(.bordered)
            }
            Spacer()
        }
        .padding(.vertical, 12)
    }

    private func chips<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 8) { content() } }
    }

    private func chip(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.subheadline).padding(.horizontal, 12).padding(.vertical, 6)
                .background(selected ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.12), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Portada del catálogo (descargada, ≤ 5 MB) o un recuadro con el título mientras no hay imagen.
private struct OnlineCoverView: View {
    let book: OnlineBook
    @ObservedObject var model: OnlineLibraryModel
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .topLeading) {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    Color.secondary.opacity(0.15)
                    VStack(spacing: 8) {
                        Image(systemName: "books.vertical").font(.title2)
                        Text(book.title).font(.subheadline).multilineTextAlignment(.center).lineLimit(5)
                    }
                    .padding(10)
                }
            }
            if let level = book.cefr_estimated {
                Text(level).font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 2).background(.thinMaterial, in: Capsule()).padding(6)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .task(id: book.cover_url) { image = await model.cover(for: book) }
    }
}

/// Ficha de un libro del catálogo: portada, resumen, nivel y «Añadir a Mis libros» (añadir y leer / solo añadir).
private struct OnlineBookSheet: View {
    @ObservedObject var model: OnlineLibraryModel
    /// Abrir el libro ya añadido (su ficha local).
    let onRead: (String) -> Void
    @State private var selectedLevel: CefrLevel?
    @State private var confirm = false

    private var level: CefrLevel? {
        model.detail?.cefr_estimated.flatMap { CefrLevel.companion.parse(value: $0) } ?? selectedLevel
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                if let book = model.detail {
                    VStack(spacing: 16) {
                        if model.detailLoading { ProgressView() }
                        if model.detailError {
                            Text("Could not connect to the library. Check your connection and retry.").font(.footnote)
                            Button("Retry") { model.retryDetail() }
                        }
                        OnlineCoverView(book: book, model: model).frame(width: 190, height: 284)
                        Text(book.title).font(.title2.bold()).multilineTextAlignment(.center)
                        if let author = book.author_names { Text(author).font(.headline).foregroundStyle(.secondary) }
                        Text(book.languageTags.map { Languages.name($0) }.joined(separator: " · ")).font(.subheadline)
                        if !model.detailLoading, !model.detailError {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Summary").font(.headline)
                                if let d = book.description_, !d.isEmpty { Text(d) } else { Text("No summary is available for this book yet.").foregroundStyle(.secondary) }
                                if let reading = book.source_reading_level {
                                    Text("Reading level").font(.headline)
                                    Text(reading)
                                }
                                if let estimated = book.cefr_estimated { Text("Estimated CEFR level: \(estimated)").font(.footnote).foregroundStyle(.secondary) }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding(20)
                }
            }
            .safeAreaInset(edge: .bottom) { actions }
            .themedScreen()
            .navigationTitle("Book details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { model.closeDetail() }.disabled(model.importing) } }
            .confirmationDialog(Text("Add “\(model.detail?.title ?? "")” to My Books? The book will be downloaded for offline reading."), isPresented: $confirm, titleVisibility: .visible) {
                Button("Add and read") { Task { if let level, let id = await model.add(level: level) { onRead(id) } } }
                Button("Add only") { Task { if let level { _ = await model.add(level: level) } } }
                Button("Cancel", role: .cancel) {}
            }
        }
        .interactiveDismissDisabled(model.importing)
    }

    @ViewBuilder
    private var actions: some View {
        if let book = model.detail, !model.detailLoading, !model.detailError {
            VStack(spacing: 8) {
                if model.importing {
                    ProgressView()
                    Text("Downloading and preparing your book…").font(.footnote)
                } else if let id = model.localId {
                    Text("Saved in My Books").font(.footnote).foregroundStyle(.secondary)
                    Button { onRead(id) } label: { Text("Read now").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent).controlSize(.large)
                        .accessibilityIdentifier("online.read")
                } else {
                    if model.importError { Text("Could not add this book. Check your connection and try again.").font(.footnote).foregroundStyle(.red) }
                    if book.download == nil { Text("No downloadable edition is available.").font(.footnote) }
                    if book.cefr_estimated.flatMap({ CefrLevel.companion.parse(value: $0) }) == nil, book.download != nil {
                        Text("Choose a level for My Books").font(.footnote.bold())
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach([CefrLevel.a1, .a2, .b1, .b2, .c1, .c2], id: \.self) { l in
                                    Button { selectedLevel = l } label: {
                                        Text(l.name).font(.subheadline).padding(.horizontal, 12).padding(.vertical, 6)
                                            .background(selectedLevel == l ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.12), in: Capsule())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                    Button { confirm = true } label: { Text("Add to My Books").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                        .disabled(level == nil || book.download == nil)
                        .accessibilityIdentifier("online.add")
                }
            }
            .padding(16)
            .background(.thinMaterial)
        }
    }
}
