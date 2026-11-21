package app.pingboost

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

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
                    icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("Главная") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }, label = { Text("Серверы") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) }, label = { Text("Настройки") },
                )
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).statusBarsPadding()) {
            when (tab) {
                0 -> HomeScreen(ui, onToggle = onToggle, onAutoChange = vm::setAutoMode, onRefresh = vm::probeAll)
                1 -> ServersScreen(
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
