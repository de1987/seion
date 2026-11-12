package org.seion.library

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One indexed audio file. [albumKey] groups album rows: the lower-cased album name plus the album artist, or the
 * folder when there is no album artist (so a compilation in one folder stays one album). Tracks without an album
 * tag are grouped by folder and shown under the folder's name.
 */
@Entity(
    tableName = "tracks",
    indices = [Index("path", unique = true), Index("folder"), Index("albumKey"), Index("artist"), Index("genre")]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val folder: String,
    val fileName: String,
    val title: String,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val albumKey: String,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val lastModified: Long,
    val sizeBytes: Long,
    val dateAdded: Long,
    val hasEmbeddedArt: Boolean,
    /** Where [title] came from: [TagSource.TAG], [TagSource.FALLBACK] (the file name) or [TagSource.ONLINE]. */
    @ColumnInfo(defaultValue = "0") val titleSource: Int = TagSource.TAG,
    /** [TagSource.TAG], [TagSource.MISSING] or [TagSource.ONLINE]. */
    @ColumnInfo(defaultValue = "0") val artistSource: Int = TagSource.TAG,
    /** [TagSource.TAG], [TagSource.MISSING] (the folder name is shown) or [TagSource.ONLINE]. */
    @ColumnInfo(defaultValue = "0") val albumSource: Int = TagSource.TAG,
    /** The online identification of an untagged file: [LookupState]; a row is looked up at most once. */
    @ColumnInfo(defaultValue = "0") val lookupState: Int = LookupState.NONE,
    val lookupAt: Long? = null,
    /** `musicbrainz` or `local` (decided without a request). */
    val lookupSource: String? = null,
    /** The recording MBID, for tracing where a value came from. */
    val lookupRef: String? = null
) {
    /** The album name to show: the tag, else the folder's name. */
    val albumTitle: String get() = album ?: folder.substringAfterLast('/')
}

/** Where a title/artist/album value came from. */
object TagSource {
    const val TAG = 0

    /** The title is the file name. */
    const val FALLBACK = 1

    /** No artist/album tag. */
    const val MISSING = 1
    const val ONLINE = 2
}

object LookupState {
    const val NONE = 0
    const val FOUND = 1
    const val NOT_FOUND = 2
}

data class AlbumRow(
    val albumKey: String,
    val album: String?,
    val artist: String?,
    val folder: String,
    val samplePath: String,
    val year: Int?,
    val trackCount: Int,
    val durationMs: Long
) {
    val title: String get() = album ?: folder.substringAfterLast('/')
}

/** How the Albums grid is ordered (its "Title ▾" chip; persisted in the settings as `album_sort`). */
enum class AlbumSort {
    /** A–Z ignoring case and a leading "The " (the library's rule; an untagged album by its folder's name). */
    TITLE,

    /** By album artist (the same rule), then title; albums without an artist last. */
    ARTIST,

    /** Newest release year first, albums without a year last. */
    YEAR,

    /** The album with the most recently added song first. */
    RECENT
}

data class NameCount(val name: String?, val trackCount: Int, val albumCount: Int)

/** An album row of the browse tree (TrackDao.browseAlbums). */
data class BrowseAlbumRow(
    val trackId: Long,
    val samplePath: String,
    val album: String?,
    val artist: String?,
    val folder: String,
    val trackCount: Int
) {
    val title: String get() = album ?: folder.substringAfterLast('/')
}

data class PathStamp(val path: String, val lastModified: Long)

// Sort keys ignore a leading "The " (always, not a setting).
private const val SORT_TITLE = "CASE WHEN title LIKE 'the %' THEN substr(title, 5) ELSE title END COLLATE NOCASE"

/**
 * The folder's own name (what [AlbumRow.title] shows for an untagged album), not its whole path: everything after the
 * last "/" (rtrim strips the trailing run of characters that aren't a "/").
 */
private const val FOLDER_NAME = "substr(folder, length(rtrim(folder, replace(folder, '/', ''))) + 1)"
private const val ALBUM_NAME = "COALESCE(album, $FOLDER_NAME)"
private const val SORT_ALBUM = "CASE WHEN $ALBUM_NAME LIKE 'the %' THEN substr($ALBUM_NAME, 5) ELSE $ALBUM_NAME END COLLATE NOCASE"

/** Artists are listed by album artist when the tag is present. */
private const val ARTIST_KEY = "COALESCE(albumArtist, artist)"
private const val SORT_ARTIST = "CASE WHEN $ARTIST_KEY LIKE 'the %' THEN substr($ARTIST_KEY, 5) ELSE $ARTIST_KEY END COLLATE NOCASE"
private const val TRACK_ORDER = "discNumber, trackNumber, title COLLATE NOCASE"

@Dao
interface TrackDao {
    @Query("SELECT path, lastModified FROM tracks")
    suspend fun stamps(): List<PathStamp>

    @Upsert
    suspend fun upsert(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE path IN (:paths)")
    suspend fun deletePaths(paths: List<String>)

    @Transaction
    suspend fun apply(upserts: List<TrackEntity>, removals: List<String>) {
        removals.chunked(500).forEach { deletePaths(it) }
        if (upserts.isNotEmpty()) upsert(upserts)
    }

    @Query("SELECT id FROM tracks WHERE path = :path")
    suspend fun idForPath(path: String): Long?

    @Query("SELECT * FROM tracks WHERE path IN (:paths)")
    suspend fun byPaths(paths: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    // --- The browse tree (Android Auto, AVRCP browsing; playback/BrowseTree.kt) ---

    /** A page of the Songs list (its order, then the row id so pages never overlap). */
    @Query("SELECT * FROM tracks WHERE durationMs >= :minMs ORDER BY $SORT_TITLE, id LIMIT :limit OFFSET :offset")
    suspend fun songsPage(minMs: Long, limit: Int, offset: Int): List<TrackEntity>

    /**
     * The Albums grid (title order) with a track id per album to name it by: an album key can hold a folder's path, which
     * a browser must never see. [BrowseAlbumRow.trackId] is the album's first track by path, the grid's cover track
     * (SQLite takes a bare column from the row of the query's only MIN()).
     */
    @Query(
        """SELECT id AS trackId, MIN(path) AS samplePath, album, COALESCE(albumArtist, artist) AS artist, folder,
           COUNT(*) AS trackCount FROM tracks WHERE durationMs >= :minMs GROUP BY albumKey ORDER BY $SORT_ALBUM"""
    )
    suspend fun browseAlbums(minMs: Long): List<BrowseAlbumRow>

    @Query("SELECT albumKey FROM tracks WHERE id = :id")
    suspend fun albumKeyOf(id: Long): String?

    @Query("SELECT * FROM tracks WHERE durationMs >= :minMs ORDER BY $SORT_TITLE")
    fun songs(minMs: Long): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks WHERE durationMs >= :minMs")
    fun songCount(minMs: Long): Flow<Int>

    @Query(
        """SELECT albumKey, album, COALESCE(albumArtist, artist) AS artist, folder, MIN(path) AS samplePath,
           MAX(year) AS year, COUNT(*) AS trackCount, SUM(durationMs) AS durationMs
           FROM tracks WHERE durationMs >= :minMs GROUP BY albumKey
           ORDER BY $SORT_ALBUM"""
    )
    fun albums(minMs: Long): Flow<List<AlbumRow>>

    /** By album artist (the leading-"The " rule), then title. */
    @Query(
        """SELECT albumKey, album, COALESCE(albumArtist, artist) AS artist, folder, MIN(path) AS samplePath,
           MAX(year) AS year, COUNT(*) AS trackCount, SUM(durationMs) AS durationMs
           FROM tracks WHERE durationMs >= :minMs GROUP BY albumKey
           ORDER BY $ARTIST_KEY IS NULL, $SORT_ARTIST, $SORT_ALBUM"""
    )
    fun albumsByArtist(minMs: Long): Flow<List<AlbumRow>>

    /** Newest year first; albums without a year last; then title. */
    @Query(
        """SELECT albumKey, album, COALESCE(albumArtist, artist) AS artist, folder, MIN(path) AS samplePath,
           MAX(year) AS year, COUNT(*) AS trackCount, SUM(durationMs) AS durationMs
           FROM tracks WHERE durationMs >= :minMs GROUP BY albumKey
           ORDER BY MAX(year) IS NULL, MAX(year) DESC, $SORT_ALBUM"""
    )
    fun albumsByYear(minMs: Long): Flow<List<AlbumRow>>

    /** The album whose newest track was added most recently first (Recently added's rule), then title. */
    @Query(
        """SELECT albumKey, album, COALESCE(albumArtist, artist) AS artist, folder, MIN(path) AS samplePath,
           MAX(year) AS year, COUNT(*) AS trackCount, SUM(durationMs) AS durationMs
           FROM tracks WHERE durationMs >= :minMs GROUP BY albumKey
           ORDER BY MAX(dateAdded) DESC, $SORT_ALBUM"""
    )
    fun albumsByAdded(minMs: Long): Flow<List<AlbumRow>>

    /** The Albums grid in the order its chip shows. */
    fun albums(minMs: Long, sort: AlbumSort): Flow<List<AlbumRow>> = when (sort) {
        AlbumSort.TITLE -> albums(minMs)
        AlbumSort.ARTIST -> albumsByArtist(minMs)
        AlbumSort.YEAR -> albumsByYear(minMs)
        AlbumSort.RECENT -> albumsByAdded(minMs)
    }

    @Query(
        """SELECT albumKey, album, COALESCE(albumArtist, artist) AS artist, folder, MIN(path) AS samplePath,
           MAX(year) AS year, COUNT(*) AS trackCount, SUM(durationMs) AS durationMs
           FROM tracks WHERE albumKey = :albumKey GROUP BY albumKey"""
    )
    fun album(albumKey: String): Flow<AlbumRow?>

    @Query("SELECT * FROM tracks WHERE albumKey = :albumKey AND durationMs >= :minMs ORDER BY $TRACK_ORDER")
    fun albumTracks(albumKey: String, minMs: Long): Flow<List<TrackEntity>>

    @Query(
        """SELECT $ARTIST_KEY AS name, COUNT(*) AS trackCount, COUNT(DISTINCT albumKey) AS albumCount FROM tracks
           WHERE durationMs >= :minMs GROUP BY $ARTIST_KEY COLLATE NOCASE ORDER BY $SORT_ARTIST"""
    )
    fun artists(minMs: Long): Flow<List<NameCount>>

    @Query(
        """SELECT albumKey, album, COALESCE(albumArtist, artist) AS artist, folder, MIN(path) AS samplePath,
           MAX(year) AS year, COUNT(*) AS trackCount, SUM(durationMs) AS durationMs
           FROM tracks WHERE durationMs >= :minMs AND
           ((:artist IS NULL AND artist IS NULL AND albumArtist IS NULL) OR artist = :artist COLLATE NOCASE OR albumArtist = :artist COLLATE NOCASE)
           GROUP BY albumKey ORDER BY year, $SORT_ALBUM"""
    )
    fun artistAlbums(artist: String?, minMs: Long): Flow<List<AlbumRow>>

    @Query(
        """SELECT * FROM tracks WHERE durationMs >= :minMs AND
           ((:artist IS NULL AND artist IS NULL AND albumArtist IS NULL) OR artist = :artist COLLATE NOCASE OR albumArtist = :artist COLLATE NOCASE)
           ORDER BY albumKey, $TRACK_ORDER"""
    )
    fun artistTracks(artist: String?, minMs: Long): Flow<List<TrackEntity>>

    @Query(
        """SELECT genre AS name, COUNT(*) AS trackCount, COUNT(DISTINCT albumKey) AS albumCount FROM tracks
           WHERE durationMs >= :minMs GROUP BY genre COLLATE NOCASE ORDER BY genre COLLATE NOCASE"""
    )
    fun genres(minMs: Long): Flow<List<NameCount>>

    @Query(
        """SELECT * FROM tracks WHERE durationMs >= :minMs AND
           ((:genre IS NULL AND genre IS NULL) OR genre = :genre COLLATE NOCASE)
           ORDER BY $SORT_ARTIST, albumKey, $TRACK_ORDER"""
    )
    fun genreTracks(genre: String?, minMs: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE durationMs >= :minMs ORDER BY dateAdded DESC, path LIMIT :limit")
    fun recentlyAdded(minMs: Long, limit: Int): Flow<List<TrackEntity>>

    @Query(
        """SELECT * FROM tracks WHERE durationMs >= :minMs AND
           (title LIKE :pattern ESCAPE '\' OR artist LIKE :pattern ESCAPE '\' OR album LIKE :pattern ESCAPE '\')
           ORDER BY $SORT_TITLE LIMIT 200"""
    )
    fun search(pattern: String, minMs: Long): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(DISTINCT folder) FROM tracks")
    fun folderCount(): Flow<Int>

    /** The largest row id: rows a scan inserts get larger ones (rows it updates keep theirs), so new files are told apart. */
    @Query("SELECT COALESCE(MAX(id), 0) FROM tracks")
    suspend fun maxId(): Long

    // A restored screen is checked against the index (NavRestore): is the album, artist or genre still there?
    @Query("SELECT NOT EXISTS(SELECT 1 FROM tracks)")
    suspend fun isEmpty(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE albumKey = :albumKey)")
    suspend fun hasAlbum(albumKey: String): Boolean

    @Query(
        """SELECT EXISTS(SELECT 1 FROM tracks WHERE
           (:artist IS NULL AND artist IS NULL AND albumArtist IS NULL) OR artist = :artist COLLATE NOCASE OR albumArtist = :artist COLLATE NOCASE)"""
    )
    suspend fun hasArtist(artist: String?): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE (:genre IS NULL AND genre IS NULL) OR genre = :genre COLLATE NOCASE)")
    suspend fun hasGenre(genre: String?): Boolean

    /** Rows the online identification may look up: never looked up, file-name title or no artist. */
    @Query("SELECT * FROM tracks WHERE lookupState = 0 AND (titleSource = 1 OR artist IS NULL) ORDER BY dateAdded, id LIMIT :limit")
    suspend fun pendingLookups(limit: Int): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE lookupState = 0 AND (titleSource = 1 OR artist IS NULL)")
    suspend fun pendingLookupCount(): Int

    /** Tracks looked up without a match (the "Identify untagged tracks again" subtitle). */
    @Query("SELECT COUNT(*) FROM tracks WHERE lookupState = 2 AND lookupSource IS NOT NULL")
    fun notIdentifiedCount(): Flow<Int>

    /**
     * "Find missing covers again": tracks without embedded art that have names to search with (album and an artist),
     * grouped by the caller; folder images and earlier online hits are checked there.
     */
    @Query(
        """SELECT * FROM tracks WHERE hasEmbeddedArt = 0 AND album IS NOT NULL AND COALESCE(albumArtist, artist) IS NOT NULL
           ORDER BY folder, path"""
    )
    suspend fun coverCandidates(): List<TrackEntity>

    /** "Identify untagged tracks again": only NOT_FOUND rows go back to pending; FOUND rows are never re-queried. */
    @Query("UPDATE tracks SET lookupState = 0 WHERE lookupState = 2 AND lookupSource IS NOT NULL")
    suspend fun resetNotFound(): Int
}

@Database(entities = [TrackEntity::class], version = 3, exportSchema = true)
abstract class SeionDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao

    companion object {
        /**
         * Schema 1 could hold M4A years read as the MP4 epoch (1904) or 0, and the tag rule only fixes files the
         * incremental scanner re-reads. Null them in place: a migration keeps the library instant after the update,
         * where a destructive bump would rescan everything.
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("UPDATE tracks SET year = NULL WHERE year IN (0, 1904)")
                }
            }

        /**
         * Where each title/artist/album came from and the online-identification state. Every row
         * gets lastModified = 0, so the next incremental scan re-reads the tags once and computes the title source
         * (the file-name fallback is only known in Kotlin); artist and album sources are right before that pass.
         */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    listOf("titleSource", "artistSource", "albumSource", "lookupState").forEach {
                        db.execSQL("ALTER TABLE tracks ADD COLUMN `$it` INTEGER NOT NULL DEFAULT 0")
                    }
                    db.execSQL("ALTER TABLE tracks ADD COLUMN `lookupAt` INTEGER")
                    db.execSQL("ALTER TABLE tracks ADD COLUMN `lookupSource` TEXT")
                    db.execSQL("ALTER TABLE tracks ADD COLUMN `lookupRef` TEXT")
                    db.execSQL(
                        "UPDATE tracks SET lastModified = 0, " +
                            "artistSource = CASE WHEN artist IS NULL THEN 1 ELSE 0 END, " +
                            "albumSource = CASE WHEN album IS NULL THEN 1 ELSE 0 END"
                    )
                }
            }

        fun create(context: Context): SeionDatabase = Room
            .databaseBuilder(context.applicationContext, SeionDatabase::class.java, "seion.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            // The DB is a cache of the file system: any other schema path simply rescans.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}

/** LIKE pattern for a user query, with the wildcard characters escaped. */
fun likePattern(query: String): String = "%" +
    query
        .trim()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") + "%"
