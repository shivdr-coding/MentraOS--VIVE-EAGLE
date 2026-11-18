package com.mentra.bluetoothsdk.sgcs

import android.content.Context
import com.htc.viveglass.sdk.ViveGlassKit
import com.htc.viveglass.sdk.client.ViveGlassClient

/**
 * RELEASE builds: the VIVE Eagle driver talks to the real glasses (paired in HTC's VIVE Connect
 * app) through ViveGlassKit. Until HTC registers the app, an unregistered package gets
 * ERROR_UNREGISTER_APP from the connect handshake (confirmed on the real glasses 2026-11-11, see
 * vive-registration-test/run2-logcat.txt and CLAUDE.md); ViveEagle reports that as a pairing
 * failure.
 *
 * build.gradle adds this source set (src/viveEagleKit/) to the release variant only; debug
 * builds compile src/viveEagleSimulator/ instead. Both files define the same object; keep their
 * signatures in step.
 */
internal object ViveEagleAdapter {
    const val IS_SIMULATOR = false

    /** Main thread only. */
    fun create(context: Context): ViveGlassClient = ViveGlassKit(context)

    /** No simulator in release builds. */
    fun pressSimulatedAiButton(): Boolean = false
}
