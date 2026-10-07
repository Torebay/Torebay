"""Ответы на свободные вопросы через Claude API."""

from __future__ import annotations

import os


class Brain:
    def __init__(self, name: str, settings: dict):
        self.name = name
        self.model = settings.get("model", "claude-opus-5-5")
        self.effort = settings.get("effort", "low")
        self.max_tokens = settings.get("max_tokens", 1024)
        self.history_turns = settings.get("history_turns", 10)
        self.history: list[dict] = []
        self._client = None

    @property
    def available(self) -> bool:
        return bool(os.environ.get("ANTHROPIC_API_KEY"))

    @property
    def system_prompt(self) -> str:
        return (
            f"Ты голосовой ассистент по имени {self.name} на компьютере пользователя. "
            "Отвечай на русском, коротко (одно-три предложения), простым разговорным языком: "
            "ответ будет зачитан вслух, поэтому без списков, markdown, ссылок и эмодзи."
        )

    def reset(self) -> None:
        self.history.clear()

    def ask(self, question: str) -> str:
        if not self.available:
            return "Чтобы отвечать на вопросы, добавьте ключ ANTHROPIC_API_KEY в файл .env."

        import anthropic

        if self._client is None:
            self._client = anthropic.Anthropic()

        self.history.append({"role": "user", "content": question})
        try:
            response = self._client.beta.messages.create(
                model=self.model,
                max_tokens=self.max_tokens,
                system=self.system_prompt,
                output_config={"effort": self.effort},
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
                messages=self.history,
            )
        except anthropic.AuthenticationError:
            self.history.pop()
            return "Ключ Claude API не подошёл. Проверьте ANTHROPIC_API_KEY."
        except anthropic.RateLimitError:
            self.history.pop()
            return "Слишком много запросов, попробуйте через минуту."
        except anthropic.APIStatusError as exc:
            self.history.pop()
            return f"Claude API вернул ошибку {exc.status_code}."
        except anthropic.APIConnectionError:
            self.history.pop()
            return "Нет связи с интернетом."

        if response.stop_reason == "refusal":
            self.history.pop()
            return "На этот вопрос я ответить не могу."

        text = " ".join(b.text for b in response.content if b.type == "text").strip()
        # Храним только текст ответа: без блоков размышлений историю можно спокойно обрезать.
        self.history.append({"role": "assistant", "content": text or "..."})
        self._trim_history()
        return text or "Не знаю, что ответить."

    def _trim_history(self) -> None:
        limit = self.history_turns * 2
        if len(self.history) > limit:
            # Отрезаем с начала целыми парами вопрос-ответ.
            del self.history[: len(self.history) - limit]
