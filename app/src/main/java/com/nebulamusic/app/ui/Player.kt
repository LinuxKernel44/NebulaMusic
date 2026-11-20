@file:OptIn(ExperimentalFoundationApi::class)

package com.nebulamusic.app.ui

import android.graphics.drawable.BitmapDrawable
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import androidx.compose.foundation.text.BasicText
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import com.nebulamusic.app.glass.GlassDefaults
import com.nebulamusic.app.glass.GlassIconButton
import com.nebulamusic.app.glass.GlassTab
import com.nebulamusic.app.glass.GlassTabs
import com.nebulamusic.app.glass.glass
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import androidx.compose.animation.Crossfade
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.nebulamusic.app.R
import com.nebulamusic.app.data.Track
import com.nebulamusic.app.data.Repo
import com.nebulamusic.app.player.PlayMode
import com.nebulamusic.app.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

enum class Tab { HOME, SEARCH, LIBRARY }

private val PillBlack = Color.Black.copy(alpha = 0.86f)
private val PillGlass = Color.White.copy(alpha = 0.14f)

/** Dominant/vibrant colour of an artwork, animated, used to tint backgrounds. */
/**
 * Palette colour of an artwork plus a tiny pre-blurred copy of it (null if it can't be decoded). The blurred copy is
 * drawn as a plain upscaled image: far cheaper than a live full-screen RenderEffect blur re-run on every frame.
 */
suspend fun loadArtInfo(ctx: android.content.Context, url: String?): Pair<Color?, ImageBitmap?> {
    if (url == null) return null to null
    val req = ImageRequest.Builder(ctx).data(url).allowHardware(false).size(96).build()
    val res = ctx.imageLoader.execute(req)
    val bmp = ((res as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return null to null
    return withContext(Dispatchers.Default) {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val c = (p.vibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.let { Color(it.rgb) }
        // Much blurrier than before: shrink to a handful of pixels, then grow back smoothly in two steps.
        val ratio = bmp.height / bmp.width.toFloat()
        fun scaled(src: android.graphics.Bitmap, w: Int) =
            android.graphics.Bitmap.createScaledBitmap(src, w, (w * ratio).toInt().coerceAtLeast(1), true)
        val small = scaled(scaled(scaled(bmp, 7), 18), 40)
        c to small.asImageBitmap()
    }
}

/** What the player is currently showing: the beat plus its (already decoded) artwork tint. */
private data class ShownArt(val beat: Track, val tint: Color, val blur: ImageBitmap?)

@Composable
fun rememberArtColor(url: String?): Color {
    val ctx = LocalContext.current
    var target by remember { mutableStateOf(Color(0xFF26262E)) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val req = ImageRequest.Builder(ctx).data(url).allowHardware(false).size(96).build()
        val res = ctx.imageLoader.execute(req)
        val bmp = ((res as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val p = withContext(Dispatchers.Default) { Palette.from(bmp).maximumColorCount(16).generate() }
        val sw = p.vibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch
        if (sw != null) target = Color(sw.rgb)
    }
    return animateColorAsState(target, tween(700), label = "art").value
}

@Composable
private fun Pill(
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
    color: Color = PillBlack,
    align: Alignment = Alignment.Center,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = align,
        content = content,
    )
}

@Composable
private fun RoundButton(size: Dp, desc: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.Black).clickable(onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun TripleChevron(forward: Boolean, size: Dp, tint: Color = Color.White) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val tw = w * 0.40f
        val step = w * 0.28f
        val total = tw + 2 * step
        val startX = (w - total) / 2f
        val th = h * 0.50f
        val top = (h - th) / 2f
        val stroke = Stroke(width = w * 0.07f, join = StrokeJoin.Round)
        for (i in 0..2) {
            val x0 = startX + i * step
            val path = Path().apply {
                if (forward) {
                    moveTo(x0, top); lineTo(x0 + tw, h / 2f); lineTo(x0, top + th); close()
                } else {
                    val x1 = startX + total - i * step
                    moveTo(x1, top); lineTo(x1 - tw, h / 2f); lineTo(x1, top + th); close()
                }
            }
            drawPath(path, tint, style = Fill)
            drawPath(path, tint, style = stroke)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Waveform: the whole track across the screen width, time running left -> right.
// ------------------------------------------------------------------------------------------------

private val WAVE_MARGIN = 10.dp
private val BAR_W = 7.dp
private val BAR_PITCH = 12.5.dp

private fun waveAmp(bins: FloatArray?, durationMs: Long, i: Int, n: Int): Float {
    if (bins == null || durationMs <= 0) return 0f
    val b0 = ((i * durationMs / n) / Repo.WAVE_BIN_MS).toInt()
    val b1 = max(b0, (((i + 1) * durationMs / n - 1) / Repo.WAVE_BIN_MS).toInt())
    var sum = 0f
    var mx = 0f
    for (b in b0..b1) { val v = bins.getOrElse(b) { 0f }; sum += v; mx = max(mx, v) }
    val mean = sum / (b1 - b0 + 1)
    return (0.65f * mean + 0.35f * mx).pow(1.25f)
}

@Composable
private fun FullWaveform(
    bins: FloatArray?,
    durationMs: Long,
    prevBins: FloatArray?,
    prevDurationMs: Long,
    morph: Float,
    playing: Boolean,
    scrubbing: Boolean,
    cursorFrac: Float,
    shape: Float,
    playedFrac: () -> Float,
    fg: Color,
    modifier: Modifier,
) {
    // The bars only change colour when the playhead crosses one (a few px per second), so refresh at a low
    // rate: redrawing this layer every frame also forces every glass button above it to re-render.
    var barsF by remember { mutableFloatStateOf(0f) }
    val scrubbingNow by rememberUpdatedState(scrubbing)
    val cursorNow by rememberUpdatedState(cursorFrac)
    val playedNow by rememberUpdatedState(playedFrac)
    LaunchedEffect(Unit) {
        while (true) {
            if (scrubbingNow) { barsF = cursorNow; withFrameNanos { } }
            else {
                // ~1% steps: the bar colours only matter at that granularity, and every change re-renders the glass above.
                val q = (playedNow().coerceIn(0f, 1f) * 100f).toInt() / 100f
                if (q != barsF) barsF = q
                kotlinx.coroutines.delay(200)
            }
        }
    }
    Canvas(modifier) {
        val w = size.width
        val m = WAVE_MARGIN.toPx()
        val bw = BAR_W.toPx()
        val inner = w - 2 * m
        val n = (inner / BAR_PITCH.toPx()).toInt().coerceAtLeast(8)
        val pitch = (inner - bw) / (n - 1)
        val cy = size.height / 2f
        val flatH = 6.dp.toPx()
        val maxH = size.height - 8.dp.toPx()
        val f = barsF
        val playedA = 0.66f
        val unplayedA = 0.28f
        val stagger = 0.55f

        for (i in 0 until n) {
            // Old waveform morphs into the new one (flat if there was none) instead of collapsing and regrowing.
            val amp = waveAmp(bins, durationMs, i, n)
            val prevAmp = waveAmp(prevBins, prevDurationMs, i, n)
            // Ripple from the start of the track (left) towards the end (right).
            val local = ((morph * (1f + stagger) - stagger * i / n)).coerceIn(0f, 1f)
            val eased = local * local * (3f - 2f * local)
            val target = 14.dp.toPx() + (maxH - 14.dp.toPx()) * (prevAmp + (amp - prevAmp) * eased)
            val h = flatH + (target - flatH)
            val x = m + bw / 2f + i * pitch
            val t = ((f - i.toFloat() / n) * n).coerceIn(0f, 1f)
            val a = unplayedA + (playedA - unplayedA) * t
            drawRoundRect(
                fg.copy(alpha = a), Offset(x - bw / 2f, cy - h / 2f), Size(bw, h),
                CornerRadius(bw / 2f, bw / 2f)
            )
        }
    }
}

/**
 * Seekbar cursor, drawn in its own layer above the waveform so moving it does not invalidate the
 * waveform (and therefore not the glass buttons sampling it). Follows playback; jumps glide.
 */
@Composable
private fun WaveCursor(
    playing: Boolean,
    scrubbing: Boolean,
    cursorFrac: Float,
    shape: Float,
    playedFrac: () -> Float,
    fg: Color,
    modifier: Modifier,
) {
    var shownF by remember { mutableFloatStateOf(0f) }
    val scrubbingNow by rememberUpdatedState(scrubbing)
    val cursorNow by rememberUpdatedState(cursorFrac)
    val playedNow by rememberUpdatedState(playedFrac)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            if (scrubbingNow) { shownF = cursorNow; last = 0L; withFrameNanos { }; continue }
            val tgt = playedNow().coerceIn(0f, 1f)
            val d = tgt - shownF
            if (kotlin.math.abs(d) > 0.004f) {
                // A jump (±30 s, previous/next, tap): ease-out glide, one step per frame.
                val t = withFrameNanos { it }
                val dtMs = if (last == 0L) 16f else ((t - last) / 1_000_000f).coerceAtMost(48f)
                last = t
                shownF += d * (1f - kotlin.math.exp(-dtMs / 70f))
            } else {
                last = 0L
                // Sub-pixel moves are invisible: only redraw once the cursor has moved ~1 px.
                if (kotlin.math.abs(d) > 0.0012f) shownF = tgt
                kotlinx.coroutines.delay(60)
            }
        }
    }
    Canvas(modifier) {
        val m = WAVE_MARGIN.toPx()
        val bw = BAR_W.toPx()
        val inner = size.width - 2 * m
        val cy = size.height / 2f
        val maxH = size.height - 8.dp.toPx()
        val cx = m + bw / 2f + shownF.coerceIn(0f, 1f) * (inner - bw)
        val half = (44.dp.toPx() + (maxH + 16.dp.toPx() - 44.dp.toPx()) * shape) / 2f
        drawRoundRect(
            fg.copy(alpha = 0.95f), Offset(cx - 1.5.dp.toPx(), cy - half), Size(3.dp.toPx(), half * 2),
            CornerRadius(1.5.dp.toPx())
        )
    }
}

private fun fmtFormat(t: Track?, decodedSr: Int, decodedBr: Int): String {
    val sr = t?.samplingRate?.takeIf { it > 0 } ?: decodedSr
    val br = t?.bitRate?.takeIf { it > 0 } ?: (decodedBr / 1000)
    val parts = ArrayList<String>()
    if (t != null && t.format.isNotBlank()) parts += t.format
    if (t != null && t.bitDepth > 0 && t.isLossless) parts += "${t.bitDepth} BIT"
    if (sr > 0) parts += if (sr % 1000 == 0) "${sr / 1000} KHZ" else "%.1f KHZ".format(sr / 1000f)
    if (br > 0 && !(t?.isLossless ?: false)) parts += "$br KBPS"
    return parts.joinToString(" · ")
}

@Composable
fun PlayerScreen(
    vm: MainViewModel,
    onClose: () -> Unit,
    onNav: (Tab) -> Unit,
    onMenu: (Track) -> Unit,
    onQueue: () -> Unit,
    onAudioInfo: () -> Unit = {},
    playerBackdrop: LayerBackdrop,
    currentTab: Tab,
    onSwipeDelta: (Float) -> Unit = {},
    onSwipeEnd: (Float) -> Unit = {},
) {
    val ctx = LocalContext.current
    val player = vm.player
    val scope = rememberCoroutineScope()
    val ui by player.state.collectAsState()
    // Read the 5 Hz position only through derived/lazy reads, so the whole player does not recompose (and the
    // glass re-render) five times a second: the clock text changes once per second, everything else polls.
    val positionState = player.position.collectAsState()
    val posSec by remember { derivedStateOf { positionState.value / 1000 } }
    val favs by vm.starred.collectAsState()
    val beat = ui.track
    val session = LocalSession.current

    // Poweramp-style track change: the previous cover/colours/title stay on screen until the next
    // artwork is decoded and its palette is ready, then everything cross-fades together in one go.
    var shown by remember { mutableStateOf<ShownArt?>(null) }
    LaunchedEffect(beat?.id) {
        val b = beat ?: return@LaunchedEffect
        session?.coverUrl(b.coverArt, 800)?.let { ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(it).build()) }
        val (c, blurBmp) = loadArtInfo(ctx, session?.coverUrl(b.coverArt, 300))
        shown = ShownArt(b, c ?: shown?.tint ?: Color(0xFF26262E), blurBmp)
    }
    // Warm Coil's cache for the neighbouring tracks.
    LaunchedEffect(ui.queue, ui.index) {
        val loader = ctx.imageLoader
        for (off in intArrayOf(1, -1, 2)) {
            val nb = ui.queue.getOrNull(ui.index + off) ?: continue
            session?.coverUrl(nb.coverArt, 800)?.let { loader.enqueue(ImageRequest.Builder(ctx).data(it).build()) }
            session?.coverUrl(nb.coverArt, 300)?.let { loader.enqueue(ImageRequest.Builder(ctx).data(it).size(96).allowHardware(false).build()) }
        }
    }

    // Waveform: computed by the view model even while only the mini player is visible, so it is already there when
    // this screen opens (no animation then). On a track change the old one stays until the new one arrives, then morphs.
    val wave by vm.wave.collectAsState()
    val waveReady = wave.trackId == beat?.id
    var bins by remember { mutableStateOf(if (waveReady) wave.bins else null) }
    var binsDurMs by remember { mutableLongStateOf(if (waveReady) wave.durationMs else 0L) }
    var prevBins by remember { mutableStateOf<FloatArray?>(null) }
    var prevBinsDurMs by remember { mutableLongStateOf(0L) }
    val morphAnim = remember { Animatable(1f) }
    // New track whose waveform is not ready yet: drop the previous track's shape instead of leaving it on screen.
    LaunchedEffect(beat?.id) {
        if (wave.trackId != beat?.id && bins != null) {
            prevBins = bins; prevBinsDurMs = binsDurMs
            bins = null; binsDurMs = 0L
            morphAnim.snapTo(0f)
            morphAnim.animateTo(1f, tween(450))
        }
    }
    LaunchedEffect(wave, beat?.id) {
        if (wave.trackId != beat?.id || wave.bins === bins) return@LaunchedEffect
        prevBins = bins; prevBinsDurMs = binsDurMs
        bins = wave.bins; binsDurMs = wave.durationMs
        morphAnim.snapTo(0f)
        morphAnim.animateTo(1f, tween(if (prevBins == null) 900 else 450))
    }

    val durationMs = ui.durationMs.takeIf { it > 0 } ?: ((beat?.durationSec ?: 0) * 1000L)
    val paused = !ui.active

    // Scrub state
    var scrubbing by remember { mutableStateOf(false) }
    val cursor = remember { Animatable(0f) }
    val pausedT by animateFloatAsState(if (paused) 1f else 0f, tween(260), label = "paused")
    val timeSp by animateFloatAsState(if (paused) 13f else 16f, tween(220), label = "timeSp")

    val density = androidx.compose.ui.platform.LocalDensity.current
    val sampler = remember { BackdropSampler() }
    val layer = rememberGraphicsLayer()
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var artH by remember { mutableIntStateOf(0) }
    var artVersion by remember { mutableIntStateOf(0) }
    // Snapshot the real backdrop a few times while the palette / artwork settle.
    LaunchedEffect(shown?.beat?.id, artVersion, artH, rootSize) {
        if (rootSize == IntSize.Zero || artH == 0) return@LaunchedEffect
        delay(150); sampler.capture(layer)
        delay(500); sampler.capture(layer)
        delay(800); sampler.capture(layer)
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { rootSize = it }) {
        // Backdrop (recorded into a layer so each text can pick black or white from what is behind it)
        Box(
            Modifier.fillMaxSize().layerBackdrop(playerBackdrop).drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
        ) {
            Crossfade(shown, animationSpec = tween(260), label = "artFade") { st ->
                if (st != null) Box(Modifier.fillMaxSize()) {
                    // Vivid blurred copy of the cover fills the screen; the whole cover sits on top as a square.
                    Box(Modifier.fillMaxSize().background(Color.Black))
                    st.blur?.let {
                        Image(
                            it, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium,
                            colorFilter = ColorFilter.colorMatrix(
                                ColorMatrix().apply {
                                    setToSaturation(1.7f)
                                    // Lift dark covers so their colour zones stay vivid behind the controls.
                                    timesAssign(ColorMatrix(floatArrayOf(1.45f, 0f, 0f, 0f, 6f, 0f, 1.45f, 0f, 0f, 6f, 0f, 0f, 1.45f, 0f, 6f, 0f, 0f, 0f, 1f, 0f)))
                                }
                            ),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Colours stay vivid down the middle; the screen only goes to black near the bottom.
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Transparent, 0.5f to Color.Transparent, 0.64f to Color.Black.copy(alpha = 0.12f),
                                0.78f to Color.Black.copy(alpha = 0.45f), 0.9f to Color.Black.copy(alpha = 0.82f), 1f to Color.Black
                            )
                        )
                    )
                    // Square cover, full width, fully visible, true colours; its bottom edge melts into the blur.
                    val sidePx = if (artH > 0 && rootSize.width > 0) minOf(rootSize.width, artH + with(density) { 40.dp.roundToPx() }) else rootSize.width
                    AsyncImage(
                        model = session?.coverUrl(st.beat.coverArt, 800), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.align(Alignment.TopCenter).size(with(density) { sidePx.toDp() })
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    Brush.verticalGradient(0.86f to Color.Black, 1f to Color.Transparent),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                    )
                }
            }
        }


        Column(Modifier.fillMaxSize()) {
            // Artwork zone (swipe down from here to close the player)
            val swipeState = rememberDraggableState { onSwipeDelta(it) }
            Box(
                Modifier.fillMaxWidth().weight(1f).onSizeChanged { artH = it.height }.draggable(
                    state = swipeState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> onSwipeEnd(velocity) },
                )
            ) {
                GlassIconButton(
                    onClose, playerBackdrop, size = 46.dp,
                    modifier = Modifier.statusBarsPadding().padding(10.dp)
                ) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                // Title lines: no background, ink colour follows what is behind
                Adaptive(sampler, Modifier.fillMaxWidth().height(38.dp), Alignment.CenterStart) { fg ->
                    Crossfade(shown?.beat, animationSpec = tween(260), label = "titleFade") { sb ->
                        Text(
                            sb?.title ?: "", color = fg, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                            maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(horizontal = 2.dp).basicMarquee(iterations = 1, initialDelayMillis = 1500)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                val subBeat = shown?.beat
                val sub = remember(subBeat?.id) { subBeat?.subtitle?.replace(" · ", " – ").orEmpty() }
                Adaptive(sampler, Modifier.fillMaxWidth().height(34.dp), Alignment.CenterStart) { fg ->
                    Crossfade(sub, animationSpec = tween(260), label = "subFade") { sv ->
                        Text(
                            sv, color = fg.copy(alpha = 0.92f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                            maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(horizontal = 2.dp).basicMarquee(iterations = 1, initialDelayMillis = 2500)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                // Like / dislike / more (no pills)
                val isFav = beat != null && beat.id in favs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Adaptive(
                        sampler,
                        Modifier.width(50.dp).height(40.dp).clip(RoundedCornerShape(50))
                            .clickable { beat?.let { vm.setFavorite(it.id, !isFav) } }
                    ) { fg ->
                        Icon(
                            if (isFav) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt,
                            stringResource(R.string.like), tint = if (isFav) Accent else fg, modifier = Modifier.size(24.dp)
                        )
                    }
                    Adaptive(
                        sampler,
                        Modifier.width(50.dp).height(40.dp).clip(RoundedCornerShape(50))
                            .clickable { beat?.let { vm.setFavorite(it.id, false) } }
                    ) { fg ->
                        Icon(Icons.Rounded.ThumbDownOffAlt, stringResource(R.string.dislike), tint = fg, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Adaptive(
                        sampler,
                        Modifier.width(44.dp).height(40.dp).clip(RoundedCornerShape(50)).clickable { beat?.let(onMenu) }
                    ) { fg ->
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = fg, modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Queue · play mode · shuffle (glass)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(onQueue, playerBackdrop, size = 42.dp, width = 70.dp) {
                        Icon(Icons.Rounded.QueueMusic, stringResource(R.string.queue), tint = Color.White.copy(alpha = 0.9f))
                    }
                    Spacer(Modifier.weight(1f))
                    GlassIconButton({
                        val m = player.cycleMode()
                        Toast.makeText(
                            ctx,
                            when (m) {
                                PlayMode.CONTINUE -> R.string.mode_continue
                                PlayMode.REPEAT_ALL -> R.string.mode_repeat_all
                                PlayMode.REPEAT_ONE -> R.string.mode_repeat_one
                                PlayMode.STOP_AFTER -> R.string.mode_stop
                            }, Toast.LENGTH_SHORT
                        ).show()
                    }, playerBackdrop, size = 42.dp, width = 70.dp, tint = if (ui.mode != PlayMode.CONTINUE) GlassDefaults.Accent else Color.Unspecified) {
                        Icon(
                            when (ui.mode) {
                                PlayMode.CONTINUE -> Icons.Rounded.PlaylistPlay
                                PlayMode.REPEAT_ALL -> Icons.Rounded.Repeat
                                PlayMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
                                PlayMode.STOP_AFTER -> Icons.Rounded.StopCircle
                            },
                            null, tint = Color.White.copy(alpha = 0.9f)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    GlassIconButton(
                        player::toggleShuffle, playerBackdrop, size = 42.dp, width = 70.dp,
                        tint = if (ui.shuffle) GlassDefaults.Accent else Color.Unspecified
                    ) {
                        Icon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), tint = Color.White.copy(alpha = 0.9f))
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Transport + full-width waveform (touch it to scrub)
                var lastSeek by remember { mutableLongStateOf(0L) }
                val playedFrac: () -> Float = {
                    val p = if (ui.active) player.positionNow() else positionState.value
                    if (durationMs > 0) p.toFloat() / durationMs else 0f
                }
                val waveBackdrop = rememberLayerBackdrop()
                val transportBackdrop = rememberCombinedBackdrop(playerBackdrop, waveBackdrop)
                Box(
                    Modifier.fillMaxWidth().height(144.dp)
                        // Scrubbing only starts when the finger lands on the cursor; taps elsewhere do nothing.
                        .pointerInput(durationMs) {
                            val m = WAVE_MARGIN.toPx()
                            val bw = BAR_W.toPx()
                            val hit = 28.dp.toPx()
                            fun fracOf(x: Float) =
                                ((x - (m + bw / 2f)) / (size.width - 2 * m - bw)).coerceIn(0f, 1f)
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = true)
                                if (durationMs <= 0) return@awaitEachGesture
                                val cur = (player.positionNow().toFloat() / durationMs).coerceIn(0f, 1f)
                                val cx = m + bw / 2f + cur * (size.width - 2 * m - bw)
                                if (kotlin.math.abs(down.position.x - cx) > hit) return@awaitEachGesture
                                down.consume()
                                val grab = down.position.x - cx
                                scrubbing = true
                                player.scrubStart()
                                scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { cursor.snapTo(cur) }
                                var f = cur
                                try {
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        ch.consume()
                                        f = fracOf(ch.position.x - grab)
                                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { cursor.snapTo(f) }
                                    }
                                } finally {
                                    player.scrubEnd((f * durationMs).toLong())
                                    scrubbing = false
                                }
                            }
                        }
                ) {
                    Box(Modifier.fillMaxSize().layerBackdrop(waveBackdrop)) {
                        Adaptive(sampler, Modifier.fillMaxSize()) { fg ->
                            FullWaveform(
                                bins = bins, durationMs = binsDurMs, prevBins = prevBins, prevDurationMs = prevBinsDurMs,
                                morph = morphAnim.value, playing = ui.active, scrubbing = scrubbing,
                                cursorFrac = cursor.value, shape = if (bins != null) 1f else 0f,
                                playedFrac = playedFrac,
                                fg = fg,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    // Cursor lives outside waveBackdrop: gliding it must not re-render the glass buttons.
                    Adaptive(sampler, Modifier.fillMaxSize()) { fg ->
                        WaveCursor(
                            playing = ui.active, scrubbing = scrubbing, cursorFrac = cursor.value,
                            shape = if (bins != null) 1f else 0f, playedFrac = playedFrac, fg = fg,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Scale the transport row down on narrow screens so the outer buttons are never clipped.
                    BoxWithConstraints(Modifier.align(Alignment.Center).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val k = (maxWidth.value / 360f).coerceAtMost(1f)
                        Row(
                            Modifier,
                            horizontalArrangement = Arrangement.spacedBy(6.dp * k),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val dark = Color.Black.copy(alpha = 0.50f)
                            GlassIconButton({ player.seekBy(-30_000) }, transportBackdrop, size = ((48 - 2 * pausedT) * k).dp, container = dark) {
                                TripleChevron(false, (28 * k).dp)
                            }
                            GlassIconButton(player::previous, transportBackdrop, size = (68 * k).dp, container = dark) {
                                Icon(Icons.Rounded.FastRewind, stringResource(R.string.previous), tint = Color.White, modifier = Modifier.size((38 * k).dp))
                            }
                            GlassIconButton(player::togglePlay, transportBackdrop, size = ((96 + 8 * pausedT) * k).dp, container = dark) {
                                Icon(
                                    if (ui.active) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    stringResource(if (ui.active) R.string.pause else R.string.play),
                                    tint = Color.White, modifier = Modifier.size(((62 + 6 * pausedT) * k).dp)
                                )
                            }
                            GlassIconButton(player::next, transportBackdrop, size = (68 * k).dp, container = dark) {
                                Icon(Icons.Rounded.FastForward, stringResource(R.string.next), tint = Color.White, modifier = Modifier.size((38 * k).dp))
                            }
                            GlassIconButton({ player.seekBy(30_000) }, transportBackdrop, size = ((48 - 2 * pausedT) * k).dp, container = dark) {
                                TripleChevron(true, (28 * k).dp)
                            }
                        }
                    }
                }

                // Times and format on a single row
                val shownMs = if (scrubbing) (cursor.value * durationMs).toLong() else posSec * 1000
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Adaptive(sampler, Modifier.height(36.dp).widthIn(min = 56.dp), Alignment.CenterStart) { fg ->
                        Text(fmtMs(shownMs), color = fg, fontWeight = FontWeight.Bold, fontSize = timeSp.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Adaptive(
                            sampler,
                            Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(
                                interactionSource = null, indication = null, onClick = {},
                                onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onAudioInfo() }
                            ).padding(horizontal = 6.dp),
                            Alignment.Center
                        ) { fg ->
                            Text(
                                fmtFormat(beat, ui.sampleRate, ui.bitrate), color = fg.copy(alpha = 0.85f),
                                fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.3.sp, maxLines = 1
                            )
                        }
                    }
                    Adaptive(sampler, Modifier.height(36.dp).widthIn(min = 56.dp), Alignment.CenterEnd) { fg ->
                        Text(fmtMs(durationMs), color = fg, fontWeight = FontWeight.Bold, fontSize = timeSp.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) { AppTabs(currentTab, onNav, playerBackdrop) }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Floating glass mini player and tab bar
// ------------------------------------------------------------------------------------------------

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Rounded.Apps
    Tab.SEARCH -> Icons.Rounded.Search
    Tab.LIBRARY -> Icons.Rounded.Menu
}

@Composable
fun GlassMiniPlayer(vm: MainViewModel, backdrop: Backdrop, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.player.state.collectAsState()
    val position by vm.player.position.collectAsState()
    val beat = ui.track ?: return
    val duration = ui.durationMs.takeIf { it > 0 } ?: (beat.durationSec * 1000L)

    Column(
        modifier.fillMaxWidth()
            .glass(
                backdrop, shape = { RoundedRectangle(30.dp) }, blurRadius = 8.dp, lensHeight = 16.dp, lensAmount = 26.dp,
                container = GlassDefaults.Container
            )
            .clickable(interactionSource = null, indication = null, onClick = onOpen)
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -120f) vm.player.next() else if (total > 120f) vm.player.previous() }
                ) { change, dx -> change.consume(); total += dx }
            }
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(beat.coverArt, Modifier.size(44.dp), 12.dp, px = 100)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(beat.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(
                    beat.artist,
                    color = Color.White.copy(alpha = 0.78f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1
                )
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClick = vm.player::togglePlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (ui.active) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (ui.active) R.string.pause else R.string.play), tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Box(
            Modifier.fillMaxWidth().padding(start = 2.dp, end = 6.dp, top = 8.dp).height(8.dp)
                .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(
                Modifier.fillMaxWidth(frac.coerceAtLeast(0.05f)).fillMaxHeight()
                    .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.75f))
            )
        }
    }
}

/** The four main destinations as liquid-glass tabs with a draggable selection lens. */
@Composable
fun AppTabs(current: Tab, onSelect: (Tab) -> Unit, backdrop: Backdrop, modifier: Modifier = Modifier) {
    // A State holder keeps the selector lambda stable while still reporting the live selection.
    val currentState = rememberUpdatedState(current)
    GlassTabs(
        selectedTabIndex = { currentState.value.ordinal },
        onTabSelected = { onSelect(Tab.entries[it]) },
        backdrop = backdrop,
        tabsCount = Tab.entries.size,
        modifier = modifier,
    ) {
        for (t in Tab.entries) {
            val label = stringResource(
                when (t) {
                    Tab.HOME -> R.string.nav_home
                    Tab.SEARCH -> R.string.nav_search
                    Tab.LIBRARY -> R.string.nav_library
                }
            )
            GlassTab(onClick = { onSelect(t) }) {
                Icon(t.icon(), null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(26.dp))
                BasicText(
                    label, maxLines = 1,
                    style = androidx.compose.ui.text.TextStyle(color = Color.White.copy(alpha = 0.92f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}
