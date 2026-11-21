package app.pingboost.core

import kotlin.math.abs

/**
 * Итог серии замеров до одного сервера.
 *
 * [sent] сколько пакетов отправили, [rtts] круговые задержки (мс) только для тех, на которые
 * пришёл ответ, в порядке прихода. [error] заполнено, если замер вообще не удалось провести
 * (например, не резолвится имя сервера).
 */
data class ProbeResult(
    val sent: Int,
    val rtts: List<Double>,
    val error: String? = null,
) {
    val received: Int get() = rtts.size

    val reachable: Boolean get() = rtts.isNotEmpty()

    val lossPercent: Double
        get() = if (sent <= 0) 100.0 else (sent - received) * 100.0 / sent

    val avgMs: Double? get() = if (rtts.isEmpty()) null else rtts.average()

    val minMs: Double? get() = rtts.minOrNull()

    val maxMs: Double? get() = rtts.maxOrNull()

    /** Джиттер: средняя разница между соседними замерами (как в RFC 3550, без сглаживания). */
    val jitterMs: Double
        get() {
            if (rtts.size < 2) return 0.0
            var sum = 0.0
            for (i in 1 until rtts.size) sum += abs(rtts[i] - rtts[i - 1])
            return sum / (rtts.size - 1)
        }

    companion object {
        val EMPTY = ProbeResult(sent = 0, rtts = emptyList())
    }
}

/** Скользящее окно живых замеров. `null` в окне означает потерянный пакет. */
class RollingProbeWindow(private val capacity: Int = 60) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val samples = ArrayDeque<Double?>()

    @Synchronized
    fun add(rttMs: Double?) {
        samples.addLast(rttMs)
        while (samples.size > capacity) samples.removeFirst()
    }

    @Synchronized
    fun clear() = samples.clear()

    @Synchronized
    fun samples(): List<Double?> = samples.toList()

    @Synchronized
    fun last(): Double? = samples.lastOrNull()

    /** Статистика по последним [lastN] замерам (по умолчанию по всему окну). */
    @Synchronized
    fun snapshot(lastN: Int = capacity): ProbeResult {
        val tail = samples.toList().takeLast(lastN)
        return ProbeResult(sent = tail.size, rtts = tail.filterNotNull())
    }
}
