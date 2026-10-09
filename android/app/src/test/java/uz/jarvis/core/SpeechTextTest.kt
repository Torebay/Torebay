package uz.jarvis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechTextTest {
    @Test
    fun cleansMarkdownLinksAndEmoji() {
        assertEquals(
            "Биткоин стоит 118 тысяч долларов. Источник: CoinGecko.",
            SpeechText.clean("**Биткоин** стоит 118 тысяч долларов 🚀 [1]. Источник: [CoinGecko](https://coingecko.com)."),
        )
        assertEquals("Погода: тепло", SpeechText.clean("## Погода: тепло https://example.com/x"))
        assertEquals("один два", SpeechText.clean("- один\n- два"))
    }

    @Test
    fun splitsStreamIntoSentences() {
        val out = mutableListOf<String>()
        val splitter = SentenceSplitter { out += it }
        listOf("Сейчас 1", "4:05. Курс доллара", " 12 650 сумов! А", " евро дороже").forEach(splitter::add)
        assertEquals(listOf("Сейчас 14:05.", "Курс доллара 12 650 сумов!"), out)
        splitter.flush()
        assertEquals("А евро дороже", out.last())
        splitter.flush()
        assertEquals(3, out.size)
    }

    @Test
    fun keepsDecimalNumbersTogether() {
        val out = mutableListOf<String>()
        val splitter = SentenceSplitter { out += it }
        splitter.add("Рост 2.5 процента. ")
        assertEquals(listOf("Рост 2.5 процента."), out)
    }
}
