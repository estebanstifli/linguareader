package com.linguareader.data.db

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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

@Database(
    entities = [ReadingProgressEntity::class, BookmarkEntity::class, VocabularyEntity::class, MachineTranslationEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun machineTranslationDao(): MachineTranslationDao
}
