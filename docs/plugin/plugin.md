# Installation

```{note}
The MCAV plugin is in active development and may contain bugs or incomplete features. As the name suggests, it is a
sandbox plugin that demonstrates what the MCAV library can do.
```

The MCAV plugin is a Paper plugin that displays images, videos, browsers, and virtual machines in Minecraft. It is
designed to be a fun and experimental plugin that showcases the capabilities of the MCAV library.

## Installing the Plugin

```{warning}
The MCAV plugin works on **Paper** servers only, not on **Spigot** or **Bukkit** servers. It requires Minecraft
**26.2** and Java 25; it will not load on any other version of Minecraft.
```

1) Download the latest JAR from the TeamCity CI page [here](https://ci.brandonli.me/repository/download/mcav/.lastFinished/mcav-sandbox-1.0.0-v26.2-all.jar).
2) Place the JAR file into the `plugins` folder of your server.
3) Start the server.

On the first start, the plugin downloads its libraries into the `libraries/mcav` folder of the server, which takes a
moment; later starts are much faster. Core file playback works on a headless server without a sound device. Optional
features can require system libraries, a browser, QEMU, or a display; see the [prerequisites](../library/prerequisites.md).
MCAV itself does not run an administrative package installer.

The first download is about 465 MiB. Most of it is the FFmpeg, OpenCV, and OpenBLAS native libraries, which the plugin
includes for the platforms a Paper server runs on: Linux (x86-64 and ARM64), macOS (Intel and Apple silicon), and
Windows (x86-64). Natives for Android, iOS, and 32-bit systems are left out. The browser commands download Chromium
separately, 136 to 165 MB depending on the platform, the first time a browser starts, and on Linux the libraries it
needs that the server lacks, about 13 MB; the browser needs no X server and nothing installed. Every file is checked against its
SHA-256 hash, and later starts reuse the downloaded files. When building the plugin yourself, the list of platforms is the `javacppPlatform` property in
`sandbox/plugin/gradle.properties`.

The server finishes starting without waiting for VLC and yt-dlp. When the server has no VLC, the plugin downloads it in
the background into the MCAV cache folder of the user running the server (`~/.mcav/cache`), without `sudo`; yt-dlp is
downloaded the same way. Players can join and use every other command, including the video commands with the
`FFMPEG` player, in the meantime. Until VLC is ready, video commands with the `VLC` player answer
"VLC is still being prepared, try again shortly or use FFMPEG", and commands given a web page such as a YouTube video
answer that yt-dlp is still being prepared. The console logs `VLC ready in <n> ms` once VLC can be used, or a warning
if it cannot be installed, after which VLC commands answer that VLC is not supported. Later starts find the downloaded
copies at once.

If the server does not run Minecraft 26.2, the plugin logs one error line and disables itself, for example:

```text
MCAV cannot be enabled: MCAV only supports Minecraft 26.2, but the server is running 26.1! (run MCAV on a Minecraft 26.2 server)
```

The same happens when Simple Voice Chat audio is enabled but the Simple Voice Chat plugin is missing (see the
[configuration](config.md)). Any other failure during the start is reported by Paper with a stack trace, and the
plugin is disabled.

```{warning}
To use the virtual machine commands, install QEMU from your package manager or from the
[official QEMU website](https://www.qemu.org/download/) and make sure it is on the `PATH` of the server.
```
