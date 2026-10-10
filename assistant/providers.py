"""Gemini and OpenAI adapters. No Claude client is used by the desktop app."""
from __future__ import annotations

import json
import os
import threading
import urllib.error
import urllib.parse
import urllib.request

from .memory import Memory
from .speech_text import SentenceSplitter

PROVIDERS = {"gemini": "Gemini", "openai": "OpenAI"}
ENV_KEYS = {"gemini": "GEMINI_API_KEY", "openai": "OPENAI_API_KEY"}


class ProviderError(Exception):
    pass


class RateLimitError(ProviderError):
    pass


def request_events(url, key, provider, payload):
    """Read SSE without waiting for the full model response."""
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
    headers["Authorization" if provider == "openai" else "x-goog-api-key"] = f"Bearer {key}" if provider == "openai" else key
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers)
    received = False
    try:
        with urllib.request.urlopen(request, timeout=35) as response:
            pending = []
            for raw in response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if line.startswith("data:"):
                    pending.append(line[5:].lstrip())
                elif not line and pending:
                    data = "\n".join(pending)
                    pending.clear()
                    if data != "[DONE]":
                        received = True
                        yield json.loads(data)
            if pending and "\n".join(pending) != "[DONE]":
                yield json.loads("\n".join(pending))
    except urllib.error.HTTPError as exc:
        if exc.code in (401, 403):
            raise ProviderError("Ключ не принят или доступ к сервису запрещён.") from None
        if exc.code == 429:
            raise RateLimitError("Сервис ИИ ограничил запрос (429). Возможны временный лимит, исчерпанная квота или отсутствие средств.") from None
        raise ProviderError(f"Ошибка ИИ {exc.code}. Проверьте модель и поддержку поиска в настройках.") from None
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        if provider == "gemini" and not received:
            # Some networks buffer streaming responses; use the ordinary endpoint.
            plain_url = url.replace(":streamGenerateContent?alt=sse", ":generateContent")
            yield request_json(plain_url, key, provider, payload)
            return
        raise ProviderError("Связь с ИИ прервалась. Попробуйте ещё раз.") from None


def request_json(url, key, provider, payload=None):
    headers = {"Content-Type": "application/json"}
    headers["Authorization" if provider == "openai" else "x-goog-api-key"] = (
        f"Bearer {key}" if provider == "openai" else key
    )
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=35) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        messages = {
            400: "Проверьте модель и API-ключ в настройках ИИ.",
            401: "API-ключ не принят. Проверьте ключ в настройках ИИ.",
            403: "Доступ запрещён: проверьте доступность сервиса и разрешения ключа.",
            404: "Модель не найдена. Выберите доступную модель в настройках ИИ.",
            429: "Лимит запросов или баланс исчерпан. Проверьте аккаунт сервиса.",
        }
        raise ProviderError(messages.get(exc.code, f"Сервис ИИ вернул ошибку {exc.code}.")) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise ProviderError("Не удалось связаться с ИИ. Проверьте интернет и доступность сервиса.") from None
    except (ValueError, TypeError):
        raise ProviderError("Сервис вернул некорректный ответ. Попробуйте снова.") from None


def list_models(provider, key):
    if not key.strip():
        raise ProviderError("Сначала вставьте API-ключ выбранного сервиса.")
    if provider == "openai":
        data = request_json("https://api.openai.com/v1/models", key, provider)
        return sorted(m["id"] for m in data.get("data", [])
                      if m["id"].startswith(("gpt-", "o1", "o3", "o4"))
                      and not any(s in m["id"] for s in ("audio", "realtime", "transcribe", "tts", "image")))
    models, token = [], None
    for _ in range(10):
        url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100"
        if token:
            url += "&pageToken=" + urllib.parse.quote(token, safe="")
        data = request_json(url, key, provider)
        models += [m["name"].removeprefix("models/") for m in data.get("models", [])
                   if "generateContent" in m.get("supportedGenerationMethods", [])]
        token = data.get("nextPageToken")
        if not token:
            break
    return sorted(set(models))


class ProviderBrain:
    def __init__(self, name, settings, memory=None, tools=None, key_reader=None):
        self.name = name
        self.settings = dict(settings)
        self.memory = memory if memory is not None else Memory(None)
        self.tools = tools
        self.lock = threading.RLock()
        self.key_reader = key_reader or (lambda provider: os.environ.get(ENV_KEYS[provider], ""))

    @property
    def provider(self):
        selected = self.settings.get("provider", "gemini")
        return selected if selected in PROVIDERS else "gemini"

    @property
    def model(self):
        return self.settings.get(self.provider + "_model", "")

    @property
    def available(self):
        return bool(self.key_reader(self.provider))

    @property
    def web_search(self):
        return self.settings.get("web_search", True)

    @property
    def history(self):
        return self.memory.history

    def configure(self, settings):
        with self.lock:
            self.settings = dict(settings)

    def reset(self):
        with self.lock:
            self.history.clear()
            self.memory.save()

    def ask(self, question, lang="ru", on_sentence=None):
        with self.lock:
            provider, model, search = self.provider, self.model, self.web_search
            key = self.key_reader(provider)
            history = list(self.history[-20:])
        if not key:
            return f"{PROVIDERS[provider]} пока не подключён. Откройте «Настройки ИИ» и добавьте API-ключ. Локальные команды уже работают."
        if not model:
            return "Выберите модель в настройках ИИ: вставьте ключ и нажмите «Загрузить модели»."
        language = {"ru": "русском", "uz": "узбекском", "tr": "турецком"}.get(lang, "русском")
        prompt = (f"Ты ассистент {self.name}. Отвечай кратко на языке пользователя, по умолчанию на {language}. "
                  "Ответ будет прочитан вслух. Не используй markdown. Ты отвечаешь на вопросы, "
                  "но не можешь самостоятельно управлять компьютером. Не утверждай, что выполнил действие. "
                  + ("Для погоды, курсов, криптовалют и новостей используй поиск и указывай дату данных. "
                     "Если поиск не дал подтверждения, честно сообщи об этом." if search else
                     "Поиск отключён. Не утверждай, что проверил свежую информацию."))
        messages = history + [{"role": "user", "content": question}]
        spoken = []
        def emit(sentence):
            spoken.append(sentence)
            if on_sentence:
                on_sentence(sentence)
        splitter = SentenceSplitter(emit)
        pieces, sources = [], []
        try:
            if provider == "openai":
                events = request_events("https://api.openai.com/v1/responses", key, provider, {
                    "model": model, "instructions": prompt, "input": messages,
                    "max_output_tokens": 2048, "store": False, "stream": True,
                    **({"tools": [{"type": "web_search"}]} if search else {}),
                })
                completed = False
                for event in events:
                    if event.get("type") == "response.output_text.delta":
                        chunk = event.get("delta", "")
                        pieces.append(chunk)
                        splitter.add(chunk)
                    elif event.get("type") == "response.completed":
                        completed = True
                        sources = [a.get("url") for item in event.get("response", {}).get("output", [])
                                   for p in item.get("content", []) for a in p.get("annotations", [])
                                   if a.get("type") == "url_citation" and a.get("url")]
                    elif event.get("type") in ("error", "response.failed", "response.incomplete"):
                        raise ProviderError("Ответ ИИ не завершён. Попробуйте ещё раз или выберите другую модель.")
                if not completed:
                    raise ProviderError("Ответ ИИ оборвался. Попробуйте ещё раз.")
            else:
                model_path = urllib.parse.quote(model.removeprefix("models/"), safe="")
                events = request_events(f"https://generativelanguage.googleapis.com/v1beta/models/{model_path}:streamGenerateContent?alt=sse",
                                      key, provider, {
                    "systemInstruction": {"parts": [{"text": prompt}]},
                    "contents": [{"role": "model" if m["role"] == "assistant" else "user",
                                  "parts": [{"text": m["content"]}]} for m in messages],
                    "generationConfig": {"maxOutputTokens": 2048,
                        **({"thinkingConfig": {"thinkingLevel": "LOW"}}
                           if model.removeprefix("models/") == "gemini-3.8-flash" else {})},
                    **({"tools": [{"google_search": {}}]} if search else {}),
                })
                completed = False
                for event in events:
                    candidates = event.get("candidates", [])
                    candidate = candidates[0] if candidates else {}
                    completed = completed or bool(candidate.get("finishReason"))
                    parts = candidate.get("content", {}).get("parts", [])
                    chunk = "".join(p.get("text", "") for p in parts if not p.get("thought"))
                    pieces.append(chunk)
                    splitter.add(chunk)
                    sources += [chunk["web"]["uri"] for chunk in candidate.get("groundingMetadata", {}).get("groundingChunks", [])
                                if chunk.get("web", {}).get("uri")]
                if not completed:
                    raise ProviderError("Ответ Gemini не завершён. Попробуйте ещё раз.")
        except ProviderError as exc:
            if isinstance(exc, RateLimitError) and search and not pieces:
                notice = "Запрос с интернет-поиском ограничен сервисом. Повторяю без поиска; свежие сведения не проверены."
                if on_sentence:
                    on_sentence(notice)
                fallback = ProviderBrain(self.name, {**self.settings, "web_search": False},
                                         memory=self.memory, tools=self.tools, key_reader=self.key_reader)
                answer = fallback.ask(question, lang, on_sentence)
                return notice + "\n" + answer
            if spoken and on_sentence:
                on_sentence(str(exc))
            return str(exc)
        text = "".join(pieces)
        splitter.flush()
        if not text.strip():
            return "ИИ не вернул текст ответа. Попробуйте переформулировать вопрос или выбрать другую модель."
        if sources:
            source_text = "\n\nИсточники:\n" + "\n".join(dict.fromkeys(sources))
            text += source_text
            if on_sentence:
                on_sentence(source_text)
        with self.lock:
            self.history.extend([{"role": "user", "content": question}, {"role": "assistant", "content": text}])
            del self.history[:-20]
            self.memory.save()
        return text.strip()
