package com.mentra.bluetoothsdk.sgcs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

class ViveEagleResamplerTest {

    private fun pcm(frames: Int, channels: Int, sample: (frame: Int, channel: Int) -> Int): ByteArray {
        val out = ByteArray(frames * channels * 2)
        var p = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val s = sample(f, c).coerceIn(-32768, 32767)
                out[p++] = (s and 0xFF).toByte()
                out[p++] = ((s shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun tone(rate: Int, hz: Double, amplitude: Double, seconds: Double, channels: Int = 1): ByteArray =
        pcm((rate * seconds).toInt(), channels) { f, _ -> (amplitude * sin(2 * PI * hz * f / rate)).roundToInt() }

    private fun samples(pcm: ByteArray): IntArray =
        IntArray(pcm.size / 2) { (pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xFF) }

    /** RMS after skipping the filter's start-up transient. */
    private fun rms(pcm: ByteArray, skip: Int = 400): Double {
        val s = samples(pcm).drop(skip)
        return sqrt(s.sumOf { it.toDouble() * it } / s.size)
    }

    @Test
    fun `one second at 44_1 and 48 kHz becomes one second at 16 kHz`() {
        for (rate in listOf(44_100, 48_000)) {
            val out = ViveEagleResampler(16_000).process(tone(rate, 440.0, 8000.0, 1.0), rate * 2, rate, 1)
            assertThat(out.size % 2).isEqualTo(0)
            assertThat(out.size / 2).`as`("output frames for $rate Hz").isCloseTo(16_000, within(2))
        }
    }

    @Test
    fun `stream split into chunks gives the same output as one buffer`() {
        val rate = 44_100
        val input = tone(rate, 1000.0, 9000.0, 0.5, channels = 2)
        val whole = ViveEagleResampler(16_000).process(input, input.size, rate, 2)

        val chunked = ViveEagleResampler(16_000)
        val parts = java.io.ByteArrayOutputStream()
        var offset = 0
        for (size in generateSequence(256) { it * 3 % 2000 + 4 }) {
            if (offset >= input.size) break
            val n = minOf(size - size % 4, input.size - offset).coerceAtLeast(4)
            parts.write(chunked.process(input.copyOfRange(offset, offset + n), n, rate, 2))
            offset += n
        }
        val joined = samples(parts.toByteArray())
        val single = samples(whole)
        assertThat(joined.size).isCloseTo(single.size, within(1))
        for (i in 0 until minOf(joined.size, single.size)) {
            assertThat(joined[i]).`as`("sample $i").isCloseTo(single[i], within(1))
        }
    }

    @Test
    fun `stereo at the target rate is averaged to mono`() {
        val input = pcm(4, 2) { f, c ->
            when (f) {
                0 -> if (c == 0) 1000 else 3000
                1 -> if (c == 0) -32768 else -32768
                2 -> if (c == 0) 32767 else -32767
                else -> if (c == 0) -101 else 100
            }
        }
        val out = samples(ViveEagleResampler(16_000).process(input, input.size, 16_000, 2))
        assertThat(out).containsExactly(2000, -32768, 0, 0)
    }

    @Test
    fun `stereo is averaged to mono when downsampling`() {
        val rate = 48_000
        // Constant left 1000 and right 3000: the low-pass has unity DC gain, so the mono
        // output settles on their mean.
        val input = pcm(rate / 4, 2) { _, c -> if (c == 0) 1000 else 3000 }
        val out = samples(ViveEagleResampler(16_000).process(input, input.size, rate, 2)).drop(200)
        assertThat(out).isNotEmpty
        assertThat(out).allSatisfy { assertThat(it).isCloseTo(2000, within(2)) }
    }

    @Test
    fun `opposite-phase channels cancel in the mono mix`() {
        val rate = 48_000
        val input = pcm(rate / 4, 2) { f, c ->
            val s = (8000 * sin(2 * PI * 1000.0 * f / rate)).roundToInt()
            if (c == 0) s else -s
        }
        val out = samples(ViveEagleResampler(16_000).process(input, input.size, rate, 2))
        assertThat(out.maxOf { abs(it) }).isLessThanOrEqualTo(1)
    }

    @Test
    fun `a tone well above 8 kHz is strongly attenuated`() {
        for (rate in listOf(44_100, 48_000)) {
            for (hz in listOf(10_000.0, 12_000.0, 15_000.0)) {
                val input = tone(rate, hz, 16_000.0, 1.0)
                val out = ViveEagleResampler(16_000).process(input, input.size, rate, 1)
                val inputRms = 16_000.0 / sqrt(2.0)
                // Without the low-pass these would alias into the speech band at near full level.
                assertThat(rms(out) / inputRms).`as`("$hz Hz at $rate Hz").isLessThan(0.01) // < -40 dB
            }
        }
    }

    @Test
    fun `a low tone passes nearly unchanged`() {
        for (rate in listOf(44_100, 48_000)) {
            for (hz in listOf(300.0, 1000.0, 3000.0)) {
                val input = tone(rate, hz, 16_000.0, 1.0)
                val out = ViveEagleResampler(16_000).process(input, input.size, rate, 1)
                val inputRms = 16_000.0 / sqrt(2.0)
                assertThat(rms(out) / inputRms).`as`("$hz Hz at $rate Hz").isCloseTo(1.0, within(0.05))
            }
        }
    }

    @Test
    fun `empty or partial-frame input yields no output`() {
        val r = ViveEagleResampler(16_000)
        assertThat(r.process(ByteArray(0), 0, 48_000, 1)).isEmpty()
        assertThat(r.process(ByteArray(3), 3, 48_000, 2)).isEmpty()
        assertThat(r.process(ByteArray(4), 4, 0, 1)).isEmpty()
    }

    @Test
    fun `low-pass taps are odd-length with unity DC gain`() {
        for (rate in listOf(44_100, 48_000)) {
            val taps = ViveEagleResampler.lowPassTaps(rate, cutoffHz = 7200.0, transitionHz = 2800.0)
            assertThat(taps.size % 2).isEqualTo(1)
            assertThat(taps.sum().toDouble()).isCloseTo(1.0, within(1e-4))
            // Symmetric (linear phase).
            for (i in taps.indices) assertThat(taps[i]).isCloseTo(taps[taps.size - 1 - i], within(1e-6f))
        }
    }
}
