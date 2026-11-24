# MOTIO — Multi Elastic Brush
Android-native Kotlin Live Wallpaper MVP.

## Build
GitHub Actions builds a debug APK on every push to main and on manual workflow dispatch.
Download it from Actions → latest run → Artifacts → MOTIO-debug-apk.

## Stack
- Kotlin, Jetpack Compose, Android SDK 35
- JDK 17, Gradle 8.9-compatible Android Gradle Plugin 8.7.3
- Rotation Vector sensor with accelerometer fallback
- Canvas bitmap mesh deformation
- Android WallpaperService

## MVP notes
This is an initial implementation. Test on a real Android device. Mesh rendering and sensor orientation
may need tuning across portrait/landscape devices. The selected image and brush map stay in app-private storage.