# HTTP Module

MCAV provides a module that streams audio to web browsers. It uses [Spring Boot](https://spring.io/) to serve a small
web player built with [TypeScript](https://www.typescriptlang.org/), [React](https://reactjs.org/), and
[Next.js](https://nextjs.org/), which plays the audio and shows the title and thumbnail of the current media.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-http:1.0.0-SNAPSHOT")
}
```

Install the `HttpModule` together with the library. The module itself holds no state; every server is created,
started, and stopped by your code.

```java
  final MCAVApi api = MCAV.api();
  api.install(HttpModule.class);
```

## Creating a Server

The `HttpResult` is an audio filter: attach it to the audio pipeline of a player and every connected browser receives
the samples over a WebSocket. By default the server listens on all network interfaces, because the browsers of players
connect from other machines; the host name only decides the address returned by `getFullUrl()`. On Linux and macOS,
ports below 1024 need administrator rights, so pick a higher port.

`HttpResult` offers a factory for the common cases. None of them starts the server.

| Factory                                        | Server                                                                        |
|------------------------------------------------|-------------------------------------------------------------------------------|
| `HttpResult.port(int port)`                    | Host name `localhost` on a port, for a browser on the same machine.           |
| `HttpResult.domain(String domain)`             | A host name on port 80.                                                       |
| `HttpResult.http(String domain, int port)`     | A host name and a port.                                                       |
| `HttpResult.http(String domain, int port, Path directory)` | Like above, but serves the web page from a directory instead of the copy bundled in the jar, which is useful while developing the page. |
| `HttpResult.builder()`                         | An `HttpResultBuilder` for every setting, including the bind address.         |

The ports must be between 1 and 65535 and the host name must not be blank; otherwise the factories throw an
`IllegalArgumentException`.

```java
  final HttpResult http = HttpResult.http("play.example.com", 8080);
  http.start(); // blocks for a few seconds; startAsync() starts it on another thread instead
  final MediaInfo info = MediaInfo.titled("My Video");
  http.setCurrentMedia(info); // or http.setCurrentMedia(dump) with the URLParseDump of yt-dlp

  final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(http);
  final VideoPlayerMultiplexer multiplexer = VideoPlayer.ffmpeg();
  final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
  audioCallback.attach(audioPipelineStep);
  final Path path = Path.of("video.mp4");
  final FileSource source = FileSource.path(path);
  multiplexer.start(source);

  final String url = http.getFullUrl(); // http://play.example.com:8080/
  System.out.println("Listen at " + url);
  // ... play some media

  multiplexer.release();
  http.stop();
```

`setCurrentMedia(URLParseDump)` shows the title, uploader, thumbnail, duration, and statistics yt-dlp reported, while
`MediaInfo.titled(String)` creates information with only a title for media that did not come from yt-dlp. Passing
`null` to `setCurrentMedia(MediaInfo)` shows that nothing is playing. An IPv6 address as host name is put in brackets
in the URL, as URLs require.

## Using the Builder

`HttpResult.builder()` returns an `HttpResultBuilder`, which configures a server setting by setting. Every setting has
a default, so only set what you need:

| Method                              | Default                          | Setting                                                  |
|-------------------------------------|----------------------------------|----------------------------------------------------------|
| `domain(String domain)`             | `localhost`                      | The host name used in the URL of `getFullUrl()`.         |
| `port(int port)`                    | `80`                             | The port, from 1 to 65535.                               |
| `directory(Path directory)`         | The web page bundled in the jar  | A directory with a built web page, such as `mcav-website/out`. Files outside it are never served. |
| `bindAddress(InetAddress address)`  | Every network interface          | The one local network interface the server listens on.   |

For example, a server behind a reverse proxy on the same machine only needs the loopback interface:

```java
  final HttpResultBuilder builder = HttpResult.builder();
  builder.domain("play.example.com");
  builder.port(8080);
  final InetAddress loopback = InetAddress.getLoopbackAddress();
  builder.bindAddress(loopback);
  final HttpResult http = builder.build();
  http.start();
```

The domain still decides the URL the players open, here `http://play.example.com:8080/`, while the server only accepts
connections from the proxy. `build()` can be called several times; changing the builder afterwards does not affect
servers built before. Builders are not thread-safe.

## Endpoints

The page served to browsers opens a WebSocket at `/audio`, which streams the audio as 16-bit little-endian stereo PCM
at 48 kHz, exactly the format of the audio pipeline, and shows the media information it reads from `/media`.

| Endpoint | Content                                                                              |
|----------|--------------------------------------------------------------------------------------|
| `/`      | The web player.                                                                      |
| `/media` | The title, uploader, thumbnail, duration, and statistics of the current media as JSON. |
| `/audio` | A WebSocket that streams 48 kHz, 16-bit little-endian stereo PCM audio.              |

Every listener has its own sender thread, so a slow connection never stalls the pipeline or the other listeners: a
listener that cannot keep up loses its oldest samples, and a listener whose writes block for five seconds is
disconnected. `getListenerCount()` returns the number of connected browsers and `isRunning()` whether the server is
started.

`start` throws an `HttpException` when the server cannot be started, for example because the port is already in use.
Calling `start` on a running server has no effect, and a stopped server can be started again. `stop` disconnects every
listener without waiting for connections that are stuck.
