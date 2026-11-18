# HTC VIVE AI Glasses SDK (local vendor folder)

This folder is intentionally empty in git. HTC's SDK is a proprietary download,
so it is not committed to this repository.

The VIVE Eagle driver (`src/main/java/com/mentra/bluetoothsdk/sgcs/ViveEagle.kt`)
is compiled against it. Without the files below, `build.gradle` logs a warning
and compiles a stub `ViveEagle` instead (`src/viveEagleStub/`), which reports a
pairing failure if someone tries to connect an Eagle.

## Setup

1. Download the VIVE AI Glasses SDK (Android, 0.6.1 beta) from
   https://developer.vive.com/resources/vive-ai-glasses-sdk/download/latest-release/
   (free, no account needed) and extract it.
2. Copy these two things from the extracted
   `sample/ViveGlassSample/` folder into this folder:

```
libs/vive-eagle-sdk/
├── README.md                          (this file)
├── ViveGlassSimulator-release.aar     from sample/ViveGlassSample/app/libs/
└── repository/                        from sample/ViveGlassSample/repository/
    └── com/htc/viveglass/sdk/viveglass_client/0.6.1/
        ├── viveglass_client-0.6.1.aar
        ├── viveglass_client-0.6.1.pom
        ├── viveglass_client-0.6.1.module
        └── (HTC's .md5/.sha1/.sha256/.sha512 files)
```

3. Rebuild. Gradle checks both AARs against pinned SHA-256 values (see the
   "VIVE Eagle" block in `build.gradle`) and fails if they don't match, for
   example after an SDK version bump.

Expected SHA-256 for 0.6.1:

- `viveglass_client-0.6.1.aar`: `55c3c321a65495dae2f86ece2a7d8fe3b76d920e79d7680ce121050fa44cd17f`
- `ViveGlassSimulator-release.aar`: `b0ce80a746b465aadf3eaaaeef2b5a25a289ad7071b8364caca568358152a955`
