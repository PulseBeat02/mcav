# Testing MCV2 on your own client

This is how to watch MCV2 on a real Minecraft client and GPU, and what to compare with the numbers measured on the
devbox. Everything the devbox could check is listed at the end, with what only your client can show.

You need a vanilla Minecraft **26.2** client (any GPU with OpenGL 3.3; no mods) and the sandbox server of this branch.

## 1. Server

Set up the server as in the [manual test](manual-test.md), sections 1 and 2, **with the modules of your build**
(`-Pmcav.e2e=true`): the published snapshot modules do not contain MCV2. Then, before the first start, look at the MCV2
section of `run/plugins/MCAV/config.yml`:

- `mcv2.encoder-threads`: the threads every MCV2 encoder of the server shares; `0` (the default) is half the processors
  the server may use. The server log says, when the plugin loads, `MCV2 encoders share N of M processors`.

The pack is served on the Minecraft server's own port (nothing else to forward), and it is built for each screen: a
screen of another size or profile gets another pack.

## 2. A screen

Join, `op` yourself from the console, stand in an open area and build a wall once per location (a second
`/mcav screen` on the same blocks stacks a second item frame in each block, which hides the maps):

```
mcav screen "6x3" 200 black_concrete ~ ~ ~
```

The wall is 6 maps wide and 3 high, maps 200 to 217, starting at the top left as you face it.

## 3. Play

**A stream encoded ahead of time** - the path for any server, however small. Stream files live in
`run/plugins/MCAV/mcv2/`; a command names a file there (a name that leads out of that folder is refused).

```
mcav mcv2 encode "/absolute/path/to/video.mp4" "clip.mcs" "1536x384" ship
```

The encode runs on its own thread inside the encoder budget while the server keeps ticking; it reports progress every
30 seconds and `MCV2 encode finished: ...` at the end (`mcav mcv2 cancel` stops it). The resolution should have the
wall's shape: a 6x3 wall is 768x384 in map pixels, so 768x384 or 1536x768 (the pack scales the picture to the wall).
Then:

```
mcav mcv2 stream @a "6x3" 200 30 "clip.mcs"
```

sends 30 frames a second from its own thread (`mcav mcv2 play ... <ticks> ...` sends one frame every few server
ticks instead); `mcav mcv2 stop` ends it. Profiles: `ship` (the shipped 1080p30 point, VMAF >= 75), `low` (the
low-bandwidth point, VMAF >= 70), `live` (the fast search), and the robust reference modes `keyframe` (P frames predict
from the last keyframe), `intra` (every frame a keyframe) and `live_keyframe`.

**A live source**, encoded while it plays:

```
mcav video mcv2 @a FFMPEG NONE "1920x1080" "6x3" 200 live FILTER_LITE "" "/absolute/path/to/video.mp4"
```

`live` is the profile for this; the screen paces itself to what the encoder budget sustains and tells whoever started it
(`MCV2 screen steps down to ...`); `mcav video release` ends it.

## 4. What to look for

- **The pack prompt.** Accept it. Your client then shows the video decoded over the wall; declining it, or a client that
  cannot load it, shows the ordinary dithered maps instead (the chat says so), never a broken screen.
- **The picture.** The video fills the wall exactly, edge to edge, in front of anything behind the wall and behind
  anything in front of it, from any angle you look at it. It is drawn fullbright (the post chain has no world light), and
  clouds and the hand are drawn over it.
- **Ordinary maps still render** beside it, unchanged.
- **Motion.** With `stream` at 30 fps the picture should move at 30 fps. A client that renders fewer frames per second
  than the video decodes only what it renders: with the shipped profiles (each P frame predicts from the frame before)
  it then shows a frozen picture until the next keyframe, every two seconds. That is the codec's reference model, not
  a bug; `keyframe` streams avoid it at about 1.7 times the rate.
- **Your frame time.** Open F3 and compare the frame time with the wall in view and out of view. The decoder runs on
  every rendered frame while page frames are in view; measured on an Intel UHD 630: 7.9 ms for a frame that brings a new
  video frame, 8.8 ms for a keyframe, 6.6 ms for one that does not (all at 1080p). A GPU twice that fast runs it in
  under 4 ms.

## 5. Shader errors

The client log (`.minecraft/logs/latest.log`, see the manual test's section 6 for other launchers) must show no
`Failed to compile`, `Couldn't compile`, `Failed to link` or `post_effect` errors after the pack loads. If the wall shows
nothing:

1. The chain only runs while one of the wall's glowing page frames is in view: look at the wall, not past it.
2. Press F3+T to reload the resource packs and watch the log.
3. Start the server with `-Dmcav.mcv2.debugView=true` (in `JVM_ARGS`, or the `runServer` task's `jvmArgs`): the pack then
   also draws the decoded picture one to one in the corner of your screen, with a row of squares showing which pages
   arrived and whether their CRCs passed. Green squares and a picture there, but nothing on the wall, means placement;
   red squares mean the pages were damaged on the way.

Send the client log, a screenshot and the server log's lines around `MCV2` if something is wrong.

## 6. Numbers to compare

| what | measured on the devbox | how to see it on yours |
| --- | --- | --- |
| upload per viewer, live 1080p60 | 5.38 Mbit/s of map packets, 3.50 after the game's zlib (-35%); TCP payload measured 3.4-3.6 Mbit/s | the server's network monitor, or `nload` on the server |
| upload per viewer, ship 1080p30 | 3.40 Mbit/s, 2.19 after zlib | as above |
| compression CPU per viewer | 2.0% of a core (live 60 fps), 1.3% (ship 30 fps) | a profiler on the server's Netty threads |
| decode per new video frame, UHD 630 | 7.9 ms (P frame), 8.8 ms (keyframe), 6.6 ms without new video | F3 frame time, wall in view and out |
| server tick with 1 or 2 live screens | TPS 20.0; MSPT p95 0.63 / 0.83 ms at the default budget | `/mspt` (Paper) |
| live encode, 1080p | see the report's LIVE 1080p60 section: short of 60 fps on a 6-core machine | the screen's pacing messages |

## 7. What was verified on the devbox, and what needs your client

Verified on the devbox:

- the Java decoder bit-exact with the reference on the conformance corpus, edge streams and 24,864 generated frames;
  the encoder byte-identical to the reference on both shipped profiles;
- the resource pack's decode chain bit-exact with the reference on the Intel UHD 630 (EGL) and on Mesa llvmpipe, pass
  for pass outside Minecraft, and **in the real 26.2 client** on llvmpipe: 300 of 300 captured frames of a ship stream
  identical to the reference decode, byte for byte;
- screen placement in several camera views, ordinary maps unaffected, the dithered fallback for a declined pack;
- transport at 60 frames a second off the server tick, per-viewer backpressure over simulated distant links, the
  server's tick with live screens encoding.

Needs your client:

- **real GPUs and drivers**: the devbox's GPU is an Intel UHD 630 driven headless through EGL, and its client renders on
  llvmpipe (software, about 3 fps at 1080p). NVIDIA and AMD drivers, macOS (OpenGL 4.1 over Metal) and Windows have not
  run the pack; the shaders stay within GLSL 330 and the post-chain features vanilla uses;
- **the look at full speed**: motion, the frame time the decoder costs a player, and a client rendering at 60 fps or
  more, which the software-rendered client cannot;
- **a network between you and the server**: the devbox tested distant links with simulated delay and loss on one
  machine.
