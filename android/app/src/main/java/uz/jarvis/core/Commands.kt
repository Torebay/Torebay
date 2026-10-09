package uz.jarvis.core

/**
 * Разбор распознанной фразы в команду. Тот же разбор, что в assistant/commands.py
 * на компьютере: русский, узбекский и турецкий. В узбекском и турецком глагол обычно
 * стоит в конце («telegramni och», «YouTube'u aç»), поэтому ищем его и в начале, и в конце.
 */
data class Command(val action: Action, val arg: String = "")

enum class Action { OPEN, SEARCH, YOUTUBE, TIME, DATE, EXIT, RESET, LANG, ASK, EMPTY }

object Commands {
    private val OPEN_PREFIX = listOf("открой", "открыть", "запусти", "запустить", "включи", "open", "run",
        "aç", "ac", "och", "başlat")
    private val OPEN_SUFFIX = listOf("açar mısın", "açsana", "aç", "ac", "başlat", "ochib bering", "ochib ber",
        "oching", "och", "ishga tushiring", "ishga tushir")
    private val SEARCH_PREFIX = listOf("найди", "найти", "поищи", "загугли", "поиск", "ara", "qidir", "izla", "search")
    private val SEARCH_SUFFIX = listOf("araştır", "arat", "ara", "bul", "qidirib bering", "qidirib ber",
        "qidiring", "qidir", "izla", "top")
    private val YOUTUBE_MARKERS = listOf("на ютубе", "в ютубе", "на youtube", "в youtube", "на ютюбе",
        "youtubeda", "youtubedan", "youtubeta", "yutubda", "ютубда")
    private val TIME_PHRASES = listOf("который час", "сколько времени", "сколько время",
        "saat kaç", "saat kac", "soat necha", "soat nechchi", "vaqt necha")
    private val DATE_PHRASES = listOf("какое сегодня число", "какая сегодня дата", "какой сегодня день",
        "bugün ayın kaçı", "bugün tarih", "bugün günlerden ne",
        "bugun sana", "bugun nechanchi", "bugun qaysi kun")
    private val EXIT_PHRASES = setOf("выход", "стоп", "пока", "до свидания", "выключись", "отключись",
        "dur", "kapan", "çıkış", "cikis", "güle güle", "toxta", "chiqish", "xayr")
    private val RESET_PHRASES = listOf("забудь разговор", "новый разговор", "начни сначала",
        "sohbeti unut", "yeni sohbet", "suhbatni unut", "yangi suhbat")
    private val FILLER_WORDS = listOf("пожалуйста", "please", "ну", "а", "lütfen", "iltimos")

    private val LANGUAGE_WORDS = linkedMapOf(
        "ru" to listOf("по-русски", "по русски", "на русском", "русский", "ruscha", "ruschaga", "rus tiliga",
            "rusça", "rusca", "rusçaya", "russian"),
        "uz" to listOf("по-узбекски", "по узбекски", "на узбекском", "узбекский",
            "ozbekcha", "ozbekchaga", "ozbek tilida", "ozbek tiliga", "özbekçe", "özbekçeye", "ozbekce", "uzbek"),
        "tr" to listOf("по-турецки", "по турецки", "на турецком", "турецкий",
            "turkcha", "turkchaga", "turk tiliga", "türkçe", "türkçeye", "turkce", "turkish"),
    )
    private val SWITCH_WORDS = setOf("говори", "давай", "перейди", "переключись", "на", "язык", "разговаривай",
        "gapir", "gapiring", "tilni", "til", "gaplash", "gaplashamiz", "tilida", "tiliga", "otish", "otamiz",
        "konuş", "konus", "konuşalım", "dil", "geç", "switch", "to", "speak")

    fun normalize(text: String): String {
        var t = text.replace("İ", "i").lowercase().replace("ё", "е")
        // Апострофы убираем совсем: «o'zbekcha» → «ozbekcha», «YouTube'u» → «youtubeu».
        t = t.replace(Regex("['’ʻʼ`]"), "")
        t = t.replace(Regex("[^\\p{L}\\p{N}_\\s:/.-]"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Возвращает (было ли обращение, остаток фразы без имени ассистента). */
    fun stripWakeWord(text: String, wakeWords: List<String>): Pair<Boolean, String> {
        val norm = normalize(text)
        for (raw in wakeWords.sortedByDescending { it.length }) {
            val word = normalize(raw)
            if (word.isEmpty()) continue
            val match = Regex("(^|\\s)${Regex.escape(word)}[\\p{L}\\p{N}_]*(\\s|$)").find(norm)
            if (match != null) {
                val rest = (norm.substring(0, match.range.first) + " " + norm.substring(match.range.last + 1)).trim()
                return true to rest.replace(Regex("\\s+"), " ")
            }
        }
        return false to norm
    }

    private fun stripPrefix(text: String, prefixes: List<String>): String? {
        for (p in prefixes) {
            if (text == p) return ""
            if (text.startsWith("$p ")) return text.substring(p.length + 1).trim()
        }
        return null
    }

    private fun stripSuffix(text: String, suffixes: List<String>): String? {
        for (s in suffixes) {
            if (text == s) return ""
            if (text.endsWith(" $s")) return text.substring(0, text.length - s.length - 1).trim()
        }
        return null
    }

    private fun stripVerb(text: String, prefixes: List<String>, suffixes: List<String>): String? =
        stripPrefix(text, prefixes) ?: stripSuffix(text, suffixes)

    private fun hasPhrase(text: String, phrase: String) =
        Regex("(^|\\s)${Regex.escape(phrase)}(\\s|$)").containsMatchIn(text)

    private fun languageSwitch(text: String): String? {
        for ((code, phrases) in LANGUAGE_WORDS) {
            for (phrase in phrases) {
                if (hasPhrase(text, phrase)) {
                    val rest = text.replace(phrase, " ").split(" ").filter { it.isNotBlank() }
                    if (rest.all { it in SWITCH_WORDS }) return code
                }
            }
        }
        return null
    }

    /** «telegramni» → «telegram», «youtubeu» → «youtube»: убираем узбекские и турецкие окончания. */
    fun appNameVariants(name: String): List<String> {
        val variants = mutableListOf(name)
        if (name.length > 5 && name.endsWith("ni")) variants += name.dropLast(2)
        if (name.length > 4 && name.last() in "ıiuü") {
            variants += name.dropLast(1)
            if (name[name.length - 2] == 'y') variants += name.dropLast(2)
        }
        return variants
    }

    fun parse(input: String): Command {
        var text = normalize(input)
        // Вежливые слова не мешают командам: «пожалуйста открой ютуб».
        text = text.split(" ").filter { it !in FILLER_WORDS }.joinToString(" ").trim()

        if (text.isEmpty()) return Command(Action.EMPTY)
        if (text in EXIT_PHRASES) return Command(Action.EXIT)
        if (RESET_PHRASES.any { it in text }) return Command(Action.RESET)
        languageSwitch(text)?.let { return Command(Action.LANG, it) }
        if (TIME_PHRASES.any { it in text }) return Command(Action.TIME)
        if (DATE_PHRASES.any { it in text }) return Command(Action.DATE)

        for (marker in YOUTUBE_MARKERS) {
            if (hasPhrase(text, marker)) {
                var query = text.replace(marker, " ").replace(Regex("\\s+"), " ").trim()
                query = stripVerb(query, SEARCH_PREFIX + OPEN_PREFIX, SEARCH_SUFFIX + OPEN_SUFFIX) ?: query
                // «открой ютуб» без запроса — просто открыть сайт.
                return if (query.isNotEmpty()) Command(Action.YOUTUBE, query) else Command(Action.OPEN, "youtube")
            }
        }

        stripVerb(text, SEARCH_PREFIX, SEARCH_SUFFIX)?.let { rest ->
            val q = rest.replace(Regex("^(в интернете|в гугле|в google)\\s*"), "").trim()
            return if (q.isNotEmpty()) Command(Action.SEARCH, q) else Command(Action.ASK, text)
        }

        val rest = stripVerb(text, OPEN_PREFIX, OPEN_SUFFIX)
        if (!rest.isNullOrEmpty()) {
            return Command(Action.OPEN, rest.replace(Regex("^(мне|программу|приложение|сайт)\\s+"), "").trim())
        }
        return Command(Action.ASK, text)
    }
}
