package uz.jarvis.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Открытие приложений, сайтов и поиска на телефоне. */
class Apps(private val context: Context) {

    private data class Known(val names: List<String>, val packages: List<String>, val url: String? = null)

    // Приложения, которые чаще всего называют голосом, с запасной веб-версией.
    private val known = listOf(
        Known(listOf("телеграм", "телеграмм", "телега", "telegram", "tilgram"),
            listOf("org.telegram.messenger", "org.telegram.plus", "org.thunderdog.challegram"), "https://web.telegram.org"),
        Known(listOf("ватсап", "вотсап", "вацап", "воцап", "whatsapp", "vatsap", "watsap", "vatsapp"),
            listOf("com.whatsapp", "com.whatsapp.w4b"), "https://web.whatsapp.com"),
        Known(listOf("ютуб", "ютюб", "youtube", "yutub", "yutup", "youtub"),
            listOf("com.google.android.youtube"), "https://www.youtube.com"),
        Known(listOf("инстаграм", "инста", "instagram", "instagram"), listOf("com.instagram.android"), "https://www.instagram.com"),
        Known(listOf("тикток", "tiktok", "tik tok"), listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "https://www.tiktok.com"),
        Known(listOf("браузер", "хром", "chrome", "гугл", "google", "интернет", "brauzer", "tarayıcı"),
            listOf("com.android.chrome"), "https://www.google.com"),
        Known(listOf("карты", "карта", "maps", "xarita", "harita"), listOf("com.google.android.apps.maps"), "https://maps.google.com"),
        Known(listOf("почта", "gmail", "джимейл"), listOf("com.google.android.gm"), "https://mail.google.com"),
    )

    // Русские названия обычных приложений → слова, которые бывают в их названиях на телефоне.
    private val synonyms = mapOf(
        "камера" to "camera", "kamera" to "camera", "галерея" to "gallery", "фото" to "photos",
        "калькулятор" to "calculator", "hesap makinesi" to "calculator", "kalkulyator" to "calculator",
        "настройки" to "settings", "параметры" to "settings", "ayarlar" to "settings", "sozlamalar" to "settings",
        "часы" to "clock", "будильник" to "clock", "календарь" to "calendar", "контакты" to "contacts",
        "телефон" to "phone", "сообщения" to "messages", "смс" to "messages", "музыка" to "music",
        "плей маркет" to "play store", "плеймаркет" to "play store", "маркет" to "play store",
    )

    fun open(name: String): Pair<Boolean, String> {
        val lower = name.lowercase().trim()
        for (variant in uz.jarvis.core.Commands.appNameVariants(lower)) {
            known.firstOrNull { k -> k.names.any { it == variant || (it.length >= 4 && variant.contains(it)) } }?.let { k ->
                k.packages.forEach { pkg -> if (launchPackage(pkg)) return true to variant }
                k.url?.let { openUrl(it); return true to variant }
            }
        }
        for (variant in uz.jarvis.core.Commands.appNameVariants(lower)) {
            findInstalled(variant)?.let { (pkg, label) -> if (launchPackage(pkg)) return true to label }
        }
        if ("." in lower && " " !in lower) {  // похоже на сайт: «открой kun.uz»
            openUrl(if ("://" in lower) lower else "https://$lower")
            return true to lower
        }
        return false to name
    }

    /** Ищет установленное приложение по названию (с переводом русских букв в латиницу). */
    private fun findInstalled(name: String): Pair<String, String>? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        val wanted = listOfNotNull(name, synonyms[name], translit(name)).distinct()
        for (w in wanted) {
            apps.firstOrNull { it.second.lowercase() == w }?.let { return it }
        }
        for (w in wanted.filter { it.length >= 3 }) {
            apps.firstOrNull { it.second.lowercase().contains(w) }?.let { return it }
        }
        return null
    }

    private fun translit(text: String): String {
        val map = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh",
            'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
            'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts",
            'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya")
        return text.map { map[it] ?: it.toString() }.joinToString("")
    }

    private fun launchPackage(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    fun webSearch(query: String) = openUrl("https://www.google.com/search?q=" + Uri.encode(query))

    fun youtubeSearch(query: String) = openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(query))
}
