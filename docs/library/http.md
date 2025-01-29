# HTTP Module

MCAV provides a module that allows you to stream audio into an HTTP web browser. It uses [Javalin](https://javalin.io/)
under the
hood to stream PCM audio to clients. To start, you have to add the `mcav-http` module into your project.

The HTTP module provides a class called the `HttpResult` which provides an HTTP server that can be used to host
a simple web server for providing audio. This class is also a `VideoFilter` which is designed to be used in conjunction
with your pipeline.

```java
  final HttpResult result = HttpResult.port(...);
  final Source source = ...;
  final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(result);
  result.start();

  final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
  final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
  audioCallback.attach(audioPipelineStep);
  
  multiplexer.start(audioPipelineStep, videoPipelineStep, source);
```