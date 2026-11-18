package com.mentra.bluetoothsdk.sgcs

import com.mentra.bluetoothsdk.Bridge
import com.mentra.bluetoothsdk.PhotoRequest
import com.mentra.bluetoothsdk.utils.DeviceTypes

/**
 * Stand-in for the VIVE Eagle driver, compiled only when HTC's VIVE AI Glasses SDK is not in
 * libs/vive-eagle-sdk/ (see build.gradle and libs/vive-eagle-sdk/README.md). The real driver is
 * src/main/java/com/mentra/bluetoothsdk/sgcs/ViveEagle.kt. Every connection attempt fails with
 * VIVE_SDK_NOT_BUNDLED; everything else is a no-op.
 */
class ViveEagle : SGCManager() {

    private companion object {
        const val TAG = "EAGLE"
        const val REASON = "VIVE_SDK_NOT_BUNDLED"
    }

    init {
        type = DeviceTypes.EAGLE
    }

    private fun unavailable(what: String) {
        Bridge.log("$TAG: $what - this build has no VIVE AI Glasses SDK ($REASON)")
    }

    override fun findCompatibleDevices() = unavailable("findCompatibleDevices")

    override fun connectById(id: String) {
        unavailable("connectById($id)")
        Bridge.sendPairFailureEvent("errors:pairingCouldNotStart", REASON)
    }

    override fun setMicEnabled(enabled: Boolean) = unavailable("setMicEnabled")
    override fun sortMicRanking(list: MutableList<String>): MutableList<String> = list

    override fun requestPhoto(request: PhotoRequest) {
        Bridge.sendPhotoError(request.requestId, "GLASSES_NOT_CONNECTED", "VIVE Eagle support is not in this build")
    }

    override fun startStream(message: MutableMap<String, Any>) {}
    override fun stopStream() {}
    override fun sendStreamKeepAlive(message: MutableMap<String, Any>) {}
    override fun startVideoRecording(requestId: String, save: Boolean, sound: Boolean) {}
    override fun stopVideoRecording(requestId: String) {}
    override fun sendButtonPhotoSettings() {}
    override fun sendButtonVideoRecordingSettings() {}
    override fun sendButtonMaxRecordingTime() {}
    override fun sendCameraFovSetting() {}
    override fun setBrightness(level: Int, autoMode: Boolean) {}
    override fun clearDisplay() {}
    override fun sendText(text: String) {}
    override fun sendTextWall(text: String) {}
    override fun sendDoubleTextWall(top: String, bottom: String) {}

    override fun displayBitmap(
            base64ImageData: String,
            x: Int?,
            y: Int?,
            width: Int?,
            height: Int?
    ): Boolean = false

    override fun showDashboard() {}
    override fun setDashboardPosition(height: Int, depth: Int) {}
    override fun setHeadUpAngle(angle: Int) {}
    override fun getBatteryStatus() {}
    override fun setSilentMode(enabled: Boolean) {}
    override fun exit() {}
    override fun sendShutdown() {}
    override fun sendReboot() {}

    override fun sendRgbLedControl(
            requestId: String,
            packageName: String?,
            action: String,
            color: String?,
            onDurationMs: Int,
            offDurationMs: Int,
            count: Int
    ) {
        Bridge.sendRgbLedControlResponse(requestId, false, "device_not_supported")
    }

    override fun disconnect() {}
    override fun forget() {}
    override fun stopScan() {}
    override fun getConnectedBluetoothName(): String = ""
    override fun cleanup() {}
    override fun ping() {}
    override fun dbg1() {}
    override fun dbg2() {}
    override fun requestWifiScan(scanId: String?) {}
    override fun sendWifiCredentials(ssid: String, password: String) {}
    override fun forgetWifiNetwork(ssid: String) {}
    override fun sendHotspotState(enabled: Boolean) {}
    override fun sendUserEmailToGlasses(email: String) {}
    override fun sendIncidentId(incidentId: String, apiBaseUrl: String?) {}
    override fun queryGalleryStatus() {}
    override fun sendGalleryMode() {}
    override fun requestVersionInfo() {}
}
