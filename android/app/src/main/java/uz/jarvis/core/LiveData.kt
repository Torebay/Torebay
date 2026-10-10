package uz.jarvis.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Public weather and exchange feeds: no AI key required. */
object LiveData {
    data class Quote(val label: String, val value: Double, val change: Double, val date: String) {
        fun text() = String.format(Locale.US, "%s  %,.2f  (%+.2f%%)", label, value, change)
    }
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Kartal/1.2")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }
    fun currencies(): List<Quote> = parseCurrencies(JSONArray(get("https://cbu.uz/ru/arkhiv-kursov-valyut/json/")))
    fun parseCurrencies(data: JSONArray): List<Quote> {
        val rows = (0 until data.length()).map { data.getJSONObject(it) }
        val rates = rows.filter { it.getString("Ccy") in listOf("USD", "RUB") }.associateBy { it.getString("Ccy") }
        val result = mutableListOf<Quote>()
        fun current(o: JSONObject) = o.getDouble("Rate") / o.getDouble("Nominal")
        fun previous(o: JSONObject) = (o.getDouble("Rate") - o.getDouble("Diff")) / o.getDouble("Nominal")
        for (code in listOf("USD", "RUB")) rates[code]?.let {
            result += Quote("$code → UZS", current(it), (current(it) / previous(it) - 1) * 100, it.getString("Date"))
        }
        val usd = rates["USD"]; val rub = rates["RUB"]
        if (usd != null && rub != null && usd.getString("Date") == rub.getString("Date")) {
            val value = current(usd) / current(rub)
            result += Quote("USD → RUB", value, (value / (previous(usd) / previous(rub)) - 1) * 100, usd.getString("Date"))
        }
        return result
    }
    fun answer(question: String): String? {
        val q = question.lowercase()
        val weather = Regex("\\bпогод\\w*", RegexOption.IGNORE_CASE).containsMatchIn(q) || q.contains("погода") || q.contains("погоду")
        val prices = listOf("курс", "стоит", "цена", "цены").any { q.contains(it) }
        if (!weather && !prices) return null
        if (listOf("анализ", "прогноз курса", "почему", "купить", "сочинение").any { q.contains(it) }) return null
        return try {
            when {
                weather -> weather(q)
                listOf("btc", "bitcoin", "битко", "бтс", "ethereum", "эфир", "eth").any { q.contains(it) } -> {
                    val data = JSONObject(get("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum&vs_currencies=usd&include_24hr_change=true&include_last_updated_at=true"))
                    listOf("bitcoin", "ethereum").joinToString("\n") { coin ->
                        val item = data.getJSONObject(coin)
                        val date = java.time.Instant.ofEpochSecond(item.getLong("last_updated_at")).toString()
                        String.format(Locale.US, "%s: $%,.2f (%+.2f%% за 24 ч). %s", coin, item.getDouble("usd"), item.getDouble("usd_24h_change"), date)
                    } + "\nИсточник: CoinGecko."
                }
                listOf("валют", "доллар", "рубл", "сум", "usd", "rub", "uzs").any { q.contains(it) } ->
                    currencies().joinToString("\n") { it.text() + " · " + it.date } + "\nЦБ Узбекистана. Процент к прошлой публикации; USD/RUB — кросс-курс."
                else -> null
            }
        } catch (e: Exception) { "Не удалось загрузить свежие данные. Проверьте интернет и повторите запрос." }
    }
    private fun weather(q: String): String {
        if (Regex("недел|вчера|месяц|[0-9]").containsMatchIn(q)) return "Укажите сегодня, завтра или послезавтра и город."
        val day = if (q.contains("послезавтра")) 2 else if (q.contains("завтра")) 1 else 0
        val stop = setOf("погода", "погоду", "погоды", "прогноз", "сегодня", "завтра", "послезавтра", "сейчас", "какая", "какой", "будет", "скажи", "покажи", "пожалуйста", "на", "в", "во", "городе", "город")
        var city = q.trim(' ', '.', '?', '!').split(Regex("\\s+")).filter { it !in stop }.joinToString(" ")
        if (city.isBlank()) return "Укажите город: например, завтра погода Москва."
        city = mapOf("москве" to "Москва", "ташкенте" to "Ташкент", "санкт-петербурге" to "Санкт-Петербург")[city] ?: city
        val results = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=5&language=ru&format=json")).optJSONArray("results")
            ?: return "Город не найден. Напишите название города."
        val p = (0 until results.length()).map { results.getJSONObject(it) }.maxByOrNull { it.optLong("population") }
            ?: return "Город не найден."
        val d = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=${p.getDouble("latitude")}&longitude=${p.getDouble("longitude")}&timezone=auto&forecast_days=3&wind_speed_unit=ms&daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max,wind_speed_10m_max")).getJSONObject("daily")
        return "${p.getString("name")}, ${p.optString("country")}. ${d.getJSONArray("time").getString(day)}: " +
            "от ${d.getJSONArray("temperature_2m_min").getDouble(day)} до ${d.getJSONArray("temperature_2m_max").getDouble(day)} °C. " +
            "Вероятность осадков ${d.getJSONArray("precipitation_probability_max").getInt(day)}%, " +
            "ветер до ${d.getJSONArray("wind_speed_10m_max").getDouble(day)} м/с. Источник: Open-Meteo. Дата по местному времени города."
    }
}
