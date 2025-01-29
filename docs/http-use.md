## Using the HTTP Module

The HTTP module provides a class called the `HttpResult` which provides an HTTP server that can be used to host
a simple web server for providing audio. This class is also a `VideoFilter` which is designed to be used in conjunction
with your pipeline.

```java
  final HttpResult result = HttpResult.port(...);
  final Source source = ...''
  final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(result);
  final VideoPipelineStep videoPipelineStep = VideoPipelineStep.NO_OP;
  result.start();

  final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
  multiplexer.start(audioPipelineStep, videoPipelineStep, source);
```