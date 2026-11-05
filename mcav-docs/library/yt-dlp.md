# Using yt-dlp

Players like [VLC](https://www.videolan.org/vlc/) and [FFmpeg](https://ffmpeg.org/) cannot play the page of a
YouTube video or a Twitch stream directly; the page hides the actual media streams.
[yt-dlp](https://github.com/yt-dlp/yt-dlp) finds these streams for thousands of
[supported sites](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md), and MCAV installs it and wraps it in
an API.

## Installation

MCAV downloads yt-dlp by itself into the cache folder of the current user (`~/.mcav/cache`), without administrator
rights, the first time you install the library. MCAV pins the download list to the yt-dlp release 2026.08.19, and
MCAV checks every file against its SHA-256 hash before it uses it, so it rejects a changed or damaged download:

| Platform                  | Downloaded file                                              |
|---------------------------|--------------------------------------------------------------|
| Windows x86-64            | `yt-dlp.exe`                                                 |
| Windows x86 (32-bit)      | `yt-dlp_x86.exe`                                             |
| Windows ARM64             | `yt-dlp_arm64.exe`                                           |
| Linux x86-64 (glibc)      | `yt-dlp_linux`                                               |
| Linux ARM64 (glibc)       | `yt-dlp_linux_aarch64`                                       |
| Linux ARMv7 (glibc 2.31+) | `yt-dlp_linux_armv7l.zip`, unpacked into `yt-dlp-unpacked`   |
| macOS (Intel and Apple)   | `yt-dlp_macos`, one universal build                          |
| FreeBSD (any)             | None: yt-dlp publishes no FreeBSD build                      |

The Linux builds need the GNU C library, so they do not run on musl-based systems such as Alpine Linux without a
compatibility layer. yt-dlp is optional: on a platform where MCAV cannot install it, only parsing web pages is
unavailable, which the `YT_DLP` [capability](instance.md#capabilities) tells.

For YouTube, yt-dlp uses a JavaScript runtime such as [Deno](https://deno.com/) when your system has one. Without one,
YouTube still works, but yt-dlp may find fewer formats; see the
[yt-dlp documentation](https://github.com/yt-dlp/yt-dlp/wiki/EJS) for the runtimes it supports.

## Using the parser

The `YtdlpParser` runs yt-dlp for a `UriSource` and returns a `UrlParseDump` with every format yt-dlp found, together
with the title, uploader, thumbnail, and other information about the media. Parsing runs yt-dlp as a separate program,
so it blocks for a few seconds; call it off threads that must stay responsive. The metadata is immutable: read it
through its getters, which return an empty `Optional` or an empty list for values yt-dlp did not report. Parsing fails
in the following cases:

| Exception                  | Meaning                                                                             |
|----------------------------|-------------------------------------------------------------------------------------|
| `InstallationException`    | MCAV cannot install yt-dlp.                                                         |
| `DownloadException`        | The download of yt-dlp fails.                                                       |
| `FileOperationException`   | MCAV cannot move the downloaded yt-dlp into place.                                   |
| `ProcessException`         | MCAV cannot run yt-dlp, or it does not finish within two minutes.                        |
| `YtdlpParseException`      | yt-dlp reports an error, for example because the video is private, or prints no usable JSON. |
| `IllegalArgumentException` | The source is not an absolute `http` or `https` URL.                                |
| `IllegalStateException`    | The library is still preparing yt-dlp in the background after `install`; wait for `api.whenCapabilityReady(Capability.YT_DLP)` or try again shortly. |

All of these are unchecked. `YtdlpParseException`, `NoMatchingFormatException` (below) and the installation, download,
file and process exceptions extend `MCAVException`, the base of every MCAV failure.

```java
  final String pageAddress = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
  final URI pageUri = URI.create(pageAddress);
  final UriSource page = UriSource.uri(pageUri);
  final YtdlpParser parser = YtdlpParser.simple();
  final UrlParseDump dump = parser.parse(page);
```

You can pass extra yt-dlp arguments, such as `--cookies`, after the source.

Sites usually offer many formats, so a `StrategySelector` picks one for video and one for audio. The strategies are
`BEST_QUALITY_VIDEO` and `BEST_QUALITY_AUDIO`, `LOWEST_QUALITY_VIDEO` and `LOWEST_QUALITY_AUDIO`, `FIRST_VIDEO` and
`FIRST_AUDIO`, `PREFER_WEBM_AUDIO`, and `PREFER_MP4_VIDEO`, all constants of `FormatStrategy`.

```java
  public boolean playBestQuality(final UrlParseDump dump, final VideoPlayerMultiplexer player) {
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final Format videoFormat = selector.getVideoSource(dump);
    final Format audioFormat = selector.getAudioSource(dump);
    final UriSource videoSource = videoFormat.toUriSource();
    final UriSource audioSource = audioFormat.toUriSource();
    return player.start(videoSource, audioSource);
  }
```

The selector throws a `NoMatchingFormatException` when no format fits a strategy, such as a video strategy for an
audio-only page.
