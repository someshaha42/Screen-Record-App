# Screen Recorder (Native Android — Kotlin & Jetpack Compose)

Native Android Studio project implementing hardware-accelerated screen recording on Android API 36–37.

## Build Configuration

- **Application Name**: `Screen Recorder`
- **Package / Namespace**: `com.screenrecorder.pro`
- **Version**: `1.0` (`versionCode = 1`)
- **Min SDK**: `36`
- **Compile / Target SDK**: `37`
- **Language / UI**: Kotlin + Jetpack Compose (Material Design 3, System Default Light/Dark)

## Fixed Internal Parameters (Not Exposed in UI)

- **Resolution**: Native display dimensions via `WindowManager.currentWindowMetrics`
- **Frame Rate**: `60` FPS
- **Video Codec**: H.264 / AVC (`video/avc`)
- **Hardware Encoder Preference**: `c2.qti.avc.encoder` (Qualcomm Snapdragon) / `c2.mtk.avc.encoder` (MediaTek), dynamically inspected via `MediaCodecList` with no silent software fallback
- **Decoder**: Hardware H.264/AVC decoder only (`HardwareVideoPlayer`)
- **Orientation**: Dynamic surface mirroring (`GlSurfaceCropRenderer` + `DisplayManager.DisplayListener`) preserving aspect ratio without stretching

## Building the Debug APK

1. Open this `android-project` directory in **Android Studio** (with Android SDK 37 & JDK 17 installed).
2. Allow Gradle sync to download dependencies.
3. Build the Debug APK from terminal or Android Studio:
   ```bash
   gradle assembleDebug
   ```
   Output APK path: `app/build/outputs/apk/debug/app-debug.apk`
4. Install to a connected Android 16/17 (API 36+) device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
