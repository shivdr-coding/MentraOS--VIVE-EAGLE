package com.mentra.bluetoothsdk.sgcs

import androidx.test.core.app.ApplicationProvider
import com.mentra.bluetoothsdk.Bridge
import com.mentra.bluetoothsdk.DeviceStore
import com.mentra.bluetoothsdk.utils.ConnTypes
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [G1PairingTest.ShadowLc3Cpp::class], instrumentedPackages = ["com.mentra.lc3Lib"])
class ViveEagleConnectionTest {

    private class RecordingListener : EagleConnectionTracker.Listener {
        val calls = mutableListOf<String>()

        override fun onGlassesConnected() {
            calls += "connected"
        }

        override fun onGlassesLost() {
            calls += "lost"
        }

        override fun onConnectFailed(failure: EagleLinkEvent.Failed) {
            calls += "failed:${failure.code}"
        }
    }

    private val listener = RecordingListener()
    private val tracker = EagleConnectionTracker(listener)

    @Before
    fun setup() {
        Bridge.initialize(ApplicationProvider.getApplicationContext())
        DeviceStore.apply("glasses", "connected", false)
        DeviceStore.apply("glasses", "fullyBooted", false)
        DeviceStore.apply("glasses", "micEnabled", false)
        DeviceStore.apply("glasses", "connectionState", ConnTypes.DISCONNECTED)
    }

    private fun glasses(key: String) = DeviceStore.get("glasses", key)

    private fun glassesSnapshot() =
        listOf("connected", "fullyBooted", "micEnabled", "connectionState").associateWith { glasses(it) }

    @Test
    fun `connection states map to MentraOS events`() {
        assertThat(EagleConnectionMapping.map("CONNECTING")).isEqualTo(EagleLinkEvent.Connecting)
        assertThat(EagleConnectionMapping.map("CONNECTED")).isEqualTo(EagleLinkEvent.Connected)
        assertThat(EagleConnectionMapping.map("DISCONNECTED")).isEqualTo(EagleLinkEvent.Disconnected)
    }

    @Test
    fun `error states map to pair failures with their own code`() {
        for (code in listOf("ERROR_UNREGISTER_APP", "ERROR_UNSUPPORTED_ROM_VERSION", "ERROR")) {
            val event = EagleConnectionMapping.map(code)
            assertThat(event).isInstanceOf(EagleLinkEvent.Failed::class.java)
            assertThat((event as EagleLinkEvent.Failed).code).isEqualTo(code)
        }
        assertThat((EagleConnectionMapping.map(null) as EagleLinkEvent.Failed).code).isEqualTo("ERROR")
        // A state newer than SDK 0.6.1 is still a failure, reported under its own name.
        assertThat((EagleConnectionMapping.map("ERROR_FUTURE") as EagleLinkEvent.Failed).code)
            .isEqualTo("ERROR_FUTURE")
    }

    @Test
    fun `every HTC ConnectionState constant has an explicit mapping`() {
        // Only runs when HTC's SDK is on the unit test classpath (it is compileOnly in the
        // public SDK build, so usually not). The names below were read from the 0.6.1 bytecode.
        val htc = runCatching { Class.forName("com.htc.viveglass.sdk.ConnectionState") }.getOrNull()
        assumeTrue("HTC SDK not on the unit test classpath", htc != null)
        val names = htc!!.enumConstants!!.map { (it as Enum<*>).name }
        assertThat(names).containsExactlyInAnyOrder(
            "DISCONNECTED", "CONNECTING", "CONNECTED", "ERROR", "ERROR_UNREGISTER_APP", "ERROR_UNSUPPORTED_ROM_VERSION"
        )
    }

    @Test
    fun `beginSession marks the glasses connecting`() {
        tracker.beginSession()
        assertThat(tracker.sessionActive).isTrue()
        assertThat(glasses("connectionState")).isEqualTo(ConnTypes.CONNECTING)
    }

    @Test
    fun `connected and disconnected states update DeviceStore during a session`() {
        tracker.beginSession()

        assertThat(tracker.onConnectionState("CONNECTED")).isTrue()
        assertThat(glasses("connected")).isEqualTo(true)
        assertThat(glasses("fullyBooted")).isEqualTo(true)
        assertThat(glasses("connectionState")).isEqualTo(ConnTypes.CONNECTED)

        DeviceStore.apply("glasses", "micEnabled", true)
        assertThat(tracker.onConnectionState("CONNECTING")).isTrue()
        assertThat(glasses("connected")).isEqualTo(false)
        assertThat(glasses("fullyBooted")).isEqualTo(false)
        assertThat(glasses("connectionState")).isEqualTo(ConnTypes.CONNECTING)

        assertThat(tracker.onConnectionState("DISCONNECTED")).isTrue()
        assertThat(glasses("connected")).isEqualTo(false)
        assertThat(glasses("micEnabled")).isEqualTo(false)
        assertThat(glasses("connectionState")).isEqualTo(ConnTypes.DISCONNECTED)

        assertThat(listener.calls).containsExactly("connected", "lost")
    }

    @Test
    fun `error states report a pair failure and then disconnect`() {
        for (state in listOf("ERROR_UNREGISTER_APP", "ERROR_UNSUPPORTED_ROM_VERSION", "ERROR", null)) {
            listener.calls.clear()
            tracker.beginSession()
            tracker.onConnectionState("CONNECTED")
            assertThat(tracker.onConnectionState(state)).isTrue()
            assertThat(listener.calls).containsExactly("connected", "failed:${state ?: "ERROR"}", "lost")
            assertThat(glasses("connected")).isEqualTo(false)
            assertThat(glasses("fullyBooted")).isEqualTo(false)
            assertThat(glasses("connectionState")).isEqualTo(ConnTypes.DISCONNECTED)
        }
    }

    @Test
    fun `callbacks after the session ended leave DeviceStore alone`() {
        tracker.beginSession()
        tracker.onConnectionState("CONNECTED")
        tracker.endSession()
        tracker.publishDisconnected()
        listener.calls.clear()

        // A newer SGC owns the glasses state now.
        DeviceStore.apply("glasses", "connected", true)
        DeviceStore.apply("glasses", "fullyBooted", true)
        DeviceStore.apply("glasses", "micEnabled", true)
        DeviceStore.apply("glasses", "connectionState", ConnTypes.CONNECTED)
        val before = glassesSnapshot()

        for (state in listOf("CONNECTING", "CONNECTED", "DISCONNECTED", "ERROR", "ERROR_UNREGISTER_APP", null)) {
            assertThat(tracker.onConnectionState(state)).isFalse()
        }

        assertThat(glassesSnapshot()).isEqualTo(before)
        assertThat(listener.calls).isEmpty()
    }

    @Test
    fun `callbacks before any session are ignored`() {
        val before = glassesSnapshot()
        assertThat(tracker.onConnectionState("CONNECTED")).isFalse()
        assertThat(glassesSnapshot()).isEqualTo(before)
        assertThat(listener.calls).isEmpty()
    }
}
