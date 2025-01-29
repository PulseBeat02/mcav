# Using yt-dlp
Most modern players like [VLC media player](https://www.videolan.org/vlc/) or [FFmpeg](https://ffmpeg.org/) aren't able 
to play Twitch streams or YouTube streams directly. You would need an external tool called [yt-dlp](https://github.com/yt-dlp/yt-dlp) 
to download the internal stream and then play it with your player of choice. To solve this issue, MCAV provides a 
yt-dlp API to extract internal URLs from streams and then play them with your player of choice.

## Using the Parser
The `YTDLPParser` is a parser that uses yt-dlp to extract the internal URL of a stream. It can be used to parse `URISoure`
sources to create `URLParseDump` objects, which are JSON POJO objects that contain the internal URL and other metadata
that yt-dlp provides.

```java
  final UriSource source = UriSource.uri(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
  final YTDLPParser parser = YTDLPParser.simple();
  final URLParseDump dump = parser.parse(source);
```

However, yt-dlp supplies many different audio and video formats for each URL. It would be hard to choose a specific 
format in mind, so MCAV provides the `StrategySelector` class, which automatically will pick up a specific audio and 
video format for you. You must specify the strategy you want to use, and it will automatically select the audio and
video format based on your strategy.

```java
  final URLParseDump dump = ..;
  final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.FIRST_VIDEO);
  final UriSource videoFormat = selector.getVideoSource(dump).toUriSource();
  final UriSource audioFormat = selector.getAudioSource(dump).toUriSource();
```

From these `UriSource` objects, you can pass them into your video player of choice, such as VLC media player or FFmpeg.
