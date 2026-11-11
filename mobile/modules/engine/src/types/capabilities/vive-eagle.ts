/**
 * @fileoverview VIVE Eagle Hardware Capabilities
 *
 * Capability profile for the HTC VIVE Eagle AI glasses.
 * Drop-in for a MentraOS fork at:
 *   mobile/modules/engine/src/types/capabilities/vive-eagle.ts
 *
 * Modelled on mentra-live.ts (the closest existing device: camera + mic +
 * speaker, no display). Differences from Mentra Live are flagged inline.
 *
 * Backing SDK: HTC "VIVE AI Glasses SDK" 0.6.1 beta, phone-side, package
 * com.htc.viveglass.sdk. The glasses are a BLE peripheral driven from the phone.
 */

import type { Capabilities } from "../hardware";

/**
 * VIVE Eagle capability profile
 */
export const viveEagle: Capabilities = {
  modelName: "VIVE Eagle",

  // Camera - 12MP. HTC SDK does still capture (captureImage) + video streaming
  // (startVideoStreaming, clips <= 10 min). NOTE: HTC delivers raw frames via
  // callback, NOT an RTMP endpoint. "rtmp" here means MentraOS must re-stream
  // the phone-received frames; the driver owns that transport.
  hasCamera: true,
  camera: {
    resolution: { width: 4000, height: 3000 }, // 12MP class still
    hasHDR: false,
    hasFocus: true,
    video: {
      canRecord: true,
      canStream: true,
      supportedStreamTypes: ["rtmp"], // via phone re-stream, see note above
      supportedResolutions: [
        { width: 1920, height: 1080 },
        { width: 1280, height: 720 },
        { width: 640, height: 480 },
      ],
    },
  },

  // Display - Eagle has NO display. Audio is the only output channel to the wearer.
  hasDisplay: false,
  display: null,

  // Microphone - HTC exposes audio streaming + on-device STT. VAD not documented.
  hasMicrophone: true,
  microphone: {
    count: 1,
    hasVAD: false,
  },

  // Speaker - open-ear speakers; HTC speakText() does TTS through them.
  hasSpeaker: true,
  speaker: {
    count: 2,
    isPrivate: false,
  },

  // IMU - not exposed by the VIVE AI Glasses SDK.
  hasIMU: false,
  imu: null,

  // Buttons - two physical keys: the AI button and the camera button.
  // CONFIRMED via public SDK callback: AI button short press (onKeyEvent AIBUTTON).
  // double_press / long_press and the camera button come from internal broadcast
  // intents (com.htc.intent.action.*_ACTIONKEY / *_CAMERAKEY) seen in the AAR and
  // are UNVERIFIED on hardware - keep them here but validate before relying on them.
  hasButton: true,
  button: {
    count: 2,
    buttons: [
      {
        type: "press",
        events: ["press", "double_press", "long_press"], // "ai" / action key
        isCapacitive: false,
      },
      {
        type: "press",
        events: ["press", "double_press", "long_press"], // camera key (unverified)
        isCapacitive: false,
      },
    ],
  },

  // Light - no LED surface exposed to the SDK.
  hasLight: false,
  light: null,

  // Power - no external battery/case.
  power: {
    hasExternalBattery: false,
  },

  // WiFi - the app reaches the glasses over BLE/BT through the phone; the SDK
  // exposes no independent on-device WiFi control.
  hasWifi: false,

  // OTA - HTC owns firmware updates via VIVE Connect; MentraOS does not update it.
  hasOta: false,
};
