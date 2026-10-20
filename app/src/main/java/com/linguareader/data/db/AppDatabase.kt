package com.linguareader.data.db

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Último punto de lectura por libro: pasaje + offset de carácter (inicio de la última palabra oída). */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val passageId: String,
    val passageIndex: Int,
    val totalPassages: Int,
    val charOffset: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["bookId", "passageId", "charOffset"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val passageId: String,
    val passageIndex: Int,
    val charOffset: Int,
    val excerpt: String,
    val createdAt: Long,
)

@Entity(
    tableName = "vocabulary",
    indices = [Index(value = ["bookId", "term", "targetLanguage"], unique = true)],
)
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val bookTitle: String,
    val term: String,
    val lemma: String?,
    /** null si no había traducción disponible al guardarla (no se inventa). */
    val translation: String?,
    val sourceLanguage: String,
    val targetLanguage: String,
    val context: String,
    val createdAt: Long,
)

@Dao
interface ProgressDao {
    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ReadingProgressEntity?

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY passageIndex, charOffset")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VocabularyEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM vocabulary WHERE bookId = :bookId AND term = :term AND targetLanguage = :target)")
    suspend fun exists(bookId: String, term: String, target: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VocabularyEntity): Long

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * Traducción automática ya calculada (ML Kit). La clave es el texto exacto, no el libro: una frase
 * repetida se traduce una sola vez, y la pretraducción de un libro y la lectura comparten caché.
 */
@Entity(tableName = "machine_translations", primaryKeys = ["engineId", "source", "target", "textHash"])
data class MachineTranslationEntity(
    val engineId: String,
    val source: String,
    val target: String,
    /** sha256 del texto original normalizado. */
    val textHash: String,
    val translation: String,
    val createdAt: Long,
)

@Dao
interface MachineTranslationDao {
    @Query("SELECT translation FROM machine_translations WHERE engineId = :engine AND source = :source AND target = :target AND textHash = :hash")
    suspend fun get(engine: String, source: String, target: String, hash: String): String?

    @Upsert
    suspend fun put(entry: MachineTranslationEntity)

    @Query("DELETE FROM machine_translations WHERE source = :language OR target = :language")
    suspend fun deleteLanguage(language: String)
}

/**
 * Estantería de la biblioteca. [kind]: `favorites`, `to_read`, `read` (fijas, se crean solas) o `custom`
 * (colecciones del usuario, que puede renombrar y borrar).
 */
@Entity(tableName = "collections", indices = [Index(value = ["kind"])])
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val createdAt: Long,
)

@Entity(
    tableName = "collection_books",
    primaryKeys = ["collectionId", "bookId"],
    foreignKeys = [ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId"])],
)
data class CollectionBookEntity(val collectionId: Long, val bookId: String, val addedAt: Long)

/** Libro enviado a la papelera: desaparece de la biblioteca hasta restaurarlo o borrarlo del todo. */
@Entity(tableName = "trash")
data class TrashEntity(@PrimaryKey val bookId: String, val title: String, val trashedAt: Long)

/**
 * Datos de un libro corregidos por el usuario (pantalla «Editar»). Sirve para cualquier libro, incluido o importado:
 * el contenido original no se toca y estos valores se aplican encima al leerlo. null = el original.
 */
@Entity(tableName = "book_overrides")
data class BookOverrideEntity(
    @PrimaryKey val bookId: String,
    val title: String?,
    val author: String?,
    val language: String?,
    val cefrLevel: String?,
)

/**
 * Documento encontrado en el dispositivo al examinar archivos (aún no importado). [location] identifica el fichero:
 * una ruta absoluta o, en carpetas elegidas con el selector del sistema, un URI `content://`.
 */
@Entity(tableName = "device_documents", indices = [Index(value = ["format"]), Index(value = ["author"])])
data class DeviceDocumentEntity(
    @PrimaryKey val location: String,
    val fileName: String,
    /** Extensión en minúscula ("epub", "pdf"...). */
    val format: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** Carpeta que lo contiene, para mostrar y agrupar. */
    val folder: String,
    val title: String,
    /** Primer autor según los metadatos (EPUB, FB2); null si no consta. */
    val author: String?,
    val language: String?,
    val foundAt: Long,
)

/** Lo justo para decidir si un fichero cambió desde la última búsqueda. */
data class DeviceDocumentStamp(val location: String, val sizeBytes: Long, val modifiedAt: Long)

@Dao
interface DeviceDocumentDao {
    @Query("SELECT * FROM device_documents ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<DeviceDocumentEntity>>

    @Query("SELECT location, sizeBytes, modifiedAt FROM device_documents")
    suspend fun stamps(): List<DeviceDocumentStamp>

    @Upsert
    suspend fun upsert(documents: List<DeviceDocumentEntity>)

    @Query("DELETE FROM device_documents WHERE location IN (:locations)")
    suspend fun delete(locations: List<String>)

    @Query("DELETE FROM device_documents")
    suspend fun clear()
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM book_overrides")
    fun observeOverrides(): Flow<List<BookOverrideEntity>>

    @Query("SELECT * FROM book_overrides WHERE bookId = :bookId")
    suspend fun override(bookId: String): BookOverrideEntity?

    @Upsert
    suspend fun putOverride(entry: BookOverrideEntity)

    @Query("DELETE FROM book_overrides WHERE bookId = :bookId")
    suspend fun deleteOverride(bookId: String)

    @Query("SELECT * FROM collections ORDER BY CASE kind WHEN 'favorites' THEN 0 WHEN 'to_read' THEN 1 WHEN 'read' THEN 2 ELSE 3 END, createdAt")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE kind = :kind LIMIT 1")
    suspend fun collectionOfKind(kind: String): CollectionEntity?

    @Insert
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id AND kind = 'custom'")
    suspend fun renameCollection(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id AND kind = 'custom'")
    suspend fun deleteCollection(id: Long)

    @Query("SELECT * FROM collection_books")
    fun observeMemberships(): Flow<List<CollectionBookEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToCollection(entry: CollectionBookEntity)

    @Query("DELETE FROM collection_books WHERE collectionId = :collectionId AND bookId = :bookId")
    suspend fun removeFromCollection(collectionId: Long, bookId: String)

    @Query("DELETE FROM collection_books WHERE bookId = :bookId")
    suspend fun removeFromAllCollections(bookId: String)

    @Query("SELECT * FROM trash ORDER BY trashedAt DESC")
    fun observeTrash(): Flow<List<TrashEntity>>

    @Upsert
    suspend fun trash(entry: TrashEntity)

    @Query("DELETE FROM trash WHERE bookId = :bookId")
    suspend fun restore(bookId: String)
}

@Database(
    entities = [
        ReadingProgressEntity::class, BookmarkEntity::class, VocabularyEntity::class, MachineTranslationEntity::class,
        CollectionEntity::class, CollectionBookEntity::class, TrashEntity::class, BookOverrideEntity::class, DeviceDocumentEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun machineTranslationDao(): MachineTranslationDao
    abstract fun libraryDao(): LibraryDao
    abstract fun deviceDocumentDao(): DeviceDocumentDao
}
