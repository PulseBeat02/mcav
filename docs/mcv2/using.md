(mcv2-using)=
# Using MCV2

Everything a server owner needs to run MCV2 screens with the MCAV plugin. The plugin's [commands](../plugin/commands.md)
and [configuration](../plugin/config.md) pages have the full syntax.

## Turning It On

Every command that draws on a wall of maps takes `--codec mcv2` after its last argument:

```text
/mcav video map @a FFMPEG NONE 1920x1080 15x9 0 NEAREST_COLOR "" https://www.youtube.com/watch?v=... --codec mcv2
/mcav image map @a 1920x1080 15x9 0 NEAREST_COLOR /path/to/picture.png --codec mcv2
/mcav browser create @a 1280x720 1 10x6 0 NEAREST_COLOR NONE https://example.com --codec mcv2
/mcav vm create @a 1280x720 30 10x6 0 NEAREST_COLOR X86_64 NONE -m 2048M -cdrom "alpine linux.iso" --codec mcv2
/mcav vnc create @a 1280x720 20 10x6 0 NEAREST_COLOR 127.0.0.1:5901 --codec mcv2
```

To make MCV2 the default of every wall of maps, set `mcv2.default-codec: mcv2` in `plugins/MCAV/config.yml`; a command
can still ask for `--codec dither`. Build the wall with `/mcav screen` first, with the same size and map id: MCV2 finds
the wall by the item frame that holds its top-left map, and a player must be near the wall when the screen starts,
because the server only knows the item frames of loaded chunks.

The resolution is what the players see. A map shows 128 dithered pixels, so a 10x6 wall shows 1280x768 dithered
pixels; with MCV2 the same wall shows a 1280x720 video at full colour, and the pack scales the picture to the wall.

```{note}
MCV2 needs maps. The block, chat, entity and scoreboard displays have no `--codec` flag and always show their own
picture, and neither does the information hologram of `/mcav video hologram`.
```

## What the Players See

Viewers are asked to load MCAV's MCV2 resource pack. Those who accept see the MCV2 picture once their client has loaded
it; those who decline, or whose client cannot load it, keep seeing the **dithered maps of the same wall**, the whole
picture scaled to the wall as the pack scales it, never a broken screen, and the chat tells them why. A player who declines is not asked again while online. A pre-encoded
stream played with `/mcav mcv2 play` or `/mcav mcv2 stream` has no dithered maps: there, a player without the pack
sees nothing new on the wall.

## Choosing a Preset

| Your source | Preset | Why |
|---|---|---|
| Anything that plays live: a browser, a VM, a VNC desktop, a stream, a camera, and video files by default | `live` (chosen for you) | The default balance of picture quality and encode cost. Throughput depends on the source and available CPU; a screen that cannot keep up steps down to a faster preset, fewer frames or a smaller size |
| Fast gameplay or a busy picture on a small encoder budget | `LIVE_ADAPTIVE` | Switches to the faster search while the picture moves; about 25% more rate on gameplay, same on quiet content |
| A server that cannot keep up with `live` at all | `LIVE_FAST` | The fastest search inside the quality rules; up to 30% more rate than `ship` |
| Viewers whose clients draw fewer frames a second than the video has | `LIVE_KEYFRAME` | Every frame predicts from the last keyframe, so a frame a client missed costs it nothing; about 2.5 times the rate of `live` (9.5 against 3.6 Mbit/s in the [far-viewer runs](results.md)) |
| A video file you show often, or a server too small to encode live | pre-encode with `SHIP` | The best quality for its bandwidth; far slower than real time, so it runs ahead of time |
| The same, for viewers on slow links | pre-encode with `LOW` | About 39% less rate than `ship` for a VMAF mean of 70.6 instead of 77.9 |

Live walls use `live` unless you ask otherwise; `/mcav video mcv2` picks the preset per screen, for example:

```text
/mcav video mcv2 @a FFMPEG NONE 1920x1080 15x9 0 LIVE_ADAPTIVE NEAREST_COLOR "" /path/to/video.mp4
```

Pre-encoding runs in the same encoder budget, on a thread of its own, never on the server tick, and reports its
progress every thirty seconds:

```text
/mcav mcv2 encode /path/to/video.mp4 clip.mcs 1920x1080 SHIP
/mcav mcv2 play @a 15x9 0 1 clip.mcs
```

`play` sends a frame every given number of server ticks, `stream` at a frame rate of its own; both loop and have no
sound. Stream files live in `plugins/MCAV/mcv2`. How each preset was measured is on the
[live encoding](live.md) page.

## Configuration

| Key | Default | What it does |
|---|---|---|
| `mcv2.default-codec` | `dither` | The codec of a wall of maps whose command has no `--codec` |
| `mcv2.encoder-threads` | `0` (half the processors) | The threads every MCV2 screen and pre-encode of the server shares, 1 to 256 |
| `mcv2.native` | `auto` | `off` runs the encoder's pixel kernels in Java instead of the native library |
| `mcv2.pack.hosting` | `injector` | Where players download the pack: `injector`, `http` or `website` |
| `mcv2.pack.http-host`, `mcv2.pack.http-port` | empty, `25580` | For `http`: the address the players reach the server by, and the port |

How many threads to give the encoders, and what a server of a given size encodes live, is in the
[hardware guide](server.md#hardware-guide).

## The Resource Pack

**One pack for every screen.** The server builds one pack that decodes every MCV2 screen at once, up to eight, each in
a slot with its own video size. A screen takes a free slot of its size, so screens of sizes the pack already has start
and stop without anyone reloading. Only a screen of a new video size changes the pack, and its viewers are then asked
to load the new one. A ninth screen at once shows the dithered maps and says so.

**The reload hitch.** Loading the pack reloads the client's resources: a hitch of a second or more (1.1 to 3.1 seconds
from the offer in the lab). A screen whose encoder steps down to a smaller video adds a slot of that size, and with it
one reload.

**Players who join later.** A player who joins, rejoins or changes world while a screen plays for `@a` (or a selector
that matches them) is offered the pack on the spot and sees the dithered maps until it has loaded. A screen keeps the
chunks of its page frames loaded until it is released, so players who walk away and come back see it too. A player who
changes world sees an exact picture again from the next keyframe. A player who leaves and joins again while the video
is paused is shown the screen anew once it plays. Releasing a screen clears its wall for every viewer, with the pack or
without it.

**Where the pack is downloaded from.**

| `mcv2.pack.hosting` | How | Pick it when |
|---|---|---|
| `injector` (default) | Served on the Minecraft server's own port, next to the game | Players join this server directly. It does **not** work behind a proxy such as Velocity or BungeeCord: the players' download reaches the proxy's port, never this server's |
| `http` | A small HTTP server on `http-port`, reached at `http-host` | The server is behind a proxy, and a port can be opened for the players |
| `website` | Uploaded to mc-packs.net | No port can be opened; the pack is then public there. It holds only the decoder's shaders |

**Other packs.** The pack is optional and additive: it is offered with `required: false` and replaces no pack of the
server or the proxy. It overrides three files, `assets/minecraft/shaders/core/text.vsh`, `core/text.fsh` and
`assets/minecraft/post_effect/entity_outline.json`, and the pack a client loads last wins those. A server pack
(`server.properties`) is sent at join and the MCV2 pack later, so MCV2 wins: screens work, and your pack's versions of
those three files are shadowed while the MCV2 pack is loaded. After a screen stops, its free slot stays in the pack
for a minute for reuse; then the pack is rebuilt, or removed when no slots remain. Everything else in your pack is unaffected, and glowing entities keep their outline.
If another plugin sends a pack with those files after the MCV2 pack, that pack wins and MCV2 screens show nothing. To
keep your own text or outline shaders, merge your changes into the MCV2 pack's copies (`mcav/mcv2/pack`, and
`mcav/mcv2/chain.json` for the outline chain, in the `mcav-bukkit` jar the plugin downloads into the server's
`libraries/mcav` folder), which are vanilla's plus the decoder.

## Testing on Your Own Client

[Testing MCV2 on your own client](../mcv2-testing.md) walks through a server, a wall, a pre-encoded and a live stream,
what to look for, and the debug view: start the server with `-Dmcav.mcv2.debugView=true` and the pack also draws the
decoded picture one to one in a corner of the screen, with a square per page slot (green: a valid page) and one for the
decoder's decision on each rendered frame.

## Troubleshooting

**Players see the dithered maps on an MCV2 screen.** Their client has not loaded the pack yet, declined it, or could not
load it (the chat says which), or their [MCV2 client mod](client-mod.md) reports Iris shaders on, which the server log
says; they see the video again as soon as they turn the shaders off. A `--codec mcv2` screen is dithered for everyone
when no item frame holds its top-left map, when eight MCV2 screens already play, or when even the fastest encoder cannot
keep up; the command that started it says which. `/mcav video mcv2`, `/mcav mcv2 play` and `/mcav mcv2 stream` do not
start at all on a wall no item frame holds, and say so.

**The wall looks like a blank map, or shows the backs of item frames.** More than one item frame hangs in a block of the
wall, placed by hand, by another plugin or by an old version of MCAV: the extra frames hang in front of the maps,
backwards, and hide the dithered maps and the MCV2 picture alike, whatever is sent. Rebuild the wall with
`/mcav screen`, which now removes the frames already hanging where it places one.

**The pack loaded, but the wall shows nothing new.** Another pack that overrides `core/text` or `entity_outline.json` was
loaded after it; an Iris shader pack bypasses MCV2's text and outline pipelines; or the page frames are out of view:
the decoder runs only while one of the wall's hidden page frames is drawn, so look at the wall. Sodium 0.9.2 and
Iris 1.11.7 with shaders disabled displayed MCV2 correctly in the Minecraft 26.3 client matrix. With Iris shaders
active, Complementary Reimagined r5.9.3 and BSL 10.1.8 left the MCV2 picture blank or frozen even though the pack
reported that it loaded. Disable the shader pack or use ordinary dithered maps. A player with a modded client is told
in the chat once the pack loads that shaders may hide the picture, and that the [MCV2 client mod](client-mod.md)
shows them the dithered maps while Iris shaders are on. The unmodified-client matrix used software rendering;
it does not establish compatibility with every graphics driver, resource pack, improved-transparency setting or
rendering backend, including Vulkan.

**The picture freezes and jumps every few seconds.** The client draws fewer frames a second than the video has, so it
misses frames, and under the default prediction a missed frame is repaired only by the next keyframe (every 4 seconds
for `live` at 30 fps). A faster client fixes it; `LIVE_KEYFRAME` hides it at a higher rate.

**The pack does not download.** Behind Velocity or BungeeCord, set `mcv2.pack.hosting` to `http` with a port the players
can reach, or to `website`.

**The screen steps down.** Its encoder takes longer than the video gives a frame, and the message says by how much.
Give the encoders more threads (`mcv2.encoder-threads`), use a smaller resolution or a faster preset, or pre-encode.

**The server log says `MCV2 kernels: Java, ...`.** The native kernels could not be used, for the reason the line gives;
the Java kernels write the same stream at about 1.8 to 2.7 times the CPU.

**The picture lags the sound a little.** MCV2 adds the client's decode time to the picture, and the plugin does not
delay the sound to match. On the lab's software-rendered client the picture came 212 ms after the sound where the
dithered wall came 141.5 ms after it; the server's own delay from a frame to its pages being sent is 6 ms (median).

## FAQ

**Is the pack safe to accept?** It holds only shaders, and they read nothing but the maps the server sends. A
malformed stream cannot make the decoder read outside its textures or loop without bound; it is refused, and the last
picture stays.

**Does MCV2 change what players without the pack see?** No. They see the dithered maps, dithered on a thread of their
own, at the frame rate the dithering keeps up with.

**Why is the picture always bright?** The decoder draws after the world, in a post pass that has no light level for
the wall, like a map in a glow item frame.

**Why do the hidden frames glow?** A glowing entity is what makes the client run the post chain that decodes. Their
outline colour is removed before the frame is shown, so nobody sees the glow.

**Can MCV2 be used without the plugin?** Yes, from `mcav-bukkit`: see [MCV2 on Maps](../bukkit/mcv2.md).
