package org.seion.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An index written by schema version 1 keeps the bad M4A years (1904, 0) for files the incremental scanner never
 * re-reads. The schema-2 migration nulls them in place: no destructive drop, so the library stays instant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LibraryMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var db: SeionDatabase? = null

    @After fun tearDown() {
        db?.close()
        context.deleteDatabase(DB_NAME)
    }

    /** A version-1 file DB exactly as 0.1.x wrote it (CREATE TABLE copied from schemas/1.json). */
    private fun writeVersion1(years: List<Int?>) {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            v1.execSQL(
                "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `path` TEXT NOT NULL, " +
                    "`folder` TEXT NOT NULL, `fileName` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT, `albumArtist` TEXT, " +
                    "`album` TEXT, `albumKey` TEXT NOT NULL, `genre` TEXT, `year` INTEGER, `trackNumber` INTEGER, " +
                    "`discNumber` INTEGER, `durationMs` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, " +
                    "`sizeBytes` INTEGER NOT NULL, `dateAdded` INTEGER NOT NULL, `hasEmbeddedArt` INTEGER NOT NULL)"
            )
            v1.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_path` ON `tracks` (`path`)")
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_folder` ON `tracks` (`folder`)")
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_albumKey` ON `tracks` (`albumKey`)")
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_artist` ON `tracks` (`artist`)")
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_genre` ON `tracks` (`genre`)")
            v1.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            v1.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$V1_IDENTITY')")
            years.forEachIndexed { i, year ->
                v1.execSQL(
                    "INSERT INTO tracks (path, folder, fileName, title, albumKey, year, durationMs, lastModified, sizeBytes, " +
                        "dateAdded, hasEmbeddedArt) VALUES (?, '/m', ?, ?, 'k', ?, 60000, 1, 1, 1, 0)",
                    arrayOf<Any?>("/m/$i.m4a", "$i.m4a", "t$i", year)
                )
            }
            v1.version = 1
        }
    }

    @Test fun `upgrading from schema 1 nulls 1904 and 0 years and keeps every row`() = runTest {
        writeVersion1(listOf(1904, 0, 1999, null))
        val opened = SeionDatabase.create(context).also { db = it }
        val rows = opened.tracks().byPaths((0..3).map { "/m/$it.m4a" }).associate { it.path to it.year }
        assertEquals(4, rows.size) // a migration, not a destructive rescan
        assertEquals(null, rows["/m/0.m4a"])
        assertEquals(null, rows["/m/1.m4a"])
        assertEquals(1999, rows["/m/2.m4a"])
        assertEquals(null, rows["/m/3.m4a"])
    }

    /** A version-2 DB as the app wrote it (schemas/2.json: same table as v1, same identity). */
    private fun writeVersion2(rows: List<Pair<String?, String?>>) {
        writeVersion1(emptyList())
        SQLiteDatabase.openDatabase(context.getDatabasePath(DB_NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use { v2 ->
            rows.forEachIndexed { i, (artist, album) ->
                v2.execSQL(
                    "INSERT INTO tracks (path, folder, fileName, title, artist, album, albumKey, durationMs, lastModified, sizeBytes, " +
                        "dateAdded, hasEmbeddedArt) VALUES (?, '/m', ?, ?, ?, ?, 'k', 60000, 1234, 1, 1, 0)",
                    arrayOf<Any?>("/m/$i.mp3", "$i.mp3", "t$i", artist, album)
                )
            }
            v2.version = 2
        }
    }

    @Test fun `upgrading from schema 2 adds the provenance columns and forces one tag pass`() = runTest {
        writeVersion2(listOf("A" to "B", null to "B", "A" to null, null to null))
        val opened = SeionDatabase.create(context).also { db = it }
        val rows = opened.tracks().byPaths((0..3).map { "/m/$it.mp3" }).associateBy { it.path }
        assertEquals(4, rows.size) // kept: no destructive drop
        rows.values.forEach {
            assertEquals(0L, it.lastModified) // the next incremental scan re-reads every file once
            assertEquals(TagSource.TAG, it.titleSource)
            assertEquals(LookupState.NONE, it.lookupState)
            assertEquals(null, it.lookupAt)
            assertEquals(null, it.lookupSource)
            assertEquals(null, it.lookupRef)
        }
        assertEquals(listOf(0, 1, 0, 1), (0..3).map { rows.getValue("/m/$it.mp3").artistSource })
        assertEquals(listOf(0, 0, 1, 1), (0..3).map { rows.getValue("/m/$it.mp3").albumSource })
    }

    private companion object {
        const val DB_NAME = "seion.db"
        const val V1_IDENTITY = "ebfdc51dc055ebf09967c198548d6e99"
    }
}
