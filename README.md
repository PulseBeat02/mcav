[![CodeFactor](https://www.codefactor.io/repository/github/pulsebeat02/mcav/badge)](https://www.codefactor.io/repository/github/pulsebeat02/mcav)
[![TeamCity Full Build Status](https://img.shields.io/teamcity/build/e/mcav?server=https%3A%2F%2Fci.brandonli.me)](https://ci.brandonli.me/project/mcav)
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
- **Testing Server**: `pulse.mcserver.us`

---

MCAV (pronounced *EM CAV*) is an incredibly powerful multimedia library and plugin for Java, serving as the successor of
EzMediaCore2. MCAV utilizes several low-level libraries like [FFmpeg](https://ffmpeg.org/), [OpenCV](https://opencv.org/), and LibVLC
(from [VLC media player](https://www.videolan.org/vlc/)) to provide a seamless playback experience for developers and
users. MCAV also is capable of rendering browsers using [Selenium](https://www.selenium.dev/) and [Playwright](https://playwright.dev/java/), or
even virtual machines using [QEMU](https://www.qemu.org/). All of this is supported within the library and plugin itself.

The plugin is an example demonstrating the power of the library. For media playback, it supports several thousands of
websites that can be listed [here](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md), some of which include
YouTube, Twitch, SoundCloud, CNN, you name it. You're also able to play local files, stream from IP cameras, screen-share 
using an OBS virtual camera, and much more. All of this combined with audio playback, which you can use a website to
stream audio to or a Discord bot to play audio in voice channels.

https://user-images.githubusercontent.com/40838203/132433665-a675fc35-e31f-4044-a960-ce46a8fb7df5.mp4

---

### Modules

Here is a list of all the modules that are included in MCAV

| Module           | Description                                                                                                                                                  |
|------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sandbox`        | A Paper plugin for Minecraft servers that utilizes all the features of MCAV.                                                                                 |
| `mcav-common`    | The core library for multimedia functionality.                                                                                                               |
| `mcav-bukkit`    | A Bukkit-specific module for Minecraft plugins.                                                                                                              |
| `mcav-installer` | A simple installer for installing and injecting required libraries across all different modules of MCAV.                                                     |
| `mcav-jda`       | A module integrating with the [Java Discord API](https://github.com/discord-jda/JDA) to play audio in Discord voice channels.                                |
| `mcav-http`      | A module with [Spring Boot](https://spring.io/) back-end and [Typescript](https://www.typescriptlang.org/) front-end to stream PCM audio to an HTTP website. |
| `mcav-vm`        | A module integrating with [QEMU](https://www.qemu.org/) to run virtual machines.                                                                             |
| `mcav-vnc`       | A module interacting with VNC servers to capture video and control remote desktops.                                                                          |
| `mcav-browser`   | A module using [Selenium](https://www.selenium.dev/) and [Playwright](https://playwright.dev/) to provide browser support.                                   |
| `mcav-lwjgl`     | A module using [LWJGL](https://www.lwjgl.org/) to provide OpenGL support for rendering video and images.                                                     |

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
| [VideoLAN/VLC](https://code.videolan.org/videolan/vlc) | [GPLv2](https://opensource.org/license/lgpl-2-0) (or later) |
| [FFmpeg/FFmpeg](https://git.ffmpeg.org/ffmpeg.git)     | [GPLv2](https://opensource.org/license/lgpl-2-0) (or later) |
| [OpenCV/OpenCV](https://github.com/opencv/opencv)      | [Apache 2](https://opensource.org/license/apache-2-0)       |
| [caprica/vlcj](https://github.com/caprica/vlcj)        | [GPLv3](https://opensource.org/license/lgpl-3-0)            |
| [bytedeco/javacv](https://github.com/bytedeco/javacv)  | [Apache 2](https://opensource.org/license/apache-2-0)       |
| [yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp)      | [Unlicense](https://opensource.org/license/unlicense)       | 
| [rkalla/imgscalr](https://github.com/rkalla/imgscalr)  | [Apache 2](https://opensource.org/license/apache-2-0)       |
| [Bukkit/Bukkit](https://github.com/Bukkit/Bukkit)      | [GPLv3](https://opensource.org/license/lgpl-3-0)            |

As a result of this, the MCAV library is licensed under the GPLv3 license shown [here](LICENSE).
The [Apache 2](https://opensource.org/license/apache-2-0) License is compatible with the
[GPLv3](https://opensource.org/license/lgpl-3-0) license, but not the [GPLv2](https://opensource.org/license/lgpl-2-0)
license. You should license your project under the [GPLv3](https://opensource.org/license/lgpl-3-0) license or any other
license that is compatible with the [GPLv3](https://opensource.org/license/lgpl-3-0) license.

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