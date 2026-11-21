package app.pingboost.core

/**
 * Мелкие операции над текстом обычного WireGuard-конфига (`wg-quick`), которым пользуется
 * приложение. Полный разбор и проверку делает библиотека wireguard-android, а здесь только то,
 * что нужно нам до этого: адрес сервера для пинга, подсказка имени и список приложений,
 * которые идут через туннель.
 */
object WgConf {
    const val DEFAULT_PING_PORT = 51831

    private val endpointRe = Regex("""^\s*Endpoint\s*=\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
    private val nameRe = Regex("""^\s*#\s*Name\s*[:=]\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
    private val pingPortRe = Regex("""^\s*#\s*PingPort\s*[:=]\s*(\d{1,5})\s*$""", RegexOption.IGNORE_CASE)
    private val appListRe = Regex("""^\s*(IncludedApplications|ExcludedApplications)\s*=""", RegexOption.IGNORE_CASE)
    private val interfaceHeaderRe = Regex("""^\s*\[Interface]\s*$""", RegexOption.IGNORE_CASE)

    /** Хост или IP из `Endpoint` первого пира. Понимает `host:port` и `[ipv6]:port`. */
    fun endpointHost(conf: String): String? {
        for (raw in conf.lineSequence()) {
            val value = endpointRe.find(stripComment(raw))?.groupValues?.get(1) ?: continue
            return splitHostPort(value).first.ifBlank { null }
        }
        return null
    }

    /** Порт WireGuard из `Endpoint`, если указан. */
    fun endpointPort(conf: String): Int? {
        for (raw in conf.lineSequence()) {
            val value = endpointRe.find(stripComment(raw))?.groupValues?.get(1) ?: continue
            return splitHostPort(value).second
        }
        return null
    }

    /** Имя сервера из комментария `# Name: Германия` (его пишет серверный скрипт). */
    fun nameHint(conf: String): String? =
        conf.lineSequence().firstNotNullOfOrNull { nameRe.find(it)?.groupValues?.get(1) }?.ifBlank { null }

    /** Порт эхо-сервиса для пинга из комментария `# PingPort = 51831`, иначе порт по умолчанию. */
    fun pingPort(conf: String): Int =
        conf.lineSequence()
            .firstNotNullOfOrNull { pingPortRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in 1..65535 }
            ?: DEFAULT_PING_PORT

    /**
     * Вставляет в `[Interface]` строку `IncludedApplications = ...`, чтобы через туннель шли
     * только перечисленные приложения. Прежние списки приложений из конфига убираются, иначе
     * чужой конфиг мог бы завернуть в туннель всё подряд.
     */
    fun withIncludedApps(conf: String, packages: Collection<String>): String {
        require(packages.isNotEmpty()) { "Список приложений пуст" }
        val lines = conf.lineSequence().filterNot { appListRe.containsMatchIn(it) }.toList()
        val at = lines.indexOfFirst { interfaceHeaderRe.matches(it) }
        require(at >= 0) { "В конфиге нет секции [Interface]" }
        val out = ArrayList<String>(lines.size + 1)
        out.addAll(lines.subList(0, at + 1))
        out.add("IncludedApplications = ${packages.joinToString(", ")}")
        out.addAll(lines.subList(at + 1, lines.size))
        return out.joinToString("\n")
    }

    private fun stripComment(line: String): String {
        val i = line.indexOf('#')
        return if (i >= 0) line.substring(0, i) else line
    }

    internal fun splitHostPort(value: String): Pair<String, Int?> {
        val v = value.trim()
        if (v.startsWith("[")) {
            val end = v.indexOf(']')
            if (end < 0) return v to null
            val port = v.substring(end + 1).removePrefix(":").toIntOrNull()
            return v.substring(1, end) to port
        }
        val first = v.indexOf(':')
        val last = v.lastIndexOf(':')
        // Ровно одно двоеточие, значит host:port. Больше, значит голый IPv6 без порта.
        return if (first > 0 && first == last) v.substring(0, first) to v.substring(first + 1).toIntOrNull()
        else v to null
    }
}
