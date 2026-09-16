# Frequently Asked Questions

Please read these questions before asking for help in the support channels.

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
