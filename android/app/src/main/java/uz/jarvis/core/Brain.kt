package uz.jarvis.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaMessage
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
    // Пары «вопрос — ответ»: их можно сохранить, чтобы разговор пережил перезапуск.
    private val turns = mutableListOf<Pair<String, String>>()
    private val client: AnthropicClient? by lazy {
        if (apiKey.isBlank()) null else AnthropicOkHttpClient.builder().apiKey(apiKey.trim()).build()
    }

    val available get() = apiKey.isNotBlank()

    fun reset() = turns.clear()

    /** Разговор для сохранения в памяти телефона. */
    fun exportTurns(): List<Pair<String, String>> = turns.toList()

    fun importTurns(saved: List<Pair<String, String>>) {
        turns.clear()
        turns += saved.takeLast(historyTurns)
    }

    private fun history(): List<BetaMessageParam> = turns.flatMap { (q, a) ->
        listOf(
            BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(q).build(),
            BetaMessageParam.builder().role(BetaMessageParam.Role.ASSISTANT).content(a).build(),
        )
    }

    private fun systemPrompt(lang: String): String {
        val fallback = mapOf("ru" to "русском", "uz" to "узбекском (латиница)", "tr" to "турецком")
        return "Ты голосовой ассистент по имени $name в телефоне пользователя. " +
            "Пользователь говорит по-русски, по-узбекски или по-турецки. " +
            "Отвечай на том языке, на котором задан вопрос; если язык непонятен, " +
            "отвечай на ${fallback[I18n.code(lang)]}. " +
            "Отвечай коротко (одно-три предложения), простым разговорным языком, сразу по делу, " +
            "без вступлений вроде «сейчас поищу»: " +
            "ответ будет зачитан вслух, поэтому без списков, markdown, ссылок и эмодзи. " +
            "Для свежих данных (курсы валют, акции и криптовалюты на бирже, новости, погода, " +
            "результаты матчей) ищи в интернете и называй числа и время, к которому они относятся. " +
            "Не давай советов, что покупать или продавать. " +
            "Сегодня ${LocalDate.now()}."
    }

    /**
     * Блокирующий вызов: запускать не в главном потоке.
     * Ответ приходит потоком; onSentence получает каждое готовое предложение сразу,
     * чтобы его можно было начать читать, пока остальное ещё пишется.
     */
    fun ask(question: String, lang: String, onSentence: ((String) -> Unit)? = null): String {
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
            .messages(history() + userMessage)
            .build()

        val splitter = SentenceSplitter { sentence -> onSentence?.invoke(sentence) }
        val response = try {
            var r = stream(c, params, splitter)
            var continuations = 0
            while (r.stopReason().orElse(null) == BetaStopReason.PAUSE_TURN && continuations < 3) {
                // Поиск ещё идёт: отправляем ответ обратно, сервер продолжит с того же места.
                params = params.toBuilder().addMessage(r).build()
                r = stream(c, params, splitter)
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
        splitter.flush()

        val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("").trim()
        if (text.isEmpty()) return I18n.t(lang, "no_answer")

        // Храним только текст: без блоков размышлений и поиска историю можно спокойно обрезать.
        turns += question to text
        while (turns.size > historyTurns) turns.removeAt(0)
        return text
    }

    private fun stream(c: AnthropicClient, params: MessageCreateParams, splitter: SentenceSplitter): BetaMessage {
        val acc = BetaMessageAccumulator.create()
        c.beta().messages().createStreaming(params).use { events ->
            events.stream().forEach { event ->
                acc.accumulate(event)
                event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { splitter.add(it.text()) }
                // Перед поиском в интернете договариваем то, что уже написано.
                event.contentBlockStart().ifPresent { if (!it.contentBlock().isText()) splitter.flush() }
            }
        }
        return acc.message()
    }
}
