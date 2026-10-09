from assistant import apps
from assistant.config import load_config

APPS = load_config().apps


def test_default_name_is_kartal():
    config = load_config()
    assert config.name == "Kartal"
    assert "картал" in config.all_wake_words


def test_name_comes_from_config(tmp_path):
    path = tmp_path / "config.json"
    path.write_text('{"name": "Пятница", "wake_words": []}', encoding="utf-8")
    config = load_config(path)
    assert config.name == "Пятница"
    assert config.all_wake_words == ["пятница"]


def test_resolve_by_alias():
    assert apps.resolve_app("телеграм", APPS)[0] == "telegram"
    assert apps.resolve_app("ватсап", APPS)[0] == "whatsapp"
    assert apps.resolve_app("YouTube", APPS)[0] == "youtube"
    assert apps.resolve_app("непонятное", APPS) is None


def test_find_shortcut(tmp_path):
    (tmp_path / "Zoom").mkdir()
    (tmp_path / "Zoom" / "Zoom Workplace.lnk").write_text("")
    (tmp_path / "Spotify.lnk").write_text("")
    assert apps.find_shortcut("spotify", [tmp_path]).stem == "Spotify"
    assert apps.find_shortcut("zoom", [tmp_path]).stem == "Zoom Workplace"
    assert apps.find_shortcut("spotfy", [tmp_path]).stem == "Spotify"
    assert apps.find_shortcut("photoshop", [tmp_path]) is None


def test_open_app_uses_config_entry(monkeypatch):
    opened = []
    monkeypatch.setattr(apps.webbrowser, "open", opened.append)
    assert apps.open_app("ютуб", APPS) == "Открываю ютуб"
    assert opened == ["https://www.youtube.com"]


def test_youtube_search_url(monkeypatch):
    opened = []
    monkeypatch.setattr(apps.webbrowser, "open", opened.append)
    apps.youtube_search("рецепт плова")
    assert opened[0].startswith("https://www.youtube.com/results?search_query=")


def test_open_app_strips_turkish_and_uzbek_endings(monkeypatch):
    opened = []
    monkeypatch.setattr(apps.webbrowser, "open", opened.append)
    assert apps.open_app("youtubeu", APPS, "tr") == "youtube açılıyor"
    assert apps.open_app("yutubni", APPS, "uz") == "yutub ochilmoqda"
    assert opened == ["https://www.youtube.com"] * 2
