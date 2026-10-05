# Web Browsers

`/mcav browser create` opens a web page in an embedded Chromium and shows it on a wall of maps, with its sound in an
audio output; players click it by clicking the wall and type into it through chat. The browser is JCEF, the Java
binding of the Chromium Embedded Framework ([browser module](../library/browser.md)), and it runs in a process of its
own, so a crashing page never takes the server with it.

```text
/mcav screen 10x6 0 BLACK_CONCRETE ~ ~ ~
/mcav browser create @a 1280x720 1 10x6 0 NEAREST_COLOR HTTP_SERVER https://example.com
```

The arguments are the viewers, the size of the page in pixels (at most 4096 on a side), how many painted frames make
one frame on the wall (1 streams every change), the wall's size and first map id, the dithering algorithm, the audio
output, and the address, which must be a full `http` or `https` URL and takes the rest of the line. Add `--codec mcv2`
at the end for a sharper picture ([Using MCV2](../mcv2/using.md)). One browser runs at a time; `/mcav browser release`
closes it.

## The First Start Downloads Chromium

Nothing has to be installed, and the server needs no JVM options. The first browser on a server downloads the CEF build
of its platform, **136 to 165 MB** depending on the platform, from Maven Central into the MCAV cache folder of the user
running the server (`~/.mcav/cache/jcef`; on a Pterodactyl server `/home/container/.mcav/cache`), checked against a
SHA-256 hash pinned in MCAV. Later starts reuse it. The browser runs on 64-bit Linux, Windows and macOS on x86-64 and
ARM64 (Windows on ARM64 is supported by the build but untested); anywhere else, and after a failed download, the
command says the browser cannot run here and the console names the reason.

**Linux servers need no X server, no Xvfb and no packages.** Chromium draws on its headless platform. The libraries it
links that a server image often lacks (the X11 client libraries, NSS, ALSA and others) are downloaded on the first start
from Debian 11, only those the server lacks, about **13 MB** into `~/.mcav/cache/jcef-libraries`, each checked against
a pinned hash, and given to the browser's own process only. This was proven in the images `eclipse-temurin:25-jre`,
`ghcr.io/pterodactyl/yolks:java_25` and `itzg/minecraft-server:latest`, run as an unprivileged user.

## Clicking and Typing

Players with the permission `mcav.browser.interact` click the page by clicking the wall: a left click clicks, a right
click right-clicks. `/mcav browser interact` switches their chat to the browser: what they write is typed into the page
instead of sent to chat, and a key name such as `Enter`, `Backspace` or `ArrowLeft` presses that key. Chromium ignores
clicks and keys for a moment right after a page appears, as every Chromium does.

```{important}
`mcav.browser.interact` is what lets a player click the page by clicking its wall, not only the chat mode. The wall
stands in the world where anyone can reach it; without the permission a click does nothing.
```

## Sound

The page's sound, from Web Audio and its audio and video elements, plays into the chosen audio output
(`HTTP_SERVER`, `DISCORD_BOT`, `SIMPLE_VOICE_CHAT`), and takes the outputs over from a video or a virtual machine; `NONE`
keeps the page silent. As in a desktop browser, a page plays sound only once a player clicked its wall or typed into it,
so a page cannot play sound before anyone looked at it; set `browser.autoplay-sound: true` for a screen that should play
a video with sound as soon as it opens. A pause of up to two seconds in its sound plays as silence, so the sound keeps
its rhythm. The sound of a frame embedded from another site, of media from another site
that does not allow it, and of protected (DRM) media cannot be captured: open the embedded player's own address
instead.

## What a Page May Reach

A page is untrusted content, and the browser runs without Chromium's sandbox, which JCEF cannot use, so MCAV limits it:

- **Confined on Linux.** Chromium's processes cannot read the server's folder, the home folder or the temporary
  folder, apart from what the browser needs there, and they change files only in the folder of their browser. So a
  page that exploits a flaw of Chromium cannot read your `config.yml` or change the server's files. It needs Linux
  5.13 or later (Landlock); on Windows, macOS and older kernels the browser runs as before, and the server log says
  `Chromium runs without confinement` with the reason. `browser.confine-chromium: false` turns it off.

- **Only the public internet.** Every connection goes through a guard that refuses loopback, private, link-local (such
  as a cloud server's metadata service) and every other special address, also when a public name resolves to one, and
  every address of the server's own network interfaces. So a page, or a player clicking on it, cannot read a service
  only the server can reach: a router, a database console, an admin page. Set `browser.allow-private-networks: true`
  only to show a page of your own network, and only if you trust everyone who may create a browser, every page they
  open, and every player who may click it.
- **No JavaScript compiler.** JavaScript runs without V8's just-in-time compiler, the part most exploits target. Pages
  with heavy scripts run slower; `browser.javascript-jit: true` turns it on for pages you trust.
- **Only `http` and `https`.** `file:` and other schemes are refused, downloads, file choosers, logins and invalid
  certificates are refused, JavaScript dialogs are dismissed, and popups open in place, only during a click or a key.
- **Nothing kept.** The browser's profile keeps nothing, and its folder is deleted when it is released. No debugging
  port is ever opened.

The details are in the [browser module](../library/browser.md#security).

## Troubleshooting

**The first browser takes long to start.** It is downloading Chromium, once; see above.

**The browser is silent.** Nobody clicked the wall yet, or the audio type is `NONE`, or the sound comes from an embedded
frame of another site.

**A page of my own network does not load.** That is the private-network guard; see above.

**The command says the browser cannot run here.** The console says why: a failed download or check, a server without
one of the basic libraries every server image has (zlib, expat, fontconfig, freetype), or a platform Chromium does not
exist for, such as a 32-bit one.
