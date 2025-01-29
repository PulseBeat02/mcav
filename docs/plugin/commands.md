# MCAV Plugin Commands

MCAV has many commands that you can use to interact with the library. This page will document all the commands,
including each argument and its purpose. If you want to see the commands in-game, you can use the `/mcav help` command
to get a tree of all the commands available to you.

## Permissions

Permissions for each command are very simple. It's just `mcav.command.<command>`. For example, the permission for the
`/mcav screen` is just `mcav.command.screen`. You can use a permissions plugin like [LuckPerms](https://luckperms.net/),
which will tab-complete the permission for you.

## General Commands

| **Command**                       | `/mcav help`                                                                                                                               |
|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                         | `/mcav help [command]`                                                                                                                     |
| **Permission**                    | `mcav.command.help`                                                                                                                        |
| **Description**                   | This command will show you a tree of all the commands available to you. It also shows some basic information about what each command does. |
| **Arguments**                     |                                                                                                                                            |
| &nbsp;&nbsp;&nbsp;&nbsp;`command` | (optional): The command you want to get help for. If not provided, it will show the entire command tree.                                   |

---

| **Command**     | `/mcav screen`                                                                                      |
|-----------------|-----------------------------------------------------------------------------------------------------|
| **Usage**       | `/mcav screen`                                                                                      |
| **Permission**  | `mcav.command.screen`                                                                               |
| **Description** | Brings up a menu to build a new map screen. Use the block width and height to construct the screen. |
| **Arguments**   | None                                                                                                |

---

| **Command**     | `/mcav dump`                                                                                        |
|-----------------|-----------------------------------------------------------------------------------------------------|
| **Usage**       | `/mcav dump`                                                                                        |
| **Permission**  | `mcav.command.dump`                                                                                 |
| **Description** | Dumps your logs, system information, and other debugging information into a paste used for support. |
| **Arguments**   | None                                                                                                |

---

## Video Commands

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

| **Command**                               | `/mcav video block`                                                                              |
|-------------------------------------------|--------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video block <playerSelector> <playerType> <audioType> <videoResolution> <location> <mrl>` |
| **Permission**                            | `mcav.command.video.block`                                                                       |
| **Description**                           | Displays a video in blocks.                                                                      |
| **Arguments**                             |                                                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                           |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location in the World to display the video                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                 |

---

| **Command**                               | `/mcav video chat`                                                                               |
|-------------------------------------------|--------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video chat <playerSelector> <playerType> <audioType> <videoResolution> <character> <mrl>` |
| **Permission**                            | `mcav.command.video.chat`                                                                        |
| **Description**                           | Displays a video in chat.                                                                        |
| **Arguments**                             |                                                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                           |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the video in chat                                             |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                 |

---

| **Command**                               | `/mcav video entity`                                                                                          |
|-------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video entity <playerSelector> <playerType> <audioType> <videoResolution> <character> <location> <mrl>` |
| **Permission**                            | `mcav.command.video.entity`                                                                                   |
| **Description**                           | Displays a video as a TextDisplay entity.                                                                     |
| **Arguments**                             |                                                                                                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                             |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                               |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the video                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`location`        | The location where to display the video entity                                                                |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                              |

---

| **Command**                                  | `/mcav video map`                                                                                                                  |
|----------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                                    | `/mcav video map <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <mrl>` |
| **Permission**                               | `mcav.command.video.map`                                                                                                           |
| **Description**                              | Displays a video on a map screen.                                                                                                  |
| **Arguments**                                |                                                                                                                                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`     | A selector for the players that can see the video                                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`         | The type of video player to use                                                                                                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`          | The type of audio output to use                                                                                                    |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution`    | A resolution in width×height format (example, 640x360)                                                                             |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions`    | The dimensions of the map blocks                                                                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set in `/mcav screen` to create the map screen                                 |
| &nbsp;&nbsp;&nbsp;&nbsp;`ditheringAlgorithm` | The algorithm used for dithering the video. Use FILTER_LITE for best results                                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`                | The Media Resource Locator pointing to the video                                                                                   |

---

| **Command**                               | `/mcav video scoreboard`                                                                               |
|-------------------------------------------|--------------------------------------------------------------------------------------------------------|
| **Usage**                                 | `/mcav video scoreboard <playerSelector> <playerType> <audioType> <videoResolution> <character> <mrl>` |
| **Permission**                            | `mcav.command.video.scoreboard`                                                                        |
| **Description**                           | Displays a video in a scoreboard.                                                                      |
| **Arguments**                             |                                                                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`  | A selector for the players that can see the video                                                      |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerType`      | The type of video player to use                                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`audioType`       | The type of audio output to use                                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`videoResolution` | A resolution in width×height format (example, 640x360)                                                 |
| &nbsp;&nbsp;&nbsp;&nbsp;`character`       | The character to use for rendering the video in the scoreboard                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`mrl`             | The Media Resource Locator pointing to the video                                                       |

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
| &nbsp;&nbsp;&nbsp;&nbsp;`url`                | The URL of the webpage to display                                                                                                |

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

| **Command**                                  | `/mcav vm create`                                                                                                                        |
|----------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **Usage**                                    | `/mcav vm create <playerSelector> <browserResolution> <targetFps> <blockDimensions> <mapId> <ditheringAlgorithm> <architecture> <flags>` |
| **Permission**                               | `mcav.command.vm.create`                                                                                                                 |
| **Description**                              | Creates and displays a virtual machine on a map.                                                                                         |
| **Arguments**                                |                                                                                                                                          |
| &nbsp;&nbsp;&nbsp;&nbsp;`playerSelector`     | A selector for the players that can see the VM                                                                                           |
| &nbsp;&nbsp;&nbsp;&nbsp;`browserResolution`  | A resolution in width×height format (example, 1280x720)                                                                                  |
| &nbsp;&nbsp;&nbsp;&nbsp;`targetFps`          | Target frames per second for the VM (minimum value: 1)                                                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`blockDimensions`    | The dimensions of the map blocks                                                                                                         |
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set in `/mcav screen` to create the map screen                                       |
| &nbsp;&nbsp;&nbsp;&nbsp;`ditheringAlgorithm` | The algorithm used for dithering the VM display. Use FILTER_LITE for best results                                                        |
| &nbsp;&nbsp;&nbsp;&nbsp;`architecture`       | The CPU architecture to use for the VM                                                                                                   |
| &nbsp;&nbsp;&nbsp;&nbsp;`flags`              | Additional flags and options to pass to the QEMU VM (for example, ISO files, boot drives, memory, etc)                                   |

---

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
| &nbsp;&nbsp;&nbsp;&nbsp;`mapId`              | The ID of the map. This corresponds with the id you set int `/mcav screen` to create the map screen       |
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

