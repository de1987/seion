package org.seion.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.seion.library.AlbumSort
import org.seion.playlists.PlaylistSort

private val Context.seionDataStore: DataStore<Preferences> by preferencesDataStore(name = "seion_settings")

/** DataStore-backed settings; one instance per process (SeionApp). */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.seionDataStore

    val settings: Flow<SeionSettings> = store.data.map(SeionSettings::from)

    suspend fun current(): SeionSettings = settings.first()

    suspend fun setMusicFolders(folders: List<String>) = store.edit { it[SeionSettings.Keys.MUSIC_FOLDERS] = folders.toSet() }

    suspend fun setIgnoreShortTracks(value: Boolean) = store.edit { it[SeionSettings.Keys.IGNORE_SHORT] = value }

    suspend fun setBackgroundStyle(value: BackgroundStyle) = store.edit { it[SeionSettings.Keys.BACKGROUND] = value.name }

    suspend fun setSpectrum(value: Boolean) = store.edit { it[SeionSettings.Keys.SPECTRUM] = value }

    suspend fun setAmbientDrift(value: Boolean) = store.edit { it[SeionSettings.Keys.DRIFT] = value }

    suspend fun setUseFolderImages(value: Boolean) = store.edit { it[SeionSettings.Keys.FOLDER_IMAGES] = value }

    suspend fun setOnlineCovers(value: Boolean) = store.edit { it[SeionSettings.Keys.ONLINE] = value }

    suspend fun setOnlineWifiOnly(value: Boolean) = store.edit { it[SeionSettings.Keys.WIFI_ONLY] = value }

    suspend fun setGapless(value: Boolean) = store.edit { it[SeionSettings.Keys.GAPLESS] = value }

    suspend fun setCrystalX(value: Boolean) = store.edit { it[SeionSettings.Keys.CRYSTALX] = value }

    suspend fun setRenovaPure(value: Boolean) = store.edit { it[SeionSettings.Keys.RENOVA_PURE] = value }

    suspend fun setReplayGain(value: Boolean) = store.edit { it[SeionSettings.Keys.REPLAY_GAIN] = value }

    suspend fun setReplayGainAlbum(album: Boolean) = store.edit {
        it[SeionSettings.Keys.REPLAY_GAIN_MODE] = if (album) "ALBUM" else "TRACK"
    }

    suspend fun setReplayGainPreamp(db: Int) = store.edit {
        it[SeionSettings.Keys.REPLAY_GAIN_PREAMP] = db.coerceIn(
            org.seion.playback.dsp.ReplayGain.PREAMP_MIN_DB,
            org.seion.playback.dsp.ReplayGain.PREAMP_MAX_DB
        )
    }

    suspend fun setShuffle(value: Boolean) = store.edit { it[SeionSettings.Keys.SHUFFLE] = value }

    suspend fun markNotificationsAsked() = store.edit { it[SeionSettings.Keys.NOTIFICATIONS_ASKED] = true }

    suspend fun markOnlineNoticeShown() = store.edit { it[SeionSettings.Keys.ONLINE_NOTICE] = true }

    suspend fun setPlaylistSort(value: PlaylistSort) = store.edit { it[SeionSettings.Keys.PLAYLIST_SORT] = value.name }

    suspend fun setAlbumSort(value: AlbumSort) = store.edit { it[SeionSettings.Keys.ALBUM_SORT] = value.name }

    suspend fun setRepeat(value: RepeatSetting) = store.edit { it[SeionSettings.Keys.REPEAT] = value.media3 }
}
