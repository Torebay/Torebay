"""Подготовка ответа к чтению вслух и нарезка потока на предложения."""

from __future__ import annotations

import re
from typing import Callable

_MARKDOWN_LINK = re.compile(r"\[([^\]]+)\]\([^)]*\)")
_URL = re.compile(r"(?:https?://|www\.)\S+")
_CITATION = re.compile(r"\[\d+(?:,\s*\d+)*\]")
_LIST_MARKER = re.compile(r"(?m)^\s*(?:[-•]|\d+[.)])\s+")
_MARKUP = re.compile(r"[*_`#>|~]+")
_EMOJI = re.compile("[\U0001F000-\U0001FFFF☀-➿️‍]")
_SPACES = re.compile(r"[ \t]+")


def clean(text: str) -> str:
    """Убирает то, что синтезатор читает плохо: markdown, ссылки, сноски, эмодзи."""
    s = _MARKDOWN_LINK.sub(r"\1", text)
    s = _URL.sub("", s)
    s = _CITATION.sub("", s)
    s = _LIST_MARKER.sub("", s)
    s = _MARKUP.sub(" ", s)
    s = _EMOJI.sub("", s)
    s = s.replace("\n", " ")
    return _SPACES.sub(" ", s).replace(" ,", ",").replace(" .", ".").strip()


class SentenceSplitter:
    """Режет поток текста на предложения, чтобы начинать говорить, пока ответ ещё пишется."""

    def __init__(self, emit: Callable[[str], None], min_length: int = 2):
        self.emit = emit
        self.min_length = min_length
        self.buffer = ""

    def add(self, delta: str) -> None:
        self.buffer += delta
        while (end := self._sentence_end()) is not None:
            sentence, self.buffer = self.buffer[:end].strip(), self.buffer[end:]
            if sentence:
                self.emit(sentence)

    def flush(self) -> None:
        rest, self.buffer = self.buffer.strip(), ""
        if rest:
            self.emit(rest)

    def _sentence_end(self) -> int | None:
        for i, c in enumerate(self.buffer):
            if c == "\n":
                end = i + 1
            elif c in ".!?…" and i + 1 < len(self.buffer) and self.buffer[i + 1].isspace():
                end = i + 1
            else:
                continue
            if len(self.buffer[:end].strip()) >= self.min_length:
                return end
        return None
