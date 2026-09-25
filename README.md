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
even virtual machines using [QEMU](https://www.qemu.org/). All of this is supported within the library and plugin itself.

The plugin is an example demonstrating the power of the library. For media playback, it supports several thousands of
websites that can be listed [here](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md), some of which include
YouTube, Twitch, SoundCloud, CNN, you name it. You're also able to play local files, stream from IP cameras, screen-share
using an OBS virtual camera, and much more. All of this combined with audio playback, which you can use a website to
stream audio to, [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat), or a Discord bot to play audio in voice channels.

MCAV requires Java 25, and the plugin runs on Paper 26.2 only. MCAV bundles native libraries for Windows (x86-64), macOS
(x86-64 and Apple silicon), and Linux (x86-64 and ARM64). Core file playback works on headless servers without a display
or sound device. Optional features can require system libraries, a browser, QEMU, or a display; MCAV does not run an
administrative package installer. FFmpeg and OpenCV are bundled and their
JavaCV natives are extracted into the JavaCPP cache of the user, and yt-dlp and VLC are downloaded into the cache folder
of the user when they are missing; on Linux, VLC is downloaded on x86-64 only and is otherwise used from the system.
Other Unix systems, such as FreeBSD, are detected as well: MCAV downloads nothing there and uses the VLC installed on
the system, but the bundled FFmpeg and OpenCV natives only exist for the platforms above. QEMU for the virtual machine
module must already be installed; the browser module downloads its Chromium on first use and needs Xvfb on Linux. Please check the
[documentation](https://mcav.readthedocs.io/en/latest/intro.html) for more information.

[![Watch the video](https://img.youtube.com/vi/ifs0GiAtqIs/maxresdefault.jpg)](https://youtu.be/ifs0GiAtqIs)

Click to watch a demo video above.

---

### Modules

Here is a list of all the modules that are included in MCAV

| Module           | Description                                                                                                                                                  |
|------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sandbox:plugin` | A Paper 26.2 plugin for Minecraft servers that utilizes all the features of MCAV.                                                                            |
| `mcav-common`    | The core library for multimedia functionality.                                                                                                               |
| `mcav-bukkit`    | A Bukkit-specific module for Minecraft plugins.                                                                                                              |
| `mcav-installer` | A simple installer for installing and injecting required libraries across all different modules of MCAV.                                                     |
| `mcav-jda`       | A module integrating with the [Java Discord API](https://github.com/discord-jda/JDA) to play audio in Discord voice channels.                                |
| `mcav-http`      | A module with [Spring Boot](https://spring.io/) back-end and [Typescript](https://www.typescriptlang.org/) front-end to stream PCM audio to an HTTP website. |
| `mcav-vm`        | A module integrating with [QEMU](https://www.qemu.org/) to run virtual machines.                                                                             |
| `mcav-vnc`       | A module interacting with VNC servers to capture video and control remote desktops.                                                                          |
| `mcav-browser`   | A module using [JCEF](https://github.com/chromiumembedded/java-cef), an embedded Chromium, to stream web pages.                                              |
| `mcav-lwjgl`     | A module using [LWJGL](https://www.lwjgl.org/) to provide OpenGL support for rendering video and images.                                                     |
| `mcav-svc`       | A module using [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) to serve audio.                                                            |

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

For a curated list of issues to work on, please check out the [TODO](TODO.md) file.

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
| [rkalla/imgscalr](https://github.com/rkalla/imgscalr)  | [Apache 2](https://opensource.org/license/apache-2-0)       |
| [Bukkit/Bukkit](https://github.com/Bukkit/Bukkit)      | [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)            |

As a result of this, the MCAV library is licensed under the GPLv3 license shown [here](LICENSE).
The [Apache 2](https://opensource.org/license/apache-2-0) License is compatible with the
[GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) license, but not the [GPLv2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)
license. You should license your project under the [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) license or any other
license that is compatible with the [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) license.

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
