package org.seion

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.seion.art.AlbumArtResolver
import org.seion.art.ArtPolicy
import org.seion.art.ArtRepository
import org.seion.art.ChainCoverSource
import org.seion.art.CoverArtSize
import org.seion.art.CoverOverrides
import org.seion.art.MissingCovers
import org.seion.art.MissingCoversWorker
import org.seion.art.MusicBrainzCoverSource
import org.seion.art.NamedSource
import org.seion.art.OnlineCoverCache
import org.seion.art.OnlineDecision
import org.seion.art.OnlineGate
import org.seion.art.OnlineHttp
import org.seion.art.RateLimiter
import org.seion.art.SystemNetworkStateProvider
import org.seion.art.openings
import org.seion.library.LibraryScanner
import org.seion.library.RetrieverTagReader
import org.seion.library.ScanScheduler
import org.seion.library.ScanStamp
import org.seion.library.SeionDatabase
import org.seion.library.StorageRoots
import org.seion.library.TrackEntity
import org.seion.meta.IdentifyWorker
import org.seion.meta.MusicBrainzRecordingSource
import org.seion.meta.TrackIdentifier
import org.seion.playback.PlayerConnection
import org.seion.playback.QueueStore
import org.seion.playlists.PlaylistDatabase
import org.seion.playlists.PlaylistRepository
import org.seion.settings.LocaleContext
import org.seion.settings.SeionSettings
import org.seion.settings.SettingsRepository
import org.seion.ui.NavRestoreEnv
import org.seion.ui.NavStore
import org.seion.ui.ambient.AmbientRepository

class SeionApp : Application() {
    lateinit var container: AppContainer
        private set

    // Seion's own language rule (Follow system: pt-BR -> Português, everything else -> English).
    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleContext.wrap(base))

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
        // A memory card mounted while Seion runs: incremental rescan (also on every app start).
        val mounts = IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") }
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (Environment.isExternalStorageManager()) container.scans.scan(force = true)
                }
            },
            mounts,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Memory is short with Seion in the background: drop the decoded covers and ambients (rebuilt on return). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (MemoryTrim.clearsCaches(level)) container.trimMemory()
    }
}

/** When `onTrimMemory` empties the in-memory caches (pure, unit-tested). */
object MemoryTrim {
    /**
     * At TRIM_MEMORY_BACKGROUND or stronger: the process is in the background list and may be killed next (Android 14+
     * delivers only UI_HIDDEN and BACKGROUND). UI_HIDDEN alone keeps them: Seion may come back at once, and the covers
     * on screen would flash while they decode again.
     */
    fun clearsCaches(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
}

/** Manual dependency graph: one instance of each service per process. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app)
    val settingsState: StateFlow<SeionSettings> = settings.settings.stateIn(appScope, SharingStarted.Eagerly, SeionSettings())
    val db: SeionDatabase by lazy { SeionDatabase.create(app) }

    /** Playlists: user data in their own database, never dropped with the library index. */
    val playlistDb: PlaylistDatabase by lazy { PlaylistDatabase.create(app) }
    val playlists by lazy {
        PlaylistRepository(playlistDb.playlists(), db.tracks(), volumeRoots = { storageRoots.volumes().map { it.dir.absolutePath } })
    }
    val storageRoots = StorageRoots(app)
    val scanner by lazy { LibraryScanner(db.tracks(), RetrieverTagReader(), storageRoots::isVolumeRoot) }
    val scans = ScanScheduler(app)

    /** When the last scan finished (Settings › Rescan now). */
    val scanStamp = ScanStamp(app)
    val queueStore = QueueStore(File(app.filesDir, "queue.json"))

    /** The browse screens on disk: a real close comes back to the same place (NavStore, NavRestore). */
    val navStore = NavStore(File(app.filesDir, "nav.json"), appScope)

    /** What a restored screen is checked against at a cold start. */
    val navEnv: NavRestoreEnv =
        object : NavRestoreEnv {
            override fun hasAccess() = Environment.isExternalStorageManager()

            override suspend fun musicFolders() = storageRoots.effectiveFolders(settings.current().musicFolders)

            override fun volumes() = storageRoots.volumes().map { it.dir.absolutePath }

            override fun isDirectory(path: String) = File(path).isDirectory

            override suspend fun indexEmpty() = db.tracks().isEmpty()

            override suspend fun hasAlbum(key: String) = db.tracks().hasAlbum(key)

            override suspend fun hasArtist(name: String?) = db.tracks().hasArtist(name)

            override suspend fun hasGenre(name: String?) = db.tracks().hasGenre(name)

            override suspend fun hasPlaylist(id: Long) = playlistDb.playlists().exists(id)

            override suspend fun awaitScanDone() {
                scans.isScanning.dropWhile { !it }.first { !it }
            }
        }
    val onlineCache = OnlineCoverCache(File(app.cacheDir, "online-covers"))

    /** Covers the user chose (album ⋮ › Change cover): Seion's own files, never the music folders. */
    val coverOverrides = CoverOverrides(File(app.filesDir, "covers"))
    val network = SystemNetworkStateProvider(app)

    /**
     * Every online request checks the switch right before it is sent (a lookup waiting in a rate limiter
     * when the switch goes off sends nothing more). The in-memory value only errs towards "on" before the settings have
     * loaded, and every lookup starts behind the persisted gate anyway.
     */
    private val httpClient = OnlineHttp.defaultClient(allowed = { settingsState.value.onlineCovers })

    /** One request per second to musicbrainz.org in total: covers and track identification share it. */
    val musicBrainzLimiter = RateLimiter(1_000)

    val resolver =
        AlbumArtResolver(
            embedded = ArtRepository::embeddedPicture,
            listNames = { folder -> File(folder).list()?.toList().orEmpty() },
            // Always the persisted values (never the in-memory default), so "online off" holds from the first call.
            policy = {
                val s = settings.current()
                ArtPolicy(s.useFolderImages, s.onlineCovers, s.onlineWifiOnly)
            },
            network = network,
            onlineSource = coverChain(),
            onlineCache = onlineCache,
            onOnlineLookup = ::onOnlineLookup,
            chosen = coverOverrides::file
        )
    val art = ArtRepository(resolver)

    /** The cover chain: MusicBrainz + Cover Art Archive. */
    private fun coverChain() = ChainCoverSource(
        listOf(
            NamedSource(
                "musicbrainz",
                // 1200 px from the cover archive on screens where Now playing's cover is wider than 500 px.
                MusicBrainzCoverSource(httpClient, musicBrainzLimiter, coverSize = {
                    CoverArtSize.forDensity(app.resources.displayMetrics.density)
                })
            )
        )
    )

    /** Whether a lookup may go online now (the switch, "Wi-Fi only" and the network). */
    suspend fun onlineAllowed(): Boolean {
        val s = settings.current()
        return OnlineGate.decide(s.onlineCovers, s.onlineWifiOnly, network.current()) == OnlineDecision.ALLOWED
    }

    /** Online identification of untagged tracks: MusicBrainz. */
    val identifier by lazy {
        TrackIdentifier(
            db.tracks(),
            MusicBrainzRecordingSource(httpClient, musicBrainzLimiter),
            allowed = ::onlineAllowed,
            onRequest = ::onOnlineLookup,
            onIdentified = { t ->
                _identified.tryEmit(t)
                missingCovers.afterIdentify(t)
            }
        )
    }

    private val _identified = MutableSharedFlow<TrackEntity>(extraBufferCapacity = 64)

    /** Rows just identified online (the playback service rebuilds their queue items: title, artist, cover names). */
    val identified: SharedFlow<TrackEntity> = _identified.asSharedFlow()

    /** Covers for tracks without one: after an identification, and "Find missing covers again". */
    val missingCovers by lazy { MissingCovers(db.tracks(), art, onlineCache, onlineOn = { settings.current().onlineCovers }) }

    /** "Find missing covers again": one background run at a time (repeated taps don't stack). */
    suspend fun findCoversAgain() {
        MissingCoversWorker.enqueue(app, settings.current().onlineWifiOnly)
    }

    /** "Identify untagged tracks again": NOT_FOUND rows go back to pending and the lookup is queued. */
    suspend fun identifyAgain() {
        db.tracks().resetNotFound()
        IdentifyWorker.enqueue(app, settings.current().onlineWifiOnly)
    }
    val ambient = AmbientRepository(art)
    val player = PlayerConnection(app)

    /** The DSP hub (stats, status; later the control snapshot) shared by the playback service and its audio sink. */
    val dsp = org.seion.playback.dsp.DspHub(android.os.Debug::threadCpuTimeNanos).apply { status.tapEnabled = BuildConfig.DEBUG }

    fun start() {
        // Art settings changed: drop decoded covers so every screen reloads with the new lookup order.
        appScope.launch {
            settings.settings
                .map { Triple(it.useFolderImages, it.onlineCovers, it.onlineWifiOnly) }
                .distinctUntilChanged()
                .drop(1)
                .collect { art.invalidate() }
        }
        // The online switch turned on: identify the untagged tracks left pending.
        appScope.launch {
            settings.settings
                .map { it.onlineCovers to it.onlineWifiOnly }
                .distinctUntilChanged()
                .drop(1)
                .collect { (on, wifiOnly) -> if (on) IdentifyWorker.enqueue(app, wifiOnly) }
        }
        // The online switch turned off: end the lookups in flight and close the idle connections (no traffic after it).
        // From settingsState, so the gate on every request already reads "off" when the calls in flight are cancelled.
        appScope.launch {
            settingsState
                .map { it.onlineCovers }
                .distinctUntilChanged()
                .drop(1)
                .collect { on -> if (!on) OnlineHttp.stopAll(httpClient) }
        }
        // The network allows online lookups again (back online; on Wi-Fi with "Wi-Fi only"): covers that were skipped or
        // failed while it didn't are asked again for the screens and the media panel showing them.
        watchNetwork()
        appScope.launch { networkChanges.map { onlineAllowed() }.openings().collect { art.retryUnanswered() } }
        // The folder list changed: rescan.
        appScope.launch {
            settings.settings
                .map { it.musicFolders }
                .distinctUntilChanged()
                .drop(1)
                .collect { scans.scan(force = true) }
        }
    }

    /** Counts changes of the default network: each one re-reads the online gate ([onlineAllowed]). */
    private val networkChanges = MutableStateFlow(0)

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val recheck = { networkChanges.update { it + 1 } }
        runCatching {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        recheck()
                    }

                    override fun onLost(network: Network) {
                        recheck()
                    }
                }
            )
        }
    }

    private val _onlineNotices = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once, the first time a cover is looked up online: the UI says what was sent, since online lookups are on by default. */
    val onlineNotices: SharedFlow<Unit> = _onlineNotices.asSharedFlow()

    private fun onOnlineLookup() {
        appScope.launch {
            if (!settings.current().onlineNoticeShown) {
                settings.markOnlineNoticeShown()
                _onlineNotices.emit(Unit)
            }
        }
    }

    /** The art LRU (up to maxMemory / 8) and the ambient LRU (8 rendered backgrounds); everything else stays. */
    fun trimMemory() {
        art.trimMemory()
        ambient.trimMemory()
        android.util.Log.i("SeionApp", "memory trimmed: decoded covers and ambients dropped")
    }

    fun clearOnlineCovers(): Int = onlineCache.clear().also { art.invalidate() }

    /** Album ⋮ › Change cover: [jpeg] becomes the cover of [album] in each of [folders]; every screen reloads. */
    fun setChosenCover(folders: Collection<String>, album: String?, jpeg: ByteArray) {
        val keys = coverOverrides.set(folders, album, jpeg)
        art.invalidate()
        _chosenCovers.tryEmit(keys)
    }

    /** "Use original cover": the chosen cover goes; the album's own art (file, folder, online) shows again. */
    fun removeChosenCover(folders: Collection<String>, album: String?) {
        val keys = coverOverrides.remove(folders, album)
        art.invalidate()
        _chosenCovers.tryEmit(keys)
    }

    private val _chosenCovers = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)

    /**
     * The [CoverOverrides.key]s whose chosen cover was just set or removed, after the decoded covers were dropped: the
     * playback service reloads those queue items' artwork (media panel, lock screen, Bluetooth).
     */
    val chosenCovers: SharedFlow<Set<String>> = _chosenCovers.asSharedFlow()
}
