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

MCAV runs out of the box on the following platforms. No administrator rights, package manager, or extra JVM
arguments are needed: everything MCAV downloads is stored in the cache folder of the current user.

| Operating System | Architectures             | Notes                                                              |
|------------------|---------------------------|--------------------------------------------------------------------|
| Windows          | x86-64                    | VLC and yt-dlp are downloaded automatically when missing.          |
| macOS            | x86-64, ARM64 (Apple)     | VLC is downloaded and mounted without administrator rights.        |
| Linux            | x86-64, ARM64             | VLC is used from the system, or downloaded as an AppImage on x86-64. |

FFmpeg and OpenCV are bundled with the library for every platform above, so the FFmpeg and OpenCV players always
work. VLC and yt-dlp are optional: when one of them cannot be installed, only the features that need it are
unavailable, which you can check with [capabilities](instance.md#capabilities).

QEMU is never installed by MCAV. To use the [virtual machine module](vm.md), install QEMU yourself and make sure it is
on the `PATH`.

## Native Access Warnings

Java 24 and newer print a warning the first time a library loads native code. The warning is harmless. To hide it,
start the JVM with the following argument:

```bash
--enable-native-access=ALL-UNNAMED
```
