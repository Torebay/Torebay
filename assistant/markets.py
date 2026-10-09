"""Цены для панели «Биржа»: криптовалюты, индекс, золото и курсы валют.

Бесплатные источники без ключа: CoinGecko (крипта), Yahoo Finance (S&P 500,
золото) и open.er-api.com (курсы валют). Если источник недоступен, строка
показывает прочерк, а остальное продолжает работать.
"""

from __future__ import annotations

import json
import time
import urllib.request
from dataclasses import dataclass

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Kartal/2.0"
TIMEOUT = 8


@dataclass
class Quote:
    label: str
    value: float | None = None
    change: float | None = None  # изменение за сутки в процентах
    prefix: str = ""
    decimals: int = 2

    @property
    def text(self) -> str:
        if self.value is None:
            return "—"
        return f"{self.prefix}{self.value:,.{self.decimals}f}".replace(",", " ")


def _get_json(url: str) -> dict:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        return json.loads(response.read().decode("utf-8"))


def parse_coingecko(data: dict, coin: str) -> tuple[float | None, float | None]:
    entry = data.get(coin) or {}
    return entry.get("usd"), entry.get("usd_24h_change")


def parse_yahoo(data: dict) -> tuple[float | None, float | None]:
    meta = ((data.get("chart") or {}).get("result") or [{}])[0].get("meta") or {}
    price = meta.get("regularMarketPrice")
    previous = meta.get("chartPreviousClose") or meta.get("previousClose")
    change = (price / previous - 1) * 100 if price and previous else None
    return price, change


def parse_rates(data: dict, currency: str) -> float | None:
    return (data.get("rates") or {}).get(currency)


class Markets:
    """Хранит последние цены; refresh() вызывается из фонового потока."""

    def __init__(self):
        self.quotes = [
            Quote("BTC", prefix="$", decimals=0),
            Quote("ETH", prefix="$", decimals=0),
            Quote("S&P 500", decimals=0),
            Quote("Золото", prefix="$", decimals=0),
            Quote("USD → UZS", decimals=0),
            Quote("USD → RUB", decimals=2),
            Quote("USD → TRY", decimals=2),
        ]
        self.updated: float | None = None
        self.online: bool | None = None

    def refresh(self) -> None:
        ok = False
        by_label = {q.label: q for q in self.quotes}
        try:
            data = _get_json("https://api.coingecko.com/api/v3/simple/price"
                             "?ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true")
            for label, coin in (("BTC", "bitcoin"), ("ETH", "ethereum")):
                value, change = parse_coingecko(data, coin)
                if value is not None:
                    by_label[label].value, by_label[label].change = value, change
                    ok = True
        except Exception:
            pass
        for label, symbol in (("S&P 500", "%5EGSPC"), ("Золото", "GC%3DF")):
            try:
                value, change = parse_yahoo(_get_json(
                    f"https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=1d&interval=1d"))
                if value is not None:
                    by_label[label].value, by_label[label].change = value, change
                    ok = True
            except Exception:
                pass
        try:
            data = _get_json("https://open.er-api.com/v6/latest/USD")
            for label, currency in (("USD → UZS", "UZS"), ("USD → RUB", "RUB"), ("USD → TRY", "TRY")):
                value = parse_rates(data, currency)
                if value is not None:
                    by_label[label].value = value
                    ok = True
        except Exception:
            pass
        self.online = ok
        if ok:
            self.updated = time.time()
