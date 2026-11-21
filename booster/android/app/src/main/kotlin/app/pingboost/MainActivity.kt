package app.pingboost

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import app.pingboost.ui.GameScreen
import app.pingboost.ui.HomeScreen
import app.pingboost.ui.PingBoostTheme
import app.pingboost.ui.ServersScreen
import app.pingboost.ui.SettingsScreen
import app.pingboost.vpn.VpnController

class MainActivity : ComponentActivity() {
    private val vm: BoosterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            PingBoostTheme { PingBoostApp(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshInstalled() // игру могли установить или удалить, пока окно было свёрнуто
    }
}

@Composable
private fun PingBoostApp(vm: BoosterViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val game by vm.game.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    // Пока открыта вкладка «Игра» и окно на экране, раз в несколько секунд обновляем нагрев, память и т.д.
    LaunchedEffect(tab, lifecycleOwner) {
        if (tab == 0) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    vm.refreshGame()
                    delay(4000)
                }
            }
        }
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    // Первое включение VPN: Android показывает системный диалог с запросом разрешения.
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.connect() else vm.say("Без разрешения VPN ускорение не работает")
    }
    val onToggle = {
        if (ui.phase == Phase.Connected) {
            vm.disconnect()
        } else {
            val intent = VpnController.permissionIntent(context)
            if (intent != null) vpnPermission.launch(intent) else vm.connect()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }, label = { Text("Игра") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("Пинг") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }, label = { Text("Серверы") },
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) }, label = { Text("Настройки") },
                )
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).statusBarsPadding()) {
            when (tab) {
                0 -> GameScreen(
                    game,
                    onPlay = {
                        val intent = vm.launchIntent()
                        if (intent != null) context.startActivity(intent) else vm.say("Не удалось запустить игру")
                    },
                    onDnd = {
                        if (!vm.toggleDnd()) {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
                1 -> HomeScreen(ui, onToggle = onToggle, onAutoChange = vm::setAutoMode, onRefresh = vm::probeAll)
                2 -> ServersScreen(
                    ui,
                    onSelect = vm::selectServer,
                    onDelete = vm::deleteServer,
                    onRefresh = vm::probeAll,
                    onAdd = vm::addServer,
                )
                else -> SettingsScreen(ui, onGameToggle = vm::setGameEnabled)
            }
        }
    }
}
