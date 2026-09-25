# Commands

MCAV has many commands that you can use to interact with the library. This page will document all the commands,
including each argument and its purpose. If you want to see the commands in-game, you can use the `/mcav help` command
to get a tree of all the commands available to you.

## Permissions

Permissions for each command are very simple. It's just `mcav.command.<command>`. For example, the permission for the
`/mcav screen` is just `mcav.command.screen`. You can use a permissions plugin like [LuckPerms](https://luckperms.net/),
which will tab-complete the permission for you.

```{important}
`mcav.browser.interact` and `mcav.vm.interact` do not only switch chat input on: they are also what lets a player
click a browser or a virtual machine by clicking its map screen. The screen stands in the world, so anyone can reach
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

---

## Video Commands

```{note}
If you set the video player to be `FFMPEG`, you can also specify directly a format and input to use. For example, you can
stream OBS output by setting the `mrl` argument to be `dshow||video=OBS Virtual Camera` on Windows.
```

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

| **Command**                        | `/mcav video hologram set`                               |
|------------------------------------|----------------------------------------------------------|
| **Usage**                          | `/mcav video hologram set <location>`                    |
| **Permission**                     | `mcav.command.hologram.set`                              |
| **Description**                    | Sets the location for displaying hologram video content. |
| **Arguments**                      |                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`location` | The location in the World to display the hologram        |

---

| **Command**     | `/mcav video hologram disable`                   |
|-----------------|--------------------------------------------------|
| **Usage**       | `/mcav video hologram disable`                   |
| **Permission**  | `mcav.command.hologram.disable`                  |
| **Description** | Disables the hologram display for video content. |
| **Arguments**   | None                                             |

---

| **Command**                               | `/mcav video block`                                                                                      |
|-------------------------------------------|----------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video block <playerSelector> <playerType> <audioType> <videoResolution> <location> <flags> <mrl>` |
| **Permission**                            | `mcav.command.video.block`                                                                               |
| **Description**                           | Displays a video in blocks.                                                                              |
| **Arguments**                             |                                                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location in the World to display the video                                                           |
| &nbsp;&nbsp;&nbsp;&nbsp;`flags`           | Additional flags if the media will be parsed by yt-dlp (in format --yt-dlp{arg1=...,arg2,etc}; see [which options are accepted](#yt-dlp-options) |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                         |

---

| **Command**                               | `/mcav video chat`                                                                                       |
|-------------------------------------------|----------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video chat <playerSelector> <playerType> <audioType> <videoResolution> <character> <flags> <mrl>` |
| **Permission**                            | `mcav.command.video.chat`                                                                                |
| **Description**                           | Displays a video in chat.                                                                                |
| **Arguments**                             |                                                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the video in chat                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`flags`           | Additional flags if the media will be parsed by yt-dlp (in format --yt-dlp{arg1=...,arg2,etc}; see [which options are accepted](#yt-dlp-options) |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                         |

---

| **Command**                               | `/mcav video entity`                                                                                                  |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video entity <playerSelector> <playerType> <audioType> <videoResolution> <character> <location> <flags> <mrl>` |
| **Permission**                            | `mcav.command.video.entity`                                                                                           |
| **Description**                           | Displays a video as a TextDisplay entity.                                                                             |
| **Arguments**                             |                                                                                                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the video                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location where to display the video entity                                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`flags`           | Additional flags if the media will be parsed by yt-dlp (in format --yt-dlp{arg1=...,arg2,etc}; see [which options are accepted](#yt-dlp-options) |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                                      |

---

| **Command**                                  | `/mcav video map`                                                                                                                          |
|----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                                    | `/mcav video map <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <flags> <mrl>` |
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
| &nbsp;&nbsp;&nbsp;&nbsp;`flags`              | Additional flags if the media will be parsed by yt-dlp (in format --yt-dlp{arg1=...,arg2,etc}; see [which options are accepted](#yt-dlp-options) |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`                | The Media Resource Locator pointing to the video                                                                                           |

---

| **Command**                               | `/mcav video scoreboard`                                                                                       |
|-------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video scoreboard <playerSelector> <playerType> <audioType> <videoResolution> <character> <flags> <mrl>` |
| **Permission**                            | `mcav.command.video.scoreboard`                                                                                |
| **Description**                           | Displays a video in a scoreboard.                                                                              |
| **Arguments**                             |                                                                                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the video in the scoreboard                                                 |
| &nbsp;&nbsp;&nbsp;&nbsp;`flags`           | Additional flags if the media will be parsed by yt-dlp (in format --yt-dlp{arg1=...,arg2,etc}; see [which options are accepted](#yt-dlp-options) |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                               |

---

## Browser Commands

| **Command**     | `/mcav browser interact`                                                                                            |
|-----------------|---------------------------------------------------------------------------------------------------------------------|
| **Usage**       | `/mcav browser interact`                                                                                            |
| **Permission**  | `mcav.browser.interact`                                                                                             |
| **Description** | Activates browser interaction mode for the player. This allows players to send text and key input into the browser. |
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
| **Usage**                                    | `/mcav browser create <playerSelector> <browserResolution> <quality> <nth> <blockDimensions> <mapId> <ditheringAlgorithm> <url>` |
| **Permission**                               | `mcav.command.browser.create`                                                                                                    |
| **Description**                              | Creates and displays a browser on a map.                                                                                         |
| **Arguments**                                |                                                                                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`     | A selector for the players that can see the browser                                                                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`browserResolution`  | A resolution in width×height format (example, 1280x720)                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`quality`            | Quality setting for the browser from 1 to 100, where 100 denotes higher quality                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`nth`                | How often frame snapshots are taken (1 means high frame rate, 2 means take a screenshot every other frame, etc)                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions`    | The dimensions of the map blocks                                                                                                 |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set in `/mcav screen` to create the map screen                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`ditheringAlgorithm` | The algorithm used for dithering the browser. Use FILTER_LITE for best results                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`url`                | The URL of the webpage to display. **Must be the full URL**.                                                                     |

---

## Virtual Machine Commands

```{warning}
You must have QEMU installed and configured to use these commands.
```

| **Command**     | `/mcav vm interact`                                                                                                      |
|-----------------|--------------------------------------------------------------------------------------------------------------------------|
| **Usage**       | `/mcav vm interact`                                                                                                      |
| **Permission**  | `mcav.vm.interact`                                                                                                       |
| **Description** | Activates VM interaction mode for the player. This allows players to send text and mouse input into the virtual machine. |
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
| **Usage**                                    | `/mcav vm create <playerSelector> <vmResolution> <targetFps> <blockDimensions> <mapId> <ditheringAlgorithm> <architecture> <flags>` |
| **Permission**                               | `mcav.command.vm.create`                                                                                                            |
| **Description**                              | Creates and displays a virtual machine on a map.                                                                                    |
| **Arguments**                                |                                                                                                                                     |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`     | A selector for the players that can see the VM                                                                                      |
| &nbsp;&nbsp;&nbsp;&nbsp;`vmResolution`       | A resolution in width×height format (example, 1280x720)                                                                             |
| &nbsp;&nbsp;&nbsp;&nbsp;`targetFps`          | Target frames per second for the VM (1 to 240)                                                                                      |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions`    | The dimensions of the map blocks                                                                                                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set in `/mcav screen` to create the map screen                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`ditheringAlgorithm` | The algorithm used for dithering the VM display. Use FILTER_LITE for best results                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`architecture`       | The CPU architecture to use for the VM                                                                                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`flags`              | Additional flags and options to pass to the QEMU VM (for example, ISO files, boot drives, memory); see [which options are accepted](#qemu-options) |

---

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

## QEMU options

`/mcav vm create` passes its `flags` to QEMU. QEMU can read and write any file of the server, load a plugin library,
share a folder of the host with the guest and publish its display on the network, so only these options are accepted:

| Kind | Options |
|---|---|
| Disk images | `-cdrom`, `-drive` (with `file=`), `-hda`, `-hdb`, `-hdc`, `-hdd`, `-fda`, `-fdb` |
| Hardware | `-m`, `-smp`, `-cpu`, `-machine`, `-accel`, `-boot`, `-name`, `-k`, `-vga`, `-rtc` |
| Switches | `-snapshot`, `-no-reboot`, `-no-hpet`, `-no-fd-bootchk`, `-enable-kvm`, `-usb` |

A disk image must be a file of the plugin's `iso` folder, named without its folder, such as
`-cdrom "alpine linux.iso"`. Put your images there, or link them in; nothing else on the server can be booted. The
display of the guest always stays on the loopback address the plugin chose for it, and the guest keeps the user-mode
network QEMU gives it by default.

## Image Commands

| **Command**     | `/mcav image release`                                     |
|-----------------|-----------------------------------------------------------|
| **Usage**       | `/mcav image release`                                     |
| **Permission**  | `mcav.command.image.release`                              |
| **Description** | Releases/removes the image that was previously displayed. |
| **Arguments**   | None                                                      |

---

| **Command**                               | `/mcav image block`                                                     |
|-------------------------------------------|-------------------------------------------------------------------------|
| **Usage**                                 | `/mcav image block <playerSelector> <imageResolution> <location> <mrl>` |
| **Permission**                            | `mcav.command.image.block`                                              |
| **Description**                           | Displays an image in blocks.                                            |
| **Arguments**                             |                                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the image                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`imageResolution` | A resolution in width×height format (example, 640x640)                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location in the World to display the image                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the image                        |

---

| **Command**                               | `/mcav image chat`                                                      |
|-------------------------------------------|-------------------------------------------------------------------------|
| **Usage**                                 | `/mcav image chat <playerSelector> <imageResolution> <character> <mrl>` |
| **Permission**                            | `mcav.command.image.chat`                                               |
| **Description**                           | Displays an image in chat.                                              |
| **Arguments**                             |                                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the image                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`imageResolution` | A resolution in width×height format (example, 640x640)                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the image in chat                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the image                        |

---

| **Command**                               | `/mcav image entity`                                                                 |
|-------------------------------------------|--------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav image entity <playerSelector> <imageResolution> <character> <location> <mrl>` |
| **Permission**                            | `mcav.command.image.entity`                                                          |
| **Description**                           | Displays an image as a TextDisplay entity.                                           |
| **Arguments**                             |                                                                                      |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the image                                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`imageResolution` | A resolution in width×height format (example, 640x640)                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the image                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location where to display the image entity                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the image                                     |

---

| **Command**                                  | `/mcav image map`                                                                                         |
|----------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| **Usage**                                    | `/mcav image map <playerSelector> <imageResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <mrl>` |
| **Permission**                               | `mcav.command.image.map`                                                                                  |
| **Description**                              | Displays an image on a map screen.                                                                        |
| **Arguments**                                |                                                                                                           |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`     | A selector for the players that can see the image                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`imageResolution`    | A resolution in width×height format (example, 640x640)                                                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions`    | The dimensions of the map blocks                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set in `/mcav screen` to create the map screen        |
| &nbsp;&nbsp;&nbsp;&nbsp;`ditheringAlgorithm` | The algorithm used for dithering the image. Use FILTER_LITE for best results                              |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`                | The Media Resource Locator pointing to the image                                                          |

---

| **Command**                               | `/mcav image scoreboard`                                                      |
|-------------------------------------------|-------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav image scoreboard <playerSelector> <imageResolution> <character> <mrl>` |
| **Permission**                            | `mcav.command.image.scoreboard`                                               |
| **Description**                           | Displays an image in a scoreboard.                                            |
| **Arguments**                             |                                                                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the image                             |
| &nbsp;&nbsp;&nbsp;&nbsp;`imageResolution` | A resolution in width×height format (example, 640x640)                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the image in the scoreboard                |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the image                              |
