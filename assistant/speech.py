"""Распознавание речи (микрофон → текст) и синтез речи (текст → голос).

Тяжёлые библиотеки импортируются внутри классов, чтобы текстовый режим и тесты
работали без микрофона и звуковых драйверов.
"""

from __future__ import annotations

from .i18n import LANGUAGES, lang_code, t


class TextIO:
    """Режим без микрофона: ввод с клавиатуры, ответы в консоль."""

    def __init__(self, name: str, lang: str = "ru"):
        self.name = name
        self.lang = lang_code(lang)

    def set_language(self, lang: str) -> None:
        self.lang = lang_code(lang)

    def listen(self) -> str | None:
        try:
            return input("Вы: ")
        except EOFError:
            return "выход"

    def say(self, text: str) -> None:
        print(f"{self.name}: {text}")


def pick_voice(voices, lang: str):
    """Выбирает голос Windows по подсказкам языка (первая подсказка — самая желанная)."""
    labels = []
    for v in voices:
        langs = " ".join(str(x) for x in (getattr(v, "languages", None) or []))
        labels.append((f"{v.name} {v.id} {langs}".lower(), v))
    for hint in LANGUAGES[lang_code(lang)]["voice_hints"]:
        for label, voice in labels:
            if hint in label:
                return voice
    return None


class VoiceIO:
    def __init__(self, name: str, lang: str = "ru", voice: dict | None = None):
        import pyttsx3
        import speech_recognition as sr

        self.name = name
        self.sr = sr
        self.recognizer = sr.Recognizer()
        self.recognizer.pause_threshold = 0.8
        self.microphone = sr.Microphone()
        with self.microphone as source:
            self.recognizer.adjust_for_ambient_noise(source, duration=1)

        self.engine = pyttsx3.init()
        self.engine.setProperty("rate", (voice or {}).get("rate", 185))
        self.voices = self.engine.getProperty("voices")
        self.set_language(lang)

    def set_language(self, lang: str) -> None:
        self.lang = lang_code(lang)
        chosen = pick_voice(self.voices, self.lang)
        if chosen is not None:
            self.engine.setProperty("voice", chosen.id)

    def listen(self) -> str | None:
        print("...")
        with self.microphone as source:
            try:
                audio = self.recognizer.listen(source, timeout=8, phrase_time_limit=15)
            except self.sr.WaitTimeoutError:
                return None
        try:
            text = self.recognizer.recognize_google(audio, language=LANGUAGES[self.lang]["speech"])
        except self.sr.UnknownValueError:
            return None
        except self.sr.RequestError:
            self.say(t(self.lang, "speech_offline"))
            return None
        print(f"Вы: {text}")
        return text

    def say(self, text: str) -> None:
        print(f"{self.name}: {text}")
        self.engine.say(text)
        self.engine.runAndWait()
