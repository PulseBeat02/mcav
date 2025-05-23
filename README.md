[![CodeFactor](https://www.codefactor.io/repository/github/pulsebeat02/mcav/badge)](https://www.codefactor.io/repository/github/pulsebeat02/mcav)
[![GitHub Actions](https://github.com/PulseBeat02/mcav/actions/workflows/tagged-release.yml/badge.svg)](https://github.com/PulseBeat02/mcav/actions)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=PulseBeat02_mcav&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=PulseBeat02_mcav)

![Banner](https://www.bisecthosting.com/images/CF/MCAV/MP_MCAV_Header.webp)
![Sponsor](https://www.bisecthosting.com/images/CF/MCAV/MP_MCAV_Promo.webp)
![Description](https://www.bisecthosting.com/images/CF/MCAV/MP_MCAV_Description.webp)

## Developer

⚙️ PulseBeat02

- **Docs**: https://mcav.readthedocs.io/en/latest/intro.html
- **GitHub**: https://github.com/PulseBeat02/mcav
- **Support**: https://discord.gg/cUMB6kCsh6
- **Donate**: https://ko-fi.com/pulsebeat_02
- **Testing Server**: `pulse.mcserver.us`

---

MCAV (pronounced *EM CAV*) is an incredibly powerful multimedia library for Java, serving as the successor of
EzMediaCore2. MCAV utilizes several low-level libraries
like [FFmpeg](https://ffmpeg.org/), [OpenCV](https://opencv.org/), and LibVLC
(from [VLC media player](https://www.videolan.org/vlc/)) to provide a seamless playback experience for developers and
users. Designed for pure performance and compatability, MCAV isn't just a YouTube player, but a robust player for
live-streams, local files, and even a web browser.

MCAV is not a library just for Java developers, but also a great library to integrate Minecraft plugins with. While you
can use MCAV in any Java project, there's also a Minecraft-specific module that provides useful features allowing you
to playback videos like the following.

https://user-images.githubusercontent.com/40838203/132433665-a675fc35-e31f-4044-a960-ce46a8fb7df5.mp4

The `common` module is the core of MCAV, and contains all the common multimedia functionality. The `bukkit` module is
the Minecraft-specific module, providing useful features like dithering, and outputs like maps, entities, scoreboards,
and chat. There is also an `installer` module that provides a simple way to install the required libraries for MCAV, a
`jda` module that integrates with the [Java Discord API](https://github.com/discord-jda/JDA), and a `http` module that
integrates with [Javalin](https://github.com/javalin/javalin) to provide a simple way to stream videos over HTTP.

The `sandbox` plugin only supports Paper and forks of it! However, any other aspect of the library can be used in any
Java context.

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