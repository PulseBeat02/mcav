# Introduction to Players

MCAV provides a variety of players. All of them follow the same idea: frames and samples come from a source, run
through the pipelines you attach, and the result is shown or sent by your filters.

```{warning}
Release every player when you are done with it by calling `release()`. Players own threads, native resources, and
network connections that are only freed on release.
```

# Video Players

Create a video player with one of the factories of `VideoPlayer`:

| Factory                | Backend                                                         | Audio |
|------------------------|-----------------------------------------------------------------|-------|
| `VideoPlayer.ffmpeg()` | The FFmpeg libraries bundled with MCAV; the recommended default | Yes   |
| `VideoPlayer.vlc()`    | VLC, which must be installed or installable (see capabilities)  | Yes   |
| `VideoPlayer.opencv()` | The OpenCV video reader; where the bundled OpenCV build has no file backend, as on Linux, files are read with the bundled FFmpeg instead | Only there |
| `VideoPlayer.device()` | Cameras and capture cards, played from a `DeviceSource`         | No    |

```{note}
VLC is prepared in the background after `install` returned (see the [installation lifecycle](instance.md#installation-lifecycle)).
When the machine has no VLC, the first start downloads it into the MCAV cache folder, which can take several minutes.
Until VLC is ready, `VideoPlayer.vlc()` throws an `IllegalStateException` that says VLC is still being prepared; if the
preparation found that VLC is not available on this system, the exception says so instead. Wait for
`api.whenCapabilityReady(Capability.VLC)`, or play with `VideoPlayer.ffmpeg()` in the meantime.
```

Every video player has three slots: a `VideoAttachableCallback` for the video pipeline, an `AudioAttachableCallback`
for the audio pipeline, and a `DimensionAttachableCallback` for the size frames are scaled to. Pipelines can be
attached before starting and swapped while playing.

```{note}
Frames always reach the pipeline as 8-bit BGR images with three channels, and audio always as signed 16-bit
little-endian PCM at 48 kHz with two interleaved channels, whatever the source format is.
```

The example below plays a file for the requested duration with its audio on the speakers of the computer. Run it
on a worker thread; it waits during playback. The `display` filter is yours and shows the frames, for example in a window.

```java
  public static void playVideoFile(final Path videoFile, final VideoFilter display, final Duration playTime)
    throws InterruptedException {
    final VideoPipelineStep videoPipelineStep = VideoPipelineStep.of(display);
    final DirectAudioOutput speakers = new DirectAudioOutput();
    final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
    try {
      speakers.start(); // throws a PlayerException on a machine without a sound device
      final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(speakers);
      final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
      videoCallback.attach(videoPipelineStep);
      final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
      audioCallback.attach(audioPipelineStep);
      final DimensionAttachableCallback dimensionCallback = player.getDimensionAttachableCallback();
      final Dimension resolution = Dimension.of(640, 360);
      dimensionCallback.attach(resolution);

      final FileSource source = FileSource.path(videoFile);
      if (player.start(source)) {
        Thread.sleep(playTime);
      }
    } finally {
      try {
        player.release();
      } finally {
        speakers.release();
      }
    }
  }
```

`start`, `pause`, `resume`, and `seek` return whether they did anything. Once the media has played to its end,
`resume()` returns `false` and does not restart it; call `start` with the source again to play it once more, which is
also how to loop a video.

## Speed, Position and Volume

The FFmpeg, OpenCV and device players extend `AbstractVideoPlayerCV`, which adds a playback speed and the position:

- `setSpeed(double)` plays media of a known length faster or slower, from `AbstractVideoPlayerCV.MIN_SPEED` (0.5) to
  `MAX_SPEED` (2), with its sound resampled to match, so faster sound is higher. It returns `false` for a live stream
  or a camera, which cannot be played ahead of itself, and when nothing plays. A seek keeps the speed; new media starts
  at normal speed. `getSpeed()` returns it.
- `getPositionMillis()` returns the timestamp of the last rendered frame, which is where a relative seek starts from.

The VLC player keeps its own rate, which it uses to keep picture and sound together, so it does not change speed. The
volume is a filter of the audio pipeline, `VolumeFilter` (see [filters](filters.md#audio-filters)), so it applies to
every player and every audio output behind it.

```java
  public static boolean playFaster(final VideoPlayerMultiplexer player) {
    if (player instanceof final AbstractVideoPlayerCV decoded) {
      final long position = decoded.getPositionMillis();
      System.out.println("Speeding up at " + position + " ms");
      return decoded.setSpeed(1.5); // false for a live stream or a camera
    }
    return false; // VLC plays at its own rate
  }
```

The FFmpeg and VLC players can play video and audio from two different sources and keep them in sync. This is how
the separate streams yt-dlp resolves for high-quality YouTube videos are played. The OpenCV and device backends
share the multiplexer interface; the device backend decodes no audio, and the OpenCV backend only where it reads files
with the bundled FFmpeg, as on Linux.

```java
  public static boolean playSeparateStreams(final VideoPlayerMultiplexer player, final URI videoUri, final URI audioUri) {
    final UriSource videoSource = UriSource.uri(videoUri);
    final UriSource audioSource = UriSource.uri(audioUri);
    return player.start(videoSource, audioSource);
  }
```

```{warning}
The `ImageBuffer` passed to a video pipeline is reused for the next frame. Copy it with `copy()` if you want to keep
it.
```

Failures on the player threads, such as a broken network stream, are reported to the exception handler, which logs
them by default. Set your own with `player.setExceptionHandler((message, error) -> ...)`.

# Image Players

The `ImagePlayer` plays frames generated by your code through a video pipeline, at the frame rate of the source. This
is useful for charts, screen captures, or anything else you can draw.

The `chartSupplier` returns the next 640 by 360 frame as a `BufferedImage` and is called once per frame; release the
returned player when the chart should stop.

```java
  public static ImagePlayer playChart(final ImageSupplier chartSupplier, final VideoFilter display) {
    final FrameSource frameSource = FrameSource.image(chartSupplier, 640, 360);
    final VideoPipelineStep videoPipelineStep = VideoPipelineStep.of(display);
    final ImagePlayer player = ImagePlayer.player();

    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(videoPipelineStep);

    player.start(frameSource);
    return player;
  }
```

To play an animated GIF, decode it into a `DynamicImageBuffer` and wrap it in a `RepeatingFrameSource`, which plays
the animation at its own frame rate. The source hands the same shared pixel arrays to the pipeline in every loop, so
filters must not write into `getPixels()` (see [reading pixels](image.md#reading-pixels)).

The animation must stay open while it plays, so the example below plays it for a fixed time, releases the player,
and only then closes the animation:

```java
  public static void playGif(final Path gifFile, final VideoFilter display, final Duration playTime)
    throws IOException, InterruptedException {
    final FileSource file = FileSource.path(gifFile);
    try (final DynamicImageBuffer gif = DynamicImageBuffer.path(file)) {
      final RepeatingFrameSource frameSource = RepeatingFrameSource.repeating(gif); // loops forever
      final VideoPipelineStep videoPipelineStep = VideoPipelineStep.of(display);
      final ImagePlayer player = ImagePlayer.player();

      final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
      videoCallback.attach(videoPipelineStep);

      try {
        player.start(frameSource);
        Thread.sleep(playTime);
      } finally {
        player.release();
      }
    }
  }
```

The FFmpeg player can play GIFs as well.
