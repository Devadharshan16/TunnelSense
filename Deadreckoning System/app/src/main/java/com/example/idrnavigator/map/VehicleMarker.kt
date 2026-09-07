package com.example.idrnavigator.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.idrnavigator.fusion.GnssState
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * High-performance Vehicle Marker with independent heading rotation
 * and smooth color transitions representing GNSS deficit states:
 *   - GNSS_ACTIVE:   Cyan (#4FD8E8)
 *   - TRANSITIONING: Amber (#F5A623)
 *   - INS_ONLY:      Electric Violet (#8E6CFF)
 */
class VehicleMarker(private val mapView: MapView) : Marker(mapView) {

    companion object {
        // State colors matching the HUD status pill
        val COLOR_GNSS_ACTIVE: Int = Color.parseColor("#4FD8E8")   // High-confidence GPS fused
        val COLOR_TRANSITIONING: Int = Color.parseColor("#F5A623") // Transitional handoff
        val COLOR_INS_ONLY: Int = Color.parseColor("#7C5CFF")      // Pure INS dead reckoning (Violet)

        private const val COLOR_ANIMATION_DURATION_MS = 400L
    }

    private var currentGnssState: GnssState = GnssState.GNSS_ACTIVE
    private var currentColorInt: Int = COLOR_GNSS_ACTIVE
    private var colorAnimator: ValueAnimator? = null
    private var positionAnimator: ValueAnimator? = null

    // Reusable cached bitmap and canvas to prevent GC pressure
    private val density: Float = mapView.context.resources.displayMetrics.density
    private val iconSize: Int = (44 * density).toInt()
    private val iconBitmap: Bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
    private val iconCanvas: Canvas = Canvas(iconBitmap)
    private val arrowPath: Path = Path()

    // Reusable Paints
    private val haloFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val haloStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val arrowShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(220, 10, 14, 18) // Dark outline for contrast on bright map tiles
    }
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val innerCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 255, 255, 255) // Small white core dot for precision
    }

    init {
        setAnchor(ANCHOR_CENTER, ANCHOR_CENTER)
        setInfoWindow(null)
        buildArrowPath()
        renderIcon(currentColorInt)
        icon = BitmapDrawable(mapView.context.resources, iconBitmap)
    }

    private fun buildArrowPath() {
        val center = iconSize / 2f
        arrowPath.reset()
        // Modern notched navigation chevron
        arrowPath.moveTo(center, center - (15f * density))                 // Tip
        arrowPath.lineTo(center - (11f * density), center + (12f * density)) // Bottom Left
        arrowPath.lineTo(center, center + (7f * density))                  // Inward notch
        arrowPath.lineTo(center + (11f * density), center + (12f * density)) // Bottom Right
        arrowPath.close()
    }

    private fun renderIcon(colorInt: Int) {
        // Clear previous frame
        iconBitmap.eraseColor(Color.TRANSPARENT)

        val center = iconSize / 2f
        val r = Color.red(colorInt)
        val g = Color.green(colorInt)
        val b = Color.blue(colorInt)

        // 1. Soft pulsing halo
        haloFillPaint.color = Color.argb(40, r, g, b)
        iconCanvas.drawCircle(center, center, center - (2f * density), haloFillPaint)

        haloStrokePaint.color = Color.argb(90, r, g, b)
        iconCanvas.drawCircle(center, center, center - (2f * density), haloStrokePaint)

        // 2. High-contrast dark shadow outline (ensures visibility on both light and dark tiles)
        iconCanvas.drawPath(arrowPath, arrowShadowPaint)

        // 3. Vibrant state-colored arrow fill
        arrowFillPaint.color = colorInt
        iconCanvas.drawPath(arrowPath, arrowFillPaint)

        // 4. Fine white center guidance pip
        iconCanvas.drawCircle(center, center + (3f * density), 2.0f * density, innerCorePaint)
    }

    /**
     * Smoothly animate the marker's color when the GNSS deficit state changes.
     */
    fun updateState(newState: GnssState) {
        if (newState == currentGnssState) return
        currentGnssState = newState

        val targetColor = when (newState) {
            GnssState.GNSS_ACTIVE -> COLOR_GNSS_ACTIVE
            GnssState.TRANSITIONING -> COLOR_TRANSITIONING
            GnssState.INS_ONLY -> COLOR_INS_ONLY
        }

        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofArgb(currentColorInt, targetColor).apply {
            duration = COLOR_ANIMATION_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                currentColorInt = animator.animatedValue as Int
                renderIcon(currentColorInt)
                mapView.invalidate()
            }
            start()
        }
    }

    /**
     * Update location and heading rotation independently of color animation.
     */
    fun updatePositionAndHeading(newGeoPoint: GeoPoint, newHeading: Float): Boolean {
        if (position.latitude == newGeoPoint.latitude &&
            position.longitude == newGeoPoint.longitude &&
            rotation == newHeading
        ) {
            return false
        }
        rotation = newHeading

        val startPoint = position
        if (startPoint.latitude == 0.0 && startPoint.longitude == 0.0) {
            position = newGeoPoint
            return true
        }

        positionAnimator?.cancel()
        positionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                position = GeoPoint(
                    startPoint.latitude + (newGeoPoint.latitude - startPoint.latitude) * progress,
                    startPoint.longitude + (newGeoPoint.longitude - startPoint.longitude) * progress
                )
                mapView.invalidate()
            }
            start()
        }
        return true
    }
}
