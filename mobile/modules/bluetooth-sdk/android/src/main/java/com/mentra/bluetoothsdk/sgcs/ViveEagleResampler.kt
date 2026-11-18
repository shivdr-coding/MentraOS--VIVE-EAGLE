package com.mentra.bluetoothsdk.sgcs

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns the VIVE Eagle's decoded mic PCM (s16le, any rate, 1+ channels) into mono s16le at
 * [targetRate], the format DeviceManager.handlePcm expects. Pure Kotlin, no Android types, so it
 * is unit-testable on the JVM.
 *
 * Downsampling (the simulator sends 44.1 kHz, the glasses may send 48 kHz) first runs a
 * Blackman-windowed-sinc low-pass FIR, then interpolates linearly to the target rate. Without the
 * low-pass, content between 8 kHz and the input Nyquist frequency would fold back into the speech
 * band. Passband to about 5.8 kHz, stopband (about -74 dB) from about 8.6 kHz; roughly 90-100
 * taps at 44.1/48 kHz, a few million multiply-adds per second.
 *
 * State (filter history, interpolation phase) carries across buffers, so a stream split into
 * arbitrary chunks gives the same output as one long buffer. Scratch arrays are reused and only
 * grow; the only per-call allocation is the returned array. Not thread-safe: one stream, one
 * thread (the decoder thread).
 */
internal class ViveEagleResampler(private val targetRate: Int = 16_000) {

    companion object {
        private val EMPTY = ByteArray(0)

        /**
         * Odd-length, unity-DC-gain low-pass FIR (Blackman-windowed sinc) for [sampleRate],
         * centred on [cutoffHz] with a transition band about [transitionHz] wide.
         */
        fun lowPassTaps(sampleRate: Int, cutoffHz: Double, transitionHz: Double): FloatArray {
            var n = ceil(5.5 * sampleRate / transitionHz).toInt()
            if (n % 2 == 0) n++
            val m = (n - 1).toDouble()
            val fc = cutoffHz / sampleRate
            val h = DoubleArray(n)
            var sum = 0.0
            for (i in 0 until n) {
                val x = i - m / 2
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val window = 0.42 - 0.5 * cos(2 * PI * i / m) + 0.08 * cos(4 * PI * i / m)
                h[i] = sinc * window
                sum += h[i]
            }
            return FloatArray(n) { (h[it] / sum).toFloat() }
        }
    }

    private var inRate = 0
    private var taps = FloatArray(1) { 1f }
    /** The last taps.size - 1 mono input samples, oldest first. */
    private var history = FloatArray(0)
    /** history + this buffer's mono samples. */
    private var work = FloatArray(0)
    /** [0] = previous buffer's last filtered sample, [1..frames] = this buffer's. */
    private var filtered = FloatArray(1)
    private var outScratch = FloatArray(0)
    /** Next output position, in input samples, relative to filtered[0]. */
    private var pos = 0.0
    private var primed = false

    fun reset() {
        history.fill(0f)
        pos = 0.0
        primed = false
    }

    /** Converts [length] bytes of [pcm] (interleaved s16le, [channels] channels, [rate] Hz). */
    fun process(pcm: ByteArray, length: Int, rate: Int, channels: Int): ByteArray {
        val ch = if (channels > 0) channels else 1
        val frames = length / (2 * ch)
        if (frames == 0 || rate <= 0) return EMPTY
        if (rate != inRate) configure(rate)

        if (rate == targetRate) {
            val out = ByteArray(frames * 2)
            for (f in 0 until frames) writeSample(out, f, mono(pcm, f, ch))
            return out
        }

        // Low-pass: filtered[f + 1] = sum_k taps[k] * work[f + k].
        val hist = history.size
        work = ensure(work, hist + frames)
        System.arraycopy(history, 0, work, 0, hist)
        for (f in 0 until frames) work[hist + f] = mono(pcm, f, ch)
        filtered = ensure(filtered, frames + 1)
        val n = taps.size
        for (f in 0 until frames) {
            var acc = 0f
            for (k in 0 until n) acc += taps[k] * work[f + k]
            filtered[f + 1] = acc
        }
        System.arraycopy(work, frames, history, 0, hist)
        if (!primed) {
            filtered[0] = filtered[1]
            primed = true
        }

        // Linear interpolation across the seam (filtered[0] is the previous buffer's tail).
        val step = rate.toDouble() / targetRate
        val last = frames
        outScratch = ensure(outScratch, ((last - pos) / step).toInt() + 2)
        var p = pos
        var count = 0
        while (p < last) {
            val i = p.toInt()
            val a = filtered[i]
            outScratch[count++] = a + (filtered[i + 1] - a) * (p - i).toFloat()
            p += step
        }
        pos = p - last
        filtered[0] = filtered[last]

        val out = ByteArray(count * 2)
        for (k in 0 until count) writeSample(out, k, outScratch[k])
        return out
    }

    private fun configure(rate: Int) {
        inRate = rate
        taps =
            if (rate > targetRate) {
                lowPassTaps(rate, cutoffHz = 0.45 * targetRate, transitionHz = 0.175 * targetRate)
            } else {
                FloatArray(1) { 1f } // upsampling or equal: nothing to alias, no filter
            }
        history = FloatArray(taps.size - 1)
        pos = 0.0
        primed = false
    }

    private fun mono(pcm: ByteArray, frame: Int, channels: Int): Float {
        var sum = 0
        var p = frame * 2 * channels
        for (c in 0 until channels) {
            sum += (pcm[p + 1].toInt() shl 8) or (pcm[p].toInt() and 0xFF)
            p += 2
        }
        return sum.toFloat() / channels
    }

    private fun writeSample(out: ByteArray, index: Int, value: Float) {
        val s = value.roundToInt().coerceIn(-32768, 32767)
        out[2 * index] = (s and 0xFF).toByte()
        out[2 * index + 1] = ((s shr 8) and 0xFF).toByte()
    }

    /** Grows [array] to at least [size], keeping its contents (filtered[0] is carried state). */
    private fun ensure(array: FloatArray, size: Int): FloatArray =
        if (array.size >= size) array else array.copyOf(maxOf(size, array.size * 2))
}
