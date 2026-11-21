package app.pingboost.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UdpEchoProbeTest {

    /** Локальный эхо-сервер; [dropEvery] = N роняет каждый N-й пакет (имитация потерь). */
    private class EchoServer(private val dropEvery: Int = 0, private val delayMs: Long = 0) : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort

        @Volatile
        private var running = true

        private val thread = Thread {
            var n = 0
            val buf = ByteArray(256)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                } catch (_: Exception) {
                    break
                }
                n++
                if (dropEvery > 0 && n % dropEvery == 0) continue
                if (delayMs > 0) Thread.sleep(delayMs)
                socket.send(DatagramPacket(p.data, p.length, p.address, p.port))
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            running = false
            socket.close()
            thread.join(1000)
        }
    }

    @Test
    fun `all packets answered gives zero loss and small rtt`() {
        EchoServer().use { server ->
            val r = UdpEchoProbe.run("127.0.0.1", server.port, count = 5, intervalMs = 5, timeoutMs = 500)
            assertEquals(5, r.sent)
            assertEquals(5, r.received)
            assertEquals(0.0, r.lossPercent)
            assertTrue(r.reachable)
            assertNotNull(r.avgMs)
            assertTrue(r.avgMs!! < 200, "loopback rtt should be tiny, was ${r.avgMs}")
            assertNull(r.error)
        }
    }

    @Test
    fun `dropped packets are counted as loss`() {
        EchoServer(dropEvery = 2).use { server ->
            val r = UdpEchoProbe.run("127.0.0.1", server.port, count = 6, intervalMs = 5, timeoutMs = 150)
            assertEquals(6, r.sent)
            assertEquals(3, r.received)
            assertEquals(50.0, r.lossPercent)
        }
    }

    @Test
    fun `delay of server is reflected in rtt`() {
        EchoServer(delayMs = 40).use { server ->
            val r = UdpEchoProbe.run("127.0.0.1", server.port, count = 3, intervalMs = 5, timeoutMs = 500)
            assertEquals(3, r.received)
            assertTrue(r.minMs!! >= 35, "expected rtt >= ~40ms, was ${r.minMs}")
        }
    }

    @Test
    fun `nobody listening means full loss`() {
        // Берём свободный порт и сразу закрываем сокет, на нём никто не слушает.
        val port = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val r = UdpEchoProbe.run("127.0.0.1", port, count = 3, intervalMs = 1, timeoutMs = 80)
        assertEquals(3, r.sent)
        assertEquals(0, r.received)
        assertEquals(100.0, r.lossPercent)
        assertTrue(!r.reachable)
        assertNull(r.avgMs)
    }

    @Test
    fun `unresolvable host reports error instead of throwing`() {
        val r = UdpEchoProbe.run("host-that-does-not-exist.invalid", 51831, count = 2, timeoutMs = 50)
        assertEquals(0, r.received)
        assertNotNull(r.error)
    }

    @Test
    fun `late reply to previous packet is ignored`() {
        // Сервер отвечает на каждый пакет дважды: вторая копия придёт, когда клиент уже ждёт
        // следующий номер, и не должна засчитаться как ответ на него.
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val t = Thread {
            val buf = ByteArray(256)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                } catch (_: Exception) {
                    break
                }
                repeat(2) { socket.send(DatagramPacket(p.data, p.length, p.address, p.port)) }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            UdpEchoClient("127.0.0.1", socket.localPort, 300).use { c ->
                repeat(4) { assertNotNull(c.ping()) }
            }
        } finally {
            socket.close()
            t.join(1000)
        }
    }
}
