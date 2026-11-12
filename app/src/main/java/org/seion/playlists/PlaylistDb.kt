package org.seion.playlists

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A playlist. [updatedAt] changes with every edit (the "Recently changed" order). */
@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long, val updatedAt: Long)

/**
 * One song of a playlist, by its absolute [path] (the library's key and the queue's media id: it survives rescans).
 * [position] is dense (0..n-1) per playlist. The rest is a snapshot taken when the song was added (and refreshed from
 * the library while it is there), so a missing file still shows its name, and the lists show counts and lengths
 * without the library index.
 */
@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["playlistId", "position"]), Index("path")]
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val position: Int,
    val path: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0
)

/** A playlist with its song count and total length (from the snapshots). */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val trackCount: Int,
    val durationMs: Long
)

/** The first entries of every playlist (the mosaics of the Playlists screen and the picker). */
data class EntryHead(
    val playlistId: Long,
    val position: Int,
    val path: String,
    val album: String?,
    val albumArtist: String?,
    val artist: String?
)

/** A song to add: the path and the snapshot. */
data class NewEntry(
    val path: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long
)

/** What an add did: [added] new songs, [skipped] already there. */
data class AddResult(val added: Int, val skipped: Int)

private const val SUMMARY =
    """SELECT p.id, p.name, p.createdAt, p.updatedAt, COUNT(e.id) AS trackCount, COALESCE(SUM(e.durationMs), 0) AS durationMs
       FROM playlists p LEFT JOIN playlist_entries e ON e.playlistId = p.id"""

@Dao
interface PlaylistDao {
    @Query("$SUMMARY GROUP BY p.id")
    fun summaries(): Flow<List<PlaylistSummary>>

    @Query("$SUMMARY WHERE p.id = :id GROUP BY p.id")
    fun summary(id: Long): Flow<PlaylistSummary?>

    @Query("SELECT COUNT(*) FROM playlists")
    fun count(): Flow<Int>

    @Query("SELECT name FROM playlists")
    suspend fun names(): List<String>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlist(id: Long): PlaylistEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM playlists WHERE id = :id)")
    suspend fun exists(id: Long): Boolean

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    fun entries(id: Long): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    suspend fun entriesNow(id: Long): List<PlaylistEntryEntity>

    @Query(
        "SELECT playlistId, position, path, album, albumArtist, artist FROM playlist_entries WHERE position < :limit ORDER BY playlistId, position"
    )
    fun heads(limit: Int): Flow<List<EntryHead>>

    @Insert
    suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :id")
    suspend fun deleteEntriesOf(id: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query(
        """UPDATE playlist_entries SET title = :title, artist = :artist, album = :album, albumArtist = :albumArtist,
           durationMs = :durationMs WHERE id = :entryId"""
    )
    suspend fun updateSnapshot(entryId: Long, title: String, artist: String?, album: String?, albumArtist: String?, durationMs: Long)

    /** A new playlist (with its first songs, if any); returns its id. */
    @Transaction
    suspend fun create(name: String, songs: List<NewEntry>, now: Long): Long {
        val id = insertPlaylist(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
        append(id, songs, now)
        return id
    }

    /** Appends [songs] at the end, skipping those already in the playlist (PlaylistRules.planAdd). */
    @Transaction
    suspend fun append(id: Long, songs: List<NewEntry>, now: Long): AddResult {
        val existing = entriesNow(id)
        val plan = PlaylistRules.planAdd(existing.map { it.path }, songs) { it.path }
        if (plan.toAdd.isNotEmpty()) {
            insertEntries(
                plan.toAdd.mapIndexed { i, s ->
                    PlaylistEntryEntity(
                        playlistId = id,
                        position = existing.size + i,
                        path = s.path,
                        title = s.title,
                        artist = s.artist,
                        album = s.album,
                        albumArtist = s.albumArtist,
                        durationMs = s.durationMs
                    )
                }
            )
            touch(id, now)
        }
        return AddResult(plan.toAdd.size, plan.skipped)
    }

    /** Replaces the playlist's songs with [entries] in this order (positions renumbered; ids kept): reorder, remove, undo. */
    @Transaction
    suspend fun replaceEntries(id: Long, entries: List<PlaylistEntryEntity>, now: Long) {
        deleteEntriesOf(id)
        insertEntries(entries.mapIndexed { i, e -> e.copy(playlistId = id, position = i) })
        touch(id, now)
    }

    @Transaction
    suspend fun delete(id: Long) {
        deleteEntriesOf(id)
        deletePlaylist(id)
    }

    @Transaction
    suspend fun updateSnapshots(changes: List<PlaylistEntryEntity>) {
        changes.forEach { updateSnapshot(it.id, it.title, it.artist, it.album, it.albumArtist, it.durationMs) }
    }
}

/**
 * The playlists: user data, so a database of its own (`playlists.db`) next to the library index (`seion.db`). The index
 * is a cache that a schema change may drop and rebuild (`fallbackToDestructiveMigration`); this one never is: every
 * schema change gets a tested migration, and an unknown version fails loudly instead of deleting playlists.
 */
@Database(entities = [PlaylistEntity::class, PlaylistEntryEntity::class], version = 1, exportSchema = true)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao

    companion object {
        const val FILE = "playlists.db"

        fun create(context: Context): PlaylistDatabase = Room
            .databaseBuilder(context.applicationContext, PlaylistDatabase::class.java, FILE)
            .build()
    }
}
