package app.pingboost.core

object ServerPicker {
    /** Миллисекунд штрафа за каждый процент потерь: для игры потери хуже, чем лишние мс. */
    private const val LOSS_PENALTY_MS = 10.0

    /** Джиттер ломает «резкость» стрельбы сильнее, чем стабильно высокий пинг. */
    private const val JITTER_WEIGHT = 2.0

    /** Чем меньше, тем лучше. У недоступного сервера бесконечность. */
    fun score(result: ProbeResult): Double {
        val avg = result.avgMs ?: return Double.POSITIVE_INFINITY
        return avg + JITTER_WEIGHT * result.jitterMs + LOSS_PENALTY_MS * result.lossPercent
    }

    /** Ключ лучшего из доступных серверов или null, если не отвечает ни один. */
    fun <K> best(results: Map<K, ProbeResult>): K? =
        results.filterValues { it.reachable }.minByOrNull { score(it.value) }?.key
}
