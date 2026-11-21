package app.pingboost

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pingboost.core.ProbeResult
import app.pingboost.core.PubgGames
import app.pingboost.core.RollingProbeWindow
import app.pingboost.core.ServerPicker
import app.pingboost.core.UdpEchoClient
import app.pingboost.core.UdpEchoProbe
import app.pingboost.core.WgConf
import app.pingboost.data.Server
import app.pingboost.data.Store
import app.pingboost.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class BoosterViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val window = RollingProbeWindow(capacity = 60)
    private var liveJob: Job? = null

    init {
        val servers = store.loadServers()
        _ui.update {
            it.copy(
                servers = servers,
                autoMode = store.autoMode,
                selectedId = store.selectedId?.takeIf { id -> servers.any { s -> s.id == id } }
                    ?: servers.firstOrNull()?.id,
            )
        }
        refreshInstalled()

        // Туннель мог остаться включённым, пока окно было закрыто: подхватываем его.
        val running = if (VpnController.tunnelUp.value) servers.firstOrNull { it.id == VpnController.activeServerId } else null
        if (running != null) {
            _ui.update { it.copy(phase = Phase.Connected, activeServerId = running.id) }
            startLive(running)
        }
        if (servers.isNotEmpty()) probeAll()

        // Если туннель выключили снаружи (системой, другим VPN, кнопкой в шторке), отражаем это.
        viewModelScope.launch {
            VpnController.tunnelUp.collect { up ->
                if (!up && _ui.value.phase == Phase.Connected) {
                    stopLive()
                    _ui.update {
                        it.copy(
                            phase = Phase.Idle,
                            activeServerId = null,
                            livePingMs = null,
                            message = "VPN выключен системой или другим приложением",
                        )
                    }
                }
            }
        }
    }

    // region игры

    /** Ищет установленные версии PUBG. Вызывается при каждом возврате в приложение. */
    fun refreshInstalled() {
        val pm = getApplication<Application>().packageManager
        val installed = PubgGames.all.filter { isInstalled(pm, it.packageName) }
        val installedPackages = installed.map { it.packageName }.toSet()
        val enabled = (store.enabledPackages ?: installedPackages).intersect(installedPackages)
        _ui.update { it.copy(installedGames = installed, enabledPackages = enabled) }
    }

    fun setGameEnabled(packageName: String, enabled: Boolean) {
        val next = if (enabled) _ui.value.enabledPackages + packageName else _ui.value.enabledPackages - packageName
        store.enabledPackages = next
        _ui.update { it.copy(enabledPackages = next) }
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(pm: PackageManager, packageName: String): Boolean =
        try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    // endregion

    // region серверы

    /** Возвращает текст ошибки или null, если сервер добавлен. */
    fun addServer(name: String, confText: String): String? {
        val text = confText.trim()
        if (text.isEmpty()) return "Вставь конфиг WireGuard"
        VpnController.validate(text)?.let { return it }
        if (WgConf.endpointHost(text) == null) return "В конфиге нет Endpoint, не знаю, куда подключаться"

        val finalName = name.trim().ifBlank { WgConf.nameHint(text) ?: WgConf.endpointHost(text) ?: "Сервер" }
        val server = Server(UUID.randomUUID().toString(), finalName, text)
        val servers = _ui.value.servers + server
        store.saveServers(servers)
        if (_ui.value.selectedId == null) store.selectedId = server.id
        _ui.update { it.copy(servers = servers, selectedId = it.selectedId ?: server.id) }
        probeAll()
        return null
    }

    fun deleteServer(id: String) {
        val cur = _ui.value
        if (cur.phase != Phase.Idle && cur.activeServerId == id) {
            say("Сначала выключи ускорение")
            return
        }
        val servers = cur.servers.filterNot { it.id == id }
        store.saveServers(servers)
        val selected = if (cur.selectedId == id) servers.firstOrNull()?.id else cur.selectedId
        store.selectedId = selected
        _ui.update { it.copy(servers = servers, selectedId = selected, probes = it.probes - id) }
    }

    /** Ручной выбор сервера выключает авто-режим. */
    fun selectServer(id: String) {
        if (_ui.value.phase != Phase.Idle) {
            say("Выбирать сервер можно, пока ускорение выключено")
            return
        }
        store.selectedId = id
        store.autoMode = false
        _ui.update { it.copy(selectedId = id, autoMode = false) }
    }

    fun setAutoMode(on: Boolean) {
        if (_ui.value.phase != Phase.Idle) {
            say("Менять режим можно, пока ускорение выключено")
            return
        }
        store.autoMode = on
        _ui.update { it.copy(autoMode = on) }
    }

    fun probeAll() {
        val servers = _ui.value.servers
        if (servers.isEmpty() || _ui.value.probing) return
        viewModelScope.launch {
            _ui.update { it.copy(probing = true) }
            val results = probeServers(servers)
            _ui.update { it.copy(probing = false, probes = results) }
        }
    }

    private suspend fun probeServers(servers: List<Server>): Map<String, ProbeResult> = coroutineScope {
        servers
            .map { s -> async(Dispatchers.IO) { s.id to UdpEchoProbe.run(s.host, s.pingPort) } }
            .awaitAll()
            .toMap()
    }

    // endregion

    // region подключение

    fun connect() {
        val cur = _ui.value
        if (cur.phase != Phase.Idle) return
        if (cur.servers.isEmpty()) {
            say("Сначала добавь сервер на вкладке «Серверы»")
            return
        }
        val packages = cur.installedGames.map { it.packageName }.filter { it in cur.enabledPackages }
        if (packages.isEmpty()) {
            say("PUBG Mobile не найден на телефоне. Установи игру или отметь версию в настройках")
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(phase = Phase.Connecting, message = null) }
            val server = chooseServer()
            try {
                val conf = WgConf.withIncludedApps(server.config, packages)
                VpnController.connect(getApplication(), conf, server.id)
                window.clear()
                _ui.update {
                    it.copy(
                        phase = Phase.Connected,
                        activeServerId = server.id,
                        history = emptyList(),
                        livePingMs = null,
                        liveLossPercent = 0.0,
                        liveJitterMs = 0.0,
                        rxBytes = 0,
                        txBytes = 0,
                    )
                }
                startLive(server)
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        phase = Phase.Idle,
                        activeServerId = null,
                        message = "Не удалось подключиться: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun disconnect() {
        if (_ui.value.phase != Phase.Connected) return
        viewModelScope.launch {
            _ui.update { it.copy(phase = Phase.Disconnecting) }
            stopLive()
            try {
                VpnController.disconnect(getApplication())
            } catch (e: Exception) {
                say("Ошибка при отключении: ${e.message ?: e.javaClass.simpleName}")
            }
            _ui.update { it.copy(phase = Phase.Idle, activeServerId = null, livePingMs = null) }
        }
    }

    /** В авто-режиме меряет все серверы и берёт лучший; иначе берёт выбранный. */
    private suspend fun chooseServer(): Server {
        val cur = _ui.value
        val manual = cur.servers.firstOrNull { it.id == cur.selectedId } ?: cur.servers.first()
        if (!cur.autoMode) return manual

        _ui.update { it.copy(probing = true) }
        val results = probeServers(cur.servers)
        _ui.update { it.copy(probing = false, probes = results) }

        val bestId = ServerPicker.best(results)
        if (bestId != null) return cur.servers.first { it.id == bestId }

        say("Ни один сервер не ответил на проверку пинга. Подключаюсь к выбранному. Проверь, что на сервере работает pingboost.")
        return manual
    }

    /** Раз в секунду шлёт пробный пакет на сервер и обновляет ping / потери / джиттер и счётчики трафика. */
    private fun startLive(server: Server) {
        liveJob?.cancel()
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            val client = try {
                UdpEchoClient(server.host, server.pingPort, timeoutMs = 800)
            } catch (_: Exception) {
                null
            }
            try {
                while (isActive) {
                    val rtt = try {
                        client?.ping()
                    } catch (_: Exception) {
                        null
                    }
                    window.add(rtt)
                    val recent = window.snapshot(lastN = 20)
                    val traffic = VpnController.traffic(getApplication())
                    _ui.update {
                        it.copy(
                            livePingMs = window.last(),
                            liveLossPercent = recent.lossPercent,
                            liveJitterMs = recent.jitterMs,
                            history = window.samples(),
                            rxBytes = traffic?.first ?: it.rxBytes,
                            txBytes = traffic?.second ?: it.txBytes,
                        )
                    }
                    delay(1000)
                }
            } finally {
                client?.close()
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    // endregion

    fun say(text: String) = _ui.update { it.copy(message = text) }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
}
