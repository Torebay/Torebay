package uz.jarvis.core

/** Подготовка текста к чтению вслух: убираем то, что синтезатор читает плохо. */
object SpeechText {
    private val markdownLink = Regex("""\[([^\]]+)]\((?:[^)]*)\)""")
    private val url = Regex("""(?:https?://|www\.)\S+""")
    private val citation = Regex("""\[\d+(?:,\s*\d+)*]""")
    private val markup = Regex("""[*_`#>|~]+""")
    private val listMarker = Regex("""(?m)^\s*(?:[-•]|\d+[.)])\s+""")
    private val spaces = Regex("""[ \t]+""")

    fun clean(text: String): String {
        var s = markdownLink.replace(text) { it.groupValues[1] }
        s = url.replace(s, "")
        s = citation.replace(s, "")
        s = listMarker.replace(s, "")
        s = markup.replace(s, " ")
        s = s.filter { !isEmoji(it) }
        s = s.replace("\n", " ")
        return spaces.replace(s, " ").replace(" ,", ",").replace(" .", ".").trim()
    }

    private fun isEmoji(c: Char): Boolean =
        Character.isSurrogate(c) || c.code in 0x2600..0x27BF || c.code == 0xFE0F || c.code == 0x200D
}

/**
 * Режет поток текста на предложения, чтобы начинать говорить, пока ответ ещё пишется.
 * add() вызывается с каждым кусочком ответа, flush() — в конце.
 */
class SentenceSplitter(private val minLength: Int = 2, private val emit: (String) -> Unit) {
    private val buffer = StringBuilder()

    fun add(delta: String) {
        buffer.append(delta)
        while (true) {
            val end = sentenceEnd() ?: return
            val sentence = buffer.substring(0, end).trim()
            buffer.delete(0, end)
            if (sentence.isNotEmpty()) emit(sentence)
        }
    }

    fun flush() {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        if (rest.isNotEmpty()) emit(rest)
    }

    /** Позиция сразу после конца первого законченного предложения или null. */
    private fun sentenceEnd(): Int? {
        for (i in buffer.indices) {
            val c = buffer[i]
            val end = when {
                c == '\n' -> i + 1
                c in ".!?…" && i + 1 < buffer.length && buffer[i + 1].isWhitespace() -> i + 1
                else -> continue
            }
            if (buffer.substring(0, end).trim().length >= minLength) return end
        }
        return null
    }
}
