"""Распознавание речи (микрофон → текст) и синтез речи (текст → голос).

Тяжёлые библиотеки импортируются внутри классов, чтобы текстовый режим и тесты
работали без микрофона и звуковых драйверов.
"""

from __future__ import annotations


class TextIO:
    """Режим без микрофона: ввод с клавиатуры, ответы в консоль."""

    def __init__(self, name: str):
        self.name = name

    def listen(self) -> str | None:
        try:
            return input("Вы: ")
        except EOFError:
            return "выход"

    def say(self, text: str) -> None:
        print(f"{self.name}: {text}")


class VoiceIO:
    def __init__(self, name: str, language: str = "ru-RU", voice: dict | None = None):
        import pyttsx3
        import speech_recognition as sr

        self.name = name
        self.language = language
        self.sr = sr
        self.recognizer = sr.Recognizer()
        self.recognizer.pause_threshold = 0.8
        self.microphone = sr.Microphone()
        with self.microphone as source:
            self.recognizer.adjust_for_ambient_noise(source, duration=1)

        self.engine = pyttsx3.init()
        voice = voice or {}
        self.engine.setProperty("rate", voice.get("rate", 185))
        prefer = voice.get("prefer_voice", "russian").lower()
        for v in self.engine.getProperty("voices"):
            label = f"{v.name} {v.id} {' '.join(map(str, v.languages or []))}".lower()
            if prefer in label or "ru" in label.split("_"):
                self.engine.setProperty("voice", v.id)
                break

    def listen(self) -> str | None:
        print("Слушаю...")
        with self.microphone as source:
            try:
                audio = self.recognizer.listen(source, timeout=8, phrase_time_limit=12)
            except self.sr.WaitTimeoutError:
                return None
        try:
            text = self.recognizer.recognize_google(audio, language=self.language)
        except self.sr.UnknownValueError:
            return None
        except self.sr.RequestError:
            self.say("Нет связи с сервисом распознавания речи.")
            return None
        print(f"Вы: {text}")
        return text

    def say(self, text: str) -> None:
        print(f"{self.name}: {text}")
        self.engine.say(text)
        self.engine.runAndWait()
