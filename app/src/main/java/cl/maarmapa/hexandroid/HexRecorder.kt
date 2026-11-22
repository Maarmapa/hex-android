package cl.maarmapa.hexandroid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/** Captura PCM 16 kHz mono — el equivalente del AudioRecord thread de Hex. */
class HexRecorder(private val context: Context) {
    val sampleRate = 16_000
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var grabando = false
    private val buffer = ArrayDeque<Short>()

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                             AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192)).apply { startRecording() }
        grabando = true
        thread = thread(name = "hex-capture") {
            val chunk = ShortArray(2048)
            while (grabando) {
                val n = record?.read(chunk, 0, chunk.size) ?: break
                synchronized(buffer) { for (i in 0 until n) buffer.add(chunk[i]) }
            }
        }
    }

    fun stop(): ShortArray? {
        grabando = false
        thread?.join(500)
        record?.stop(); record?.release(); record = null
        val data: ShortArray
        synchronized(buffer) { data = buffer.toShortArray(); buffer.clear() }
        return if (data.isNotEmpty()) data else null
    }
}
