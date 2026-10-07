"""Ответы на свободные вопросы через Claude API, с поиском в интернете."""

from __future__ import annotations

import datetime
import os

from .i18n import t

LANGUAGE_NAMES = {"ru": "русском", "uz": "узбекском (латиница)", "tr": "турецком"}
MAX_CONTINUATIONS = 3


class Brain:
    def __init__(self, name: str, settings: dict):
        self.name = name
        self.model = settings.get("model", "claude-opus-5-5")
        self.effort = settings.get("effort", "low")
        self.max_tokens = settings.get("max_tokens", 2048)
        self.history_turns = settings.get("history_turns", 10)
        self.web_search = settings.get("web_search", True)
        self.max_searches = settings.get("max_searches", 3)
        self.history: list[dict] = []
        self._client = None

    @property
    def available(self) -> bool:
        return bool(os.environ.get("ANTHROPIC_API_KEY"))

    def system_prompt(self, lang: str) -> str:
        today = datetime.date.today().isoformat()
        return (
            f"Ты голосовой ассистент по имени {self.name} на компьютере пользователя. "
            "Пользователь говорит по-русски, по-узбекски или по-турецки. "
            "Отвечай на том языке, на котором задан вопрос; если язык непонятен, "
            f"отвечай на {LANGUAGE_NAMES.get(lang, 'русском')}. "
            "Отвечай коротко (одно-три предложения), простым разговорным языком: "
            "ответ будет зачитан вслух, поэтому без списков, markdown, ссылок и эмодзи. "
            "Для свежих данных (курсы валют, акции и криптовалюты на бирже, новости, погода, "
            "результаты матчей) ищи в интернете и называй числа и время, к которому они относятся. "
            "Не давай советов, что покупать или продавать. "
            f"Сегодня {today}."
        )

    def reset(self) -> None:
        self.history.clear()

    def _tools(self) -> list[dict]:
        if not self.web_search:
            return []
        return [{"type": "web_search_20260209", "name": "web_search", "max_uses": self.max_searches}]

    def ask(self, question: str, lang: str = "ru") -> str:
        if not self.available:
            return t(lang, "no_key")

        import anthropic

        if self._client is None:
            self._client = anthropic.Anthropic()

        messages = [*self.history, {"role": "user", "content": question}]
        try:
            response = None
            for _ in range(MAX_CONTINUATIONS + 1):
                response = self._client.beta.messages.create(
                    model=self.model,
                    max_tokens=self.max_tokens,
                    system=self.system_prompt(lang),
                    output_config={"effort": self.effort},
                    tools=self._tools(),
                    betas=["server-side-fallback-2026-07-01"],
                    fallbacks="default",
                    messages=messages,
                )
                if response.stop_reason != "pause_turn":
                    break
                # Поиск ещё идёт: отправляем ответ обратно, сервер продолжит с того же места.
                messages = [*messages, {"role": "assistant", "content": response.content}]
        except anthropic.AuthenticationError:
            return t(lang, "bad_key")
        except anthropic.RateLimitError:
            return t(lang, "rate_limit")
        except anthropic.APIStatusError as exc:
            return t(lang, "api_error", x=exc.status_code)
        except anthropic.APIConnectionError:
            return t(lang, "offline")

        if response.stop_reason == "refusal":
            return t(lang, "refusal")

        text = "".join(b.text for b in response.content if b.type == "text").strip()
        if not text:
            return t(lang, "no_answer")
        # Храним только текст: без блоков размышлений и поиска историю можно спокойно обрезать.
        self.history += [{"role": "user", "content": question}, {"role": "assistant", "content": text}]
        self._trim_history()
        return text

    def _trim_history(self) -> None:
        limit = self.history_turns * 2
        if len(self.history) > limit:
            # Отрезаем с начала целыми парами вопрос-ответ.
            del self.history[: len(self.history) - limit]
