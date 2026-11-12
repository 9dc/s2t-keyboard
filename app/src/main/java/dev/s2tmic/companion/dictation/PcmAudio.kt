package dev.s2tmic.companion.dictation

import kotlin.math.abs

/** A finished mono PCM16 recording at [PcmAudio.SAMPLE_RATE], kept as the chunks the recorder delivered. */
class RecordedAudio(val chunks: List<ByteArray>, val byteCount: Int, val peak: Int) {
    val durationMs: Long get() = byteCount * 1000L / PcmAudio.BYTES_PER_SECOND

    /** True when the microphone delivered nothing but zeros, as Android does while it is muted or taken. */
    val isSilent: Boolean get() = peak < SILENT_PEAK

    private companion object {
        const val SILENT_PEAK = 4
    }
}

/** Collects the recorder's chunks up to [maxBytes]. Written by the capture thread, read by the main thread. */
class PcmBuffer(private val maxBytes: Int) {
    private val chunks = ArrayList<ByteArray>()
    private var byteCount = 0
    private var peak = 0

    /** Returns false once the buffer is full; the chunk that did not fit is dropped. */
    @Synchronized
    fun append(chunk: ByteArray): Boolean {
        if (chunk.isEmpty()) return true
        if (byteCount + chunk.size > maxBytes) return false
        chunks += chunk
        byteCount += chunk.size
        peak = maxOf(peak, PcmAudio.peak(chunk))
        return true
    }

    @Synchronized
    fun snapshot(): RecordedAudio = RecordedAudio(ArrayList(chunks), byteCount, peak)
}

object PcmAudio {
    /** Speech models work at 16 kHz, so anything above it only makes the upload larger. */
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE
    const val WAV_HEADER_BYTES = 44

    /** Largest absolute sample value in little-endian PCM16 data. */
    fun peak(pcm: ByteArray): Int {
        var peak = 0
        for (index in 0 until pcm.size / 2) {
            val sample = abs(sampleAt(pcm, index))
            if (sample > peak) peak = sample
        }
        return peak
    }

    /**
     * Converts little-endian mono PCM16 from [sourceRate] to [targetRate]. Downsampling averages the
     * source samples each output sample covers, which filters out most of what would otherwise alias.
     */
    fun resample(input: ByteArray, sourceRate: Int, targetRate: Int = SAMPLE_RATE): ByteArray {
        val inputSamples = input.size / 2
        if (sourceRate == targetRate) return input.copyOf(inputSamples * 2)
        val outputSamples = (inputSamples.toLong() * targetRate / sourceRate).toInt()
        val output = ByteArray(outputSamples * 2)
        for (index in 0 until outputSamples) {
            val from = (index.toLong() * sourceRate / targetRate).toInt()
            val until = ((index + 1L) * sourceRate / targetRate).toInt()
                .coerceIn(from + 1, inputSamples)
            var sum = 0
            for (source in from until until) sum += sampleAt(input, source)
            val sample = sum / (until - from)
            output[index * 2] = sample.toByte()
            output[index * 2 + 1] = (sample shr 8).toByte()
        }
        return output
    }

    /** The 44-byte header of a WAV file holding [pcmBytes] bytes of mono PCM16 at [SAMPLE_RATE]. */
    fun wavHeader(pcmBytes: Int): ByteArray = java.nio.ByteBuffer
        .allocate(WAV_HEADER_BYTES)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcmBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(SAMPLE_RATE)
            putInt(BYTES_PER_SECOND)
            putShort(BYTES_PER_SAMPLE.toShort())
            putShort((BYTES_PER_SAMPLE * 8).toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes)
        }
        .array()

    private fun sampleAt(pcm: ByteArray, index: Int): Int =
        (pcm[index * 2].toInt() and 0xFF) or (pcm[index * 2 + 1].toInt() shl 8)
}
