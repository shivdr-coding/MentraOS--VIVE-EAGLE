package com.mentra.bluetoothsdk.sgcs

import com.mentra.bluetoothsdk.DeviceStore
import com.mentra.bluetoothsdk.utils.ConnTypes

// Connection and photo bookkeeping for the VIVE Eagle driver (ViveEagle.kt). Kept apart from it
// and free of HTC SDK types so it compiles without the SDK and can be unit-tested on the JVM.

/** What one of HTC's ConnectionState values means for MentraOS. */
internal sealed class EagleLinkEvent {
    object Connecting : EagleLinkEvent()
    object Connected : EagleLinkEvent()
    object Disconnected : EagleLinkEvent()

    /** Reported to MentraOS as a pair failure with [code], then handled as a disconnect. */
    data class Failed(val code: String, val logMessage: String) : EagleLinkEvent()
}

internal object EagleConnectionMapping {
    /**
     * HTC's ConnectionState and MentraOS's ConnTypes are separate enums; map explicitly.
     * Keyed by the HTC enum constant's name (`state?.name`), so no HTC class is needed here.
     */
    fun map(stateName: String?): EagleLinkEvent =
        when (stateName) {
            "CONNECTING" -> EagleLinkEvent.Connecting
            "CONNECTED" -> EagleLinkEvent.Connected
            "DISCONNECTED" -> EagleLinkEvent.Disconnected
            // Only the real ViveGlassKit path; the simulator never reports it.
            "ERROR_UNREGISTER_APP" ->
                EagleLinkEvent.Failed(stateName, "HTC rejected this app: not registered with VIVE Connect")
            "ERROR_UNSUPPORTED_ROM_VERSION" ->
                EagleLinkEvent.Failed(stateName, "glasses firmware (ROM) not supported by this SDK version")
            // ConnectionState.ERROR, null, or a value newer than SDK 0.6.1.
            else -> EagleLinkEvent.Failed(stateName ?: "ERROR", "connection error state=$stateName")
        }
}

/**
 * Owns the glasses connection fields in DeviceStore for the VIVE Eagle, and the session guard:
 * [sessionActive] is true between [beginSession] (connectById) and [endSession] (disconnect).
 * HTC callbacks that arrive outside a session are ignored, so a late callback after a disconnect
 * cannot overwrite DeviceStore state that a newer SGC may already own.
 */
internal class EagleConnectionTracker(private val listener: Listener) {

    interface Listener {
        /** After DeviceStore says connected. */
        fun onGlassesConnected()

        /** Before DeviceStore says disconnected (stop the mic, fail a pending photo). */
        fun onGlassesLost()

        /** A failure state, before the disconnect that follows it. */
        fun onConnectFailed(failure: EagleLinkEvent.Failed)
    }

    @Volatile
    var sessionActive = false
        private set

    fun beginSession() {
        sessionActive = true
        DeviceStore.apply("glasses", "connectionState", ConnTypes.CONNECTING)
    }

    fun endSession() {
        sessionActive = false
    }

    /** Applies an HTC connection state. Returns false when it was ignored (no session). */
    fun onConnectionState(stateName: String?): Boolean {
        if (!sessionActive) return false
        when (val event = EagleConnectionMapping.map(stateName)) {
            EagleLinkEvent.Connecting -> {
                DeviceStore.apply("glasses", "connected", false)
                DeviceStore.apply("glasses", "fullyBooted", false)
                DeviceStore.apply("glasses", "connectionState", ConnTypes.CONNECTING)
            }
            EagleLinkEvent.Connected -> markConnected()
            EagleLinkEvent.Disconnected -> markDisconnected()
            is EagleLinkEvent.Failed -> {
                listener.onConnectFailed(event)
                markDisconnected()
            }
        }
        return true
    }

    fun markConnected() {
        DeviceStore.apply("glasses", "connected", true)
        DeviceStore.apply("glasses", "fullyBooted", true)
        DeviceStore.apply("glasses", "connectionState", ConnTypes.CONNECTED)
        listener.onGlassesConnected()
    }

    fun markDisconnected() {
        listener.onGlassesLost()
        publishDisconnected()
    }

    /** DeviceStore writes only, without [Listener.onGlassesLost]. */
    fun publishDisconnected() {
        DeviceStore.apply("glasses", "micEnabled", false)
        DeviceStore.apply("glasses", "connected", false)
        DeviceStore.apply("glasses", "fullyBooted", false)
        DeviceStore.apply("glasses", "connectionState", ConnTypes.DISCONNECTED)
    }
}

/**
 * The VIVE Eagle's one in-flight photo request and its capture timeout. Thread-safe: requests
 * come from MentraOS, results from HTC's callback thread, the timeout from [scheduler].
 */
internal class EaglePhotoSlot<T : Any>(private val scheduler: Scheduler, private val timeoutMs: Long) {

    interface Scheduler {
        fun schedule(delayMs: Long, task: Runnable)

        fun cancel(task: Runnable)
    }

    private val lock = Any()
    private var pending: T? = null
    private var timeout: Runnable? = null

    val isBusy: Boolean
        get() = synchronized(lock) { pending != null }

    /**
     * Makes [request] the pending one and starts its timeout. Returns false, changing nothing,
     * when another request is still pending. If the timeout fires first, the request is taken
     * out of the slot and passed to [onTimeout].
     */
    fun begin(request: T, onTimeout: (T) -> Unit): Boolean {
        synchronized(lock) {
            if (pending != null) return false
            pending = request
            val task = object : Runnable {
                override fun run() {
                    val timedOut =
                        synchronized(lock) {
                            // A stale timer (its request already finished) must not take a newer one.
                            if (timeout !== this) return
                            takeLocked()
                        } ?: return
                    onTimeout(timedOut)
                }
            }
            timeout = task
            scheduler.schedule(timeoutMs, task)
            return true
        }
    }

    /** Removes and returns the pending request (null if none) and cancels its timeout. */
    fun take(): T? = synchronized(lock) { takeLocked() }

    private fun takeLocked(): T? {
        val request = pending
        pending = null
        timeout?.let { scheduler.cancel(it) }
        timeout = null
        return request
    }
}
