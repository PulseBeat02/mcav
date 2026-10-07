# MCV2 on Maps

Besides dithering, `mcav-bukkit` can show video on a wall of maps with MCV2, MCAV's own codec: the server encodes each
frame, sends it as the colours of a few hidden page maps, and a resource pack decodes it on the player's GPU at the
resolution you choose. Players without the pack see the same wall dithered. How the codec works, what it costs and how
it compares with H.264, VP9 and AV1 is in [MCV2: 1080p Video on Vanilla Minecraft Maps](../mcv2.md). This page is the
how-to: the plugin's commands and settings for server admins, then the Java API for plugin developers.

## In the Plugin

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
because the server only knows the item frames of loaded chunks. The resolution is what the players see: a 10x6 wall
shows 1280x768 dithered pixels, or with MCV2 a 1280x720 video in full colour, scaled to the wall. MCV2 needs maps, so
the block, chat, entity and scoreboard displays always show their own picture.

Viewers are asked to load the MCV2 resource pack. Those who accept see the MCV2 picture once their client has loaded
it; those who decline, or whose client cannot load it, keep seeing the dithered maps of the same wall, and the chat
tells them why. A pre-encoded stream played with `/mcav mcv2 play` or `/mcav mcv2 stream` has no dithered maps: there,
a player without the pack sees nothing new on the wall.

### Presets

| Your source | Preset | Why |
|---|---|---|
| Anything that plays live: a browser, a VM, a VNC desktop, a stream, a camera, and video files by default | `live` (chosen for you) | The best picture per bit that encodes a 1080p30 frame in time on a 6-core server; a screen that cannot keep up steps down by itself |
| Fast gameplay or a busy picture on a small encoder budget | `LIVE_ADAPTIVE` | Switches to the faster search while the picture moves |
| A server that cannot keep up with `live` at all | `LIVE_FAST` | The fastest search |
| Viewers whose clients draw fewer frames a second than the video has | `LIVE_KEYFRAME` | Every frame predicts from the last keyframe, so a frame a client missed costs it nothing, at a higher rate |
| A video file you show often, or a server too small to encode live | pre-encode with `SHIP` | The best picture for its rate; far slower than real time, so it runs ahead of time |
| The same, for viewers on slow links | pre-encode with `LOW` | Less rate for a lower VMAF |

Live walls use `live` unless you ask otherwise; `/mcav video mcv2` picks the preset per screen:

```text
/mcav video mcv2 @a FFMPEG NONE 1920x1080 15x9 0 LIVE_ADAPTIVE NEAREST_COLOR "" /path/to/video.mp4
```

Pre-encoding runs in the same encoder budget, on a thread of its own, never on the server tick, and reports its
progress every thirty seconds; `play` then sends a frame every given number of server ticks, `stream` at a frame rate of
its own, both looping and without sound. Stream files live in `plugins/MCAV/mcv2`:

```text
/mcav mcv2 encode /path/to/video.mp4 clip.mcs 1920x1080 SHIP
/mcav mcv2 play @a 15x9 0 1 clip.mcs
```

### Configuration

| Key | Default | What it does |
|---|---|---|
| `mcv2.default-codec` | `dither` | The codec of a wall of maps whose command has no `--codec` |
| `mcv2.encoder-threads` | `0` (half the processors) | The threads every MCV2 screen and pre-encode of the server shares, 1 to 256 |
| `mcv2.native` | `auto` | `off` runs the encoder's pixel kernels in Java instead of the native library; both write the same stream |
| `mcv2.pack.hosting` | `injector` | Where players download the pack: `injector`, `http` or `website` |
| `mcv2.pack.http-host`, `mcv2.pack.http-port` | empty, `25580` | For `http`: the address the players reach the server by, and the port |

The server log says at startup `MCV2 encoders share N of M processors` and which encoder kernels run, such as
`MCV2 kernels: native avx2 (linux-x86_64)`. Full syntax: [commands](../plugin/commands.md) and
[configuration](../plugin/config.md).

### The Resource Pack

One pack decodes every MCV2 screen of the server, up to eight at once, each in a slot of its own video size. A screen
of a size the pack already has starts and stops without anyone reloading; only a screen of a new video size changes the
pack, and its viewers are asked to load the new one. Loading the pack reloads the client's resources, a hitch of a
second or more. A player who joins, rejoins or changes world while a screen plays for them is offered the pack on the
spot and sees the dithered maps until it has loaded.

| `mcv2.pack.hosting` | How | Pick it when |
|---|---|---|
| `injector` (default) | Served on the Minecraft server's own port | Players join this server directly. It does not work behind a proxy such as Velocity or BungeeCord |
| `http` | A small HTTP server on `http-port`, reached at `http-host` | The server is behind a proxy, and a port can be opened for the players |
| `website` | Uploaded to mc-packs.net | No port can be opened; the pack is then public there. It holds only the decoder's shaders |

The pack is optional and additive: it is offered with `required: false` and replaces no other pack. It overrides
`assets/minecraft/shaders/core/text.vsh`, `core/text.fsh` and `assets/minecraft/post_effect/entity_outline.json`, and
the pack a client loads last wins those. A server pack (`server.properties`) is sent at join and the MCV2 pack later, so
MCV2 wins. To keep your own text or outline shaders, merge your changes into the MCV2 pack's copies (`mcav/mcv2/pack`,
and `mcav/mcv2/chain.json` for the outline chain, in the `mcav-bukkit` jar the plugin downloads into the server's
`libraries/mcav` folder), which are vanilla's plus the decoder.

### The Client Mod for Iris Shader Players

Iris draws the world with its shader pack's own programs, which replace the MCV2 pack's shaders, and it writes the
shader pack's final image over the screen after the level is drawn. On its own, a player with Iris shaders on sees an
MCV2 wall frozen or blank. The MCAV MCV2 Client mod fixes that with Iris 1.11.7: it reads the frame's MCV2 maps as the
game draws them, builds the transport strip on the CPU once the shader pack's final image is done, writes it into the
top rows of the screen and runs the pack's post chain over it. The screens decode under shader packs such as
Complementary and BSL like they do without them, and a frame without MCV2 maps is left exactly as Iris drew it.

A shader pack costs frames: a client that draws fewer frames a second than the video has sees the picture freeze and
jump (see Troubleshooting below), and `LIVE_KEYFRAME` hides that at a higher rate.

The mod decodes only under the Iris version it was proven with. With another one, or if the game's code it hooks into
isn't there, it tells the server instead: while a shader pack is on, every MCV2 screen shows that player the dithered
maps, and with the shaders off the video again, without rejoining. Vanilla, Sodium, and Iris with its shaders off all
show MCV2 and need no mod.

The mod is for Minecraft 26.3, on Fabric (Loader 0.19.5 or newer, with Fabric API) or NeoForge (26.3.0.43-beta or
newer), with or without Iris. Build it with `./gradlew :mcav-mcv2-client:assemble` and put
`mcav-mcv2-client/build/libs/mcav-mcv2-client-fabric.jar` or `mcav-mcv2-client-neoforge.jar` into the client's `mods`
folder; it is not published anywhere yet. It talks on one plugin channel, `mcav:mcv2`, which carries nothing else, and
only to a server that registered it. A report is four bytes:

| Byte | Meaning |
|---|---|
| 0 | the report's version, 1 |
| 1 | 1 if Iris is installed, else 0 |
| 2 | the shader pack: 0 none in use, 1 in use, 2 unknown (an Iris without the API the mod asks) |
| 3 | 1 if the mod decodes MCV2 under the shader pack in use (Iris 1.11.7), else 0 |

The mod sends a report when the player joins and whenever it changes, once it held for a second. Under Iris 1.11.7 it
reports that MCV2 decodes once the game has drawn a map, so a player who joins with shaders on may see the dithered maps
for a moment before the video starts. For admins there is nothing to set up: the pack server listens on the channel from
the moment it starts. A client learns of the channel as it joins, so players who joined before the pack server started
report only after they rejoin. The server log says what each mod reports and what that player's screens show. Reports
longer than 64 bytes, of another version or malformed are ignored, and so is one sent faster than once a second after a
burst of 5; a player without the mod sees MCV2 screens as before.

### Troubleshooting

- **Players see the dithered maps on an MCV2 screen.** Their client has not loaded the pack yet, declined it or could
  not load it (the chat says which), or their client mod reports Iris shaders on that it can't decode under, with an
  Iris other than 1.11.7 (the server log says so). A
  `--codec mcv2` screen is dithered for everyone when no item frame holds its top-left map, when eight MCV2 screens
  already play, or when even the fastest encoder cannot keep up; the command that started it says which.
- **The wall shows the backs of item frames.** More than one item frame hangs in a block of the wall. Rebuild it with
  `/mcav screen`, which removes the frames already hanging where it places one.
- **The pack loaded, but the wall shows nothing new.** Another pack that overrides `core/text` or `entity_outline.json`
  was loaded after it; the client uses improved transparency, an Iris shader pack without the client mod, or the Vulkan
  backend; or the hidden page frames are out of view: the decoder only runs while one is drawn, so look at the wall.
- **The picture freezes and jumps every few seconds.** The client draws fewer frames a second than the video has, and a
  missed frame is repaired only by the next keyframe. A faster client fixes it; `LIVE_KEYFRAME` hides it at a higher
  rate.
- **The pack does not download.** Behind Velocity or BungeeCord, set `mcv2.pack.hosting` to `http` with a port the
  players can reach, or to `website`.
- **The screen steps down.** Its encoder takes longer than the video gives a frame, and the message says by how much.
  Give the encoders more threads, use a smaller resolution or a faster preset, or pre-encode.
- **Something else.** Start the server with `-Dmcav.mcv2.debugView=true`: the pack then also draws the decoded picture
  one to one in a corner of the screen, with a square per page slot (green: a valid page) and one for the decoder's
  decision on each rendered frame. Check the client log (`.minecraft/logs/latest.log`) for `Failed to compile`,
  `Failed to link` or `post_effect` errors.

## In Your Own Plugin

The sandbox plugin's `--codec mcv2` is built from the same API as any other plugin can use.

### Setting Up Once

MCV2 needs three things per plugin, set up once as the plugin starts, after the Bukkit module received the plugin
instance with `inject`:

```java
import java.nio.file.Path;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderPool;
import me.brandonli.mcav.bukkit.media.mcv2.encode.Mcv2Natives;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;

  // call on the main thread, in onEnable
  public static Mcv2PackServer setUpMcv2(final Path dataFolder) {
    EncoderPool.setSharedThreads(0); // the threads every MCV2 encoder shares; 0 is half the processors
    Mcv2Natives.install(dataFolder.resolve("natives"), "auto"); // or "off" for the Java kernels only

    final Path packFolder = dataFolder.resolve("mcv2-pack");
    final Mcv2PackServer packs = new Mcv2PackServer(
      packFolder,
      PackHosting::injector, // or zip -> PackHosting.http(zip, host, port), or PackHosting::website
      false, // the debug view
      player -> player.sendMessage("Load the pack to see the video at its full resolution"),
      player -> player.sendMessage("Without the pack, you see the dithered picture")
    );
    packs.start();
    return packs;
  }
```

- **`EncoderPool`** is the one encoder budget of the server. Every MCV2 encoder uses `EncoderPool.shared()`, so screens
  share the threads instead of each taking the machine. Half the processors is the default; what that buys is in
  [what MCV2 costs](../mcv2.md#what-it-costs).
- **`Mcv2Natives.install`** gives the native encoder kernels a folder to extract their library into; `describe()`
  returns the line to log, such as `native avx2 (linux-x86_64)`. Use your data folder, not a temporary folder, which
  hosted servers often mount without execution. Without a library for the platform, the Java kernels encode the same
  stream.
- **`Mcv2PackServer`** builds and hosts the one resource pack that decodes every MCV2 screen of your plugin, offers it
  to the viewers, and follows who loaded it. Call `shutdown()` in `onDisable`. The injector hosting serves the pack on
  the Minecraft port and does not work behind a proxy such as Velocity; see
  [hosting resource packs](resourcepack.md#hosting-resource-packs).

### Showing a Screen

A screen is a wall of item frames holding consecutive maps, as `MapConfiguration` describes it, built by your plugin.
Describe it with an `Mcv2Configuration`, take a slot of the pack for it, and put an `Mcv2Result` at the end of a video
pipeline:

```java
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result;
import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderSettings;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.bukkit.entity.ItemFrame;

  // the result and the lease of one wall: release() the result, then close() the lease, on the main thread
  public record Mcv2Wall(Mcv2Result result, Mcv2PackServer.Lease lease) {}

  // call on the main thread; topLeft is the item frame holding the wall's first map
  public static Mcv2Wall showOnMcv2Wall(
    final Mcv2PackServer packs,
    final ItemFrame topLeft,
    final int firstMapId,
    final Collection<UUID> viewers,
    final VideoPlayerMultiplexer player
  ) {
    final Mcv2Configuration requested = Mcv2Configuration.builder()
      .viewers(viewers)
      .origin(topLeft.getLocation())
      .facing(topLeft.getFacing())
      .map(firstMapId)
      .columns(15)
      .rows(9)
      .video(1920, 1080)
      .settings(EncoderSettings.LIVE)
      .build();
    final Mcv2PackServer.Lease lease = packs.open(requested); // throws IllegalStateException when every slot plays
    final Mcv2Configuration configuration = lease.getConfiguration();
    final DitherAlgorithm fallback = DitherAlgorithm.filterLite(); // for the viewers without the pack
    final Mcv2Result result = new Mcv2Result(configuration, packs.getViewers(), fallback);
    try {
      result.start();
      final VideoPipelineStep pipeline = VideoPipelineStep.of(result);
      final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
      videoCallback.attach(pipeline);
    } catch (final RuntimeException failure) {
      result.release();
      lease.close(); // otherwise the slot stays taken until the server stops
      throw failure;
    }
    return new Mcv2Wall(result, lease);
  }
```

When the video is over, call `release()` on the result on the main thread, which stops the encoder, removes the page
frames and clears the dithered maps, and then `close()` on the lease, which gives the screen's slot back to the pack for
the next screen of its size. Keep the lease for that: `release()` does not free the slot, and once all 8 slots are taken
`open` throws.

`Mcv2Result` resizes every frame to the configured video size and encodes the newest one on the shared budget; frames
that arrive while it works replace each other, so a slow encoder shows fewer frames instead of falling behind. Viewers
whose pack has not loaded see the wall dithered with the fallback algorithm, and so do viewers whose [MCV2 client
mod](#the-client-mod-for-iris-shader-players) reports that Iris draws a shader pack it can't decode under; they get the
video back, without rejoining, once it reports the shaders off. `packs.start()` listens for those
reports on the `mcav:mcv2` plugin channel, which a client learns of as it joins, so start the pack server in `onEnable`.
A few settings matter:

| Setting | Default | What it does |
|---|---|---|
| `settings(EncoderSettings)` | `EncoderSettings.LIVE` | The preset: `LIVE`, `LIVE_ADAPTIVE` or `LIVE_FAST` for sources that play as they are encoded, `SHIP` and `LOW_BANDWIDTH` for pre-encoding ([presets](#presets)) |
| `video(width, height)` | the wall's native size, 128 pixels per map | The resolution of the video, up to 4096 on a side; the pack scales it to the wall |
| `pageSlots(n)` | 8, or one per map of a smaller wall | How many pages a frame may have (8 carry 98 KB); the encoder searches a frame that would not fit again at a higher lambda, and a frame that still does not fit is not sent |
| `maxFrameRate(fps)` | 30 | Sources that paint faster, such as a browser, are thinned to it: a client decodes at most one frame per frame it draws |
| `backlogLimit(bytes)`, `unsentLimit(bytes)` | 128 KiB, 32 KiB | Per-viewer backpressure: a viewer whose connection falls behind skips to the next frame they can decode, without holding back the others ([pages on maps](../mcv2.md#pages-on-maps)) |

`setPacingListener` hears every step the screen's pacer takes when the encoder cannot keep up (a faster preset, fewer
frames a second, a smaller video, the dithered maps), with a message that says why; `setSmallerSizes(sizes, lease)`
names the smaller video sizes it may step down to.

### Pre-Encoding a File

`Mcv2FileEncoder` encodes a video file ahead of time with any preset, usually `EncoderSettings.SHIP`, inside a budget,
into a stream of frames, each written as its length (a little-endian 32-bit integer) and its bytes:

```java
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderPool;
import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderSettings;
import me.brandonli.mcav.bukkit.media.mcv2.encode.Mcv2FileEncoder;

  // call off the main thread: this takes minutes per minute of 1080p video
  public static Mcv2FileEncoder.Result preEncode(final Path video, final Path stream) throws IOException, InterruptedException {
    final EncoderPool budget = EncoderPool.shared();
    try (
      Mcv2FileEncoder.FrameReader frames = Mcv2FileEncoder.ffmpeg(video, 1920, 1080);
      OutputStream out = new BufferedOutputStream(Files.newOutputStream(stream))
    ) {
      return Mcv2FileEncoder.encode(frames, 1920, 1080, EncoderSettings.SHIP, budget, out, frame -> {});
    }
  }
```

The last argument hears the number of every frame as it is written. A minute of 1080p30 takes 26 minutes on four
threads with `SHIP`.

```{note}
MCV2 is part of `mcav-bukkit` only; `mcav-common` holds none of it. The format, the decoder and the encoder are plain
Java in `me.brandonli.mcav.bukkit.media.mcv2` and its `encode` and `transport` packages, and are
[specified](../mcv2.md#the-bitstream) completely.
```
