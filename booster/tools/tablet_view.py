#!/usr/bin/env python3
"""«Планшетный вид» для PUBG Mobile на телефоне.

На планшете и iPad экран почти квадратный (4:3), поэтому по вертикали видно больше, а интерфейс
крупнее. Телефон вытянут (20:9), и сверху/снизу видно меньше. Скрипт через adb (по USB) просит
Android показывать экран телефона в формате 4:3. Игру он не трогает, это обычная настройка экрана,
и выключается одной командой.

Запуск (из папки со скриптом):
    python tablet_view.py on          включить 4:3
    python tablet_view.py off         вернуть как было
    python tablet_view.py status      что сейчас
    python tablet_view.py on --ratio 3:2     другой формат (3:2, 16:10, 4:3)
Без параметров покажет меню.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from typing import Callable, Optional

Size = tuple[int, int]
# runner(adb_path, args) -> (код возврата, вывод)
Runner = Callable[[str, list[str]], tuple[int, str]]

RATIOS = {"4:3": (4, 3), "3:2": (3, 2), "16:10": (16, 10)}

SETUP_HELP = """\
Как подготовить телефон (один раз):
  1. Настройки → О телефоне → «Номер сборки» (на Vivo: «Версия ПО» → «Номер версии»):
     нажми 7 раз подряд, появится «Режим разработчика».
  2. Настройки → Для разработчиков → включи «Отладка по USB».
  3. Подключи телефон кабелем к ноутбуку, на телефоне нажми «Разрешить отладку».
  4. Нужна программа adb: скачай «SDK Platform-Tools for Windows» (developer.android.com/tools/releases/platform-tools),
     распакуй и положи папку platform-tools рядом с этим скриптом."""


def run_adb(adb: str, args: list[str]) -> tuple[int, str]:
    try:
        p = subprocess.run([adb, *args], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, str(e)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def find_adb(explicit: Optional[str] = None) -> Optional[str]:
    if explicit:
        return explicit if os.path.isfile(explicit) else shutil.which(explicit)
    here = os.path.dirname(os.path.abspath(__file__))
    for rel in (("platform-tools", "adb.exe"), ("platform-tools", "adb"), ("adb.exe",), ("adb",)):
        path = os.path.join(here, *rel)
        if os.path.isfile(path):
            return path
    return shutil.which("adb")


def parse_devices(text: str) -> list[tuple[str, str]]:
    """Строки вида «SERIAL<TAB>device». Служебные строки adb («* daemon…», заголовок) пропускаем."""
    found = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("*") or line.startswith("List of"):
            continue
        parts = line.split(None, 1)
        if len(parts) == 2:
            found.append((parts[0], parts[1]))
    return found


def parse_wm_size(text: str) -> tuple[Optional[Size], Optional[Size]]:
    """Из вывода `wm size` достаёт (физический размер, текущий подменённый размер)."""
    def grab(label: str) -> Optional[Size]:
        m = re.search(rf"{label} size:\s*(\d+)x(\d+)", text)
        return (int(m.group(1)), int(m.group(2))) if m else None

    return grab("Physical"), grab("Override")


def parse_ratio(text: str) -> tuple[int, int]:
    if text in RATIOS:
        return RATIOS[text]
    m = re.fullmatch(r"(\d{1,2}):(\d{1,2})", text)
    if not m or int(m.group(2)) == 0:
        raise ValueError(f"не понимаю формат «{text}», пример: 4:3")
    return int(m.group(1)), int(m.group(2))


def target_size(physical: Size, ratio: tuple[int, int]) -> Optional[Size]:
    """Размер экрана в нужном формате или None, если экран уже не вытянутее.

    Короткая сторона остаётся родной (картинка не теряет чёткость по ширине), длинная
    укорачивается до нужного формата. Порядок (ширина, высота) сохраняется как у экрана.
    """
    w, h = physical
    short, long_ = min(w, h), max(w, h)
    rw, rh = ratio
    want_long = round(short * max(rw, rh) / min(rw, rh))
    want_long -= want_long % 2  # чётное число пикселей
    if want_long >= long_:
        return None
    return (short, want_long) if w <= h else (want_long, short)


def _adb_shell(runner: Runner, adb: str, serial: str, *args: str) -> tuple[int, str]:
    return runner(adb, ["-s", serial, "shell", *args])


def pick_device(runner: Runner, adb: str, serial: Optional[str], out: Callable[[str], None]) -> Optional[str]:
    code, text = runner(adb, ["devices"])
    if code != 0:
        out(f"adb не запустился: {text.strip()}")
        return None
    devices = parse_devices(text)
    ready = [s for s, state in devices if state == "device"]
    if serial:
        if serial in ready:
            return serial
        out(f"Устройство {serial} не найдено среди готовых: {ready or 'нет'}")
        return None
    if not devices:
        out("Телефон не виден. Подключи его по USB и включи «Отладку по USB».\n" + SETUP_HELP)
        return None
    if not ready:
        states = ", ".join(f"{s}: {st}" for s, st in devices)
        out(f"Телефон найден, но не готов ({states}). Разреши отладку в окне на экране телефона и повтори.")
        return None
    if len(ready) > 1:
        out(f"Подключено несколько устройств: {', '.join(ready)}. Укажи нужное: --serial НОМЕР")
        return None
    return ready[0]


def read_size(runner: Runner, adb: str, serial: str) -> tuple[Optional[Size], Optional[Size]]:
    code, text = _adb_shell(runner, adb, serial, "wm", "size")
    return parse_wm_size(text) if code == 0 else (None, None)


def cmd_status(runner: Runner, adb: str, serial: str, out: Callable[[str], None]) -> int:
    physical, override = read_size(runner, adb, serial)
    if physical is None:
        out("Не удалось прочитать размер экрана.")
        return 1
    out(f"Экран телефона: {physical[0]}x{physical[1]}")
    out(f"Сейчас включён размер {override[0]}x{override[1]}" if override else "Сейчас всё как по умолчанию.")
    return 0


def cmd_on(runner: Runner, adb: str, serial: str, ratio_text: str, out: Callable[[str], None]) -> int:
    try:
        ratio = parse_ratio(ratio_text)
    except ValueError as e:
        out(str(e))
        return 1
    physical, _ = read_size(runner, adb, serial)
    if physical is None:
        out("Не удалось прочитать размер экрана, ничего не менял.")
        return 1
    size = target_size(physical, ratio)
    if size is None:
        out(f"Экран {physical[0]}x{physical[1]} уже не вытянутее формата {ratio_text}, менять нечего.")
        return 0
    code, text = _adb_shell(runner, adb, serial, "wm", "size", f"{size[0]}x{size[1]}")
    if code != 0:
        out(f"Телефон отказал: {text.strip()}")
        return 1
    out(f"Готово: экран теперь {size[0]}x{size[1]} (формат {ratio_text}), было {physical[0]}x{physical[1]}.")
    out("Дальше: полностью закрой PUBG и запусти заново. По бокам или снизу появятся чёрные полосы, это нормально.")
    out("Если картинка растянута или тач ведёт себя странно, верни как было: python tablet_view.py off")
    return 0


def cmd_off(runner: Runner, adb: str, serial: str, out: Callable[[str], None]) -> int:
    code, text = _adb_shell(runner, adb, serial, "wm", "size", "reset")
    if code != 0:
        out(f"Не удалось вернуть размер: {text.strip()}")
        return 1
    out("Готово: размер экрана вернулся как был.")
    return 0


def menu(out: Callable[[str], None], read: Callable[[str], str]) -> Optional[str]:
    out("Планшетный вид для PUBG Mobile")
    out("  1 — включить (4:3)")
    out("  2 — вернуть как было")
    out("  3 — показать, что сейчас")
    choice = read("Выбери 1, 2 или 3: ").strip()
    return {"1": "on", "2": "off", "3": "status"}.get(choice)


def main(
    argv: Optional[list[str]] = None,
    runner: Runner = run_adb,
    out: Callable[[str], None] = print,
    read: Callable[[str], str] = input,
) -> int:
    parser = argparse.ArgumentParser(description="Планшетный вид (формат экрана 4:3) для PUBG Mobile через adb.")
    parser.add_argument("action", nargs="?", choices=["on", "off", "status"])
    parser.add_argument("--ratio", default="4:3", help="формат: 4:3 (по умолчанию), 3:2, 16:10")
    parser.add_argument("--serial", help="номер устройства, если подключено несколько")
    parser.add_argument("--adb", help="путь к adb, если он не лежит рядом")
    args = parser.parse_args(argv)

    action = args.action or menu(out, read)
    if action is None:
        out("Ничего не выбрано.")
        return 1

    adb = find_adb(args.adb)
    if adb is None:
        out("Не нашёл программу adb.\n" + SETUP_HELP)
        return 1

    serial = pick_device(runner, adb, args.serial, out)
    if serial is None:
        return 1

    if action == "status":
        return cmd_status(runner, adb, serial, out)
    if action == "on":
        return cmd_on(runner, adb, serial, args.ratio, out)
    return cmd_off(runner, adb, serial, out)


if __name__ == "__main__":
    sys.exit(main())
