package org.seion.regressions

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.MemoryTrim
import org.seion.art.ArtOrigin
import org.seion.art.ArtRef
import org.seion.art.ArtRepository
import org.seion.art.ResolvedArt
import org.seion.ui.ambient.Ambient
import org.seion.ui.ambient.AmbientParams
import org.seion.ui.ambient.AmbientRepository
import org.seion.ui.components.NoCoverColors

/** `onTrimMemory`: at TRIM_MEMORY_BACKGROUND or stronger the art and ambient LRUs are emptied. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("DEPRECATION") // the older, stronger levels are still delivered below Android 14
class MemoryTrimTest {
    @Test fun `background and stronger levels clear, a hidden UI or a running process does not`() {
        assertTrue(MemoryTrim.clearsCaches(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(MemoryTrim.clearsCaches(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertTrue(MemoryTrim.clearsCaches(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
        assertFalse(MemoryTrim.clearsCaches(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(MemoryTrim.clearsCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertFalse(MemoryTrim.clearsCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
    }

    private val png: ByteArray =
        ByteArrayOutputStream()
            .also { out -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out) }
            .toByteArray()

    @Test fun `the art cache empties and the cover decodes again without a new lookup`() = runTest {
        var local = 0
        val ref = ArtRef("/m/a/01.flac", "/m/a", "A", "B")
        val art = ArtRepository(resolveLocal = { local++.let { ResolvedArt.Bytes(png, ArtOrigin.EMBEDDED) } }, fileExists = { true })
        assertNotNull(art.load(ref, 64))
        assertNotNull(art.cached(ref, 64))
        art.trimMemory()
        assertNull(art.cached(ref, 64))
        assertNotNull(art.load(ref, 64))
        assertEquals(2, local) // read again from the file: nothing was remembered as missing
    }

    @Test fun `the ambient cache empties`() = runTest {
        val img = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
        var renders = 0
        val ambient =
            AmbientRepository(
                loadArt = { _, _ -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) },
                generation = { 0 },
                renderer = { key, _ ->
                    renders++
                    Ambient(key, img, img, img, AmbientParams.DEFAULT, NoCoverColors.Tile, NoCoverColors.Tile, NoCoverColors.Tile)
                }
            )
        val ref = ArtRef("/m/a/01.flac", "/m/a", "A", "B")
        assertNotNull(ambient.load(ref))
        assertNotNull(ambient.cached(ref))
        ambient.trimMemory()
        assertNull(ambient.cached(ref))
        assertNotNull(ambient.load(ref))
        assertEquals(2, renders)
    }
}
