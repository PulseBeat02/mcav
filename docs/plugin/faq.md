# Frequently Asked Questions

Please read these questions before asking for help in the support channels.

---

## Why does the first browser take so long to start?

The first `/mcav browser create` on a server downloads Chromium (136 to 165 MB, depending on the platform) into the
MCAV cache folder of the user running the server (`~/.mcav/cache/jcef`), and on Linux the libraries it needs that the
server lacks (about 13 MB of Debian 11 packages, into `~/.mcav/cache/jcef-libraries`). Every file is checked against a
SHA-256 hash pinned in MCAV; later starts reuse them. The browser needs no X server, Xvfb or packages, and no Java
options. If the command answers that the browser cannot run on this server, the console names the reason: a failed
download, a server without one of the basic libraries every server image has, or a system Chromium does not exist for,
such as a 32-bit one.

---

## Why is the browser silent?

As in a desktop browser, a page plays sound only once a player clicked its screen or typed into it, so a page cannot
play sound before anyone looked at it; click the screen once, or turn on `browser.autoplay-sound` in the
[configuration](./config). Choose an audio type other than `NONE` in `/mcav browser create`. The sound of frames from
another site embedded in the page (open the address of the embedded player instead), of media from another site that
does not allow it, and of protected media (DRM) cannot be captured.

---

## Why can the browser not open a page of my own network?

By default the browser reaches public addresses of the internet only, so that a page, or a player clicking on it,
cannot read services that only the server can reach, such as a router or the metadata service of a cloud server.
Turn on `browser.allow-private-networks` in the [configuration](./config) only if you trust everyone who may create a
browser, every page they open and every player who may click on it.

---

## How does the sound of a virtual machine work?

`/mcav vm create` with an audio type other than `NONE` gives an `X86_64` machine a sound card (Intel HD Audio, and the
PC speaker) and plays its sound through that output, like a video; the guest needs a driver for the card, which every
current operating system has. Machines of other architectures have no sound. Only one video, browser or virtual
machine plays through the audio outputs at a time: the newest one takes them over.

---

## Why are players asked to load a resource pack?

A wall of maps with `--codec mcv2` (or `mcv2.default-codec: mcv2`) is decoded by MCAV's MCV2 resource pack. The pack
is optional: a player who declines it keeps the dithered maps and is not asked again until they rejoin. One pack serves
every MCV2 screen of the server, so a player is asked once, and again only when a screen of a video size the pack does
not decode yet starts; loading it reloads the client's resources, a hitch of a second or more.

---

## Why does a player see the dithered maps on an MCV2 screen?

Until their client has loaded the MCV2 pack, and for good when they declined it or their client could not load it
(the chat tells them). A screen is also dithered for everyone when no item frame holds its top-left map (build it with
`/mcav screen`, and start the screen while a player is near it: the server only sees the item frames of loaded
chunks), when eight MCV2 screens already play, or when even the fastest encoder cannot keep up on the threads of
`mcv2.encoder-threads`; the command that started it says which.

---

## The MCV2 pack does not download behind my proxy

By default the pack is served on the Minecraft server's port, which players behind Velocity or BungeeCord never reach.
Set `mcv2.pack.hosting` to `http` with a port the players can reach, or to `website` to upload it to mc-packs.net,
in the MCV2 part of the [configuration file](./config.md).

---

## My server already sends a resource pack. Does it clash with the MCV2 pack?

Only if your pack changes the text shaders (`assets/minecraft/shaders/core/text.vsh`, `text.fsh`) or the glow outline
(`assets/minecraft/post_effect/entity_outline.json`), which the MCV2 pack replaces to decode the video. The pack that a
player's client loads last wins those files. Your server pack (`server.properties`) is sent when the player joins and
the MCV2 pack later, so the MCV2 pack wins: MCV2 screens work, and your pack's versions of those three files are not used
by that player until they leave (the MCV2 pack stays loaded after its screens stop, so players are not reloaded again
and again). Everything else in your pack - textures, sounds, other shaders - is unaffected, and glowing entities keep
their outline. If another plugin sends a pack with those files after the MCV2 pack, that pack wins and MCV2 screens
show nothing. To keep your own text or outline shaders, merge your changes into the MCV2 pack's copies (the
`mcav/mcv2/pack` folder of the plugin jar), which are vanilla's plus the decoder.

---

## Do players who join later see an MCV2 screen?

Yes. A player who joins, rejoins or changes world while a screen plays for `@a` (or for a selector that matches them) is
offered the MCV2 pack on the spot and sees the dithered maps until it has loaded. The screen keeps the chunks of its
hidden page frames loaded until it is released, so players who walk away and come back see it too. Start a screen
while a player is near its wall: the server only knows the item frames of loaded chunks.

---

## Why do VLC commands say "VLC is still being prepared"?

On the first start of a server that has no VLC, the plugin downloads VLC in the background into the MCAV cache folder
of the user running the server, without `sudo` or administrator rights. The server does not wait for it, so players
can join right away. Until the download is finished and VLC is loaded, video commands with the `VLC` player answer
that VLC is still being prepared; try again once the console logs `VLC ready in <n> ms`, or use the `FFMPEG` player,
which is bundled and always works. Commands given a web page, such as a YouTube video, answer the same way while
yt-dlp is being downloaded. Later starts use the downloaded copies at once.

If the console logs a warning that VLC is not available instead, VLC cannot be installed on this system, and VLC
commands answer that VLC is not supported.

---

## I'm getting an `UnsatisfiedLinkError` saying that version `GLIBC_2.38` is not found, how do I fix this?

The error indicates that your system's GLibC version is way too old for VLC to use. To fix this, you have to install
a newer version of GLibC. If you are on a dedicated server provider, you would have to contact them to see if they are
able to update this library for you.
