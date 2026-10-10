"""Weather forecasts directly from Open-Meteo, independent of AI quotas."""
import re
from urllib.parse import urlencode
from .markets import _get_json


def weather_answer(text):
    phrase = text.lower().strip()
    if not re.search(r"\bпогод\w*\b", phrase):
        return None
    if re.search(r"почему|климат|объясни|сочинение", phrase):
        return None
    if re.search(r"недел|вчера|месяц|\d", phrase):
        return "Пока могу показать погоду на сегодня, завтра и послезавтра. Например: завтра погода Москва."
    day = 2 if 'послезавтра' in phrase else 1 if 'завтра' in phrase else 0
    city = re.sub(r"\b(погод\w*|прогноз|сегодня|завтра|послезавтра|сейчас|какая|какой|будет|скажи|покажи|пожалуйста|на|в|во|городе|город)\b", " ", phrase)
    city = ' '.join(city.strip(' .,!?').split())
    if not city:
        return "Укажите город: например, «завтра погода Москва»."
    city = {'москве': 'Москва', 'москва': 'Москва', 'ташкенте': 'Ташкент',
            'ташкент': 'Ташкент', 'санкт-петербурге': 'Санкт-Петербург'}.get(city, city)
    try:
        locations = _get_json('https://geocoding-api.open-meteo.com/v1/search?' +
                              urlencode({'name': city, 'count': 5, 'language': 'ru', 'format': 'json'})).get('results', [])
        if not locations:
            return f"Город «{city}» не найден. Напишите название города без дополнительных слов."
        place = max(locations, key=lambda p: p.get('population', 0))
        params = {'latitude': place['latitude'], 'longitude': place['longitude'],
                  'timezone': 'auto', 'forecast_days': 3, 'wind_speed_unit': 'ms',
                  'daily': 'temperature_2m_min,temperature_2m_max,precipitation_probability_max,wind_speed_10m_max,weather_code'}
        daily = _get_json('https://api.open-meteo.com/v1/forecast?' + urlencode(params))['daily']
        date = daily['time'][day]
        low, high = daily['temperature_2m_min'][day], daily['temperature_2m_max'][day]
        rain, wind = daily['precipitation_probability_max'][day], daily['wind_speed_10m_max'][day]
        if any(v is None for v in (low, high, rain, wind)):
            raise ValueError('Incomplete forecast')
        code = daily['weather_code'][day]
        condition = ('ясно' if code == 0 else 'облачно' if code in (1, 2, 3) else
                     'туман' if code in (45, 48) else 'гроза' if code in (95, 96, 99) else
                     'снег' if code in (71, 73, 75, 77, 85, 86) else
                     'дождь или морось' if code in (51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82) else 'переменная погода')
        label = ('Сегодня', 'Завтра', 'Послезавтра')[day]
        name = ', '.join(filter(None, (place['name'], place.get('country'))))
        return (f"{name}. {label}, {date}: {condition}. "
                f"Температура от {low:+g} до {high:+g} °C. "
                f"Вероятность осадков {rain:g}%, ветер до {wind:g} м/с. "
                "Источник: Open-Meteo. Дата по местному времени города.")
    except Exception:
        return "Не удалось загрузить прогноз погоды. Проверьте интернет и попробуйте снова."
