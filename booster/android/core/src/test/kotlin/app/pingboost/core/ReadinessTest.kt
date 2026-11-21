package app.pingboost.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadinessTest {
    private val good = DeviceFacts(
        maxRefreshHz = 120f,
        currentRefreshHz = 120f,
        thermalStatus = 0,
        powerSave = false,
        batteryPercent = 80,
        charging = false,
        availRamMb = 3000,
        totalRamMb = 8000,
        dndOn = true,
        gameInstalled = true,
    )

    private fun Readiness.levelOf(f: DeviceFacts, titlePart: String): Level =
        checks(f).first { it.title.contains(titlePart, ignoreCase = true) }.level

    @Test
    fun `fully prepared phone is ready`() {
        val checks = Readiness.checks(good)
        assertTrue(checks.all { it.level == Level.Ok }, checks.toString())
        assertEquals(Verdict.Ready, Readiness.verdict(checks))
    }

    @Test
    fun `fps cap follows display`() {
        assertEquals(144, Readiness.fpsCap(144f))
        assertEquals(144, Readiness.fpsCap(143.99f)) // 143.99 Гц, как отдают некоторые панели
        assertEquals(120, Readiness.fpsCap(120f))
        assertEquals(120, Readiness.fpsCap(119.9f))
        assertEquals(90, Readiness.fpsCap(90f))
        assertEquals(60, Readiness.fpsCap(60f))
        assertEquals(60, Readiness.fpsCap(75f)) // игра такого потолка не предлагает
    }

    @Test
    fun `60 Hz screen is a warning and says so`() {
        val c = Readiness.checks(good.copy(maxRefreshHz = 60f, currentRefreshHz = 60f))
            .first { it.title.contains("60 Гц") }
        assertEquals(Level.Warn, c.level)
    }

    @Test
    fun `90 Hz screen never promises 120`() {
        val c = Readiness.checks(good.copy(maxRefreshHz = 90f)).first { it.title.contains("Экран") }
        assertEquals(Level.Ok, c.level)
        assertTrue(c.detail.contains("120 и 144 на этом экране невозможны"))
    }

    @Test
    fun `thermal states`() {
        assertEquals(Level.Ok, Readiness.levelOf(good.copy(thermalStatus = 1), "Температура"))
        assertEquals(Level.Warn, Readiness.levelOf(good.copy(thermalStatus = 2), "нагрет"))
        assertEquals(Level.Bad, Readiness.levelOf(good.copy(thermalStatus = 3), "перегрет"))
        assertEquals(Level.Bad, Readiness.levelOf(good.copy(thermalStatus = 5), "перегрет"))
    }

    @Test
    fun `unknown thermal status adds no check`() {
        val titles = Readiness.checks(good.copy(thermalStatus = null)).map { it.title }
        assertTrue(titles.none { it.contains("Температура") || it.contains("нагрет") || it.contains("перегрет") })
    }

    @Test
    fun `power save is bad`() {
        assertEquals(Level.Bad, Readiness.levelOf(good.copy(powerSave = true), "энергосбережение"))
    }

    @Test
    fun `low battery only matters when not charging`() {
        assertEquals(Level.Warn, Readiness.levelOf(good.copy(batteryPercent = 10, charging = false), "Заряд"))
        assertEquals(Level.Ok, Readiness.levelOf(good.copy(batteryPercent = 10, charging = true), "Заряд"))
        assertEquals(Level.Ok, Readiness.levelOf(good.copy(batteryPercent = 50), "Заряд"))
    }

    @Test
    fun `unknown battery adds no check`() {
        assertTrue(Readiness.checks(good.copy(batteryPercent = null)).none { it.title.startsWith("Заряд") })
    }

    @Test
    fun `memory thresholds`() {
        assertEquals(Level.Bad, Readiness.levelOf(good.copy(availRamMb = 500), "памяти"))
        assertEquals(Level.Warn, Readiness.levelOf(good.copy(availRamMb = 900), "Свободно"))
        assertEquals(Level.Ok, Readiness.levelOf(good.copy(availRamMb = 1200), "Свободно"))
    }

    @Test
    fun `dnd off is warning, game missing is bad`() {
        assertEquals(Level.Warn, Readiness.levelOf(good.copy(dndOn = false), "Не беспокоить"))
        assertEquals(Level.Bad, Readiness.levelOf(good.copy(gameInstalled = false), "PUBG"))
    }

    @Test
    fun `verdict takes the worst level`() {
        assertEquals(Verdict.AlmostReady, Readiness.verdict(Readiness.checks(good.copy(dndOn = false))))
        assertEquals(Verdict.Problems, Readiness.verdict(Readiness.checks(good.copy(dndOn = false, powerSave = true))))
    }
}
