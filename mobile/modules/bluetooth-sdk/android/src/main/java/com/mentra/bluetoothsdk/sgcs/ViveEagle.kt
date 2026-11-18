package com.mentra.bluetoothsdk.sgcs

// Compiled against HTC's VIVE AI Glasses SDK 0.6.1 (viveglass_client + ViveGlassSimulator),
// which is not committed: put it in libs/vive-eagle-sdk/ as that folder's README.md says.
// Without it, build.gradle warns and compiles src/viveEagleStub/ in place of this file.
//
// Every HTC signature used below was checked against the 0.6.1 AAR bytecode and HTC's own sample
// (ViveGlassSample/ViveGlassKitManager.kt), not just the docs.

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import com.htc.viveglass.sdk.AudioChannel
import com.htc.viveglass.sdk.AudioStreamingFormat
import com.htc.viveglass.sdk.CaptureEvent
import com.htc.viveglass.sdk.ConnectionState
import com.htc.viveglass.sdk.ImagePayload
import com.htc.viveglass.sdk.ImageQuality
import com.htc.viveglass.sdk.KeyEvent
import com.htc.viveglass.sdk.Microphone
import com.htc.viveglass.sdk.Permission
import com.htc.viveglass.sdk.PermissionResult
import com.htc.viveglass.sdk.StreamingEvent
import com.htc.viveglass.sdk.SynthesisEvent
import com.htc.viveglass.sdk.TranscribedEvent
import com.htc.viveglass.sdk.ViveGlass
import com.htc.viveglass.sdk.ViveGlassError
import com.htc.viveglass.sdk.ViveGlassKit
import com.htc.viveglass.sdk.client.StreamingBufferCallback
import com.htc.viveglass.sdk.client.StreamingEventCallback
import com.htc.viveglass.sdk.client.ViveGlassClientCallback
import com.htc.viveglass.sdk.simulator.ViveGlassSimulator
import com.mentra.bluetoothsdk.BluetoothSdkDefaults
import com.mentra.bluetoothsdk.Bridge
import com.mentra.bluetoothsdk.DeviceManager
import com.mentra.bluetoothsdk.DeviceStore
import com.mentra.bluetoothsdk.PhotoRequest
import com.mentra.bluetoothsdk.utils.BlePhotoUploadService
import com.mentra.bluetoothsdk.utils.ConnTypes
import com.mentra.bluetoothsdk.utils.DeviceTypes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/**
 * HTC VIVE Eagle AI glasses.
 *
 * The Eagle is driven entirely through HTC's phone-side "VIVE AI Glasses SDK": the SDK owns the
 * BLE link, pairing and transport, and this class only maps its callbacks onto MentraOS events.
 * No display, no IMU, no LED, no Wi-Fi exposed. Camera, microphone, speaker and one AI button.
 */
class ViveEagle : SGCManager() {

    companion object {
        private const val TAG = "EAGLE"

        // ---------------------------------------------------------------------------------
        // BUILD MODE
        //
        // true  = ViveGlass.adapter = ViveGlassSimulator.instance()
        //         HTC's bundled simulator. Same adapter contract, no hardware and no HTC app
        //         registration needed, so this path works today. Default for now.
        // false = ViveGlass.adapter = ViveGlassKit(context)
        //         The real glasses (paired in HTC's VIVE Connect app). Blocked until HTC
        //         registers the app: an unregistered package gets ERROR_UNREGISTER_APP from the
        //         connect handshake (confirmed on the real glasses 2026-11-11, see
        //         vive-registration-test/run2-logcat.txt and CLAUDE.md).
        // ---------------------------------------------------------------------------------
        private const val USE_SIMULATOR = true

        // Mic routing. false (default) = stream raw glasses audio and feed it into MentraOS's own
        // PCM pipeline (VAD, wake word, on-device STT, mic_pcm). true = use HTC's own speech
        // recognizer instead and forward its text as local_transcription; no PCM is produced
        // then. The two are not run together: HTC reports ERROR_RESOURCE_CONFLICT for
        // overlapping mic users.
        private const val USE_GLASSES_TRANSCRIPTION = false

        /** The one entry offered in search results. The SDK connects to whichever Eagle is
         *  paired in VIVE Connect, so there is no scan and no per-device id. */
        private const val DEVICE_NAME = "VIVE Eagle"

        /** Same cache folder Mentra Live uses for `PhotoRequest.localFile`. */
        private const val LOCAL_PHOTO_DIR = "mentra_local_photos"
        /** Raw HEIC copies kept when `PhotoRequest.save` is set. */
        private const val FILE_SAVE_DIR = "ViveEagle_Images"

        /** No onImageCaptured within this window fails the request. */
        private const val CAPTURE_TIMEOUT_MS = 15_000L

        /** MentraOS PCM pipeline format (Bridge.MIC_SAMPLE_RATE, mono, s16le). */
        private const val TARGET_SAMPLE_RATE = 16_000

        // ViveGlassSimulator.create() builds a TextToSpeech and SpeechRecognizer; do it once
        // per process, on the main thread.
        @Volatile private var simulatorCreated = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val viveGlass = ViveGlass()
    private var adapterReady = false

    /** True between connectById() and disconnect()/cleanup(). Late SDK callbacks after a
     *  disconnect must not overwrite DeviceStore state that a newer SGC may already own. */
    @Volatile private var sessionActive = false

    @Volatile private var preferredLocale: Locale = Locale.getDefault()

    // Camera
    private val photoLock = Any()
    private var pendingPhoto: PhotoRequest? = null
    private var captureTimeout: Runnable? = null

    // Microphone
    /** What MentraOS asked for; re-applied when the glasses (re)connect. */
    @Volatile private var micWanted = false
    @Volatile private var audioStreaming = false
    @Volatile private var transcribing = false
    @Volatile private var micDecoder: EagleAacDecoder? = null

    init {
        type = DeviceTypes.EAGLE
        hasMic = true
        DeviceStore.apply("glasses", "fullyBooted", false)
        DeviceStore.apply("glasses", "connected", false)
        DeviceStore.apply("glasses", "connectionState", ConnTypes.DISCONNECTED)
        DeviceStore.apply("glasses", "micEnabled", false)
        DeviceStore.apply(
            "glasses",
            "voiceActivityDetectionEnabled",
            BluetoothSdkDefaults.VOICE_ACTIVITY_DETECTION_ENABLED
        )
    }

    // =====================================================================================
    // HTC SDK callbacks
    //
    // ViveGlassClientCallback is a Java interface, so every parameter is a nullable platform
    // type; the overrides match HTC's sample. onPermissionResult is part of the interface too.
    // =====================================================================================

    private val clientCallback =
        object : ViveGlassClientCallback {
            override fun onConnectionStateChanged(state: ConnectionState?) {
                handleConnectionState(state)
            }

            override fun onImageCaptured(event: CaptureEvent?, payload: ImagePayload?) {
                handleImageCaptured(event, payload)
            }

            override fun onSpeechTranscribed(event: TranscribedEvent?, text: String?) {
                handleSpeechTranscribed(event, text)
            }

            override fun onTextSpoken(event: SynthesisEvent?) {
                Bridge.log("$TAG: onTextSpoken event=$event")
            }

            override fun onKeyEvent(event: KeyEvent?) {
                handleKeyEvent(event)
            }

            override fun onPermissionResult(permission: Permission?, result: PermissionResult?) {
                Bridge.log("$TAG: onPermissionResult permission=$permission result=$result")
            }
        }

    private val micBufferCallback =
        object : StreamingBufferCallback {
            override fun onReceiveBuffer(byteBuffer: ByteBuffer?, bufferInfo: MediaCodec.BufferInfo?) {
                if (byteBuffer == null || bufferInfo == null || bufferInfo.size <= 0) return
                if (!audioStreaming) return
                // Copy now: the SDK may reuse the buffer after this call returns.
                val payload = ByteArray(bufferInfo.size)
                byteBuffer.duplicate().apply {
                    position(bufferInfo.offset)
                    limit(bufferInfo.offset + bufferInfo.size)
                    get(payload)
                }
                micDecoder?.enqueue(payload, bufferInfo.presentationTimeUs, bufferInfo.flags)
            }
        }

    private val micEventCallback =
        object : StreamingEventCallback {
            override fun onStreamingEvent(event: StreamingEvent?) {
                Bridge.log("$TAG: audio streaming event=$event")
                when (event) {
                    StreamingEvent.STARTED -> {
                        audioStreaming = true
                    }
                    StreamingEvent.STOPPED -> {
                        audioStreaming = false
                        releaseMicDecoder()
                    }
                    else -> {
                        // ERROR, ERROR_RESOURCE_CONFLICT, GLASS_OVERHEATING, BATTERY_LOW, null:
                        // the stream is not running.
                        audioStreaming = false
                        releaseMicDecoder()
                        DeviceStore.apply("glasses", "micEnabled", false)
                    }
                }
            }
        }

    // =====================================================================================
    // Connection
    // =====================================================================================

    /** HTC's ConnectionState and MentraOS's ConnTypes are separate enums; map explicitly. */
    private fun handleConnectionState(state: ConnectionState?) {
        Bridge.log("$TAG: onConnectionStateChanged state=$state")
        if (!sessionActive) {
            Bridge.log("$TAG: ignoring connection state after disconnect")
            return
        }
        when (state) {
            ConnectionState.CONNECTING -> {
                DeviceStore.apply("glasses", "connected", false)
                DeviceStore.apply("glasses", "fullyBooted", false)
                DeviceStore.apply("glasses", "connectionState", ConnTypes.CONNECTING)
            }
            ConnectionState.CONNECTED -> onConnected()
            ConnectionState.DISCONNECTED -> onDisconnected()
            ConnectionState.ERROR_UNREGISTER_APP -> {
                // Only the real ViveGlassKit path; the simulator never reports it.
                Bridge.log("$TAG: HTC rejected this app: not registered with VIVE Connect")
                Bridge.sendPairFailureEvent("errors:pairingCouldNotStart", "ERROR_UNREGISTER_APP")
                onDisconnected()
            }
            ConnectionState.ERROR_UNSUPPORTED_ROM_VERSION -> {
                Bridge.log("$TAG: glasses firmware (ROM) not supported by this SDK version")
                Bridge.sendPairFailureEvent("errors:pairingCouldNotStart", "ERROR_UNSUPPORTED_ROM_VERSION")
                onDisconnected()
            }
            else -> {
                // ConnectionState.ERROR, null, or a value newer than SDK 0.6.1.
                Bridge.log("$TAG: connection error state=$state")
                Bridge.sendPairFailureEvent("errors:pairingCouldNotStart", state?.name ?: "ERROR")
                onDisconnected()
            }
        }
    }

    private fun onConnected() {
        DeviceStore.apply("glasses", "connected", true)
        DeviceStore.apply("glasses", "fullyBooted", true)
        DeviceStore.apply("glasses", "connectionState", ConnTypes.CONNECTED)

        // HTC's sample reads the locale off the main thread; with ViveGlassKit it goes over
        // the link and may block.
        scope.launch {
            try {
                viveGlass.userPreferredLocale?.let { preferredLocale = it }
                Bridge.log("$TAG: preferred locale $preferredLocale")
            } catch (e: Exception) {
                Bridge.log("$TAG: getUserPreferredLocale failed: ${e.message}")
            }
        }

        if (micWanted) startMic()
    }

    private fun onDisconnected() {
        stopMicLocal()
        failPendingPhoto("GLASSES_DISCONNECTED", "VIVE Eagle disconnected before the photo arrived")
        DeviceStore.apply("glasses", "micEnabled", false)
        DeviceStore.apply("glasses", "connected", false)
        DeviceStore.apply("glasses", "fullyBooted", false)
        DeviceStore.apply("glasses", "connectionState", ConnTypes.DISCONNECTED)
    }

    /** Assigns ViveGlass.adapter. Main thread only (the simulator creates TTS/STT objects). */
    private fun ensureAdapter(context: Context): Boolean {
        if (adapterReady) return true
        return try {
            if (USE_SIMULATOR) {
                if (!simulatorCreated) {
                    ViveGlassSimulator.create(context)
                    simulatorCreated = true
                }
                // The simulator refuses connect() ("Glasses is not power on or put on") unless
                // it is powered and worn. Both start true (mPower/mWear in its constructor);
                // only its own UI (showUI, HTC's sample) changes them. setPower/setWear are
                // package-private in SDK 0.6.1, so they cannot be called from here anyway.
                ViveGlass.adapter = ViveGlassSimulator.instance()
                Bridge.log("$TAG: adapter = ViveGlassSimulator")
            } else {
                ViveGlass.adapter = ViveGlassKit(context)
                Bridge.log("$TAG: adapter = ViveGlassKit (real glasses)")
            }
            adapterReady = true
            true
        } catch (e: Exception) {
            Bridge.log("$TAG: failed to set up the HTC adapter: ${e.message}")
            false
        }
    }

    override fun findCompatibleDevices() {
        Bridge.log("$TAG: findCompatibleDevices")
        // Nothing to scan: HTC's SDK talks to the glasses paired in VIVE Connect.
        Bridge.sendDiscoveredDevice(DeviceTypes.EAGLE, DEVICE_NAME)
    }

    override fun stopScan() {
        Bridge.log("$TAG: stopScan")
    }

    override fun connectById(id: String) {
        Bridge.log("$TAG: connectById($id)")
        sessionActive = true
        DeviceStore.apply("glasses", "connectionState", ConnTypes.CONNECTING)
        mainHandler.post {
            if (!sessionActive) return@post
            val context =
                try {
                    Bridge.getContext().applicationContext
                } catch (e: IllegalStateException) {
                    Bridge.log("$TAG: no app context, cannot connect")
                    onDisconnected()
                    return@post
                }
            if (!ensureAdapter(context)) {
                Bridge.sendPairFailureEvent("errors:pairingCouldNotStart", "ADAPTER_SETUP_FAILED")
                onDisconnected()
                return@post
            }
            if (viveGlass.isConnected) {
                Bridge.log("$TAG: already connected")
                onConnected()
                return@post
            }
            // Documented as callback-based: the result arrives in onConnectionStateChanged.
            // ViveGlassKit reports handshake failures there (ERROR_UNREGISTER_APP, ...), but the
            // exceptions below are declared by the SDK, so guard against them being thrown too.
            try {
                viveGlass.connect(clientCallback)
            } catch (e: ViveGlassError.UnRegisteredAppException) {
                Bridge.log("$TAG: connect: app not registered with HTC")
                Bridge.sendPairFailureEvent("errors:pairingCouldNotStart", "ERROR_UNREGISTER_APP")
                onDisconnected()
            } catch (e: ViveGlassError.NoPairedGlassesException) {
                Bridge.log("$TAG: connect: no glasses paired in VIVE Connect")
                Bridge.sendPairFailureEvent("errors:pairingCouldNotStart", "NO_PAIRED_GLASSES")
                onDisconnected()
            } catch (e: ViveGlassError.GlassesPermissionDeniedException) {
                Bridge.log("$TAG: connect: permission denied")
                Bridge.sendPairFailureEvent("errors:pairingCouldNotStart", "PERMISSION_DENIED")
                onDisconnected()
            } catch (e: ViveGlassError.GlassesNotWornException) {
                Bridge.log("$TAG: connect: glasses not worn")
                Bridge.sendPairFailureEvent("errors:pairingCouldNotStart", "GLASSES_NOT_WORN")
                onDisconnected()
            } catch (e: Exception) {
                Bridge.log("$TAG: connect failed: ${e.message}")
                Bridge.sendPairFailureEvent("errors:pairingCouldNotStart", "ERROR")
                onDisconnected()
            }
        }
    }

    override fun disconnect() {
        Bridge.log("$TAG: disconnect")
        stopMic()
        failPendingPhoto("GLASSES_DISCONNECTED", "Disconnected from VIVE Eagle")
        sessionActive = false
        scope.coroutineContext.cancelChildren()
        if (adapterReady) {
            try {
                viveGlass.disconnect()
            } catch (e: Exception) {
                Bridge.log("$TAG: disconnect failed: ${e.message}")
            }
        }
        DeviceStore.apply("glasses", "micEnabled", false)
        DeviceStore.apply("glasses", "connected", false)
        DeviceStore.apply("glasses", "fullyBooted", false)
        DeviceStore.apply("glasses", "connectionState", ConnTypes.DISCONNECTED)
    }

    override fun forget() {
        Bridge.log("$TAG: forget")
        // Pairing lives in VIVE Connect; there is nothing of ours to unpair.
        disconnect()
    }

    override fun cleanup() {
        Bridge.log("$TAG: cleanup")
        disconnect()
        micWanted = false
        releaseMicDecoder()
        scope.cancel()
    }

    override fun getConnectedBluetoothName(): String {
        return if (connected) DEVICE_NAME else ""
    }

    override fun ping() {
        Bridge.log("$TAG: ping (isConnected=${adapterReady && viveGlass.isConnected})")
    }

    /**
     * Simulator only: presses the simulated AI button, so the button path can be tested.
     * sendAIButton() is package-private in SDK 0.6.1 (HTC's simulator dialog calls it), so it
     * is reached by reflection; debug-only, and it just logs if a later SDK renames it.
     */
    override fun dbg1() {
        if (USE_SIMULATOR && adapterReady) {
            Bridge.log("$TAG: dbg1 -> simulated AI button")
            mainHandler.post {
                try {
                    ViveGlassSimulator::class.java
                        .getDeclaredMethod("sendAIButton")
                        .apply { isAccessible = true }
                        .invoke(ViveGlassSimulator.instance())
                } catch (e: ReflectiveOperationException) {
                    Bridge.log("$TAG: simulated AI button unavailable: $e")
                }
            }
        }
    }

    override fun dbg2() {}

    // =====================================================================================
    // Permissions
    //
    // HTC gates camera and microphone behind its own permission (checkPermission /
    // requestPermission). requestPermission needs an Activity, which an SGC normally does not
    // have; it is only attempted when the bridge context happens to be one. Otherwise the
    // request fails and the permission has to be granted first (in VIVE Connect for the real
    // glasses).
    // =====================================================================================

    private fun hasPermission(permission: Permission): Boolean {
        val result =
            try {
                viveGlass.checkPermission(permission)
            } catch (e: Exception) {
                Bridge.log("$TAG: checkPermission($permission) failed: ${e.message}")
                null
            }
        if (result == PermissionResult.GRANT) return true
        Bridge.log("$TAG: $permission not granted ($result)")
        val activity =
            try {
                Bridge.getContext() as? Activity
            } catch (_: IllegalStateException) {
                null
            }
        if (activity != null) {
            mainHandler.post {
                try {
                    viveGlass.requestPermission(activity, permission)
                } catch (e: Exception) {
                    Bridge.log("$TAG: requestPermission($permission) failed: ${e.message}")
                }
            }
        }
        return false
    }

    // =====================================================================================
    // Camera
    // =====================================================================================

    override fun requestPhoto(request: PhotoRequest) {
        Bridge.log(
            "$TAG: requestPhoto id=${request.requestId} localFile=${request.localFile} " +
                "save=${request.save} sound=${request.sound}"
        )
        // Not mapped: size, compress, the capture-tuning fields and transferMethod. HTC's
        // captureImage takes only ImageQuality (DEFAULT is its one value) and has no shutter
        // sound control, so `sound` is ignored too.
        if (!adapterReady || !viveGlass.isConnected) {
            Bridge.sendPhotoError(request.requestId, "GLASSES_NOT_CONNECTED", "VIVE Eagle is not connected")
            return
        }
        synchronized(photoLock) {
            if (pendingPhoto != null) {
                Bridge.sendPhotoError(request.requestId, "CAMERA_BUSY", "A VIVE Eagle photo is already in progress")
                return
            }
            pendingPhoto = request
            val timeout = Runnable {
                failPendingPhoto("CAPTURE_TIMEOUT", "VIVE Eagle did not return the photo in time")
            }
            captureTimeout = timeout
            mainHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
        }
        if (!hasPermission(Permission.CAMERA)) {
            failPendingPhoto("PERMISSION_DENIED", "VIVE Eagle camera permission not granted")
            return
        }
        // Returns 0 when the capture was accepted and -1 on error (simulator constants); the
        // image itself arrives in onImageCaptured.
        val result =
            try {
                viveGlass.captureImage(ImageQuality.DEFAULT)
            } catch (e: Exception) {
                Bridge.log("$TAG: captureImage threw: ${e.message}")
                -1
            }
        if (result < 0) {
            failPendingPhoto("CAPTURE_FAILED", "VIVE Eagle captureImage returned $result")
        }
    }

    private fun takePendingPhoto(): PhotoRequest? {
        synchronized(photoLock) {
            val request = pendingPhoto
            pendingPhoto = null
            captureTimeout?.let { mainHandler.removeCallbacks(it) }
            captureTimeout = null
            return request
        }
    }

    private fun failPendingPhoto(errorCode: String, message: String) {
        val request = takePendingPhoto() ?: return
        Bridge.log("$TAG: photo ${request.requestId} failed: $errorCode $message")
        Bridge.sendPhotoError(request.requestId, errorCode, message)
    }

    private fun handleImageCaptured(event: CaptureEvent?, payload: ImagePayload?) {
        Bridge.log("$TAG: onImageCaptured event=$event bytes=${payload?.byteArray?.size}")
        when (event) {
            CaptureEvent.SUCCESS -> {
                val bytes = payload?.byteArray
                if (bytes == null || bytes.isEmpty()) {
                    failPendingPhoto("CAPTURE_FAILED", "VIVE Eagle returned an empty image")
                    return
                }
                val request = takePendingPhoto()
                if (request == null) {
                    // Not requested by MentraOS (or already timed out).
                    Bridge.log("$TAG: dropping unrequested photo (${bytes.size} bytes)")
                    return
                }
                deliverPhoto(request, bytes)
            }
            CaptureEvent.ERROR_RESOURCE_CONFLICT ->
                failPendingPhoto("CAMERA_BUSY", "VIVE Eagle camera is in use (ERROR_RESOURCE_CONFLICT)")
            else -> failPendingPhoto("CAPTURE_FAILED", "VIVE Eagle capture failed ($event)")
        }
    }

    /**
     * The Eagle returns HEIC. It is decoded and re-encoded as an upright JPEG here (see
     * EagleHeic), on a worker thread since the SDK callback thread is HTC's. BlePhotoUploadService
     * passes JPEG input through unchanged, so the same helpers Mentra Live uses for its
     * phone-relayed photos then handle both the upload and the local-file case.
     */
    private fun deliverPhoto(request: PhotoRequest, heic: ByteArray) {
        val context =
            try {
                Bridge.getContext().applicationContext
            } catch (_: IllegalStateException) {
                Bridge.sendPhotoError(request.requestId, "PHONE_SAVE_FAILED", "No app context to handle the photo")
                return
            }

        // A plain thread rather than `scope`: disconnect() cancels the scope's children, and a
        // photo already taken from pendingPhoto must still get a response.
        Thread({
            if (request.save) saveRawCopy(context, request.requestId, heic)
            val jpeg =
                try {
                    EagleHeic.toUprightJpeg(heic)
                } catch (e: Exception) {
                    Bridge.log("$TAG: HEIC to JPEG failed: ${e.message}")
                    Bridge.sendPhotoError(request.requestId, "PHONE_DECODE_FAILED", "Could not decode the VIVE Eagle photo: ${e.message}")
                    return@Thread
                }
            deliverJpeg(context, request, jpeg)
        }, "EaglePhoto").start()
    }

    private fun deliverJpeg(context: Context, request: PhotoRequest, jpeg: ByteArray) {
        if (request.localFile) {
            BlePhotoUploadService.processAndSavePhoto(
                jpeg,
                request.requestId,
                File(context.cacheDir, LOCAL_PHOTO_DIR),
                object : BlePhotoUploadService.SaveCallback {
                    override fun onSaved(requestId: String, file: File) {
                        val event = HashMap<String, Any>()
                        event["type"] = "photo_response"
                        event["state"] = "success"
                        event["success"] = true
                        event["requestId"] = requestId
                        event["uploadUrl"] = ""
                        event["photoUrl"] = Uri.fromFile(file).toString()
                        event["contentType"] = "image/jpeg"
                        event["fileSizeBytes"] = file.length()
                        event["timestamp"] = System.currentTimeMillis()
                        Bridge.sendPhotoResponse(event)
                    }

                    override fun onError(requestId: String, error: String?) {
                        Bridge.sendPhotoError(
                            requestId,
                            "PHONE_SAVE_FAILED",
                            "Could not save the photo on the phone: $error"
                        )
                    }
                }
            )
            return
        }

        if (request.webhookUrl.isEmpty()) {
            Bridge.sendPhotoError(request.requestId, "NO_UPLOAD_TARGET", "Photo request had no webhookUrl")
            return
        }
        BlePhotoUploadService.processAndUploadPhoto(
            jpeg,
            request.requestId,
            request.webhookUrl,
            request.authToken,
            object : BlePhotoUploadService.UploadCallback {
                override fun onSuccess(requestId: String, responseBody: String?) {
                    val event = HashMap<String, Any>()
                    event["type"] = "photo_response"
                    event["state"] = "success"
                    event["success"] = true
                    event["requestId"] = requestId
                    event["uploadUrl"] = request.webhookUrl
                    event["timestamp"] = System.currentTimeMillis()
                    copyUploadResponseMetadata(event, responseBody)
                    Bridge.sendPhotoResponse(event)
                }

                override fun onError(requestId: String, error: String?) {
                    Bridge.sendPhotoError(requestId, "PHONE_UPLOAD_FAILED", "VIVE Eagle photo upload failed: $error")
                }
            }
        )
    }

    /** Same fields Mentra Live copies from the upload response. */
    private fun copyUploadResponseMetadata(event: MutableMap<String, Any>, responseBody: String?) {
        if (responseBody.isNullOrBlank()) return
        try {
            val response = JSONObject(responseBody)
            for (key in listOf("photoUrl", "statusUrl", "mimeType", "contentType", "bytes", "size")) {
                if (response.has(key) && !response.isNull(key)) event[key] = response.get(key)
            }
        } catch (e: JSONException) {
            Bridge.log("$TAG: upload response body was not JSON metadata")
        }
    }

    /**
     * First-pass `save`: on the Eagle the phone already holds the only copy, so "save" keeps
     * the original HEIC in the app's external files dir (like Mentra Live's BLE photo backup).
     * There is no gallery on the glasses to save into through this SDK.
     */
    private fun saveRawCopy(context: Context, requestId: String, heic: ByteArray) {
        try {
            val dir = File(context.getExternalFilesDir(null), FILE_SAVE_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "EAGLE_${requestId}_${System.currentTimeMillis()}.heic")
            FileOutputStream(file).use { it.write(heic) }
            Bridge.log("$TAG: saved photo ${file.absolutePath}")
        } catch (e: Exception) {
            Bridge.log("$TAG: could not save the HEIC copy: ${e.message}")
        }
    }

    override fun startStream(message: MutableMap<String, Any>) {
        // HTC's startVideoStreaming hands back H.264 + AAC buffers, not an RTMP session; the
        // phone-side re-streamer that would need is not built yet.
        Bridge.log("$TAG: startStream - not supported yet")
    }

    override fun stopStream() {
        Bridge.log("$TAG: stopStream - not supported yet")
    }

    override fun sendStreamKeepAlive(message: MutableMap<String, Any>) {
        Bridge.log("$TAG: sendStreamKeepAlive - not supported yet")
    }

    override fun startVideoRecording(requestId: String, save: Boolean, sound: Boolean) {
        Bridge.log("$TAG: startVideoRecording - not supported yet")
    }

    override fun stopVideoRecording(requestId: String) {
        Bridge.log("$TAG: stopVideoRecording - not supported yet")
    }

    // =====================================================================================
    // Microphone
    // =====================================================================================

    override fun setMicEnabled(enabled: Boolean) {
        Bridge.log("$TAG: setMicEnabled($enabled)")
        micWanted = enabled
        if (!adapterReady || !viveGlass.isConnected) {
            // Applied in onConnected().
            Bridge.log("$TAG: not connected; mic state will apply on connect")
            return
        }
        if (enabled) startMic() else stopMic()
    }

    override fun sortMicRanking(list: MutableList<String>): MutableList<String> {
        return list
    }

    private fun startMic() {
        if (audioStreaming || transcribing) return
        if (!hasPermission(Permission.MICROPHONE)) {
            DeviceStore.apply("glasses", "micEnabled", false)
            return
        }
        if (USE_GLASSES_TRANSCRIPTION) {
            val result =
                try {
                    viveGlass.startTranscription(true)
                } catch (e: Exception) {
                    Bridge.log("$TAG: startTranscription threw: ${e.message}")
                    -1
                }
            transcribing = result >= 0
            Bridge.log("$TAG: startTranscription(continuous) result=$result")
            DeviceStore.apply("glasses", "micEnabled", transcribing)
            return
        }

        val decoder =
            EagleAacDecoder { pcm ->
                val manager = DeviceManager.getInstance()
                manager.reportGlassesAudioActivity()
                manager.handlePcm(pcm)
            }
        micDecoder = decoder
        decoder.start()

        val result =
            try {
                viveGlass.startAudioStreaming(micFormat(), micBufferCallback, micEventCallback)
            } catch (e: Exception) {
                Bridge.log("$TAG: startAudioStreaming threw: ${e.message}")
                -1
            }
        Bridge.log("$TAG: startAudioStreaming result=$result")
        if (result < 0) {
            releaseMicDecoder()
            DeviceStore.apply("glasses", "micEnabled", false)
            return
        }
        // Buffers can arrive before STARTED; accept them right away.
        audioStreaming = true
        DeviceStore.apply("glasses", "micEnabled", true)
    }

    /**
     * Asks for 16 kHz mono when the SDK supports it, so no resampling is needed. The decoder
     * resamples whatever actually arrives (the simulator replays a fixed 44.1 kHz stereo AAC
     * sample regardless of the format asked for).
     */
    private fun micFormat(): AudioStreamingFormat {
        val sampleRate =
            if (AudioStreamingFormat.SUPPORTED_SAMPLE_RATES.contains(TARGET_SAMPLE_RATE)) {
                TARGET_SAMPLE_RATE
            } else {
                AudioStreamingFormat.DEFAULT_SAMPLE_RATE_AUDIO
            }
        return AudioStreamingFormat(
            Microphone.MIC_DIRECTION_TOWARD_USER,
            AudioStreamingFormat.DEFAULT_BITRATE,
            sampleRate,
            AudioChannel.MONO
        )
    }

    private fun stopMic() {
        if (adapterReady) {
            try {
                if (audioStreaming) viveGlass.stopAudioStreaming()
                if (transcribing) viveGlass.stopTranscription()
            } catch (e: Exception) {
                Bridge.log("$TAG: stopping the mic failed: ${e.message}")
            }
        }
        stopMicLocal()
        DeviceStore.apply("glasses", "micEnabled", false)
    }

    /** Local teardown only, for when the link is already gone. */
    private fun stopMicLocal() {
        audioStreaming = false
        transcribing = false
        releaseMicDecoder()
    }

    private fun releaseMicDecoder() {
        val decoder = micDecoder
        micDecoder = null
        decoder?.release()
    }

    private fun handleSpeechTranscribed(event: TranscribedEvent?, text: String?) {
        Bridge.log("$TAG: onSpeechTranscribed event=$event")
        when (event) {
            TranscribedEvent.SUCCESS -> {
                if (text.isNullOrEmpty()) return
                // HTC delivers one finished result per callback, with no partial/final split
                // (unlike SherpaOnnxTranscriber's onPartialResult/onFinalResult), so each result
                // is reported as final. Language is the user's VIVE locale as a BCP-47 tag.
                Bridge.sendLocalTranscription(text, true, preferredLocale.toLanguageTag())
            }
            else -> {
                // ERROR, ERROR_RESOURCE_CONFLICT, null: recognition stopped.
                transcribing = false
                DeviceStore.apply("glasses", "micEnabled", false)
            }
        }
        // TODO: verify on hardware whether startTranscription(true) keeps listening after a
        // SUCCESS or needs re-arming; HTC's sample only uses one-shot mode (false).
    }

    // =====================================================================================
    // Buttons and speaker
    // =====================================================================================

    private fun handleKeyEvent(event: KeyEvent?) {
        Bridge.log("$TAG: onKeyEvent event=$event")
        when (event) {
            // The callback carries no press duration or count, so every press is "short".
            KeyEvent.AIBUTTON -> Bridge.sendButtonPressEvent("ai", "short")
            else -> {}
        }
        // The SDK's internal constants (com.htc.viveglass.sdk.C) also name camera/action key
        // broadcasts (SHORTPRESS_CAMERAKEY etc.). They are unverified on hardware and outside
        // the public callback API, so they are not wired up in this pass.
    }

    /**
     * Speaks through the Eagle's speakers with HTC's TTS. Nothing in SGCManager calls this yet:
     * MentraOS plays TTS as phone audio routed over Bluetooth.
     */
    fun speakText(text: String) {
        if (!adapterReady || !viveGlass.isConnected) {
            Bridge.log("$TAG: speakText while disconnected")
            return
        }
        val result =
            try {
                viveGlass.speakText(text, preferredLocale)
            } catch (e: Exception) {
                Bridge.log("$TAG: speakText threw: ${e.message}")
                -1
            }
        Bridge.log("$TAG: speakText result=$result")
    }

    // =====================================================================================
    // Not available on the Eagle (no display, LED, Wi-Fi, IMU) or not exposed by SDK 0.6.1
    // =====================================================================================

    override fun sendButtonPhotoSettings() {
        Bridge.log("$TAG: sendButtonPhotoSettings - not supported")
    }

    override fun sendButtonVideoRecordingSettings() {
        Bridge.log("$TAG: sendButtonVideoRecordingSettings - not supported")
    }

    override fun sendButtonMaxRecordingTime() {
        Bridge.log("$TAG: sendButtonMaxRecordingTime - not supported")
    }

    override fun sendCameraFovSetting() {
        Bridge.log("$TAG: sendCameraFovSetting - not supported")
    }

    override fun setBrightness(level: Int, autoMode: Boolean) {
        Bridge.log("$TAG: setBrightness - no display")
    }

    override fun clearDisplay() {
        Bridge.log("$TAG: clearDisplay - no display")
    }

    override fun sendText(text: String) {
        Bridge.log("$TAG: sendText - no display")
    }

    override fun sendTextWall(text: String) {
        Bridge.log("$TAG: sendTextWall - no display")
    }

    override fun sendDoubleTextWall(top: String, bottom: String) {
        Bridge.log("$TAG: sendDoubleTextWall - no display")
    }

    override fun displayBitmap(
            base64ImageData: String,
            x: Int?,
            y: Int?,
            width: Int?,
            height: Int?
    ): Boolean {
        Bridge.log("$TAG: displayBitmap - no display")
        return false
    }

    override fun showDashboard() {
        Bridge.log("$TAG: showDashboard - no display")
    }

    override fun setDashboardPosition(height: Int, depth: Int) {
        Bridge.log("$TAG: setDashboardPosition - no display")
    }

    override fun setHeadUpAngle(angle: Int) {
        Bridge.log("$TAG: setHeadUpAngle - no IMU")
    }

    override fun getBatteryStatus() {
        // SDK 0.6.1 has no battery query or battery callback (only a BATTERY_LOW streaming
        // event), so battery_status is never sent for the Eagle.
        Bridge.log("$TAG: getBatteryStatus - not exposed by the VIVE SDK")
    }

    override fun setSilentMode(enabled: Boolean) {
        Bridge.log("$TAG: setSilentMode - not supported")
    }

    override fun exit() {
        Bridge.log("$TAG: exit")
    }

    override fun sendShutdown() {
        Bridge.log("$TAG: sendShutdown - not supported")
    }

    override fun sendReboot() {
        Bridge.log("$TAG: sendReboot - not supported")
    }

    override fun sendRgbLedControl(
            requestId: String,
            packageName: String?,
            action: String,
            color: String?,
            onDurationMs: Int,
            offDurationMs: Int,
            count: Int
    ) {
        Bridge.log("$TAG: sendRgbLedControl - no LED")
        Bridge.sendRgbLedControlResponse(requestId, false, "device_not_supported")
    }

    override fun requestWifiScan(scanId: String?) {
        Bridge.log("$TAG: requestWifiScan - not supported")
    }

    override fun sendWifiCredentials(ssid: String, password: String) {
        Bridge.log("$TAG: sendWifiCredentials - not supported")
    }

    override fun forgetWifiNetwork(ssid: String) {
        Bridge.log("$TAG: forgetWifiNetwork - not supported")
    }

    override fun sendHotspotState(enabled: Boolean) {
        Bridge.log("$TAG: sendHotspotState - not supported")
    }

    override fun sendUserEmailToGlasses(email: String) {
        Bridge.log("$TAG: sendUserEmailToGlasses - not supported")
    }

    override fun sendIncidentId(incidentId: String, apiBaseUrl: String?) {
        Bridge.log("$TAG: sendIncidentId: $incidentId - not supported")
    }

    override fun queryGalleryStatus() {
        Bridge.log("$TAG: queryGalleryStatus - not supported")
    }

    override fun sendGalleryMode() {
        Bridge.log("$TAG: sendGalleryMode - not supported")
    }

    override fun requestVersionInfo() {
        Bridge.log("$TAG: requestVersionInfo - not exposed by the VIVE SDK")
    }
}

// ---------- HEIC photo ----------

/**
 * HEIC -> upright JPEG. BitmapFactory (HEIF from Android 9) ignores the EXIF orientation, so the
 * bitmap is rotated before encoding, as HTC's sample does (ViveGlassKitManager.byteToBitmap:
 * decode, read TAG_ORIENTATION, rotate). The sample handles 90/180/270 only; the mirrored
 * orientations are handled here too. The output JPEG carries no EXIF, so viewers cannot rotate
 * it a second time.
 */
internal object EagleHeic {
    private const val JPEG_QUALITY = 90

    fun toUprightJpeg(heic: ByteArray): ByteArray {
        val decoded =
            BitmapFactory.decodeByteArray(heic, 0, heic.size)
                ?: throw IllegalArgumentException("BitmapFactory could not decode ${heic.size} bytes")
        val orientation = readOrientation(heic)
        val upright = applyOrientation(decoded, orientation)
        if (upright !== decoded) decoded.recycle()
        try {
            val out = ByteArrayOutputStream()
            if (!upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw IllegalStateException("JPEG encode failed")
            }
            Bridge.log(
                "EAGLE: photo ${upright.width}x${upright.height} orientation=$orientation " +
                    "HEIC ${heic.size} -> JPEG ${out.size()} bytes"
            )
            return out.toByteArray()
        } finally {
            upright.recycle()
        }
    }

    private fun readOrientation(heic: ByteArray): Int =
        try {
            ByteArrayInputStream(heic).use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) {
            Bridge.log("EAGLE: could not read the HEIC orientation: ${e.message}")
            ExifInterface.ORIENTATION_NORMAL
        }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val transform = orientationTransform(orientation)
        if (transform.degrees == 0f && !transform.mirror) return source
        val matrix = Matrix()
        matrix.setRotate(transform.degrees)
        if (transform.mirror) matrix.postScale(-1f, 1f)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Rotate clockwise by [degrees], then mirror horizontally when [mirror]. */
    data class Transform(val degrees: Float, val mirror: Boolean)

    /** EXIF orientation (1..8) to the transform that makes the image upright. */
    fun orientationTransform(orientation: Int): Transform =
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Transform(0f, true)
            ExifInterface.ORIENTATION_ROTATE_180 -> Transform(180f, false)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Transform(180f, true)
            ExifInterface.ORIENTATION_TRANSPOSE -> Transform(90f, true)
            ExifInterface.ORIENTATION_ROTATE_90 -> Transform(90f, false)
            ExifInterface.ORIENTATION_TRANSVERSE -> Transform(270f, true)
            ExifInterface.ORIENTATION_ROTATE_270 -> Transform(270f, false)
            else -> Transform(0f, false) // NORMAL, UNDEFINED, or out of range
        }
}

// ---------- AAC decoder ----------

/**
 * Decodes the Eagle's AAC mic stream to 16 kHz mono s16le PCM, the format DeviceManager.handlePcm
 * expects. Mirrors HTC's sample AudioDecoder (codec config from the CODEC_CONFIG buffer or an
 * ADTS header) minus playback, and runs on its own thread so the SDK callback only copies bytes.
 */
private class EagleAacDecoder(private val onPcm: (ByteArray) -> Unit) {
    companion object {
        private const val TARGET_RATE = 16_000
        private const val MAX_PENDING = 200
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var codec: MediaCodec? = null
    private var asc: ByteArray? = null
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var running = false

    // Output format, from the ASC and then from INFO_OUTPUT_FORMAT_CHANGED.
    private var outRate = 0
    private var outChannels = 0

    /** Downmix + anti-aliased resample to 16 kHz, state carried across buffers. */
    private val resampler = ViveEagleResampler(TARGET_RATE)
    /** Reused copy of each decoder output buffer. */
    private var pcmScratch = ByteArray(0)

    private var loggedFirstPcm = false

    fun start() {
        if (running) return
        val t = HandlerThread("EagleAacDecoder")
        t.start()
        thread = t
        handler = Handler(t.looper)
        running = true
    }

    fun enqueue(data: ByteArray, ptsUs: Long, flags: Int) {
        val h = handler ?: return
        if (!running) return
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet()
            return // backpressure: drop rather than grow without bound
        }
        h.post {
            pending.decrementAndGet()
            if (running) process(data, ptsUs, flags)
        }
    }

    fun release() {
        running = false
        val h = handler
        val t = thread
        handler = null
        thread = null
        if (h == null || t == null) return
        h.removeCallbacksAndMessages(null)
        h.post {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {}
            codec = null
            t.quitSafely()
        }
    }

    private fun process(data: ByteArray, ptsUs: Long, flags: Int) {
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            asc = data
            configure()
            return
        }
        if (asc == null && looksLikeAdts(data)) {
            asc = ascFromAdts(data)
            configure()
        }
        val c = codec ?: return
        val au = if (looksLikeAdts(data)) stripAdts(data) else data
        if (au.isEmpty()) return
        try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx)
                if (buf == null) {
                    c.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                } else {
                    buf.clear()
                    buf.put(au)
                    c.queueInputBuffer(inIdx, 0, au.size, ptsUs, 0)
                }
            }
            drain(c)
        } catch (e: Exception) {
            Bridge.log("EAGLE: AAC decode error: ${e.message}")
        }
    }

    private fun configure() {
        if (codec != null) return
        val config = asc ?: return
        if (config.size < 2) return
        val b0 = config[0].toInt() and 0xFF
        val b1 = config[1].toInt() and 0xFF
        val sfIndex = ((b0 and 0x07) shl 1) or (b1 shr 7)
        val channels = (b1 shr 3) and 0x0F
        val rate = sampleRateForIndex(sfIndex)
        if (rate <= 0 || channels <= 0) {
            Bridge.log("EAGLE: unsupported AAC config sfIndex=$sfIndex channels=$channels")
            return
        }
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            outRate = rate
            outChannels = channels
            Bridge.log("EAGLE: AAC decoder configured rate=$rate channels=$channels")
        } catch (e: Exception) {
            Bridge.log("EAGLE: AAC decoder setup failed: ${e.message}")
            codec = null
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            when {
                outIdx >= 0 -> {
                    val buf = c.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0) {
                        if (pcmScratch.size < info.size) pcmScratch = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(pcmScratch, 0, info.size)
                        val out = resampler.process(pcmScratch, info.size, outRate, outChannels)
                        if (out.isNotEmpty()) {
                            if (!loggedFirstPcm) {
                                loggedFirstPcm = true
                                Bridge.log("EAGLE: first mic PCM (${out.size} bytes @16kHz)")
                            }
                            onPcm(out)
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                else -> return // INFO_TRY_AGAIN_LATER and anything else
            }
        }
    }

    private fun sampleRateForIndex(index: Int): Int =
        when (index) {
            0 -> 96000
            1 -> 88200
            2 -> 64000
            3 -> 48000
            4 -> 44100
            5 -> 32000
            6 -> 24000
            7 -> 22050
            8 -> 16000
            9 -> 12000
            10 -> 11025
            11 -> 8000
            12 -> 7350
            else -> -1
        }

    private fun looksLikeAdts(b: ByteArray): Boolean =
        b.size >= 7 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xF0) == 0xF0

    private fun ascFromAdts(adts: ByteArray): ByteArray {
        val profile = ((adts[2].toInt() shr 6) and 0x03) + 1
        val sfIndex = (adts[2].toInt() shr 2) and 0x0F
        val chCfg = ((adts[2].toInt() and 0x01) shl 2) or ((adts[3].toInt() shr 6) and 0x03)
        val b0 = (profile shl 3) or (sfIndex shr 1)
        val b1 = ((sfIndex and 1) shl 7) or (chCfg shl 3)
        return byteArrayOf(b0.toByte(), b1.toByte())
    }

    private fun stripAdts(frame: ByteArray): ByteArray {
        val protectionAbsent = (frame[1].toInt() and 0x01) == 1
        val headerLen = if (protectionAbsent) 7 else 9
        if (frame.size <= headerLen) return ByteArray(0)
        return frame.copyOfRange(headerLen, frame.size)
    }
}
