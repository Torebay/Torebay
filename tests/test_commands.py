import pytest

from assistant.commands import parse, strip_wake_word

WAKE = ["джарвис", "jarvis", "джарви"]


@pytest.mark.parametrize(
    "text, action, arg",
    [
        ("Открой Telegram", "open", "telegram"),
        ("открой телеграм", "open", "телеграм"),
        ("запусти ватсап", "open", "ватсап"),
        ("пожалуйста, открой программу блокнот", "open", "блокнот"),
        ("открой ютуб", "open", "ютуб"),
        ("найди на ютубе рецепт плова", "youtube", "рецепт плова"),
        ("включи музыку на ютубе", "youtube", "музыку"),
        ("найди погоду в Алматы", "search", "погоду в алматы"),
        ("найди в интернете курс доллара", "search", "курс доллара"),
        ("Который час?", "time", ""),
        ("какое сегодня число", "date", ""),
        ("стоп", "exit", ""),
        ("забудь разговор", "reset", ""),
        ("", "empty", ""),
        ("почему небо голубое", "ask", "почему небо голубое"),
    ],
)
def test_parse(text, action, arg):
    command = parse(text)
    assert command.action == action
    assert command.arg == arg


def test_wake_word_at_start():
    assert strip_wake_word("Джарвис, открой YouTube", WAKE) == (True, "открой youtube")


def test_wake_word_with_case_ending():
    # «Джарвису» тоже считается обращением.
    called, rest = strip_wake_word("скажи джарвису привет", WAKE)
    assert called and rest == "скажи привет"


def test_wake_word_alone():
    assert strip_wake_word("Джарвис", WAKE) == (True, "")


def test_no_wake_word():
    assert strip_wake_word("открой телеграм", WAKE) == (False, "открой телеграм")


def test_renamed_assistant():
    assert strip_wake_word("Пятница, который час", ["пятница"]) == (True, "который час")
