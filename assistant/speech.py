"""Распознавание речи (микрофон → текст) и синтез речи (текст → голос).

Тяжёлые библиотеки импортируются внутри классов, чтобы текстовый режим и тесты
работали без микрофона и звуковых драйверов.
"""

from __future__ import annotations

import asyncio
import os
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

from .i18n import LANGUAGES, lang_code, t
from .speech_text import clean

# Нейроголоса Microsoft (как в Edge): звучат как живой человек, есть даже узбекский.
EDGE_VOICES = {
    "ru": {"male": "ru-RU-DmitryNeural", "female": "ru-RU-SvetlanaNeural"},
    "uz": {"male": "uz-UZ-SardorNeural", "female": "uz-UZ-MadinaNeural"},
    "tr": {"male": "tr-TR-AhmetNeural", "female": "tr-TR-EmelNeural"},
}


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

    def say(self, text: str, append: bool = False) -> None:
        print(f"{self.name}: {text}" if not append else f"  {text}")


class WindowIO(TextIO):
    """Poll the window's command queue without blocking on a hidden console."""
    window_only = True

    def listen(self):
        import time
        time.sleep(0.1)
        return None


def play_mp3(path: str) -> None:
    """Проигрывает mp3 и ждёт конца. На Windows — встроенным MCI, без лишних программ."""
    if sys.platform != "win32":
        raise RuntimeError("mp3 playback is implemented for Windows only")
    import ctypes

    mci = ctypes.windll.winmm.mciSendStringW
    alias = f"kartal{os.getpid()}"
    if mci(f'open "{path}" type mpegvideo alias {alias}', None, 0, None) != 0:
        raise RuntimeError("MCI could not open the file")
    try:
        mci(f"play {alias} wait", None, 0, None)
    finally:
        mci(f"close {alias}", None, 0, None)


class EdgeVoice:
    """Голос через edge-tts. Следующую фразу готовит заранее, пока звучит текущая."""

    def __init__(self, gender: str = "male", rate: str = "+0%"):
        import edge_tts  # нет пакета — VoiceIO возьмёт голос Windows

        self.edge_tts = edge_tts
        self.gender = gender if gender in ("male", "female") else "male"
        self.rate = rate
        self.pool = ThreadPoolExecutor(max_workers=2)
        self.ready = {}  # (язык, текст) -> задача с путём к mp3

    def _synthesize(self, text: str, lang: str) -> str:
        fd, path = tempfile.mkstemp(prefix="kartal-", suffix=".mp3")
        os.close(fd)
        voice = EDGE_VOICES[lang_code(lang)][self.gender]
        try:
            asyncio.run(self.edge_tts.Communicate(text, voice, rate=self.rate, connect_timeout=5).save(path))
        except Exception:
            os.remove(path)
            raise
        return path

    def prefetch(self, text: str, lang: str) -> None:
        key = (lang, text)
        if text and key not in self.ready:
            self.ready[key] = self.pool.submit(self._synthesize, text, lang)

    def say(self, text: str, lang: str) -> None:
        self.prefetch(text, lang)
        path = self.ready.pop((lang, text)).result(timeout=20)
        try:
            play_mp3(path)
        finally:
            try:
                os.remove(path)
            except OSError:
                pass


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
        self.recognizer.operation_timeout = 8
        self.recognizer.pause_threshold = 0.6  # меньше ждём тишины после фразы
        self.microphone = sr.Microphone()
        with self.microphone as source:
            self.recognizer.adjust_for_ambient_noise(source, duration=1)

        voice = voice or {}
        self.voice_settings = voice
        self.cloud_voice = None
        self.edge = None
        if voice.get("engine", "edge") == "edge":
            try:
                self.edge = EdgeVoice(voice.get("gender", "male"), voice.get("edge_rate", "+0%"))
            except ImportError:
                print("Пакет edge-tts не установлен — говорю голосом Windows. Установка: pip install edge-tts")
        self.engine = pyttsx3.init()
        self.engine.setProperty("rate", voice.get("rate", 185))
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
                audio = self.recognizer.listen(source, timeout=getattr(self, "listen_timeout", 1), phrase_time_limit=12)
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

    def prefetch(self, text: str) -> None:
        """Начать готовить звук фразы заранее (можно вызывать из другого потока)."""
        if self.edge is not None:
            self.edge.prefetch(clean(text), self.lang)

    def say(self, text: str, append: bool = False) -> None:
        print(f"{self.name}: {text}" if not append else f"  {text}")
        spoken = clean(text)
        if not spoken:
            return
        if self.voice_settings.get("engine") == "openai":
            if self.cloud_voice is None:
                from .cloud_voice import OpenAIVoice
                self.cloud_voice = OpenAIVoice(self.voice_settings)
            try:
                self.cloud_voice.say(spoken, self.lang)
                return
            except RuntimeError:
                print("Нейроголос недоступен — используется голос Windows.")
        if self.edge is not None:
            try:
                self.edge.say(spoken, self.lang)
                return
            except Exception as exc:  # нет интернета или сервис не ответил — говорим голосом Windows
                print(f"(нейроголос недоступен: {exc})")
        self.engine.setProperty("rate", self.voice_settings.get("rate", 175))
        self.engine.say(spoken)
        self.engine.runAndWait()
