# Videos and Images

How the plugin plays media, and what each choice in the `/mcav video` and `/mcav image` commands does. The syntax of
every command is on the [commands](commands.md) page.

## Where the picture goes

| Display | Command | What the players see | MCV2 |
|---|---|---|---|
| A wall of maps | `/mcav video map`, `/mcav image map` | The picture on item frames of maps, built with `/mcav screen` | yes, with `--codec mcv2` |
| Blocks | `video block`, `image block` | Colored blocks in the world | no |
| Text display entities | `video entity`, `image entity` | Colored characters on an entity | no |
| The scoreboard | `video scoreboard`, `image scoreboard` | Colored characters in the sidebar | no |
| Chat | `video chat`, `image chat` | Colored characters in chat | no |

`/mcav video hologram set <location>` adds a floating hologram with the title, uploader and a progress bar of every
video started afterwards; `/mcav video hologram disable` turns it off again.

A wall of maps shows 128x128 pixels per map, dithered to the map palette, or, with MCV2, a video of the resolution you
choose decoded by a resource pack ([MCV2 on Maps](../bukkit/mcv2.md)). MCAV does not dither the other displays; the
block, entity and scoreboard displays update at most 20 times a second, once per server tick, and the chat display sends
every frame as a new message as it arrives.

Dithering. `FILTER_LITE` is the recommended default, `NEAREST_COLOR` keeps text and desktops sharp, and
`FLOYD_STEINBERG_TEMPORAL` keeps unchanged areas identical from frame to frame, so the wall sends less. A wall of maps
sends only the parts of each map that changed, 128 KiB per frame and viewer on average, and every frame whole or not at
all; a big picture that changes everywhere, such as 1080p video, needs far more than that, so the wall skips frames and
shows far fewer of them ([why MCV2 exists](../mcv2.md#part-4-why-minecraft-maps-need-their-own-codec)).

## Players

| `playerType` | What it plays | Seek | Speed and loop |
|---|---|---|---|
| `FFMPEG` | Files, URLs, streams and raw FFmpeg inputs with the FFmpeg bundled in MCAV | to a time, or back and forth from where it plays | yes, for files |
| `VLC` | The same with VLC, which the plugin downloads in the background on the first start of a server without one | to a time from the start | no |
| `DEVICE` | A camera or capture card of the server, by the number `/mcav video devices` lists | no | no |

The `mrl` is an absolute file path, a URL, a device number for `DEVICE`, or a raw FFmpeg input written as
`format||input`, such as `dshow||video=OBS Virtual Camera` on Windows. MCAV resolves a web page, such as a YouTube
video, with yt-dlp first; the `ytdlpOptions` argument passes the options that choose which stream of a page plays, and
`""` passes none ([accepted options](commands.md#yt-dlp-options)). Put a path with spaces in double quotes.

```{warning}
A camera, a capture card or a raw FFmpeg input reaches the server's own devices: its cameras, its microphones and its
screen. The `DEVICE` player and raw inputs need the permission `mcav.command.video.device`, which only operators have
until you grant it.
```

## Sound

| `audioType` | Where the sound plays | Set up in |
|---|---|---|
| `NONE` | Nowhere | - |
| `HTTP_SERVER` | An audio web page the players open in a browser | `http-server` of the [configuration](config.md) |
| `DISCORD_BOT` | A Discord voice channel | `discord-bot` |
| `SIMPLE_VOICE_CHAT` | Voice chat, played from the position of each viewer and heard within 32 blocks of them | `simple-voice-chat`, and the Simple Voice Chat plugin |

One source plays through the audio outputs at a time: a video, a browser or a virtual machine that starts takes them
over from the one that played before, and gives them back to it when you release it, if that one still plays.

## Controlling a video

| Command | What it does |
|---|---|
| `/mcav video pause`, `resume`, `release` | Pauses, resumes, and stops the video, clearing its display |
| `/mcav video seek 1:30` | Jumps to 1:30 from the start; `+10` and `-1:00` jump from where it plays (the `FFMPEG` player). A live stream or a camera cannot jump |
| `/mcav video volume 50` | Half as loud, in every audio output, for this video and the videos started later; 0 to 200 |
| `/mcav video speed 1.5` | Plays a file one and a half times as fast, its sound higher to match; 0.5 to 2, `FFMPEG` only |
| `/mcav video loop true` | Plays a file again from its start whenever it ends, this video and the ones started later; `FFMPEG` only |

## Filters

Every video and image command takes `--filters "<chain>"`: filters applied to every frame, in the order written, before
MCAV dithers or encodes the picture, such as `--filters "grayscale,blur=3,text=Live"`. A chain has at most 8 filters and
256 characters, and the plugin bounds every argument, so the command's resolution bounds a filter's work. The [filter
table](commands.md#filters) lists all 17 and their arguments. An `overlay=<name>` draws a PNG of the plugin's
`plugins/MCAV/overlays` folder, which only the server owner fills; a player names it, never a path or a URL.

The library has a few more filters than the plugin offers ([filters](../library/filters.md)). Left out on purpose: face
detection, which needs a classifier model the plugin does not ship and runs on every frame; resize and transpose, which
the resolution and `rotate` cover; blend, which needs a second picture; the other shapes; and the LWJGL texture filter.

## Cameras and capture cards

`/mcav video devices` lists the cameras and capture cards of the server by number and name; the `DEVICE` player then
plays one by its number, and only a number the list showed. On Linux the number is that of `/dev/video<number>`, with
the name the system gives the device; on Windows and macOS the list tries the numbers 0 to 7, which briefly opens every
device there is. A server in a container usually has no device at all, and the command says so.

## Why there is no LWJGL output

`mcav-lwjgl` streams video into an OpenGL texture of a desktop application, such as a game or a mod, which renders it in
its own window. A Minecraft server has no OpenGL context and no window, so nothing in the plugin could draw with it. Its
players see what the server sends them: maps, blocks, entities, the scoreboard and chat.

## If playback stutters

If playback pauses or arrives in bursts without an error, try a lower-resolution, lower-frame-rate H.264 file and
compare it with the original on the same screen: decoding a high-resolution AV1 source can fall behind on a busy server,
and the player drops frames that are more than 100 ms late. While frames keep coming too late, it still shows one
frame a second. When it drops most frames of ten seconds of video, the server log says
`Playback falls behind: <dropped> of the last <frames> frames came too late to show`, at most once a minute of video.
The playing message reports the player's state, not whether frames are arriving, and a still image sends no map
updates at all, so silence on the network alone does not mean a failure.
