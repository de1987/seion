package org.seion.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.seion.library.AlbumSort
import org.seion.playback.dsp.ReplayGain
import org.seion.playback.dsp.ReplayGainMode
import org.seion.playlists.PlaylistSort

/**
 * Now-playing background, in the picker's order: the blurred, edge-clamped cover ("Seion Infinite"), the single
 * palette gradient ("Classic"), or the app's plain black ("Onyx"). The stored value is the constant's name.
 */
enum class BackgroundStyle { AMBIENT, CLASSIC, ONYX }

/** Repeat modes in the order the transport button cycles through them (values match Media3's Player.REPEAT_MODE_*). */
enum class RepeatSetting(val media3: Int) {
    OFF(0),
    ALL(2),
    ONE(1)
    ;

    fun next(): RepeatSetting = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    companion object {
        fun fromMedia3(mode: Int): RepeatSetting = entries.firstOrNull { it.media3 == mode } ?: OFF
    }
}

/**
 * Every user setting with its default. [musicFolders] is null until the user edits the
 * list: the defaults (internal Music/ plus every removable volume) are computed at runtime then, so a card
 * inserted later is picked up.
 */
data class SeionSettings(
    val musicFolders: List<String>? = null,
    val ignoreShortTracks: Boolean = true,
    val backgroundStyle: BackgroundStyle = BackgroundStyle.AMBIENT,
    val ambientDrift: Boolean = true,
    /** Now playing: the spectrum band under the cover, off by default. */
    val spectrum: Boolean = false,
    val useFolderImages: Boolean = true,
    val onlineCovers: Boolean = true,
    val onlineWifiOnly: Boolean = true,
    val gapless: Boolean = true,
    /** Sound: CrystalX+, off by default so the output is unprocessed until the user turns it on. */
    val crystalX: Boolean = false,
    /** Sound: RenovaPure, off by default. */
    val renovaPure: Boolean = false,
    /** Sound: ReplayGain, off by default; Album (falls back to the track gain) unless Track is picked. */
    val replayGain: Boolean = false,
    val replayGainAlbum: Boolean = true,
    /** The pre-amp for tagged files, whole dB in [ReplayGain.PREAMP_MIN_DB]..[ReplayGain.PREAMP_MAX_DB]. */
    val replayGainPreampDb: Int = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatSetting = RepeatSetting.OFF,
    /** The one-time "a cover was looked up online" notice has been shown (not a user setting). */
    val onlineNoticeShown: Boolean = false,
    /** The notification permission was asked once already (never re-prompt on every launch). */
    val notificationsAsked: Boolean = false,
    /** The Playlists screen's order: by name unless the user picked "Recently changed". */
    val playlistSort: PlaylistSort = PlaylistSort.NAME,
    /** The Albums grid's order (the "Title ▾" chip): by title unless the user picked another. */
    val albumSort: AlbumSort = AlbumSort.TITLE
) {
    /** What the DSP engine applies: OFF while the switch is off. */
    val replayGainMode: ReplayGainMode get() = when {
        !replayGain -> ReplayGainMode.OFF
        replayGainAlbum -> ReplayGainMode.ALBUM
        else -> ReplayGainMode.TRACK
    }

    /** Tracks shorter than this are hidden from the library lists (the folder browser still shows every file). */
    val minDurationMs: Long get() = if (ignoreShortTracks) SHORT_TRACK_MS else 0L

    companion object {
        const val SHORT_TRACK_MS = 30_000L

        fun from(prefs: Preferences): SeionSettings {
            val d = SeionSettings()
            return SeionSettings(
                musicFolders = prefs[Keys.MUSIC_FOLDERS]?.sorted(),
                ignoreShortTracks = prefs[Keys.IGNORE_SHORT] ?: d.ignoreShortTracks,
                backgroundStyle =
                prefs[Keys.BACKGROUND]?.let { name -> BackgroundStyle.entries.firstOrNull { it.name == name } }
                    ?: d.backgroundStyle,
                ambientDrift = prefs[Keys.DRIFT] ?: d.ambientDrift,
                spectrum = prefs[Keys.SPECTRUM] ?: d.spectrum,
                useFolderImages = prefs[Keys.FOLDER_IMAGES] ?: d.useFolderImages,
                onlineCovers = prefs[Keys.ONLINE] ?: d.onlineCovers,
                onlineWifiOnly = prefs[Keys.WIFI_ONLY] ?: d.onlineWifiOnly,
                gapless = prefs[Keys.GAPLESS] ?: d.gapless,
                crystalX = prefs[Keys.CRYSTALX] ?: d.crystalX,
                renovaPure = prefs[Keys.RENOVA_PURE] ?: d.renovaPure,
                replayGain = prefs[Keys.REPLAY_GAIN] ?: d.replayGain,
                replayGainAlbum = prefs[Keys.REPLAY_GAIN_MODE]?.let { it != ReplayGainMode.TRACK.name } ?: d.replayGainAlbum,
                replayGainPreampDb =
                (prefs[Keys.REPLAY_GAIN_PREAMP] ?: d.replayGainPreampDb).coerceIn(ReplayGain.PREAMP_MIN_DB, ReplayGain.PREAMP_MAX_DB),
                shuffle = prefs[Keys.SHUFFLE] ?: d.shuffle,
                repeat = prefs[Keys.REPEAT]?.let(RepeatSetting::fromMedia3) ?: d.repeat,
                onlineNoticeShown = prefs[Keys.ONLINE_NOTICE] ?: d.onlineNoticeShown,
                notificationsAsked = prefs[Keys.NOTIFICATIONS_ASKED] ?: d.notificationsAsked,
                playlistSort =
                prefs[Keys.PLAYLIST_SORT]?.let { name -> PlaylistSort.entries.firstOrNull { it.name == name } } ?: d.playlistSort,
                albumSort = prefs[Keys.ALBUM_SORT]?.let { name -> AlbumSort.entries.firstOrNull { it.name == name } } ?: d.albumSort
            )
        }
    }

    object Keys {
        val MUSIC_FOLDERS = stringSetPreferencesKey("music_folders")
        val IGNORE_SHORT = booleanPreferencesKey("ignore_short_tracks")
        val BACKGROUND = stringPreferencesKey("background_style")
        val DRIFT = booleanPreferencesKey("ambient_drift")
        val SPECTRUM = booleanPreferencesKey("now_playing_spectrum")
        val FOLDER_IMAGES = booleanPreferencesKey("use_folder_images")
        val ONLINE = booleanPreferencesKey("online_covers")
        val WIFI_ONLY = booleanPreferencesKey("online_wifi_only")
        val GAPLESS = booleanPreferencesKey("gapless")
        val CRYSTALX = booleanPreferencesKey("sound_crystalx")
        val RENOVA_PURE = booleanPreferencesKey("sound_renovapure")
        val REPLAY_GAIN = booleanPreferencesKey("sound_replaygain")

        /** "ALBUM" or "TRACK". */
        val REPLAY_GAIN_MODE = stringPreferencesKey("sound_replaygain_mode")
        val REPLAY_GAIN_PREAMP = intPreferencesKey("sound_replaygain_preamp_db")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT = intPreferencesKey("repeat_mode")
        val ONLINE_NOTICE = booleanPreferencesKey("online_notice_shown")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications_asked")
        val PLAYLIST_SORT = stringPreferencesKey("playlist_sort")
        val ALBUM_SORT = stringPreferencesKey("album_sort")
    }
}
