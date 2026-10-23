package com.atomkarnickel.overhead.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.atomkarnickel.overhead.domain.Aircraft
import com.atomkarnickel.overhead.domain.Geo
import com.atomkarnickel.overhead.domain.RadarProjection
import com.atomkarnickel.overhead.domain.Snapshot
import com.atomkarnickel.overhead.domain.WidgetModelBuilder

/** Zeichnet das Mini-Radar fürs Widget: Norden oben, du in der Mitte, Rand = Suchradius. */
object RadarRenderer {
    private const val LOW = 0xFFF2B84B.toInt() // unter 3 000 m
    private const val MID = 0xFF5AD8CC.toInt() // 3 000–8 000 m
    private const val HIGH = 0xFF4F97F0.toInt() // ab 8 000 m
    private const val ALERT = 0xFFFF5A4F.toInt()

    fun colorFor(a: Aircraft): Int {
        val r = a.raw
        if (r.emergency != null || r.squawk in WidgetModelBuilder.EMERGENCY_SQUAWKS) return ALERT
        val m = (r.altitudeFt ?: 0) * Geo.M_PER_FT
        return when {
            r.onGround -> Color.GRAY
            m < 3000 -> LOW
            m < 8000 -> MID
            else -> HIGH
        }
    }

    fun render(snapshot: Snapshot, sizePx: Int, dark: Boolean, maxPlanes: Int = 40): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = sizePx.toFloat()
        val center = s / 2f
        val pad = s * 0.06f
        val outer = center - pad
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s / 90f }

        fill.color = if (dark) 0xFF0B1A33.toInt() else 0xFFE8EEF7.toInt()
        c.drawCircle(center, center, outer, fill)
        stroke.color = if (dark) 0xFF2F5A93.toInt() else 0xFFB8C7DC.toInt()
        c.drawCircle(center, center, outer, stroke)
        c.drawCircle(center, center, outer / 2f, stroke)
        c.drawLine(center, pad, center, s - pad, stroke)
        c.drawLine(pad, center, s - pad, center, stroke)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFF9FB3D1.toInt() else 0xFF4F6380.toInt()
            textSize = s * 0.09f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        c.drawText("N", center, pad + text.textSize * 1.05f, text)

        val radius = snapshot.radiusKm.toDouble()
        val planeSize = s * 0.055f
        // Entfernte zuerst zeichnen, damit nahe Flugzeuge oben liegen.
        snapshot.aircraft.take(maxPlanes).sortedByDescending { it.distanceKm }.forEach { a ->
            val p = RadarProjection.project(a.bearingDeg, a.distanceKm, radius, s, pad)
            fill.color = colorFor(a)
            val track = a.raw.trackDeg
            if (track == null) {
                c.drawCircle(p.x, p.y, planeSize * 0.45f, fill)
            } else {
                c.save()
                c.rotate(track.toFloat(), p.x, p.y)
                val path = Path().apply {
                    moveTo(p.x, p.y - planeSize)
                    lineTo(p.x + planeSize * 0.6f, p.y + planeSize * 0.7f)
                    lineTo(p.x, p.y + planeSize * 0.35f)
                    lineTo(p.x - planeSize * 0.6f, p.y + planeSize * 0.7f)
                    close()
                }
                c.drawPath(path, fill)
                c.restore()
            }
        }

        fill.color = 0xFF5AD8CC.toInt()
        c.drawCircle(center, center, s * 0.03f, fill)
        return bmp
    }
}
