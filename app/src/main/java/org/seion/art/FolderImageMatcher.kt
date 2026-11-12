package org.seion.art

/**
 * Picks a folder's cover image from its file names (pure, unit-tested). Case-insensitive, in this order
 * cover.*, folder.*, front.*, album.*, albumart*.* (the "Large" variant preferred),
 * `<folder name>`.*, then the only image in the folder, ignoring names that
 * look like other artwork (back, cd, disc, inlay, booklet). Image types: jpg, jpeg, png, webp.
 */
object FolderImageMatcher {
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val NAMED = listOf("cover", "folder", "front", "album")
    private val NOT_A_COVER = listOf("back", "cd", "disc", "inlay", "booklet")

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS && !name.startsWith(".")

    fun pick(fileNames: Collection<String>, folderName: String? = null): String? {
        val images = fileNames.filter(::isImage).sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (images.isEmpty()) return null
        fun base(n: String) = n.substringBeforeLast('.')
        for (named in NAMED) {
            images.firstOrNull { base(it).equals(named, ignoreCase = true) }?.let { return it }
        }
        val albumArt = images.filter { it.lowercase().startsWith("albumart") }
        if (albumArt.isNotEmpty()) return albumArt.firstOrNull { it.lowercase().contains("large") } ?: albumArt.first()
        if (!folderName.isNullOrBlank()) {
            images.firstOrNull { base(it).equals(folderName, ignoreCase = true) }?.let { return it }
        }
        return images.singleOrNull()?.takeUnless { n -> NOT_A_COVER.any { base(n).lowercase().contains(it) } }
    }
}
