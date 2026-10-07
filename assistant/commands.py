"""Разбор распознанной фразы в команду. Здесь нет ввода-вывода, поэтому всё легко тестировать.

Понимает русский, узбекский и турецкий. В узбекском и турецком глагол обычно
стоит в конце («telegramni och», «YouTube'u aç»), поэтому ищем его и в начале, и в конце.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

OPEN_PREFIX = ("открой", "открыть", "запусти", "запустить", "включи", "open", "run",
               "aç", "ac", "och", "başlat")
OPEN_SUFFIX = ("açar mısın", "açsana", "aç", "ac", "başlat", "ochib bering", "ochib ber",
               "oching", "och", "ishga tushiring", "ishga tushir")
SEARCH_PREFIX = ("найди", "найти", "поищи", "загугли", "поиск", "ara", "qidir", "izla", "search")
SEARCH_SUFFIX = ("araştır", "arat", "ara", "bul", "qidirib bering", "qidirib ber",
                 "qidiring", "qidir", "izla", "top")
YOUTUBE_MARKERS = ("на ютубе", "в ютубе", "на youtube", "в youtube", "на ютюбе",
                   "youtubeda", "youtubedan", "youtubeta", "yutubda", "ютубда")
TIME_PHRASES = ("который час", "сколько времени", "сколько время",
                "saat kaç", "saat kac", "soat necha", "soat nechchi", "vaqt necha")
DATE_PHRASES = ("какое сегодня число", "какая сегодня дата", "какой сегодня день",
                "bugün ayın kaçı", "bugün tarih", "bugün günlerden ne",
                "bugun sana", "bugun nechanchi", "bugun qaysi kun")
EXIT_PHRASES = ("выход", "стоп", "пока", "до свидания", "выключись", "отключись",
                "dur", "kapan", "çıkış", "cikis", "güle güle", "toxta", "chiqish", "xayr")
RESET_PHRASES = ("забудь разговор", "новый разговор", "начни сначала",
                 "sohbeti unut", "yeni sohbet", "suhbatni unut", "yangi suhbat")
FILLER_WORDS = ("пожалуйста", "please", "ну", "а", "lütfen", "iltimos")

LANGUAGE_WORDS = {
    "ru": ("по-русски", "по русски", "на русском", "русский", "ruscha", "ruschaga", "rus tiliga",
           "rusça", "rusca", "rusçaya", "russian"),
    "uz": ("по-узбекски", "по узбекски", "на узбекском", "узбекский",
           "ozbekcha", "ozbekchaga", "ozbek tilida", "ozbek tiliga", "özbekçe", "özbekçeye",
           "ozbekce", "uzbek"),
    "tr": ("по-турецки", "по турецки", "на турецком", "турецкий",
           "turkcha", "turkchaga", "turk tiliga", "türkçe", "türkçeye", "turkce", "turkish"),
}
SWITCH_WORDS = {"говори", "давай", "перейди", "переключись", "на", "язык", "разговаривай",
                "gapir", "gapiring", "tilni", "til", "gaplash", "gaplashamiz", "tilida", "tiliga", "otish", "otamiz",
                "konuş", "konus", "konuşalım", "dil", "geç", "switch", "to", "speak"}


@dataclass
class Command:
    action: str  # open | search | youtube | time | date | exit | reset | lang | ask | empty
    arg: str = ""


def normalize(text: str) -> str:
    text = text.replace("İ", "i").lower().replace("ё", "е")
    # Апострофы убираем совсем: «o'zbekcha» → «ozbekcha», «YouTube'u» → «youtubeu».
    text = re.sub(r"['’ʻʼ`]", "", text)
    text = re.sub(r"[^\w\s:/.-]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def strip_wake_word(text: str, wake_words: list[str]) -> tuple[bool, str]:
    """Возвращает (было ли обращение, остаток фразы без имени ассистента)."""
    norm = normalize(text)
    for word in wake_words:
        word = normalize(word)
        match = re.search(rf"(^|\s){re.escape(word)}\w*(\s|$)", norm)
        if match:
            rest = (norm[: match.start()] + " " + norm[match.end():]).strip()
            return True, re.sub(r"\s+", " ", rest)
    return False, norm


def _strip_prefix(text: str, prefixes: tuple[str, ...]) -> str | None:
    for prefix in prefixes:
        if text == prefix:
            return ""
        if text.startswith(prefix + " "):
            return text[len(prefix) + 1:].strip()
    return None


def _strip_suffix(text: str, suffixes: tuple[str, ...]) -> str | None:
    for suffix in suffixes:
        if text == suffix:
            return ""
        if text.endswith(" " + suffix):
            return text[: -len(suffix) - 1].strip()
    return None


def _strip_verb(text: str, prefixes: tuple[str, ...], suffixes: tuple[str, ...]) -> str | None:
    rest = _strip_prefix(text, prefixes)
    return rest if rest is not None else _strip_suffix(text, suffixes)


def _language_switch(text: str) -> str | None:
    for code, phrases in LANGUAGE_WORDS.items():
        for phrase in phrases:
            if re.search(rf"(^|\s){re.escape(phrase)}(\s|$)", text):
                rest = text.replace(phrase, " ").split()
                if all(word in SWITCH_WORDS for word in rest):
                    return code
    return None


def app_name_variants(name: str) -> list[str]:
    """«telegramni» → «telegram», «youtubeu» → «youtube»: убираем узбекские и турецкие окончания."""
    variants = [name]
    if len(name) > 5 and name.endswith("ni"):
        variants.append(name[:-2])
    if len(name) > 4 and name[-1] in "ıiuü":
        variants.append(name[:-1])
        if name[-2] == "y":
            variants.append(name[:-2])
    return variants


def parse(text: str) -> Command:
    text = normalize(text)
    # Вежливые слова не мешают командам: «пожалуйста открой ютуб».
    text = re.sub(rf"(^|\s)({'|'.join(FILLER_WORDS)})(?=\s|$)", " ", text)
    text = re.sub(r"\s+", " ", text).strip()

    if not text:
        return Command("empty")
    if text in EXIT_PHRASES:
        return Command("exit")
    if any(p in text for p in RESET_PHRASES):
        return Command("reset")
    lang = _language_switch(text)
    if lang:
        return Command("lang", lang)
    if any(p in text for p in TIME_PHRASES):
        return Command("time")
    if any(p in text for p in DATE_PHRASES):
        return Command("date")

    for marker in YOUTUBE_MARKERS:
        if re.search(rf"(^|\s){re.escape(marker)}(\s|$)", text):
            query = re.sub(r"\s+", " ", text.replace(marker, " ")).strip()
            rest = _strip_verb(query, SEARCH_PREFIX + OPEN_PREFIX, SEARCH_SUFFIX + OPEN_SUFFIX)
            query = rest if rest is not None else query
            # «открой ютуб» без запроса — просто открыть сайт.
            return Command("youtube", query) if query else Command("open", "youtube")

    rest = _strip_verb(text, SEARCH_PREFIX, SEARCH_SUFFIX)
    if rest is not None:
        rest = re.sub(r"^(в интернете|в гугле|в google)\s*", "", rest).strip()
        return Command("search", rest) if rest else Command("ask", text)

    rest = _strip_verb(text, OPEN_PREFIX, OPEN_SUFFIX)
    if rest:
        rest = re.sub(r"^(мне|программу|приложение|сайт)\s+", "", rest).strip()
        return Command("open", rest)

    return Command("ask", text)
