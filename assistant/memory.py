"""Память между перезапусками: последние реплики разговора и факты, которые попросили запомнить."""

from __future__ import annotations

import json
from pathlib import Path

from .config import ROOT

DEFAULT_PATH = ROOT / "memory.json"
MAX_FACTS = 50


class Memory:
    def __init__(self, path: Path | str | None = DEFAULT_PATH):
        self.path = Path(path) if path else None
        self.history: list[dict] = []
        self.facts: list[str] = []
        self.load()

    def load(self) -> None:
        if not self.path or not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return  # испорченный файл не должен мешать запуску
        self.history = [m for m in data.get("history", [])
                        if m.get("role") in ("user", "assistant") and isinstance(m.get("content"), str)]
        self.facts = [f for f in data.get("facts", []) if isinstance(f, str)][:MAX_FACTS]

    def save(self) -> None:
        if not self.path:
            return
        try:
            self.path.write_text(json.dumps({"history": self.history, "facts": self.facts},
                                            ensure_ascii=False, indent=1), encoding="utf-8")
        except OSError:
            pass

    def remember(self, fact: str) -> None:
        fact = " ".join(fact.split())
        if fact and fact not in self.facts:
            self.facts.append(fact)
            del self.facts[:-MAX_FACTS]
            self.save()

    def forget(self, words: str) -> int:
        """Удаляет факты, в которых встречается текст. Возвращает, сколько удалено."""
        needle = words.lower().strip()
        before = len(self.facts)
        self.facts = [f for f in self.facts if needle not in f.lower()] if needle else self.facts
        self.save()
        return before - len(self.facts)
