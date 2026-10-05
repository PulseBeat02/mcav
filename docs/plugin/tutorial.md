# Plugin Tutorial

If you need help with using this plugin, refer to this tutorial to guide you through a step-by-step process to set this
plugin up.

---

**Step 1: Install the Plugin**

If you haven't already, follow the installation instructions in the [Installation Guide](./plugin.md#installing-the-plugin) to
install the MCAV plugin on your Minecraft server. Make sure you are using a compatible version of Paper (26.3).


---

**Step 2: Configure the Plugin**

Run the plugin once to generate the default configuration files. You can find the main configuration file in
`plugins/MCAV/config.yml`. Open this file in your favorite text editor and adjust the settings as needed. If you would
like audio support for videos, follow one or both of the ways to set up audio:

**Option 1**: HTTP Audio Streaming
1) Port-forward another port besides your current Minecraft server port.
2) Go into the `config.yml` and set the `port` under the `http-server` section to the port you just forwarded. Make sure
to set the `host-name` to your public IP address or domain if the server is open to the internet.
3) Change the `enabled` option under `http-server` to `true`.
4) Restart your server to apply the changes.
5) The web server starts in the background. Once it is listening, the console prints
`The audio web page is available at <link>`; open that link in a browser to check it.

**Option 2**: Discord Audio Streaming
1) Create a new Discord application [here](https://discord.com/developers/applications/).
2) Open the `Installation` tab in your new application. Uncheck the `User Install` option, and set the `Install Link`
dropdown menu to `None`.
3) Open the `Bot` section in your application. The bot only joins voice channels, so none of the privileged gateway
intents are needed.
4) Rename your bot to something unique, and then click the `Reset Token` button to generate a new token. Keep the
token secret: anyone who has it can control your bot.
5) Copy and paste that token into the `token` field under the `discord-bot` section in your `config.yml`.
6) Enable Developer Mode in Discord by going to `User Settings > Advanced > Developer Mode`. Then right-click on your
server icon in Discord and select `Copy Server ID`. Paste that ID into the `guild-id` field under the `discord-bot`.
7) In your server, right-click the voice-channel you want to use for audio streaming and click `Copy Channel ID`.
Paste that ID into the `channel-id` field under the `discord-bot` section in your `config.yml`.
8) Set the `enabled` option under `discord-bot` to `true`.
9) Restart your server to apply the changes.

**Option 3**: Simple Voice Chat
1) Install the [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) plugin on your server. Make sure that
it's the correct version for your Minecraft server; version 2.6.24 supports Minecraft 26.3.
2) Open the `config.yml` file, and set the `enabled` option under the `simple-voice-chat` section to `true`.
3) (User Side) Install the [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) mod on your client.
4) Restart your server to apply the changes.
5) Once Simple Voice Chat has started, the console prints `Simple Voice Chat audio is ready`. If Simple Voice Chat
audio is enabled but the plugin is missing, MCAV logs one error instead, `MCAV cannot be enabled: Simple Voice Chat
audio is enabled, but the voicechat plugin is not installed (install the Simple Voice Chat plugin or set
simple-voice-chat.enabled to false in config.yml)`, and disables itself. Fix either and restart the server.

---

**Step 3: Profit**

Now that you have configured the plugin, you can start using it to play videos in your Minecraft server!

## Usage Instructions

The sandbox plugin provides many features. Refer to the [Commands Guide](./commands.md) for a complete list of all
their commands and proper usage. The following guide below will help you get started with the most common commands.

First, run the `/mcav screen` command with arguments to create a new map screen. For example, running
`/mcav screen 5x5 0 OAK_PLANKS ~ ~ ~` will create a 5x5 screen at your current location with oak planks as the frame.

### If you would like to play a video, here are the steps to take:
1) If you would like to play a video on the screen you just created, run the `/mcav video map` command. Otherwise, you
can use other commands like `/mcav video block`.
2) Set the audio type to whatever audio you configured in Step 2 (HTTP, Discord, or Simple Voice Chat).
3) Specify the other arguments accordingly to its [command usage](./commands).
4) Set the `mrl` to either a local file path, or pretty much any valid URL to a website like YouTube, Vimeo, or Twitch.
A list of all supported video sites can be found [here](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md).

Image commands also accept a path or URL enclosed in double quotes. Unquoted image paths containing spaces still
work; the image argument consumes the rest of the command.

While a video plays, `/mcav video seek 1:30` (or `+10`, `-10`) jumps, `/mcav video volume 50` turns it down,
`/mcav video speed 1.5` plays a file faster (0.5 to 2, with the sound higher to match) and `/mcav video loop true`
plays it again whenever it ends; the speed and loop need the `FFMPEG` player, and a live stream only changes volume.
Add `--filters "grayscale,blur=3"` to a video or image command to filter every picture in order; see the
[filters](./commands.md#filters) the plugin offers.

[Videos and Images](video.md) explains the players, the audio outputs, the controls and the filters in full.

If playback pauses or arrives in bursts without an error, try a lower-resolution, lower-frame-rate H.264 file and
compare it with the original on the same screen. Decoding a high-resolution AV1 source can fall behind on a busy
server; when the player drops most frames of ten seconds of video for coming too late, the server log says
`Playback falls behind`. The playing message reports player state; it does not guarantee frames are arriving, and a
static image can also produce no map updates, so packet silence alone does not identify a decoder failure.

### If you would like to create a browser, here are the steps to take:
1) Use the `/mcav browser create` command to create a new browser on that screen. Browsers can only be created on maps.
For example, running `/mcav browser create @a 640x640 1 5x5 0 FILTER_LITE HTTP_SERVER https://www.google.com` will
create a new browser that all players can see on the 5x5 screen you just created with a resolution of 640x640 pixels,
streams every changed browser frame with Filter Lite dithering, and plays the sound of the page on the audio web page.
The frame-skip value of `1` keeps every frame; it does not set a one-second interval. Choose `NONE` as the audio type
for a silent page. The first browser on a server downloads Chromium once (136 to 165 MB), and on Linux the libraries
it needs that the server lacks (about 13 MB), so it takes a moment longer to start; nothing has to be installed.
As in a desktop browser, a page plays sound only after a player clicked its screen or typed into it; turn on
`browser.autoplay-sound` in the [configuration](./config) to let pages play sound right away.
2) If you want to interact with the browser, you can use the `/mcav browser interact` command, which will take all your
chat input and send it to the browser as if you were typing in a real web browser. For special keys like enter, type the
key in "Enter" to simulate pressing the enter key. Left and right-clicking on the browser will simulate mouse
clicks, for every player with the permission `mcav.browser.interact`. For more information on possible keys, see the `KeyboardEvent.key` column [here](https://developer.mozilla.org/en-US/docs/Web/API/UI_Events/Keyboard_event_key_values).
3) Once you're done with the browser, you can close it by running the `/mcav browser release` command.

[Web Browsers](browser.md) has the details: the first-start download, sound, and what a page may reach.

### If you would like to stream OBS output, here are the steps to take:
1) Follow all the steps to play a video. The only difference is that you must set the player to be `FFMPEG` instead of
`VLC`.
2) Set the `mrl` to be `dshow||video=OBS Virtual Camera` on Windows. For other operating systems, please refer to the
[FFmpeg Documentation](https://trac.ffmpeg.org/wiki/Capture/Webcam). The format MCAV parses is `format||input`. In this
case, the format is `dshow` and the input is `video=OBS Virtual Camera`. A raw input like this can open the server's
cameras, microphones and screen, so it needs the permission `mcav.command.video.device` (operators have it).
3) Alternatively, run `/mcav video devices` to list the cameras and capture cards of the server, and play one with the
`DEVICE` player and its number as the `mrl`.

### If you would like to create a virtual machine, here are the steps to take:
1) Use the `/mcav vm create` command to create a new virtual machine on that screen. Virtual machines can only be
created on maps.
2) Follow the command argument usage in the [Commands Guide](./commands) to specify the VM parameters. You are
on your own to provide the valid QEMU arguments for the VM to run. For some examples of valid VM arguments, here is one
for providing an ISO image of the plugin's `iso` folder with 2 GB of RAM and 2 CPU cores, whose sound plays through
Simple Voice Chat. Put names that contain spaces in double quotes.

```
/mcav vm create @a 640x640 30 5x5 0 FILTER_LITE X86_64 SIMPLE_VOICE_CHAT -cdrom your.iso -m 2048M -smp 2
```

MCAV gives an `X86_64` machine its sound card itself (Intel HD Audio, and the PC speaker), so the guest needs no
option for it; machines of other architectures are silent and must choose `NONE`. Its sound plays about 70 ms late on
purpose, so that it plays with the picture of QEMU's display. [Virtual Machines](vm.md) lists the options QEMU accepts
and where disk images go.

### If you would like a sharper picture with MCV2, here are the steps to take:
1) Build the wall with `/mcav screen` as usual; MCV2 finds the wall by the item frame that holds its top-left map.
2) Add `--codec mcv2` at the end of `/mcav video map`, `/mcav image map`, `/mcav browser create`, `/mcav vnc create`
or `/mcav vm create`, for example
`/mcav video map @a FFMPEG NONE 1280x720 10x6 0 NEAREST_COLOR "" https://www.youtube.com/watch?v=... --codec mcv2`.
The resolution is what the players see: 1280x720 on a 10x6 wall is sharper than the 128 pixels a map shows. To use
MCV2 whenever a command has no flag, set `mcv2.default-codec` to `mcv2` in the [configuration](./config).
3) The players who watch are asked to load MCAV's MCV2 resource pack. Those who accept see the MCV2 picture once their
client has loaded it; those who decline, or whose client cannot load it, keep seeing the dithered maps. The same pack
serves every MCV2 screen of the server, so a player loads it once, and again only when a screen of a new video size
starts.
4) If players join through a proxy such as Velocity or BungeeCord, the pack cannot be served on the Minecraft port: set
`mcv2.pack.hosting` to `http` (and open `mcv2.pack.http-port`) or to `website` in the MCV2 part of the [configuration file](./config.md).
5) MCV2 encodes on the server's CPU, on the threads of `mcv2.encoder-threads`, which every MCV2 screen shares. When a
screen asks for more than they can give, it steps down to a faster encoder, a smaller video or fewer frames, and tells
you. A video file you show often can be encoded ahead of time at the best quality with `/mcav mcv2 encode` and shown
with `/mcav mcv2 play` (without sound). [Using MCV2](../mcv2/using.md) helps you choose a preset, host the pack and
troubleshoot.

### If you would like to show a VNC desktop, here are the steps to take:
1) List the VNC server in `vnc.allowed-hosts` of the [configuration file](./config.md), with its password if it has one,
and restart the server. Nothing can be reached until you do.
2) Run `/mcav vnc create @a 1280x720 20 10x6 0 NEAREST_COLOR 127.0.0.1:5901`, naming the server exactly as listed.
3) Players click the screen to click the desktop; `/mcav vnc interact` types their chat into it. `/mcav vnc release`
disconnects. See [VNC Desktops](vnc.md).

You are not limited by any of these commands! You can combine them in any way you like to create whatever you want on your
server!
