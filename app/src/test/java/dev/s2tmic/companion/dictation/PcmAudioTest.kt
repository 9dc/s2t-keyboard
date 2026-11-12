package dev.s2tmic.companion.dictation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmAudioTest {
    private fun pcm(vararg samples: Int): ByteArray = ByteBuffer
        .allocate(samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { samples.forEach { putShort(it.toShort()) } }
        .array()

    private fun samples(pcm: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return List(pcm.size / 2) { buffer.getShort().toInt() }
    }

    @Test
    fun leavesAudioAtTargetRateUntouched() {
        val input = pcm(1, -2, 300, -32768, 32767)
        assertArrayEquals(input, PcmAudio.resample(input, PcmAudio.SAMPLE_RATE))
    }

    @Test
    fun averagesSamplesWhenDownsampling() {
        val input = pcm(300, 600, 900, -30, -60, -90, 32767, 32767, 32767)
        assertEquals(listOf(600, -60, 32767), samples(PcmAudio.resample(input, 48_000)))
    }

    @Test
    fun producesExpectedLengthForUnevenRatio() {
        val tenthOfSecond = ByteArray(4_410 * 2)
        assertEquals(1_600 * 2, PcmAudio.resample(tenthOfSecond, 44_100).size)
    }

    @Test
    fun findsPeakOfNegativeAndPositiveSamples() {
        assertEquals(0, PcmAudio.peak(ByteArray(64)))
        assertEquals(1200, PcmAudio.peak(pcm(5, -1200, 800)))
    }

    @Test
    fun writesWavHeaderForMono16BitAudio() {
        val header = ByteBuffer.wrap(PcmAudio.wavHeader(32_000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, header.capacity())
        assertEquals("RIFF", String(header.array(), 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 32_000, header.getInt(4))
        assertEquals("WAVE", String(header.array(), 8, 4, Charsets.US_ASCII))
        assertEquals(1, header.getShort(20).toInt()) // PCM
        assertEquals(1, header.getShort(22).toInt()) // mono
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28)) // bytes per second
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(32_000, header.getInt(40))
    }

    @Test
    fun bufferStopsAtItsLimitAndKeepsEarlierAudio() {
        val buffer = PcmBuffer(maxBytes = 6)
        assertTrue(buffer.append(pcm(100, 200)))
        assertFalse(buffer.append(pcm(300, 400)))
        assertTrue(buffer.append(pcm(-500)))

        val audio = buffer.snapshot()
        assertEquals(6, audio.byteCount)
        assertEquals(500, audio.peak)
        assertEquals(listOf(100, 200, -500), audio.chunks.flatMap(::samples))
    }

    @Test
    fun recognizesMutedMicrophone() {
        val muted = PcmBuffer(1_000).apply { append(ByteArray(640)) }.snapshot()
        assertTrue(muted.isSilent)
        assertEquals(20, muted.durationMs)

        val quietRoom = PcmBuffer(1_000).apply { append(pcm(3, -9, 12)) }.snapshot()
        assertFalse(quietRoom.isSilent)
    }
}
