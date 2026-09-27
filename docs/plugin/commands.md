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
| `mcav.command.video.chat`, `.block`, `.entity`, `.scoreboard`, `.map`, `.pause`, `.resume`, `.release` | the `/mcav video` commands of those names |
| `mcav.command.browser.create` | `/mcav browser create` |
| `mcav.browser.release` | `/mcav browser release` |
| `mcav.browser.interact` | `/mcav browser interact`, and clicking the screen of the browser |
| `mcav.command.vm.create` | `/mcav vm create` |
| `mcav.vm.release` | `/mcav vm release` |
| `mcav.vm.interact` | `/mcav vm interact`, and clicking the screen of the virtual machine |

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

The paste is public. Before the log goes up, the addresses of players, what players typed after the commands of other
plugins, secret-looking settings, and the user name and password, query and fragment of every web address are
replaced with `<redacted>`; the host and path of an address stay, since they are what a bug report is about.

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
| **Usage**                                    | `/mcav browser create <playerSelector> <browserResolution> <nth> <blockDimensions> <mapId> <ditheringAlgorithm> <audioType> <url>` |
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
| &nbsp;&nbsp;&nbsp;&nbsp;`url`                | The URL of the webpage to display. **Must be the full `http` or `https` URL**. Pages of the server's own network are refused unless `browser.allow-private-networks` is on. |

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
| **Usage**                                    | `/mcav vm create <playerSelector> <vmResolution> <targetFps> <blockDimensions> <mapId> <ditheringAlgorithm> <architecture> <audioType> <flags>` |
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
