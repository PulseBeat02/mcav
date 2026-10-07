(mcv2-server)=
# Server and Network Cost

What an MCV2 screen costs the server and the network, per screen and per viewer. Everything was measured on Temurin 25
(HotSpot C2, the JVM the common server images ship) on a 6-core i7-8700 with 12 hardware threads, shared with other
work, and is in the [design doc, sections 7, 10, 11 and 14](../mcv2-integration.md#11-server-viability) or on the
[results page](results.md#far-viewers). These are the original integration workloads; later CPU results and
loaded-host limits are summarized in [current usage](using.md#troubleshooting).

## Bandwidth per Viewer

A screen is encoded once; every viewer who can see the wall receives the same stream. A viewer farther from the wall
than their view distance, or in another world, receives nothing (they are measured once a second, and one who could
see the wall goes out of range 32 blocks farther); coming back, they start on a keyframe. At 1080p30:

| Preset and content | Map packets | After the game's zlib |
|---|---:|---:|
| `live`, quiet content (1080p30 proxy) | 2.80 Mbit/s | 1.83 Mbit/s |
| `live`, fast gameplay | 13.0 Mbit/s | 8.3 Mbit/s |
| `adaptive`, fast gameplay | 15.2 Mbit/s | 8.6 Mbit/s |
| `ship`, pre-encoded, quiet content | 3.40 Mbit/s | 2.19 Mbit/s |

Give a viewer a link with headroom over that: at twice the stream's rate and up to about 200 ms of round trip the
picture stays smooth. The game compresses every packet; deflating costs the server 1.3 to 2.0% of a core per viewer
([transport](transport.md#the-games-compression)).

## Far Viewers

Everything reaches a player over its one Minecraft connection, so a viewer whose link cannot keep up with the stream
must neither hold the other viewers back nor let a growing backlog of video delay its own game packets. Every viewer
therefore gets a link of its own (`Mcv2Link`):

- **Only frames the viewer can decode**: a keyframe, or a P frame whose reference is the last frame or keyframe that
  viewer was sent. A viewer who missed a frame waits for the next frame it can decode, and its client keeps the last
  picture meanwhile.
- **A backlog threshold**: before accepting a frame, the link checks the viewer's video that was handed to its
  connection but has not yet been written. The default threshold is 128 KiB (`Mcv2Configuration.backlogLimit`), or
  twice that for a keyframe. An existing backlog at the threshold still admits one frame; adding that frame can
  take the backlog above it.
- **No backlog hidden in the operating system**: Linux would take megabytes of unsent data into a socket's buffer at
  once (measured: 1.2 MB on a 200 ms link), where the limit cannot see it and every game packet waits behind it. So a
  viewer's connection has its unsent bytes capped at 32 KiB with `TCP_NOTSENT_LOWAT` (on Linux, where Paper uses the
  epoll transport). Bytes in flight do not count, so the cap does not slow a connection down.

Measured on four simulated links (the host's `tc netem` on the lab server's port, a real client, pre-encoded 1080p
streams, backpressure on and off; the first live profile at 60 fps, 5.4 map Mbit/s):

| Link | Backpressure on: frames held, the game's round trip at p95 | Backpressure off |
|---|---|---|
| nearby (15 ms, no loss) | 0%, 36 ms (the game alone: 35) | 0%, 37 ms |
| another continent (100 ms each way, ±10) | 6.6%, 239 ms (alone: 217) | 0%, 259 ms, worst 759 |
| far and lossy (100 ms ±20, 1% loss) | 7.5%, 412 ms (alone: 335) | 0%, **1,572 ms, worst 3.2 s, a backlog of 6 MB** |
| thin (40 ms, 0.2% loss, 6 Mbit/s) | 8.2%, 182 ms (alone: 90) | 0%, 319 ms, worst 1.2 s |

Backpressure costs nothing nearby and is what keeps a far viewer's game playable: without it, on the lossy link the
server's backlog for that viewer grew to 5 to 6 MB and the game's round trip reached 1.6 s at p95. A stream faster than
the link (9.5 Mbit/s on the 6 Mbit/s link) still hurt, because what TCP has already put in flight no backlog limit
takes back: a viewer needs a link faster than the stream. On the lossy link, BBR delivered 92.5% of the frames and
CUBIC 19%.

```{note}
For far viewers: run the server with BBR {cite}`cardwell2016bbr` (`net.ipv4.tcp_congestion_control=bbr`; Linux defaults to CUBIC), keep
backpressure on (it is the default), and give viewers on links slower than the stream a smaller screen or a
pre-encoded `ship` or `low` stream instead of the keyframe-reference presets, which need about 2.5 times the rate.
```

## One Encoder Budget per Server

Every MCV2 screen of a server, and every pre-encode, shares one set of encoder threads (`EncoderPool`): by default
**half the processors the JVM may use, at least one**, or `mcv2.encoder-threads` in `plugins/MCAV/config.yml` (1 to
256). Java counts the processors of a container's CPU quota, so a 4-CPU container on a 64-core host gets two encoder
threads. The pool never starts a thread beyond its size, not even while one waits, and two screens share it frame by
frame instead of each taking the machine. The threads run at the lowest priority, which Windows honours and Linux
ignores (HotSpot applies Java priorities only as root), so on Linux the size of the budget is what keeps processors
free for the game. The server log says at startup `MCV2 encoders share N of M processors`.

The encoders never run on the server thread, and the game kept its **20 TPS**: with one and two live 1080p screens
encoding in the default budget, the tick's p95 rose from 0.53 to 0.72 ms to 0.78 to 0.91 ms. With a video, a browser and
a VM playing through MCV2 at once (three 640x384 screens), the main thread took 0.6 to 1.1 ms a tick against 0.6 to
0.8 with nothing playing, and 20.0 TPS in every sample. A live 1080p encoder keeps about 49 MB of heap (20 MB at 720p).

## Adaptive, Never Overload

`Mcv2Pacer` watches the encode time of every P frame against the time the video gives a frame. When the smoothed time
has been over for a second, the screen steps down to the first rung its measurements predict to fit in 85% of a
frame: first a faster preset (`live`, `adaptive`, `live-fast`), then fewer frames a second (every second, third, fourth
or sixth frame, not below 10 fps), then a smaller video (two thirds, then half), then the dithered maps, which need no
encoder. It climbs back once a rung above has been predicted to fit in 70% for five seconds, keeps off a rung it had to
leave (ten seconds, doubling to ten minutes), and from the dithered maps tries encoding again after 30 seconds
(doubling). Every step is logged with the numbers that decided it, and the plugin tells whoever started the screen,
for example:

```text
MCV2 screen steps down to 1920x1080 at 30 fps with the live-fast search: encoding 1920x1080 with the live search takes
36.0 ms per frame, more than the 33.3 ms a frame has at 30 fps with the encoder threads it has
```

## Hardware Guide

The time to encode a frame with `live` (mean / p95 ms, and in brackets the CPU time per frame), as a screen encodes it,
every frame verified, the native AVX2 kernels, on the i7-8700 limited to that many threads:

| Source (30 fps) | 1 thread | 2 | 3 | 4 | 6 | 8 | 12 |
|---|---|---|---|---|---|---|---|
| 1920x1080, quiet content | 73.2 / 84.7 (79) | 41.1 / 50.0 (87) | 30.5 / 38.1 (93) | 24.4 / 29.7 (94) | 20.3 / 24.2 (113) | 18.2 / 20.9 (124) | 18.0 / 21.0 (146) |
| 1920x1080, gameplay | 112.3 / 139.3 (121) | 64.5 / 90.9 (133) | 44.4 / 57.6 (130) | 35.6 / 47.0 (136) | 29.1 / 37.5 (162) | 26.6 / 34.2 (186) | 25.2 / 32.3 (213) |
| 1280x720, quiet content | 39.0 / 48.1 (49) | 22.8 / 34.3 (52) | 17.0 / 21.8 (60) | 14.2 / 18.3 (60) | 11.7 / 14.1 (66) | 10.8 / 13.5 (70) | 10.6 / 13.7 (80) |
| 1280x720, gameplay | 57.4 / 72.1 (69) | 32.4 / 44.6 (73) | 24.2 / 33.1 (81) | 19.5 / 26.9 (79) | 16.8 / 22.1 (92) | 15.4 / 19.2 (101) | 15.3 / 19.4 (115) |

**Cores needed = CPU ms per frame x frames a second / 1000**, with the one-thread CPU time: 1080p30 needs about 2.4
cores of this CPU on quiet content and 3.6 on fast gameplay, 720p30 1.5 and 2.1. The frame time stops falling past
about 8 threads, where the 9 to 11 ms of a frame outside the parallel search remain. How many frames a second the
faster of `live` and `adaptive` sustains with the default budget, `1000 / mean ms`, at most the source's 30 (the
[design doc](../mcv2-integration.md#11-server-viability) also gives the adaptive measurements):

| Server | Encoder threads | 1080p30, quiet content | 1080p30, gameplay | 720p30, quiet content | 720p30, gameplay |
|---|---:|---|---|---|---|
| 2 processors | 1 | 13 fps | 10 fps | 25 fps | 20 fps |
| 4 processors | 2 | 24 fps | 17 fps | 30 fps | 30 fps |
| 6 processors | 3 | 30 fps | 25 fps | 30 fps | 30 fps |
| 8 processors or more | 4 or more | 30 fps | 30 fps | 30 fps | 30 fps |

A screen never plays at a rate between these rungs: from a 30 fps source the pacer encodes 30, 15 or 10 frames a
second (every frame, every second or every third), at the size asked for, two thirds or half of it, or switches to the
dithered maps. Where the table shows less than 30, the pacer first tries the faster presets at 30 frames a second, then
the fastest one at 15 and 10, then the smaller sizes, and plays the first rung its measured times predict to fit.

"Processors" are this CPU's hardware threads at 3.2 to 4.6 GHz; a VPS's vCPU is usually a hyperthread of a busier,
often slower host, so measure its available budget and contention instead of applying a fixed percentage reduction. With the Java kernels (`mcv2.native: off`, or a platform without a
library) a live frame costs about 2.7 times the CPU on gameplay and 1.8 times on quiet content. ARM64 servers were not
measured.

**Small servers should pre-encode.** `/mcav mcv2 encode` runs the `ship` search on its own thread inside the budget,
never on the server tick: a minute of 1080p30 takes 57 minutes on 2 threads and 26 on 4 (with `live`, 3.0 to 6.4
minutes on 2 threads); `/mcav mcv2 play` then plays the stream without any encoding.
