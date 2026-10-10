import pytest
from assistant.markets import currency_quotes, market_answer
from assistant.providers import ProviderBrain, RateLimitError


DATA = [
    {"Ccy": "USD", "Rate": "12000", "Diff": "-100", "Nominal": "1", "Date": "10.10.2026"},
    {"Ccy": "RUB", "Rate": "1500", "Diff": "100", "Nominal": "10", "Date": "10.10.2026"},
]


def test_currency_direction_nominal_and_cross_rate():
    quotes = {q.label: q for q in currency_quotes(DATA)}
    assert quotes['USD → UZS'].change < 0
    assert quotes['RUB → UZS'].value == 150
    assert quotes['RUB → UZS'].change > 0
    assert quotes['USD → RUB'].value == 80
    assert quotes['USD → RUB'].change == pytest.approx((80 / (12100 / 140) - 1) * 100)


def test_currency_answer_without_ai(monkeypatch):
    monkeypatch.setattr('assistant.markets._get_json', lambda url: DATA)
    answer = market_answer('какой сейчас курс доллара к суму')
    assert '12 000.00 UZS' in answer
    assert '10.10.2026' in answer
    assert market_answer('анализ курса доллара') is None


def test_source_failure_does_not_invent_prices(monkeypatch):
    def fail(url):
        raise OSError()
    monkeypatch.setattr('assistant.markets._get_json', fail)
    assert 'недоступен' in market_answer('курс бтс')


def test_search_quota_retries_once_without_search(monkeypatch):
    requests = []
    def events(url, key, provider, payload):
        requests.append(payload)
        if 'tools' in payload:
            raise RateLimitError('429')
        yield {'candidates': [{'content': {'parts': [{'text': 'Привет!'}]}, 'finishReason': 'STOP'}]}
    monkeypatch.setattr('assistant.providers.request_events', events)
    brain = ProviderBrain('Kartal', {'provider': 'gemini', 'gemini_model': 'test', 'web_search': True}, key_reader=lambda p: 'test')
    answer = brain.ask('Привет')
    assert 'Привет!' in answer and 'без поиска' in answer
    assert len(requests) == 2 and 'tools' not in requests[1]


def test_percentage_colors():
    from assistant import dashboard as d
    from types import SimpleNamespace
    from assistant.markets import Quote
    calls = []
    hud = SimpleNamespace(markets=SimpleNamespace(updated=None, online=True,
        quotes=[Quote('down', 1, -2), Quote('up', 1, 2), Quote('flat', 1, 0)]),
        text=lambda *a, **k: calls.append(k), row_frame=lambda *a: None,
        font=lambda *a: None, num_font=lambda *a: None, t=lambda s: s)
    d.Dashboard._draw_markets(hud, {'loading': 'loading'})
    percentages = [v for v in calls if '%' in v.get('text', '')]
    assert [v['fill'] for v in percentages] == [d.RED, d.GREEN, d.DIM]
