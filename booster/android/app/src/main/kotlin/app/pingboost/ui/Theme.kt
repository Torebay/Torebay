package app.pingboost.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import java.util.Locale

val Good = Color(0xFF22E58B)
val Warn = Color(0xFFFFC857)
val Bad = Color(0xFFFF5C5C)
val Muted = Color(0xFF6B7C8F)

private val scheme = darkColorScheme(
    primary = Good,
    onPrimary = Color(0xFF00130A),
    secondary = Color(0xFF56B4FF),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE8EEF5),
    surface = Color(0xFF141B24),
    onSurface = Color(0xFFE8EEF5),
    surfaceVariant = Color(0xFF1B2430),
    onSurfaceVariant = Color(0xFF9DB0C4),
    secondaryContainer = Color(0xFF1F3A33),
    onSecondaryContainer = Good,
    error = Bad,
    outline = Color(0xFF2A3644),
)

@Composable
fun PingBoostTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Цвет по качеству пинга: до 60 мс отлично, до 120 терпимо, дальше плохо. */
fun pingColor(ms: Double?): Color = when {
    ms == null -> Muted
    ms <= 60 -> Good
    ms <= 120 -> Warn
    else -> Bad
}

fun lossColor(percent: Double): Color = when {
    percent < 1.0 -> Good
    percent < 5.0 -> Warn
    else -> Bad
}

fun jitterColor(ms: Double): Color = when {
    ms <= 10 -> Good
    ms <= 30 -> Warn
    else -> Bad
}

fun fmtMs(v: Double?): String = if (v == null) "—" else v.roundToInt().toString()

fun fmtPercent(v: Double): String =
    if (v < 10) String.format(Locale.getDefault(), "%.1f", v) else v.roundToInt().toString()

fun fmtBytes(b: Long): String = when {
    b < 1024 -> "$b Б"
    b < 1024 * 1024 -> "${b / 1024} КБ"
    else -> String.format(Locale.getDefault(), "%.1f МБ", b / 1048576.0)
}
