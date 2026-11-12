package org.seion.library

import java.io.File

/** File-system rules shared by the scanner and the folder browser. Pure, unit-tested. */
object AudioFiles {
    /**
     * Extensions Seion indexes and plays: MP3, AAC/ALAC in MP4 (.m4a) or raw ADTS (.aac), FLAC, WAV (PCM),
     * Ogg Vorbis and Opus. DSD (.dsf/.dff), cue sheets and anything else are not supported.
     */
    val EXTENSIONS: Set<String> = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus")

    fun isAudio(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in EXTENSIONS
    }

    /** Hidden entries (dot-files) are never shown or scanned. */
    fun isHidden(name: String): Boolean = name.startsWith(".")

    /** Folders the scanner skips: hidden ones, Android/ at a volume root (app data), and folders with .nomedia. */
    fun shouldSkipDir(dir: File, isVolumeRoot: (File) -> Boolean): Boolean {
        if (isHidden(dir.name)) return true
        if (dir.name == "Android" && dir.parentFile?.let(isVolumeRoot) == true) return true
        return File(dir, ".nomedia").exists()
    }

    /** Folder browser order: case-insensitive natural-ish name order. */
    val NAME_ORDER: Comparator<String> = compareBy(String.CASE_INSENSITIVE_ORDER) { it }

    fun titleFromFileName(name: String): String = name.substringBeforeLast('.').ifBlank { name }
}

/** A folder listing: subfolders first, then audio files, both in name order. */
data class FolderListing(val folders: List<File>, val files: List<File>)

fun listFolder(dir: File): FolderListing {
    val entries = dir.listFiles().orEmpty().filterNot { AudioFiles.isHidden(it.name) }
    val folders = entries.filter { it.isDirectory }.sortedWith(compareBy(AudioFiles.NAME_ORDER) { it.name })
    val files =
        entries
            .filter { it.isFile && AudioFiles.isAudio(it.name) }
            .sortedWith(compareBy(AudioFiles.NAME_ORDER) { it.name })
    return FolderListing(folders, files)
}
