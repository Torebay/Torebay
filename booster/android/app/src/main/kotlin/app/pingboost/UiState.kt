package app.pingboost

import app.pingboost.core.GameVariant
import app.pingboost.core.ProbeResult
import app.pingboost.data.Server

enum class Phase { Idle, Connecting, Connected, Disconnecting }

data class UiState(
    val servers: List<Server> = emptyList(),
    /** Последние замеры до каждого сервера (ключ: id сервера). */
    val probes: Map<String, ProbeResult> = emptyMap(),
    val probing: Boolean = false,
    val autoMode: Boolean = true,
    val selectedId: String? = null,

    val phase: Phase = Phase.Idle,
    val activeServerId: String? = null,

    // Живые показатели, пока туннель включён.
    val livePingMs: Double? = null,
    val liveLossPercent: Double = 0.0,
    val liveJitterMs: Double = 0.0,
    val history: List<Double?> = emptyList(),
    val rxBytes: Long = 0,
    val txBytes: Long = 0,

    val installedGames: List<GameVariant> = emptyList(),
    val enabledPackages: Set<String> = emptySet(),

    /** Одноразовое сообщение для показа внизу экрана. */
    val message: String? = null,
) {
    val activeServer: Server? get() = servers.firstOrNull { it.id == activeServerId }
    val selectedServer: Server? get() = servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
}
