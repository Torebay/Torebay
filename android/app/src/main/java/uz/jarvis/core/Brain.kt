package uz.jarvis.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.LocalDate

/** Ответы на свободные вопросы через Claude API, с поиском в интернете (как assistant/brain.py). */
class Brain(
    private val name: String,
    private val apiKey: String,
    private val model: String = "claude-opus-5-5",
    private val historyTurns: Int = 10,
    private val maxSearches: Long = 3,
) {
    private val history = mutableListOf<BetaMessageParam>()
    private val client: AnthropicClient? by lazy {
        if (apiKey.isBlank()) null else AnthropicOkHttpClient.builder().apiKey(apiKey.trim()).build()
    }

    val available get() = apiKey.isNotBlank()

    fun reset() = history.clear()

    private fun systemPrompt(lang: String): String {
        val fallback = mapOf("ru" to "русском", "uz" to "узбекском (латиница)", "tr" to "турецком")
        return "Ты голосовой ассистент по имени $name в телефоне пользователя. " +
            "Пользователь говорит по-русски, по-узбекски или по-турецки. " +
            "Отвечай на том языке, на котором задан вопрос; если язык непонятен, " +
            "отвечай на ${fallback[I18n.code(lang)]}. " +
            "Отвечай коротко (одно-три предложения), простым разговорным языком: " +
            "ответ будет зачитан вслух, поэтому без списков, markdown, ссылок и эмодзи. " +
            "Для свежих данных (курсы валют, акции и криптовалюты на бирже, новости, погода, " +
            "результаты матчей) ищи в интернете и называй числа и время, к которому они относятся. " +
            "Не давай советов, что покупать или продавать. " +
            "Сегодня ${LocalDate.now()}."
    }

    /** Блокирующий вызов: запускать не в главном потоке. */
    fun ask(question: String, lang: String): String {
        val c = client ?: return I18n.t(lang, "no_key")
        val userMessage = BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(question).build()

        var params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(2048L)
            .system(systemPrompt(lang))
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .addTool(BetaWebSearchTool20260209.builder().maxUses(maxSearches).build())
            // Если модель откажется отвечать, сервер сам попробует подходящую запасную модель.
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .messages(history + userMessage)
            .build()

        val response = try {
            var r = c.beta().messages().create(params)
            var continuations = 0
            while (r.stopReason().orElse(null) == BetaStopReason.PAUSE_TURN && continuations < 3) {
                // Поиск ещё идёт: отправляем ответ обратно, сервер продолжит с того же места.
                params = params.toBuilder().addMessage(r).build()
                r = c.beta().messages().create(params)
                continuations++
            }
            r
        } catch (e: UnauthorizedException) {
            return I18n.t(lang, "bad_key")
        } catch (e: RateLimitException) {
            return I18n.t(lang, "rate_limit")
        } catch (e: AnthropicServiceException) {
            return I18n.t(lang, "api_error", e.statusCode())
        } catch (e: AnthropicIoException) {
            return I18n.t(lang, "offline")
        }

        if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) return I18n.t(lang, "refusal")

        val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("").trim()
        if (text.isEmpty()) return I18n.t(lang, "no_answer")

        // Храним только текст: без блоков размышлений и поиска историю можно спокойно обрезать.
        history += userMessage
        history += BetaMessageParam.builder().role(BetaMessageParam.Role.ASSISTANT).content(text).build()
        while (history.size > historyTurns * 2) history.removeAt(0)
        return text
    }
}
