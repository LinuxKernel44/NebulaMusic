/*
 * Liquid-glass building blocks for NebulaMusic.
 * Built on https://github.com/Kyant0/AndroidLiquidGlass (io.github.kyant0:backdrop, Apache License 2.0).
 * GlassButton and GlassTabs are adapted from the library's sample components (LiquidButton, LiquidBottomTabs).
 */
package com.nebulamusic.app.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

object GlassDefaults {
    val Container = Color(0xFF16161B).copy(alpha = 0.38f)
    val ContainerStrong = Color(0xFF0C0C10).copy(alpha = 0.22f)
    val DefaultAccent = Color(0xFF9B8CFF)
    /** Follows the artwork of the current track (see AppRoot); reading it from composition recomposes on change. */
    var accentState by androidx.compose.runtime.mutableStateOf(DefaultAccent)
    val Accent: Color get() = accentState
}

/** The backdrop behind the current top-level screen, shared with every glass element. */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Draws the element as liquid glass: whatever was recorded in [backdrop] behind it is blurred,
 * saturated and (on Android 13+) refracted through a lens, then framed with a highlight and shadow.
 * On Android 12 the refraction is skipped by the library and only blur + vibrancy remain.
 */
fun Modifier.glass(
    backdrop: Backdrop,
    shape: () -> Shape = { Capsule() },
    blurRadius: Dp = 4.dp,
    lensHeight: Dp = 12.dp,
    lensAmount: Dp = 24.dp,
    tint: Color = Color.Unspecified,
    container: Color = GlassDefaults.Container,
    shadowOn: Boolean = true,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = shape,
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
        if (lensHeight > 0.dp) lens(lensHeight.toPx(), lensAmount.toPx())
    },
    shadow = if (shadowOn) ({ Shadow.Default }) else null,
    layerBlock = layerBlock,
    onDrawSurface = {
        if (tint.isSpecified) {
            drawRect(tint, blendMode = BlendMode.Hue)
            drawRect(tint.copy(alpha = 0.75f))
        }
        if (container.isSpecified) drawRect(container)
    }
)

/** A pressable glass capsule (or any shape) with the library's squish-and-glow touch feedback. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    shape: () -> Shape = { Capsule() },
    tint: Color = Color.Unspecified,
    container: Color = GlassDefaults.Container,
    blurRadius: Dp = 2.dp,
    lensHeight: Dp = 10.dp,
    lensAmount: Dp = 20.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    interactive: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope = animationScope) }

    Row(
        modifier
            .glass(
                backdrop = backdrop, shape = shape, blurRadius = blurRadius, lensHeight = lensHeight,
                lensAmount = lensAmount, tint = tint, container = container,
                layerBlock = if (interactive) {
                    {
                        val width = size.width
                        val h = size.height

                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX = scale + maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                            (width / h).fastCoerceAtMost(1f)
                        scaleY = scale + maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                            (h / width).fastCoerceAtMost(1f)
                    }
                } else null
            )
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .then(
                if (interactive) Modifier.then(interactiveHighlight.modifier).then(interactiveHighlight.gestureModifier)
                else Modifier
            )
            .height(height)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Small round or capsule icon button. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    width: Dp = size,
    tint: Color = Color.Unspecified,
    container: Color = GlassDefaults.Container,
    content: @Composable RowScope.() -> Unit,
) {
    GlassButton(
        onClick = onClick, backdrop = backdrop,
        modifier = modifier.width(width),
        height = size, tint = tint, container = container,
        lensHeight = (size.value * 0.25f).dp, lensAmount = (size.value * 0.5f).dp,
        contentPadding = PaddingValues(0.dp), content = content
    )
}

/** Capsule filter chip: tinted with the accent when selected. */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    GlassButton(
        onClick = onClick, backdrop = backdrop, modifier = modifier, height = 38.dp,
        tint = if (selected) GlassDefaults.Accent else Color.Unspecified,
        blurRadius = 2.dp, lensHeight = 8.dp, lensAmount = 14.dp,
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        androidx.compose.foundation.text.BasicText(
            label,
            style = TextStyle(
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1,
        )
        if (count != null) {
            androidx.compose.foundation.text.BasicText(
                count.toString(),
                style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Bottom tabs
// ------------------------------------------------------------------------------------------------

internal val LocalGlassTabScale = staticCompositionLocalOf { { 1f } }

@Composable
fun RowScope.GlassTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalGlassTabScale.current
    Column(
        modifier
            .clip(Capsule())
            .clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick)
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
fun GlassTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val accentColor = GlassDefaults.Accent
    val containerColor = GlassDefaults.Container
    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val tabWidth = with(density) { (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) { mutableIntStateOf(selectedTabIndex()) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch { offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f)) }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch { offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x) }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }.collectLatest { index -> currentIndex = index }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalGlassTabScale provides { lerp(1f, 1.2f, dampedDragAnimation.pressProgress) }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, chromaticAberration = true)
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 8f.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(0.12f), alpha = 1f - progress)
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Overlays rendered inside the composition (so they can sample the same backdrop)
// ------------------------------------------------------------------------------------------------

private val SheetShape = { RoundedRectangle(34.dp) }

/** Bottom sheet made of glass over a dimming scrim. Has a handle and follows the finger when dragged down. */
@Composable
fun GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var sheetH by remember { mutableIntStateOf(1) }
    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) offset.snapTo(0f) }

    fun release(velocity: Float) {
        scope.launch {
            if (offset.value > sheetH * 0.3f || velocity > 1200f) {
                offset.animateTo(sheetH.toFloat(), tween(180))
                onDismiss()
            } else offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
        }
    }
    fun dragBy(dy: Float) { scope.launch { offset.snapTo((offset.value + dy).coerceAtLeast(0f)) } }

    // Lets scrollable content (queue, playlists) hand the drag over to the sheet once it is at its top.
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f && offset.value > 0f) {
                    val take = maxOf(available.y, -offset.value); dragBy(take); Offset(0f, take)
                } else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0f && source == NestedScrollSource.UserInput) { dragBy(available.y); Offset(0f, available.y) }
                else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (offset.value > 0f) { release(available.y); available } else Velocity.Zero
        }
    }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = (1f - offset.value / sheetH).coerceIn(0f, 1f) }
                    .background(Color.Black.copy(alpha = 0.12f))
                    .clickable(interactionSource = null, indication = null, onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .onSizeChanged { sheetH = it.height.coerceAtLeast(1) }
                    .graphicsLayer { translationY = offset.value }
                    .glass(
                        backdrop, shape = SheetShape, blurRadius = 5.dp, lensHeight = 22.dp, lensAmount = 36.dp,
                        container = GlassDefaults.ContainerStrong
                    )
                    .pointerInput(Unit) { detectTapGestures { } }
                    .nestedScroll(nested)
                    .draggable(
                        rememberDraggableState { dragBy(it) }, Orientation.Vertical,
                        onDragStarted = { dragging = true },
                        onDragStopped = { dragging = false; release(it) }
                    )
                    .padding(bottom = 10.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                    val handleW by androidx.compose.animation.core.animateDpAsState(
                        if (dragging || offset.value > 0.5f) 40.dp else 84.dp,
                        spring(dampingRatio = 0.75f, stiffness = 400f), label = "handleW"
                    )
                    Box(Modifier.width(handleW).height(5.dp).background(Color.White.copy(alpha = 0.4f), RoundedCornerShape(50)))
                }
                content()
            }
        }
    }
}

/**
 * Small floating glass menu anchored to a corner, dismissed by tapping outside.
 *
 * It slides up while fading in, and slides back down while fading out. The slide is clipped to the menu's own
 * resting bounds, so it never shows below its bottom edge (the line it grows from / sinks back into).
 */
@Composable
fun GlassMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomEnd,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(
            if (visible) 1f else 0f,
            androidx.compose.animation.core.tween(if (visible) 280 else 220, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        )
    }
    Box(modifier.fillMaxSize()) {
        if (visible) {
            Box(
                Modifier.fillMaxSize()
                    .clickable(interactionSource = null, indication = null, onClick = onDismiss)
            )
        }
        if (visible || progress.value > 0f) {
            Box(Modifier.align(alignment).padding(12.dp).clipToBounds()) {
                Column(
                    Modifier
                        .graphicsLayer {
                            val p = progress.value
                            translationY = (1f - p) * size.height * 0.75f
                            alpha = p
                        }
                        .glass(
                            backdrop, shape = { RoundedRectangle(26.dp) }, blurRadius = 5.dp, lensHeight = 14.dp,
                            lensAmount = 24.dp, container = GlassDefaults.ContainerStrong
                        )
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}

/** Centered glass dialog. */
@Composable
fun GlassDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f))
                    .clickable(interactionSource = null, indication = null, onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.92f),
            exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.95f),
        ) {
            Column(
                Modifier
                    .padding(28.dp)
                    .fillMaxWidth()
                    .glass(
                        backdrop, shape = { RoundedRectangle(30.dp) }, blurRadius = 5.dp, lensHeight = 16.dp,
                        lensAmount = 26.dp, container = GlassDefaults.ContainerStrong
                    )
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(20.dp),
                content = content
            )
        }
    }
}

/** Single-line text input inside a glass capsule. */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Search,
    onImeAction: () -> Unit = {},
    password: Boolean = false,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
) {
    Row(
        modifier
            .glass(backdrop, blurRadius = 4.dp, lensHeight = 12.dp, lensAmount = 20.dp)
            .height(54.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                androidx.compose.foundation.text.BasicText(
                    placeholder, style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp), maxLines = 1
                )
            }
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(GlassDefaults.Accent),
                keyboardOptions = KeyboardOptions(
                    imeAction = imeAction,
                    keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else keyboardType,
                    autoCorrectEnabled = false,
                ),
                visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardActions = KeyboardActions(onSearch = { onImeAction() }, onDone = { onImeAction() }, onNext = { onImeAction() }),
                modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
        trailing?.invoke()
    }
}
