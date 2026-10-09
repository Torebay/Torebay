import sys
import types
from types import SimpleNamespace as NS

from assistant.brain import Brain
from assistant.main import ask_aloud
from assistant.speech_text import SentenceSplitter, clean


def test_clean_removes_markdown_links_and_emoji():
    assert clean("**Биткоин** стоит 118 тысяч 🚀 [1]. Источник: [CoinGecko](https://coingecko.com).") == \
        "Биткоин стоит 118 тысяч. Источник: CoinGecko."
    assert clean("## Погода: тепло https://example.com/x") == "Погода: тепло"
    assert clean("- один\n- два") == "один два"


def test_splitter_emits_whole_sentences():
    out = []
    splitter = SentenceSplitter(out.append)
    for piece in ["Сейчас 1", "4:05. Курс доллара", " 12 650 сумов! А", " евро дороже"]:
        splitter.add(piece)
    assert out == ["Сейчас 14:05.", "Курс доллара 12 650 сумов!"]
    splitter.flush()
    assert out[-1] == "А евро дороже"
    splitter.add("Рост 2.5 процента. ")
    assert out[-1] == "Рост 2.5 процента."


class FakeStream:
    def __init__(self, pieces, stop_reason="end_turn"):
        self.pieces = pieces
        self.stop_reason = stop_reason

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def __iter__(self):
        for piece in self.pieces:
            if piece is None:  # поиск в интернете
                yield NS(type="content_block_start", content_block=NS(type="server_tool_use"))
            else:
                yield NS(type="content_block_delta", delta=NS(type="text_delta", text=piece))

    def get_final_message(self):
        text = "".join(p for p in self.pieces if p)
        return NS(stop_reason=self.stop_reason, content=[NS(type="text", text=text)])


def make_brain(monkeypatch, *streams):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test")
    try:
        import anthropic  # noqa: F401
    except ImportError:  # в CI пакета нет: хватит пустых классов ошибок
        stub = types.ModuleType("anthropic")
        for name in ("AuthenticationError", "RateLimitError", "APIStatusError", "APIConnectionError"):
            setattr(stub, name, type(name, (Exception,), {}))
        monkeypatch.setitem(sys.modules, "anthropic", stub)
    brain = Brain("Kartal", {})
    calls = []
    queue = list(streams)

    def stream(**kwargs):
        calls.append(kwargs)
        return queue.pop(0)

    brain._client = NS(beta=NS(messages=NS(stream=stream)))
    return brain, calls


def test_brain_streams_sentences_and_continues_after_pause(monkeypatch):
    brain, calls = make_brain(monkeypatch,
                              FakeStream(["Ищу", None], stop_reason="pause_turn"),
                              FakeStream(["Биткоин стоит 118 тысяч. ", "Это на 2% больше."]))
    heard = []
    answer = brain.ask("сколько стоит биткоин", "ru", on_sentence=heard.append)
    assert heard == ["Ищу", "Биткоин стоит 118 тысяч.", "Это на 2% больше."]
    assert answer == "Ищу Биткоин стоит 118 тысяч. Это на 2% больше."
    assert len(calls) == 2 and calls[0]["model"] == "claude-opus-5-5"
    assert calls[1]["messages"][-1]["role"] == "assistant"


class FakeIO:
    lang = "ru"

    def __init__(self):
        self.said = []
        self.prefetched = []

    def prefetch(self, text):
        self.prefetched.append(text)

    def say(self, text, append=False):
        self.said.append((text, append))


def test_ask_aloud_speaks_each_sentence_once(monkeypatch):
    brain, _ = make_brain(monkeypatch, FakeStream(["Да. ", "Сейчас 14:05."]))
    io = FakeIO()
    assert ask_aloud(brain, "который час", "ru", io) is None
    assert io.said == [("Да.", False), ("Сейчас 14:05.", True)]
    assert io.prefetched == ["Да.", "Сейчас 14:05."]


def test_ask_aloud_returns_error_when_nothing_was_said(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    io = FakeIO()
    answer = ask_aloud(Brain("Kartal", {}), "вопрос", "ru", io)
    assert "ANTHROPIC_API_KEY" in answer and io.said == []
