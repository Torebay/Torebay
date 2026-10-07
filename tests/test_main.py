from assistant.brain import Brain
from assistant.config import load_config
from assistant.main import run


class FakeIO:
    def __init__(self, phrases, lang="ru"):
        self.phrases = list(phrases)
        self.said = []
        self.lang = lang

    def set_language(self, lang):
        self.lang = lang

    def listen(self):
        return self.phrases.pop(0)

    def say(self, text):
        self.said.append(text)


def test_ignores_phrases_without_name_and_answers_with_name(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    config = load_config()
    io = FakeIO(["просто разговор", "Джарвис", "который час", "джарвис стоп"])
    run(io, config, Brain(config.name, config.claude))
    assert io.said[0] == "Jarvis на связи."
    assert io.said[1] == "Слушаю."
    assert io.said[2].startswith("Сейчас ")
    assert io.said[-1] == "До встречи!"
    assert len(io.said) == 4  # «просто разговор» проигнорирован


def test_question_without_key_explains_setup(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    config = load_config()
    io = FakeIO(["джарвис почему небо голубое", "джарвис выход"])
    run(io, config, Brain(config.name, config.claude))
    assert "ANTHROPIC_API_KEY" in io.said[1]


def test_switch_to_uzbek_and_turkish(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    config = load_config()
    io = FakeIO([
        "джарвис говори по-узбекски", "jarvis soat necha",
        "jarvis türkçe konuş", "carvis saat kaç", "jarvis dur",
    ])
    run(io, config, Brain(config.name, config.claude))
    assert io.said[1] == "Mayli, endi o'zbekcha gaplashaman."
    assert io.said[2].startswith("Hozir soat ")
    assert io.said[3] == "Tamam, artık Türkçe konuşuyorum."
    assert io.said[4].startswith("Saat ")
    assert io.said[5] == "Görüşürüz!"
