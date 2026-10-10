package uz.jarvis.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Speech PCM is played as it arrives; work stays off the UI thread. */
class CloudSpeech {
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var retryAfter = 0L

    fun cancel() {
        generation.incrementAndGet()
        active?.disconnect()
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
    }

    fun close() { cancel(); worker.shutdownNow() }

    fun speak(text: String, key: String, voice: String, done: () -> Unit, fallback: () -> Unit) {
        val myGeneration = generation.get()
        worker.execute {
            if (generation.get() != myGeneration) return@execute
            if (key.isBlank() || System.currentTimeMillis() < retryAfter) { fallback(); return@execute }
            var output: AudioTrack? = null
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://api.openai.com/v1/audio/speech").openConnection() as HttpURLConnection
                active = connection
                connection.connectTimeout = 8000; connection.readTimeout = 12000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("model", "gpt-4o-mini-tts").put("voice", if (voice == "marin") "marin" else "cedar")
                    .put("input", text.take(4000)).put("response_format", "pcm")
                    .put("instructions", "Speak clearly and naturally in the language of the text. Use crisp pronunciation and short pauses. Do not rush.")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) throw IllegalStateException("Speech unavailable")
                val format = AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                val bufferSize = maxOf(4096, AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
                output = AudioTrack.Builder().setAudioFormat(format).setBufferSizeInBytes(bufferSize)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track = output
                output.play()
                var bytes = 0
                connection.inputStream.use { input ->
                    val buffer = ByteArray(2048)
                    while (generation.get() == myGeneration) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        var offset = 0
                        while (offset < count && generation.get() == myGeneration) {
                            val written = output.write(buffer, offset, count - offset)
                            if (written <= 0) throw IllegalStateException("Audio playback failed")
                            offset += written; bytes += written
                        }
                    }
                }
                if (bytes == 0) throw IllegalStateException("Empty audio")
                val deadline = System.currentTimeMillis() + 2000
                while (generation.get() == myGeneration && output.playbackHeadPosition.toLong() < bytes / 2 && System.currentTimeMillis() < deadline) Thread.sleep(15)
                if (generation.get() == myGeneration) done()
            } catch (_: Exception) {
                retryAfter = System.currentTimeMillis() + 60000
                if (generation.get() == myGeneration) fallback()
            } finally {
                try { output?.stop(); output?.release() } catch (_: Exception) {}
                connection?.disconnect(); active = null; track = null
            }
        }
    }
}
