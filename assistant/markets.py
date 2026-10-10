"""Цены для панели «Биржа»: криптовалюты, индекс, золото и курсы валют.

Бесплатные источники без ключа: CoinGecko (крипта), Yahoo Finance (S&P 500,
золото) и ЦБ Узбекистана (курсы валют). Если источник недоступен, строка
показывает прочерк, а остальное продолжает работать.
"""

from __future__ import annotations

import json
import time
import re
import datetime
import urllib.request
from dataclasses import dataclass

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Kartal/2.0"
TIMEOUT = 8


@dataclass
class Quote:
    label: str
    value: float | None = None
    change: float | None = None  # крипта: 24 ч; валюты: к прошлой публикации
    prefix: str = ""
    decimals: int = 2
    date: str = ""
    source: str = ""

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


def currency_quotes(data):
    rates = {}
    for item in data:
        if item.get("Ccy") in ("USD", "RUB"):
            nominal = float(item["Nominal"])
            current = float(item["Rate"]) / nominal
            previous = (float(item["Rate"]) - float(item["Diff"])) / nominal
            if current > 0 and previous > 0:
                rates[item["Ccy"]] = (current, previous, item["Date"])
    result = []
    for code in ("USD", "RUB"):
        if code in rates:
            current, previous, date = rates[code]
            result.append(Quote(f"{code} → UZS", current, (current / previous - 1) * 100,
                                date=date, source="ЦБ Узбекистана"))
    if "USD" in rates and "RUB" in rates and rates["USD"][2] == rates["RUB"][2]:
        current = rates["USD"][0] / rates["RUB"][0]
        previous = rates["USD"][1] / rates["RUB"][1]
        result.append(Quote("USD → RUB", current, (current / previous - 1) * 100,
                            date=rates["USD"][2], source="Кросс-курс ЦБ Узбекистана"))
    return result


def market_answer(text):
    phrase = text.lower()
    if not re.search(r"курс|стоит|цен[аыу]|почем|почём", phrase):
        return None
    if re.search(r"анализ|прогноз|почему|купить|прода|завтра", phrase):
        return None
    crypto = re.search(r"\b(btc|бтс|битко\w*|bitcoin|eth|эфир\w*|ethereum)\b", phrase)
    currency = re.search(r"доллар|рубл|сум|валют|usd|rub|uzs", phrase)
    if not crypto and not currency:
        return None
    try:
        if crypto:
            ethereum = crypto.group().startswith(("eth", "эфир"))
            coin, label = ("ethereum", "ETH") if ethereum else ("bitcoin", "BTC")
            data = _get_json("https://api.coingecko.com/api/v3/simple/price"
                             f"?ids={coin}&vs_currencies=usd&include_24hr_change=true&include_last_updated_at=true")
            value, change = parse_coingecko(data, coin)
            stamp = data.get(coin, {}).get("last_updated_at")
            if value is None or not stamp:
                raise ValueError()
            date = datetime.datetime.fromtimestamp(stamp, datetime.timezone.utc).strftime("%d.%m.%Y %H:%M UTC")
            delta = f" Изменение за 24 часа: {change:+.2f}%." if change is not None else ""
            return f"{label}: {Quote(label, value, prefix='$').text}.{delta}\nДанные: {date}. Источник: CoinGecko."
        quotes = currency_quotes(_get_json("https://cbu.uz/ru/arkhiv-kursov-valyut/json/"))
        if not quotes:
            raise ValueError()
        lines = []
        for q in quotes:
            base, target = q.label.split(" → ")
            lines.append(f"1 {base} = {q.text} {target} ({q.change:+.2f}%). Дата: {q.date}.")
        return "\n".join(lines) + "\nИсточник: ЦБ Узбекистана. USD/RUB — расчётный кросс-курс. Процент к предыдущему опубликованному курсу."
    except Exception:
        return "Источник курсов сейчас недоступен. Не удалось получить свежие значения. Попробуйте позже."


class Markets:
    """Хранит последние цены; refresh() вызывается из фонового потока."""

    def __init__(self):
        self.quotes = [
            Quote("USD → UZS"),
            Quote("RUB → UZS"),
            Quote("USD → RUB"),
            Quote("BTC", prefix="$", decimals=0),
            Quote("ETH", prefix="$", decimals=0),
            Quote("S&P 500", decimals=0),
            Quote("Золото", prefix="$", decimals=0),
        ]
        self.updated: float | None = None
        self.online: bool | None = None

    def refresh(self) -> None:
        ok = False
        by_label = {q.label: q for q in self.quotes}
        # Clear old values so a failed refresh cannot look like fresh data.
        for quote in self.quotes:
            quote.value = quote.change = None
        try:
            for fresh in currency_quotes(_get_json("https://cbu.uz/ru/arkhiv-kursov-valyut/json/")):
                by_label[fresh.label].__dict__.update(fresh.__dict__)
                ok = True
        except Exception:
            pass
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
        self.online = ok
        if ok:
            self.updated = time.time()
