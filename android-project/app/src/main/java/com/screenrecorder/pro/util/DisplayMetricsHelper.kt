package com.screenrecorder.pro.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.screenrecorder.pro.model.NormalizedRegion
import com.screenrecorder.pro.model.PixelRegion
import com.screenrecorder.pro.model.StorageInfo
import kotlin.math.roundToInt

data class DisplaySnapshot(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int
)

object DisplayMetricsHelper {
    fun getCurrentDisplaySnapshot(context: Context): DisplaySnapshot {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val bounds = windowManager.currentWindowMetrics.bounds
        val rotation = runCatching { context.display.rotation }.getOrDefault(Surface.ROTATION_0)
        val metrics = context.resources.displayMetrics
        val densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_HIGH

        // Hardware AVC encoders require even frame dimensions
        val evenWidth = (bounds.width() and 1.inv()).coerceAtLeast(2)
        val evenHeight = (bounds.height() and 1.inv()).coerceAtLeast(2)

        return DisplaySnapshot(
            width = evenWidth,
            height = evenHeight,
            densityDpi = densityDpi,
            rotation = rotation
        )
    }

    fun toPixelRegion(
        normalized: NormalizedRegion,
        displayWidth: Int,
        displayHeight: Int
    ): PixelRegion {
        val safe = normalized.clamped()
        val rawLeft = (safe.left * displayWidth).roundToInt().coerceIn(0, displayWidth - 64)
        val rawTop = (safe.top * displayHeight).roundToInt().coerceIn(0, displayHeight - 64)
        val rawRight = (safe.right * displayWidth).roundToInt().coerceIn(rawLeft + 64, displayWidth)
        val rawBottom = (safe.bottom * displayHeight).roundToInt().coerceIn(rawTop + 64, displayHeight)

        // Align to even dimensions for YUV420 hardware AVC encoder compatibility
        val evenLeft = rawLeft and 1.inv()
        val evenTop = rawTop and 1.inv()
        val evenWidth = ((rawRight - evenLeft) and 1.inv()).coerceAtLeast(64)
        val evenHeight = ((rawBottom - evenTop) and 1.inv()).coerceAtLeast(64)

        val clampedWidth = evenWidth.coerceAtMost((displayWidth - evenLeft) and 1.inv())
        val clampedHeight = evenHeight.coerceAtMost((displayHeight - evenTop) and 1.inv())

        return PixelRegion(
            left = evenLeft,
            top = evenTop,
            width = clampedWidth.coerceAtLeast(64),
            height = clampedHeight.coerceAtLeast(64),
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
    }

    fun getAvailableStorageInfo(): StorageInfo {
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val stat = StatFs(path.absolutePath)
        return StorageInfo(
            availableBytes = stat.availableBytes,
            totalBytes = stat.totalBytes
        )
    }
}
