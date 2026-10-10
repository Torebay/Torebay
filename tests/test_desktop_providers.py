import urllib.error
import pytest

from assistant import providers
from assistant.local_commands import calculate, handle_local
from assistant.main import run
from assistant.config import Config
from assistant.ui import WindowCommand, HudIO
from assistant.speech import WindowIO
from test_ui import FakeHud


@pytest.mark.parametrize("provider", ["gemini", "openai"])
def test_no_key_makes_no_network_request(monkeypatch, provider):
    monkeypatch.setattr(providers, "request_events", lambda *a: pytest.fail("Network must not be used"))
    brain = providers.ProviderBrain("Kartal", {"provider": provider}, key_reader=lambda p: "")
    assert "Настройки ИИ" in brain.ask("привет")


@pytest.mark.parametrize("provider", ["gemini", "openai"])
def test_provider_request_history_and_sources(monkeypatch, provider):
    calls = []
    def request(url, key, selected, payload):
        calls.append((url, key, selected, payload))
        if provider == "openai":
            return iter([{"type": "response.output_text.delta", "delta": "Ответ."},
                {"type": "response.completed", "response": {"output": [{"type": "message", "content": [{"type": "output_text", "text": "Ответ.", "annotations": [{"type": "url_citation", "url": "https://example.com"}]}]}]}}])
        return iter([{"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": "hidden", "thought": True}, {"text": "Ответ."}]}, "groundingMetadata": {"groundingChunks": [{"web": {"uri": "https://example.com"}}]}}]}])
    monkeypatch.setattr(providers, "request_events", request)
    brain = providers.ProviderBrain("Kartal", {"provider": provider, provider + "_model": "test-model"}, key_reader=lambda p: "test-secret")
    answer = brain.ask("A + B = 2?\nCase matters")
    assert "https://example.com" in answer and "hidden" not in answer
    assert calls[0][2] == provider and calls[0][3]["tools"]
    assert brain.history[0]["content"] == "A + B = 2?\nCase matters"
    brain.reset()
    assert not brain.history


def test_key_not_leaked_in_http_errors(monkeypatch):
    def fail(*a, **kw):
        raise urllib.error.HTTPError("https://example.com", 401, "secret-key", {}, None)
    monkeypatch.setattr(providers.urllib.request, "urlopen", fail)
    with pytest.raises(providers.ProviderError) as exc:
        providers.request_json("https://api.openai.com/v1/responses", "secret-key", "openai", {})
    assert "secret-key" not in str(exc.value)


def test_arithmetic_bounded_and_no_code_execution():
    assert calculate("(120 + 30) * 2") == 300
    assert calculate("2^3 + 1,5") == 9.5
    for formula in ("__import__('os')", "2**1000000", "[1]", "True"):
        with pytest.raises((ValueError, SyntaxError)):
            calculate(formula)
    assert "300" in handle_local("посчитай (120 + 30) * 2", None)


def test_window_input_preserves_formula_and_case():
    from test_main import FakeIO
    class Brain:
        tools = None
        def ask(self, question, lang, on_sentence=None):
            self.received = question
            return "OK"
    brain = Brain()
    io = FakeIO([WindowCommand("Explain A+B=2\nFile Text", "Kartal"), WindowCommand("стоп", "Kartal")])
    run(io, Config(name="Kartal"), brain)
    assert brain.received == "Explain A+B=2\nFile Text"


def test_window_only_does_not_read_console(monkeypatch):
    monkeypatch.setattr("builtins.input", lambda *a: pytest.fail("Console must not be read"))
    hud = FakeHud()
    io = HudIO(WindowIO("Kartal"), hud)
    assert io.listen() is None
    hud.typed.append("посчитай 1+2")
    assert io.listen().original_text == "посчитай 1+2"


@pytest.mark.parametrize("provider", ["openai", "gemini"])
def test_sentence_is_spoken_before_stream_finishes(monkeypatch, provider):
    heard = []
    def events(*args):
        if provider == "openai":
            yield {"type": "response.output_text.delta", "delta": "Первая фраза. "}
            assert heard == ["Первая фраза."]
            yield {"type": "response.output_text.delta", "delta": "Вторая."}
            yield {"type": "response.completed", "response": {"output": []}}
        else:
            yield {"candidates": [{"content": {"parts": [{"text": "Первая фраза. "}]}}]}
            assert heard == ["Первая фраза."]
            yield {"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": "Вторая."}]}}]}
    monkeypatch.setattr(providers, "request_events", events)
    brain = providers.ProviderBrain("Kartal", {"provider": provider, provider + "_model": "test-model"}, key_reader=lambda p: "test")
    assert brain.ask("Привет", on_sentence=heard.append) == "Первая фраза. Вторая."
    assert heard == ["Первая фраза.", "Вторая."]


def test_partial_stream_does_not_hide_failure(monkeypatch):
    heard = []
    monkeypatch.setattr(providers, "request_events", lambda *args: iter([
        {"type": "response.output_text.delta", "delta": "Начало. "},
        {"type": "response.failed"},
    ]))
    brain = providers.ProviderBrain("Kartal", {"provider": "openai", "openai_model": "test"}, key_reader=lambda p: "test")
    result = brain.ask("Привет", on_sentence=heard.append)
    assert "не завершён" in result and heard[-1] == result
    assert not brain.history


def test_sse_parser_reads_multiline_events_and_done(monkeypatch):
    import io
    response = io.BytesIO(b'data: {"type":\n' b'data: "response.completed"}\n\n' b'data: [DONE]\n\n')
    monkeypatch.setattr(providers.urllib.request, "urlopen", lambda *args, **kwargs: response)
    assert list(providers.request_events("https://api.openai.com/v1/responses", "test", "openai", {})) == [{"type": "response.completed"}]


def test_neural_voice_without_key_makes_no_request(monkeypatch):
    from assistant import cloud_voice
    monkeypatch.setattr(cloud_voice, "get_key", lambda p: "")
    monkeypatch.setattr(cloud_voice.urllib.request, "urlopen", lambda *a, **kw: pytest.fail("No key"))
    with pytest.raises(RuntimeError):
        cloud_voice.OpenAIVoice({}).say("Проверка", "ru")
