"""Загрузка настроек из config.json и ключей из .env."""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = Path(os.environ.get("LOCALAPPDATA", str(ROOT))) / "Kartal" if getattr(sys, "frozen", False) else ROOT
DEFAULT_CONFIG_PATH = DATA_ROOT / "config.json"


@dataclass
class Config:
    name: str = "Jarvis"
    wake_words: list[str] = field(default_factory=lambda: ["джарвис", "jarvis"])
    require_wake_word: bool = True
    language: str = "ru-RU"
    voice: dict = field(default_factory=dict)
    claude: dict = field(default_factory=dict)
    ai: dict = field(default_factory=dict)
    apps: dict = field(default_factory=dict)
    ui: dict = field(default_factory=dict)

    @property
    def all_wake_words(self) -> list[str]:
        """Слова-обращения: имя ассистента плюс дополнительные варианты."""
        words = [self.name.lower(), *(w.lower() for w in self.wake_words)]
        # Длинные сначала, чтобы «джарвис» отрезался целиком, а не как «джарви».
        return sorted(set(words), key=len, reverse=True)


def load_env(path: Path = ROOT / ".env") -> None:
    """Подхватывает строки KEY=VALUE из .env, не перезаписывая уже заданные переменные."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def load_config(path: Path | str = DEFAULT_CONFIG_PATH) -> Config:
    if Path(path) == DEFAULT_CONFIG_PATH and not Path(path).exists() and DATA_ROOT != ROOT:
        DATA_ROOT.mkdir(parents=True, exist_ok=True)
        import shutil
        shutil.copyfile(ROOT / "config.json", path)
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    known = {k: v for k, v in data.items() if k in Config.__dataclass_fields__}
    return Config(**known)
