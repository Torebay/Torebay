package uz.jarvis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandsTest {
    private val wake = listOf("kartal", "картал", "qartal")

    @Test fun opensAppsInThreeLanguages() {
        assertEquals(Command(Action.OPEN, "телеграм"), Commands.parse("Открой Телеграм"))
        assertEquals(Command(Action.OPEN, "telegramni"), Commands.parse("Telegramni och"))
        assertEquals(Command(Action.OPEN, "whatsappı"), Commands.parse("WhatsApp'ı aç"))
    }

    @Test fun youtubeSearch() {
        assertEquals(Command(Action.YOUTUBE, "рецепт плова"), Commands.parse("найди на ютубе рецепт плова"))
        assertEquals(Command(Action.OPEN, "youtube"), Commands.parse("открой на ютубе"))
    }

    @Test fun webSearch() {
        assertEquals(Command(Action.SEARCH, "погоду в ташкенте"), Commands.parse("найди погоду в Ташкенте"))
        assertEquals(Command(Action.SEARCH, "dollar kursini"), Commands.parse("dollar kursini qidir"))
    }

    @Test fun timeDateExitLang() {
        assertEquals(Action.TIME, Commands.parse("который час").action)
        assertEquals(Action.DATE, Commands.parse("bugün tarih ne").action)
        assertEquals(Action.EXIT, Commands.parse("стоп").action)
        assertEquals(Command(Action.LANG, "uz"), Commands.parse("говори по-узбекски"))
        assertEquals(Command(Action.LANG, "tr"), Commands.parse("türkçe konuş"))
    }

    @Test fun questionsGoToClaude() {
        assertEquals(Action.ASK, Commands.parse("сколько стоит биткоин").action)
    }

    @Test fun wakeWord() {
        assertEquals(true to "открой ютуб", Commands.stripWakeWord("Картал, открой ютуб", wake))
        assertEquals(true to "saat kaç", Commands.stripWakeWord("Kartal saat kaç", wake))
        assertEquals(false to "открой ютуб", Commands.stripWakeWord("открой ютуб", wake))
    }

    @Test fun appSuffixes() {
        assertEquals(listOf("telegramni", "telegram", "telegramn"), Commands.appNameVariants("telegramni"))
    }
}
