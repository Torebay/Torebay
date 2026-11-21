package app.pingboost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pingboost.Phase
import app.pingboost.UiState

@Composable
fun HomeScreen(
    ui: UiState,
    onToggle: () -> Unit,
    onAutoChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text("PingBoost", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Ускоритель сети для PUBG Mobile",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(6.dp))
        ConnectButton(ui.phase, onToggle)
        Text(statusText(ui), color = statusColor(ui), fontWeight = FontWeight.Medium)

        ServerCard(ui, onAutoChange, onRefresh)
        StatsRow(ui)
        ChartCard(ui)
        GamesCard(ui)
        Spacer(Modifier.height(8.dp))
    }
}

private fun statusText(ui: UiState): String = when (ui.phase) {
    Phase.Idle -> if (ui.probing) "Проверяю серверы…" else "Ускорение выключено"
    Phase.Connecting -> if (ui.probing) "Выбираю лучший сервер…" else "Подключаюсь…"
    Phase.Connected -> "Ускорение включено" + (ui.activeServer?.let { " · ${it.name}" } ?: "")
    Phase.Disconnecting -> "Отключаюсь…"
}

@Composable
private fun statusColor(ui: UiState): Color =
    if (ui.phase == Phase.Connected) Good else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun ConnectButton(phase: Phase, onClick: () -> Unit) {
    val connected = phase == Phase.Connected
    val busy = phase == Phase.Connecting || phase == Phase.Disconnecting
    val ring = if (connected) Good else MaterialTheme.colorScheme.outline

    Box(
        Modifier
            .size(188.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(5.dp, ring, CircleShape)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = Good)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (connected) "ВКЛЮЧЕНО" else "ЗАПУСК",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (connected) Good else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (connected) "нажми, чтобы выключить" else "нажми, чтобы ускорить",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ServerCard(ui: UiState, onAutoChange: (Boolean) -> Unit, onRefresh: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val shown = if (ui.phase == Phase.Connected) ui.activeServer else ui.selectedServer
                Text(
                    when {
                        ui.servers.isEmpty() -> "Серверов нет"
                        ui.autoMode && ui.phase != Phase.Connected -> "Авто: лучший по пингу"
                        else -> shown?.name ?: "—"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        ui.servers.isEmpty() -> "Добавь свой сервер на вкладке «Серверы»"
                        ui.autoMode && ui.phase != Phase.Connected -> "Перед запуском замерю все ${ui.servers.size}"
                        else -> shown?.host ?: ""
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onRefresh, enabled = !ui.probing && ui.servers.isNotEmpty()) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить пинг серверов")
            }
            Text("Авто", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 6.dp))
            Switch(
                checked = ui.autoMode,
                onCheckedChange = onAutoChange,
                enabled = ui.phase == Phase.Idle && ui.servers.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun StatsRow(ui: UiState) {
    val connected = ui.phase == Phase.Connected
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            "ПИНГ", if (connected) fmtMs(ui.livePingMs) else "—", "мс",
            if (connected) pingColor(ui.livePingMs) else Muted, Modifier.weight(1f),
        )
        StatTile(
            "ПОТЕРИ", if (connected) fmtPercent(ui.liveLossPercent) else "—", "%",
            if (connected) lossColor(ui.liveLossPercent) else Muted, Modifier.weight(1f),
        )
        StatTile(
            "ДЖИТТЕР", if (connected) fmtMs(ui.liveJitterMs) else "—", "мс",
            if (connected) jitterColor(ui.liveJitterMs) else Muted, Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, color: Color, modifier: Modifier) {
    Card(
        modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
                Spacer(Modifier.width(3.dp))
                Text(
                    unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun ChartCard(ui: UiState) {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Пинг за минуту", fontWeight = FontWeight.SemiBold)
            if (ui.phase == Phase.Connected) {
                Text(
                    "↓ ${fmtBytes(ui.rxBytes)}   ↑ ${fmtBytes(ui.txBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        PingChart(ui.history, Modifier.fillMaxWidth().height(110.dp))
        if (ui.phase != Phase.Connected) {
            Text(
                "График появится после запуска. Показывает путь от телефона до твоего сервера.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GamesCard(ui: UiState) {
    val active = ui.installedGames.filter { it.packageName in ui.enabledPackages }
    AppCard {
        if (active.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Warn)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("PUBG Mobile не найден", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (ui.installedGames.isEmpty()) "Установи игру. Через туннель идёт только она."
                        else "Отметь нужную версию в настройках.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Text("Через ускоритель идёт только игра", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            active.forEach {
                Text("• ${it.label}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Остальные приложения работают как обычно, напрямую.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}
