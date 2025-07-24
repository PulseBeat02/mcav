# Plugin Tutorial

If you need help with using this plugin, refer to this tutorial to guide you through a step-by-step process to set this
plugin up.

---

**Step 1: Install the Plugin**  
If you haven't already, follow the installation instructions in the [Installation Guide](./plugin.md#installation) to 
install the MCAV plugin on your Minecraft server. Make sure you are using a compatible version of Paper (1.21.8).

---

**Step 2: Configure the Plugin**  
Run the plugin once to generate the default configuration files. You can find the main configuration file in
`plugins/MCAV/config.yml`. Open this file in your favorite text editor and adjust the settings as needed. If you would
like audio support for videos, follow one or both of the ways to set up audio:

**Option 1**: HTTP Audio Streaming
1) Port-forward another port besides your current Minecraft server port.
2) Go into the `config.yml` and set the `port` under the `http-server` section to the port you just forwarded. Make sure
to set the `host-hame` to your public-ip address too if the server is open to the internet.
3) Change the `enabled` option under `http-server` to `true`.
4) Restart your server to apply the changes.

**Option 2**: Discord Audio Streaming
1) Create a new Discord application [here](https://discord.com/developers/applications/).
2) Open the `Installation` tab in your new application. Uncheck the `User Install` option, and set the `Install Link` 
dropdown menu to `None`.
3) Open the `Bot` section in your application, and scroll down to the `Privileged Gateway Intents` section. Enable all
the intents (PRESENCE, SERVER_MEMBERS, and MESSAGE_CONTENT).
4) Scroll up, and rename your bot to something unique, and then click the `Reset Token` button to generate a new token.
5) Copy and paste that token into the `token` field under the `discord-bot` section in your `config.yml`.
6) Enable Developer Mode in Discord by going to `User Settings > Advanced > Developer Mode`. Then right-click on your
server icon in Discord and select `Copy Server ID`. Paste that ID into the `guild-id` field under the `discord-bot`.
7) In your server, right-click the voice-channel you want to use for audio streaming and click `Copy Channel ID`.
Paste that ID into the `channel-id` field under the `discord-bot` section in your `config.yml`.
8) Set the `enabled` option under `discord-bot` to `true`.
9) Restart your server to apply the changes.

**Option 3**: Simple Voice Chat
1) Install the [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) plugin on your server. Make sure that
it's the correct version for your Minecraft server.
2) Open the `config.yml` file, and set the `enabled` option under the `simple-voice-chat` section to `true`.
3) (User Side) Install the [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) mod on your client.
4) Restart your server to apply the changes.

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
2) Set the audio type to whatever audio you configured in Step 2 (HTTP or Discord).
3) Specify the other arguments accordingly to its [command usage](./commands).
4) Set the `mrl` to either a local file path, or pretty much any valid URL to a website like YouTube, Vimeo, or Twitch.
A list of all supported video sites can be found [here](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md).

### If you would like to create a browser, here are the steps to take:
1) Use the `/mcav browser create` command to create a new browser on that screen. Browsers can only be created on maps.
For example, running `/mcav browser create @a 640x640 100 1 5x5 0 FILTER_LITE https://www.google.com` will create a new 
browser that all players can see on the 5x5 screen you just created with a resolution of 640x640 pixels, full quality,
and snapshots taken every second with Filter Lite dithering. It will display the Google homepage by default.
2) If you want to interact with the browser, you can use the `/mcav browser interact` command, which will take all your
chat input and send it to the browser as if you were typing in a real web browser. For special keys like enter, type the
key in "Enter" to simulate pressing the enter key. Left and right-clicking on the browser will simulate mouse
clicks. For more information on possible keys, see the `KeyboardEvent.key` column [here](https://developer.mozilla.org/en-US/docs/Web/API/UI_Events/Keyboard_event_key_values).
3) Once you're done with the browser, you can close it by running the `/mcav browser release` command.

### If you would like to stream OBS output, here are the steps to take:
1) Follow all the steps to play a video. The only difference is that you must set the player to be `FFMPEG` instead of
`VLC`. 
2) Set the `mrl` to be `dshow:video=OBS Virtual Camera` on Windows. For other operating systems, please refer to the
[FFmpeg Documentation](https://trac.ffmpeg.org/wiki/Capture/Webcam). The format MCAV parses is `format:input`. In this
case, the format is `dshow` and the input is `video=OBS Virtual Camera`.

### If you would like to create a virtual machine, here are the steps to take:
1) Use the `/mcav vm create` command to create a new virtual machine on that screen. Virtual machines can only be 
created on maps.
2) Follow the command argument usage in the [Commands Guide](./commands) to specify the VM parameters. You are
on your own to provide the valid QEMU arguments for the VM to run. For some examples of valid VM arguments, here is one
for providing an ISO image with 2 GB of RAM and 2 CPU cores:

```
/mcav vm create @a 640x640 30 5x5 0 FILTER_LITE X86_64 -cdrom /path/to/your.iso -m 2048 -smp 2
```

You are not limited by any of these commands! You can combine them in any way you like to create whatever you want on your
server!