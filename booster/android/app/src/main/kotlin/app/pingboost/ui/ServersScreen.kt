package app.pingboost.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pingboost.Phase
import app.pingboost.UiState
import app.pingboost.core.ProbeResult
import app.pingboost.data.Server

@Composable
fun ServersScreen(
    ui: UiState,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onAdd: (name: String, config: String) -> String?,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Server?>(null) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Серверы", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Твои узлы: игра идёт через тот, где пинг лучше.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onRefresh, enabled = !ui.probing && ui.servers.isNotEmpty()) {
                Icon(Icons.Default.Refresh, contentDescription = "Замерить пинг")
            }
        }

        if (ui.servers.isEmpty()) {
            AppCard {
                Text("Пока пусто", fontWeight = FontWeight.SemiBold)
                Text(
                    "Установи WireGuard на свой сервер скриптом из папки booster/server " +
                        "(инструкция в booster/README.md), скопируй выданный конфиг и добавь его кнопкой ниже.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        ui.servers.forEach { server ->
            ServerRow(
                server = server,
                probe = ui.probes[server.id],
                probing = ui.probing && ui.probes[server.id] == null,
                chosen = !ui.autoMode && ui.selectedId == server.id,
                active = ui.phase != Phase.Idle && ui.activeServerId == server.id,
                onClick = { onSelect(server.id) },
                onDelete = { deleting = server },
            )
        }

        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Добавить сервер")
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showAdd) {
        AddServerDialog(onDismiss = { showAdd = false }, onAdd = onAdd)
    }

    deleting?.let { server ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить «${server.name}»?") },
            text = { Text("Конфиг с ключом будет стёрт из приложения. На самом сервере ничего не изменится.") },
            confirmButton = {
                TextButton(onClick = { onDelete(server.id); deleting = null }) { Text("Удалить", color = Bad) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ServerRow(
    server: Server,
    probe: ProbeResult?,
    probing: Boolean,
    chosen: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name, fontWeight = FontWeight.SemiBold)
                    if (chosen || active) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Check, contentDescription = "Выбран", tint = Good)
                    }
                }
                Text(server.host, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(
                    probeSummary(probe, probing, server.pingPort),
                    color = probeColor(probe),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (probe?.avgMs != null) {
                Text(fmtMs(probe.avgMs), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = pingColor(probe.avgMs))
                Text(" мс", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun probeSummary(r: ProbeResult?, probing: Boolean, port: Int): String = when {
    r == null -> if (probing) "проверяю…" else "не проверен"
    !r.reachable && r.error != null -> "ошибка: ${r.error}"
    !r.reachable -> "нет ответа по UDP $port (запущен ли pingboost на сервере?)"
    else -> "потери ${fmtPercent(r.lossPercent)}% · джиттер ${fmtMs(r.jitterMs)} мс"
}

private fun probeColor(r: ProbeResult?): androidx.compose.ui.graphics.Color = when {
    r == null -> Muted
    !r.reachable -> Bad
    else -> lossColor(r.lossPercent)
}

@Composable
private fun AddServerDialog(onDismiss: () -> Unit, onAdd: (String, String) -> String?) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var name by remember { mutableStateOf("") }
    var config by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                null
            }
            if (text != null) {
                config = text
                error = null
            } else {
                error = "Не удалось прочитать файл"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый сервер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название (необязательно)") },
                    placeholder = { Text("Германия") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config,
                    onValueChange = { config = it; error = null },
                    label = { Text("Конфиг WireGuard") },
                    placeholder = { Text("[Interface]\nPrivateKey = …\n…") },
                    minLines = 6,
                    maxLines = 10,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { clipboard.getText()?.text?.let { config = it; error = null } }) { Text("Вставить") }
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Из файла") }
                }
                error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = onAdd(name, config)
                if (result == null) onDismiss() else error = result
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
