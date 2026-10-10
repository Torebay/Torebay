package uz.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.*

enum class HudState { IDLE, LISTENING, THINKING, SPEAKING }

/** Green command-center globe matching the Windows dashboard. */
class HudView(context: Context) : View(context) {
    var state = HudState.IDLE
        set(value) { field = value; invalidate() }
    var title = "KARTAL"
    var status = "ожидание"
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbit = RectF()
    private val path = Path()

    init {
        setBackgroundColor(Color.rgb(2, 20, 6))
        contentDescription = "Глобус Kartal. Нажмите, чтобы говорить"
        isClickable = true
        isFocusable = true
    }

    private fun color(rgb: Int, alpha: Int = 255) = (alpha shl 24) or rgb

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis() / 1000.0
        val cx = width / 2f
        val cy = height * 0.45f
        val radius = min(width * 0.34f, height * 0.32f)
        val density = resources.displayMetrics.density
        val rgb = when (state) {
            HudState.IDLE -> 0x17A85C
            HudState.LISTENING -> 0x22E07C
            HudState.THINKING -> 0xE53935
            HudState.SPEAKING -> 0x2F9BFF
        }
        pen.style = Paint.Style.STROKE
        pen.strokeWidth = density * 0.5f
        pen.color = color(0x0D501F, 90)
        val step = max(18f * density, 1f)
        var grid = 0f
        while (grid < width) { canvas.drawLine(grid, 0f, grid, height.toFloat(), pen); grid += step }
        grid = 0f
        while (grid < height) { canvas.drawLine(0f, grid, width.toFloat(), grid, pen); grid += step }
        pen.style = Paint.Style.FILL
        for (i in 7 downTo 1) {
            pen.color = color(rgb, 4 + (7 - i) * 2)
            canvas.drawCircle(cx, cy, radius * (1f + i * 0.055f), pen)
        }
        pen.style = Paint.Style.STROKE
        pen.color = color(rgb, 110)
        pen.strokeWidth = density
        orbit.set(cx - radius * 1.22f, cy - radius * 1.22f, cx + radius * 1.22f, cy + radius * 1.22f)
        canvas.drawArc(orbit, (now * 12 % 360).toFloat(), 125f, false, pen)
        canvas.drawArc(orbit, (now * 12 % 360 + 180).toFloat(), 125f, false, pen)
        for (i in 0 until 60) {
            val a = i * PI / 30
            val inner = radius * if (i % 5 == 0) 1.28f else 1.31f
            canvas.drawLine(cx + (cos(a) * inner).toFloat(), cy + (sin(a) * inner).toFloat(),
                cx + (cos(a) * radius * 1.35).toFloat(), cy + (sin(a) * radius * 1.35).toFloat(), pen)
        }
        // Rotating longitude/latitude mesh with depth-dependent brightness.
        val rotation = now * if (state == HudState.THINKING) 0.65 else 0.18
        fun project(lat: Double, lon: Double): Triple<Float, Float, Double> {
            val x = cos(lat) * sin(lon + rotation)
            val z = cos(lat) * cos(lon + rotation)
            val y = sin(lat)
            return Triple(cx + (radius * x).toFloat(), cy - (radius * (y * 0.94 + z * 0.25)).toFloat(), z)
        }
        fun mesh(points: List<Triple<Float, Float, Double>>) {
            points.zipWithNext().forEach { (a, b) ->
                pen.color = color(rgb, if ((a.third + b.third) / 2 > 0) 165 else 35)
                canvas.drawLine(a.first, a.second, b.first, b.second, pen)
            }
        }
        for (lat in -60..60 step 20) mesh((0..72).map { project(lat * PI / 180, it * PI / 36) })
        for (lon in 0 until 360 step 20) mesh((0..36).map { project(-PI / 2 + it * PI / 36, lon * PI / 180) })
        pen.style = Paint.Style.FILL
        for (i in 0 until 75) {
            val lat = asin(2.0 * (i + 0.5) / 75 - 1)
            val point = project(lat, i * PI * (3 - sqrt(5.0)))
            if (point.third > 0) {
                pen.color = color(0xA8FFD0, 210)
                canvas.drawCircle(point.first, point.second, density * 1.4f, pen)
            }
        }
        pen.textAlign = Paint.Align.CENTER
        pen.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        pen.textSize = 18 * density
        pen.color = color(0xD4F7E2)
        canvas.drawText(title.uppercase(), cx, height - 47 * density, pen)
        pen.textSize = 11 * density
        pen.color = color(rgb)
        canvas.drawText(status.uppercase(), cx, height - 26 * density, pen)
        if (isShown && windowVisibility == VISIBLE) postInvalidateDelayed(33)
    }
}
