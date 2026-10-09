"""Главный цикл ассистента: слушать → понять → выполнить → ответить."""

from __future__ import annotations

import argparse
import datetime
import queue
import threading

from . import apps
from .brain import Brain
from .commands import parse, strip_wake_word
from .config import load_config, load_env
from .i18n import format_date, lang_code, t


def handle(text: str, config, brain: Brain, io) -> tuple[str | None, bool]:
    """Выполняет одну фразу. Возвращает (ответ, продолжать ли работу).
    Ответ None значит, что он уже сказан вслух."""
    command = parse(text)
    lang = io.lang
    now = datetime.datetime.now()

    if command.action == "exit":
        return t(lang, "bye"), False
    if command.action == "empty":
        return t(lang, "listening"), True
    if command.action == "reset":
        brain.reset()
        return t(lang, "reset"), True
    if command.action == "lang":
        io.set_language(command.arg)
        return t(command.arg, "switched"), True
    if command.action == "time":
        return t(lang, "time", x=f"{now:%H:%M}"), True
    if command.action == "date":
        return t(lang, "date", x=format_date(lang, now.day, now.month)), True
    try:
        if command.action == "open":
            return apps.open_app(command.arg, config.apps, lang), True
        if command.action == "search":
            return apps.web_search(command.arg, lang), True
        if command.action == "youtube":
            return apps.youtube_search(command.arg, lang), True
    except Exception as exc:  # программа не открылась — не роняем ассистента
        return t(lang, "failed", x=exc), True
    return ask_aloud(brain, command.arg, lang, io), True


def ask_aloud(brain: Brain, question: str, lang: str, io) -> str | None:
    """Говорит ответ Claude по предложениям, пока остальное ещё пишется.
    Возвращает ответ, если ничего не было сказано (ошибка, нет ключа), иначе None."""
    sentences: queue.Queue = queue.Queue()
    prefetch = getattr(io, "prefetch", None)
    result = {}

    def on_sentence(sentence: str) -> None:
        if prefetch is not None:
            prefetch(sentence)  # голос начинает готовиться сразу, ещё до своей очереди
        sentences.put(sentence)

    def work() -> None:
        try:
            result["answer"] = brain.ask(question, lang, on_sentence=on_sentence)
        except Exception as exc:
            result["answer"] = t(lang, "failed", x=exc)
        finally:
            sentences.put(None)

    threading.Thread(target=work, daemon=True).start()
    spoken = 0
    while (sentence := sentences.get()) is not None:
        io.say(sentence, append=spoken > 0)
        spoken += 1
    return None if spoken else result.get("answer")


def run(io, config, brain: Brain) -> None:
    io.say(t(io.lang, "hello", name=config.name))
    waiting_for_command = False
    while True:
        heard = io.listen()
        if not heard:
            continue

        called, rest = strip_wake_word(heard, config.all_wake_words)
        if config.require_wake_word and not called and not waiting_for_command:
            continue  # фраза была не для ассистента
        if called and not rest:
            io.say(t(io.lang, "listening"))
            waiting_for_command = True
            continue

        waiting_for_command = False
        answer, keep_going = handle(rest, config, brain, io)
        if answer:
            io.say(answer)
        if not keep_going:
            break


def main() -> None:
    parser = argparse.ArgumentParser(description="Голосовой ассистент")
    parser.add_argument("--text", action="store_true", help="печатать вместо голоса (без микрофона)")
    parser.add_argument("--lang", choices=["ru", "uz", "tr"], help="язык при запуске")
    parser.add_argument("--config", default=None, help="путь к config.json")
    parser.add_argument("--no-ui", action="store_true", help="без анимированного окна")
    args = parser.parse_args()

    load_env()
    config = load_config(args.config) if args.config else load_config()
    lang = lang_code(args.lang or config.language)
    from .memory import Memory
    from .pc_tools import PcTools

    memory = Memory()  # memory.json рядом с config.json: разговор и факты переживают перезапуск
    tools = PcTools(memory) if config.claude.get("pc_control", True) else None
    brain = Brain(config.name, config.claude, memory=memory, tools=tools)

    def make_io():
        if args.text:
            from .speech import TextIO

            config.require_wake_word = False  # в текстовом режиме обращаться по имени не нужно
            return TextIO(config.name, lang)
        from .speech import VoiceIO

        return VoiceIO(config.name, lang, config.voice)

    ui_settings = config.ui or {}
    if args.no_ui or not ui_settings.get("enabled", True):
        try:
            run(make_io(), config, brain)
        except KeyboardInterrupt:
            print()
        return

    run_with_window(make_io, config, brain, ui_settings)


def run_with_window(make_io, config, brain: Brain, ui_settings: dict) -> None:
    """Окно крутится в главном потоке, а ассистент слушает и отвечает в соседнем."""
    from .ui import HudIO, HudWindow

    lang = lang_code(config.language)
    if ui_settings.get("style", "dashboard") == "compact":
        hud = HudWindow(config.name, lang, ui_settings.get("always_on_top", True))
    else:
        from .dashboard import Dashboard

        hud = Dashboard(config.name, lang, model=brain.model, has_key=brain.available, web_search=brain.web_search,
                        memory=lambda: len(brain.history) // 2,
                        always_on_top=ui_settings.get("always_on_top", False),
                        theme=ui_settings.get("theme", "green"))

    def worker():
        try:
            # Микрофон и голос создаём в этом же потоке: так голос Windows работает надёжнее.
            run(HudIO(make_io(), hud), config, brain)
        except Exception as exc:  # покажем ошибку в окне, а не молча закроемся
            print(f"Ошибка: {exc}")
            hud.set_caption(f"Ошибка: {exc}")
            hud.log("bot", f"Ошибка: {exc}")
            return
        hud.close()

    threading.Thread(target=worker, daemon=True).start()
    try:
        hud.run()
    except KeyboardInterrupt:
        print()


if __name__ == "__main__":
    main()
