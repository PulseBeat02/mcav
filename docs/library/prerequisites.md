# Library Prerequisites

MCAV is a Java library that can be used in any project. To get started, add the MCAV core dependency to your project
with a build system that supports Maven repositories, such as Gradle or Maven.

```{note}
All of MCAV's modules require Java 25 or newer.
```

```kotlin
repositories {
    maven("https://repo.brandonli.me/snapshots")
}
```

```kotlin
dependencies {
    implementation("me.brandonli:mcav-common:1.0.0-SNAPSHOT")
}
```

## Supported Platforms

MCAV targets the following platforms. Everything MCAV downloads is stored in the cache folder of the current user;
optional native features can also need system libraries, a display, or an audio device.

| Operating System | Architectures             | Notes                                                              |
|------------------|---------------------------|--------------------------------------------------------------------|
| Windows          | x86-64                    | VLC and yt-dlp are downloaded automatically when missing.          |
| macOS            | x86-64, ARM64 (Apple)     | VLC is downloaded and mounted without administrator rights.        |
| Linux            | x86-64, ARM64             | VLC is used from the system, or downloaded as an AppImage on x86-64. |

FFmpeg and OpenCV are bundled with the library for every platform above. The OpenCV build differs per platform: the
Windows and macOS builds read video files themselves, the Linux build has no file backend and only captures from
cameras. `VideoPlayer.opencv()` therefore reads files with the bundled FFmpeg where OpenCV cannot, so a file plays on
every platform. OpenCV capture still depends on a working capture backend and device. VLC and yt-dlp are optional: when one of them cannot be installed, only the features that need it are
unavailable, which you can check with [capabilities](instance.md#capabilities).

QEMU is never installed by MCAV. To use the [virtual machine module](vm.md), install QEMU yourself and make sure it is
on the `PATH`.

## Native Access Warnings

Java 24 and newer print a warning the first time a library loads native code. The warning is harmless. To hide it,
start the JVM with the following argument:

```bash
--enable-native-access=ALL-UNNAMED
```
