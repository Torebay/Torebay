from assistant.ui import HudIO, _mix


class FakeHud:
    def __init__(self):
        self.events = []

    def set_state(self, state):
        self.events.append(("state", state))

    def set_caption(self, text):
        self.events.append(("caption", text))

    def set_language(self, lang):
        self.events.append(("lang", lang))


class FakeIO:
    name = "Jarvis"
    lang = "ru"

    def __init__(self, heard):
        self.heard = heard
        self.said = []

    def listen(self):
        return self.heard

    def say(self, text):
        self.said.append(text)

    def set_language(self, lang):
        self.lang = lang


def test_hud_shows_listening_then_thinking():
    hud = FakeHud()
    io = HudIO(FakeIO("открой ютуб"), hud)
    assert io.listen() == "открой ютуб"
    assert ("state", "listening") in hud.events
    assert hud.events[-2:] == [("state", "thinking"), ("caption", "открой ютуб")]


def test_hud_returns_to_idle_after_speaking():
    hud = FakeHud()
    inner = FakeIO(None)
    io = HudIO(inner, hud)
    io.say("Привет")
    assert inner.said == ["Привет"]
    assert hud.events[-3:] == [("state", "speaking"), ("caption", "Привет"), ("state", "idle")]


def test_language_switch_reaches_window():
    hud = FakeHud()
    io = HudIO(FakeIO(None), hud)
    io.set_language("tr")
    assert io.lang == "tr" and hud.events[-1] == ("lang", "tr")


def test_mix_darkens_and_lightens():
    assert _mix("#808080", 0.5) == "#404040"
    assert _mix("#000000", 2) == "#ffffff"
