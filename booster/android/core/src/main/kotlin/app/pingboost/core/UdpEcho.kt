package app.pingboost.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Клиент для замера пинга по UDP. На сервере должен работать эхо-сервис (его ставит
 * `booster/server/pingboost.sh`), который возвращает пакет обратно без изменений.
 *
 * UDP, а не ICMP, потому что обычное Android-приложение не может отправлять ICMP-пакеты,
 * а игры (PUBG Mobile) как раз используют UDP, так что замер ближе к реальности.
 */
class UdpEchoClient(
    host: String,
    private val port: Int,
    private val timeoutMs: Int = 800,
) : AutoCloseable {

    private val address: InetAddress = InetAddress.getByName(host)
    private val socket = DatagramSocket().apply { soTimeout = timeoutMs }
    private var nextSeq = 0

    /** Один круг «туда-обратно». Возвращает задержку в мс или null, если ответа не было. */
    fun ping(): Double? {
        val id = nextSeq++
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE).putInt(id).putLong(System.nanoTime()).array()
        val started = System.nanoTime()
        socket.send(DatagramPacket(payload, payload.size, address, port))

        val deadline = started + timeoutMs * NANOS_PER_MS
        val buffer = ByteArray(64)
        val reply = DatagramPacket(buffer, buffer.size)
        while (true) {
            val remainingMs = ((deadline - System.nanoTime()) / NANOS_PER_MS).toInt()
            if (remainingMs <= 0) return null
            socket.soTimeout = remainingMs // 0 означало бы «ждать вечно», сюда 0 не попадает
            reply.length = buffer.size
            try {
                socket.receive(reply)
            } catch (_: SocketTimeoutException) {
                return null
            }
            // Запоздавший ответ на прошлый пакет пропускаем и ждём свой.
            if (reply.length >= 4 && ByteBuffer.wrap(buffer, 0, 4).int == id) {
                return (System.nanoTime() - started) / NANOS_PER_MS.toDouble()
            }
        }
    }

    override fun close() = socket.close()

    private companion object {
        const val PAYLOAD_SIZE = 12
        const val NANOS_PER_MS = 1_000_000L
    }
}

object UdpEchoProbe {
    /** Серия из [count] замеров с паузой [intervalMs]. Блокирующая, вызывать не из UI-потока. */
    fun run(
        host: String,
        port: Int,
        count: Int = 8,
        intervalMs: Long = 60,
        timeoutMs: Int = 700,
    ): ProbeResult {
        val client = try {
            UdpEchoClient(host, port, timeoutMs)
        } catch (e: IOException) {
            return ProbeResult(sent = count, rtts = emptyList(), error = e.message ?: e.javaClass.simpleName)
        }
        val rtts = ArrayList<Double>(count)
        client.use {
            for (i in 0 until count) {
                try {
                    it.ping()?.let(rtts::add)
                } catch (e: IOException) {
                    return ProbeResult(sent = count, rtts = rtts, error = e.message ?: e.javaClass.simpleName)
                }
                if (i < count - 1) Thread.sleep(intervalMs)
            }
        }
        return ProbeResult(sent = count, rtts = rtts)
    }
}
