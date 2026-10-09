"""Ответы на свободные вопросы через Claude API, с поиском в интернете."""

from __future__ import annotations

import datetime
import os

from .i18n import t
from .memory import Memory
from .speech_text import SentenceSplitter

LANGUAGE_NAMES = {"ru": "русском", "uz": "узбекском (латиница)", "tr": "турецком"}
MAX_STEPS = 8  # продолжения поиска и вызовы инструментов компьютера за один вопрос


class Brain:
    def __init__(self, name: str, settings: dict, memory: Memory | None = None, tools=None):
        self.name = name
        self.model = settings.get("model", "claude-opus-5-5")
        self.effort = settings.get("effort", "low")
        self.max_tokens = settings.get("max_tokens", 2048)
        self.history_turns = settings.get("history_turns", 10)
        self.web_search = settings.get("web_search", True)
        self.max_searches = settings.get("max_searches", 3)
        self.memory = memory or Memory(None)  # без файла — только на время работы
        self.tools = tools  # PcTools: управление компьютером; None — только разговор
        self._client = None

    @property
    def history(self) -> list[dict]:
        return self.memory.history

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
            "Отвечай коротко (одно-три предложения), простым разговорным языком, сразу по делу, "
            "без вступлений вроде «сейчас поищу»: "
            "ответ будет зачитан вслух, поэтому без списков, markdown, ссылок и эмодзи. "
            "Для свежих данных (курсы валют, акции и криптовалюты на бирже, новости, погода, "
            "результаты матчей) ищи в интернете и называй числа и время, к которому они относятся. "
            "Не давай советов, что покупать или продавать. "
            f"Сегодня {today}."
            + self._tools_prompt()
            + self._facts_prompt()
        )

    def _tools_prompt(self) -> str:
        if self.tools is None:
            return ""
        return (
            " Ты можешь управлять компьютером через инструменты: громкость, музыка, папки, поиск и "
            "открытие файлов, блокировка, сон, выключение, снимок экрана. Делай то, что просят, и "
            "коротко скажи, что сделал. Перед выключением или перезагрузкой сначала спроси "
            "подтверждение и вызывай инструмент только после явного «да». "
            "Когда тебя просят что-то запомнить, сохрани это инструментом remember."
        )

    def _facts_prompt(self) -> str:
        if not self.memory.facts:
            return ""
        return " Что ты знаешь о пользователе: " + "; ".join(self.memory.facts) + "."

    def reset(self) -> None:
        self.history.clear()
        self.memory.save()

    def _tools(self) -> list[dict]:
        tools = []
        if self.web_search:
            tools.append({"type": "web_search_20260209", "name": "web_search", "max_uses": self.max_searches})
        if self.tools is not None:
            from .pc_tools import TOOLS

            tools += TOOLS
        return tools

    def ask(self, question: str, lang: str = "ru", on_sentence=None) -> str:
        """Ответ на вопрос. Ответ приходит потоком: on_sentence получает каждое готовое
        предложение сразу, чтобы его можно было начать читать, пока остальное ещё пишется."""
        if not self.available:
            return t(lang, "no_key")

        import anthropic

        if self._client is None:
            self._client = anthropic.Anthropic()

        splitter = SentenceSplitter(on_sentence or (lambda sentence: None))
        messages = [*self.history, {"role": "user", "content": question}]
        texts = []
        try:
            response = None
            for _ in range(MAX_STEPS + 1):
                response = self._stream(lang, messages, splitter)
                texts.append("".join(b.text for b in response.content if b.type == "text"))
                if response.stop_reason == "pause_turn":
                    # Поиск ещё идёт: отправляем ответ обратно, сервер продолжит с того же места.
                    messages = [*messages, {"role": "assistant", "content": response.content}]
                elif response.stop_reason == "tool_use" and self.tools is not None:
                    splitter.flush()  # «Сейчас сделаю тише» звучит, пока инструмент работает
                    messages = [*messages, {"role": "assistant", "content": response.content},
                                {"role": "user", "content": self._run_tools(response.content)}]
                else:
                    break
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
        splitter.flush()

        text = " ".join(x.strip() for x in texts if x.strip())
        if not text:
            return t(lang, "no_answer")
        # Храним только текст: без блоков размышлений и поиска историю можно спокойно обрезать.
        self.history.extend([{"role": "user", "content": question}, {"role": "assistant", "content": text}])
        self._trim_history()
        self.memory.save()
        return text

    def _run_tools(self, content) -> list[dict]:
        results = []
        for block in content:
            if block.type != "tool_use":
                continue
            output = self.tools.execute(block.name, block.input)
            results.append({"type": "tool_result", "tool_use_id": block.id, "content": output})
        return results

    def _stream(self, lang: str, messages: list, splitter: SentenceSplitter):
        with self._client.beta.messages.stream(
            model=self.model,
            max_tokens=self.max_tokens,
            system=self.system_prompt(lang),
            output_config={"effort": self.effort},
            tools=self._tools(),
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            messages=messages,
        ) as stream:
            for event in stream:
                if event.type == "content_block_delta" and event.delta.type == "text_delta":
                    splitter.add(event.delta.text)
                elif event.type == "content_block_start" and event.content_block.type != "text":
                    splitter.flush()  # перед поиском в интернете договариваем то, что уже написано
            return stream.get_final_message()

    def _trim_history(self) -> None:
        limit = self.history_turns * 2
        if len(self.history) > limit:
            # Отрезаем с начала целыми парами вопрос-ответ.
            del self.history[: len(self.history) - limit]
