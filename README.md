# AURA Camera AI

Native Kotlin Android camera foundation built with Jetpack Compose, CameraX and on-device ML Kit.

## Current foundation
- CameraX live back-camera preview.
- Y-plane luminance histogram and average brightness estimate.
- On-device ML Kit image labels for basic scene hints.
- Camera permission handling and latest-frame backpressure.
- GitHub Actions debug APK build.

## Build
Requires JDK 17 and Gradle 8.9:
`gradle :app:assembleDebug`

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## Device notes
Designed to start conservatively on mid-range Android devices, including Samsung Galaxy A16 5G. Camera2 capabilities vary by device; this first foundation uses CameraX defaults and does not promise unsupported manual controls, multi-frame HDR, segmentation or optical features.

## Roadmap
1. Verify baseline build and device preview.
2. Add robust scene classification and exposure recommendations.
3. Add capture/save flow while preserving original images.
4. Add optional manual controls only when supported by Camera2.
5. Add computational processing with measured memory and thermal limits.

This is an early foundation, not a finished computational-photography camera.
