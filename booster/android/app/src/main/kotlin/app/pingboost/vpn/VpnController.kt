package app.pingboost.vpn

import android.content.Context
import android.content.Intent
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Единственная точка работы с туннелем WireGuard. Объект живёт, пока жив процесс, поэтому
 * состояние туннеля переживает закрытие и повторное открытие окна приложения.
 */
object VpnController {
    private const val TUNNEL_NAME = "pingboost"

    private val _tunnelUp = MutableStateFlow(false)

    /** true, пока туннель реально поднят (в том числе если систему/пользователя отключили его снаружи). */
    val tunnelUp: StateFlow<Boolean> = _tunnelUp.asStateFlow()

    @Volatile
    var activeServerId: String? = null
        private set

    @Volatile
    private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            when (newState) {
                Tunnel.State.UP -> _tunnelUp.value = true
                Tunnel.State.DOWN -> {
                    _tunnelUp.value = false
                    activeServerId = null
                }
                else -> Unit
            }
        }
    }

    private fun backend(context: Context): GoBackend =
        backend ?: synchronized(this) {
            backend ?: GoBackend(context.applicationContext).also { backend = it }
        }

    /** Не null, если нужно показать системный диалог «Разрешить приложению VPN». */
    fun permissionIntent(context: Context): Intent? = android.net.VpnService.prepare(context)

    /** Проверяет текст конфига. Возвращает сообщение об ошибке или null, если всё хорошо. */
    fun validate(confText: String): String? =
        try {
            Config.parse(ByteArrayInputStream(confText.toByteArray(Charsets.UTF_8)))
            null
        } catch (e: BadConfigException) {
            "Неверный конфиг: ${e.section} → ${e.location}: ${e.reason}"
        } catch (e: IOException) {
            "Не удалось прочитать конфиг: ${e.message}"
        }

    /** Поднимает туннель. Блокирующая, ходит в сеть для разбора адреса сервера, поэтому только в IO. */
    suspend fun connect(context: Context, confText: String, serverId: String) =
        withContext(Dispatchers.IO) {
            val config = Config.parse(ByteArrayInputStream(confText.toByteArray(Charsets.UTF_8)))
            activeServerId = serverId
            try {
                backend(context).setState(tunnel, Tunnel.State.UP, config)
            } catch (e: Exception) {
                activeServerId = null
                throw e
            }
        }

    suspend fun disconnect(context: Context) =
        withContext(Dispatchers.IO) {
            backend(context).setState(tunnel, Tunnel.State.DOWN, null)
        }

    /** Принято и отправлено через туннель в байтах (rx, tx) или null, если счётчики недоступны. */
    fun traffic(context: Context): Pair<Long, Long>? =
        try {
            val s = backend(context).getStatistics(tunnel)
            s.totalRx() to s.totalTx()
        } catch (_: Exception) {
            null
        }
}
