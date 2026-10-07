package uz.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class HudState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Анимированное кольцо, как окно ассистента на компьютере (assistant/ui.py):
 * медленно вращается в ожидании, ярче светится, когда слушает, становится
 * оранжевым, когда думает, и «звучит» волной, когда говорит.
 */
class HudView(context: Context) : View(context) {
    var state = HudState.IDLE
        set(value) { field = value; invalidate() }
    var title = "KARTAL"
    var status = ""

    // Состояние → (цвет, скорость вращения, сила пульса)
    private val styles = mapOf(
        HudState.IDLE to Triple(0x00B4D8, 0.4f, 0.15f),
        HudState.LISTENING to Triple(0x00F0FF, 1.0f, 0.45f),
        HudState.THINKING to Triple(0xFFB703, 2.6f, 0.25f),
        HudState.SPEAKING to Triple(0x4CC9F0, 0.8f, 1.0f),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var angle = 0f
    private var level = 0f

    init { setBackgroundColor(Color.rgb(3, 8, 15)) }

    /** Делает цвет темнее (factor < 1) или светлее (factor > 1). */
    private fun mix(rgb: Int, factor: Float): Int {
        var r = (rgb shr 16) and 0xFF
        var g = (rgb shr 8) and 0xFF
        var b = rgb and 0xFF
        if (factor <= 1f) {
            r = (r * factor).toInt(); g = (g * factor).toInt(); b = (b * factor).toInt()
        } else {
            val k = min(factor - 1f, 1f)
            r = (r + (255 - r) * k).toInt(); g = (g + (255 - g) * k).toInt(); b = (b + (255 - b) * k).toInt()
        }
        return Color.rgb(r, g, b)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (color, speed, pulsePower) = styles.getValue(state)
        val now = SystemClock.uptimeMillis() / 1000f
        angle = (angle + speed * 2.2f) % 360f
        val cx = width / 2f
        val cy = height * 0.42f
        val s = min(width, height) / 460f  // размеры как в окне 460 px на компьютере

        val target = when (state) {
            HudState.SPEAKING -> Random.nextFloat() * 0.65f + 0.35f
            HudState.LISTENING -> 0.35f + 0.25f * sin(now * 5)
            else -> 0f
        }
        level += (target - level) * 0.3f
        val pulse = (sin(now * (2 + speed * 2)) + 1) / 2 * pulsePower + level * 0.6f

        // Мягкое свечение вокруг ядра.
        paint.style = Paint.Style.FILL
        for (i in 8 downTo 1) {
            val r = (70 + i * 9 + pulse * 18) * s
            paint.color = mix(color, 0.03f + 0.012f * (9 - i))
            canvas.drawCircle(cx, cy, r, paint)
        }

        // Внешнее кольцо из делений.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * s
        paint.color = mix(color, 0.55f)
        for (i in 0 until 72) {
            val a = Math.toRadians((i * 5 + angle * 0.3f).toDouble())
            val r1 = 200 * s
            val r2 = (200 - if (i % 6 == 0) 12 else 6) * s
            canvas.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), paint)
        }

        // Вращающиеся дуги в разные стороны.
        paint.color = mix(color, 0.9f)
        val arcs = listOf(Triple(180f, 8f, 1.0f to 4), Triple(160f, 5f, -1.6f to 3), Triple(138f, 3f, 2.3f to 6))
        for ((radius, w, dirCount) in arcs) {
            val (direction, count) = dirCount
            val step = 360f / count
            paint.strokeWidth = w * s
            oval.set(cx - radius * s, cy - radius * s, cx + radius * s, cy + radius * s)
            for (k in 0 until count) {
                canvas.drawArc(oval, -(angle * direction + k * step), -step * 0.55f, false, paint)
            }
        }

        // Звуковая «волна» вокруг ядра, когда ассистент говорит или слушает.
        if (level > 0.05f) {
            paint.strokeWidth = 3 * s
            paint.color = mix(color, 1.2f)
            for (i in 0 until 48) {
                val a = Math.toRadians(i * 7.5)
                val h = (6 + level * (Random.nextFloat() * 26 + 8)) * s
                val r1 = 104 * s
                canvas.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(),
                    cx + (r1 + h) * cos(a).toFloat(), cy + (r1 + h) * sin(a).toFloat(), paint)
            }
        }

        // Ядро.
        val core = (62 + pulse * 14) * s
        paint.style = Paint.Style.FILL
        paint.color = mix(color, 0.18f)
        canvas.drawCircle(cx, cy, core, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * s
        paint.color = mix(color, 1.4f)
        canvas.drawCircle(cx, cy, core, paint)
        paint.strokeWidth = 2 * s
        paint.color = mix(color, 1.1f)
        canvas.drawCircle(cx, cy, core * 0.62f, paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 22 * s
        paint.color = mix(color, 1.6f)
        canvas.drawText(title.uppercase(), cx, cy, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 13 * s
        paint.color = mix(color, 1.0f)
        canvas.drawText(status, cx, cy + 26 * s, paint)

        postInvalidateOnAnimation()
    }
}
