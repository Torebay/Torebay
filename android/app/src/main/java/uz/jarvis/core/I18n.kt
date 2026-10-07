package uz.jarvis.core

/** Фразы, которые ассистент говорит сам, на русском, узбекском и турецком (как assistant/i18n.py). */
object I18n {
    const val DEFAULT = "ru"
    val LANGS = listOf("ru", "uz", "tr")

    /** Код языка для распознавания речи Android. */
    fun speechTag(lang: String) = when (code(lang)) { "uz" -> "uz-UZ"; "tr" -> "tr-TR"; else -> "ru-RU" }

    fun code(value: String?): String {
        val c = (value ?: DEFAULT).lowercase().take(2)
        return if (c in LANGS) c else DEFAULT
    }

    private val MONTHS = mapOf(
        "ru" to listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
            "августа", "сентября", "октября", "ноября", "декабря"),
        "uz" to listOf("yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul",
            "avgust", "sentabr", "oktabr", "noyabr", "dekabr"),
        "tr" to listOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz",
            "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"),
    )

    private val MESSAGES = mapOf(
        "ru" to mapOf(
            "hello" to "{name} на связи.",
            "listening" to "Слушаю.",
            "bye" to "До встречи!",
            "open" to "Открываю {x}",
            "open_site" to "Открываю сайт {x}",
            "not_found" to "Не нашёл приложение {x}.",
            "search" to "Ищу {x}",
            "youtube" to "Ищу на ютубе {x}",
            "time" to "Сейчас {x}.",
            "date" to "Сегодня {x}.",
            "reset" to "Хорошо, начнём разговор заново.",
            "switched" to "Хорошо, теперь говорю по-русски.",
            "failed" to "Не получилось: {x}",
            "no_key" to "Чтобы отвечать на вопросы, вставьте ключ Claude API в настройках.",
            "bad_key" to "Ключ Claude API не подошёл. Проверьте его в настройках.",
            "rate_limit" to "Слишком много запросов, попробуйте через минуту.",
            "api_error" to "Claude API вернул ошибку {x}.",
            "offline" to "Нет связи с интернетом.",
            "refusal" to "На этот вопрос я ответить не могу.",
            "no_answer" to "Не знаю, что ответить.",
            "not_heard" to "Не расслышал.",
            "status_idle" to "нажми, чтобы говорить",
            "status_listening" to "слушаю",
            "status_thinking" to "думаю",
            "status_speaking" to "говорю",
        ),
        "uz" to mapOf(
            "hello" to "{name} aloqada.",
            "listening" to "Eshitaman.",
            "bye" to "Ko'rishguncha!",
            "open" to "{x} ochilmoqda",
            "open_site" to "{x} sayti ochilmoqda",
            "not_found" to "{x} ilovasini topa olmadim.",
            "search" to "{x} qidirilmoqda",
            "youtube" to "YouTube'da {x} qidirilmoqda",
            "time" to "Hozir soat {x}.",
            "date" to "Bugun {x}.",
            "reset" to "Mayli, suhbatni yangidan boshlaymiz.",
            "switched" to "Mayli, endi o'zbekcha gaplashaman.",
            "failed" to "Bo'lmadi: {x}",
            "no_key" to "Savollarga javob berish uchun sozlamalarda Claude API kalitini kiriting.",
            "bad_key" to "Claude API kaliti to'g'ri kelmadi. Sozlamalarda tekshiring.",
            "rate_limit" to "So'rovlar juda ko'p, bir daqiqadan keyin urinib ko'ring.",
            "api_error" to "Claude API {x} xatosini qaytardi.",
            "offline" to "Internet aloqasi yo'q.",
            "refusal" to "Bu savolga javob bera olmayman.",
            "no_answer" to "Nima deyishni bilmayman.",
            "not_heard" to "Eshitmadim.",
            "status_idle" to "gapirish uchun bosing",
            "status_listening" to "eshitaman",
            "status_thinking" to "o'ylayapman",
            "status_speaking" to "gapiryapman",
        ),
        "tr" to mapOf(
            "hello" to "{name} hazır.",
            "listening" to "Dinliyorum.",
            "bye" to "Görüşürüz!",
            "open" to "{x} açılıyor",
            "open_site" to "{x} sitesi açılıyor",
            "not_found" to "{x} uygulamasını bulamadım.",
            "search" to "{x} aranıyor",
            "youtube" to "YouTube'da {x} aranıyor",
            "time" to "Saat {x}.",
            "date" to "Bugün {x}.",
            "reset" to "Tamam, sohbete baştan başlayalım.",
            "switched" to "Tamam, artık Türkçe konuşuyorum.",
            "failed" to "Olmadı: {x}",
            "no_key" to "Sorulara cevap vermem için ayarlara Claude API anahtarını girin.",
            "bad_key" to "Claude API anahtarı geçersiz. Ayarlardan kontrol edin.",
            "rate_limit" to "Çok fazla istek var, bir dakika sonra tekrar deneyin.",
            "api_error" to "Claude API {x} hatası döndürdü.",
            "offline" to "İnternet bağlantısı yok.",
            "refusal" to "Bu soruya cevap veremem.",
            "no_answer" to "Ne diyeceğimi bilemedim.",
            "not_heard" to "Duyamadım.",
            "status_idle" to "konuşmak için dokun",
            "status_listening" to "dinliyorum",
            "status_thinking" to "düşünüyorum",
            "status_speaking" to "konuşuyorum",
        ),
    )

    fun t(lang: String, key: String, x: Any? = null, name: String? = null): String {
        var s = MESSAGES.getValue(code(lang)).getValue(key)
        if (x != null) s = s.replace("{x}", x.toString())
        if (name != null) s = s.replace("{name}", name)
        return s
    }

    fun formatDate(lang: String, day: Int, month: Int) = "$day ${MONTHS.getValue(code(lang))[month - 1]}"
}
