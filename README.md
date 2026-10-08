[![CodeFactor](https://www.codefactor.io/repository/github/pulsebeat02/mcav/badge)](https://www.codefactor.io/repository/github/pulsebeat02/mcav)
[![TeamCity Full Build Status](https://img.shields.io/teamcity/build/s/mcav_Build?server=https%3A%2F%2Fci.brandonli.me
)](https://ci.brandonli.me/project/mcav)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=PulseBeat02_mcav&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=PulseBeat02_mcav)

![Banner](https://www.bisecthosting.com/images/CF/MCAV/MP_MCAV_Header.webp)
![Sponsor](https://www.bisecthosting.com/images/CF/MCAV/MP_MCAV_Promo.webp)
![Description](https://www.bisecthosting.com/images/CF/MCAV/MP_MCAV_Description.webp)

## Developer

<img align="right" src="developer.png" alt="My Image">

⚙️ PulseBeat02

- **Docs**: https://mcav.readthedocs.io/en/latest/intro.html
- **GitHub**: https://github.com/PulseBeat02/mcav
- **CI**: https://ci.brandonli.me/project/mcav
- **Support**: https://discord.gg/cUMB6kCsh6
- **Donate**: https://ko-fi.com/pulsebeat_02

---

MCAV (pronounced *EM CAV*) is an incredibly powerful multimedia library and plugin for Java, serving as the successor of
EzMediaCore2. MCAV utilizes several low-level libraries like [FFmpeg](https://ffmpeg.org/), [OpenCV](https://opencv.org/), and LibVLC
(from [VLC media player](https://www.videolan.org/vlc/)) to provide a seamless playback experience for developers and
users. MCAV also is capable of rendering web pages with an embedded Chromium through [JCEF](https://github.com/chromiumembedded/java-cef), or
even virtual machines using [QEMU](https://www.qemu.org/), each with their sound. All of this is supported within the library and plugin itself.
For Minecraft, MCAV comes with its own video codec, [MCV2](#mcv2), which shows sharp, full-colour video on a wall of maps
to players with an unmodified client.

The plugin is an example demonstrating the power of the library. For media playback, it supports several thousands of
websites that can be listed [here](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md), some of which include
YouTube, Twitch, SoundCloud, CNN, you name it. You're also able to play local files, stream from IP cameras and capture
cards, screen-share using an OBS virtual camera, show web pages, virtual machines and VNC desktops, and much more. All of
this combined with audio playback, which you can use a website to stream audio to, [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat),
or a Discord bot to play audio in voice channels. Please check the [documentation](https://mcav.readthedocs.io/en/latest/intro.html)
for more information.

[![Watch the video](https://img.youtube.com/vi/ifs0GiAtqIs/maxresdefault.jpg)](https://youtu.be/ifs0GiAtqIs)

Click to watch a demo video above.

---

### Requirements

| What | Needs |
|------|-------|
| Library and plugin | Java 25 or newer |
| Minecraft | Paper 26.3, for the plugin and `mcav-bukkit`. Paper 26.3 has only **alpha** builds as of 2026-09-27; MCAV is built and tested against build 49 |
| Platforms | Windows (x86-64), macOS (x86-64 and Apple silicon), and Linux (x86-64 and ARM64): FFmpeg and OpenCV are bundled for these, and their natives are extracted into the JavaCPP cache of the user |
| VLC (optional) | Nothing: when the system has none, VLC 3.0.24 is downloaded into the cache folder of the user on Windows and macOS, and on x86-64 Linux, for which VideoLAN publishes no build, a pinned AppImage of Arch Linux's VLC package; elsewhere the system's VLC is used |
| yt-dlp (optional) | Nothing: it is downloaded into the cache folder of the user, pinned and checked; its Linux builds need glibc |
| Web browser | Nothing: MCAV embeds Chromium through JCEF, so there is no Selenium, Playwright, ChromeDriver or installed browser. Chromium (about 136 to 163 MiB) is downloaded on the first browser start, and on Linux the libraries a server lacks (about 13 MB). No X server, no Xvfb and no JVM options; 64-bit Linux, Windows and macOS on x86-64 and ARM64 |
| Virtual machines | QEMU, installed by you and on the `PATH` |
| MCV2 | Players accept a resource pack the server offers; the server's CPU encodes, with native kernels for Linux, Windows and macOS on x86-64 and ARM64 and a Java fallback anywhere else |
| Sound in Minecraft | A port for the audio web page, a Discord bot, or the [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) plugin (2.6.24 for Minecraft 26.3) |

Core file playback works on headless servers without a display or sound device, and MCAV never runs an administrative
package installer. Other Unix systems, such as FreeBSD, are detected as well: MCAV downloads nothing there and uses the
VLC installed on the system, but the bundled FFmpeg and OpenCV natives only exist for the platforms above.

---

### MCV2

A wall of maps normally shows a dithered picture: 128 pixels per map, in the colours of the map palette. For moving
1080p video that needs far more than a server can send: the plugin caps it at 128 KiB per frame, about 10 Mbit/s per
viewer at 30 fps after Minecraft's compression, and the wall still falls behind the video. MCV2 is MCAV's own video
codec for Minecraft: the server encodes the video and sends it as the colours of a few hidden maps, and a resource pack
decodes it on the player's GPU, in full colour, at the resolution you choose. The client needs no mod.

| At 1080p, 30 fps | Rate on the wire, after Minecraft's compression | VMAF mean |
|------------------|-------------------------------------------------|-----------|
| Dithered maps, the default budget | 10.4 Mbit/s | 33.4: the wall never shows a whole frame |
| MCV2, its `DEFAULT` preset | 2.11 Mbit/s | 75.7 |
| MCV2, its `DEFAULT` preset, real Minecraft gameplay | 9.22 Mbit/s | 75.6 |

The first two rows are measured on a procedural Minecraft test clip, which flatters MCV2; the last on real gameplay.
Live sources (browsers, virtual machines, VNC desktops, streams, cameras, and video files by default) share a
configurable CPU budget. Throughput depends on the source, encoder preset and available CPU; 1080p30 is a measured
workload, not a guarantee for every six-core server. A screen that cannot keep up steps down on its own, or you can
pre-encode a file. Players without the pack keep the dithered maps. Turn it on with `--codec mcv2` on any command that
draws on a wall of maps, or with `mcv2.default-codec: mcv2` in `config.yml`. The
[MCV2 article](https://mcav.readthedocs.io/en/latest/mcv2.html) explains how it works, what it costs, and how it
compares with H.264, VP9 and AV1.

---

### Modules

Here is a list of all the modules that are included in MCAV

| Module           | Description                                                                                                                                                  |
|------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `mcav-plugin`    | A Paper 26.3 plugin for Minecraft servers that utilizes all the features of MCAV.                                                                            |
| `mcav-common`    | The core library for multimedia functionality: the FFmpeg, VLC, OpenCV and capture device players, pipelines and filters, dithering, yt-dlp, and audio and FFmpeg utilities. |
| `mcav-bukkit`    | A Bukkit-specific module for Minecraft plugins: video and images on maps, blocks, entities, the scoreboard and chat, audio resource packs, and the MCV2 codec. |
| `mcav-installer` | A simple installer for installing and injecting required libraries across all different modules of MCAV.                                                     |
| `mcav-jda`       | A module integrating with the [Java Discord API](https://github.com/discord-jda/JDA) to play audio in Discord voice channels.                                |
| `mcav-http`      | A module with [Spring Boot](https://spring.io/) back-end and [Typescript](https://www.typescriptlang.org/) front-end to stream PCM audio to an HTTP website. |
| `mcav-vm`        | A module integrating with [QEMU](https://www.qemu.org/) to run virtual machines, with their display and their sound.                                         |
| `mcav-vnc`       | A module interacting with VNC servers to capture video and control remote desktops.                                                                          |
| `mcav-browser`   | A module using [JCEF](https://github.com/chromiumembedded/java-cef), an embedded Chromium, to stream web pages and their sound, with no JVM options.         |
| `mcav-lwjgl`     | A module using [LWJGL](https://www.lwjgl.org/) to provide OpenGL support for rendering video and images.                                                     |
| `mcav-voicechat` | A module using [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) to serve audio.                                                            |
| `mcav-jcstress`  | Concurrency tests of MCAV on OpenJDK's [jcstress](https://github.com/openjdk/jcstress) harness; a test module, not published.                                 |

---

### Building from Source

```bash
git clone https://github.com/PulseBeat02/mcav.git
cd mcav
./gradlew build
```

The plugin jar is `mcav-plugin/build/libs/mcav-plugin-1.0.0-v26.3-all.jar`. Gradle itself runs on any JDK 17 or
newer; the Java 25 toolchain MCAV compiles with is downloaded by Gradle when the machine has none, and so is the
Node.js that the code formatter and the web page of `mcav-http` use. No credentials are needed, except to publish. The
project builds on any one of Windows, macOS or Linux: the tests that need another operating system, or a program the
machine lacks (VLC, QEMU, a display), skip themselves. `build` also enforces the coverage lint, on which the code those
tests would have run shows up as gaps, so on such a machine build with `-Pmcav.coverage=false`.
[CONTRIBUTING.md](CONTRIBUTING.md) describes the tests, the coverage lint, the property, fuzz and concurrency tests,
mutation testing and the end-to-end test of the plugin.

`build` also builds the documentation into `mcav-docs/build/html` (open `index.html`); `./gradlew :mcav-docs:build`
builds only the documentation. No Python is needed either: Gradle downloads uv, checks it against a pinned SHA-256,
and with it installs the pinned Python and the hash-locked packages of `mcav-docs/requirements.lock` into
`mcav-docs/build`.

---

### Contributing

MCAV is looking for contributors to help improve the library and plugin. We need
- Web Developers (Typescript, React, NextJS) to help improve the front-end of the HTTP module.
- Back-end Developers (Java, Spring Boot) to help improve the back-end of the HTTP module.
- Java Developers to help improve the core library.
- Bukkit Developers to help improve the Bukkit module and the sandbox plugin.
- Writers to help improve the documentation and tutorials.
- Testers to help test the library and plugin.
- Content Creators to help promote the library and plugin.
- And much more!

---

### Licensing

Please note that MCAV integrates several different libraries, each under different licenses based on what is
incorporated into the project. The following table lists the libraries used in MCAV, and their respective licenses.

| Library                                                | License                                                     |
|--------------------------------------------------------|-------------------------------------------------------------|
| [VideoLAN/VLC](https://code.videolan.org/videolan/vlc) | [GPLv2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html) (or later) |
| [FFmpeg/FFmpeg](https://git.ffmpeg.org/ffmpeg.git) | [LGPLv2.1+ or GPLv2+, depending on build options](https://ffmpeg.org/legal.html) |
| [OpenCV/OpenCV](https://github.com/opencv/opencv)      | [Apache 2](https://opensource.org/license/apache-2-0)       |
| [caprica/vlcj](https://github.com/caprica/vlcj)        | [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)            |
| [bytedeco/javacv](https://github.com/bytedeco/javacv)  | [Apache 2](https://opensource.org/license/apache-2-0)       |
| [yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp)      | [Unlicense](https://opensource.org/license/unlicense)       |
| [chromiumembedded/java-cef](https://github.com/chromiumembedded/java-cef) | [BSD 3-Clause](https://opensource.org/license/bsd-3-clause) |
| [jcefmaven/jcefmaven](https://github.com/jcefmaven/jcefmaven) | [Apache 2](https://opensource.org/license/apache-2-0)       |
| [Bukkit/Bukkit](https://github.com/Bukkit/Bukkit)      | [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)            |

As a result of this, the MCAV library is licensed under the GPLv3 license shown [here](LICENSE).
The [Apache 2](https://opensource.org/license/apache-2-0) License is compatible with the
[GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) license, but not the [GPLv2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)
license. You should license your project under the [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) license or any other
license that is compatible with the [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) license.

The third-party code that MCAV's jars bundle, with its licences, and what MCAV downloads while it runs, are listed in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), which every MCAV jar carries in `META-INF/`.

---

### Contributors / Acknowledgements

| Developer                                               | Contribution                                   |
|---------------------------------------------------------|------------------------------------------------|
| [BananaPuncher714](https://github.com/BananaPuncher714) | Original Inspiration                           |
| [Jetp250](https://github.com/jetp250)                   | Implemented Java Floyd-Steinberg dithering     |
| [Emilyy](https://github.com/emilyy-dev)                 | Assisted with implementation and testing       |
| [Conclure](https://github.com/Conclure)                 | Assisted with Maven to Gradle migration        |
| [itxfrosty](https://github.com/itxfrosty)               | Developed a Discord bot for music integration  |
| [Rouge_Ram](https://rogueram.xyz/index.html)            | Developed a Discord bot used in Discord Server |

| Sponsor        | Donation |
|----------------|----------|
| Vijay Pondini  | $10.00   |
| Matthew Holden | $6.00    |

---

### MCAV Projects

| Project                                                   | Description                            |
|-----------------------------------------------------------|----------------------------------------|
| [MakiDesktop](https://github.com/ayunami2000/MakiDesktop) | Controlling VNC through Minecraft Maps |
| [MakiScreen](https://github.com/makifoxgirl/MakiScreen)   | Streaming OBS onto Minecraft Maps      |

---

### Legacy Demo

https://user-images.githubusercontent.com/40838203/132433665-a675fc35-e31f-4044-a960-ce46a8fb7df5.mp4
