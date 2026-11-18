package com.mentra.bluetoothsdk.sgcs

import android.content.Context
import com.htc.viveglass.sdk.client.ViveGlassClient
import com.htc.viveglass.sdk.simulator.ViveGlassSimulator

/**
 * DEBUG builds: the VIVE Eagle driver runs against HTC's bundled ViveGlassSimulator. Same
 * adapter contract as the real glasses, no hardware and no HTC app registration needed.
 *
 * build.gradle adds this source set (src/viveEagleSimulator/) to the debug variant only, and
 * the simulator AAR (with its ~10 MB of sample media, Material and ConstraintLayout) is a
 * debug-only dependency. Release builds compile src/viveEagleKit/ instead, which uses the real
 * ViveGlassKit adapter. Both files define the same object; keep their signatures in step.
 */
internal object ViveEagleAdapter {
    const val IS_SIMULATOR = true

    // ViveGlassSimulator.create() builds a TextToSpeech and SpeechRecognizer; do it once per
    // process, on the main thread.
    @Volatile private var simulatorCreated = false

    /** Main thread only. */
    fun create(context: Context): ViveGlassClient {
        if (!simulatorCreated) {
            ViveGlassSimulator.create(context)
            simulatorCreated = true
        }
        // The simulator refuses connect() ("Glasses is not power on or put on") unless it is
        // powered and worn. Both start true (mPower/mWear in its constructor); only its own UI
        // (showUI, HTC's sample) changes them. setPower/setWear are package-private in SDK
        // 0.6.1, so they cannot be called from here anyway.
        return ViveGlassSimulator.instance()
    }

    /**
     * Presses the simulated AI button. sendAIButton() is package-private in SDK 0.6.1 (HTC's
     * simulator dialog calls it), so it is reached by reflection. Main thread only.
     * Returns false (and the caller logs) if a later SDK renames it.
     */
    fun pressSimulatedAiButton(): Boolean =
        try {
            ViveGlassSimulator::class.java
                .getDeclaredMethod("sendAIButton")
                .apply { isAccessible = true }
                .invoke(ViveGlassSimulator.instance())
            true
        } catch (e: ReflectiveOperationException) {
            false
        }
}
