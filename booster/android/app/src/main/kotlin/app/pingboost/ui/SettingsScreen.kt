package app.pingboost.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pingboost.Phase
import app.pingboost.UiState
import app.pingboost.core.PubgGames

@Composable
fun SettingsScreen(
    ui: UiState,
    onGameToggle: (packageName: String, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val idle = ui.phase == Phase.Idle

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Настройки", fontSize = 26.sp, fontWeight = FontWeight.Bold)

        AppCard {
            Text("Какая игра идёт через ускоритель", fontWeight = FontWeight.SemiBold)
            Text(
                "Только отмеченные приложения. Всё остальное на телефоне (мессенджеры, браузер, YouTube) " +
                    "работает напрямую и не тормозится.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            if (ui.installedGames.isEmpty()) {
                Text("Ни одна версия PUBG на телефоне не найдена.", color = Warn)
            }
            ui.installedGames.forEach { game ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = game.packageName in ui.enabledPackages,
                        onCheckedChange = { onGameToggle(game.packageName, it) },
                        enabled = idle,
                    )
                    Column {
                        Text(game.label)
                        Text(game.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (!idle) {
                Text(
                    "Менять можно, пока ускорение выключено.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val missing = PubgGames.all.size - ui.installedGames.size
            if (missing > 0 && ui.installedGames.isNotEmpty()) {
                Text(
                    "Остальные $missing версий не установлены и не показаны.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        AppCard {
            Text("Чтобы ускорение не отваливалось в игре", fontWeight = FontWeight.SemiBold)
            Text(
                "Некоторые телефоны (Xiaomi, Samsung, Huawei) убивают фоновые приложения для экономии батареи. " +
                    "Разреши PingBoost работать без ограничений, иначе туннель может оборваться посреди матча.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Открыть настройки батареи") }
        }

        AppCard {
            Text("Как это работает", fontWeight = FontWeight.SemiBold)
            Text(
                "Трафик игры шифруется WireGuard и уходит на твой сервер, а оттуда к серверам PUBG. " +
                    "Если маршрут от сервера до игры лучше, чем напрямую от твоего интернета, пинг падает, " +
                    "а потерь пакетов становится меньше. Если нет, выбери другой сервер или выключи ускорение.\n\n" +
                    "Показанный пинг — это путь до твоего сервера. До самой игры он будет немного больше.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
