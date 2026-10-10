"""Low-buffer PCM playback from the OpenAI speech API."""
import json
import time
import urllib.request

from .secrets import get_key


class OpenAIVoice:
    def __init__(self, settings):
        self.settings = settings
        self.retry_after = 0

    def say(self, text, lang):
        key = get_key("openai")
        if not key or time.monotonic() < self.retry_after:
            raise RuntimeError("Нейроголос не подключён")
        voice = self.settings.get("openai_voice", "cedar")
        if voice not in ("cedar", "marin"):
            voice = "cedar"
        payload = {"model": "gpt-4o-mini-tts", "voice": voice, "input": text[:4000],
                   "response_format": "pcm", "speed": 1.0,
                   "instructions": "Speak clearly and naturally in the language of the text. Use crisp pronunciation and short pauses. Do not rush."}
        request = urllib.request.Request("https://api.openai.com/v1/audio/speech",
                                         data=json.dumps(payload).encode(),
                                         headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
        import pyaudio
        audio = pyaudio.PyAudio()
        stream = None
        try:
            with urllib.request.urlopen(request, timeout=12) as response:
                stream = audio.open(format=pyaudio.paInt16, channels=1, rate=24000, output=True,
                                    frames_per_buffer=1024)
                count = 0
                while chunk := response.read(2048):
                    stream.write(chunk)
                    count += len(chunk)
                if not count:
                    raise RuntimeError("Сервис не вернул звук")
        except Exception:
            self.retry_after = time.monotonic() + 60
            raise RuntimeError("Нейроголос недоступен, используется голос Windows") from None
        finally:
            if stream:
                stream.stop_stream()
                stream.close()
            audio.terminate()
