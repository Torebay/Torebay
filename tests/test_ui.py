from assistant.ui import HudIO, _mix


class FakeHud:
    def set_state(self, state):
        self.events.append(("state", state))

    def set_caption(self, text):
        self.events.append(("caption", text))

    def set_language(self, lang):
        self.events.append(("lang", lang))

    def log(self, who, text, append=False):
        self.events.append(("log", who, text) if not append else ("log+", who, text))

    def __init__(self, typed=None, armed=False):
        self.events = []
        self.typed = list(typed or [])
        self.armed = armed

    def take_command(self):
        return self.typed.pop(0) if self.typed else None

    def take_armed(self):
        armed, self.armed = self.armed, False
        return armed


class FakeIO:
    name = "Jarvis"
    lang = "ru"

    def __init__(self, heard):
        self.heard = heard
        self.said = []

    def listen(self):
        return self.heard

    def say(self, text, append=False):
        self.said.append(text)

    def set_language(self, lang):
        self.lang = lang


def test_hud_shows_listening_then_thinking():
    hud = FakeHud()
    io = HudIO(FakeIO("открой ютуб"), hud)
    assert io.listen() == "открой ютуб"
    assert ("state", "listening") in hud.events
    assert hud.events[-3:] == [("state", "thinking"), ("caption", "открой ютуб"), ("log", "user", "открой ютуб")]


def test_hud_returns_to_idle_after_speaking():
    hud = FakeHud()
    inner = FakeIO(None)
    io = HudIO(inner, hud)
    io.say("Привет")
    assert inner.said == ["Привет"]
    assert hud.events[-4:] == [("state", "speaking"), ("caption", "Привет"), ("log", "bot", "Привет"),
                               ("state", "idle")]


def test_language_switch_reaches_window():
    hud = FakeHud()
    io = HudIO(FakeIO(None), hud)
    io.set_language("tr")
    assert io.lang == "tr" and hud.events[-1] == ("lang", "tr")


def test_mix_darkens_and_lightens():
    assert _mix("#808080", 0.5) == "#404040"
    assert _mix("#000000", 2) == "#ffffff"


def test_typed_command_is_addressed_to_assistant():
    hud = FakeHud(typed=["который час"])
    inner = FakeIO("это не должно прозвучать")
    io = HudIO(inner, hud)
    assert io.listen() == "Jarvis который час"
    assert ("log", "user", "который час") in hud.events


def test_mic_button_skips_wake_word():
    hud = FakeHud(armed=True)
    io = HudIO(FakeIO("открой ютуб"), hud)
    assert io.listen() == "Jarvis открой ютуб"
    assert ("log", "user", "открой ютуб") in hud.events


def test_markets_parsers():
    from assistant.markets import Quote, parse_coingecko, parse_rates, parse_yahoo

    assert parse_coingecko({"bitcoin": {"usd": 100.0, "usd_24h_change": -2.5}}, "bitcoin") == (100.0, -2.5)
    assert parse_coingecko({}, "bitcoin") == (None, None)
    price, change = parse_yahoo({"chart": {"result": [{"meta": {"regularMarketPrice": 110.0,
                                                                "chartPreviousClose": 100.0}}]}})
    assert price == 110.0 and round(change, 6) == 10.0
    assert parse_yahoo({"chart": {"result": None}}) == (None, None)
    assert parse_rates({"rates": {"UZS": 12850.5}}, "UZS") == 12850.5
    assert Quote("BTC", 118250.4, prefix="$", decimals=0).text == "$118 250"
    assert Quote("X").text == "—"


def test_sysinfo_numbers_are_sane():
    from assistant import sysinfo

    meter = sysinfo.CpuMeter()
    sum(range(200000))
    cpu = meter.percent()
    assert cpu is None or 0 <= cpu <= 100
    for value in (sysinfo.memory_percent(), sysinfo.disk_percent()):
        assert value is None or 0 <= value <= 100
    assert sysinfo.format_uptime(3725) == "1:02"
