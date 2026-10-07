"""Главный цикл ассистента: слушать → понять → выполнить → ответить."""

from __future__ import annotations

import argparse
import datetime

from . import apps
from .brain import Brain
from .commands import parse, strip_wake_word
from .config import load_config, load_env

MONTHS = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля",
          "августа", "сентября", "октября", "ноября", "декабря"]


def handle(text: str, config, brain: Brain) -> tuple[str, bool]:
    """Выполняет одну фразу. Возвращает (ответ, продолжать ли работу)."""
    command = parse(text)
    now = datetime.datetime.now()

    if command.action == "exit":
        return "До встречи!", False
    if command.action == "empty":
        return "Слушаю.", True
    if command.action == "reset":
        brain.reset()
        return "Хорошо, начнём разговор заново.", True
    if command.action == "time":
        return f"Сейчас {now:%H:%M}.", True
    if command.action == "date":
        return f"Сегодня {now.day} {MONTHS[now.month - 1]}.", True
    try:
        if command.action == "open":
            return apps.open_app(command.arg, config.apps), True
        if command.action == "search":
            return apps.web_search(command.arg), True
        if command.action == "youtube":
            return apps.youtube_search(command.arg), True
    except Exception as exc:  # программа не открылась — не роняем ассистента
        return f"Не получилось: {exc}", True
    return brain.ask(command.arg), True


def run(io, config, brain: Brain) -> None:
    io.say(f"{config.name} на связи.")
    waiting_for_command = False
    while True:
        heard = io.listen()
        if not heard:
            continue

        called, rest = strip_wake_word(heard, config.all_wake_words)
        if config.require_wake_word and not called and not waiting_for_command:
            continue  # фраза была не для ассистента
        if called and not rest:
            io.say("Слушаю.")
            waiting_for_command = True
            continue

        waiting_for_command = False
        answer, keep_going = handle(rest, config, brain)
        io.say(answer)
        if not keep_going:
            break


def main() -> None:
    parser = argparse.ArgumentParser(description="Голосовой ассистент")
    parser.add_argument("--text", action="store_true", help="печатать вместо голоса (без микрофона)")
    parser.add_argument("--config", default=None, help="путь к config.json")
    args = parser.parse_args()

    load_env()
    config = load_config(args.config) if args.config else load_config()
    brain = Brain(config.name, config.claude)

    if args.text:
        from .speech import TextIO

        config.require_wake_word = False  # в текстовом режиме обращаться по имени не нужно
        io = TextIO(config.name)
    else:
        from .speech import VoiceIO

        io = VoiceIO(config.name, config.language, config.voice)

    try:
        run(io, config, brain)
    except KeyboardInterrupt:
        print()


if __name__ == "__main__":
    main()
