package org.seion.library

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** A mounted storage volume (internal shared storage or a card) the folder picker can start from. */
data class StorageVolumeRoot(val dir: File, val label: String, val removable: Boolean)

/** Where music lives: the mounted volumes and the default scan roots. */
class StorageRoots(private val context: Context) {
    /** [labels] names the volumes (pass the UI's context, so the names follow a language change without a restart). */
    fun volumes(labels: Context = context): List<StorageVolumeRoot> {
        val sm = context.getSystemService(StorageManager::class.java)
        val fromSystem =
            sm?.storageVolumes.orEmpty().mapNotNull { v ->
                val dir = v.directory ?: return@mapNotNull null
                if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                    return@mapNotNull null
                }
                StorageVolumeRoot(dir, v.getDescription(labels), v.isRemovable)
            }
        if (fromSystem.isNotEmpty()) return fromSystem
        @Suppress("DEPRECATION")
        return listOf(StorageVolumeRoot(Environment.getExternalStorageDirectory(), "", false))
    }

    fun isVolumeRoot(dir: File): Boolean = volumes().any { it.dir.absolutePath == dir.absolutePath }

    /** Default: internal shared storage's Music/ plus the root of every mounted removable volume. */
    fun defaultFolders(): List<String> {
        val vols = volumes()
        val internal = vols.firstOrNull { !it.removable }?.dir ?: return emptyList()
        return listOf(File(internal, Environment.DIRECTORY_MUSIC).absolutePath) +
            vols.filter { it.removable }.map { it.dir.absolutePath }
    }

    /** The scan roots in display order: internal shared storage first, then cards, each alphabetically. */
    fun effectiveFolders(configured: List<String>?): List<String> {
        val folders = configured ?: return defaultFolders()
        val internal = volumes().firstOrNull { !it.removable }?.dir?.absolutePath
        return orderRoots(folders, internal)
    }

    /** A short label for a root path: the volume's name, plus the path inside it. */
    fun labelFor(path: String, labels: Context = context): String {
        val vol = volumes(labels).filter { path.startsWith(it.dir.absolutePath) }.maxByOrNull { it.dir.absolutePath.length }
        if (vol == null) return path
        val rest = path.removePrefix(vol.dir.absolutePath).trim('/')
        return if (rest.isEmpty()) vol.label else "${vol.label} › ${rest.replace("/", " › ")}"
    }
}

/** Internal-storage roots first, then the rest; alphabetical within each group (pure, unit-tested). */
fun orderRoots(folders: List<String>, internalRoot: String?): List<String> =
    folders.distinct().sortedWith(compareBy<String>({ internalRoot == null || !it.startsWith(internalRoot) }, { it.lowercase() }))
