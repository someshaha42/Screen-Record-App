package com.screenrecorder.pro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.screenrecorder.pro.model.NormalizedRegion
import com.screenrecorder.pro.util.DisplayMetricsHelper
import kotlin.math.hypot

private val DeepRedBorderColor = Color(0xFFB91C1C)

private enum class DragHandle {
    NONE,
    CENTER,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

/**
 * Lightweight animated deep-red rectangular dashed border indicator.
 * Used inside the app preview/selector and never relies on heavy glow effects.
 */
@Composable
fun AnimatedDeepRedDashedBorder(
    region: NormalizedRegion,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "region_dash")
    val density = LocalDensity.current
    val dashLenPx = with(density) { 12.dp.toPx() }
    val gapLenPx = with(density) { 8.dp.toPx() }
    val strokePx = with(density) { 2.5.dp.toPx() }
    val cycle = dashLenPx + gapLenPx

    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = cycle,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dash_phase"
    )

    Canvas(modifier = modifier) {
        val left = region.left * size.width
        val top = region.top * size.height
        val width = (region.right - region.left) * size.width
        val height = (region.bottom - region.top) * size.height

        drawRect(
            color = DeepRedBorderColor,
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(
                width = strokePx,
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(dashLenPx, gapLenPx),
                    phase = -phase
                )
            )
        )
    }
}

/**
 * Full-screen interactive region selector with corner resize handles, center move drag,
 * live cropped pixel dimension readout, and an easy confirmation button.
 */
@Composable
fun RegionSelectorDialog(
    initialRegion: NormalizedRegion,
    onConfirm: (NormalizedRegion) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val displaySnap = remember(context) { DisplayMetricsHelper.getCurrentDisplaySnapshot(context) }

    var workingRegion by remember(initialRegion) { mutableStateOf(initialRegion.clamped()) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var activeHandle by remember { mutableStateOf(DragHandle.NONE) }

    val pxRegion = remember(workingRegion, displaySnap) {
        DisplayMetricsHelper.toPixelRegion(workingRegion, displaySnap.width, displaySnap.height)
    }

    val transition = rememberInfiniteTransition(label = "selector_dash")
    val density = LocalDensity.current
    val dashPx = with(density) { 14.dp.toPx() }
    val gapPx = with(density) { 9.dp.toPx() }
    val strokePx = with(density) { 3.dp.toPx() }
    val handleRadiusPx = with(density) { 11.dp.toPx() }
    val touchSlopPx = with(density) { 34.dp.toPx() }

    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = dashPx + gapPx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "selector_phase"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .onSizeChanged { containerSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val w = containerSize.width.toFloat().coerceAtLeast(1f)
                            val h = containerSize.height.toFloat().coerceAtLeast(1f)
                            val l = workingRegion.left * w
                            val t = workingRegion.top * h
                            val r = workingRegion.right * w
                            val b = workingRegion.bottom * h

                            activeHandle = when {
                                hypot(start.x - l, start.y - t) <= touchSlopPx -> DragHandle.TOP_LEFT
                                hypot(start.x - r, start.y - t) <= touchSlopPx -> DragHandle.TOP_RIGHT
                                hypot(start.x - l, start.y - b) <= touchSlopPx -> DragHandle.BOTTOM_LEFT
                                hypot(start.x - r, start.y - b) <= touchSlopPx -> DragHandle.BOTTOM_RIGHT
                                start.x in l..r && start.y in t..b -> DragHandle.CENTER
                                else -> DragHandle.NONE
                            }
                        },
                        onDragEnd = { activeHandle = DragHandle.NONE },
                        onDragCancel = { activeHandle = DragHandle.NONE },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val w = containerSize.width.toFloat().coerceAtLeast(1f)
                            val h = containerSize.height.toFloat().coerceAtLeast(1f)
                            val dx = dragAmount.x / w
                            val dy = dragAmount.y / h
                            val minSpan = 0.14f

                            workingRegion = when (activeHandle) {
                                DragHandle.CENTER -> {
                                    val spanW = workingRegion.right - workingRegion.left
                                    val spanH = workingRegion.bottom - workingRegion.top
                                    val newL = (workingRegion.left + dx).coerceIn(0f, 1f - spanW)
                                    val newT = (workingRegion.top + dy).coerceIn(0f, 1f - spanH)
                                    NormalizedRegion(newL, newT, newL + spanW, newT + spanH)
                                }

                                DragHandle.TOP_LEFT -> workingRegion.copy(
                                    left = (workingRegion.left + dx).coerceIn(0f, workingRegion.right - minSpan),
                                    top = (workingRegion.top + dy).coerceIn(0f, workingRegion.bottom - minSpan)
                                )

                                DragHandle.TOP_RIGHT -> workingRegion.copy(
                                    right = (workingRegion.right + dx).coerceIn(workingRegion.left + minSpan, 1f),
                                    top = (workingRegion.top + dy).coerceIn(0f, workingRegion.bottom - minSpan)
                                )

                                DragHandle.BOTTOM_LEFT -> workingRegion.copy(
                                    left = (workingRegion.left + dx).coerceIn(0f, workingRegion.right - minSpan),
                                    bottom = (workingRegion.bottom + dy).coerceIn(workingRegion.top + minSpan, 1f)
                                )

                                DragHandle.BOTTOM_RIGHT -> workingRegion.copy(
                                    right = (workingRegion.right + dx).coerceIn(workingRegion.left + minSpan, 1f),
                                    bottom = (workingRegion.bottom + dy).coerceIn(workingRegion.top + minSpan, 1f)
                                )

                                DragHandle.NONE -> workingRegion
                            }.clamped()
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val l = workingRegion.left * size.width
                val t = workingRegion.top * size.height
                val w = (workingRegion.right - workingRegion.left) * size.width
                val h = (workingRegion.bottom - workingRegion.top) * size.height

                // Clear cutout highlight inside selected area
                drawRect(
                    color = Color.White.copy(alpha = 0.08f),
                    topLeft = Offset(l, t),
                    size = Size(w, h)
                )

                // Deep Red animated moving dashed border
                drawRect(
                    color = DeepRedBorderColor,
                    topLeft = Offset(l, t),
                    size = Size(w, h),
                    style = Stroke(
                        width = strokePx,
                        pathEffect = PathEffect.dashPathEffect(
                            intervals = floatArrayOf(dashPx, gapPx),
                            phase = -phase
                        )
                    )
                )

                // Corner resize handles
                val corners = listOf(
                    Offset(l, t),
                    Offset(l + w, t),
                    Offset(l, t + h),
                    Offset(l + w, t + h)
                )
                corners.forEach { corner ->
                    drawCircle(color = Color.White, radius = handleRadiusPx, center = corner)
                    drawCircle(color = DeepRedBorderColor, radius = handleRadiusPx * 0.65f, center = corner)
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 28.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Select Recording Region (${pxRegion.width} × ${pxRegion.height} px)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Drag inside the rectangle to move or drag any corner handle to resize. Only this region will be encoded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Text("Cancel", modifier = Modifier.padding(start = 6.dp))
                        }
                        Button(
                            onClick = { onConfirm(workingRegion) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = DeepRedBorderColor)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Text("Confirm Region", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
