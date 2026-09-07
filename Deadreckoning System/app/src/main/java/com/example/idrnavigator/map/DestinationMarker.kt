package com.example.idrnavigator.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class DestinationMarker(mapView: MapView) : Marker(mapView) {
    private val density = mapView.context.resources.displayMetrics.density
    private val size = (42 * density).toInt()
    private val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinPath = Path()

    init {
        setAnchor(ANCHOR_CENTER, ANCHOR_BOTTOM)
        setInfoWindow(null)
        val center = size / 2f
        pinPath.moveTo(center, size - 2f * density)
        pinPath.cubicTo(5f * density, center + 7f * density, 8f * density, 7f * density, center, 7f * density)
        pinPath.cubicTo(size - 8f * density, 7f * density, size - 5f * density, center + 7f * density, center, size - 2f * density)
        paint.color = Color.rgb(244, 104, 74)
        paint.style = Paint.Style.FILL
        canvas.drawPath(pinPath, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, 4f * density, paint)
        icon = BitmapDrawable(mapView.context.resources, bitmap)
    }

    fun setDestination(point: GeoPoint) {
        position = point
        setVisible(true)
    }
}
