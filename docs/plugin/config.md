# Configuration File

The plugin creates `plugins/MCAV/config.yml` on its first start. Restart the server after changing it. Every option
has a comment that describes its purpose and default value:

```yaml
# MCAV Configuration File

# ======================================================================
# MCAV LOCALIZATION
# ======================================================================

# Sets the language for the plugin
# Valid options are: EN_US
# Default is EN_US
language: EN_US

# ======================================================================
# DISCORD BOT CONFIGURATION
# ======================================================================

# This is the Discord bot that plays audio in the background in a voice channel using the following
# credentials.
discord-bot:

  # Whether or not the bot is enabled
  enabled: false

  # The token of the discord bot
  # Default is none
  token: none

  # The server id of the guild (server) to play audio in
  # Default is none
  guild-id: none

  # The channel id of the voice channel to send audio to
  # Default is none
  channel-id: none

# ======================================================================
# HTTP WEBSITE CONFIGURATION
# ======================================================================

# This is the HTTP server that plays audio into onto a website using the following options.
http-server:

  # Whether or not the HTTP server is enabled
  enabled: false

  # The host name of the daemon (for example, google.com)
  # Default is localhost
  host-name: localhost

  # The port of the daemon (for example, 3000)
  # Default is 3000
  port: 3000

# ======================================================================
# SIMPLE VOICE CHAT CONFIGURATION
# ======================================================================

# This is the Simple Voice Chat extension that plays audio in the background
simple-voice-chat:

  # Whether or not the Simple Voice Chat extension is enabled
  enabled: false
```

```{warning}
Keep the Discord bot token secret. Do not share your `config.yml` without removing it first; the `/mcav dump` command
never includes it.
```

The audio web page listens on all network interfaces of the server; `host-name` only decides the link players are
sent. On Linux and macOS, ports below 1024 need administrator rights, so keep the port above 1024.

The Discord bot and the web page start in the background, so they never delay the server start. The console reports
when each output is ready: `The audio web page is available at <link>` for the web page and
`Simple Voice Chat audio is ready` for Simple Voice Chat. If the Discord bot or the web page cannot start, the plugin
logs an error and that output stays off without affecting the rest of the plugin; until an output is ready, video
commands that use it tell the player so.

Simple Voice Chat audio is checked when the plugin starts: if `simple-voice-chat.enabled` is `true` but the Simple
Voice Chat plugin (`voicechat`) is not installed, MCAV logs one error line and disables itself:

```text
MCAV cannot be enabled: Simple Voice Chat audio is enabled, but the voicechat plugin is not installed (install the Simple Voice Chat plugin or set simple-voice-chat.enabled to false in config.yml)
```

Install the plugin or set the option to `false`, then restart the server.
