# What is MCAV?

![Banner](https://www.bisecthosting.com/images/CF/MCAV/MP_MCAV_Header.webp)

## Java Multimedia Framework

MCAV (pronounced *EM CAV*) is an incredibly powerful multimedia library for [Java](https://www.java.com/en/), serving
as the successor of EzMediaCore2. MCAV utilizes several low-level libraries like [FFmpeg](https://ffmpeg.org/),
[OpenCV](https://opencv.org/), and LibVLC (from [VLC media player](https://www.videolan.org/vlc/)) to provide a seamless
playback experience for developers and
users. Designed for pure performance and compatability, MCAV isn't just a YouTube player, but a robust player for
live-streams, local files, and even a web browser.

MCAV is not a library just for Java developers but also a great library to integrate Minecraft plugins with. While you
can use MCAV in any Java project, there's also a Minecraft-specific module that provides useful features allowing you
to play back videos like the following. For Minecraft, MCAV even comes with its own video codec,
[MCV2](mcv2/why.md), which shows sharp, full-colour video on a wall of maps to players with an unmodified client.

<iframe width="560" height="315" src="https://www.youtube.com/embed/ifs0GiAtqIs?si=qxLjZLfNv3W8tJpz" frameborder="0" allow="accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture" allowfullscreen></iframe>

---

## What's Inside

| Module | What it does |
|---|---|
| `mcav-common` | The core library: the FFmpeg, VLC, OpenCV and capture device [players](library/player.md), [pipelines](library/pipeline.md) and [filters](library/filters.md), dithering, [yt-dlp](library/yt-dlp.md), and audio and FFmpeg utilities |
| `mcav-bukkit` | Video and images in Minecraft: on [maps](bukkit/map.md), blocks, entities, the scoreboard and chat, [audio resource packs](bukkit/resourcepack.md), and [MCV2](bukkit/mcv2.md) |
| `mcav-browser` | Web pages and their sound through [JCEF](library/browser.md), an embedded Chromium, with no JVM options |
| `mcav-vm`, `mcav-vnc` | [Virtual machines](library/vm.md) with QEMU, with their sound, and [VNC desktops](library/vnc.md) |
| `mcav-http`, `mcav-jda`, `mcav-svc` | Audio to [web browsers](library/http.md), [Discord](library/jda.md) and [Simple Voice Chat](library/voice.md) |
| `mcav-lwjgl` | Video in [OpenGL textures](library/lwjgl.md) |
| `mcav-installer` | Downloads the modules at run time for [plugins](library/installer.md) |
| `sandbox:plugin` | The [MCAV plugin](plugin/plugin.md) for Paper, which uses all of the above |

MCAV requires Java 25, and runs on Windows (x86-64), macOS (x86-64 and Apple silicon) and Linux (x86-64 and ARM64),
for which FFmpeg and OpenCV are bundled; the [prerequisites](library/prerequisites.md) say what each optional feature
needs. The plugin and `mcav-bukkit` run on Paper 26.3, which has only alpha builds as of 2026-09-27; MCAV is built and
tested against build 49.
