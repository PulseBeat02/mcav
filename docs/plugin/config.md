# Configuration File

This is what the current configuration file looks like:

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
```

All of the configuration options have comments that describe their purpose and default values.