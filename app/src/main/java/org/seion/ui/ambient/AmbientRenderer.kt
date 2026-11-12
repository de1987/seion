package org.seion.ui.ambient

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.seion.art.ArtRef
import org.seion.art.ArtRepository
import org.seion.ui.components.NoCoverColors

/**
 * Everything the now-playing background needs for one cover, computed once per track off the main thread:
 * the far layer (cover stretched and blurred), the near layer (edge-clamped, blurred, faded), the contrast
 * parameters and the palette colours. Drawing it is then only bitmap draws (and a transform for the drift).
 */
class Ambient(
    val key: String,
    val cover: ImageBitmap,
    val far: ImageBitmap,
    val near: ImageBitmap,
    val params: AmbientParams,
    /** Classic gradient's top colour. */
    val classicBase: Color,
    /** Accent for active states (shuffle/repeat on, the playing row), from the light-vibrant swatch. */
    val accent: Color,
    /** Faint tint for the top of browse screens. */
    val tint: Color,
    /** The mini-player's backdrop, drawn cropped to fill the bar: the far layer, except without a cover ([NoCoverGlow.mini]). */
    val mini: ImageBitmap = far
)

/** Geometry of the near layer relative to the cover: it spans the cover plus [MARGIN] × its size on each side. */
object NearLayer {
    const val MARGIN = 0.75f
    const val COVER_PX = 160
    val SIZE_PX = (COVER_PX * (1 + 2 * MARGIN)).toInt()
}

/** The far layer covers an area this much larger than the screen (580 × 980 dp around 360 × 640 dp). */
object FarLayer {
    const val OVERSIZE_X = 580f / 360f
    const val OVERSIZE_Y = 980f / 640f
    const val WIDTH_PX = 116
    const val HEIGHT_PX = 196
}

/**
 * Ambients per cover, built once and cached. A track without art gets the shared no-cover ambient: the ink field with a
 * soft top glow ([NoCoverGlow], [buildNoCover]) built once under [NO_COVER_KEY] with the no-cover palette.
 */
class AmbientRepository(
    private val loadArt: suspend (ArtRef, Int) -> Bitmap?,
    private val generation: () -> Int,
    /** The renderer (tests pass a fake); null = [build]. */
    private val renderer: ((String, Bitmap) -> Ambient?)? = null,
    /** The no-cover ambient's builder (tests pass a fake); null = [buildNoCover]. */
    private val noCoverBuilder: (() -> Ambient?)? = null
) {
    constructor(art: ArtRepository) : this({ ref, size -> art.load(ref, size) }, { art.generation })

    private val cache = LruCache<String, Ambient>(8)
    private val noCoverLock = Mutex()

    @Volatile private var noCover: Ambient? = null

    fun cached(ref: ArtRef): Ambient? = cache.get(ref.cacheKey + "#" + generation())

    /** Memory is short (`onTrimMemory`): the rendered backgrounds go (each is rebuilt from its cover when shown again). */
    fun trimMemory() = cache.evictAll()

    /** The cover's ambient, or the shared no-cover ambient when the track has no art. */
    suspend fun load(ref: ArtRef): Ambient? {
        val key = ref.cacheKey + "#" + generation()
        cache.get(key)?.let { return it }
        val bmp = loadArt(ref, COVER_SIZE) ?: return noCover()?.also { cache.put(key, it) }
        return withContext(Dispatchers.Default) { render(key, bmp) }?.also { cache.put(key, it) }
    }

    /** The ambient of a cover already decoded ([ArtState.Found]): no second lookup. */
    suspend fun forBitmap(ref: ArtRef, bmp: Bitmap): Ambient? {
        val key = ref.cacheKey + "#" + generation()
        cache.get(key)?.takeIf { it.key == key }?.let { return it }
        return withContext(Dispatchers.Default) { render(key, bmp) }?.also { cache.put(key, it) }
    }

    /** The no-cover ambient: computed once per process, shared by every track without art. */
    suspend fun noCover(): Ambient? {
        noCover?.let { return it }
        return noCoverLock.withLock {
            noCover ?: withContext(Dispatchers.Default) {
                (noCoverBuilder ?: ::buildNoCover)()?.let(::withNoCoverPalette)
            }?.also { noCover = it }
        }
    }

    private fun render(key: String, bmp: Bitmap): Ambient? = if (renderer != null) renderer(key, bmp) else build(key, bmp)

    private fun withNoCoverPalette(a: Ambient) = Ambient(
        key = a.key,
        cover = a.cover,
        far = a.far,
        near = a.near,
        params = a.params,
        classicBase = NoCoverColors.ClassicBase,
        accent = NoCoverColors.Accent,
        tint = NoCoverColors.ClassicBase,
        mini = a.mini
    )

    /**
     * The no-cover ambient: the far layer is the ink field with the soft top glow ([NoCoverGlow.far]), with no
     * near layer (the tile sits on the field; an edge-clamped halo would bring the glow down around it), and the mini-
     * player gets the same rule over its bar. The contrast rule solves on what is drawn, as for a cover, but with no
     * extra saturation and from full far brightness: the field is already dark, and the glow keeps its designed peak.
     */
    @androidx.annotation.VisibleForTesting
    internal fun buildNoCover(): Ambient? = runCatching {
        val far = NoCoverGlow.far()
        val near = createBitmap(1, 1) // transparent
        val farPx = IntArray(far.width * far.height).also { far.getPixels(it, 0, far.width, 0, 0, far.width, far.height) }
        val samples = TextSampler.samples(farPx, far.width, far.height, IntArray(1), 1, 1, 1.0, 1.0)
        val params = AmbientContrast.compute(ArtStats.of(farPx, far.width, far.height), samples, saturation = 1.0, farStart = 1.0)
        val farImage = far.asImageBitmap()
        Ambient(
            key = NO_COVER_KEY,
            cover = farImage, // never shown: the tile is drawn
            far = farImage,
            near = near.asImageBitmap(),
            params = params,
            classicBase = NoCoverColors.ClassicBase,
            accent = NoCoverColors.Accent,
            tint = NoCoverColors.ClassicBase,
            mini = NoCoverGlow.mini().asImageBitmap()
        )
    }.onFailure { Log.w(TAG, "no-cover ambient build failed", it) }.getOrNull()

    @androidx.annotation.VisibleForTesting
    internal fun build(key: String, cover: Bitmap): Ambient? = runCatching {
        val src = if (cover.config == Bitmap.Config.HARDWARE) cover.copy(Bitmap.Config.ARGB_8888, false) else cover
        val small = src.scale(STATS_PX, STATS_PX)
        val stats = statsOf(small)
        val far = renderFar(src)
        val near = renderNear(src)
        val params = AmbientContrast.compute(stats, textSamples(far, near, stats))
        val palette = Palette.from(small).maximumColorCount(16).clearFilters().generate()
        val swatch = (palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb ?: 0xFF404040.toInt()
        val base = ClassicGradient.baseColour(Rgb.of(swatch))
        val accentSrc = (palette.lightVibrantSwatch ?: palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb
        val accent = accentSrc?.let { accentFrom(Rgb.of(it)) } ?: DEFAULT_ACCENT
        val tintSrc = (palette.darkVibrantSwatch ?: palette.dominantSwatch)?.rgb ?: 0xFF202020.toInt()
        Ambient(
            key = key,
            cover = src.asImageBitmap(),
            far = far.asImageBitmap(),
            near = near.asImageBitmap(),
            params = params,
            classicBase = base.toColor(),
            accent = accent,
            tint = Rgb.of(tintSrc).toColor()
        )
    }.onFailure { Log.w(TAG, "ambient build failed", it) }.getOrNull()

    /** Far layer: the cover stretched over the oversized area, blurred (~58 dp at the 360 dp reference width). */
    private fun renderFar(cover: Bitmap): Bitmap {
        val w = FarLayer.WIDTH_PX
        val h = FarLayer.HEIGHT_PX
        val canvasBmp = createBitmap(w, h)
        // object-fit: cover — scale the square art to fill the tall canvas, centred.
        val s = maxOf(w, h).toFloat() / cover.width
        val dw = cover.width * s
        val left = (w - dw) / 2
        Canvas(canvasBmp).drawBitmap(cover, null, RectF(left, 0f, left + dw, h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        val radius = 58f / 580f * w
        return GpuBlur.blur(canvasBmp, radius) ?: SoftBlur.blur(canvasBmp)
    }

    /**
     * Near layer: every pixel outside the cover repeats the nearest edge pixel (BitmapShader with
     * Shader.TileMode.CLAMP), blurred ~18 dp, then faded out with distance by a blurred rounded-rect mask.
     */
    private fun renderNear(cover: Bitmap): Bitmap {
        val size = NearLayer.SIZE_PX
        val c = NearLayer.COVER_PX.toFloat()
        val inset = NearLayer.MARGIN * c
        val clamp = createBitmap(size, size)
        val shader =
            BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(
                    Matrix().apply {
                        setRectToRect(
                            RectF(0f, 0f, cover.width.toFloat(), cover.height.toFloat()),
                            RectF(
                                inset,
                                inset,
                                inset + c,
                                inset + c
                            ),
                            Matrix.ScaleToFit.FILL
                        )
                    }
                )
            }
        Canvas(clamp).drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        val blurred = GpuBlur.blur(clamp, 18f / 229.6f * c) ?: SoftBlur.blur(clamp)
        val out = blurred.copy(Bitmap.Config.ARGB_8888, true)
        val mask = createBitmap(size, size)
        val falloff = 60f / 229.6f * c
        val grow = 0.06f * c
        Canvas(mask).drawRoundRect(
            RectF(inset - grow, inset - grow, inset + c + grow, inset + c + grow),
            0.25f * c,
            0.25f * c,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(falloff, BlurMaskFilter.Blur.NORMAL) }
        )
        Canvas(out).drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        return out
    }

    companion object {
        private const val TAG = "SeionAmbient"
        const val COVER_SIZE = 720

        /** The shared ambient of every track without art (v3: the ink field with a soft top glow). */
        const val NO_COVER_KEY = "no-cover:v3"
        private const val STATS_PX = 64
        private const val SATURATION = 1.5

        /** What is drawn behind the secondary text: the rendered far and near layers, sampled. */
        private fun textSamples(far: Bitmap, near: Bitmap, stats: ArtStats): List<TextSample> {
            val farPx = IntArray(far.width * far.height).also { far.getPixels(it, 0, far.width, 0, 0, far.width, far.height) }
            val nearPx = IntArray(near.width * near.height).also { near.getPixels(it, 0, near.width, 0, 0, near.width, near.height) }
            val brightness = AmbientContrast.coverBrightness(stats.meanLuminance)
            return TextSampler.samples(farPx, far.width, far.height, nearPx, near.width, near.height, SATURATION, brightness)
        }

        private fun statsOf(small: Bitmap): ArtStats {
            val px = IntArray(STATS_PX * STATS_PX)
            small.getPixels(px, 0, STATS_PX, 0, 0, STATS_PX, STATS_PX)
            return ArtStats.of(px, STATS_PX, STATS_PX)
        }

        /**
         * The dimming a cover frame carries, from the already-decoded cover on the same downscale the ambient
         * uses, so the frame and its halo agree. Call off the main thread.
         */
        fun coverLook(cover: Bitmap): CoverLook = runCatching {
            val src = if (cover.config == Bitmap.Config.HARDWARE) cover.copy(Bitmap.Config.ARGB_8888, false) else cover
            AmbientContrast.coverLook(statsOf(src.scale(STATS_PX, STATS_PX)).meanLuminance)
        }.getOrElse { CoverLook(1f, feather = true, featherWidth = 0.09f) }
        val DEFAULT_ACCENT = Color(0xFFE6D6A6)

        private fun Rgb.toColor() = Color(r.toFloat() / 255f, g.toFloat() / 255f, b.toFloat() / 255f)

        /** The swatch's hue, lifted to a light, readable accent on dark backgrounds. */
        fun accentFrom(c: Rgb): Color {
            val (h, s, l) = ClassicGradient.toHsl(c)
            return ClassicGradient.fromHsl(h, s.coerceAtMost(0.75), l.coerceIn(0.68, 0.82)).toColor()
        }
    }
}

/** RenderEffect.createBlurEffect (CLAMP edges) rendered off-screen once into a bitmap (API 31+). */
private object GpuBlur {
    fun blur(src: Bitmap, radius: Float): Bitmap? {
        val w = src.width
        val h = src.height
        var reader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        val node = RenderNode("seion-blur")
        return try {
            reader =
                ImageReader.newInstance(
                    w,
                    h,
                    PixelFormat.RGBA_8888,
                    1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                )
            node.setPosition(0, 0, w, h)
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            val canvas = node.beginRecording()
            canvas.drawBitmap(src, 0f, 0f, null)
            node.endRecording()
            renderer =
                HardwareRenderer().apply {
                    setSurface(reader.surface)
                    setContentRoot(node)
                    isOpaque = false
                }
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            reader.acquireNextImage().use { image ->
                val hb = image.hardwareBuffer ?: return null
                hb.use {
                    Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB))?.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
        } catch (t: Throwable) {
            Log.w("SeionAmbient", "GPU blur unavailable, software fallback: ${t.javaClass.simpleName}")
            null
        } finally {
            renderer?.destroy()
            reader?.close()
            node.discardDisplayList()
        }
    }
}

/** Fallback: downscale and upscale with bilinear filtering (an upscaled tiny bitmap is itself a blur). */
private object SoftBlur {
    fun blur(src: Bitmap): Bitmap {
        val tiny = src.scale(maxOf(4, src.width / 8), maxOf(4, src.height / 8))
        val mid = tiny.scale(maxOf(8, src.width / 3), maxOf(8, src.height / 3))
        val out = createBitmap(src.width, src.height)
        Canvas(out).drawBitmap(mid, Rect(0, 0, mid.width, mid.height), Rect(0, 0, src.width, src.height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
