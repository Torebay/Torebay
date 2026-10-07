import pytest

from assistant.commands import app_name_variants, parse, strip_wake_word

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
        # Узбекский
        ("telegramni och", "open", "telegramni"),
        ("iltimos, vatsapni ochib ber", "open", "vatsapni"),
        ("YouTube'da musiqa qidir", "youtube", "musiqa"),
        ("dollar kursini qidir", "search", "dollar kursini"),
        ("soat necha", "time", ""),
        ("o'zbekcha gapir", "lang", "uz"),
        ("tilni ozbekchaga otamiz", "lang", "uz"),
        ("xayr", "exit", ""),
        # Турецкий
        ("Telegram'ı aç", "open", "telegramı"),
        ("lütfen YouTube'u aç", "open", "youtubeu"),
        ("YouTube'da Tarkan şarkıları aç", "youtube", "tarkan şarkıları"),
        ("bitcoin fiyatını ara", "search", "bitcoin fiyatını"),
        ("saat kaç", "time", ""),
        ("Türkçe konuş", "lang", "tr"),
        ("говори по-русски", "lang", "ru"),
        ("dur", "exit", ""),
        # Слово языка внутри обычного вопроса — это не переключение.
        ("как будет привет по-турецки", "ask", "как будет привет по-турецки"),
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


def test_app_name_variants():
    assert "telegram" in app_name_variants("telegramni")
    assert "telegram" in app_name_variants("telegramı")
    assert "youtube" in app_name_variants("youtubeu")
    assert "spotify" in app_name_variants("spotifyı")
