"""Открытие программ, сайтов и поиска.

Сначала ищем программу в config.json (по имени и синонимам), затем среди ярлыков
меню «Пуск» Windows — так открывается почти любая установленная программа.
"""

from __future__ import annotations

import difflib
import os
import subprocess
import sys
import urllib.parse
import webbrowser
from pathlib import Path


def start_menu_dirs() -> list[Path]:
    dirs = []
    for env in ("APPDATA", "PROGRAMDATA"):
        base = os.environ.get(env)
        if base:
            dirs.append(Path(base) / "Microsoft" / "Windows" / "Start Menu" / "Programs")
    desktop = Path.home() / "Desktop"
    dirs.append(desktop)
    return [d for d in dirs if d.is_dir()]


def find_shortcut(name: str, dirs: list[Path] | None = None) -> Path | None:
    """Ищет ярлык (.lnk/.url/.exe), чьё имя похоже на запрошенное."""
    dirs = start_menu_dirs() if dirs is None else dirs
    candidates: dict[str, Path] = {}
    for d in dirs:
        for path in d.rglob("*"):
            if path.suffix.lower() in (".lnk", ".url", ".exe", ".appref-ms"):
                candidates.setdefault(path.stem.lower(), path)
    if not candidates:
        return None

    name = name.lower().strip()
    for stem, path in candidates.items():
        if stem == name:
            return path
    for stem, path in sorted(candidates.items(), key=lambda kv: len(kv[0])):
        if name in stem:
            return path
    close = difflib.get_close_matches(name, list(candidates), n=1, cutoff=0.6)
    return candidates[close[0]] if close else None


def resolve_app(name: str, apps: dict) -> tuple[str, dict] | None:
    """Находит программу из config.json по имени или синониму."""
    name = name.lower().strip()
    for key, entry in apps.items():
        names = [key.lower(), *(a.lower() for a in entry.get("aliases", []))]
        if name in names:
            return key, entry
    for key, entry in apps.items():
        names = [key.lower(), *(a.lower() for a in entry.get("aliases", []))]
        if any(n in name or name in n for n in names if len(n) >= 4):
            return key, entry
    return None


def _start(target: str) -> None:
    if sys.platform == "win32":
        os.startfile(target)  # noqa: S606 — открываем то, что пользователь назвал сам
    elif sys.platform == "darwin":
        subprocess.Popen(["open", target])
    else:
        subprocess.Popen(["xdg-open", target])


def open_entry(entry: dict) -> None:
    if "uri" in entry:
        try:
            _start(entry["uri"])
            return
        except OSError:
            if "fallback_url" not in entry:
                raise
            webbrowser.open(entry["fallback_url"])
            return
    if "url" in entry:
        webbrowser.open(entry["url"])
        return
    if "path" in entry:
        _start(os.path.expandvars(entry["path"]))
        return
    if "command" in entry:
        subprocess.Popen(entry["command"], shell=True)
        return
    raise ValueError("В записи программы нет uri, url, path или command")


def open_app(name: str, apps: dict) -> str:
    """Открывает программу и возвращает фразу для ответа голосом."""
    found = resolve_app(name, apps)
    if found:
        key, entry = found
        open_entry(entry)
        return f"Открываю {name}"

    shortcut = find_shortcut(name)
    if shortcut:
        _start(str(shortcut))
        return f"Открываю {shortcut.stem}"

    if "." in name and " " not in name:  # похоже на сайт: «открой vk.com»
        webbrowser.open(name if "://" in name else f"https://{name}")
        return f"Открываю сайт {name}"

    return f"Не нашёл программу {name}. Добавьте её в config.json."


def web_search(query: str) -> str:
    webbrowser.open("https://www.google.com/search?q=" + urllib.parse.quote(query))
    return f"Ищу {query}"


def youtube_search(query: str) -> str:
    webbrowser.open("https://www.youtube.com/results?search_query=" + urllib.parse.quote(query))
    return f"Ищу на ютубе {query}"
