package app.pingboost.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {

    @Test
    fun `avg min max and loss`() {
        val r = ProbeResult(sent = 5, rtts = listOf(30.0, 50.0, 40.0, 60.0))
        assertEquals(45.0, r.avgMs)
        assertEquals(30.0, r.minMs)
        assertEquals(60.0, r.maxMs)
        assertEquals(20.0, r.lossPercent)
    }

    @Test
    fun `jitter is mean abs difference of neighbours`() {
        // |50-30| + |40-50| + |60-40| = 50, делим на 3 разницы
        val r = ProbeResult(sent = 4, rtts = listOf(30.0, 50.0, 40.0, 60.0))
        assertEquals(50.0 / 3, r.jitterMs, 1e-9)
    }

    @Test
    fun `jitter of fewer than two samples is zero`() {
        assertEquals(0.0, ProbeResult(1, listOf(20.0)).jitterMs)
        assertEquals(0.0, ProbeResult(3, emptyList()).jitterMs)
    }

    @Test
    fun `nothing sent counts as full loss`() {
        assertEquals(100.0, ProbeResult.EMPTY.lossPercent)
        assertTrue(!ProbeResult.EMPTY.reachable)
    }

    @Test
    fun `picker prefers lower ping`() {
        val results = mapOf(
            "de" to ProbeResult(10, List(10) { 40.0 }),
            "fi" to ProbeResult(10, List(10) { 25.0 }),
            "ru" to ProbeResult(10, List(10) { 70.0 }),
        )
        assertEquals("fi", ServerPicker.best(results))
    }

    @Test
    fun `picker punishes loss and jitter`() {
        val lossy = ProbeResult(10, List(8) { 20.0 }) // 20% потерь = +200 к оценке
        val steady = ProbeResult(10, List(10) { 60.0 })
        assertEquals("steady", ServerPicker.best(mapOf("lossy" to lossy, "steady" to steady)))

        val jittery = ProbeResult(10, List(10) { if (it % 2 == 0) 20.0 else 80.0 }) // avg 50, jitter 60
        val calm = ProbeResult(10, List(10) { 90.0 })
        assertEquals("calm", ServerPicker.best(mapOf("jittery" to jittery, "calm" to calm)))
    }

    @Test
    fun `picker ignores unreachable and returns null when nothing answers`() {
        val down = ProbeResult(10, emptyList())
        val up = ProbeResult(10, List(10) { 200.0 })
        assertEquals("up", ServerPicker.best(mapOf("down" to down, "up" to up)))
        assertNull(ServerPicker.best(mapOf("down" to down)))
        assertNull(ServerPicker.best(emptyMap<String, ProbeResult>()))
    }

    @Test
    fun `rolling window keeps only capacity and tracks loss`() {
        val w = RollingProbeWindow(capacity = 4)
        listOf(10.0, null, 30.0, 40.0, 50.0).forEach(w::add)
        assertEquals(listOf(null, 30.0, 40.0, 50.0), w.samples())
        assertEquals(50.0, w.last())
        val s = w.snapshot()
        assertEquals(4, s.sent)
        assertEquals(25.0, s.lossPercent)
        assertEquals(40.0, s.avgMs)
    }

    @Test
    fun `rolling window snapshot of last N`() {
        val w = RollingProbeWindow(capacity = 10)
        listOf(100.0, 100.0, 10.0, 20.0).forEach(w::add)
        assertEquals(15.0, w.snapshot(lastN = 2).avgMs)
    }
}
