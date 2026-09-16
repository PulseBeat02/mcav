# MCAV Instance

To use the library, create an instance with `MCAV.api()` and call `install` with the modules you use. When you are
done with the library, call `release` to stop the modules and free native resources. Keep one instance for the whole
life of your application or plugin.

```java
  final MCAVApi api = MCAV.api();
  api.install(); // or api.install(BukkitModule.class, HttpModule.class, ...)
  // ... use the library ...
  api.release();
```

## Installation Lifecycle

`install` returns as soon as the library is usable. By then:

- the JavaCV natives, FFmpeg and OpenCV, are loaded;
- the modules you passed are started;
- the lookup tables, such as the map color table, are built.

This takes a few seconds on the first start of a machine and less afterward. FFmpeg players can be used right away.

VLC and yt-dlp are external programs, so they are prepared **in the background** after `install` returned. Each is
taken from the system if it is installed there, from the MCAV cache folder (`~/.mcav/cache`) if an earlier run
downloaded it, or downloaded into that folder otherwise. No administrator rights or system packages are needed. The
first download can take several minutes on a slow connection, and nothing waits for it: each capability simply turns
on once its program is ready. The log shows `VLC ready in <n> ms` when VLC is ready, or a warning that says why VLC
is not available.

Until a program is ready:

- `hasCapability` reports its capability as unavailable;
- `VideoPlayer.vlc()` throws an `IllegalStateException` saying that VLC is still being prepared, and the default
  yt-dlp parser does the same for yt-dlp;
- `whenCapabilityReady` returns a future that completes once the preparation finished.

`release` cancels a preparation that is still running: the download is interrupted, its temporary file is deleted,
and the installation thread has ended when `release` returns.

## Capabilities

MCAV builds on several native programs. FFmpeg and OpenCV are bundled and always available; VLC and yt-dlp are
installed when possible, and a failed installation only disables the features that need them instead of failing the
library.

| Capability       | Needed for                                               | Decided                   |
|------------------|----------------------------------------------------------|---------------------------|
| `VLC`            | `VideoPlayer.vlc()`                                      | In the background         |
| `FFMPEG`         | The FFmpeg players and FFmpeg commands                   | Before `install` returns  |
| `YT_DLP`         | Resolving web pages, such as YouTube videos, with yt-dlp | In the background         |
| `FACE_DETECTION` | The `FaceDetectionFilter`; its OpenCV natives need GTK 2 on Linux, so it is missing on many headless servers | Before `install` returns |

`hasCapability` tells whether a capability is available **right now**. A capability that is still being prepared is
not available yet, so the example below plays with FFmpeg until VLC is ready:

```java
  public static VideoPlayerMultiplexer createPreferredPlayer(final MCAVApi api) {
    final boolean vlcAvailable = api.hasCapability(Capability.VLC);
    if (vlcAvailable) {
      final VideoPlayerMultiplexer vlcPlayer = VideoPlayer.vlc();
      return vlcPlayer;
    }
    final VideoPlayerMultiplexer ffmpegPlayer = VideoPlayer.ffmpeg(); // bundled, always available
    return ffmpegPlayer;
  }
```

## Waiting for a Capability

To act as soon as a capability is ready, without polling, use `whenCapabilityReady`. The future completes with `true`
when the capability is available, and with `false` when it cannot be provided: the system does not support it, its
installation failed, or `release` cancelled it. A capability that is decided before `install` returns, or whose
preparation already finished, gets a future that is complete already.

```java
  public static CompletableFuture<VideoPlayerMultiplexer> createVlcPlayerWhenReady(final MCAVApi api) {
    final CompletableFuture<Boolean> vlcReady = api.whenCapabilityReady(Capability.VLC);
    return vlcReady.thenApply(available -> {
      if (!available) {
        final VideoPlayerMultiplexer ffmpegPlayer = VideoPlayer.ffmpeg(); // VLC cannot be used on this system
        return ffmpegPlayer;
      }
      final VideoPlayerMultiplexer vlcPlayer = VideoPlayer.vlc();
      return vlcPlayer;
    });
  }
```

Callbacks attached to the future never run on the installation threads of the library, so they may call any method
of the library, even `release`. A thread that may block, such as a worker thread, can also wait with `join()`:

```java
  final CompletableFuture<Boolean> ytdlpReady = api.whenCapabilityReady(Capability.YT_DLP);
  final boolean ytdlpAvailable = ytdlpReady.join(); // never call this on a server main thread
```

## Modules

Modules add features to the library. Pass their classes to `install`, then look them up with `getModule`. For
example, a Minecraft plugin installs the Bukkit module and hands it the plugin instance:

```java
  final MCAVApi api = MCAV.api();
  api.install(BukkitModule.class);
  final BukkitModule bukkit = api.getModule(BukkitModule.class);
  bukkit.inject(plugin);
```
