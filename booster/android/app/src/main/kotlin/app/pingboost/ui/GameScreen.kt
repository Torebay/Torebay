package app.pingboost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pingboost.GameState
import app.pingboost.core.Check as DeviceCheck
import app.pingboost.core.Level
import app.pingboost.core.Verdict

@Composable
fun GameScreen(
    game: GameState,
    onPlay: () -> Unit,
    onDnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text("Игра", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Что мешает FPS на этом телефоне, и как настроить PUBG",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        VerdictCard(game, onPlay)

        AppCard {
            Text("Проверка телефона", fontWeight = FontWeight.SemiBold)
            Text(
                "Данные снимаются с телефона прямо сейчас и обновляются сами.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            game.checks.forEachIndexed { i, check ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                CheckRow(check)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDnd, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        !game.dndAccess -> "Разрешить управление «Не беспокоить»"
                        game.dndOn -> "Выключить «Не беспокоить»"
                        else -> "Включить «Не беспокоить» на время игры"
                    },
                )
            }
        }

        InfoCard(
            "Настройки FPS в PUBG Mobile",
            listOf(
                "Настройки → Графика. Качество поставь «Плавная»: именно на ней обычно открываются самые высокие частоты кадров.",
                "Частота кадров: выбери максимальную из доступных. Если нужного значения (120 или 144) в списке нет, игра не поддерживает твою модель. Это ограничение игры, его нельзя обойти.",
                "По желанию отключи тени и сглаживание: добавляет кадров на слабых телефонах.",
                "После смены графики перезапусти игру.",
                "У Vivo и iQOO есть игровой режим оболочки («Игровой куб», «Ультра игровой режим» или «Режим монстра», название зависит от модели). Включи в нём высокую производительность.",
                "Не играй с зарядкой и толстым чехлом: перегрев режет FPS сильнее любых настроек.",
            ),
        )

        InfoCard(
            "Прицел и чувствительность",
            listOf(
                "Гироскоп: Настройки → Чувствительность → Гироскоп. Включи его при прицеливании: мелкие подруливания телефоном точнее, чем пальцем. Названия пунктов могут отличаться по версии игры.",
                "Чувствительность строится «лесенкой»: чем больше кратность прицела, тем она ниже. Стартовая точка из типичных пресетов (проценты слайдера): без прицела ~100, красная точка ~50, 2× ~35, 3× ~28, 4× ~22, 6× ~15. Это не готовый ответ, а отправная точка.",
                "Подстраивай по 3–5% за раз. Перелетаешь цель: снижай. Не успеваешь довернуть: повышай.",
                "Проверяй в режиме «Тренировочная площадка»: 10 минут по мишеням на разной дистанции.",
                "Не меняй настройки каждый день: на привыкание уходит 1–2 недели.",
                "Перенести настройки между устройствами можно кодом чувствительности (если такая кнопка есть в твоей версии игры).",
            ),
        )

        InfoCard(
            "iPad",
            listOf(
                "На iPad это приложение не установить: Apple требует Mac и платный аккаунт разработчика.",
                "Используй те же настройки внутри игры. На iPad с экраном 120 Гц в списке частот кадров должно быть 120. Если его нет, этот iPad или версия игры его не поддерживают.",
            ),
        )

        AppCard {
            Text("Чего приложение не делает", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "• Не поднимает FPS выше предела экрана и процессора. Его задают телефон и игра.\n" +
                    "• Не «чистит память» кнопкой: на Android это почти не добавляет кадров, зато игра дольше грузится.\n" +
                    "• Не наводит прицел и не помогает попадать в голову. Это чит, за него банят аккаунт навсегда.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VerdictCard(game: GameState, onPlay: () -> Unit) {
    val (title, color) = when (game.verdict) {
        Verdict.Ready -> "Телефон готов к игре" to Good
        Verdict.AlmostReady -> "Почти готово" to Warn
        Verdict.Problems -> "Есть проблемы, которые срежут FPS" to Bad
    }
    AppCard {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(
            "Экран позволяет до ${game.fpsCap} FPS",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        Button(onClick = onPlay, enabled = game.launchPackage != null, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (game.launchPackage != null) "Запустить PUBG Mobile" else "PUBG Mobile не найден")
        }
    }
}

@Composable
private fun CheckRow(check: DeviceCheck) {
    val color: Color = when (check.level) {
        Level.Ok -> Good
        Level.Warn -> Warn
        Level.Bad -> Bad
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (check.level == Level.Ok) Icons.Default.Check else Icons.Default.Warning,
            contentDescription = null,
            tint = color,
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(check.title, fontWeight = FontWeight.Medium)
            Text(
                check.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, points: List<String>) {
    AppCard {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        points.forEachIndexed { i, text ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Row {
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
