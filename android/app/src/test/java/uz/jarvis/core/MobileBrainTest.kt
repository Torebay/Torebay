package uz.jarvis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileBrainTest {
    @Test fun missingKeysDoNotCallNetwork() {
        for (provider in listOf("gemini", "openai")) {
            val brain = MobileBrain("Kartal", provider, "", "test-model")
            assertFalse(brain.available)
            assertTrue(brain.ask("Привет", "ru").contains("ключ"))
            assertTrue(brain.exportTurns().isEmpty())
        }
    }

    @Test fun missingModelExplainsSetupWithoutNetwork() {
        val brain = MobileBrain("Kartal", "gemini", "test-key", "")
        assertTrue(brain.ask("Привет", "ru").contains("модел"))
    }

    @Test fun historyIsBoundedAndResetWorks() {
        val brain = MobileBrain("Kartal", "openai", "", "")
        brain.importTurns((0..15).map { "Q$it" to "A$it" })
        assertEquals(10, brain.exportTurns().size)
        assertEquals("Q6", brain.exportTurns().first().first)
        brain.reset()
        assertTrue(brain.exportTurns().isEmpty())
    }
}
