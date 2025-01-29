# Introduction to Players

MCAV provides a variety of players that you can choose to play your media from. In general, all players follow a
similar logic, where frames are supplied via some source, and the result is processed.

```{warning}
Note that for every video player, you **must** release the video player after you are done using it. This is done by
calling the `release()` method on the player. Failing to do so will result in memory leaks and potentially crashes. Not
all players need to be released, so check the documentation for the specific player you are using.
```

# Video Players

There are several different types of video players. There are multiplexer video players, which are able to play
from different audio and video inputs at once. For example, the `VLCPlayer` is a multiplexer video player that can play
from a separated audio and video input at the same time. It automatically synchronizes the audio and video streams to
ensure that they are in sync.

All video players will automatically have a wrapper callback (called `VideoAttachableCallback` for video and
`AudioAttachableCallback` for audio) that allows you to attach a `VideoPipelineStep` or `AudioPipelineStep` to the player
for callbacks. They wrap the respective pipeline callbacks.

```{note}
As an implementation note. All image and audio samples follow a standard format throughout the pipeline and should
always maintain this same type at all times. The image frames are always encoded in **BGR24** format with 8-bits of
padding. The audio samples are always encoded in **Signed PCM 16-bit Little-Endian** format, with 48kHz sample rate and
2 channels.
```

```java
  final AudioPipelineStep audioPipelineStep = ...;
  final VideoPipelineStep videoPipelineStep = ...;
  final FileSource videoSource = ...;
  final FileSource audioSource = ...;
  final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
  
  // audioPipelineStep and videoPipelineStep from above
  final VideoAttachableCallback videoCallback = multiplexer.getVideoAttachableCallback();
  videoCallback.attach(videoPipelineStep);

  final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
  audioCallback.attach(audioPipelineStep);
  
  multiplexer.start(videoSource, audioSource);
  // ... do something with the player
  multiplexer.release();
```

```{warning}
The ImageBuffer provided in a VideoPipelineStep is always released after the frame is processed. If you would like to
store the frame, you must copy the frame to a new ImageBuffer object.
```

All multiplexer players support single input video and audio sources. For example, you're able to play a video file like
so.

```java
  final AudioPipelineStep audioPipelineStep = ...;
  final VideoPipelineStep videoPipelineStep = ...;
  final FileSource source = ...;
  final VideoPlayerMultiplexer player = VideoPlayer.vlc();
  
  // audioPipelineStep and videoPipelineStep from above
  final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
  videoCallback.attach(videoPipelineStep);

  final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
  audioCallback.attach(audioPipelineStep);
  
  player.start(source);
  // ... do something with the player
  player.release();
```

The `VLCPlayer`, `FFmpegPlayer`, `VideoInputPlayer`, and `OpenCVPlayer` are all multiplexer video players.

```{note}
Note that the `VideoInputPlayer`, however, requires an integer device input, so you must use the `DeviceSource` to 
specify a device identifier. Otherwise, the `VideoInputPlayer` will not work.
```

# Image Players

For any other image-based video players, MCAV provides an `ImagePlayer`, which is a video player that plays a series of
images given a `FrameSource`. This is incredibly useful for other miscellaneous tasks, such as taking the input from a
JFreeChart chart and displaying it.

```java
  final VideoPipelineStep videoPipelineStep = ...;
  final FrameSource frameSource = FrameSource.image(...); // provide your frames in a supplier
  final ImagePlayer player = ImagePlayer.player();

  final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
  videoCallback.attach(videoPipelineStep);
  
  player.start(frameSource);
  // ... do something with the player
  player.release();
```

If you want to play a GIF image, you can use the `RepeatingFrameSource` which accepts any `DynamicImageBuffer`. You can pass
this into `ImagePlayer` directly.

```java
  final VideoPipelineStep videoPipelineStep = ...;
  final DynamicImageBuffer gif = DynamicImageBuffer.path(FileSource.path(Path.of("example.gif"))); // provide your gif image
  final RepeatingFrameSource frameSource = RepeatingFrameSource.repeating(gif); // provide your gif frames in a supplier
  final ImagePlayer player = ImagePlayer.player();
  
  final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
  videoCallback.attach(videoPipelineStep);
  
  player.start(videoPipelineStep, frameSource);
  // ... do something with the player
  player.release();
```

This will play the GIF indefinitely until you stop the player. You're also welcome to play the GIF in the
`FFmpegPlayer`, as it's able to play GIFs as well.