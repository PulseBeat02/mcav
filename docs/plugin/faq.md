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
