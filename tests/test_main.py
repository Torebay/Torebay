from assistant.brain import Brain
from assistant.config import load_config
from assistant.main import run


class FakeIO:
    def __init__(self, phrases):
        self.phrases = list(phrases)
        self.said = []

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
