package com.example.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.WindowManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.example.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.i18n.TranslationLanguages
import com.example.service.BubbleBox
import com.example.service.TextBlockObj
import com.example.ui.components.LanguagePickerDialog
import com.example.ui.components.LanguagePickerOption
import com.example.ui.components.StateView
import com.example.ui.components.SurfaceCard
import com.example.ui.theme.AppElevation
import com.example.ui.theme.AppRadius
import com.example.ui.theme.AppSizes
import com.example.ui.theme.AppSpacing
import com.example.util.BubbleBackgroundGeometry
import com.example.util.BubbleLayoutGeometry
import com.example.util.BubbleContourExtractor
import com.example.util.BubbleTextLayoutEngine
import com.example.util.ContourScanlineProfile
import com.example.util.buildContourPath
import com.example.util.OverlayBackgroundBounds
import com.example.util.OverlayLayoutPolicy
import com.example.util.PageFontHarmonizer
import com.example.util.TurkishSyllabifier
import com.example.util.StaticTextLayoutOptimizer
import com.example.util.TextLayoutLocale
import com.example.viewmodel.MangaViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
private fun ReaderSheetSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = AppSpacing.md, bottom = AppSpacing.xs),
    )
}

@Composable
private fun ReaderChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 0.dp,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
            )
        },
        modifier = modifier.widthIn(min = minWidth),
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun ReaderToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leadingIcon: ImageVector? = null,
    leadingIconTint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSizes.minTouchTarget + AppSpacing.sm)
            .padding(vertical = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = leadingIconTint,
                        modifier = Modifier.size(AppSizes.iconSmall),
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun LanguageSettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSizes.minTouchTarget + AppSpacing.sm)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(AppSpacing.md))
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppSizes.icon),
            )
        }
    }
}

data class PlacedOverlayBox(
    val block: TextBlockObj,
    val leftDp: Dp,
    val topDp: Dp,
    val widthDp: Dp,
    val heightDp: Dp,
    val layoutResult: StaticTextLayoutOptimizer.OptimizedLayoutResult,
    val paddingHDp: Dp,
    val paddingVDp: Dp,
    val showOriginalFallback: Boolean = false,
    val fallbackSourceBounds: Rect? = null,
    val forceTextToFitBox: Boolean = true,
    val boldBoost: Boolean = false,
    val contourProfile: ContourScanlineProfile? = null
)

// Detector boxes are rectangles around rounded balloons. The largest
// axis-aligned rectangle that is guaranteed to fit inside the inscribed
// ellipse is about 70% of the detector box (1/sqrt(2)). Fitting text to the
// full rectangle makes the first and last lines visibly spill outside the
// balloon; always use this safe interior for layout and expansion.
private fun bubbleTextSafeRect(bubble: Rect, fill: Float = 0.35f): Rect? {
    if (bubble.isEmpty) return null
    val centerX = bubble.exactCenterX()
    val centerY = bubble.exactCenterY()
    val safe = Rect(
        (centerX - bubble.width() * fill).toInt().coerceAtLeast(bubble.left),
        (centerY - bubble.height() * fill).toInt().coerceAtLeast(bubble.top),
        (centerX + bubble.width() * fill).toInt().coerceAtMost(bubble.right),
        (centerY + bubble.height() * fill).toInt().coerceAtMost(bubble.bottom)
    )
    return safe.takeUnless { it.isEmpty }
}

// Birbirine yakın/değen iki çeviri kutusu varsa, iki kutuyu da kesişim
// bölgesinin orta hattına kadar geri çekerek birbirlerinin üzerine
// çökmelerini engeller. Kutular orijinal boyutlarının %35'inden fazla
// küçültülmez. Küçültülen bloklar işaretlenir; bu blokların yazıları
// kalın çizilerek küçülen alanda okunabilirlik telafi edilir.
private fun separateNearbyBoxes(
    rects: List<Rect>,
    isBubble: List<Boolean>,
    anchorRects: List<Rect>
): BooleanArray {
    val boosted = BooleanArray(rects.size)
    if (rects.size < 2) return boosted
    val margin = 3
    val originalWidths = IntArray(rects.size) { rects[it].width() }
    val originalHeights = IntArray(rects.size) { rects[it].height() }

    fun minWidthFromLeft(index: Int): Int {
        val anchor = anchorRects.getOrNull(index) ?: return 0
        return anchor.right - rects[index].left
    }

    fun minWidthFromRight(index: Int): Int {
        val anchor = anchorRects.getOrNull(index) ?: return 0
        return rects[index].right - anchor.left
    }

    fun minHeightFromTop(index: Int): Int {
        val anchor = anchorRects.getOrNull(index) ?: return 0
        return anchor.bottom - rects[index].top
    }

    fun minHeightFromBottom(index: Int): Int {
        val anchor = anchorRects.getOrNull(index) ?: return 0
        return rects[index].bottom - anchor.top
    }

    repeat(2) {
    for (i in rects.indices) {
        if (!isBubble.getOrElse(i) { false }) continue
        for (j in i + 1 until rects.size) {
            if (!isBubble.getOrElse(j) { false }) continue
            val a = rects[i]
            val b = rects[j]
            if (a.isEmpty || b.isEmpty) continue
            val interLeft = max(a.left, b.left)
            val interTop = max(a.top, b.top)
            val interRight = min(a.right, b.right)
            val interBottom = min(a.bottom, b.bottom)
            val interW = interRight - interLeft
            val interH = interBottom - interTop
            if (interW <= 0 || interH <= 0) continue

            val aArea = a.width().toLong() * a.height().toLong()
            val bArea = b.width().toLong() * b.height().toLong()
            val interArea = interW.toLong() * interH.toLong()
            val unionArea = aArea + bArea - interArea
            val overlapRatio = if (unionArea > 0L) interArea.toFloat() / unionArea else 1f
            val aContained = interArea >= aArea * 9L / 10L
            val bContained = interArea >= bArea * 9L / 10L

            val aCenterY = anchorRects.getOrNull(i)?.centerY() ?: a.centerY()
            val bCenterY = anchorRects.getOrNull(j)?.centerY() ?: b.centerY()
            val aCenterX = anchorRects.getOrNull(i)?.centerX() ?: a.centerX()
            val bCenterX = anchorRects.getOrNull(j)?.centerX() ?: b.centerX()

            if (aContained || bContained || overlapRatio >= 0.35f) {
                // Ağır çakışma (aynı balonda iki metin): Metinlerin orijinal OCR merkezlerine göre
                // dikey veya yatay olarak ayrıştırılması zorunludur. continue ile üst üste bırakılamaz.
                val verticalDist = kotlin.math.abs(aCenterY - bCenterY)
                val horizontalDist = kotlin.math.abs(aCenterX - bCenterX)
                if (verticalDist >= horizontalDist * 0.7f) {
                    val ti = if (aCenterY <= bCenterY) i else j
                    val bi = if (ti == i) j else i
                    val topBox = rects[ti]
                    val bottomBox = rects[bi]
                    val mid = interTop + interH / 2
                    topBox.bottom = min(topBox.bottom, mid - margin).coerceAtLeast(topBox.top + 16)
                    bottomBox.top = max(bottomBox.top, mid + margin).coerceAtMost(bottomBox.bottom - 16)
                    boosted[ti] = true
                    boosted[bi] = true
                } else {
                    val li = if (aCenterX <= bCenterX) i else j
                    val ri = if (li == i) j else i
                    val leftBox = rects[li]
                    val rightBox = rects[ri]
                    val mid = interLeft + interW / 2
                    leftBox.right = min(leftBox.right, mid - margin).coerceAtLeast(leftBox.left + 16)
                    rightBox.left = max(rightBox.left, mid + margin).coerceAtMost(rightBox.right - 16)
                    boosted[li] = true
                    boosted[ri] = true
                }
                continue
            }

            if (interW <= interH) {
                // Dikey bölme: soldaki kutunun sağ kenarı, sağdakinin sol kenarı
                // kesişimin orta hattına çekilir.
                val li = if (a.centerX() <= b.centerX()) i else j
                val ri = if (li == i) j else i
                val leftBox = rects[li]
                val rightBox = rects[ri]
                val mid = interLeft + interW / 2
                val newLeftRight = mid - margin
                val newRightLeft = mid + margin
                val minLeftWidth = (originalWidths[li] * 0.55f).toInt().coerceAtLeast(20)
                val minRightWidth = (originalWidths[ri] * 0.55f).toInt().coerceAtLeast(20)
                val canPullLeft = newLeftRight - leftBox.left >= minLeftWidth
                val canPullRight = rightBox.right - newRightLeft >= minRightWidth
                if (canPullLeft && canPullRight) {
                    leftBox.right = newLeftRight
                    rightBox.left = newRightLeft
                    boosted[li] = true
                    boosted[ri] = true
                } else if (canPullLeft && leftBox.width() > rightBox.width()) {
                    leftBox.right = (rightBox.left - margin).coerceAtLeast(leftBox.left + minLeftWidth)
                    boosted[li] = true
                } else if (canPullRight && rightBox.width() > leftBox.width()) {
                    rightBox.left = (leftBox.right + margin).coerceAtMost(rightBox.right - minRightWidth)
                    boosted[ri] = true
                }
            } else {
                // Yatay bölme: üstteki kutunun alt kenarı, alttakinin üst kenarı
                // kesişimin orta hattına çekilir.
                val ti = if (a.centerY() <= b.centerY()) i else j
                val bi = if (ti == i) j else i
                val topBox = rects[ti]
                val bottomBox = rects[bi]
                val mid = interTop + interH / 2
                val newTopBottom = mid - margin
                val newBottomTop = mid + margin
                val minTopHeight = (originalHeights[ti] * 0.55f).toInt().coerceAtLeast(20)
                val minBottomHeight = (originalHeights[bi] * 0.55f).toInt().coerceAtLeast(20)
                val canPullTop = newTopBottom - topBox.top >= minTopHeight
                val canPullBottom = bottomBox.bottom - newBottomTop >= minBottomHeight
                if (canPullTop && canPullBottom) {
                    topBox.bottom = newTopBottom
                    bottomBox.top = newBottomTop
                    boosted[ti] = true
                    boosted[bi] = true
                } else if (canPullTop && topBox.height() > bottomBox.height()) {
                    topBox.bottom = (bottomBox.top - margin).coerceAtLeast(topBox.top + minTopHeight)
                    boosted[ti] = true
                } else if (canPullBottom && bottomBox.height() > topBox.height()) {
                    bottomBox.top = (topBox.bottom + margin).coerceAtMost(bottomBox.bottom - minBottomHeight)
                    boosted[bi] = true
                }
            }
        }
    }
    }
    return boosted
}

private data class BoxGeometry(
    val leftDp: Dp,
    val topDp: Dp,
    val widthDp: Dp,
    val heightDp: Dp,
    val padH: Dp,
    val padV: Dp
)

internal fun resolveIsRectangularBox(
    isBubble: Boolean,
    bubbleBox: Rect,
    targetBox: Rect,
    contourProfile: ContourScanlineProfile?
): Boolean {
    if (!isBubble || bubbleBox.isEmpty) return true
    val usableContour = contourProfile?.takeIf { it.isUsable }
    if (usableContour != null) return usableContour.isRectangularLike
    return false
}

internal fun resolveDrawIsRectangularBox(
    isBubble: Boolean,
    bubbleBox: Rect,
    widthDp: Float,
    heightDp: Float,
    contourProfile: ContourScanlineProfile?
): Boolean {
    if (!isBubble || bubbleBox.isEmpty) return true
    val usableContour = contourProfile?.takeIf { it.isUsable }
    if (usableContour != null) return usableContour.isRectangularLike
    return false
}

internal fun shouldDrawRectangularBorder(
    isSubtitleMode: Boolean,
    isRectangularBox: Boolean,
    isBubble: Boolean
): Boolean = !isSubtitleMode && isRectangularBox && isBubble

internal fun shouldExpandMicroSliver(
    widthDp: Float,
    heightDp: Float,
    isSliver: Boolean
): Boolean {
    if (!isSliver) return false
    if (heightDp >= widthDp * 1.9f && widthDp >= 14f) return false
    return widthDp < 14f
}

internal fun shouldDrawNaturalBubbleBorder(
    isSubtitleMode: Boolean,
    isBubble: Boolean,
    hasRealMaskBitmap: Boolean,
    isFallbackExpanded: Boolean,
    bubbleStyle: String = "Doğal Balon"
): Boolean {
    if (isSubtitleMode || !isBubble) return false
    if (hasRealMaskBitmap &&
        !isFallbackExpanded &&
        bubbleStyle != "Klasik Kutu" &&
        bubbleStyle != "Koyu Zemin"
    ) {
        return false
    }
    return true
}

internal fun resolveShouldDrawFloatingHaloText(
    isBubble: Boolean,
    hasBackgroundColor: Boolean,
    bubbleStyle: String
): Boolean = !isBubble && !hasBackgroundColor && bubbleStyle != "Alt Yazı (Şeffaf)"

internal fun resolveFloatingHaloStrokeColor(textColor: Color): Color =
    if (textColor == Color.White) Color.Black.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.96f)

internal data class DrawBoxRectDp(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

internal fun resolveOverlayBackgroundBoundsDp(
    isSubtitleMode: Boolean,
    isOvalFallback: Boolean,
    anchorLeftDp: Float,
    anchorTopDp: Float,
    anchorRightDp: Float,
    anchorBottomDp: Float,
    bubbleLeftDp: Float,
    bubbleTopDp: Float,
    bubbleRightDp: Float,
    bubbleBottomDp: Float,
    placedLeftDp: Float,
    placedTopDp: Float,
    placedRightDp: Float,
    placedBottomDp: Float,
    marginDp: Float = 2f
): DrawBoxRectDp {
    if (isSubtitleMode) {
        return DrawBoxRectDp(
            left = placedLeftDp,
            top = placedTopDp,
            right = maxOf(placedRightDp, placedLeftDp + 1f),
            bottom = maxOf(placedBottomDp, placedTopDp + 1f)
        )
    }
    var left = (minOf(bubbleLeftDp, anchorLeftDp, placedLeftDp) - marginDp).coerceAtLeast(0f)
    var top = (minOf(bubbleTopDp, anchorTopDp, placedTopDp) - marginDp).coerceAtLeast(0f)
    var right = maxOf(bubbleRightDp, anchorRightDp, placedRightDp) + marginDp
    var bottom = maxOf(bubbleBottomDp, anchorBottomDp, placedBottomDp) + marginDp

    if (isOvalFallback && right > left && bottom > top) {
        // An ellipse inscribed in a bounding box cuts ~29% of corners. Expand symmetrically
        // around the center when needed so all 4 corners of anchorBox (plus 1dp ink margin)
        // stay covered by the opaque oval background and never leak source corner glyphs.
        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f
        val rx = ((right - left) * 0.5f).coerceAtLeast(1f)
        val ry = ((bottom - top) * 0.5f).coerceAtLeast(1f)
        val coverMargin = 1f
        val dxMax = maxOf(
            abs((anchorLeftDp - coverMargin) - cx),
            abs((anchorRightDp + coverMargin) - cx)
        )
        val dyMax = maxOf(
            abs((anchorTopDp - coverMargin) - cy),
            abs((anchorBottomDp + coverMargin) - cy)
        )
        val u = dxMax / rx
        val v = dyMax / ry
        val normSq = u * u + v * v
        if (normSq > 1.0f) {
            val expandScale = (sqrt(normSq) * 1.03f).coerceAtMost(1.50f)
            val newRx = rx * expandScale
            val newRy = ry * expandScale
            left = (cx - newRx).coerceAtLeast(0f)
            top = (cy - newRy).coerceAtLeast(0f)
            right = maxOf(cx + newRx, anchorRightDp + coverMargin + 1f)
            bottom = maxOf(cy + newRy, anchorBottomDp + coverMargin + 1f)
        }
    }

    return DrawBoxRectDp(
        left = left,
        top = top,
        right = maxOf(right, left + 1f),
        bottom = maxOf(bottom, top + 1f)
    )
}

internal fun computeBubblePaddingDp(
    widthDp: Float,
    heightDp: Float,
    isRectangularBox: Boolean
): Pair<Float, Float> {
    if (isRectangularBox) {
        val pH = minOf(widthDp * 0.25f, maxOf(3.5f, minOf(widthDp * 0.06f, 6f))).coerceAtLeast(0f)
        val pV = minOf(heightDp * 0.25f, maxOf(3.5f, minOf(heightDp * 0.06f, 6f))).coerceAtLeast(0f)
        return pH to pV
    }
    val baseH = minOf(widthDp * 0.045f, 5f).coerceAtLeast(0f)
    val isNarrowVertical = widthDp > 0f && heightDp > 0f &&
        (heightDp > widthDp * 1.35f || widthDp < 64f)
    val pH = if (isNarrowVertical) {
        minOf(widthDp * 0.14f, maxOf(baseH, 4.0f, minOf(widthDp * 0.075f, 5.5f))).coerceAtLeast(0f)
    } else {
        baseH
    }
    val pV = minOf(heightDp * 0.045f, 5f).coerceAtLeast(0f)
    return pH to pV
}

internal data class CanvasTextTransform(
    val scale: Float,
    val translateX: Float,
    val translateY: Float
)

internal fun computeCanvasTextTransform(
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    layoutWidthPx: Float,
    layoutHeightPx: Float,
    maxLineWidthPx: Float
): CanvasTextTransform {
    if (canvasWidthPx <= 0f || canvasHeightPx <= 0f ||
        layoutWidthPx <= 0f || layoutHeightPx <= 0f
    ) {
        return CanvasTextTransform(scale = 1f, translateX = 0f, translateY = 0f)
    }
    val effectiveWidth = maxOf(layoutWidthPx, maxLineWidthPx).coerceAtLeast(1f)
    val safeHeight = layoutHeightPx.coerceAtLeast(1f)
    val drawScale = minOf(
        1f,
        canvasWidthPx / effectiveWidth,
        canvasHeightPx / safeHeight
    )
    val drawnBoxWidth = layoutWidthPx * drawScale
    val drawnHeight = layoutHeightPx * drawScale
    return CanvasTextTransform(
        scale = drawScale,
        translateX = (canvasWidthPx - drawnBoxWidth) / 2f,
        translateY = (canvasHeightPx - drawnHeight) / 2f
    )
}

private fun contrastingTextColor(backgroundColor: Int, effectiveAlpha: Float = 1f): Color {
    val alpha = effectiveAlpha.coerceIn(0f, 1f)
    fun linearChannel(channel: Int): Float {
        val srgb = (channel * alpha + 255f * (1f - alpha)) / 255f
        return if (srgb <= 0.04045f) srgb / 12.92f
        else ((srgb + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    val luminance = 0.2126f * linearChannel(android.graphics.Color.red(backgroundColor)) +
        0.7152f * linearChannel(android.graphics.Color.green(backgroundColor)) +
        0.0722f * linearChannel(android.graphics.Color.blue(backgroundColor))
    return if (luminance >= 0.179f) Color.Black else Color.White
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    viewModel: MangaViewModel,
    chapterId: String,
    onNavigateBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var bottomHudHeightPx by remember { mutableIntStateOf(0) }
    val bottomHudHeightDp = with(density) { bottomHudHeightPx.toDp() }

    var retainedReaderChapterId by rememberSaveable(chapterId) {
        mutableStateOf(chapterId)
    }
    LaunchedEffect(chapterId, viewModel) {
        val chapterToRestore = retainedReaderChapterId.ifBlank { chapterId }
        if (viewModel.activeChapter.value?.id != chapterToRestore) {
            viewModel.loadChapterInReader(chapterToRestore)
        } else if (viewModel.translationActive.value) {
            viewModel.resumeReaderTranslation()
        }
    }
    LaunchedEffect(viewModel) {
        // Navigation actions load chapters through the ViewModel; this collector only
        // remembers the latest requested id so it can be restored after recreation.
        viewModel.requestedReaderChapterId.collect { requestedId ->
            if (requestedId.isNotBlank()) retainedReaderChapterId = requestedId
        }
    }

    val loading by viewModel.readerLoading.collectAsState()
    val readerError by viewModel.readerError.collectAsState()
    val manga by viewModel.readerManga.collectAsState()
    val activeCh by viewModel.activeChapter.collectAsState()
    val chapters by viewModel.readerChapters.collectAsState()
    val pages by viewModel.readerPages.collectAsState()
    val webChapterUrl = remember(activeCh?.id, chapterId) {
        viewModel.webChapterUrl(activeCh?.id ?: chapterId)
    }
    val currentPageIdx by viewModel.currentPageIndex.collectAsState()
    val translationActive by viewModel.translationActive.collectAsState()
    val translatedPagesMap by viewModel.translatedPagesMap.collectAsState()
    val isTranslationLoading by viewModel.isTranslationLoading.collectAsState()
    val translationNotice by viewModel.translationNotice.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(translationNotice) {
        val message = translationNotice
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            viewModel.clearTranslationNotice()
        }
    }
    val translationBubbleStyle by viewModel.translationBubbleStyle.collectAsState()
    val translationOpacity by viewModel.translationOpacity.collectAsState()
    val translationEngine by viewModel.preferences.translationEngine.collectAsState(initial = "")
    val translationTargetLanguage by viewModel.translationTargetLanguage.collectAsState()
    val translationSourceLanguage by viewModel.translationSourceLanguage.collectAsState()
    val recentTranslationLanguages by viewModel.recentTranslationLanguages.collectAsState()
    val autoTranslateEnabled by viewModel.preferences.autoTranslate.collectAsState(initial = false)
    var showTargetLanguagePicker by remember { mutableStateOf(false) }
    var showSourceLanguagePicker by remember { mutableStateOf(false) }
    val resolvedSourceLabel = if (translationSourceLanguage == TranslationLanguages.AUTO) {
        "(Otomatik) ${manga?.originLanguage ?: "İngilizce"}"
    } else {
        TranslationLanguages.turkishName(translationSourceLanguage) ?: translationSourceLanguage
    }
    val targetLanguageLabel =
        TranslationLanguages.turkishName(translationTargetLanguage) ?: translationTargetLanguage
    val readingDirection by viewModel.readingDirection.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val brightnessLock by viewModel.brightnessLock.collectAsState()
    val isDebugMode by viewModel.isDebugMode.collectAsState()

    var showHud by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showCorrectionDialog by remember { mutableStateOf(false) }
    var correctionOriginal by remember { mutableStateOf("") }
    var correctionTranslated by remember { mutableStateOf("") }
    var correctionSaving by remember { mutableStateOf(false) }
    var correctionSaveError by remember { mutableStateOf<String?>(null) }

    val readerWindow = activity?.window
    DisposableEffect(readerWindow, keepScreenOn, brightnessLock) {
        if (readerWindow == null) {
            onDispose { }
        } else {
            val originalKeepScreenOn =
                readerWindow.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            val originalBrightness = readerWindow.attributes.screenBrightness

            if (keepScreenOn) {
                readerWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                readerWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            readerWindow.attributes = readerWindow.attributes.apply {
                screenBrightness = if (brightnessLock) {
                    1.0f
                } else {
                    originalBrightness
                }
            }

            onDispose {
                if (originalKeepScreenOn) {
                    readerWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    readerWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                readerWindow.attributes = readerWindow.attributes.apply {
                    screenBrightness = originalBrightness
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        val errorMsg = readerError
        if (errorMsg != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(AppSpacing.lg),
                contentAlignment = Alignment.Center
            ) {
                SurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    StateView(
                        title = "Bölüm Yüklenemedi",
                        message = errorMsg,
                        icon = Icons.Default.ErrorOutline,
                        actionLabel = "Yeniden Dene",
                        onAction = {
                            retainedReaderChapterId.ifBlank { chapterId }
                                .let(viewModel::loadChapterInReader)
                        },
                        secondaryActionLabel = "Geri Git",
                        onSecondaryAction = onNavigateBack,
                    )
                    if (webChapterUrl != null) {
                        TextButton(
                            onClick = { onOpenInBrowser(webChapterUrl) },
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .heightIn(min = AppSizes.minTouchTarget)
                        ) {
                            Text(
                                text = "Tarayıcıda Aç",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        } else if (loading || activeCh == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                StateView(
                    title = "Bölüm yükleniyor…",
                    loading = true,
                )
            }
        } else if (pages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(AppSpacing.lg),
                contentAlignment = Alignment.Center
            ) {
                SurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    StateView(
                        title = "Bu bölümde gösterilecek sayfa bulunamadı.",
                        actionLabel = "Yeniden Dene",
                        onAction = {
                            retainedReaderChapterId.ifBlank { chapterId }
                                .let(viewModel::loadChapterInReader)
                        },
                        secondaryActionLabel = "Geri",
                        onSecondaryAction = onNavigateBack,
                    )
                }
            }
        } else {
            val ch = activeCh!!
            val totalPages = pages.size
            val safeCurrentPageIdx = if (totalPages > 0) {
                currentPageIdx.coerceIn(0, totalPages - 1)
            } else {
                0
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Box(
                    modifier = Modifier
                    .fillMaxSize()
                        .clickable(interactionSource = null, indication = null) { showHud = !showHud }
                ) {
                    key(ch.id, readingDirection) {
                    if (readingDirection == "Dikey") {
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeCurrentPageIdx)
                        LaunchedEffect(listState, totalPages) {
                            if (totalPages > 0) {
                                snapshotFlow {
                                    val layoutInfo = listState.layoutInfo
                                    val viewportStart = layoutInfo.viewportStartOffset
                                    val viewportEnd = layoutInfo.viewportEndOffset
                                    val mostVisibleIndex = layoutInfo.visibleItemsInfo
                                        .maxByOrNull { item ->
                                            val visibleStart = maxOf(item.offset, viewportStart)
                                            val visibleEnd = minOf(item.offset + item.size, viewportEnd)
                                            (visibleEnd - visibleStart).coerceAtLeast(0)
                                        }
                                        ?.index
                                        ?: listState.firstVisibleItemIndex
                                    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index
                                    if (
                                        !listState.canScrollForward &&
                                        lastVisibleIndex == totalPages - 1 &&
                                        (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
                                    ) {
                                        totalPages - 1
                                    } else {
                                        mostVisibleIndex
                                    }
                                }
                                    .distinctUntilChanged()
                                    .collect { index ->
                                        viewModel.onPageIndexChanged(index.coerceIn(0, totalPages - 1))
                                    }
                            }
                        }
                        LaunchedEffect(currentPageIdx, totalPages) {
                            if (totalPages > 0 && listState.firstVisibleItemIndex != safeCurrentPageIdx && !listState.isScrollInProgress) {
                                listState.scrollToItem(safeCurrentPageIdx)
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(pages.size, key = { "${ch.id}_$it" }) { idx ->
                                ReaderPageContainer(
                                    chapterId = ch.id,
                                    pageIndex = idx,
                                    viewModel = viewModel,
                                    translationActive = translationActive,
                                    translatedBlocks = translatedPagesMap[idx] ?: emptyList(),
                                    bubbleStyle = translationBubbleStyle,
                                    opacity = translationOpacity,
                                    targetLanguage = translationTargetLanguage,
                                    isDebugMode = isDebugMode,
                                    onCorrectionClick = { orig, trans ->
                                        correctionOriginal = orig
                                        correctionTranslated = trans
                                        showCorrectionDialog = true
                                    }
                                )
                            }
                        }
                    } else {
                        val pagerState = rememberPagerState(initialPage = safeCurrentPageIdx) { totalPages }
                        LaunchedEffect(currentPageIdx, totalPages) {
                            if (totalPages > 0 && pagerState.currentPage != safeCurrentPageIdx) {
                                pagerState.scrollToPage(safeCurrentPageIdx)
                            }
                        }
                        LaunchedEffect(pagerState.currentPage, totalPages) {
                            if (totalPages > 0) {
                                viewModel.onPageIndexChanged(pagerState.currentPage.coerceIn(0, totalPages - 1))
                            }
                        }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            key = { "${ch.id}_$it" }
                        ) { index ->
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                ReaderPageContainer(
                                    chapterId = ch.id,
                                    pageIndex = index,
                                    viewModel = viewModel,
                                    translationActive = translationActive,
                                    translatedBlocks = translatedPagesMap[index] ?: emptyList(),
                                    bubbleStyle = translationBubbleStyle,
                                    opacity = translationOpacity,
                                    targetLanguage = translationTargetLanguage,
                                    isDebugMode = isDebugMode,
                                    onCorrectionClick = { orig, trans ->
                                        correctionOriginal = orig
                                        correctionTranslated = trans
                                        showCorrectionDialog = true
                                    }
                                )
                            }
                        }
                    }
                    }
                }

                // TOP TOOLBAR HUD
                if (showHud) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .wrapContentHeight(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(
                            bottomStart = AppRadius.lg,
                            bottomEnd = AppRadius.lg
                        ),
                        shadowElevation = AppElevation.low
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                IconButton(
                                    onClick = onNavigateBack,
                                    modifier = Modifier
                                        .testTag("reader_back")
                                        .sizeIn(
                                            minWidth = AppSizes.minTouchTarget,
                                            minHeight = AppSizes.minTouchTarget
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Geri",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = AppSpacing.xs)
                                ) {
                                    Text(
                                        text = manga?.title ?: "Manga",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = ch.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (webChapterUrl != null) {
                                    IconButton(
                                        onClick = { onOpenInBrowser(webChapterUrl) },
                                        modifier = Modifier
                                            .testTag("reader_open_browser")
                                            .sizeIn(
                                                minWidth = AppSizes.minTouchTarget,
                                                minHeight = AppSizes.minTouchTarget
                                            )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Public,
                                            contentDescription = "Tarayıcıda Aç",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { viewModel.toggleDebugMode() },
                                    modifier = Modifier
                                        .testTag("reader_debug_toggle")
                                        .sizeIn(
                                            minWidth = AppSizes.minTouchTarget,
                                            minHeight = AppSizes.minTouchTarget
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BugReport,
                                        contentDescription = "RT-DETR AI Balon Debug Modu",
                                        tint = if (isDebugMode) {
                                            Color(0xFF00E676)
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                                IconButton(
                                    onClick = { showSettingsSheet = true },
                                    modifier = Modifier
                                        .testTag("reader_settings")
                                        .sizeIn(
                                            minWidth = AppSizes.minTouchTarget,
                                            minHeight = AppSizes.minTouchTarget
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Okuyucu Ayarları",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // BOTTOM BAR STATUS HUD
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .onSizeChanged { bottomHudHeightPx = it.height },
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(
                            topStart = AppRadius.lg,
                            topEnd = AppRadius.lg
                        ),
                        shadowElevation = AppElevation.low
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Sayfa ${currentPageIdx + 1} / $totalPages",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.xs))
                            val lastPageIndex = (totalPages - 1).coerceAtLeast(0)
                            var sliderPage by remember(currentPageIdx) {
                                mutableStateOf(currentPageIdx.toFloat())
                            }
                            Slider(
                                value = sliderPage.coerceIn(0f, lastPageIndex.toFloat()),
                                onValueChange = { sliderPage = it.coerceIn(0f, lastPageIndex.toFloat()) },
                                onValueChangeFinished = {
                                    viewModel.onPageIndexChanged(sliderPage.roundToInt().coerceIn(0, lastPageIndex))
                                },
                                valueRange = 0f..lastPageIndex.toFloat(),
                                steps = (totalPages - 2).coerceAtLeast(0),
                                enabled = totalPages > 1,
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val currentIdx = chapters.indexOfFirst { it.id == ch.id }
                                fun adjacentChapter(step: Int): com.example.data.db.ChapterEntity? {
                                    if (currentIdx == -1) return null
                                    var index = currentIdx + step
                                    while (index in chapters.indices) {
                                        val candidate = chapters[index]
                                        val sameNumberTranslation = ch.chapterNumber != 0.0 &&
                                            candidate.chapterNumber == ch.chapterNumber
                                        if (!sameNumberTranslation) return candidate
                                        index += step
                                    }
                                    return null
                                }
                                val prevCh = adjacentChapter(1)
                                val nextCh = adjacentChapter(-1)

                                FilledTonalButton(
                                    onClick = { prevCh?.let { viewModel.loadChapterInReader(it.id) } },
                                    enabled = prevCh != null,
                                    modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
                                    contentPadding = PaddingValues(
                                        horizontal = AppSpacing.md,
                                        vertical = AppSpacing.xs
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Önceki"
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                                    Text(
                                        text = "Önceki Bölüm",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }

                                FilledTonalButton(
                                    onClick = { nextCh?.let { viewModel.loadChapterInReader(it.id) } },
                                    enabled = nextCh != null,
                                    modifier = Modifier.heightIn(min = AppSizes.minTouchTarget),
                                    contentPadding = PaddingValues(
                                        horizontal = AppSpacing.md,
                                        vertical = AppSpacing.xs
                                    )
                                ) {
                                    Text(
                                        text = "Sonraki Bölüm",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Sonraki"
                                    )
                                }
                            }
                        }
                    }
                }

                // FLOATING AUTO-TRANSLATOR CONTROL BUTTON
                FloatingActionButton(
                    onClick = { viewModel.toggleLiveTranslation(!translationActive) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            bottom = if (showHud) {
                                bottomHudHeightDp + AppSpacing.lg
                            } else {
                                AppSpacing.xl
                            },
                            end = AppSpacing.xl
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                            shape = FloatingActionButtonDefaults.shape
                        )
                        .testTag("translate_toggle_fab"),
                    containerColor = if (translationActive) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                    },
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = AppElevation.low,
                        pressedElevation = AppElevation.medium
                    )
                ) {
                    if (isTranslationLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(AppSizes.iconLarge),
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Çeviri"
                        )
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        val sheetScrollState = rememberScrollState()
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(sheetScrollState)
                    .padding(horizontal = AppSpacing.xl, vertical = AppSpacing.lg)
            ) {
                Text(
                    text = "Okuyucu & Çeviri Tercihleri",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(AppSpacing.lg))
                ReaderSheetSectionHeader("Okuma Yönü:")
                val directions = listOf("Dikey", "Yatay")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    directions.forEachIndexed { index, d ->
                        SegmentedButton(
                            selected = d == readingDirection,
                            onClick = {
                                coroutineScope.launch { viewModel.preferences.setReadingMode(d) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = directions.size
                            ),
                            label = { Text(d) }
                        )
                    }
                }
                ReaderSheetSectionHeader("Çeviri Motoru:")
                val engines = listOf("Otomatik", "Gemini", "DeepL", "ML Kit", "Google")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = AppSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    engines.forEach { eng ->
                        ReaderChoiceChip(
                            label = eng,
                            selected = eng == translationEngine,
                            onClick = { viewModel.setTranslationEngine(eng) },
                            minWidth = 84.dp,
                        )
                    }
                }
                ReaderSheetSectionHeader("Çeviri Dilleri:")
                LanguageSettingRow(
                    label = "Kaynak Dil",
                    value = resolvedSourceLabel,
                    onClick = { showSourceLanguagePicker = true },
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                LanguageSettingRow(
                    label = "Hedef Dil",
                    value = targetLanguageLabel,
                    onClick = { showTargetLanguagePicker = true },
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                ReaderToggleRow(
                    title = "Otomatik Çeviri",
                    subtitle = "Sayfa açıldığında çeviri kendiliğinden başlar",
                    checked = autoTranslateEnabled,
                    onCheckedChange = { checked ->
                        coroutineScope.launch { viewModel.preferences.setAutoTranslate(checked) }
                    },
                )
                ReaderSheetSectionHeader("Balon & Yazı Stili:")
                val styles = listOf("Doğal Balon", "Alt Yazı (Şeffaf)", "Koyu Zemin")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = AppSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    styles.forEach { st ->
                        ReaderChoiceChip(
                            label = st,
                            selected = st == translationBubbleStyle,
                            onClick = {
                                coroutineScope.launch {
                                    viewModel.preferences.setTranslationBubbleStyle(st)
                                }
                            },
                            minWidth = 112.dp,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                var opacitySliderValue by remember(translationOpacity) {
                    mutableFloatStateOf(translationOpacity.toFloat())
                }
                Text(
                    text = "Balon Opaklığı: %${opacitySliderValue.roundToInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = opacitySliderValue,
                    onValueChange = { opacitySliderValue = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            viewModel.preferences.setTranslationOpacity(opacitySliderValue.roundToInt())
                        }
                    },
                    valueRange = 20f..100f,
                    steps = 15,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                ReaderToggleRow(
                    title = "RT-DETR AI Balon Debug Modu",
                    subtitle = "Balon ve OCR kutularını ekranda dikdörtgenlerle görselleştirir",
                    checked = isDebugMode,
                    onCheckedChange = { checked -> viewModel.setDebugMode(checked) },
                    leadingIcon = Icons.Default.BugReport,
                    leadingIconTint = if (isDebugMode) {
                        Color(0xFF00E676)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.height(AppSpacing.xl))
            }
        }
    }

    if (showSourceLanguagePicker) {
        val options = buildList {
            add(LanguagePickerOption(code = TranslationLanguages.AUTO, title = "Otomatik", nativeName = "Manga bilgisinden algıla"))
            TranslationLanguages.sources.forEach { lang ->
                add(LanguagePickerOption(code = lang.code, title = lang.turkishName, nativeName = lang.nativeName))
            }
        }
        LanguagePickerDialog(
            title = "Kaynak Dil",
            options = options,
            selectedCode = translationSourceLanguage,
            recentCodes = recentTranslationLanguages,
            onSelect = {
                viewModel.setTranslationSourceLanguage(it)
                showSourceLanguagePicker = false
            },
            onDismiss = { showSourceLanguagePicker = false },
        )
    }

    if (showTargetLanguagePicker) {
        val options = TranslationLanguages.targets.map { lang ->
            LanguagePickerOption(code = lang.code, title = lang.turkishName, nativeName = lang.nativeName)
        }
        LanguagePickerDialog(
            title = "Hedef Dil",
            options = options,
            selectedCode = translationTargetLanguage,
            recentCodes = recentTranslationLanguages,
            onSelect = {
                viewModel.setTranslationTargetLanguage(it)
                showTargetLanguagePicker = false
            },
            onDismiss = { showTargetLanguagePicker = false },
        )
    }

    if (showCorrectionDialog) {
        var newText by remember(correctionOriginal, correctionTranslated) {
            mutableStateOf(correctionTranslated)
        }
        AlertDialog(
            onDismissRequest = {
                if (!correctionSaving) showCorrectionDialog = false
            },
            title = {
                Text(
                    text = "Çeviriyi Düzelt",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = "Orijinal Metin:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = correctionOriginal,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    Text(
                        text = "Çeviri:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    OutlinedTextField(
                        value = newText,
                        onValueChange = {
                            newText = it
                            correctionSaveError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    correctionSaveError?.let { error ->
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (!correctionSaving) {
                        correctionSaving = true
                        correctionSaveError = null
                        coroutineScope.launch {
                            try {
                                val result = viewModel.submitCorrection(correctionOriginal, newText)
                                result.fold(
                                    onSuccess = {
                                        showCorrectionDialog = false
                                        correctionSaveError = null
                                    },
                                    onFailure = { error ->
                                        correctionSaveError = error.localizedMessage
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { "Düzeltme kaydedilemedi: $it" }
                                            ?: "Düzeltme kaydedilemedi. Tekrar deneyin."
                                    }
                                )
                            } finally {
                                correctionSaving = false
                            }
                        }
                    }
                }, enabled = newText.isNotBlank() && !correctionSaving) {
                    if (correctionSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Kaydet")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCorrectionDialog = false }, enabled = !correctionSaving) {
                    Text("İptal")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderPageContainer(
    chapterId: String,
    pageIndex: Int,
    viewModel: MangaViewModel,
    translationActive: Boolean,
    translatedBlocks: List<TextBlockObj>,
    bubbleStyle: String,
    opacity: Int,
    targetLanguage: String = "",
    isDebugMode: Boolean = false,
    onCorrectionClick: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    val uiLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val textLayoutLocale = remember(uiLocale, targetLanguage) {
        TextLayoutLocale.resolveLocale(targetLanguage, uiLocale)
    }
    val pageQuality by viewModel.pageQuality.collectAsState()
    val rawBitmapState = remember(chapterId, pageIndex, pageQuality) {
        mutableStateOf(viewModel.peekPageBitmap(chapterId, pageIndex)?.takeUnless { it.isRecycled })
    }
    var rawBitmap by rawBitmapState
    DisposableEffect(rawBitmapState) {
        onDispose {
            // getPageBitmap returns a borrowed bitmap owned by the ViewModel cache.
            // Drop this screen's reference, but never recycle shared cache data here.
            rawBitmapState.value = null
        }
    }
    var bitmapLoadFailed by remember(chapterId, pageIndex) { mutableStateOf(false) }
    var bitmapLoadAttempt by remember(chapterId, pageIndex) { mutableIntStateOf(0) }
    val rawBitmapIsRecycled = rawBitmap?.isRecycled == true
    val maskedPagesMap by viewModel.maskedPagesMap.collectAsState()
    val readerPageRetryTick by viewModel.readerPageRetryTick.collectAsState()

    LaunchedEffect(chapterId, pageIndex, pageQuality, rawBitmapIsRecycled, bitmapLoadAttempt, readerPageRetryTick) {
        if (rawBitmap == null || rawBitmapIsRecycled) {
            rawBitmap = null
            bitmapLoadFailed = false
            try {
                val loaded = viewModel.getPageBitmap(chapterId, pageIndex)
                    ?.takeUnless { it.isRecycled }
                rawBitmap = loaded
                bitmapLoadFailed = loaded == null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                rawBitmap = null
                bitmapLoadFailed = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (rawBitmap != null && !rawBitmapIsRecycled) {
            val bmp = rawBitmap!!
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                val containerWidth = constraints.maxWidth
                val density = LocalDensity.current

                val maskedBmp = maskedPagesMap[pageIndex]
                val validMasked = if (translationActive && maskedBmp != null && !maskedBmp.isRecycled) {
                    maskedBmp
                } else null
                val displayBitmap = when {
                    validMasked != null -> validMasked
                    !bmp.isRecycled -> bmp
                    else -> null
                }

                val comicTypeface = remember(ctx) {
                    try {
                        androidx.core.content.res.ResourcesCompat.getFont(
                            ctx, com.example.R.font.comic_neue
                        ) ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    } catch (_: Exception) {
                        Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                }
                val condensedTypeface = remember {
                    try {
                        Typeface.create("sans-serif-condensed", Typeface.BOLD)
                    } catch (_: Exception) {
                        Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    }
                }

                // OCR rectangles belong to the bitmap used during translation. Keep that
                // coordinate space separate from whichever bitmap is currently displayed.
                val coordinateBlock = translatedBlocks.firstOrNull {
                    it.coordinateImageWidth > 0 && it.coordinateImageHeight > 0
                }
                val pageOriginalWidth = (
                    coordinateBlock?.coordinateImageWidth ?: validMasked?.width ?: bmp.width
                ).toFloat().coerceAtLeast(1f)
                val pageOriginalHeight = (
                    coordinateBlock?.coordinateImageHeight ?: validMasked?.height ?: bmp.height
                ).toFloat().coerceAtLeast(1f)
                val renderedBitmap = displayBitmap ?: bmp
                val bitmapScaleX = renderedBitmap.width.toFloat() / pageOriginalWidth
                val bitmapScaleY = renderedBitmap.height.toFloat() / pageOriginalHeight
                val widthScale = containerWidth.coerceAtLeast(1).toFloat() /
                    renderedBitmap.width.coerceAtLeast(1)
                val coordinateScaleX = bitmapScaleX * widthScale
                val coordinateScaleY = bitmapScaleY * widthScale

                if (displayBitmap != null && !displayBitmap.isRecycled) {
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = "Sayfa ${pageIndex + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                var debugPlacedOverlays by remember(pageIndex, translatedBlocks) {
                    mutableStateOf(emptyList<PlacedOverlayBox>())
                }
                if (translationActive && translatedBlocks.isNotEmpty()) {
                    val userAlpha = (opacity / 100f).coerceIn(0.20f, 1.0f)
                    val placedOverlays by produceState(
                        initialValue = emptyList<PlacedOverlayBox>(),
                        translatedBlocks,
                        widthScale,
                        coordinateScaleX,
                        coordinateScaleY,
                        density,
                        containerWidth,
                        bubbleStyle,
                        pageOriginalWidth,
                        pageOriginalHeight,
                        comicTypeface,
                        condensedTypeface,
                        renderedBitmap,
                        validMasked,
                        targetLanguage,
                        textLayoutLocale
                    ) {
                        value = withContext(Dispatchers.Default) {
                        val screenWidthDp = with(density) { containerWidth.toFloat().toDp() }
                        // Çeviri hedef kutuları: birbirine değen/çakışan kutular
                        // metin yerleşiminden önce ayrıştırılır ki iki çeviri
                        // birbirinin üzerine çökmesin.
                        val pageBounds = Rect(
                            0,
                            0,
                            pageOriginalWidth.toInt().coerceAtLeast(1),
                            pageOriginalHeight.toInt().coerceAtLeast(1)
                        )
                        val preparedBoxes = translatedBlocks.map { block ->
                            val bubbleW = if (!block.bubbleBox.isEmpty) block.bubbleBox.width() else 0
                            val safeBubble = BubbleLayoutGeometry.reliableBubbleBounds(block.bubbleBox, block.boundingBox, pageBounds)
                            val availableBubbleWidth = when {
                                safeBubble != null && safeBubble.width() > 0 -> (safeBubble.width() * 0.92f).toInt()
                                bubbleW > 0 -> (bubbleW * 0.92f).toInt()
                                else -> 0
                            }
                            val baseBox = if (block.isBubble) {
                                // Never clip or recenter a fallback layout against the
                                // bubble here: it can be the OCR anchor itself and would
                                // be silently shrunk. Only page bounds may clip it.
                                BubbleLayoutGeometry.prepareBubbleTextLayoutBox(
                                    layout = Rect(block.layoutBox),
                                    bubble = Rect(block.bubbleBox),
                                    anchor = Rect(block.boundingBox),
                                    pageBounds = pageBounds
                                )
                            } else {
                                if (!block.bubbleBox.isEmpty && block.bubbleBox.width() > block.boundingBox.width()) {
                                    Rect(block.bubbleBox)
                                } else {
                                    Rect(block.boundingBox)
                                }
                            }
                            val currentAspect = if (baseBox.height() > 0) baseBox.width().toFloat() / baseBox.height().toFloat() else 1f
                            if (baseBox.height() > 0 && (currentAspect < 0.85f || (availableBubbleWidth > baseBox.width() * 1.15f))) {
                                val rawTargetW = when {
                                    availableBubbleWidth > 0 -> maxOf(baseBox.width(), availableBubbleWidth)
                                    else -> maxOf(baseBox.width(), (baseBox.height() * 0.70f).toInt())
                                }
                                val pageW = pageOriginalWidth.toInt().coerceAtLeast(1)
                                val maxWidthCap = if (availableBubbleWidth > 0) {
                                    availableBubbleWidth
                                } else {
                                    minOf(maxOf((baseBox.width() * 3.5f).toInt(), 260), (pageW * 0.50f).toInt())
                                }
                                val targetW = minOf(rawTargetW, maxWidthCap).coerceAtLeast(baseBox.width())
                                val bubbleL = if (!block.bubbleBox.isEmpty) block.bubbleBox.left else 0
                                val bubbleR = if (!block.bubbleBox.isEmpty) block.bubbleBox.right else pageW
                                val bW = (bubbleR - bubbleL).coerceAtLeast(baseBox.width())
                                val newLeft = if (targetW <= bW) {
                                    (baseBox.centerX() - targetW / 2).coerceIn(bubbleL, (bubbleR - targetW).coerceAtLeast(bubbleL))
                                } else {
                                    val centered = (bubbleL + bubbleR - targetW) / 2
                                    centered.coerceIn(0, (pageW - targetW).coerceAtLeast(0))
                                }
                                Rect(newLeft, baseBox.top, newLeft + targetW, baseBox.bottom)
                            } else {
                                baseBox
                            }
                        }
                        val isBubbleList = translatedBlocks.map { it.isBubble }
                        val anchorList = translatedBlocks.map { Rect(it.boundingBox) }
                        val boldBoostFlags = separateNearbyBoxes(
                            preparedBoxes,
                            isBubbleList,
                            anchorList
                        )
                        // Also separate raw bubbleBounds so touching/overlapping speech bubbles
                        // never lay out text or opaque fills on top of a neighbor bubble.
                        val separatedBubbleBounds = translatedBlocks.map { block ->
                            val raw = BubbleLayoutGeometry.reliableBubbleBounds(
                                bubble = block.bubbleBox,
                                anchor = block.boundingBox,
                                pageBounds = pageBounds,
                                requireDistinctBubble = false
                            ) ?: if (!block.bubbleBox.isEmpty) Rect(block.bubbleBox) else Rect(block.boundingBox)
                            Rect(raw)
                        }
                        separateNearbyBoxes(
                            separatedBubbleBounds,
                            isBubbleList,
                            anchorList
                        )
                        val contourCache = HashMap<Int, ContourScanlineProfile?>()
                        fun layoutBlock(
                            blockIndex: Int,
                            block: TextBlockObj,
                            harmonizedMaxSpCap: Float? = null
                        ): PlacedOverlayBox? {
                            val targetBox = preparedBoxes[blockIndex]
                            val boldBoost = boldBoostFlags[blockIndex]
                            val cleanText = block.translatedText.trim()
                            val isSub = bubbleStyle == "Alt Yazı (Şeffaf)" || !block.isBubble
                            val rawBubbleBounds = if (isSub) null else {
                                BubbleLayoutGeometry.reliableBubbleBounds(
                                    bubble = block.bubbleBox,
                                    anchor = block.boundingBox,
                                    pageBounds = pageBounds,
                                    requireDistinctBubble = false
                                ) ?: if (!block.bubbleBox.isEmpty) Rect(block.bubbleBox) else null
                            }
                            val sepBounds = separatedBubbleBounds[blockIndex]
                            val bubbleBounds = if (isSub || rawBubbleBounds == null) null else {
                                if (!sepBounds.isEmpty) Rect(sepBounds) else rawBubbleBounds
                            }
                            val wasBubbleTrimmed = rawBubbleBounds != null && bubbleBounds != null &&
                                (bubbleBounds.left > rawBubbleBounds.left + 2 ||
                                    bubbleBounds.right < rawBubbleBounds.right - 2 ||
                                    bubbleBounds.top > rawBubbleBounds.top + 2 ||
                                    bubbleBounds.bottom < rawBubbleBounds.bottom - 2)
                            val contourProfile = if (isSub || wasBubbleTrimmed) {
                                null
                            } else {
                                (block.contourProfile?.takeIf { it.isUsable }) ?: if (validMasked != null && bubbleBounds != null) {
                                    contourCache.getOrPut(blockIndex) {
                                        BubbleContourExtractor.extractFromBitmap(
                                            bitmap = validMasked,
                                            bubbleBox = bubbleBounds,
                                            anchorBox = block.boundingBox,
                                            pageOriginalWidth = pageOriginalWidth,
                                            pageOriginalHeight = pageOriginalHeight,
                                            isDarkBubble = block.isDarkBubble
                                        )
                                    }?.takeIf { it.isUsable }
                                } else {
                                    null
                                }
                            }
                            val isRectangularBox = resolveIsRectangularBox(
                                isBubble = block.isBubble,
                                bubbleBox = block.bubbleBox,
                                targetBox = targetBox,
                                contourProfile = contourProfile
                            )

                            fun geometryFor(box: Rect): BoxGeometry {
                                val rawLeftDp = with(density) { (box.left * coordinateScaleX).toDp() }
                                val rawTopDp = with(density) { (box.top * coordinateScaleY).toDp() }
                                val rawWidthDp = with(density) { (box.width() * coordinateScaleX).toDp() }
                                val rawHeightDp = with(density) { (box.height() * coordinateScaleY).toDp() }
                                return if (isSub) {
                                    // Subtitle text uses a page-wide line area; source glyph width
                                    // should not force a long translation into a narrow box.
                                    val horizontalMargin = 16.dp
                                    val subtitleWidth = (screenWidthDp - horizontalMargin * 2)
                                        .coerceAtLeast(1.dp)
                                        .coerceAtMost(screenWidthDp)
                                    val subtitleLeft = ((screenWidthDp - subtitleWidth) / 2)
                                        .coerceAtLeast(0.dp)
                                    BoxGeometry(
                                        subtitleLeft,
                                        rawTopDp,
                                        subtitleWidth,
                                        maxOf(24.dp, rawHeightDp),
                                        6.dp,
                                        4.dp
                                    )
                                } else {
                                    // Keep the overlay on the detected source bounds. Moving or shrinking
                                    // it at the screen edge can expose the original glyphs underneath.
                                    val bubbleW = rawWidthDp.coerceAtLeast(1.dp)
                                    val bubbleH = rawHeightDp.coerceAtLeast(1.dp)
                                    val (pHVal, pVVal) = computeBubblePaddingDp(
                                        widthDp = bubbleW.value,
                                        heightDp = bubbleH.value,
                                        isRectangularBox = isRectangularBox
                                    )
                                    BoxGeometry(rawLeftDp, rawTopDp, bubbleW, bubbleH, pHVal.dp, pVVal.dp)
                                }
                            }

                            fun expansionBubbleBounds(): Rect? {
                                if (isSub) return null
                                val candidateBubble = if (!sepBounds.isEmpty) sepBounds else block.bubbleBox
                                return BubbleLayoutGeometry.reliableBubbleBounds(
                                    bubble = candidateBubble,
                                    anchor = block.boundingBox,
                                    pageBounds = pageBounds
                                )
                            }

                            fun p1BubbleBounds(): Rect? {
                                if (isSub) return null
                                val candidateBubble = if (!sepBounds.isEmpty) sepBounds else block.bubbleBox
                                return BubbleLayoutGeometry.reliableBubbleBounds(
                                    bubble = candidateBubble,
                                    anchor = block.boundingBox,
                                    pageBounds = pageBounds,
                                    requireDistinctBubble = true
                                )
                            }

                            fun maxSafeBubbleBounds(): Rect {
                                val bubble = expansionBubbleBounds() ?: return Rect(targetBox)
                                return if (isRectangularBox) {
                                    BubbleLayoutGeometry.safeRectangle(bubble) ?: Rect(targetBox)
                                } else {
                                    bubbleTextSafeRect(bubble) ?: Rect(targetBox)
                                }
                            }

                            fun optimize(
                                geo: BoxGeometry,
                                minSp: Float = 8f,
                                allowBrokenWords: Boolean = false,
                                textOverride: String? = null,
                                textScaleX: Float = 1.0f,
                                typefaceOverride: Typeface? = null
                            ): StaticTextLayoutOptimizer.OptimizedLayoutResult {
                                val measuredText = textOverride ?: cleanText
                                val textBounds = RectF(
                                    0f,
                                    0f,
                                    (geo.widthDp - geo.padH * 2).value.coerceAtLeast(1f),
                                    (geo.heightDp - geo.padV * 2).value.coerceAtLeast(1f)
                                )
                                val rawMaxSp = (geo.heightDp.value * 0.55f).coerceIn(minSp, 32f)
                                val effectiveMaxSp = harmonizedMaxSpCap?.let { minOf(rawMaxSp, it).coerceAtLeast(minSp) } ?: rawMaxSp
                                return StaticTextLayoutOptimizer.optimizeLayout(
                                    text = measuredText,
                                    bounds = textBounds,
                                    minSp = minSp,
                                    maxSp = effectiveMaxSp,
                                    density = density.density,
                                    spToPx = { fontSizeSp ->
                                        with(density) { fontSizeSp.sp.toPx() }
                                    },
                                    layoutLocale = textLayoutLocale,
                                    textDirection = TextLayoutLocale.directionFor(
                                        targetLanguage,
                                        measuredText
                                    ),
                                    isOvalBubble = !isSub && !isRectangularBox,
                                    measureTypeface = typefaceOverride ?: if (isSub) condensedTypeface else comicTypeface,
                                    allowBrokenWords = allowBrokenWords,
                                    textScaleX = textScaleX
                                )
                            }

                            fun optimizeBubbleEnvelope(
                                bubbleGeo: BoxGeometry,
                                minSp: Float = 8f,
                                allowBrokenWords: Boolean = false,
                                textScaleX: Float = 1.0f,
                                contourProfile: ContourScanlineProfile? = null,
                                typefaceOverride: Typeface? = null,
                                isRectangular: Boolean = false
                            ): StaticTextLayoutOptimizer.OptimizedLayoutResult? {
                                val hasContour = contourProfile?.isUsable == true && !isRectangular
                                // Avoid triple-insetting (padH + EDT margin + safeInset) on contour profiles,
                                // which previously crushed narrow vertical bubbles into tiny 1-word columns.
                                val effPadH = if (hasContour) minOf(bubbleGeo.padH.value, 2.5f) else bubbleGeo.padH.value
                                val effPadV = if (hasContour) minOf(bubbleGeo.padV.value, 2.5f) else bubbleGeo.padV.value
                                val rawMaxSp = (bubbleGeo.heightDp.value * 0.55f).coerceIn(minSp, 32f)
                                val effectiveMaxSp = harmonizedMaxSpCap?.let { minOf(rawMaxSp, it).coerceAtLeast(minSp) } ?: rawMaxSp
                                return BubbleTextLayoutEngine.optimizeLayout(
                                    text = cleanText,
                                    bubbleBounds = RectF(
                                        0f,
                                        0f,
                                        bubbleGeo.widthDp.value.coerceAtLeast(1f),
                                        bubbleGeo.heightDp.value.coerceAtLeast(1f)
                                    ),
                                    horizontalPaddingDp = effPadH,
                                    verticalPaddingDp = effPadV,
                                    minSp = minSp,
                                    maxSp = effectiveMaxSp,
                                    density = density.density,
                                    spToPx = { fontSizeSp ->
                                        with(density) { fontSizeSp.sp.toPx() }
                                    },
                                    layoutLocale = textLayoutLocale,
                                    targetLanguage = targetLanguage,
                                    measureTypeface = typefaceOverride ?: comicTypeface,
                                    allowBrokenWords = allowBrokenWords,
                                    textScaleX = textScaleX,
                                    contourProfile = contourProfile,
                                    isRectangular = isRectangular,
                                    safeInsetRatio = if (hasContour) 0.015f else BubbleTextLayoutEngine.READER_SAFE_INSET_RATIO,
                                    minSafeInsetPx = if (hasContour) 1.0f else BubbleTextLayoutEngine.READER_MIN_SAFE_INSET_PX,
                                    maxSafeInsetPx = if (hasContour) 4.0f else BubbleTextLayoutEngine.READER_MAX_SAFE_INSET_PX
                                )
                            }

                            // Android Türkçe heceleme sözlüğü içermediği için yalnızca
                            // Türkçe metinlerde fonetik hece sınırlarına yumuşak tire (\u00AD)
                            // ekler; diğer diller Android'in yerleşik tirelemesini kullanır.
                            fun hyphenateForFit(source: String): String =
                                if (textLayoutLocale.language.equals("tr", ignoreCase = true)) {
                                    TurkishSyllabifier.insertSoftHyphens(source)
                                } else {
                                    source
                                }

                            var geo = geometryFor(targetBox)
                            var opt: StaticTextLayoutOptimizer.OptimizedLayoutResult
                            if (isSub) {
                                val pageHeightDp = with(density) {
                                    (renderedBitmap.height * widthScale).toDp()
                                }
                                // Measure subtitle text with the available page height first. If we
                                // measure only in the source glyph's one-line box, the optimizer can
                                // settle on 4sp (which technically fits) and never request expansion.
                                val probeGeo = geo.copy(
                                    topDp = 0.dp,
                                    heightDp = pageHeightDp.coerceAtLeast(1.dp)
                                )
                                val probe = optimize(probeGeo)
                                opt = probe
                                val probeFontPx = with(density) { probe.fontSizeSp.sp.toPx() }
                                val safePaddingPx = max(1.5f, probeFontPx * 0.10f)
                                val requiredHeightDp = (
                                    probe.totalHeightPx + safePaddingPx * 2f
                                ) / density.density + geo.padV.value * 2f
                                // Clamp/normalize even when no expansion is needed, so a
                                // stale subtitle box near a page edge cannot draw off-page.
                                val expanded = OverlayLayoutPolicy.expandSubtitleToRequiredHeight(
                                    top = geo.topDp.value,
                                    height = geo.heightDp.value,
                                    requiredHeight = requiredHeightDp,
                                    pageHeight = pageHeightDp.value
                                )
                                if (expanded.top != geo.topDp.value || expanded.height != geo.heightDp.value) {
                                    geo = geo.copy(
                                        topDp = expanded.top.dp,
                                        heightDp = expanded.height.dp
                                    )
                                    opt = optimize(geo)
                                }
                                // If even the expanded subtitle area is too small, try the
                                // hyphenated path before accepting a plain 6sp fit. A larger
                                // broken-word fit is preferable; the plain minimum is only
                                // used when hyphenation cannot produce a readable result.
                                if (OverlayLayoutPolicy.shouldShrinkSubtitleToMinimum(
                                        opt.fitsInside,
                                        opt.fontSizeSp
                                    )
                                ) {
                                    val hyphenatedFit = optimize(
                                        geo,
                                        minSp = OverlayLayoutPolicy.READABLE_MIN_SP,
                                        allowBrokenWords = true,
                                        textOverride = hyphenateForFit(cleanText)
                                    )
                                    val minimumFit = optimize(
                                        geo,
                                        minSp = OverlayLayoutPolicy.READABLE_MIN_SP
                                    )
                                    opt = when (OverlayLayoutPolicy.chooseSubtitleFit(
                                        original = OverlayLayoutPolicy.SubtitleFit(
                                            opt.fitsInside,
                                            opt.fontSizeSp
                                        ),
                                        hyphenated = OverlayLayoutPolicy.SubtitleFit(
                                            hyphenatedFit.fitsInside,
                                            hyphenatedFit.fontSizeSp
                                        ),
                                        minimum = OverlayLayoutPolicy.SubtitleFit(
                                            minimumFit.fitsInside,
                                            minimumFit.fontSizeSp
                                        )
                                    )) {
                                        OverlayLayoutPolicy.SubtitleFitChoice.ORIGINAL -> opt
                                        OverlayLayoutPolicy.SubtitleFitChoice.HYPHENATED -> hyphenatedFit
                                        OverlayLayoutPolicy.SubtitleFitChoice.MINIMUM -> minimumFit
                                        OverlayLayoutPolicy.SubtitleFitChoice.FALLBACK -> minimumFit
                                    }
                                }
                            } else {
                                val bubbleGeo = bubbleBounds?.let { geometryFor(it) }
                                val envelopeFit = bubbleGeo?.let { candidateGeo ->
                                    fun tryEnvelope(
                                        profile: ContourScanlineProfile?,
                                        safeInsetRatio: Float = 0f,
                                        minFloorSp: Float = 8.0f
                                    ): StaticTextLayoutOptimizer.OptimizedLayoutResult? {
                                        val effectiveGeo = if (safeInsetRatio > 0f) {
                                            val extraPadH = candidateGeo.widthDp.value * safeInsetRatio
                                            val extraPadV = candidateGeo.heightDp.value * safeInsetRatio
                                            candidateGeo.copy(
                                                padH = (candidateGeo.padH.value + extraPadH).dp,
                                                padV = (candidateGeo.padV.value + extraPadV).dp
                                            )
                                        } else {
                                            candidateGeo
                                        }

                                        if (minFloorSp >= 8.0f) {
                                            // 1. Unified crisp manga lettering: standard scale, ZERO hyphens, down to 8.0sp floor.
                                            optimizeBubbleEnvelope(
                                                effectiveGeo,
                                                minSp = 8.0f,
                                                allowBrokenWords = false,
                                                contourProfile = profile,
                                                isRectangular = isRectangularBox
                                            )?.takeIf { it.fitsInside && it.fontSizeSp >= 8.0f }?.let { return it }

                                            // 2. Slight horizontal condensation (0.90x), still ZERO hyphens, down to 8.0sp.
                                            optimizeBubbleEnvelope(
                                                effectiveGeo,
                                                minSp = 8.0f,
                                                allowBrokenWords = false,
                                                textScaleX = 0.90f,
                                                contourProfile = profile,
                                                isRectangular = isRectangularBox
                                            )?.takeIf { it.fitsInside && it.fontSizeSp >= 8.0f }?.let { return it }

                                            // 3. Tighter condensation (0.85x), still ZERO hyphens, ensuring intact words.
                                            optimizeBubbleEnvelope(
                                                effectiveGeo,
                                                minSp = 8.0f,
                                                allowBrokenWords = false,
                                                textScaleX = 0.85f,
                                                contourProfile = profile,
                                                isRectangular = isRectangularBox
                                            )?.takeIf { it.fitsInside && it.fontSizeSp >= 8.0f }?.let { return it }

                                            // 3b. Whole words down to 7.0sp before hyphenating so normal dialogue stays clean.
                                            optimizeBubbleEnvelope(
                                                effectiveGeo,
                                                minSp = 7.0f,
                                                allowBrokenWords = false,
                                                textScaleX = 0.85f,
                                                contourProfile = profile,
                                                isRectangular = isRectangularBox
                                            )?.takeIf { it.fitsInside && it.fontSizeSp >= 7.0f }?.let { return it }

                                            // 4. Only when whole words cannot fit cleanly: allow Turkish syllable hyphenation (0.90x).
                                            optimizeBubbleEnvelope(
                                                effectiveGeo,
                                                minSp = 8.0f,
                                                allowBrokenWords = true,
                                                textScaleX = 0.90f,
                                                contourProfile = profile,
                                                isRectangular = isRectangularBox
                                            )?.takeIf { it.fitsInside && it.fontSizeSp >= 8.0f }?.let { return it }

                                            // 5. Dense extreme bubble: tighter scaleX (0.84x) with hyphenation, locked to readable 8.0sp floor.
                                            return optimizeBubbleEnvelope(
                                                effectiveGeo,
                                                minSp = 8.0f,
                                                allowBrokenWords = true,
                                                textScaleX = 0.84f,
                                                contourProfile = profile,
                                                isRectangular = isRectangularBox
                                            )?.takeIf { it.fitsInside && it.fontSizeSp >= 8.0f }
                                        }

                                        // Contour-preserving smaller floor (e.g. 6.0sp) before leaving contourProfile.
                                        optimizeBubbleEnvelope(
                                            effectiveGeo,
                                            minSp = minFloorSp,
                                            allowBrokenWords = false,
                                            textScaleX = 0.85f,
                                            contourProfile = profile,
                                            isRectangular = isRectangularBox
                                        )?.takeIf { it.fitsInside && it.fontSizeSp >= minFloorSp }?.let { return it }

                                        optimizeBubbleEnvelope(
                                            effectiveGeo,
                                            minSp = minFloorSp,
                                            allowBrokenWords = true,
                                            textScaleX = 0.85f,
                                            contourProfile = profile,
                                            isRectangular = isRectangularBox
                                        )?.takeIf { it.fitsInside && it.fontSizeSp >= minFloorSp }?.let { return it }

                                        optimizeBubbleEnvelope(
                                            effectiveGeo,
                                            minSp = minFloorSp,
                                            allowBrokenWords = true,
                                            textScaleX = 0.80f,
                                            contourProfile = profile,
                                            isRectangular = isRectangularBox
                                        )?.takeIf { it.fitsInside && it.fontSizeSp >= minFloorSp }?.let { return it }

                                        return optimizeBubbleEnvelope(
                                            effectiveGeo,
                                            minSp = minFloorSp,
                                            allowBrokenWords = true,
                                            textScaleX = 0.80f,
                                            contourProfile = profile,
                                            typefaceOverride = condensedTypeface,
                                            isRectangular = isRectangularBox
                                        )?.takeIf { it.fitsInside && it.fontSizeSp >= minFloorSp }
                                    }

                                    if (contourProfile != null && !isRectangularBox) {
                                        // 1. Try contour envelope at readable 8.0sp floor.
                                        // 2. Stay inside contourProfile down to 6.0sp before trying ellipse fallback.
                                        // 3. Only if contour 6.0sp is impossible, fall back to conservative 15% inset ellipse.
                                        tryEnvelope(contourProfile, minFloorSp = 8.0f)
                                            ?: tryEnvelope(contourProfile, minFloorSp = 6.0f)
                                            ?: tryEnvelope(null, safeInsetRatio = 0.15f, minFloorSp = 8.0f)
                                            ?: tryEnvelope(null, safeInsetRatio = 0.15f, minFloorSp = 6.0f)
                                    } else {
                                        tryEnvelope(contourProfile, minFloorSp = 8.0f)
                                            ?: tryEnvelope(contourProfile, minFloorSp = 6.0f)
                                    }
                                }
                                if (bubbleGeo != null && envelopeFit != null && envelopeFit.fitsInside) {
                                    geo = bubbleGeo
                                    opt = envelopeFit
                                } else if (bubbleGeo != null) {
                                    val fallbackEnvelope = if (contourProfile != null && !isRectangularBox) {
                                        optimizeBubbleEnvelope(
                                            bubbleGeo,
                                            minSp = 4.5f,
                                            allowBrokenWords = true,
                                            textScaleX = 0.78f,
                                            contourProfile = contourProfile,
                                            typefaceOverride = condensedTypeface,
                                            isRectangular = false
                                        )?.takeIf { it.fitsInside }
                                    } else {
                                        optimizeBubbleEnvelope(
                                            bubbleGeo,
                                            minSp = 6.0f,
                                            allowBrokenWords = true,
                                            textScaleX = 0.82f,
                                            contourProfile = contourProfile,
                                            isRectangular = isRectangularBox
                                        )?.takeIf { it.fitsInside }
                                    }
                                    geo = bubbleGeo
                                    opt = fallbackEnvelope ?: optimize(
                                        bubbleGeo,
                                        minSp = 6.0f,
                                        allowBrokenWords = true,
                                        textOverride = hyphenateForFit(cleanText),
                                        textScaleX = 0.82f
                                    )
                                } else {
                                    opt = optimize(
                                        geo,
                                        minSp = 8.0f,
                                        allowBrokenWords = true,
                                        textOverride = hyphenateForFit(cleanText),
                                        textScaleX = 0.84f
                                    )
                                }
                            }
                            if (!opt.fitsInside) {
                                if (!isSub) {
                                    val safeBubble = maxSafeBubbleBounds()
                                    if (!safeBubble.isEmpty) {
                                        val anchorX = block.boundingBox.exactCenterX()
                                            .coerceIn(safeBubble.left.toFloat(), safeBubble.right.toFloat())
                                        val anchorY = block.boundingBox.exactCenterY()
                                            .coerceIn(safeBubble.top.toFloat(), safeBubble.bottom.toFloat())
                                        var expanded = Rect(targetBox).apply { union(block.boundingBox) }
                                        var attempts = 0
                                        while (!opt.fitsInside && attempts < 5) {
                                            val requiredWidthPx = ceil(
                                                (opt.requiredWidthDp + geo.padH.value * 2f) * density.density /
                                                    coordinateScaleX.coerceAtLeast(0.0001f)
                                            ).toInt()
                                            val fontPx = with(density) { opt.fontSizeSp.sp.toPx() }
                                            val safeTextPaddingPx = max(1.5f, fontPx * 0.10f)
                                            val requiredHeightPx = ceil(
                                                (
                                                    opt.totalHeightPx + safeTextPaddingPx * 2f +
                                                        geo.padV.value * density.density * 2f
                                                    ) / coordinateScaleY.coerceAtLeast(0.0001f)
                                            ).toInt()

                                            // Exact width can underestimate emergency-wrapped tokens;
                                            // bounded geometric growth gives the next measurement more
                                            // room without immediately filling the whole speech bubble.
                                            val maxAllowedWidth = if (p1BubbleBounds() != null) {
                                                safeBubble.width()
                                            } else {
                                                maxOf(
                                                    safeBubble.width(),
                                                    (pageOriginalWidth * 0.35f).toInt().coerceAtLeast((48f * density.density).toInt())
                                                )
                                            }
                                            val nextWidth = maxOf(
                                                expanded.width(),
                                                requiredWidthPx,
                                                ceil(expanded.width() * 1.30f).toInt()
                                            ).coerceAtMost(maxAllowedWidth)
                                            val nextHeight = maxOf(
                                                expanded.height(),
                                                requiredHeightPx,
                                                ceil(expanded.height() * 1.30f).toInt()
                                            ).coerceAtMost(maxOf(safeBubble.height(), (50f * density.density).toInt()))
                                            val safeW = safeBubble.width()
                                            val left = if (nextWidth <= safeW) {
                                                (anchorX - nextWidth / 2f).roundToInt().coerceIn(safeBubble.left, (safeBubble.right - nextWidth).coerceAtLeast(safeBubble.left))
                                            } else {
                                                val centered = (safeBubble.left + safeBubble.right - nextWidth) / 2
                                                centered.coerceIn(0, (pageOriginalWidth.toInt() - nextWidth).coerceAtLeast(0))
                                            }
                                            val safeH = safeBubble.height()
                                            val top = if (nextHeight <= safeH) {
                                                (anchorY - nextHeight / 2f).roundToInt().coerceIn(safeBubble.top, (safeBubble.bottom - nextHeight).coerceAtLeast(safeBubble.top))
                                            } else {
                                                val centered = (safeBubble.top + safeBubble.bottom - nextHeight) / 2
                                                centered.coerceIn(0, (pageOriginalHeight.toInt() - nextHeight).coerceAtLeast(0))
                                            }
                                            val next = Rect(left, top, left + nextWidth, top + nextHeight)
                                            if (next == expanded) break
                                            expanded = next
                                            geo = geometryFor(expanded)
                                            opt = optimize(geo)
                                            attempts++
                                        }

                                        // If the OCR-anchored expansion still cannot fit the
                                        // translation, try the complete safe balloon area. The
                                        // OCR box is an anchor, not a hard size limit for translated text.
                                        if (!opt.fitsInside) {
                                            geo = geometryFor(Rect(safeBubble))
                                            opt = optimize(geo)
                                        }

                                        // Long translations can need a smaller font even when the
                                        // whole safe balloon is available. Keep this as a last
                                        // readable-size attempt before showing the source fallback.
                                        if (!opt.fitsInside) {
                                            opt = optimize(geo, minSp = 6f)
                                        }
                                    }
                                }
                                // If even the readable minimum cannot fit without splitting
                                // words, allow hyphenated/broken words so the translation can
                                // grow instead of collapsing to an unreadable size. Subtitles
                                // already tried this ordering above; normal bubbles keep the
                                // existing fallback chain.
                                if (!opt.fitsInside && !isSub) {
                                    val hyphenatedText = hyphenateForFit(cleanText)
                                    val brokenFit = optimize(
                                        geo,
                                        minSp = OverlayLayoutPolicy.READABLE_MIN_SP,
                                        allowBrokenWords = true,
                                        textOverride = hyphenatedText
                                    )
                                    if (brokenFit.fitsInside && brokenFit.fontSizeSp >= OverlayLayoutPolicy.READABLE_MIN_SP) {
                                        opt = brokenFit
                                    } else {
                                        val condensedAttempts = listOf(
                                            0.92f to (if (isSub) condensedTypeface else comicTypeface),
                                            0.85f to (if (isSub) condensedTypeface else comicTypeface),
                                            0.85f to condensedTypeface
                                        )
                                        for ((scaleX, face) in condensedAttempts) {
                                            val condensedFit = optimize(
                                                geo,
                                                minSp = OverlayLayoutPolicy.READABLE_MIN_SP,
                                                allowBrokenWords = true,
                                                textOverride = hyphenatedText,
                                                textScaleX = scaleX,
                                                typefaceOverride = face
                                            )
                                            if (condensedFit.fitsInside && condensedFit.fontSizeSp >= OverlayLayoutPolicy.READABLE_MIN_SP) {
                                                opt = condensedFit
                                                break
                                            }
                                        }
                                    }
                                }
                                if (!opt.fitsInside) {
                                    // Keep speech-bubble translations inside their safe
                                    // balloon area only while the scaled-down result stays
                                    // readable; otherwise show the original text below.
                                    if (!isSub) {
                                        val safeBubble = maxSafeBubbleBounds().let { detectedSafe ->
                                            if (!detectedSafe.isEmpty) {
                                                detectedSafe
                                            } else {
                                                Rect(block.bubbleBox).takeUnless { it.isEmpty }
                                                    ?: Rect(block.boundingBox)
                                            }
                                        }.apply {
                                            val pageBounds = Rect(
                                                0,
                                                0,
                                                pageOriginalWidth.toInt().coerceAtLeast(1),
                                                pageOriginalHeight.toInt().coerceAtLeast(1)
                                            )
                                            if (!intersect(pageBounds)) setEmpty()
                                        }
                                        if (!safeBubble.isEmpty) {
                                            val hyphenatedLastResort = hyphenateForFit(cleanText)
                                            geo = geometryFor(safeBubble)
                                            opt = optimize(
                                                geo,
                                                minSp = OverlayLayoutPolicy.READABLE_MIN_SP,
                                                allowBrokenWords = true,
                                                textOverride = hyphenatedLastResort,
                                                textScaleX = 0.85f,
                                                typefaceOverride = if (isSub) condensedTypeface else comicTypeface
                                            )
                                            // Strict BallonsTranslator boundary policy: NEVER expand beyond the original
                                            // detected speech balloon. The balloon's drawn borders and surrounding artwork
                                            // are strictly preserved.
                                            if (!opt.fitsInside) {
                                                val tighter = optimize(
                                                    geo,
                                                    minSp = OverlayLayoutPolicy.READABLE_MIN_SP,
                                                    allowBrokenWords = true,
                                                    textOverride = hyphenatedLastResort,
                                                    textScaleX = 0.78f,
                                                    typefaceOverride = condensedTypeface
                                                )
                                                if (tighter.fitsInside || tighter.totalHeightPx < opt.totalHeightPx) {
                                                    opt = tighter
                                                }
                                            }
                                            val isSliver = OverlayLayoutPolicy.isPathologicalSliver(
                                                widthDp = geo.widthDp.value,
                                                heightDp = geo.heightDp.value,
                                                lineCount = opt.lineCount,
                                                textLength = cleanText.length
                                            )
                                            if (opt.hasRenderableLayout && !isSliver) {
                                                return PlacedOverlayBox(
                                                    block = block,
                                                    leftDp = geo.leftDp,
                                                    topDp = geo.topDp,
                                                    widthDp = geo.widthDp,
                                                    heightDp = geo.heightDp,
                                                    layoutResult = opt,
                                                    paddingHDp = geo.padH,
                                                    paddingVDp = geo.padV,
                                                    forceTextToFitBox = !opt.fitsInside,
                                                    boldBoost = boldBoost,
                                                    contourProfile = contourProfile
                                                )
                                            } else if (shouldExpandMicroSliver(
                                                    widthDp = geo.widthDp.value,
                                                    heightDp = geo.heightDp.value,
                                                    isSliver = isSliver
                                                )
                                            ) {
                                                val targetWidthDp = maxOf(geo.widthDp, 48.dp)
                                                val shiftLeftDp = ((targetWidthDp - geo.widthDp) / 2f).coerceAtLeast(0.dp)
                                                val recoveredLeftDp = (geo.leftDp - shiftLeftDp).coerceAtLeast(2.dp)
                                                val recoveredGeo = geo.copy(
                                                    leftDp = recoveredLeftDp,
                                                    widthDp = targetWidthDp
                                                )
                                                val recoveredOpt = optimize(
                                                    recoveredGeo,
                                                    minSp = 7.0f,
                                                    allowBrokenWords = false,
                                                    textOverride = cleanText,
                                                    typefaceOverride = if (isSub) condensedTypeface else comicTypeface
                                                )
                                                if (recoveredOpt.hasRenderableLayout) {
                                                    val neededHeightDp = with(density) { recoveredOpt.totalHeightPx.toDp() } + geo.padV * 2f
                                                    return PlacedOverlayBox(
                                                        block = block,
                                                        leftDp = recoveredGeo.leftDp,
                                                        topDp = recoveredGeo.topDp,
                                                        widthDp = recoveredGeo.widthDp,
                                                        heightDp = maxOf(recoveredGeo.heightDp, neededHeightDp),
                                                        layoutResult = recoveredOpt,
                                                        paddingHDp = geo.padH,
                                                        paddingVDp = geo.padV,
                                                        forceTextToFitBox = true,
                                                        boldBoost = boldBoost,
                                                        contourProfile = contourProfile
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    // This is a layout fallback only. Keep the successful translation
                                    // available and do not change the block's translation status.
                                    // Include OCR text, its measured layout area, and the detected
                                    // bubble so a clipped rectangle cannot crop off original glyphs.
                                    val sourceBox = Rect(block.boundingBox).apply {
                                        union(block.layoutBox)
                                        union(block.bubbleBox)
                                    }
                                    val pageBounds = Rect(
                                        0,
                                        0,
                                        pageOriginalWidth.toInt(),
                                        pageOriginalHeight.toInt()
                                    )
                                    if (!sourceBox.intersect(pageBounds)) {
                                        sourceBox.set(block.boundingBox)
                                        sourceBox.intersect(pageBounds)
                                    }
                                    if (sourceBox.isEmpty) return null
                                    val fallbackGeo = geometryFor(sourceBox)
                                    val shouldFallbackToOriginal = !block.translationSucceeded || block.translatedText.isBlank()
                                    return PlacedOverlayBox(
                                        block = block,
                                        leftDp = fallbackGeo.leftDp,
                                        topDp = fallbackGeo.topDp,
                                        widthDp = fallbackGeo.widthDp,
                                        heightDp = fallbackGeo.heightDp,
                                        layoutResult = opt,
                                        paddingHDp = fallbackGeo.padH,
                                        paddingVDp = fallbackGeo.padV,
                                        showOriginalFallback = shouldFallbackToOriginal,
                                        fallbackSourceBounds = Rect(sourceBox),
                                        forceTextToFitBox = !shouldFallbackToOriginal,
                                        boldBoost = boldBoost,
                                        contourProfile = contourProfile
                                    )
                                }
                            }

                            // Translations that only fit at very small sizes leave most of
                            // the balloon empty. Retry allowing hyphenated/broken words so
                            // the text can grow into the available space instead of staying
                            // tiny with empty space above and below.
                            if (!isSub && opt.bubbleTextLayout == null &&
                                opt.fitsInside && opt.fontSizeSp < 8.0f
                            ) {
                                val brokenFit = optimize(
                                    geo,
                                    allowBrokenWords = true,
                                    textOverride = hyphenateForFit(cleanText)
                                )
                                if (brokenFit.fitsInside && brokenFit.fontSizeSp >= opt.fontSizeSp * 1.2f) {
                                    opt = brokenFit
                                }
                            }

                            val usedCompactContourPad = opt.bubbleTextLayout?.contourProfile != null && !isRectangularBox
                            val finalPadH = if (usedCompactContourPad) minOf(geo.padH, 2.5.dp) else geo.padH
                            val finalPadV = if (usedCompactContourPad) minOf(geo.padV, 2.5.dp) else geo.padV
                            return PlacedOverlayBox(
                                block = block,
                                leftDp = geo.leftDp,
                                topDp = geo.topDp,
                                widthDp = geo.widthDp,
                                // Keep the rendered bounds identical to the geometry that was
                                // measured and fit-checked; do not grow downward after placement.
                                heightDp = geo.heightDp,
                                layoutResult = opt,
                                paddingHDp = finalPadH,
                                paddingVDp = finalPadV,
                                // P1 line widths are measured with the exact paint used
                                // for drawing; fake-bold would invalidate that fit.
                                boldBoost = boldBoost && opt.bubbleTextLayout == null,
                                forceTextToFitBox = true,
                                contourProfile = contourProfile
                            )
                        }

                        // 1. Geçiş: Her balonun doğal en iyi yerleşimini hesapla.
                        val firstPass = translatedBlocks.withIndex().mapNotNull { (idx, blk) ->
                            layoutBlock(idx, blk)?.let { idx to it }
                        }

                        // 2. Geçiş (PageFontHarmonizer): Aynı sayfadaki komşu diyalog balonlarında
                        // tek kelimelik/seyrek balonların 24sp'ye fırlayıp ritmi bozmasını engelle.
                        val isSubtitleStyle = bubbleStyle == "Alt Yazı (Şeffaf)"
                        val pageBaseSp = if (isSubtitleStyle) {
                            null
                        } else {
                            PageFontHarmonizer.computePageBaselineSp(
                                firstPass.map { (_, placed) ->
                                    PageFontHarmonizer.BubbleSample(
                                        fontSizeSp = placed.layoutResult.fontSizeSp,
                                        text = placed.block.translatedText,
                                        isNormalSpeechBubble = placed.block.isBubble && !placed.showOriginalFallback,
                                        fitsInside = placed.layoutResult.fitsInside
                                    )
                                }
                            )
                        }
                        val placements = if (pageBaseSp != null) {
                            firstPass.map { (idx, placed) ->
                                val cap = if (placed.block.isBubble && !placed.showOriginalFallback) {
                                    PageFontHarmonizer.harmonizedCapSp(
                                        currentSp = placed.layoutResult.fontSizeSp,
                                        text = placed.block.translatedText,
                                        pageBaseSp = pageBaseSp
                                    )
                                } else {
                                    null
                                }
                                if (cap != null) {
                                    layoutBlock(idx, placed.block, harmonizedMaxSpCap = cap)
                                        ?.takeIf { it.layoutResult.fitsInside }
                                        ?: placed
                                } else {
                                    placed
                                }
                            }
                        } else {
                            firstPass.map { it.second }
                        }
                        placements
                        }
                    }
                    LaunchedEffect(placedOverlays) {
                        debugPlacedOverlays = placedOverlays
                    }

                    // Katman 1: balon/alt yazı zeminleri. Tüm zeminler bütün
                    // metinlerden ÖNCE çizilir; böylece hiçbir zemin komşu
                    // bir metni örtemez ve zeminlerin kırpılmasına gerek kalmaz.
                    val peekHiddenStates = remember { mutableStateMapOf<String, Boolean>() }
                    fun canRenderOverlayBox(placed: PlacedOverlayBox): Boolean {
                        val block = placed.block
                        if (placed.showOriginalFallback) return false
                        if (!block.translationSucceeded || block.translatedText.isBlank()) return false
                        if (!placed.layoutResult.hasRenderableLayout) return false
                        if (placed.layoutResult.layoutWidthPx <= 0f || placed.layoutResult.layoutHeightPx <= 0f) return false
                        return true
                    }
                    placedOverlays.forEach { placed ->
                        val block = placed.block
                        val overlayKey = "$pageIndex:${block.boundingBox}:${block.originalText}:${placed.showOriginalFallback}"
                        if (placed.showOriginalFallback || peekHiddenStates[overlayKey] == true || !canRenderOverlayBox(placed)) {
                            return@forEach
                        }
                        val isSubtitleMode = bubbleStyle == "Alt Yazı (Şeffaf)"
                        val isFloatingHaloText = resolveShouldDrawFloatingHaloText(
                            isBubble = block.isBubble,
                            hasBackgroundColor = block.backgroundColor != null,
                            bubbleStyle = bubbleStyle
                        )
                        val contour = (placed.layoutResult.bubbleTextLayout?.contourProfile
                            ?: placed.contourProfile)?.takeIf { it.isUsable }
                        val isRectangularBox = resolveDrawIsRectangularBox(
                            isBubble = block.isBubble,
                            bubbleBox = block.bubbleBox,
                            widthDp = placed.widthDp.value,
                            heightDp = placed.heightDp.value,
                            contourProfile = contour
                        )

                        val resolvedBgColor = block.backgroundColor?.let { rawBg ->
                            val r = android.graphics.Color.red(rawBg)
                            val g = android.graphics.Color.green(rawBg)
                            val b = android.graphics.Color.blue(rawBg)
                            val lum = r * 0.299f + g * 0.587f + b * 0.114f
                            val chroma = max(r, max(g, b)) - min(r, min(g, b))
                            if (!block.isDarkBubble && lum > 236f && chroma <= 10) Color.White else Color(rawBg)
                        }
                        val boxBackground = when {
                            isSubtitleMode -> Color.Black.copy(alpha = 0.85f)
                            bubbleStyle == "Koyu Zemin" -> Color(0xFF111111).copy(alpha = userAlpha)
                            resolvedBgColor != null -> resolvedBgColor.copy(alpha = userAlpha)
                            block.isDarkBubble -> Color(0xFF111111).copy(alpha = userAlpha)
                            else -> Color.White.copy(alpha = userAlpha)
                        }

                        // Metin ve balon kapsamı: Zemin, orijinal OCR metin alanının
                        // (boundingBox), tespit edilen balonun (bubbleBox) ve yeni
                        // yerleştirilen çeviri metninin (placed) tamamını örter.
                        // Böylece hiçbir orijinal İngilizce harf dışarı taşmaz.
                        val anchorLeftDp = with(density) { (block.boundingBox.left * coordinateScaleX).toDp() }
                        val anchorTopDp = with(density) { (block.boundingBox.top * coordinateScaleY).toDp() }
                        val anchorRightDp = with(density) { (block.boundingBox.right * coordinateScaleX).toDp() }
                        val anchorBottomDp = with(density) { (block.boundingBox.bottom * coordinateScaleY).toDp() }

                        val hasValidBubble = !block.bubbleBox.isEmpty && block.bubbleBox.width() > 0 && block.bubbleBox.height() > 0
                        val bubbleLeftDp = if (hasValidBubble) with(density) { (block.bubbleBox.left * coordinateScaleX).toDp() } else anchorLeftDp
                        val bubbleTopDp = if (hasValidBubble) with(density) { (block.bubbleBox.top * coordinateScaleY).toDp() } else anchorTopDp
                        val bubbleRightDp = if (hasValidBubble) with(density) { (block.bubbleBox.right * coordinateScaleX).toDp() } else anchorRightDp
                        val bubbleBottomDp = if (hasValidBubble) with(density) { (block.bubbleBox.bottom * coordinateScaleY).toDp() } else anchorBottomDp

                        val placedLeftDp = placed.leftDp
                        val placedTopDp = placed.topDp
                        val placedRightDp = placed.leftDp + placed.widthDp
                        val placedBottomDp = placed.topDp + placed.heightDp

                        val hasRealMaskBitmap = validMasked != null
                        val spilledBeyondBubble = !hasValidBubble ||
                            placedLeftDp < bubbleLeftDp - 4.dp ||
                            placedTopDp < bubbleTopDp - 4.dp ||
                            placedRightDp > bubbleRightDp + 4.dp ||
                            placedBottomDp > bubbleBottomDp + 4.dp
                        val isPlacedExpanded = spilledBeyondBubble ||
                            (placed.fallbackSourceBounds != null && !(hasRealMaskBitmap && hasValidBubble && block.isBubble))

                        val isOvalFallback = !isSubtitleMode && !isRectangularBox &&
                            (contour == null || !contour.isUsable)
                        val baseMarginDp = if (hasRealMaskBitmap &&
                            hasValidBubble &&
                            block.isBubble &&
                            !isPlacedExpanded &&
                            bubbleStyle != "Klasik Kutu" &&
                            bubbleStyle != "Koyu Zemin"
                        ) {
                            0f
                        } else {
                            2f
                        }
                        // When a real masked bitmap already erased the original text inside the bubble,
                        // keep the Layer 1 fill strictly within the separated placement bounds and never
                        // trigger the 1.50x oval corner bloat that paints over neighboring bubbles/art.
                        val useTrimmedPlacedBg = hasRealMaskBitmap &&
                            hasValidBubble &&
                            block.isBubble &&
                            bubbleStyle == "Doğal Balon" &&
                            contour == null &&
                            !isPlacedExpanded
                        val bgBubbleL = if (useTrimmedPlacedBg) placedLeftDp.value else bubbleLeftDp.value
                        val bgBubbleT = if (useTrimmedPlacedBg) placedTopDp.value else bubbleTopDp.value
                        val bgBubbleR = if (useTrimmedPlacedBg) placedRightDp.value else bubbleRightDp.value
                        val bgBubbleB = if (useTrimmedPlacedBg) placedBottomDp.value else bubbleBottomDp.value
                        val bgAnchorL = if (useTrimmedPlacedBg) placedLeftDp.value else anchorLeftDp.value
                        val bgAnchorT = if (useTrimmedPlacedBg) placedTopDp.value else anchorTopDp.value
                        val bgAnchorR = if (useTrimmedPlacedBg) placedRightDp.value else anchorRightDp.value
                        val bgAnchorB = if (useTrimmedPlacedBg) placedBottomDp.value else anchorBottomDp.value
                        val resolvedBoundsDp = resolveOverlayBackgroundBoundsDp(
                            isSubtitleMode = isSubtitleMode,
                            isOvalFallback = isOvalFallback && !hasRealMaskBitmap,
                            anchorLeftDp = bgAnchorL,
                            anchorTopDp = bgAnchorT,
                            anchorRightDp = bgAnchorR,
                            anchorBottomDp = bgAnchorB,
                            bubbleLeftDp = bgBubbleL,
                            bubbleTopDp = bgBubbleT,
                            bubbleRightDp = bgBubbleR,
                            bubbleBottomDp = bgBubbleB,
                            placedLeftDp = placedLeftDp.value,
                            placedTopDp = placedTopDp.value,
                            placedRightDp = placedRightDp.value,
                            placedBottomDp = placedBottomDp.value,
                            marginDp = baseMarginDp
                        )
                        val didOvalExpand = isOvalFallback && hasValidBubble && (
                            resolvedBoundsDp.width > (bubbleRightDp - bubbleLeftDp).value + 4f ||
                                resolvedBoundsDp.height > (bubbleBottomDp - bubbleTopDp).value + 4f
                            )
                        val isFallbackExpanded = isPlacedExpanded || didOvalExpand
                        val drawBubbleBorder = shouldDrawNaturalBubbleBorder(
                            isSubtitleMode = isSubtitleMode,
                            isBubble = block.isBubble,
                            hasRealMaskBitmap = hasRealMaskBitmap,
                            isFallbackExpanded = isFallbackExpanded,
                            bubbleStyle = bubbleStyle
                        )
                        val boxLeft = resolvedBoundsDp.left.dp
                        val boxTop = resolvedBoundsDp.top.dp
                        val boxWidth = resolvedBoundsDp.width.dp
                        val boxHeight = resolvedBoundsDp.height.dp

                        val cachedBackgroundPath = remember(
                            block,
                            contour,
                            isRectangularBox,
                            isSubtitleMode,
                            isFloatingHaloText,
                            hasValidBubble,
                            bubbleLeftDp,
                            bubbleTopDp,
                            bubbleRightDp,
                            bubbleBottomDp,
                            boxLeft,
                            boxTop,
                            boxWidth,
                            boxHeight,
                            coordinateScaleX,
                            coordinateScaleY,
                            density
                        ) {
                            if (!isFloatingHaloText && contour != null && contour.isUsable && !isRectangularBox && !isSubtitleMode) {
                                val bubbleRect = if (hasValidBubble) {
                                    val left = with(density) { (bubbleLeftDp - boxLeft).toPx() }
                                    val top = with(density) { (bubbleTopDp - boxTop).toPx() }
                                    val right = with(density) { (bubbleRightDp - boxLeft).toPx() }
                                    val bottom = with(density) { (bubbleBottomDp - boxTop).toPx() }
                                    RectF(left, top, right, bottom)
                                } else {
                                    RectF(0f, 0f, with(density) { boxWidth.toPx() }, with(density) { boxHeight.toPx() })
                                }
                                buildContourPath(contour, bubbleRect).asComposePath()
                            } else {
                                null
                            }
                        }

                        Box(
                            modifier = Modifier
                                .absoluteOffset(x = boxLeft, y = boxTop)
                                .width(boxWidth)
                                .height(boxHeight)
                                .drawBehind {
                                    if (isFloatingHaloText || size.width <= 0f || size.height <= 0f) return@drawBehind
                                    val isDark = block.isDarkBubble || bubbleStyle == "Koyu Zemin"
                                    val borderColor = if (isDark) {
                                        Color.White.copy(alpha = 0.85f)
                                    } else {
                                        Color.Black.copy(alpha = 0.90f)
                                    }

                                    if (cachedBackgroundPath != null) {
                                        // 1. Yumuşak iç kontur zemin dolgusu
                                        drawPath(
                                            path = cachedBackgroundPath,
                                            color = boxBackground
                                        )
                                        if (drawBubbleBorder) {
                                            // 2. Maske yoksa/genişletilmişse mürekkep sızıntısını örten dolgu ve yapay çerçeve
                                            drawPath(
                                                path = cachedBackgroundPath,
                                                color = boxBackground,
                                                style = Stroke(
                                                    width = with(density) { 2.dp.toPx() },
                                                    cap = StrokeCap.Round,
                                                    join = StrokeJoin.Round
                                                )
                                            )
                                            drawPath(
                                                path = cachedBackgroundPath,
                                                color = borderColor,
                                                style = Stroke(
                                                    width = with(density) { 1.5.dp.toPx() },
                                                    cap = StrokeCap.Round,
                                                    join = StrokeJoin.Round
                                                )
                                            )
                                        }
                                    } else if (isRectangularBox || isSubtitleMode) {
                                        drawRoundRect(
                                            color = boxBackground,
                                            size = size,
                                            cornerRadius = CornerRadius(6.dp.toPx())
                                        )
                                        if (drawBubbleBorder && shouldDrawRectangularBorder(
                                                isSubtitleMode = isSubtitleMode,
                                                isRectangularBox = isRectangularBox,
                                                isBubble = block.isBubble
                                            )
                                        ) {
                                            drawRoundRect(
                                                color = borderColor,
                                                size = size,
                                                cornerRadius = CornerRadius(6.dp.toPx()),
                                                style = Stroke(width = with(density) { 1.5.dp.toPx() })
                                            )
                                        }
                                    } else {
                                        drawOval(
                                            color = boxBackground,
                                            size = size
                                        )
                                        if (drawBubbleBorder) {
                                            drawOval(
                                                color = borderColor,
                                                size = size,
                                                style = Stroke(width = with(density) { 1.5.dp.toPx() })
                                            )
                                        }
                                    }
                                }
                                .combinedClickable(
                                    onClick = { peekHiddenStates[overlayKey] = true },
                                    onLongClick = { onCorrectionClick(block.originalText, block.translatedText) }
                                )
                        )
                    }

                    // Katman 2: metinler ve kırpılmış orijinal görseller,
                    // zeminlerin üstünde.
                    placedOverlays.forEachIndexed { placedIndex, placed ->
                        val block = placed.block
                        key("$pageIndex:$placedIndex:${block.boundingBox}:${block.originalText}") {
                        val overlayKey = "$pageIndex:${block.boundingBox}:${block.originalText}:${placed.showOriginalFallback}"
                        val isPeekHidden = peekHiddenStates[overlayKey] ?: placed.showOriginalFallback
                        val isSubtitleMode = bubbleStyle == "Alt Yazı (Şeffaf)"

                        val boxWidth = placed.widthDp
                        val boxHeight = placed.heightDp
                        val boxLeft = placed.leftDp
                        val boxTop = placed.topDp
                        val paddingH = placed.paddingHDp
                        val paddingV = placed.paddingVDp
                        val layoutResult = placed.layoutResult

                        if (placed.showOriginalFallback || isPeekHidden) {
                            val cropBounds = placed.fallbackSourceBounds ?: remember(
                                block.boundingBox,
                                block.layoutBox,
                                block.bubbleBox,
                                pageOriginalWidth,
                                pageOriginalHeight
                            ) {
                                val expandedBounds = Rect(block.boundingBox).apply {
                                    union(block.layoutBox)
                                    union(block.bubbleBox)
                                }
                                val imageBounds = Rect(
                                    0,
                                    0,
                                    pageOriginalWidth.toInt(),
                                    pageOriginalHeight.toInt()
                                )
                                if (!expandedBounds.intersect(imageBounds)) {
                                    expandedBounds.set(block.boundingBox)
                                    expandedBounds.intersect(imageBounds)
                                }
                                expandedBounds
                            }
                            val cropRect = remember(cropBounds, bmp, pageOriginalWidth, pageOriginalHeight) {
                                val b = cropBounds
                                val scaleX = bmp.width.toFloat() / pageOriginalWidth
                                val scaleY = bmp.height.toFloat() / pageOriginalHeight
                                val cLeft = (b.left * scaleX).toInt().coerceIn(0, bmp.width - 1)
                                val cTop = (b.top * scaleY).toInt().coerceIn(0, bmp.height - 1)
                                val cRight = (b.right * scaleX).toInt().coerceIn(cLeft + 1, bmp.width)
                                val cBottom = (b.bottom * scaleY).toInt().coerceIn(cTop + 1, bmp.height)
                                Rect(cLeft, cTop, cRight, cBottom)
                            }
                            val cropBitmap = remember(cropRect, bmp, boxWidth, boxHeight, density) {
                                try {
                                    val sourceWidth = cropRect.width()
                                    val sourceHeight = cropRect.height()
                                    if (sourceWidth <= 0 || sourceHeight <= 0) {
                                        null
                                    } else {
                                        // The crop is only displayed inside this overlay. Avoid
                                        // keeping a full-resolution copy when the on-screen box
                                        // needs far fewer pixels; draw directly into the smaller
                                        // bitmap so no full-size intermediate is allocated.
                                        val displayEdgePx = with(density) {
                                            maxOf(boxWidth, boxHeight).roundToPx().coerceAtLeast(1)
                                        }
                                        val maxOutputEdge = (displayEdgePx.toLong() * 2L)
                                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                        val scale = minOf(
                                            1f,
                                            maxOutputEdge.toFloat() / maxOf(sourceWidth, sourceHeight)
                                        )
                                        val outputWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
                                        val outputHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
                                        if (scale >= 0.999f) {
                                            Bitmap.createBitmap(
                                                bmp,
                                                cropRect.left,
                                                cropRect.top,
                                                sourceWidth,
                                                sourceHeight
                                            )
                                        } else {
                                            Bitmap.createBitmap(
                                                outputWidth,
                                                outputHeight,
                                                Bitmap.Config.ARGB_8888
                                            ).also { output ->
                                                android.graphics.Canvas(output).drawBitmap(
                                                    bmp,
                                                    cropRect,
                                                    Rect(0, 0, outputWidth, outputHeight),
                                                    android.graphics.Paint(
                                                        android.graphics.Paint.ANTI_ALIAS_FLAG or
                                                            android.graphics.Paint.FILTER_BITMAP_FLAG
                                                    )
                                                )
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            DisposableEffect(cropBitmap, bmp) {
                                onDispose {
                                    if (cropBitmap != null && cropBitmap !== bmp && !cropBitmap.isRecycled) {
                                        cropBitmap.recycle()
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .absoluteOffset(x = boxLeft, y = boxTop)
                                    .width(boxWidth)
                                    .height(boxHeight)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { peekHiddenStates[overlayKey] = false }
                            ) {
                                if (cropBitmap != null) {
                                    Image(
                                        bitmap = cropBitmap.asImageBitmap(),
                                        contentDescription = "Orijinal Metin",
                                        modifier = Modifier.matchParentSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                                if (!placed.showOriginalFallback) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(2.dp)
                                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("TR", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(2.dp)
                                            .background(Color(0xFF008577).copy(alpha = 0.88f), RoundedCornerShape(3.dp))
                                            .clickable { peekHiddenStates[overlayKey] = false }
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            "TR",
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        } else if (canRenderOverlayBox(placed)) {
                            val isFloatingHaloText = resolveShouldDrawFloatingHaloText(
                                isBubble = block.isBubble,
                                hasBackgroundColor = block.backgroundColor != null,
                                bubbleStyle = bubbleStyle
                            )
                            val textColor = when {
                                isSubtitleMode || bubbleStyle == "Koyu Zemin" -> Color.White
                                block.backgroundColor != null -> contrastingTextColor(
                                    block.backgroundColor,
                                    effectiveAlpha = if (block.isDarkBubble || validMasked != null) 1f else userAlpha
                                )
                                block.isDarkBubble -> Color.White
                                else -> Color.Black
                            }
                            val haloStrokeColor = if (isFloatingHaloText) {
                                resolveFloatingHaloStrokeColor(textColor)
                            } else null
                            val textShadowColor = if (isSubtitleMode) Color.Black.copy(alpha = 0.55f) else null
                            val drawContour = (layoutResult.bubbleTextLayout?.contourProfile
                                ?: placed.contourProfile)?.takeIf { it.isUsable }
                            val drawIsRectangularBox = resolveDrawIsRectangularBox(
                                isBubble = block.isBubble,
                                bubbleBox = block.bubbleBox,
                                widthDp = placed.widthDp.value,
                                heightDp = placed.heightDp.value,
                                contourProfile = drawContour
                            )
                            val fallbackContourClipPath = remember(
                                block,
                                drawContour,
                                drawIsRectangularBox,
                                isSubtitleMode,
                                layoutResult.bubbleTextLayout,
                                boxLeft,
                                boxTop,
                                boxWidth,
                                boxHeight,
                                paddingH,
                                paddingV,
                                coordinateScaleX,
                                coordinateScaleY,
                                density
                            ) {
                                if (drawContour != null &&
                                    !drawIsRectangularBox &&
                                    !isSubtitleMode &&
                                    layoutResult.bubbleTextLayout?.contourProfile == null
                                ) {
                                    val hasValidBubble = !block.bubbleBox.isEmpty &&
                                        block.bubbleBox.width() > 0 &&
                                        block.bubbleBox.height() > 0
                                    val canvasLeftPx = with(density) { (boxLeft + paddingH).toPx() }
                                    val canvasTopPx = with(density) { (boxTop + paddingV).toPx() }
                                    val rawRect = if (hasValidBubble) {
                                        RectF(
                                            with(density) { (block.bubbleBox.left * coordinateScaleX).toDp().toPx() } - canvasLeftPx,
                                            with(density) { (block.bubbleBox.top * coordinateScaleY).toDp().toPx() } - canvasTopPx,
                                            with(density) { (block.bubbleBox.right * coordinateScaleX).toDp().toPx() } - canvasLeftPx,
                                            with(density) { (block.bubbleBox.bottom * coordinateScaleY).toDp().toPx() } - canvasTopPx
                                        )
                                    } else {
                                        RectF(
                                            with(density) { -paddingH.toPx() },
                                            with(density) { -paddingV.toPx() },
                                            with(density) { (boxWidth - paddingH).toPx() },
                                            with(density) { (boxHeight - paddingV).toPx() }
                                        )
                                    }
                                    val insetPx = with(density) { 1.dp.toPx() }
                                    val clipRect = if (rawRect.width() > insetPx * 2f + 4f &&
                                        rawRect.height() > insetPx * 2f + 4f
                                    ) {
                                        RectF(
                                            rawRect.left + insetPx,
                                            rawRect.top + insetPx,
                                            rawRect.right - insetPx,
                                            rawRect.bottom - insetPx
                                        )
                                    } else {
                                        rawRect
                                    }
                                    if (clipRect.width() > 2f && clipRect.height() > 2f) {
                                        buildContourPath(drawContour, clipRect)
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .absoluteOffset(x = boxLeft, y = boxTop)
                                    .width(boxWidth)
                                    .height(boxHeight)
                                    .combinedClickable(
                                        onClick = { peekHiddenStates[overlayKey] = true },
                                        onLongClick = { onCorrectionClick(block.originalText, block.translatedText) }
                                    )
                                    .padding(horizontal = paddingH, vertical = paddingV)
                                    .semantics { contentDescription = block.translatedText },
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val staticLayout = layoutResult.staticLayout
                                    val bubbleLayout = layoutResult.bubbleTextLayout
                                    if (staticLayout == null && bubbleLayout?.lines.isNullOrEmpty()) {
                                        return@Canvas
                                    }
                                    if (size.width <= 0f || size.height <= 0f) return@Canvas

                                    val rawLayoutWidth = layoutResult.layoutWidthPx
                                    val layoutHeight = layoutResult.layoutHeightPx
                                    if (rawLayoutWidth <= 0f || layoutHeight <= 0f) return@Canvas

                                    // Balona dinamik ölçekleme: Her boyuttaki metin balona tam oturur
                                    val transform = computeCanvasTextTransform(
                                        canvasWidthPx = size.width,
                                        canvasHeightPx = size.height,
                                        layoutWidthPx = rawLayoutWidth,
                                        layoutHeightPx = layoutHeight,
                                        maxLineWidthPx = layoutResult.maxLineWidthPx
                                    )
                                    val nativeCanvas = drawContext.canvas.nativeCanvas
                                    val textPaint = bubbleLayout?.let { layoutResult.bubbleTextPaint }
                                        ?: staticLayout?.paint
                                        ?: return@Canvas
                                    textPaint.isFakeBoldText = false
                                    val saveCount = nativeCanvas.save()
                                    nativeCanvas.translate(
                                        transform.translateX,
                                        transform.translateY
                                    )
                                    nativeCanvas.scale(transform.scale, transform.scale)
                                    if (isFloatingHaloText && haloStrokeColor != null) {
                                        val prevStyle = textPaint.style
                                        val prevStrokeWidth = textPaint.strokeWidth
                                        val prevStrokeJoin = textPaint.strokeJoin
                                        val prevStrokeCap = textPaint.strokeCap
                                        textPaint.clearShadowLayer()
                                        textPaint.style = android.graphics.Paint.Style.STROKE
                                        textPaint.strokeWidth = with(density) { 3.5.dp.toPx() } /
                                            transform.scale.coerceAtLeast(0.5f)
                                        textPaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                        textPaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                        textPaint.color = haloStrokeColor.toArgb()
                                        if (bubbleLayout != null) {
                                            bubbleLayout.lines.forEach { line ->
                                                nativeCanvas.drawText(
                                                    line.text,
                                                    line.leftPx,
                                                    line.baselinePx,
                                                    textPaint
                                                )
                                            }
                                        } else {
                                            staticLayout?.draw(nativeCanvas)
                                        }
                                        textPaint.style = prevStyle
                                        textPaint.strokeWidth = prevStrokeWidth
                                        textPaint.strokeJoin = prevStrokeJoin
                                        textPaint.strokeCap = prevStrokeCap
                                    }
                                    textPaint.style = android.graphics.Paint.Style.FILL
                                    textPaint.color = textColor.toArgb()
                                    if (textShadowColor != null) {
                                        textPaint.setShadowLayer(
                                            3f * density.density,
                                            1.5f * density.density,
                                            1.5f * density.density,
                                            textShadowColor.toArgb()
                                        )
                                    } else {
                                        textPaint.clearShadowLayer()
                                    }
                                    if (bubbleLayout != null) {
                                        bubbleLayout.lines.forEach { line ->
                                            nativeCanvas.drawText(
                                                line.text,
                                                line.leftPx,
                                                line.baselinePx,
                                                textPaint
                                            )
                                        }
                                    } else {
                                        staticLayout?.draw(nativeCanvas)
                                    }
                                    nativeCanvas.restoreToCount(saveCount)
                                }
                                if (!block.translationSucceeded) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(18.dp)
                                            .background(Color(0xFFD32F2F), RoundedCornerShape(50)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("!", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        }
                    }
                }

                // ==================== DEBUG MOD GÖRSELLEŞTİRME ====================
                if (isDebugMode) {
                    val detectedBubblesMap by viewModel.detectedBubblesMap.collectAsState()
                    val detectedBubbles: List<BubbleBox> = detectedBubblesMap[pageIndex] ?: emptyList()

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        val scale = renderedBitmap.width * widthScale
                        val strokePx = with(density) { 1.5.dp.toPx() }
                        val cornerPx = with(density) { 4.dp.toPx() }
                        val horizontalPadding = with(density) { 3.dp.toPx() }
                        val verticalPadding = with(density) { 1.dp.toPx() }
                        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.BLACK
                            textSize = with(density) { 8.sp.toPx() }
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        }
                        val boxPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(0, 229, 255)
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = strokePx
                        }
                        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(0, 229, 255)
                            style = android.graphics.Paint.Style.FILL
                        }

                        fun drawDebugBox(
                            rect: Rect,
                            scaleX: Float,
                            scaleY: Float,
                            color: Int,
                            label: String,
                            strokeWidth: Float,
                            radius: Float
                        ) {
                            if (rect.isEmpty) return
                            val left = rect.left * scaleX
                            val top = rect.top * scaleY
                            val right = rect.right * scaleX
                            val bottom = rect.bottom * scaleY
                            boxPaint.color = color
                            boxPaint.strokeWidth = strokeWidth
                            nativeCanvas.drawRoundRect(
                                RectF(left, top, right, bottom),
                                radius,
                                radius,
                                boxPaint
                            )

                            val metrics = textPaint.fontMetrics
                            val labelWidth = textPaint.measureText(label)
                            val labelHeight = metrics.descent - metrics.ascent
                            val labelLeft = left.coerceIn(
                                0f,
                                (size.width - labelWidth - horizontalPadding * 2).coerceAtLeast(0f)
                            )
                            val labelTop = (top - labelHeight - verticalPadding * 2)
                                .coerceIn(0f, (size.height - labelHeight - verticalPadding * 2).coerceAtLeast(0f))
                            labelPaint.color = color
                            nativeCanvas.drawRect(
                                labelLeft,
                                labelTop,
                                labelLeft + labelWidth + horizontalPadding * 2,
                                labelTop + labelHeight + verticalPadding * 2,
                                labelPaint
                            )
                            textPaint.color = android.graphics.Color.BLACK
                            nativeCanvas.drawText(
                                label,
                                labelLeft + horizontalPadding,
                                labelTop + verticalPadding - metrics.ascent,
                                textPaint
                            )
                        }

                        detectedBubbles.forEach { bubble ->
                            val left = bubble.bounds.left * renderedBitmap.width * widthScale
                            val top = bubble.bounds.top * renderedBitmap.height * widthScale
                            val right = bubble.bounds.right * renderedBitmap.width * widthScale
                            val bottom = bubble.bounds.bottom * renderedBitmap.height * widthScale
                            val label = "Aday ${bubble.label} #${bubble.id} (${(bubble.confidence * 100).toInt()}%)"

                            if (bubble.contourProfile != null && bubble.contourProfile.isUsable) {
                                val contourPath = buildContourPath(bubble.contourProfile, RectF(left, top, right, bottom))
                                boxPaint.color = android.graphics.Color.argb(38, 0, 229, 255)
                                boxPaint.style = android.graphics.Paint.Style.FILL
                                nativeCanvas.drawPath(contourPath, boxPaint)

                                boxPaint.color = android.graphics.Color.rgb(0, 229, 255)
                                boxPaint.style = android.graphics.Paint.Style.STROKE
                                boxPaint.strokeWidth = strokePx
                                nativeCanvas.drawPath(contourPath, boxPaint)

                                val metrics = textPaint.fontMetrics
                                val labelWidth = textPaint.measureText(label)
                                val labelHeight = metrics.descent - metrics.ascent
                                val labelLeft = left.coerceIn(
                                    0f,
                                    (size.width - labelWidth - horizontalPadding * 2).coerceAtLeast(0f)
                                )
                                val labelTop = (top - labelHeight - verticalPadding * 2)
                                    .coerceIn(0f, (size.height - labelHeight - verticalPadding * 2).coerceAtLeast(0f))
                                labelPaint.color = android.graphics.Color.rgb(0, 229, 255)
                                nativeCanvas.drawRect(
                                    labelLeft,
                                    labelTop,
                                    labelLeft + labelWidth + horizontalPadding * 2,
                                    labelTop + labelHeight + verticalPadding * 2,
                                    labelPaint
                                )
                                textPaint.color = android.graphics.Color.BLACK
                                nativeCanvas.drawText(
                                    label,
                                    labelLeft + horizontalPadding,
                                    labelTop + verticalPadding - metrics.ascent,
                                    textPaint
                                )
                            } else {
                                val normalizedRect = Rect(
                                    (bubble.bounds.left * renderedBitmap.width).toInt(),
                                    (bubble.bounds.top * renderedBitmap.height).toInt(),
                                    (bubble.bounds.right * renderedBitmap.width).toInt(),
                                    (bubble.bounds.bottom * renderedBitmap.height).toInt()
                                )
                                drawDebugBox(
                                    normalizedRect,
                                    widthScale,
                                    widthScale,
                                    android.graphics.Color.rgb(0, 229, 255),
                                    label,
                                    strokePx,
                                    cornerPx
                                )
                            }
                        }

                        translatedBlocks.forEachIndexed { idx, block ->
                            if (block.contourProfile != null && block.contourProfile.isUsable) {
                                val left = block.bubbleBox.left * coordinateScaleX
                                val top = block.bubbleBox.top * coordinateScaleY
                                val right = block.bubbleBox.right * coordinateScaleX
                                val bottom = block.bubbleBox.bottom * coordinateScaleY
                                val contourPath = buildContourPath(block.contourProfile, RectF(left, top, right, bottom))
                                boxPaint.color = android.graphics.Color.rgb(224, 64, 251)
                                boxPaint.style = android.graphics.Paint.Style.STROKE
                                boxPaint.strokeWidth = with(density) { 2.dp.toPx() }
                                nativeCanvas.drawPath(contourPath, boxPaint)
                            } else {
                                drawDebugBox(
                                    block.bubbleBox,
                                    coordinateScaleX,
                                    coordinateScaleY,
                                    android.graphics.Color.rgb(224, 64, 251),
                                    "Ham balon #${idx + 1} [${block.bubbleBox.width()}x${block.bubbleBox.height()}]",
                                    with(density) { 2.dp.toPx() },
                                    cornerPx
                                )
                            }
                            val placed = if (translationActive) {
                                debugPlacedOverlays.firstOrNull {
                                    it.block.boundingBox == block.boundingBox &&
                                        it.block.originalText == block.originalText
                                }
                            } else null
                            val safeScaleX = coordinateScaleX.coerceAtLeast(0.0001f)
                            val safeScaleY = coordinateScaleY.coerceAtLeast(0.0001f)
                            val targetBox = if (placed != null) {
                                Rect(
                                    (with(density) { placed.leftDp.toPx() } / safeScaleX)
                                        .roundToInt(),
                                    (with(density) { placed.topDp.toPx() } / safeScaleY)
                                        .roundToInt(),
                                    (with(density) { (placed.leftDp + placed.widthDp).toPx() } /
                                        safeScaleX).roundToInt(),
                                    (with(density) { (placed.topDp + placed.heightDp).toPx() } /
                                        safeScaleY).roundToInt()
                                )
                            } else {
                                block.layoutBox
                            }
                            drawDebugBox(
                                targetBox,
                                coordinateScaleX,
                                coordinateScaleY,
                                android.graphics.Color.rgb(0, 230, 118),
                                "Hedef kutu",
                                with(density) { 1.dp.toPx() },
                                with(density) { 2.dp.toPx() }
                            )
                            drawDebugBox(
                                block.boundingBox,
                                coordinateScaleX,
                                coordinateScaleY,
                                android.graphics.Color.rgb(255, 214, 0),
                                "OCR #${idx + 1}",
                                with(density) { 1.dp.toPx() },
                                with(density) { 2.dp.toPx() }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF00E676))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.BugReport, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Debug: ${translatedBlocks.size} Balon | Auto-Scale Aktif",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(550.dp),
                contentAlignment = Alignment.Center
            ) {
                if (bitmapLoadFailed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sayfa yüklenemedi.", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { bitmapLoadAttempt++ }) {
                            Text("Yeniden Dene")
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Sayfa yükleniyor...", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
