# Commands

MCAV has many commands that you can use to interact with the library. This page will document all the commands,
including each argument and its purpose. If you want to see the commands in-game, you can use the `/mcav help` command
to get a tree of all the commands available to you.

## Permissions

Every command checks a permission, and the plugin declares all of them, so a permissions plugin like
[LuckPerms](https://luckperms.net/) lists and tab-completes them. Every permission is for operators until it is
granted.

| Permission | Allows |
|---|---|
| `mcav.command.help` | `/mcav help` |
| `mcav.command.dump` | `/mcav dump` |
| `mcav.command.screen` | `/mcav screen` |
| `mcav.command.hologram.set`, `mcav.command.hologram.disable` | `/mcav video hologram set` and `disable` |
| `mcav.command.image.chat`, `.block`, `.entity`, `.scoreboard`, `.map`, `.release` | the `/mcav image` commands of those names |
| `mcav.command.video.chat`, `.block`, `.entity`, `.scoreboard`, `.map`, `.pause`, `.resume`, `.release`, `.seek`, `.volume`, `.speed`, `.loop` | the `/mcav video` commands of those names |
| `mcav.command.video.device` | `/mcav video devices`, the `DEVICE` player, and raw FFmpeg inputs (`format\|\|input`) in any video command |
| `mcav.command.browser.create` | `/mcav browser create` |
| `mcav.browser.release` | `/mcav browser release` |
| `mcav.browser.interact` | `/mcav browser interact`, and clicking the screen of the browser |
| `mcav.command.vm.create` | `/mcav vm create` |
| `mcav.vm.release` | `/mcav vm release` |
| `mcav.vm.interact` | `/mcav vm interact`, and clicking the screen of the virtual machine |
| `mcav.command.vnc.create` | `/mcav vnc create` |
| `mcav.vnc.release` | `/mcav vnc release` |
| `mcav.vnc.interact` | `/mcav vnc interact`, and clicking the screen of the desktop |
| `mcav.command.video.mcv2` | `/mcav video mcv2` |
| `mcav.command.mcv2.play` | `/mcav mcv2 play`, `stream` and `stop` |
| `mcav.command.mcv2.encode` | `/mcav mcv2 encode` and `cancel` |

```{important}
`mcav.browser.interact`, `mcav.vm.interact` and `mcav.vnc.interact` do not only switch chat input on: they are also
what lets a player click a browser, a virtual machine or a desktop by clicking its map screen. The screen stands in the world, so anyone can reach
it; without the permission a click does nothing, while the frames of the screen stay protected for everyone.
```

## General Commands

| **Command**                       | `/mcav help`                                                                                                                               |
|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                         | `/mcav help [query]`                                                                                                                       |
| **Permission**                    | `mcav.command.help`                                                                                                                        |
| **Description**                   | This command will show you a tree of all the commands available to you. It also shows some basic information about what each command does. |
| **Arguments**                     |                                                                                                                                            |
| &nbsp;&nbsp;&nbsp;&nbsp;`query`   | (optional): The command you want to get help for. If not provided, it will show the entire command tree.                                   |

---

| **Command**                               | `/mcav screen <blockDimensions> <mapId> <material> <location>`                                      |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav screen <blockDimensions> <mapId> <material> <location>`                                      |
| **Permission**                            | `mcav.command.screen`                                                                               |
| **Description**                           | Brings up a menu to build a new map screen. Use the block width and height to construct the screen. |
| **Arguments**                             |                                                                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions` | The dimensions of the map blocks (e.g., 3x2), at most 64x64 so one frame of the wall still fits into a single packet bundle |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`           | The ID of the map to use. It may lie at most 4096 ids past the maps the world already has, because every map in between is created |
| &nbsp;&nbsp;&nbsp;&nbsp;`material`        | The material to use for the screen frame                                                            |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location in the World to build the screen                                                       |

---

| **Command**     | `/mcav dump`                                                                                        |
|-----------------|-----------------------------------------------------------------------------------------------------|
| **Usage**       | `/mcav dump`                                                                                        |
| **Permission**  | `mcav.command.dump`                                                                                 |
| **Description** | Dumps your logs, system information, and other debugging information into a paste used for support. |
| **Arguments**   | None                                                                                                |

The paste is public. Before the log goes up, the IP addresses of players are replaced with `<redacted-address>`, and
what players typed after the commands of other plugins, secret-looking settings, and the user name and password, query
and fragment of every web address with `<redacted>`; the host and path of an address stay, since they are what a bug
report is about.

---

## The codec of a wall of maps

A wall of maps shows its picture in one of two ways, chosen per command with the `--codec` flag, or by
`mcv2.default-codec` in `config.yml` when the command has none:

| Codec | What the players see | What it needs |
|---|---|---|
| `dither` (the default) | The picture reduced to the map palette by the dithering algorithm, 128×128 pixels a map | Nothing: every client shows maps |
| `mcv2` | The picture encoded with MCV2 and decoded by MCAV's resource pack at the resolution of the command, drawn over the wall | The MCV2 resource pack, which the viewers are asked to load |

`/mcav video map`, `/mcav image map`, `/mcav browser create` and `/mcav vnc create` take the flag after their last
argument, for example `/mcav browser create @a 1280x720 1 10x6 0 NEAREST_COLOR NONE https://example.com --codec mcv2`.
`/mcav vm create` takes it at the very end of its QEMU options, since those start with a dash too:
`... X86_64 NONE -m 2048M -cdrom "alpine linux.iso" --codec mcv2`. `/mcav video mcv2` always uses MCV2 and chooses its
encoder profile. The block, chat, entity and scoreboard outputs (and the information hologram) draw no maps, so the
codec does not apply to them.

With `mcv2`:

- The viewers are offered MCAV's MCV2 resource pack, one pack that decodes every MCV2 screen of the server. It is
  optional and replaces no other pack: a player who declines it sees the dithered maps and is not asked again while
  online. Until a player's client has loaded it, that player sees the dithered maps.
- Loading the pack reloads the client's resources, a hitch of a second or more. The pack changes only when a screen of
  a video size it does not decode yet starts, and a minute after a screen stopped, when its size leaves the pack (the
  pack itself, once no screen plays); a new screen of the same size within that minute reloads nothing. It decodes up
  to eight sizes; when eight screens play at once, the next one shows dithered maps and says so.
- A wall shown to `@a` keeps following the players online: a player who joins while it plays is offered the pack and
  watches too. Only players who can see the wall are sent its MCV2 stream: one farther away than their view distance,
  or in another world, is sent nothing until they come back. Any other selector means the players it matches when the command runs, who are offered the pack again
  when they join or change world.
- The picture is encoded as it arrives, with the default live preset `live`, on the encoder threads every MCV2 screen
  shares (`mcv2.encoder-threads`). When they cannot keep up, the screen steps down to faster presets, then fewer frames
  a second, then a smaller video, and at worst the dithered maps; whoever started it is told every step and why.
- An MCV2 wall can be at most 63 maps on a side, and its resolution at most 4096 pixels on a side, against 64 maps and
  8192 pixels for the dithered maps. A larger one is refused with "MCV2 plays on walls of at most 63 by 63 maps, and
  videos of at most 4096 by 4096 pixels." Commands that take `--codec mcv2` (video and image maps, and the browser,
  virtual machine and VNC screens) then show the dithered maps; `/mcav video mcv2` and `/mcav mcv2 play` and `stream`
  start nothing.
- A wall that no item frame holds (build it with `/mcav screen` first, with the same size and map id) shows the dithered
  maps, and the command says so. The server only knows the item frames of loaded chunks, so start a screen while a
  player is near its wall; from then on the screen keeps the chunks of its page frames loaded until it is released, so
  players who leave and come back, or join later, still see it.

## Video Commands

```{note}
If you set the video player to be `FFMPEG`, you can also specify directly a format and input to use. For example, you can
stream OBS output by setting the `mrl` argument to be `dshow||video=OBS Virtual Camera` on Windows. Such a raw FFmpeg
input can open the server's cameras, microphones and screen, so it needs the permission `mcav.command.video.device`, like
the `DEVICE` player.
```

A camera or capture card of the server plays with the `DEVICE` player and its number as the `mrl`. A device belongs
to the server, so the `DEVICE` player needs the permission `mcav.command.video.device`, and it plays only a number that
`/mcav video devices` listed: list the devices first. On Linux the number is that of `/dev/video<number>` and the list
shows the name the system gives the device; on Windows and macOS the list tries the numbers 0 to 7, which briefly opens
every device there is.

| **Command**     | `/mcav video release`                                     |
|-----------------|-----------------------------------------------------------|
| **Usage**       | `/mcav video release`                                     |
| **Permission**  | `mcav.command.video.release`                              |
| **Description** | Releases/removes the video that was previously displayed. |
| **Arguments**   | None                                                      |

---

| **Command**     | `/mcav video pause`                 |
|-----------------|-------------------------------------|
| **Usage**       | `/mcav video pause`                 |
| **Permission**  | `mcav.command.video.pause`          |
| **Description** | Pauses the currently playing video. |
| **Arguments**   | None                                |

---

| **Command**     | `/mcav video resume`                |
|-----------------|-------------------------------------|
| **Usage**       | `/mcav video resume`                |
| **Permission**  | `mcav.command.video.resume`         |
| **Description** | Resumes the currently paused video. |
| **Arguments**   | None                                |

---

| **Command**                        | `/mcav video seek`                                                                                                                                                                                    |
|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                          | `/mcav video seek <position>`                                                                                                                                                                         |
| **Permission**                     | `mcav.command.video.seek`                                                                                                                                                                             |
| **Description**                    | Jumps to a time of the video. A jump before the start goes to the start. A live stream or a camera cannot jump. The VLC player only jumps to a time from the start, because it does not tell where it is. |
| **Arguments**                      |                                                                                                                                                                                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`position` | A time from the start of the video, such as `90`, `1:30` or `1:02:03`, or with a sign a jump from where it plays, such as `+10` or `-1:00`; seconds may have up to three decimals, at most 99:59:59.999 |

---

| **Command**                       | `/mcav video volume`                                                                                                                         |
|-----------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                         | `/mcav video volume <percent>`                                                                                                               |
| **Permission**                    | `mcav.command.video.volume`                                                                                                                  |
| **Description**                   | Sets the volume of the video that plays and of the videos started later, in every audio output. Above 100, loud videos clip.                 |
| **Arguments**                     |                                                                                                                                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`percent` | The volume in percent of the video's own loudness, from 0 to 200                                                                             |

---

| **Command**                      | `/mcav video speed`                                                                                                                                                                                                   |
|----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                        | `/mcav video speed <factor>`                                                                                                                                                                                          |
| **Permission**                   | `mcav.command.video.speed`                                                                                                                                                                                            |
| **Description**                  | Plays the video file faster or slower, with its sound higher or lower to match, so picture and sound stay together. A jump keeps the speed; a new video starts at normal speed. A live stream or a camera plays at its own pace, and the VLC player does not change speed: use `FFMPEG`. |
| **Arguments**                    |                                                                                                                                                                                                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`factor` | The factor of the normal speed, from 0.5 to 2                                                                                                                                                                         |

---

| **Command**                       | `/mcav video loop`                                                                                                                                                                                  |
|-----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                         | `/mcav video loop <enabled>`                                                                                                                                                                        |
| **Permission**                    | `mcav.command.video.loop`                                                                                                                                                                           |
| **Description**                   | Makes the videos play again from their start whenever they end (checked twice a second), for the video that plays and those started later, or stop at their end. A live stream or a camera has no end to start from again, and the VLC player does not loop: use `FFMPEG`. |
| **Arguments**                     |                                                                                                                                                                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`enabled` | `true` to loop, `false` to stop at the end                                                                                                                                                         |

---

| **Command**     | `/mcav video devices`                                                                                                                  |
|-----------------|----------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**       | `/mcav video devices`                                                                                                                  |
| **Permission**  | `mcav.command.video.device`                                                                                                            |
| **Description** | Lists the cameras and capture cards of the server by number and name. The `DEVICE` player then plays one of them by its number.        |
| **Arguments**   | None                                                                                                                                   |

---

| **Command**                        | `/mcav video hologram set`                               |
|------------------------------------|----------------------------------------------------------|
| **Usage**                          | `/mcav video hologram set <location>`                    |
| **Permission**                     | `mcav.command.hologram.set`                              |
| **Description**                    | Makes every video started from now on show a floating hologram at the location with its title, uploader and a progress bar; a video that already plays keeps its hologram, or none. |
| **Arguments**                      |                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`location` | Where the hologram floats, such as `~ ~2 ~`                |

---

| **Command**     | `/mcav video hologram disable`                   |
|-----------------|--------------------------------------------------|
| **Usage**       | `/mcav video hologram disable`                   |
| **Permission**  | `mcav.command.hologram.disable`                  |
| **Description** | Videos started from now on show no hologram; the one shown goes away when the next video starts. |
| **Arguments**   | None                                             |

---

| **Command**                               | `/mcav video block`                                                                                      |
|-------------------------------------------|----------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video block <playerSelector> <playerType> <audioType> <videoResolution> <location> <ytDlpOptions> <mrl> [--filters "<chain>"]` |
| **Permission**                            | `mcav.command.video.block`                                                                               |
| **Description**                           | Displays a video in blocks.                                                                              |
| **Arguments**                             |                                                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location in the World to display the video                                                           |
| &nbsp;&nbsp;&nbsp;&nbsp;`ytDlpOptions`           | Additional options if the media will be parsed by yt-dlp (in format --yt-dlp{arg1=...,arg2,etc}; see [which options are accepted](#yt-dlp-options) |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`--filters`       | Optional: filters applied to every frame in order, in quotes, such as `"grayscale,blur=3"`; see [filters](#filters) |

---

| **Command**                               | `/mcav video chat`                                                                                       |
|-------------------------------------------|----------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video chat <playerSelector> <playerType> <audioType> <videoResolution> <character> <ytDlpOptions> <mrl> [--filters "<chain>"]` |
| **Permission**                            | `mcav.command.video.chat`                                                                                |
| **Description**                           | Displays a video in chat.                                                                                |
| **Arguments**                             |                                                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the video in chat                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`ytDlpOptions`           | Additional options if the media will be parsed by yt-dlp (in format --yt-dlp{arg1=...,arg2,etc}; see [which options are accepted](#yt-dlp-options) |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`--filters`       | Optional: filters applied to every frame in order, in quotes, such as `"grayscale,blur=3"`; see [filters](#filters) |

---

| **Command**                               | `/mcav video entity`                                                                                                  |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video entity <playerSelector> <playerType> <audioType> <videoResolution> <character> <location> <ytDlpOptions> <mrl> [--filters "<chain>"]` |
| **Permission**                            | `mcav.command.video.entity`                                                                                           |
| **Description**                           | Displays a video as a TextDisplay entity.                                                                             |
| **Arguments**                             |                                                                                                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the video                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location where to display the video entity                                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`ytDlpOptions`           | Additional options if the media will be parsed by yt-dlp (in format --yt-dlp{arg1=...,arg2,etc}; see [which options are accepted](#yt-dlp-options) |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                                      |
| &nbsp;&nbsp;&nbsp;&nbsp;`--filters`       | Optional: filters applied to every frame in order, in quotes, such as `"grayscale,blur=3"`; see [filters](#filters) |

---

| **Command**                                  | `/mcav video map`                                                                                                                          |
|----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                                    | `/mcav video map <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <ytDlpOptions> <mrl> [--codec dither\|mcv2] [--filters "<chain>"]` |
| **Permission**                               | `mcav.command.video.map`                                                                                                                   |
| **Description**                              | Displays a video on a map screen.                                                                                                          |
| **Arguments**                                |                                                                                                                                            |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`     | A selector for the players that can see the video                                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`         | The type of video player to use                                                                                                            |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`          | The type of audio output to use                                                                                                            |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution`    | A resolution in width×height format (example, 640x360)                                                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions`    | The dimensions of the map blocks                                                                                                           |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set in `/mcav screen` to create the map screen                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`ditheringAlgorithm` | The algorithm used for dithering the video. Use FILTER_LITE for best results                                                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`ytDlpOptions`       | Additional flags if the media will be parsed by yt-dlp (in format --yt-dlp{arg1=...,arg2,etc}; see [which options are accepted](#yt-dlp-options) |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`                | The Media Resource Locator pointing to the video                                                                                           |
| &nbsp;&nbsp;&nbsp;&nbsp;`--filters`       | Optional: filters applied to every frame in order, in quotes, such as `"grayscale,blur=3"`; see [filters](#filters) |
| &nbsp;&nbsp;&nbsp;&nbsp;`--codec`            | (optional): `dither` or `mcv2`, see [the codec of a wall of maps](#the-codec-of-a-wall-of-maps); `mcv2.default-codec` without it           |

---

| **Command**                                  | `/mcav video mcv2`                                                                                                                         |
|----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                                    | `/mcav video mcv2 <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions> <mapId> <profile> <ditheringAlgorithm> <ytDlpOptions> <mrl> [--filters "<chain>"]` |
| **Permission**                               | `mcav.command.video.mcv2`                                                                                                                  |
| **Description**                              | Plays a video on a map screen with MCV2, like `/mcav video map ... --codec mcv2`, with the encoder profile of your choice                  |
| **Arguments**                                |                                                                                                                                            |
| &nbsp;&nbsp;&nbsp;&nbsp;`profile`            | The encoder profile: `LIVE` (the default of `--codec mcv2`), `LIVE_ADAPTIVE`, `LIVE_FAST` (faster, more bandwidth), `LIVE_KEYFRAME` (for viewers whose clients draw fewer frames than the video has), or the slower `SHIP`, `LOW`, `KEYFRAME` and `INTRA` meant for encoding ahead of time |
|                                              | The other arguments are those of `/mcav video map`; the dithering algorithm is for the viewers without the pack                          |

---

| **Command**                               | `/mcav video scoreboard`                                                                                       |
|-------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video scoreboard <playerSelector> <playerType> <audioType> <videoResolution> <character> <ytDlpOptions> <mrl> [--filters "<chain>"]` |
| **Permission**                            | `mcav.command.video.scoreboard`                                                                                |
| **Description**                           | Displays a video in a scoreboard.                                                                              |
| **Arguments**                             |                                                                                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the video in the scoreboard                                                 |
| &nbsp;&nbsp;&nbsp;&nbsp;`ytDlpOptions`           | Additional options if the media will be parsed by yt-dlp (in format --yt-dlp{arg1=...,arg2,etc}; see [which options are accepted](#yt-dlp-options) |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`--filters`       | Optional: filters applied to every frame in order, in quotes, such as `"grayscale,blur=3"`; see [filters](#filters) |

---

## Browser Commands

| **Command**     | `/mcav browser interact`                                                                                            |
|-----------------|---------------------------------------------------------------------------------------------------------------------|
| **Usage**       | `/mcav browser interact`                                                                                            |
| **Permission**  | `mcav.browser.interact`                                                                                             |
| **Description** | Switches browser interaction mode on or off for the player: while it is on, their chat messages are typed into the browser instead of sent to chat. Clicking the screen needs no mode, only the permission. |
| **Arguments**   | None                                                                                                                |

---

| **Command**     | `/mcav browser release`                                     |
|-----------------|-------------------------------------------------------------|
| **Usage**       | `/mcav browser release`                                     |
| **Permission**  | `mcav.browser.release`                                      |
| **Description** | Releases/removes the browser that was previously displayed. |
| **Arguments**   | None                                                        |

---

| **Command**                                  | `/mcav browser create`                                                                                                           |
|----------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                                    | `/mcav browser create <playerSelector> <browserResolution> <nth> <blockDimensions> <mapId> <ditheringAlgorithm> <audioType> <url> [--codec dither\|mcv2]` |
| **Permission**                               | `mcav.command.browser.create`                                                                                                    |
| **Description**                              | Opens a web page in an embedded Chromium, which runs in a process of its own, shows it on a wall of maps and plays its sound in the chosen audio output. Only one browser runs at a time, and it takes the audio output over from a video or virtual machine until it is released. The first browser on a server downloads Chromium once (136 to 165 MB), and on Linux the libraries it needs that the server lacks (about 13 MB); nothing has to be installed. As in a desktop browser, a page plays sound only once a player clicked its screen or typed into it, unless `browser.autoplay-sound` is on. A server Chromium does not exist for, a failed download, a size beyond the limits, an address that is not `http` or `https`, or an audio output that is off or not ready, are answered with an error message. |
| **Arguments**                                |                                                                                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`     | A selector for the players that can see the browser                                                                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`browserResolution`  | A resolution in width×height format (example, 1280x720), at most 4096 on each side                                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`nth`                | How many painted frames make one streamed frame (1 streams every frame, 2 every other frame, up to 1000)                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions`    | The dimensions of the map blocks                                                                                                 |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set in `/mcav screen` to create the map screen                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`ditheringAlgorithm` | The algorithm used for dithering the browser. Use FILTER_LITE for best results                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`          | Where the sound of the page plays, as for the video commands (`NONE` keeps the page silent)                                      |
| &nbsp;&nbsp;&nbsp;&nbsp;`url`                | The URL of the webpage to display, the rest of the line up to `--codec`. **Must be the full `http` or `https` URL**. Pages of the server's own network are refused unless `browser.allow-private-networks` is on. |
| &nbsp;&nbsp;&nbsp;&nbsp;`--codec`            | (optional): `dither` or `mcv2`, see [the codec of a wall of maps](#the-codec-of-a-wall-of-maps); `mcv2.default-codec` without it |

---

## Virtual Machine Commands

```{warning}
You must have QEMU installed and configured to use these commands.
```

| **Command**     | `/mcav vm interact`                                                                                                      |
|-----------------|--------------------------------------------------------------------------------------------------------------------------|
| **Usage**       | `/mcav vm interact`                                                                                                      |
| **Permission**  | `mcav.vm.interact`                                                                                                       |
| **Description** | Switches VM interaction mode on or off for the player: while it is on, their chat messages are typed into the virtual machine instead of sent to chat. Clicking the screen needs no mode, only the permission. |
| **Arguments**   | None                                                                                                                     |

---

| **Command**     | `/mcav vm release`                                     |
|-----------------|--------------------------------------------------------|
| **Usage**       | `/mcav vm release`                                     |
| **Permission**  | `mcav.vm.release`                                      |
| **Description** | Releases/removes the VM that was previously displayed. |
| **Arguments**   | None                                                   |

---

| **Command**                                  | `/mcav vm create`                                                                                                                   |
|----------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                                    | `/mcav vm create <playerSelector> <vmResolution> <targetFps> <blockDimensions> <mapId> <ditheringAlgorithm> <architecture> <audioType> <flags> [--codec dither\|mcv2]` |
| **Permission**                               | `mcav.command.vm.create`                                                                                                            |
| **Description**                              | Boots a QEMU virtual machine, shows its display on a wall of maps and plays its sound in the chosen audio output. MCAV gives an `X86_64` machine its sound card itself (Intel HD Audio and the PC speaker) and holds its sound about 70 ms, so that it plays with the picture. Only one machine runs at a time, and it takes the audio output over from a video or browser until it is released. A server without QEMU, options that are not accepted, and an audio output that is off or not ready are answered with an error message. |
| **Arguments**                                |                                                                                                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`     | A selector for the players that can see the VM                                                                                      |
| &nbsp;&nbsp;&nbsp;&nbsp;`vmResolution`       | A resolution in width×height format (example, 1280x720)                                                                             |
| &nbsp;&nbsp;&nbsp;&nbsp;`targetFps`          | Target frames per second for the VM (1 to 240)                                                                                      |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions`    | The dimensions of the map blocks                                                                                                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set in `/mcav screen` to create the map screen                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`ditheringAlgorithm` | The algorithm used for dithering the VM display. Use FILTER_LITE for best results                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`architecture`       | The CPU architecture to use for the VM                                                                                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`          | Where the sound of the VM plays, as for the video commands (`NONE` keeps it silent); only `X86_64` PC and Q35 machines have sound, other architectures must choose `NONE`; a machine with sound takes the output over from a playing video    |
| &nbsp;&nbsp;&nbsp;&nbsp;`flags`              | Additional flags and options to pass to the QEMU VM (for example, ISO files, boot drives, memory); see [which options are accepted](#qemu-options). A `--codec dither` or `--codec mcv2` at the very end is not passed to QEMU: it chooses [the codec of the wall](#the-codec-of-a-wall-of-maps) |

---

## VNC Commands

`/mcav vnc create` connects the **server** to a VNC server and shows its desktop on a wall of maps. The server a player
names is reached from the Minecraft server, so only the servers an operator lists in `vnc.allowed-hosts` of
`config.yml` can be named, none by default, and the permissions are for operators until granted. A server's password is
written in that list, never in the command, since the server log keeps every command; MCAV never logs it, and
`/mcav dump` leaves it out. MCAV checks everything a VNC server sends before its VNC client reads it: sizes, lengths,
message types and encodings outside what the client supports close the connection instead of allocating memory.

| **Command**                                  | `/mcav vnc create`                                                                                                                  |
|----------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                                    | `/mcav vnc create <playerSelector> <vncResolution> <targetFps> <blockDimensions> <mapId> <ditheringAlgorithm> <server> [--codec dither\|mcv2]` |
| **Permission**                               | `mcav.command.vnc.create`                                                                                                           |
| **Description**                              | Connects to a listed VNC server and shows its desktop on a wall of maps; players click it and type into it like the browser         |
| **Arguments**                                |                                                                                                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`     | A selector for the players that can see the desktop                                                                                 |
| &nbsp;&nbsp;&nbsp;&nbsp;`vncResolution`      | The size the desktop is scaled to, in width×height format (example, 1280x720)                                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`targetFps`          | Frames per second streamed from the desktop (1 to 240)                                                                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions`    | The dimensions of the map blocks                                                                                                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set in `/mcav screen` to create the map screen                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`ditheringAlgorithm` | The algorithm used for dithering the desktop. `NEAREST_COLOR` keeps text sharp                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`server`             | The VNC server as `host:port`, or `[address]:port` for an IPv6 address, exactly as listed in `vnc.allowed-hosts`                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`--codec`            | (optional): `dither` or `mcv2`, see [the codec of a wall of maps](#the-codec-of-a-wall-of-maps); `mcv2.default-codec` without it    |

---

| **Command**     | `/mcav vnc interact`                                                                                                   |
|-----------------|------------------------------------------------------------------------------------------------------------------------|
| **Usage**       | `/mcav vnc interact`                                                                                                   |
| **Permission**  | `mcav.vnc.interact`                                                                                                    |
| **Description** | Toggles typing into the desktop: while it is on, what you write in the chat is typed into the desktop and not sent    |
| **Arguments**   | None                                                                                                                   |

---

| **Command**     | `/mcav vnc release`                                                              |
|-----------------|----------------------------------------------------------------------------------|
| **Usage**       | `/mcav vnc release`                                                              |
| **Permission**  | `mcav.vnc.release`                                                               |
| **Description** | Disconnects from the desktop and clears its maps for every viewer               |
| **Arguments**   | None                                                                             |

## MCV2 Commands

These commands play streams that are already MCV2-encoded, without a video player or an encoder: streams encoded ahead of
time with `/mcav mcv2 encode`, which a server too small to encode while a video plays can still show at full quality.
They have no sound. Stream files live in the plugin's `mcv2` folder; a name that leads out of it is refused.

| **Command**                               | `/mcav mcv2 encode`                                                                                    |
|-------------------------------------------|--------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav mcv2 encode <file> <output> <resolution> <profile>`                                            |
| **Permission**                            | `mcav.command.mcv2.encode`                                                                             |
| **Description**                           | Encodes a video file into a stream file ahead of time, on the encoder threads every MCV2 screen shares, telling you the progress every thirty seconds. One encode runs at a time |
| **Arguments**                             |                                                                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`file`            | The video file on the server                                                                           |
| &nbsp;&nbsp;&nbsp;&nbsp;`output`          | The stream file to write in the plugin's `mcv2` folder; an existing one is replaced when the encode ends |
| &nbsp;&nbsp;&nbsp;&nbsp;`resolution`      | The video size in width×height format (example, 1280x720)                                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`profile`         | The encoder profile, usually `SHIP`, the best quality for its bandwidth, which is far slower than real time |

---

| **Command**     | `/mcav mcv2 cancel`                   |
|-----------------|---------------------------------------|
| **Usage**       | `/mcav mcv2 cancel`                   |
| **Permission**  | `mcav.command.mcv2.encode`            |
| **Description** | Stops the encode that is running      |
| **Arguments**   | None                                  |

---

| **Command**                               | `/mcav mcv2 play` and `/mcav mcv2 stream`                                                              |
|-------------------------------------------|--------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav mcv2 play <playerSelector> <blockDimensions> <mapId> <ticks> <file>` or `/mcav mcv2 stream <playerSelector> <blockDimensions> <mapId> <fps> <file>` |
| **Permission**                            | `mcav.command.mcv2.play`                                                                               |
| **Description**                           | Plays a stream file on a wall of maps with MCV2, looping; `play` sends a frame every few server ticks, `stream` at a frame rate of its own. The screen takes a slot of the MCV2 pack like every MCV2 screen, and replaces the stream played before |
| **Arguments**                             |                                                                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`ticks` / `fps`   | The server ticks between frames (1 or more), or the frames a second (1 to 240)                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`file`            | The stream file in the plugin's `mcv2` folder                                                          |
|                                           | The other arguments are those of `/mcav video map`                                                     |

---

| **Command**     | `/mcav mcv2 stop`                                        |
|-----------------|----------------------------------------------------------|
| **Usage**       | `/mcav mcv2 stop`                                        |
| **Permission**  | `mcav.command.mcv2.play`                                 |
| **Description** | Stops the stream and gives its slot of the pack back    |
| **Arguments**   | None                                                     |

## Filters

The video and image commands take `--filters "<chain>"`: filters applied to every frame of a video, or to the image, in
the order written, before the picture is dithered or encoded with MCV2. A chain is filter names separated by commas,
each with its arguments after `=`, separated by colons, in quotes: `--filters "grayscale,blur=3,text=Live"`. A chain has
at most 8 filters and 256 characters. The pictures are filtered at the resolution of the command, so the work of a
filter is bounded by what the command shows; every argument is bounded too.

| Filter | Arguments | Effect |
|---|---|---|
| `grayscale` | none | shades of gray |
| `invert` | none | inverts every colour |
| `blur=<radius>` | 1 to 15 | Gaussian blur over a kernel twice the radius plus one wide |
| `bilateral=<diameter>` | 1 to 9 | blur that keeps edges (its work grows with the square of the diameter) |
| `threshold=<level>` | 0 to 255 | black below the level, white above |
| `luminance=<contrast>:<brightness>` | 0 to 3 (up to 3 decimals), -255 to 255 | contrast factor and brightness shift |
| `colormap=<name>` | `autumn`, `bone`, `jet`, `winter`, `rainbow`, `ocean`, `summer`, `spring`, `cool`, `hsv`, `pink`, `hot`, `parula`, `magma`, `inferno`, `plasma`, `viridis`, `cividis`, `twilight`, `turbo` | false colours of OpenCV's colour map |
| `tint=<rrggbb>[:<strength>]` | a hex colour, 0 to 100 percent (default 50) | blends the colour in |
| `flip=<h\|v\|hv>` | direction | mirrors horizontally, vertically or both |
| `rotate=<90\|180\|270>` | degrees clockwise | rotates the picture |
| `crop=<x>:<y>:<width>:<height>` | percent of the picture, the region inside it | zooms into the region, shown at the full size |
| `rectangle=<x>:<y>:<width>:<height>:<rrggbb>` | percent of the picture, a hex colour | draws the outline of a rectangle |
| `dilate=<size>`, `erode=<size>` | 1 to 15 | grows or shrinks bright areas |
| `text=<text>` | up to 32 letters, digits, spaces and `.!?'()+-` | writes the text in the top-left corner |
| `overlay=<name>` | the name of a PNG file of the plugin's `overlays` folder, without `.png` | draws the file over the top-left corner |
| `fps` | none, videos only | writes the frames shown per second |

An overlay is read only from `plugins/MCAV/overlays`, by a name of letters, digits, `-` and `_`: never a path or a URL
a player types, and a link in the folder is not followed. The file may be at most 4 MiB, and a picture larger than
1024 pixels on a side is scaled down to that. The server owner puts the overlays there.

The library has more filters than the plugin offers. Left out: face detection (it needs an OpenCV classifier model
the plugin does not ship, and runs a classifier over every frame); resize and transpose (the command's resolution
already sets the size, and `rotate` covers transposing); blend (it needs a second picture); circle, ellipse, line and
region fill (`rectangle` marks a region); zero (a black picture); and the LWJGL texture filter (`GLTextureFilter`),
which needs an OpenGL context that a server does not have. Dithering is not a filter here: it is how every wall of maps
without MCV2 shows its picture.

## yt-dlp options

`/mcav video …` passes the options inside `--yt-dlp{…}` to yt-dlp when it resolves a web page. yt-dlp can also write
files, run programs of its own and send credentials, none of which belongs in a chat command, so only the options that
choose *which stream of a page is played* are accepted:

| Kind | Options |
|---|---|
| Format | `format`, `format-sort`, `format-sort-force`, `no-format-sort-force`, `prefer-free-formats`, `no-prefer-free-formats`, `check-formats`, `check-all-formats`, `no-check-formats`, `video-multistreams`, `no-video-multistreams`, `audio-multistreams`, `no-audio-multistreams` |
| Playlist | `no-playlist`, `yes-playlist`, `playlist-items` |
| Filtering | `match-filter`, `no-match-filters`, `break-match-filters` |
| Region | `geo-bypass`, `no-geo-bypass`, `geo-bypass-country`, `geo-bypass-ip-block` |
| Network | `retries`, `extractor-retries`, `socket-timeout`, `source-address`, `add-header`, `user-agent`, `referer` |

A switch such as `no-playlist` is written on its own, an option with a value as `name=value`. An option outside the
list, a switch given a value, an option missing its value, or a value that starts with a dash is refused and the
command tells you which option it was.

The page chooses the addresses yt-dlp reports for its streams, so only `http` and `https` streams are played. A page
that names a stream of another kind, such as a `file:` of the server or a `tcp:` connection, does not start, and the
console says why.

## QEMU options

`/mcav vm create` passes its `flags` to QEMU. QEMU can read and write any file of the server, load a plugin library,
share a folder of the host with the guest and publish its display on the network, so only these options are accepted:

| Kind | Options |
|---|---|
| Disk images | `-cdrom`, `-drive` (with `file=`), `-hda`, `-hdb`, `-hdc`, `-hdd`, `-fda`, `-fdb` |
| Hardware | `-m`, `-smp`, `-cpu`, `-machine`, `-accel`, `-boot`, `-name`, `-k`, `-vga`, `-rtc`, each with the values of its kind only |
| Switches | `-snapshot`, `-no-reboot`, `-no-hpet`, `-no-fd-bootchk`, `-enable-kvm`, `-usb` |

The hardware options take machine types, accelerators, sizes, counts, CPU models and features, and their switches,
such as `-machine q35,accel=kvm,usb=on` or `-boot order=dc,menu=on`; the properties that make QEMU read or write a
file, such as `-machine dumpdtb=`, `firmware=` or `kernel=` and `-boot splash=`, are refused, and so is
`pcspk-audiodev=`, because the plugin routes the sound itself. A disk image must be a file of the plugin's `iso`
folder, named without its folder, such as `-cdrom "alpine linux.iso"`. Put your images there, or link them in; nothing else on the server can be booted. Besides
`file=`, a `-drive` takes only the properties that say how the drive is attached: `format` (`raw`, `qcow2`, `vmdk`,
`vdi`, `vhdx`, `vpc`), `if`, `media`, `index`, `bus`, `unit`, `id`, `serial`, `cache`, `aio`, `snapshot`, `readonly`,
`copy-on-read`, `discard`, `detect-zeroes`, `werror` and `rerror`, such as `-drive file=disk.img,format=raw,if=virtio`;
any other property is refused. A machine may have at most half of the memory of the server (or of its container),
and at least 512 MiB, since a guest can use all the memory it is given; a larger `-m` is refused. The
display of the guest always stays on the loopback address the plugin chose for it, and the guest keeps the user-mode
network QEMU gives it by default. In that network the address 10.0.2.2 is the server itself: a guest reaches every
service the server offers only on its loopback address, such as an RCON port, a database or an admin page, as a program
on the server would. Whoever types into the guest can use them, so on a server with such services do not create
machines for players who may not.

## Image Commands

An image that declares more than 8192 by 8192 pixels is refused before it is decoded, since decoding it could take
gigabytes of memory. Start the server with `-Dmcav.image.maxPixels=<pixels>` for another limit.

| **Command**     | `/mcav image release`                                     |
|-----------------|-----------------------------------------------------------|
| **Usage**       | `/mcav image release`                                     |
| **Permission**  | `mcav.command.image.release`                              |
| **Description** | Releases/removes the image that was previously displayed. |
| **Arguments**   | None                                                      |

---

| **Command**                               | `/mcav image block`                                                     |
|-------------------------------------------|-------------------------------------------------------------------------|
| **Usage**                                 | `/mcav image block <playerSelector> <imageResolution> <location> <mrl> [--filters "<chain>"]` |
| **Permission**                            | `mcav.command.image.block`                                              |
| **Description**                           | Displays an image in blocks.                                            |
| **Arguments**                             |                                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the image                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`imageResolution` | A resolution in width×height format (example, 640x640)                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location in the World to display the image                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the image                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`--filters`       | Optional: filters applied to the image in order, in quotes, such as `"grayscale,blur=3"`; see [filters](#filters) |

---

| **Command**                               | `/mcav image chat`                                                      |
|-------------------------------------------|-------------------------------------------------------------------------|
| **Usage**                                 | `/mcav image chat <playerSelector> <imageResolution> <character> <mrl> [--filters "<chain>"]` |
| **Permission**                            | `mcav.command.image.chat`                                               |
| **Description**                           | Displays an image in chat.                                              |
| **Arguments**                             |                                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the image                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`imageResolution` | A resolution in width×height format (example, 640x640)                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the image in chat                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the image                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`--filters`       | Optional: filters applied to the image in order, in quotes, such as `"grayscale,blur=3"`; see [filters](#filters) |

---

| **Command**                               | `/mcav image entity`                                                                 |
|-------------------------------------------|--------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav image entity <playerSelector> <imageResolution> <character> <location> <mrl> [--filters "<chain>"]` |
| **Permission**                            | `mcav.command.image.entity`                                                          |
| **Description**                           | Displays an image as a TextDisplay entity.                                           |
| **Arguments**                             |                                                                                      |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the image                                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`imageResolution` | A resolution in width×height format (example, 640x640)                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the image                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location where to display the image entity                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the image                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`--filters`       | Optional: filters applied to the image in order, in quotes, such as `"grayscale,blur=3"`; see [filters](#filters) |

---

| **Command**                                  | `/mcav image map`                                                                                         |
|----------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| **Usage**                                    | `/mcav image map <playerSelector> <imageResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <mrl> [--codec dither\|mcv2] [--filters "<chain>"]` |
| **Permission**                               | `mcav.command.image.map`                                                                                  |
| **Description**                              | Displays an image on a map screen.                                                                        |
| **Arguments**                                |                                                                                                           |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`     | A selector for the players that can see the image                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`imageResolution`    | A resolution in width×height format (example, 640x640)                                                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions`    | The dimensions of the map blocks                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set in `/mcav screen` to create the map screen        |
| &nbsp;&nbsp;&nbsp;&nbsp;`ditheringAlgorithm` | The algorithm used for dithering the image. Use FILTER_LITE for best results                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`                | The Media Resource Locator pointing to the image; the rest of the line up to `--codec`                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`--filters`       | Optional: filters applied to the image in order, in quotes, such as `"grayscale,blur=3"`; see [filters](#filters) |
| &nbsp;&nbsp;&nbsp;&nbsp;`--codec`            | (optional): `dither` or `mcv2`, see [the codec of a wall of maps](#the-codec-of-a-wall-of-maps); `mcv2.default-codec` without it |

---

| **Command**                               | `/mcav image scoreboard`                                                      |
|-------------------------------------------|-------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav image scoreboard <playerSelector> <imageResolution> <character> <mrl> [--filters "<chain>"]` |
| **Permission**                            | `mcav.command.image.scoreboard`                                               |
| **Description**                           | Displays an image in a scoreboard.                                            |
| **Arguments**                             |                                                                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the image                             |
| &nbsp;&nbsp;&nbsp;&nbsp;`imageResolution` | A resolution in width×height format (example, 640x640)                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the image in the scoreboard                |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the image                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`--filters`       | Optional: filters applied to the image in order, in quotes, such as `"grayscale,blur=3"`; see [filters](#filters) |
