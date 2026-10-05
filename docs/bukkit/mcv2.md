# MCV2 on Maps

Besides dithering, `mcav-bukkit` can show video on a wall of maps with [MCV2](../mcv2/why.md), MCAV's own codec: the
server encodes each frame, sends it as the colours of a few hidden page maps, and a resource pack decodes it on the
player's GPU at the resolution you choose. Players without the pack see the same wall dithered. This page shows how a
plugin uses it; the sandbox plugin's `--codec mcv2` is built the same way.

## Setting Up Once

MCV2 needs three things per plugin, all set up once as the plugin starts, after the Bukkit module received the plugin
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
  share the threads instead of each taking the machine. Half the processors is the default; see the
  [hardware guide](../mcv2/server.md#hardware-guide).
- **`Mcv2Natives.install`** gives the native encoder kernels a folder to extract their library into; `describe()`
  returns the line to log, such as `native avx2 (linux-x86_64)`. Use your data folder, not a temporary folder, which
  hosted servers often mount without execution. Without a library for the platform, the Java kernels encode the same
  stream.
- **`Mcv2PackServer`** builds and hosts the one resource pack that decodes every MCV2 screen of your plugin, offers it
  to the viewers, and follows who loaded it. Call `shutdown()` in `onDisable`. The injector hosting serves the pack on
  the Minecraft port and does not work behind a proxy such as Velocity; see
  [hosting resource packs](resourcepack.md#hosting-resource-packs).

## Showing a Screen

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
frames and clears the dithered maps, and then `close()` on the lease, which gives the screen's slot back to the pack for the next screen of its
size. Keep the lease for that: `release()` does not free the slot, and once all 8 slots are taken `open` throws.

`Mcv2Result` resizes every frame to the configured video size and encodes the newest one on the shared budget; frames
that arrive while it works replace each other, so a slow encoder shows fewer frames instead of falling behind. Viewers
whose pack has not loaded see the wall dithered with the fallback algorithm, and so do viewers whose MCV2 client mod
reports that Iris draws a shader pack, which keeps the pack's shaders from decoding; they get the video back, without
rejoining, once it reports the shaders off. `packs.start()` listens for those reports on the `mcav:mcv2` plugin channel,
which a client learns of as it joins, so start the pack server in `onEnable`. A few settings matter:

| Setting | Default | What it does |
|---|---|---|
| `settings(EncoderSettings)` | `EncoderSettings.LIVE` | The preset: `LIVE`, `LIVE_ADAPTIVE` or `LIVE_FAST` for sources that play as they are encoded, `SHIP` and `LOW_BANDWIDTH` for pre-encoding ([choosing a preset](../mcv2/using.md#choosing-a-preset)) |
| `video(width, height)` | the wall's native size, 128 pixels per map | The resolution of the video, up to 4096 on a side; the pack scales it to the wall |
| `pageSlots(n)` | 8, or one per map of a smaller wall | How many pages a frame may have (8 carry 98 KB); the encoder searches a frame that would not fit again at a higher lambda, and a frame that still does not fit is not sent |
| `maxFrameRate(fps)` | 30 | Sources that paint faster, such as a browser, are thinned to it: a client decodes at most one frame per frame it draws |
| `backlogLimit(bytes)`, `unsentLimit(bytes)` | 128 KiB, 32 KiB | Per-viewer backpressure ([far viewers](../mcv2/server.md#far-viewers)) |

`setPacingListener` hears every step the screen's pacer takes when the encoder cannot keep up (a faster preset, fewer
frames a second, a smaller video, the dithered maps), with a message that says why; `setSmallerSizes(sizes, lease)`
names the smaller video sizes it may step down to.

## Pre-Encoding a File

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
threads with `SHIP` ([server cost](../mcv2/server.md#hardware-guide)).

```{note}
MCV2 is part of `mcav-bukkit` only; `mcav-common` holds none of it. The format, the decoder and the encoder are plain
Java in `me.brandonli.mcav.bukkit.media.mcv2` and its `encode` and `transport` packages, and are
[specified](../mcv2/format.md) completely.
```
