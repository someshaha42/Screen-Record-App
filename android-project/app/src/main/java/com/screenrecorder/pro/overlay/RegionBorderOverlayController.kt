package com.screenrecorder.pro.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.screenrecorder.pro.model.NormalizedRegion
import com.screenrecorder.pro.util.DisplayMetricsHelper

/**
 * Optional floating deep-red animated dashed indicator for Region Recording.
 * Only shown when SYSTEM_ALERT_WINDOW has been voluntarily granted by the user.
 *
 * The border stroke is offset outside the cropped capture bounds so that the
 * region encoder (which crops strictly inside [left..right, top..bottom]) never
 * captures the red border pixels into the output video.
 */
class RegionBorderOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: AnimatedRegionBorderView? = null
    private var activeRegion: NormalizedRegion? = null

    fun showIfPermitted(region: NormalizedRegion): Boolean {
        activeRegion = region.clamped()
        if (!Settings.canDrawOverlays(context)) {
            return false
        }

        dismiss()

        val view = AnimatedRegionBorderView(context, activeRegion!!)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        return runCatching {
            windowManager.addView(view, params)
            overlayView = view
            true
        }.getOrDefault(false)
    }

    fun updateRegion(region: NormalizedRegion) {
        activeRegion = region.clamped()
        overlayView?.updateRegion(activeRegion!!)
    }

    fun dismiss() {
        val view = overlayView ?: return
        overlayView = null
        view.stopAnimation()
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private class AnimatedRegionBorderView(
        context: Context,
        private var region: NormalizedRegion
    ) : View(context) {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val strokePx = 3f * resources.displayMetrics.density
        private val dashLen = 14f * resources.displayMetrics.density
        private val gapLen = 9f * resources.displayMetrics.density
        private var phaseOffset = 0f
        private var animating = true

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B91C1C")
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }

        private val drawRect = RectF()

        private val tickRunnable = object : Runnable {
            override fun run() {
                if (!animating) return
                phaseOffset = (phaseOffset + 1.5f * resources.displayMetrics.density) % (dashLen + gapLen)
                borderPaint.pathEffect = DashPathEffect(floatArrayOf(dashLen, gapLen), -phaseOffset)
                invalidate()
                mainHandler.postDelayed(this, 16L)
            }
        }

        init {
            mainHandler.post(tickRunnable)
        }

        fun updateRegion(newRegion: NormalizedRegion) {
            region = newRegion
            invalidate()
        }

        fun stopAnimation() {
            animating = false
            mainHandler.removeCallbacks(tickRunnable)
        }

        override fun onDetachedFromWindow() {
            stopAnimation()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val snap = DisplayMetricsHelper.getCurrentDisplaySnapshot(context)
            val px = DisplayMetricsHelper.toPixelRegion(region, snap.width, snap.height)

            // Offset outward by half the stroke width + 2px margin so the stroke stays strictly
            // outside the cropped capture bounds [px.left, px.top, px.right, px.bottom].
            val outwardOffset = (strokePx / 2f) + 2f
            drawRect.set(
                (px.left - outwardOffset).coerceAtLeast(strokePx / 2f),
                (px.top - outwardOffset).coerceAtLeast(strokePx / 2f),
                (px.right + outwardOffset).coerceAtMost(width - strokePx / 2f),
                (px.bottom + outwardOffset).coerceAtMost(height - strokePx / 2f)
            )
            canvas.drawRect(drawRect, borderPaint)
        }
    }
}
