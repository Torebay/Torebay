package app.pingboost.data

import android.content.Context
import app.pingboost.core.WgConf
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Один сервер (узел) ускорения: имя и обычный WireGuard-конфиг для него. */
data class Server(val id: String, val name: String, val config: String) {
    val host: String get() = WgConf.endpointHost(config) ?: "?"
    val pingPort: Int get() = WgConf.pingPort(config)
}

/** Хранилище настроек в приватной папке приложения (другие приложения её не видят). */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pingboost", Context.MODE_PRIVATE)

    fun loadServers(): List<Server> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Server(o.getString("id"), o.getString("name"), o.getString("config"))
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    fun saveServers(servers: List<Server>) {
        val arr = JSONArray()
        servers.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("config", it.config)) }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    var autoMode: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    var selectedId: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    /** Какие версии игры пускать через туннель. `null` значит «ничего не выбирали, все найденные». */
    var enabledPackages: Set<String>?
        get() = prefs.getStringSet(KEY_PACKAGES, null)?.toSet()
        set(value) = prefs.edit().putStringSet(KEY_PACKAGES, value).apply()

    private companion object {
        const val KEY_SERVERS = "servers"
        const val KEY_AUTO = "auto"
        const val KEY_SELECTED = "selected"
        const val KEY_PACKAGES = "packages"
    }
}
