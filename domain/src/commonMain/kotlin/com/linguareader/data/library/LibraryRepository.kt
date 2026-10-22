package com.linguareader.data.library

import com.linguareader.core.platform.epochMillis
import com.linguareader.data.content.ImportedBookRepository
import com.linguareader.data.db.BookOverrideEntity
import com.linguareader.data.db.CollectionBookEntity
import com.linguareader.data.db.CollectionEntity
import com.linguareader.data.db.LibraryDao
import com.linguareader.data.db.TrashEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Borra un libro importado del dispositivo (ficheros, narración y, en Android, el permiso del original). */
fun interface BookDeleter {
    suspend fun delete(bookId: String)
}

/** Estantería con los libros que contiene. */
data class Shelf(val id: Long, val name: String, val kind: String, val bookIds: Set<String>) {
    val isCustom: Boolean get() = kind == LibraryRepository.CUSTOM
}

/** Estado de la biblioteca: estanterías y papelera. */
data class LibraryState(val shelves: List<Shelf> = emptyList(), val trash: List<TrashEntity> = emptyList()) {
    val trashedIds: Set<String> by lazy { trash.map { it.bookId }.toSet() }
    fun shelvesOf(bookId: String): Set<Long> = shelves.filter { bookId in it.bookIds }.map { it.id }.toSet()
}

/**
 * Favoritos, «Para leer», «Leídos» (estanterías fijas, se crean la primera vez), colecciones del usuario y papelera.
 * Un libro en la papelera desaparece de la biblioteca; se puede restaurar o, si es importado, borrar del todo.
 */
class LibraryRepository(
    private val dao: LibraryDao,
    private val imported: ImportedBookRepository,
    private val deleter: BookDeleter,
) {
    private val seedLock = Mutex()
    private var seeded = false

    val state: Flow<LibraryState> =
        combine(dao.observeCollections(), dao.observeMemberships(), dao.observeTrash()) { collections, members, trash ->
            val byCollection = members.groupBy({ it.collectionId }, { it.bookId })
            LibraryState(
                shelves = collections.map { Shelf(it.id, it.name, it.kind, byCollection[it.id].orEmpty().toSet()) },
                trash = trash,
            )
        }.onStart { ensureSystemShelves() }

    private suspend fun ensureSystemShelves() = seedLock.withLock {
        if (seeded) return@withLock
        for ((kind, name) in SYSTEM) {
            if (dao.collectionOfKind(kind) == null) dao.insertCollection(CollectionEntity(name = name, kind = kind, createdAt = epochMillis()))
        }
        seeded = true
    }

    suspend fun setInShelf(shelfId: Long, bookId: String, inShelf: Boolean) {
        if (inShelf) dao.addToCollection(CollectionBookEntity(shelfId, bookId, epochMillis()))
        else dao.removeFromCollection(shelfId, bookId)
    }

    /** Crea una colección propia y, si se da [bookId], mete el libro en ella. */
    suspend fun createCollection(name: String, bookId: String? = null): Long {
        val id = dao.insertCollection(CollectionEntity(name = name.trim(), kind = CUSTOM, createdAt = epochMillis()))
        if (bookId != null) dao.addToCollection(CollectionBookEntity(id, bookId, epochMillis()))
        return id
    }

    suspend fun renameCollection(id: Long, name: String) = dao.renameCollection(id, name.trim())

    suspend fun deleteCollection(id: Long) = dao.deleteCollection(id)

    /** Guarda los datos corregidos de un libro (se aplican encima del original; ver [BookOverrideEntity]). */
    suspend fun editBook(bookId: String, title: String, author: String, language: String, level: String) =
        dao.putOverride(BookOverrideEntity(bookId, title.trim(), author.trim(), language, level))

    suspend fun hasEdits(bookId: String): Boolean = dao.override(bookId) != null

    /** Vuelve a los datos originales del libro. */
    suspend fun resetBook(bookId: String) = dao.deleteOverride(bookId)

    suspend fun trash(bookId: String, title: String) = dao.trash(TrashEntity(bookId, title, epochMillis()))

    suspend fun restore(bookId: String) = dao.restore(bookId)

    /** Solo los importados se pueden borrar del dispositivo; los incluidos en la app solo se restauran. */
    fun canDeleteForever(bookId: String): Boolean = imported.owns(bookId)

    suspend fun deleteForever(bookId: String) {
        if (!canDeleteForever(bookId)) return
        deleter.delete(bookId)
        dao.removeFromAllCollections(bookId)
        dao.deleteOverride(bookId)
        dao.restore(bookId)
    }

    companion object {
        const val FAVORITES = "favorites"
        const val TO_READ = "to_read"
        const val READ = "read"
        const val CUSTOM = "custom"
        private val SYSTEM = listOf(FAVORITES to "Favoritos", TO_READ to "Para leer", READ to "Leídos")
    }
}
