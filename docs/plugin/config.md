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

# ======================================================================
# MCV2 VIDEO CONFIGURATION
# ======================================================================

# The MCV2 encoder turns video into what the MCV2 resource pack decodes. Every MCV2 screen of the server encodes
# on one shared set of threads, so the game keeps the rest of the processors.
mcv2:

  # How many threads the MCV2 encoders share, from 1 to 256, or 0 for half the processors the server may use (at
  # least one). A screen whose frames take longer than the video gives them encodes fewer frames a second, and at
  # worst shows the dithered maps, and the server log says what it chose and why.
  # Default is 0
  encoder-threads: 0

  # Whether the live encoders run their pixel kernels in the native library MCAV ships for Linux, Windows and macOS
  # on x86-64 and ARM64 (auto), which computes exactly what Java computes, about twice as fast, or always in Java
  # (off). Anything that stops the library from loading, such as a JVM that refuses native access, runs Java. The
  # system property mcv2.native, given to the server's JVM, wins over this setting, and the server log says at startup
  # which kernels run and why.
  # Default is auto
  native: auto

  # How a wall of maps shows its picture when the command that starts it has no --codec flag: dither, map colours
  # every client shows, or mcv2, the MCV2 resource pack's decoder at the resolution the command asks for, which the
  # players are offered and may decline (those who do see the dithered maps). /mcav video map, /mcav image map,
  # /mcav browser create, /mcav vm create and /mcav vnc create take the flag, for example --codec mcv2.
  # Default is dither
  default-codec: dither

  # The MCV2 resource pack: one pack decodes every MCV2 screen of the server, and changes only when a screen of a
  # video size it does not decode yet starts; accepting a changed pack reloads a player's resources, a hitch of a
  # second or more.
  pack:

    # Where the players download the pack from:
    #   injector - the Minecraft server's own port. Nothing to set up. It does NOT work behind a proxy such as
    #              Velocity or BungeeCord: the players' connections reach the proxy's port, not this server's.
    #   http     - a small HTTP server on http-port, which the players must be able to reach (open the port in the
    #              firewall). The choice behind a proxy when a port can be opened.
    #   website  - an upload to mc-packs.net: no port at all, for a server behind a proxy or a firewall that opens
    #              none. The pack is public there.
    # Default is injector
    hosting: injector

    # For http: the host name or address the players reach this server by. Empty means the address the server finds
    # for itself.
    # Default is empty
    http-host: ""

    # For http: the port of the HTTP server, from 1 to 65535.
    # Default is 25580
    http-port: 25580

# ======================================================================
# VNC CONFIGURATION
# ======================================================================

# The VNC servers /mcav vnc create may show. The Minecraft server connects to them, not the players, so a command can
# only name a host and port listed here, and none is listed by default. Write a server's password here, never in the
# command, where the server log would keep it, and leave it out for a server without one; MCAV never logs it. For
# example:
#   allowed-hosts:
#     - host: 127.0.0.1
#       port: 5901
#       password: secret
vnc:

  # Default is none
  allowed-hosts: []

# ======================================================================
# WEB BROWSER CONFIGURATION
# ======================================================================

# The web browser of /mcav browser create: an embedded Chromium that runs in a process of its own.
browser:

  # Whether pages may reach addresses of the server's own network: loopback (localhost), private networks such as
  # 192.168.x.x, and link-local addresses such as the metadata service of a cloud server. Leave it off unless you
  # want to show a page of your own network: with it on, everyone who may create a browser, every page they open,
  # and every player who may click on it can read services that only the server can reach.
  # Default is false
  allow-private-networks: false

  # Whether JavaScript is compiled to machine code. The browser cannot use Chromium's sandbox, so this is off: most
  # exploits of malicious pages target the compiler. Pages with heavy scripts run slower without it. Turn it on only
  # if you trust every page that can be opened.
  # Default is false
  javascript-jit: false

  # Whether pages play sound right away. Off, a page plays sound only once a player clicked its screen or typed into
  # it, as in a desktop browser, so a page cannot play sound before anyone looked at it. Turn it on for a screen that
  # should play a video with sound as soon as it opens.
  # Default is false
  autoplay-sound: false

  # Whether Chromium is confined, on Linux 5.13 or later and on macOS: its processes cannot read the server's folder,
  # the home folder or the temporary folder, apart from what the browser needs there, and they change files only in
  # the folder of their browser. A page that exploits a flaw of Chromium then cannot read this file or change the
  # server's files. Elsewhere (Windows) the browser runs as before, and the server log says so. Turn it off only if a
  # page needs something it hides, such as fonts in the home folder.
  # Default is true
  confine-chromium: true

  # More hosts whose addresses pages may not reach, by name or address. Pages never reach private addresses or the
  # addresses of this machine's network interfaces, but inside a container (Docker, Pterodactyl) the public address of
  # the machine around it is no interface of the container, and it still reaches every service that listens on all
  # interfaces of that machine, past its firewall. List that address, or the name it has, here, for example
  # ["203.0.113.5", "play.example.com"]. Names are looked up when a browser starts.
  # Default is []
  refused-hosts: []

# ======================================================================
# VIRTUAL MACHINE CONFIGURATION
# ======================================================================

# The virtual machines of /mcav vm create.
vm:

  # Whether a machine's guest gets QEMU's user-mode network. It reaches the internet, and also every service of the
  # server that listens only on its loopback address (10.0.2.2 inside the guest), such as an RCON port, a database or
  # an admin page. Off, the guest has a network card that reaches nothing. Turn it on only if everyone who may create
  # a machine or type into one may use those services.
  # Default is false
  allow-network: false
```

A port or a number of threads that is not a whole number in its range, such as a port of 4294967376 or 8080.5, is
refused with a warning in the server log, and the option keeps its default.

```{warning}
Keep the Discord bot token secret. Do not share your `config.yml` without removing it first; the `/mcav dump` command
never includes it.
```

The audio web page listens on all network interfaces of the server; `host-name` only decides the link players are
sent. On Linux and macOS, ports below 1024 need administrator rights, so keep the port above 1024.

The page keeps a tenth of a second of sound ahead of what it plays, so a short hold-up of the connection does not
break the sound. Sound that arrives while the browser's audio is still starting, such as for a player who opens the
page while a video plays, or all at once after a stalled connection, is dropped instead of delaying everything after
it. Measured with a vanilla client drawing in software and a browser playing into a virtual sound device, the sound
left the device 0.1 to 0.15 s after the wall showed the same moment.

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

The browser of `/mcav browser create` reaches public addresses of the internet only, and none of the server's own
addresses, public ones included. Turn on
`browser.allow-private-networks` only to show a page of your own network: it lets everyone who may create a browser,
every page they open and every player who may click on it reach services that only the server can reach, such as a
router, a database console or the metadata service of a cloud server. `browser.javascript-jit` makes pages with heavy
scripts faster, at the cost of the protection described in the [browser module](../library/browser.md#security).
`browser.autoplay-sound` lets pages play sound before a player clicked their screen.

The browser keeps what it downloads in the MCAV cache folder of the user running the server, like VLC and yt-dlp:
Chromium in `~/.mcav/cache/jcef` (about 136 to 163 MiB) and, on Linux, the libraries it needs that the server lacks in
`~/.mcav/cache/jcef-libraries` (about 13 MB). On a Pterodactyl server that is `/home/container/.mcav/cache`. The folder
has no setting of its own; delete it to download everything again.

A virtual machine's guest gets a network card that reaches nothing, neither the internet nor the server.
`vm.allow-network` gives it QEMU's own user-mode network instead, which reaches the internet and every service the
server offers only on its loopback address. Where a machine's sound plays is chosen with the audio type of
`/mcav vm create`, and MCAV gives an `X86_64` machine its sound card itself.

## MCV2

`mcv2.encoder-threads` is the one encoder budget every MCV2 screen of the server shares, a video, a browser, a virtual
machine, a desktop or an image alike; no screen starts threads of its own, and the default, half of the processors the
server may use, leaves the other half to the game. `mcv2.native` chooses the native encoder kernels MCAV ships, which
compute exactly what the Java ones do, about twice as fast; the server log says at startup which kernels run and why.

`mcv2.default-codec` is the codec of a wall of maps whose command has no `--codec` flag, see
[the codec of a wall of maps](commands.md#the-codec-of-a-wall-of-maps). It is `dither` unless you choose `mcv2`.

`mcv2.pack` is where the players download the MCV2 resource pack from, one pack for every MCV2 screen of the server:

| `hosting` | Where the pack is served | Pick it when |
|---|---|---|
| `injector` (default) | On the Minecraft server's own port, next to the game | Players join this server directly. It does **not** work behind a proxy such as Velocity or BungeeCord: the players' connections reach the proxy's port, never this server's |
| `http` | A small HTTP server on `http-port`, reached at `http-host` (empty: the address the server finds for itself) | The server is behind a proxy and the port can be opened in the firewall for the players |
| `website` | Uploaded to mc-packs.net | No port can be opened. The pack is then public there; it holds only the decoder's shaders |

Behind a proxy, pick `http` with the public host name of the machine this server runs on and a port the players can
reach, or `website`. The pack is optional and additive: it is offered with `required: false` and replaces no pack of
the server or the proxy.

## VNC

`vnc.allowed-hosts` lists the VNC servers `/mcav vnc create` may show, each with its `host`, `port` and, for a server
that has one, its `password`. None is listed by default, so the command cannot reach anything until you list a server:
the Minecraft server connects to it, not the players, so a player must never choose where it connects. The password is
read from this file only, never from the command, since the server log keeps every command, and MCAV never writes it
to the log.
