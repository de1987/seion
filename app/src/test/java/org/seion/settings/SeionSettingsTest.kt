package org.seion.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeionSettingsTest {
    @Test fun `defaults`() {
        val s = SeionSettings.from(emptyPreferences())
        assertNull(s.musicFolders) // computed: internal Music/ + removable volumes
        assertTrue(s.ignoreShortTracks)
        assertEquals(30_000L, s.minDurationMs)
        assertEquals(BackgroundStyle.AMBIENT, s.backgroundStyle)
        assertTrue(s.ambientDrift)
        assertTrue(s.useFolderImages)
        assertTrue(s.onlineCovers)
        assertTrue(s.onlineWifiOnly)
        assertTrue(s.gapless)
        assertFalse(s.shuffle)
        assertEquals(RepeatSetting.OFF, s.repeat)
        assertEquals(SeionSettings(), s)
    }

    @Test fun `stored values are read back`() {
        val p =
            mutablePreferencesOf(
                SeionSettings.Keys.MUSIC_FOLDERS to setOf("/b", "/a"),
                SeionSettings.Keys.IGNORE_SHORT to false,
                SeionSettings.Keys.BACKGROUND to "CLASSIC",
                SeionSettings.Keys.ONLINE to false,
                SeionSettings.Keys.SHUFFLE to true,
                SeionSettings.Keys.REPEAT to 1
            )
        val s = SeionSettings.from(p)
        assertEquals(listOf("/a", "/b"), s.musicFolders)
        assertEquals(0L, s.minDurationMs)
        assertEquals(BackgroundStyle.CLASSIC, s.backgroundStyle)
        assertFalse(s.onlineCovers)
        assertTrue(s.shuffle)
        assertEquals(RepeatSetting.ONE, s.repeat)
    }

    @Test fun `unknown stored values fall back to defaults`() {
        val s = SeionSettings.from(mutablePreferencesOf(SeionSettings.Keys.BACKGROUND to "NEON", SeionSettings.Keys.REPEAT to 42))
        assertEquals(BackgroundStyle.AMBIENT, s.backgroundStyle)
        assertEquals(RepeatSetting.OFF, s.repeat)
    }
}

/** The CrystalX+ switch is OFF by default and persists under "sound_crystalx". */
class SoundSettingsTest {
    @Test fun `CrystalX+ is off by default and read back`() {
        assertFalse(SeionSettings.from(emptyPreferences()).crystalX)
        assertTrue(SeionSettings.from(mutablePreferencesOf(SeionSettings.Keys.CRYSTALX to true)).crystalX)
        assertEquals("sound_crystalx", SeionSettings.Keys.CRYSTALX.name)
    }
}

/** The RenovaPure switch is OFF by default and persists under "sound_renovapure". */
class RenovaPureSettingsTest {
    @Test fun `Seion RenovaPure is off by default and read back`() {
        assertFalse(SeionSettings().renovaPure)
        assertFalse(SeionSettings.from(emptyPreferences()).renovaPure)
        assertTrue(SeionSettings.from(mutablePreferencesOf(SeionSettings.Keys.RENOVA_PURE to true)).renovaPure)
    }

    @Test fun `the RenovaPure switch persists under its own key`() {
        // DataStore keys compare by name: changing it again would silently reset every user's choice.
        val stored = booleanPreferencesKey("sound_renovapure")
        assertTrue(SeionSettings.from(mutablePreferencesOf(stored to true)).renovaPure)
        assertFalse(SeionSettings.from(mutablePreferencesOf(stored to false)).renovaPure)
        assertEquals("sound_renovapure", SeionSettings.Keys.RENOVA_PURE.name)
    }
}

/** The spectrum band is off by default and persists under "now_playing_spectrum". */
class SpectrumSettingsTest {
    @Test fun `spectrum is off by default and read back`() {
        assertFalse(SeionSettings().spectrum)
        assertFalse(SeionSettings.from(emptyPreferences()).spectrum)
        assertTrue(SeionSettings.from(mutablePreferencesOf(SeionSettings.Keys.SPECTRUM to true)).spectrum)
        assertEquals("now_playing_spectrum", SeionSettings.Keys.SPECTRUM.name)
    }
}

/** The Albums grid's order (its "Title ▾" chip) is by title until the user picks another, kept as `album_sort`. */
class AlbumSortSettingTest {
    @Test fun `albums sort by title by default, and the choice is read back`() {
        assertEquals(org.seion.library.AlbumSort.TITLE, SeionSettings.from(emptyPreferences()).albumSort)
        assertEquals(
            org.seion.library.AlbumSort.YEAR,
            SeionSettings.from(mutablePreferencesOf(SeionSettings.Keys.ALBUM_SORT to "YEAR")).albumSort
        )
        assertEquals(
            org.seion.library.AlbumSort.TITLE,
            SeionSettings.from(mutablePreferencesOf(SeionSettings.Keys.ALBUM_SORT to "SIZE")).albumSort
        )
        assertEquals("album_sort", SeionSettings.Keys.ALBUM_SORT.name)
    }

    @Test fun `ReplayGain is off by default, album mode, no pre-amp, and stored values are read and clamped`() {
        val d = SeionSettings.from(emptyPreferences())
        assertFalse(d.replayGain)
        assertTrue(d.replayGainAlbum)
        assertEquals(0, d.replayGainPreampDb)
        assertEquals(org.seion.playback.dsp.ReplayGainMode.OFF, d.replayGainMode)
        val p =
            mutablePreferencesOf(
                SeionSettings.Keys.REPLAY_GAIN to true,
                SeionSettings.Keys.REPLAY_GAIN_MODE to "TRACK",
                SeionSettings.Keys.REPLAY_GAIN_PREAMP to 40
            )
        val s = SeionSettings.from(p)
        assertEquals(org.seion.playback.dsp.ReplayGainMode.TRACK, s.replayGainMode)
        assertEquals(6, s.replayGainPreampDb)
        assertEquals(org.seion.playback.dsp.ReplayGainMode.ALBUM, s.copy(replayGainAlbum = true).replayGainMode)
    }
}
