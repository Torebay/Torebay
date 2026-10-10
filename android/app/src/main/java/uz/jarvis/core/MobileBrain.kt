package uz.jarvis.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Personal Gemini/OpenAI clients; keys are supplied by the app's secure storage. */
class MobileBrain(private val name: String, private val provider: String, private val key: String,
                  private val model: String, private val search: Boolean = true) {
    private val turns = mutableListOf<Pair<String, String>>()
    val available get() = key.isNotBlank()
    @Synchronized fun reset() = turns.clear()
    @Synchronized fun exportTurns() = turns.toList()
    @Synchronized fun importTurns(saved: List<Pair<String, String>>) { turns.clear(); turns += saved.takeLast(10) }

    fun ask(question: String, lang: String, onSentence: ((String) -> Unit)? = null): String {
        LiveData.answer(question)?.let { return it }
        if (!available) return "Добавьте ключ Gemini или OpenAI в настройках. Локальные команды уже работают."
        if (model.isBlank()) return "В настройках загрузите список моделей и выберите модель."
        val prompt = "Ты ассистент $name. Отвечай кратко на языке пользователя ($lang), без markdown. " +
            "Ты не можешь управлять телефоном из этого запроса. Не утверждай, что выполнил действие. " +
            if (search) "Для погоды, новостей и курсов используй поиск. Указывай дату данных. Если данных нет, сообщи об этом." else "Поиск отключён. Не выдумывай свежие данные."
        val history = exportTurns()
        val splitter = SentenceSplitter { onSentence?.invoke(it) }
        return try {
            val sources = mutableListOf<String>()
            val text: String
            if (provider == "openai") {
                val input = JSONArray()
                history.forEach { (q, a) ->
                    input.put(JSONObject().put("role", "user").put("content", q))
                    input.put(JSONObject().put("role", "assistant").put("content", a))
                }
                input.put(JSONObject().put("role", "user").put("content", question))
                val body = JSONObject().put("model", model).put("input", input).put("instructions", prompt)
                    .put("max_output_tokens", 2048).put("store", false)
                if (search) body.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                body.put("stream", true)
                val result = streamResult("https://api.openai.com/v1/responses", provider, key, body) { splitter.add(it) }
                val output = result.optJSONArray("output") ?: JSONArray()
                val pieces = mutableListOf<String>()
                for (i in 0 until output.length()) {
                    val content = output.getJSONObject(i).optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.getJSONObject(j)
                        if (part.optString("type") == "output_text") pieces += part.optString("text")
                        val annotations = part.optJSONArray("annotations") ?: continue
                        for (k in 0 until annotations.length()) {
                            val url = annotations.getJSONObject(k).optString("url")
                            if (url.isNotBlank()) sources += url
                        }
                    }
                }
                text = pieces.joinToString("\n")
            } else {
                val contents = JSONArray()
                fun message(role: String, value: String) = JSONObject().put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", value)))
                history.forEach { (q, a) -> contents.put(message("user", q)); contents.put(message("model", a)) }
                contents.put(message("user", question))
                val body = JSONObject().put("contents", contents)
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 2048))
                if (model == "gemini-3.8-flash") body.getJSONObject("generationConfig")
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
                if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                val escaped = URLEncoder.encode(model.removePrefix("models/"), "UTF-8")
                val result = streamResult("https://generativelanguage.googleapis.com/v1beta/models/$escaped:streamGenerateContent?alt=sse", provider, key, body) { splitter.add(it) }
                val candidate = result.optJSONArray("candidates")?.optJSONObject(0)
                val parts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                text = (0 until parts.length()).map { parts.getJSONObject(it) }
                    .filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
                val chunks = candidate?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks") ?: JSONArray()
                for (i in 0 until chunks.length()) {
                    val url = chunks.getJSONObject(i).optJSONObject("web")?.optString("uri").orEmpty()
                    if (url.isNotBlank()) sources += url
                }
            }
            if (text.isBlank()) return "ИИ не вернул текст. Попробуйте другой вопрос или модель."
            val answer = text + if (sources.isEmpty()) "" else "\n\nИсточники:\n" + sources.distinct().joinToString("\n")
            synchronized(this) { turns += question to answer; while (turns.size > 10) turns.removeAt(0) }
            splitter.flush()
            answer
        } catch (e: ApiFailure) {
            if (search && e.code == 429) {
                val notice = "Поиск ограничен сервисом. Отвечаю без поиска; свежие сведения не проверены."
                onSentence?.invoke(notice)
                val fallback = MobileBrain(name, provider, key, model, false)
                fallback.importTurns(history)
                val result = fallback.ask(question, lang, onSentence)
                importTurns(fallback.exportTurns())
                return notice + "\n" + result
            }
            e.message ?: "Ошибка сервиса ИИ."
        } catch (e: Exception) {
            "Не удалось получить ответ. Проверьте интернет и настройки ИИ."
        }
    }

    companion object {
        private fun streamResult(url: String, provider: String, key: String, body: JSONObject,
                                 onText: (String) -> Unit): JSONObject {
            val connection = URL(url).openConnection() as HttpURLConnection
            var complete = false
            var final = JSONObject()
            var grounding: JSONObject? = null
            val text = StringBuilder()
            try {
                connection.connectTimeout = 15000; connection.readTimeout = 35000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "text/event-stream")
                connection.setRequestProperty(if (provider == "openai") "Authorization" else "x-goog-api-key",
                    if (provider == "openai") "Bearer $key" else key)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) throw ApiFailure("Ошибка ИИ ${connection.responseCode}. Проверьте ключ, модель, лимиты и поддержку поиска.", connection.responseCode)
                val pending = StringBuilder()
                fun event(data: String) {
                    if (data.isBlank() || data == "[DONE]") return
                    val value = JSONObject(data)
                    if (provider == "openai") {
                        when (value.optString("type")) {
                            "response.output_text.delta" -> onText(value.optString("delta"))
                            "response.completed" -> { complete = true; final = value.getJSONObject("response") }
                            "error", "response.failed", "response.incomplete" -> throw ApiFailure("Ответ ИИ не завершён. Попробуйте другую модель или повторите запрос.")
                        }
                    } else {
                        val candidate = value.optJSONArray("candidates")?.optJSONObject(0) ?: return
                        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (!part.optBoolean("thought")) { val chunk = part.optString("text"); text.append(chunk); onText(chunk) }
                        }
                        candidate.optJSONObject("groundingMetadata")?.let { grounding = it }
                        if (candidate.has("finishReason")) { complete = true; final = candidate }
                    }
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("data:")) { if (pending.isNotEmpty()) pending.append('\n'); pending.append(line.drop(5).trimStart()) }
                        else if (line.isEmpty() && pending.isNotEmpty()) { event(pending.toString()); pending.clear() }
                    }
                }
                if (pending.isNotEmpty()) event(pending.toString())
                if (!complete) throw ApiFailure("Связь прервалась до завершения ответа. Повторите запрос.")
                if (provider == "openai") return final
                final.put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text.toString()))))
                grounding?.let { final.put("groundingMetadata", it) }
                return JSONObject().put("candidates", JSONArray().put(final))
            } finally { connection.disconnect() }
        }

        fun models(provider: String, key: String): List<String> {
            if (key.isBlank()) throw ApiFailure("Сначала вставьте API-ключ.")
            val url = if (provider == "openai") "https://api.openai.com/v1/models" else
                "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
            val data = request(url, provider, key)
            val array = data.optJSONArray(if (provider == "openai") "data" else "models") ?: JSONArray()
            return (0 until array.length()).map { array.getJSONObject(it) }.filter {
                if (provider == "openai") it.optString("id").startsWith("gpt-") &&
                    listOf("audio", "realtime", "image", "transcribe", "tts").none { word -> it.optString("id").contains(word) }
                else it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true
            }.map { it.optString(if (provider == "openai") "id" else "name").removePrefix("models/") }.sorted()
        }

        private fun request(url: String, provider: String, key: String, body: JSONObject? = null): JSONObject {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15000
                connection.readTimeout = 35000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty(if (provider == "openai") "Authorization" else "x-goog-api-key",
                    if (provider == "openai") "Bearer $key" else key)
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code !in 200..299) throw ApiFailure(when (code) {
                    400 -> "Проверьте модель, ключ и поддержку поиска в настройках."
                    401, 403 -> "Ключ не принят или доступ к сервису запрещён."
                    404 -> "Модель не найдена. Загрузите список моделей в настройках."
                    429 -> "Лимит запросов или баланс исчерпан. Проверьте аккаунт сервиса."
                    else -> "Сервис ИИ вернул ошибку $code."
                })
                return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            } finally { connection.disconnect() }
        }
    }
}

class ApiFailure(message: String, val code: Int = 0) : Exception(message)
