package app.pingboost.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WgConfTest {
    private val sample = """
        # Name: Германия
        # PingPort = 51900
        [Interface]
        PrivateKey = AAAA
        Address = 10.77.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = BBBB
        Endpoint = 203.0.113.7:51830
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    @Test
    fun `endpoint host and port`() {
        assertEquals("203.0.113.7", WgConf.endpointHost(sample))
        assertEquals(51830, WgConf.endpointPort(sample))
    }

    @Test
    fun `endpoint with hostname ipv6 and trailing comment`() {
        assertEquals("de.example.com", WgConf.endpointHost("[Peer]\nEndpoint = de.example.com:51820"))
        assertEquals("2001:db8::1", WgConf.endpointHost("[Peer]\nEndpoint = [2001:db8::1]:51820"))
        assertEquals(51820, WgConf.endpointPort("[Peer]\nEndpoint = [2001:db8::1]:51820"))
        assertEquals("1.2.3.4", WgConf.endpointHost("[Peer]\nEndpoint = 1.2.3.4:1 # main"))
        assertEquals("2001:db8::1", WgConf.endpointHost("[Peer]\nEndpoint = 2001:db8::1"))
        assertNull(WgConf.endpointPort("[Peer]\nEndpoint = 2001:db8::1"))
    }

    @Test
    fun `endpoint is case insensitive and may be missing`() {
        assertEquals("a.b", WgConf.endpointHost("[Peer]\nendpoint=a.b:1"))
        assertNull(WgConf.endpointHost("[Interface]\nPrivateKey = x"))
        // Закомментированный Endpoint не считается.
        assertNull(WgConf.endpointHost("# Endpoint = 1.1.1.1:1"))
    }

    @Test
    fun `name hint and ping port`() {
        assertEquals("Германия", WgConf.nameHint(sample))
        assertEquals(51900, WgConf.pingPort(sample))
        assertNull(WgConf.nameHint("[Interface]"))
        assertEquals(WgConf.DEFAULT_PING_PORT, WgConf.pingPort("[Interface]"))
        assertEquals(WgConf.DEFAULT_PING_PORT, WgConf.pingPort("# PingPort = 99999"))
    }

    @Test
    fun `included apps are inserted right after Interface header`() {
        val out = WgConf.withIncludedApps(sample, listOf("com.tencent.ig", "com.pubg.imobile"))
        val lines = out.lines()
        val at = lines.indexOf("[Interface]")
        assertEquals("IncludedApplications = com.tencent.ig, com.pubg.imobile", lines[at + 1])
        assertEquals("PrivateKey = AAAA", lines[at + 2])
        // Остальное не потеряно.
        assertTrue(out.contains("Endpoint = 203.0.113.7:51830"))
        assertTrue(out.contains("AllowedIPs = 0.0.0.0/0"))
    }

    @Test
    fun `existing application lists in config are replaced`() {
        val conf = "[Interface]\nPrivateKey = x\nExcludedApplications = com.evil\nIncludedApplications = everything\n[Peer]\nEndpoint = a:1"
        val out = WgConf.withIncludedApps(conf, listOf("com.tencent.ig"))
        assertFalse(out.contains("com.evil"))
        assertFalse(out.contains("everything"))
        assertFalse(out.contains("ExcludedApplications"))
        assertEquals(1, out.lines().count { it.startsWith("IncludedApplications") })
    }

    @Test
    fun `windows line endings and header case`() {
        val out = WgConf.withIncludedApps("[interface]\r\nPrivateKey = x\r\n[Peer]\r\nEndpoint = a:1\r\n", listOf("p"))
        assertEquals("[interface]", out.lines()[0])
        assertEquals("IncludedApplications = p", out.lines()[1])
    }

    @Test
    fun `rejects empty app list and missing Interface`() {
        assertFailsWith<IllegalArgumentException> { WgConf.withIncludedApps(sample, emptyList()) }
        assertFailsWith<IllegalArgumentException> { WgConf.withIncludedApps("[Peer]\nEndpoint = a:1", listOf("p")) }
    }

    @Test
    fun `pubg package list contains global and bgmi`() {
        assertTrue("com.tencent.ig" in PubgGames.packages)
        assertTrue("com.pubg.imobile" in PubgGames.packages)
        assertEquals(PubgGames.packages.size, PubgGames.packages.toSet().size)
    }
}
