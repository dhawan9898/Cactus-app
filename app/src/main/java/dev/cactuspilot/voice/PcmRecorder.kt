package dev.cactuspilot.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** Captures 16 kHz mono PCM16 (what the Cactus speech models expect). Needs RECORD_AUDIO granted. */
class PcmRecorder {
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val out = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, onChunk: ((ByteArray) -> Unit)? = null): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 4,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        out.reset()
        record = rec
        rec.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(minBuf)
            while (isActive && out.size() < MAX_BYTES) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(out) { out.write(buf, 0, n) }
                    onChunk?.invoke(buf.copyOf(n))
                }
            }
        }
        return true
    }

    /** Stops capture and returns everything recorded so far. */
    suspend fun stop(): ByteArray {
        job?.cancel()
        job?.join()
        job = null
        record?.let {
            try { it.stop() } catch (_: IllegalStateException) { }
            it.release()
        }
        record = null
        return synchronized(out) { out.toByteArray() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MAX_BYTES = SAMPLE_RATE * 2 * 60 // one minute
    }
}
