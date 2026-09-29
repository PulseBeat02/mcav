(mcv2-why)=
# Why MCV2

Minecraft can show a picture on a map, and MCAV has always used that to play video: every frame is reduced to the map
palette and sent as the colours of a wall of maps. That works for small screens, and it breaks down for large, moving
ones. MCV2 is MCAV's answer: a video codec made for the one channel a vanilla Minecraft client offers, which sends a
1080p picture in a few megabits a second and decodes it on the player's GPU.

## What MCAV Sends Without It

A map is 128x128 pixels, and each pixel is one byte, the id of one of the 248 map colours of Minecraft 26.3 (62 base
colours in four shades; the four shades of the first are transparent) {cite}`mcwikimap`. To show video, MCAV dithers
every frame to those colours ([dithering](../bukkit/map.md)) at 128 pixels per map, so a 1920x1080 picture needs a wall
of 15 x 9 = 135 maps, and a full frame is 2.2 MB of map colours. `CompressedMapResult` sends only the parts of each map
that changed, and at most 128 KiB per frame and viewer, so that a scene cut is spread over a few frames instead of
flooding the connection.

What that sends, measured with MCAV's own dithering and map encoder on 1080p video at 30 fps
([results](results.md#dithered-maps)):

| Content | Dithered, 128 KiB per frame (the plugin's default) | Dithered, every change sent |
|---|---|---|
| 1080p30 proxy | 30.4 Mbit/s of map packets (10.4 after zlib); the viewer's wall is never complete, VMAF 33.4 | 367.2 Mbit/s (125.5 after zlib), VMAF 96.9 |
| 1080p30 gameplay | 30.4 Mbit/s (10.9 after zlib); never complete, VMAF 10.9 | 498.2 Mbit/s (178.3 after zlib), VMAF 99.5 |

So dithered maps at 1080p need hundreds of megabits a second to show every frame, which no server and no player's
connection can give. Under the default budget they use the whole budget, about 30 Mbit/s per viewer, and the wall
still lags behind the video: 128 KiB is a seventeenth of the wall, so some maps show the frame before and some a frame
from seconds ago, and on gameplay, where almost every map changes on every frame, the wall is a mosaic of different
moments. The dithered picture is also limited to 128 pixels per map, whatever the video's resolution.

Sending the frames uncompressed is worse: a 1080p RGB frame is 6.2 MB, 8.3 MB as six-bit map colours, about 2 Gbit/s at
30 frames a second.

## What a Vanilla Client Allows

A mod could decode video with anything, but players would have to install it. MCAV keeps to what an unmodified client
does with a resource pack, which a server can offer to every player:

- **Map colours reach the client exactly.** The client uploads a map's bytes into a texture as they are, and 64 of the
  colours can be told apart exactly after the client draws them, so a map carries six bits per pixel: 12 KB of data.
- **A resource pack may replace the game's shaders**, including the core text shaders that draw maps and the post
  effect that draws the outlines of glowing entities. Shaders run on the GPU, in GLSL 330, one invocation per pixel.
- **A post effect may keep textures from one frame to the next**, so the previous decoded picture can survive.

Nothing else is available: no state in the shaders, no compute shaders, no files, no network. MCV2 is what fits: the
server encodes the video into a bitstream built so that a fragment shader can decode any pixel on its own, sends it as
the colours of a few maps per frame, and the pack's shaders decode it and draw it over the wall.

## What It Gives

| | Dithered maps (default budget) | MCV2 `ship`, pre-encoded | MCV2 `live`, as it plays |
|---|---|---|---|
| 1080p30 proxy, per viewer | 30.4 Mbit/s, 10.4 after zlib, VMAF 33.4: never a whole frame | 3.46 Mbit/s of map packets, 2.21 after zlib, VMAF 77.9 | 2.80 Mbit/s, 1.83 after zlib, VMAF 75.7 |
| 1080p30 gameplay, per viewer | 30.4 Mbit/s, 10.9 after zlib, VMAF 10.9: never a whole frame | 29.2 Mbit/s, 18.0 after zlib, VMAF 90.7 | 13.0 Mbit/s, 8.3 after zlib, VMAF 76.1 |
| Resolution | 128 pixels per map | any, up to 4096 on a side | any, up to 4096 on a side |
| What the client needs | nothing | the resource pack | the resource pack |

The dithered figures are 600-frame runs of Filter Lite, the recommended dithering, at the plugin's default budget
([dithered maps](results.md#dithered-maps));
the `ship` figures are its lambda on 30 frames of the proxy and 60 frames of gameplay
([the codec comparison](results.md#the-codec-comparison)); the `live` figures are 600-frame runs
([the live presets](results.md#the-live-presets)). The cost moves to the server's CPU and the player's GPU: a
live 1080p screen needs about 2.4 cores of a desktop CPU on quiet content and 3.6 on fast gameplay
([server cost](server.md)), and a player's GPU spends 7.4 to 8.8 ms per new frame on an Intel UHD 630
([client decode](client.md)). Players who do not load the pack keep the dithered maps of the same wall.

MCV2 is not AV1. Built for a fragment shader, it cannot use the transforms and entropy coding that make modern codecs
efficient, and it is 15.8 VMAF points behind AV1 at the same nominal rate; [the design page](design.md) measures why,
and [the codec comparison](quality.md#four-codecs-on-one-chart) shows where it stands against H.264, VP9 and AV1.
