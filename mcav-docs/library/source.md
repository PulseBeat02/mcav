# Introduction to Sources

A source describes what a player plays: a local file, a URL, a capture device, a raw FFmpeg input, or frames your
code generates. Sources are small immutable descriptions; the player opens them when it starts.

| Source                | Created with                                         | Played by                          |
|-----------------------|------------------------------------------------------|------------------------------------|
| `FileSource`          | `FileSource.path(path)` with a `Path`                | FFmpeg, VLC, OpenCV                |
| `UriSource`           | `UriSource.uri(uri)` with a `URI`                    | FFmpeg, VLC; OpenCV with a compatible URL backend |
| `DeviceSource`        | `DeviceSource.device(0)`                             | `VideoPlayer.device()`             |
| `FFmpegDirectSource`  | `FFmpegDirectSource.mrl("desktop", "gdigrab")`       | `VideoPlayer.ffmpeg()`             |
| `FrameSource`         | `FrameSource.supplier(...)` or `FrameSource.image(...)` | `ImagePlayer`                   |
| `BrowserSource`       | `BrowserSource.uri(...)`                             | `BrowserPlayer`                    |

```java
  public static boolean playExampleVideo(final VideoPlayer player) {
    final URI sourceUri = URI.create("https://example.com/video.mp4");
    final UriSource source = UriSource.uri(sourceUri);
    return player.start(source);
  }
```

A `UriSource` that points at a web page rather than a media file, such as a YouTube video, has to be resolved with
[yt-dlp](yt-dlp.md) first. `isDirect()` tells the two apart by the file extension of the URL.

## Detecting Sources

To turn text entered by a user into a source, use the `SourceDetectionHelper`. It recognizes existing file paths,
URLs, device numbers, and FFmpeg inputs written as `format||input`, such as `dshow||video=OBS Virtual Camera`:

```java
  public static Optional<Source> parseUserInput(final String input) {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Optional<Source> detected = helper.detectSource(input); // empty if MCAV does not understand the input
    return detected;
  }
```
