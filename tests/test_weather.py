from assistant.weather import weather_answer
from assistant.local_commands import handle_local


def test_tomorrow_uses_second_local_day_without_ai(monkeypatch):
    urls = []
    def get(url):
        urls.append(url)
        if 'geocoding' in url:
            return {'results': [{'name': 'Москва', 'country': 'Россия', 'latitude': 55.75, 'longitude': 37.62}]}
        return {'daily': {'time': ['2026-10-11', '2026-10-12', '2026-10-13'],
                         'temperature_2m_min': [1, 2, 3], 'temperature_2m_max': [4, 5, 6],
                         'precipitation_probability_max': [0, 70, 10],
                         'wind_speed_10m_max': [1, 2, 3], 'weather_code': [0, 61, 3]}}
    monkeypatch.setattr('assistant.weather._get_json', get)
    result = handle_local('завтра погода Москва', None)
    assert '2026-10-12' in result and 'от +2 до +5' in result
    assert '70%' in result and 'Open-Meteo' in result
    assert 'timezone=auto' in urls[1]


def test_missing_city_and_non_weather_do_not_fetch(monkeypatch):
    monkeypatch.setattr('assistant.weather._get_json', lambda url: (_ for _ in ()).throw(AssertionError()))
    assert 'Укажите город' in weather_answer('погода завтра')
    assert weather_answer('привет') is None
    assert 'Пока могу' in weather_answer('погода на неделю Москва')


def test_weather_network_failure(monkeypatch):
    def fail(url):
        raise OSError()
    monkeypatch.setattr('assistant.weather._get_json', fail)
    assert 'Не удалось загрузить' in weather_answer('погода в Москве')
