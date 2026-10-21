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

| Platform       | VLC                                                               | yt-dlp        | FFmpeg and OpenCV |
|----------------|-------------------------------------------------------------------|---------------|-------------------|
| Windows x86-64 | Downloaded from VideoLAN when missing                             | Downloaded    | Bundled           |
| Windows ARM64  | Downloaded from VideoLAN when missing                             | Downloaded    | Not available     |
| macOS x86-64   | Downloaded from VideoLAN and mounted without administrator rights | Downloaded    | Bundled           |
| macOS ARM64    | Downloaded from VideoLAN and mounted without administrator rights | Downloaded    | Bundled           |
| Linux x86-64   | Used from the system, or installed from Debian packages           | Downloaded    | Bundled           |
| Linux ARM64    | Used from the system, or installed from Debian packages           | Downloaded    | Bundled           |
| FreeBSD (any)  | Not available                                                     | Not available | Not available     |

MCAV builds, tests and runs as an ordinary user on every platform in the table: it needs no administrator rights. It
never runs `sudo`, `pkexec` or `doas`, a system package manager such as `apt` or `brew`, or an installer that asks for
elevation, and it writes only to the cache folder of the current user, the JavaCPP cache and the temporary directory.
Every tool it fetches is unpacked into the cache and loaded from there: the macOS disk image is attached with `hdiutil`,
which needs no administrator rights, and copied into the cache, never into `/Applications`. The package manager
commands below are an option you can run yourself, never a step MCAV takes.

FFmpeg and OpenCV come from the JavaCPP builds MCAV depends on, which have none for Windows on ARM64 or for FreeBSD on
any processor. MCAV needs them to install, so on those platforms `install()` fails with an `MCAVLoadingException` caused
by a `NativeLoadingException` that names the platform, and MCAV cannot be used there; the VLC and yt-dlp columns only
matter once JavaCPP publishes builds for them. When VLC is missing and the platform has no download, starting it fails
with an `UnsupportedOperatingSystemException` that names the platform; yt-dlp is then marked unavailable with a warning
that names the platform, and downloading it directly fails with an `InstallationException` that does. 32-bit x86 is not
listed, because Java 25, which MCAV needs, has no port for it.

On Windows and macOS the download is VLC 3.0.24 from VideoLAN, checked against the SHA-256 hash MCAV ships. VideoLAN
publishes no Linux build, so on x86-64 and ARM64 Linux MCAV installs the Debian 12 (bookworm) packages of VLC 3.0.23:
`libvlc5`, `libvlccore9` and `vlc-plugin-base` with every library they depend on, apart from the C and C++ runtimes of
the system. Each package comes from the bookworm archive or its security updates on `snapshot.debian.org`, whichever has
the newer version, and is checked against the SHA-256 hash MCAV ships. MCAV unpacks the packages itself into the cache
folder, without `dpkg`, a package manager or administrator rights, and loads their libraries in dependency order before
VLC. The libraries need glibc 2.36 or newer; on an older system, install VLC from your distribution instead
(`apt install vlc`, `dnf install vlc`): MCAV then uses that VLC and downloads nothing. How to regenerate the pinned
packages is described in [compiling MCAV](compile.md).

FFmpeg and OpenCV are bundled with the library for every platform marked so above. The OpenCV build differs per
platform: the Windows and macOS builds read video files themselves, the Linux build has no file backend and only
captures from cameras. `VideoPlayer.opencv()` therefore reads files with the bundled FFmpeg where OpenCV cannot, so a
file plays on every platform where they are bundled. OpenCV capture still depends on a working capture backend and
device. VLC and yt-dlp are optional: when one of them cannot be installed, only the features that need it are
unavailable, which you can check with [capabilities](instance.md#capabilities).

The FFmpeg libraries link the sound, display and device libraries of a Linux desktop: ALSA, PulseAudio, XCB and udev.
When the system lacks one of them, as a container or a minimal server can, MCAV installs the Debian 12 packages of
`libasound2`, `libpulse0`, `libxcb1`, `libxcb-shm0` and `libudev1` with their dependencies on x86-64 and ARM64 Linux,
the same way as VLC above, and loads them before FFmpeg. The FFmpeg program does not share the libraries of the JVM, so
MCAV runs it with their folders on `LD_LIBRARY_PATH`, after the folder of the program itself, as [FFmpeg](ffmpeg.md)
describes. When that installation fails, MCAV logs a warning, and FFmpeg loads only if the system has the libraries.

Face detection loads the object detection module of OpenCV, which links GTK 2 on Linux. When the system has no GTK 2,
MCAV installs the Debian 12 packages of `libgtk2.0-0` and its dependencies on x86-64 and ARM64 Linux, the same way as
VLC above: pinned, checked against their hashes, unpacked into the cache without administrator rights, and loaded before
OpenCV. When that installation fails, only face detection is unavailable.

QEMU is never installed by MCAV. To use the [virtual machine module](vm.md), install QEMU yourself and make sure it is
on the `PATH`.

## Native Access Warnings

Java 24 and newer print a warning the first time a library loads native code. The warning is harmless. To hide it,
start the JVM with the following argument:

```bash
--enable-native-access=ALL-UNNAMED
```
