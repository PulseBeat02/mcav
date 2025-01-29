## Video Players
MCAV provides a variety of video players that you can choose to play your videos from. Video players that are supported
in MCAV include a `VLCPlayer`, `FFmpegPlayer`, `VideoInputPlayer`, `OpenCVPlayer`, `VideoInputPlayer`, `ImagePlayer`, and 
a `ChromeDriverPlayer`. In general, all players follow a similar logic, where frames are supplied via some source, and 
the result is processed.

```{warning}
Note that for every video player, you **must** release the video player after you are done using it. This is done by
calling the `release()` method on the player. Failing to do so will result in memory leaks and potentially crashes. Not
all players need to be released, so check the documentation for the specific player you are using.
```

There are several different types of video players. There are multiplexer video players, which are able to play
from different audio and video inputs at once. For example, the `VLCPlayer` is a multiplexer video player that can play
from a separated audio and video input at the same time. It automatically synchronizes the audio and video streams to
ensure that they are in sync.

```java
  final AudioPipelineStep audioPipelineStep = ...;
  final VideoPipelineStep videoPipelineStep = ...;
  final FileSource videoSource = ...;
  final FileSource audioSource = ...;
  final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
  // audioPipelineStep and videoPipelineStep from above
  multiplexer.start(audioPipelineStep, videoPipelineStep, videoSource, audioSource);
  // ... do something with the player
  multiplexer.release();
```

All multiplexer players support single input video and audio sources. For example, you're able to play a video file like
so.

```java
  final AudioPipelineStep audioPipelineStep = ...;
  final VideoPipelineStep videoPipelineStep = ...;
  final FileSource source = ...;
  final VideoPlayerMultiplexer player = VideoPlayer.vlc();
  // audioPipelineStep and videoPipelineStep from above
  player.start(audioPipelineStep, videoPipelineStep, source);
  // ... do something with the player
  player.release();
```

The `VLCPlayer`, `FFmpegPlayer`, `VideoInputPlayer`, and `OpenCVPlayer` are all multiplexer video players. 

```{note}
Note that the `VideoInputPlayer`, however, requires an integer device input, so you must use the `DeviceSource` to 
specify a device identifier. Otherwise, the `VideoInputPlayer` will not work.
```

## Browser Players
MCAV also provides a `ChromeDriverPlayer`, which is a video player that uses the [Chrome WebDriver](https://developer.chrome.com/docs/chromedriver) to screencast a web
page from a browser. Unfortunately, audio is not supported in this player due to limitations in the Chrome WebDriver.
To use the `ChromeDriverPlayer`, you must have a `BrowserSource` that specifies the URL of the web page to connect to.

```java
  final VideoPipelineStep videoPipelineStep = ...;
  final VideoMetadata metadata = VideoMetadata.of(1920, 1080); // specify browser resolution
  final BrowserSource browserSource = BrowserSource.of(URI.create("https://example.com"), metadata);
  final BrowserPlayer player = BrowserPlayer.defaultChrome(); // starts Chrome WebDriver with default arguments
  player.start(videoPipelineStep, browserSource);
  // ... do something with the player
  player.release();
```

## Image Players
For any other image-based video players, MCAV provides an `ImagePlayer`, which is a video player that plays a series of
images given a `FrameSource`. This is incredibly useful for other miscellaneous tasks, such as taking the input from a
JFreeChart chart and displaying it.

```java
  final VideoPipelineStep videoPipelineStep = ...;
  final FrameSource frameSource = FrameSource.image(...); // provide your frames in a supplier
  final ImagePlayer player = ImagePlayer.player();
  player.start(videoPipelineStep, frameSource);
  // ... do something with the player
  player.release();
```

If you want to play a GIF image, you can use the `RepeatingFrameSource` which accepts any `DynamicImage`. You can pass
this into `ImagePlayer` directly.

```java
  final VideoPipelineStep videoPipelineStep = ...;
  final DynamicImage gif = DynamicImage.path(FileSource.path(Path.of("example.gif"))); // provide your gif image
  final RepeatingFrameSource frameSource = RepeatingFrameSource.repeating(gif); // provide your gif frames in a supplier
  final ImagePlayer player = ImagePlayer.player();
  player.start(videoPipelineStep, frameSource);
  // ... do something with the player
  player.release();
```

This will play the GIF indefinitely until you stop the player. You're also welcome to play the GIF in the `FFmpegPlayer`,
as it's able to play GIFs as well.

## VNC Players
MCAV also provides a `VNCPlayer`, which is a video player that connects to a VNC server and displays the screen. This is
useful for remote desktop applications or for monitoring remote systems. To use the `VNCPlayer`, you must construct
a `VNCSource`, and pass it to the player.

```java
  final VideoPipelineStep pipeline = ...;
  final VNCSource source = VNCSource.vnc()
    .host("localhost")
    .port(5900)
    .password("passwd")
    .videoMetadata(videoMetadata)
    .name("VNC Connection")
    .build();
  final VNCPlayer player = VNCPlayer.vm();
  player.start(pipeline, source);
  // ... do some play back
  player.release();
```

That way, you're able to connect to virtual machines or any VNC server and interact with it as if it were a local desktop.