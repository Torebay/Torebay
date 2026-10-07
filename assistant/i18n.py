"""Языки ассистента: русский, узбекский и турецкий.

Здесь фразы, которые ассистент говорит сам, коды для распознавания речи
и подсказки, по которым выбирается голос Windows.
"""

from __future__ import annotations

DEFAULT_LANGUAGE = "ru"

LANGUAGES: dict[str, dict] = {
    "ru": {
        "speech": "ru-RU",
        "voice_hints": ["russian", "ru-ru", "ru_ru", "irina", "pavel", "svetlana", "dmitry"],
        "months": ["января", "февраля", "марта", "апреля", "мая", "июня", "июля",
                   "августа", "сентября", "октября", "ноября", "декабря"],
        "messages": {
            "hello": "{name} на связи.",
            "listening": "Слушаю.",
            "bye": "До встречи!",
            "open": "Открываю {x}",
            "open_site": "Открываю сайт {x}",
            "not_found": "Не нашёл программу {x}. Добавьте её в config.json.",
            "search": "Ищу {x}",
            "youtube": "Ищу на ютубе {x}",
            "time": "Сейчас {x}.",
            "date": "Сегодня {x}.",
            "reset": "Хорошо, начнём разговор заново.",
            "switched": "Хорошо, теперь говорю по-русски.",
            "failed": "Не получилось: {x}",
            "no_key": "Чтобы отвечать на вопросы, добавьте ключ ANTHROPIC_API_KEY в файл .env.",
            "bad_key": "Ключ Claude API не подошёл. Проверьте ANTHROPIC_API_KEY.",
            "rate_limit": "Слишком много запросов, попробуйте через минуту.",
            "api_error": "Claude API вернул ошибку {x}.",
            "offline": "Нет связи с интернетом.",
            "refusal": "На этот вопрос я ответить не могу.",
            "no_answer": "Не знаю, что ответить.",
            "speech_offline": "Нет связи с сервисом распознавания речи.",
        },
    },
    "uz": {
        "speech": "uz-UZ",
        # Узбекского голоса в Windows обычно нет, поэтому запасной вариант — турецкий.
        "voice_hints": ["uzbek", "uz-uz", "uz_uz", "turkish", "tr-tr", "tolga", "seda"],
        "months": ["yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul",
                   "avgust", "sentabr", "oktabr", "noyabr", "dekabr"],
        "messages": {
            "hello": "{name} aloqada.",
            "listening": "Eshitaman.",
            "bye": "Ko'rishguncha!",
            "open": "{x} ochilmoqda",
            "open_site": "{x} sayti ochilmoqda",
            "not_found": "{x} dasturini topa olmadim. Uni config.json fayliga qo'shing.",
            "search": "{x} qidirilmoqda",
            "youtube": "YouTube'da {x} qidirilmoqda",
            "time": "Hozir soat {x}.",
            "date": "Bugun {x}.",
            "reset": "Mayli, suhbatni yangidan boshlaymiz.",
            "switched": "Mayli, endi o'zbekcha gaplashaman.",
            "failed": "Bo'lmadi: {x}",
            "no_key": "Savollarga javob berish uchun .env fayliga ANTHROPIC_API_KEY kalitini qo'shing.",
            "bad_key": "Claude API kaliti to'g'ri kelmadi. ANTHROPIC_API_KEY ni tekshiring.",
            "rate_limit": "So'rovlar juda ko'p, bir daqiqadan keyin urinib ko'ring.",
            "api_error": "Claude API {x} xatosini qaytardi.",
            "offline": "Internet aloqasi yo'q.",
            "refusal": "Bu savolga javob bera olmayman.",
            "no_answer": "Nima deyishni bilmayman.",
            "speech_offline": "Nutqni tanish xizmati bilan aloqa yo'q.",
        },
    },
    "tr": {
        "speech": "tr-TR",
        "voice_hints": ["turkish", "tr-tr", "tr_tr", "tolga", "seda", "emel", "ahmet"],
        "months": ["Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz",
                   "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"],
        "messages": {
            "hello": "{name} hazır.",
            "listening": "Dinliyorum.",
            "bye": "Görüşürüz!",
            "open": "{x} açılıyor",
            "open_site": "{x} sitesi açılıyor",
            "not_found": "{x} programını bulamadım. config.json dosyasına ekleyin.",
            "search": "{x} aranıyor",
            "youtube": "YouTube'da {x} aranıyor",
            "time": "Saat {x}.",
            "date": "Bugün {x}.",
            "reset": "Tamam, sohbete baştan başlayalım.",
            "switched": "Tamam, artık Türkçe konuşuyorum.",
            "failed": "Olmadı: {x}",
            "no_key": "Sorulara cevap vermem için .env dosyasına ANTHROPIC_API_KEY anahtarını ekleyin.",
            "bad_key": "Claude API anahtarı geçersiz. ANTHROPIC_API_KEY değerini kontrol edin.",
            "rate_limit": "Çok fazla istek var, bir dakika sonra tekrar deneyin.",
            "api_error": "Claude API {x} hatası döndürdü.",
            "offline": "İnternet bağlantısı yok.",
            "refusal": "Bu soruya cevap veremem.",
            "no_answer": "Ne diyeceğimi bilemedim.",
            "speech_offline": "Konuşma tanıma hizmetine bağlanılamıyor.",
        },
    },
}


def lang_code(value: str | None) -> str:
    """«ru-RU», «RU», «ru» → «ru». Неизвестный язык заменяется русским."""
    code = (value or DEFAULT_LANGUAGE).lower()[:2]
    return code if code in LANGUAGES else DEFAULT_LANGUAGE


def t(lang: str, key: str, **kwargs) -> str:
    return LANGUAGES[lang_code(lang)]["messages"][key].format(**kwargs)


def format_date(lang: str, day: int, month: int) -> str:
    months = LANGUAGES[lang_code(lang)]["months"]
    return f"{day} {months[month - 1]}"
