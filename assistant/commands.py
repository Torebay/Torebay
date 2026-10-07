"""Разбор распознанной фразы в команду. Здесь нет ввода-вывода, поэтому всё легко тестировать."""

from __future__ import annotations

import re
from dataclasses import dataclass

OPEN_VERBS = ("открой", "открыть", "запусти", "запустить", "включи", "open", "run")
SEARCH_VERBS = ("найди", "найти", "поищи", "загугли", "поиск")
YOUTUBE_MARKERS = ("на ютубе", "в ютубе", "на youtube", "в youtube", "на ютюбе")
TIME_PHRASES = ("который час", "сколько времени", "сколько время")
DATE_PHRASES = ("какое сегодня число", "какая сегодня дата", "какой сегодня день")
EXIT_PHRASES = ("выход", "стоп", "пока", "до свидания", "выключись", "отключись")
RESET_PHRASES = ("забудь разговор", "новый разговор", "начни сначала")


@dataclass
class Command:
    action: str  # open | search | youtube | time | date | exit | reset | ask | empty
    arg: str = ""


def normalize(text: str) -> str:
    text = text.lower().replace("ё", "е")
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


def parse(text: str) -> Command:
    text = normalize(text)
    # Вежливые слова не мешают командам: «пожалуйста открой ютуб».
    text = re.sub(r"\b(пожалуйста|please|ну|а)\b", " ", text)
    text = re.sub(r"\s+", " ", text).strip()

    if not text:
        return Command("empty")
    if text in EXIT_PHRASES:
        return Command("exit")
    if any(p in text for p in RESET_PHRASES):
        return Command("reset")
    if any(p in text for p in TIME_PHRASES):
        return Command("time")
    if any(p in text for p in DATE_PHRASES):
        return Command("date")

    for marker in YOUTUBE_MARKERS:
        if marker in text:
            query = text.replace(marker, " ")
            rest = _strip_prefix(query.strip(), SEARCH_VERBS + OPEN_VERBS)
            query = re.sub(r"\s+", " ", rest if rest is not None else query).strip()
            # «открой ютуб» без запроса — просто открыть сайт.
            return Command("youtube", query) if query else Command("open", "ютуб")

    rest = _strip_prefix(text, SEARCH_VERBS)
    if rest is not None:
        rest = re.sub(r"^(в интернете|в гугле|в google)\s*", "", rest).strip()
        return Command("search", rest) if rest else Command("ask", text)

    rest = _strip_prefix(text, OPEN_VERBS)
    if rest is not None and rest:
        rest = re.sub(r"^(мне|программу|приложение|сайт)\s+", "", rest).strip()
        return Command("open", rest)

    return Command("ask", text)
