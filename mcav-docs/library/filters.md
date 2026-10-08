# Filters

MCAV comes with a set of ready-made filters for the [pipelines](pipeline.md) of its players. Video filters live in
`me.brandonli.mcav.media.player.pipeline.filter.video` of `mcav-common`, audio filters in
`me.brandonli.mcav.media.player.pipeline.filter.audio`. Every video filter works on the `ImageBuffer` of a frame, in
place, so each one can also be applied to a single image with `applyFilter(image)`, as in
[image manipulation](image.md).

```java
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.FPSFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.GrayscaleFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.LuminanceFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.TextFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.bytedeco.opencv.global.opencv_imgproc;

  public static VideoPipelineStep createSecurityCameraPipeline(final VideoFilter display) {
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then(new GrayscaleFilter());
    builder.then(new LuminanceFilter(1.4, -20)); // contrast, brightness
    builder.then(new TextFilter("CAM 1", 12, 28, opencv_imgproc.FONT_HERSHEY_SIMPLEX, 0.8, new double[] { 255, 255, 255 }));
    builder.then(new FPSFilter());
    builder.then(display);
    return builder.build();
  }
```

## Video Filters

Colours are arrays of the blue, green and red components from 0 to 255, OpenCV's order. Coordinates are pixels of the
frame, from its top left corner.

| Filter | Created with | Effect |
|---|---|---|
| `GrayscaleFilter` | `new GrayscaleFilter()` | Shades of grey; the frame keeps its three channels |
| `InvertFilter` | `new InvertFilter()` | Every colour turned into its negative |
| `LuminanceFilter` | `new LuminanceFilter(contrast, brightness)` | Every channel multiplied by the contrast and the brightness added, clamped |
| `ThresholdFilter` | `new ThresholdFilter(threshold, maxValue, type)` | OpenCV's fixed threshold on every channel, `type` a `THRESH_` constant of `opencv_imgproc` |
| `ColorMapFilter` | `new ColorMapFilter(colorMap)` | False colours from one of OpenCV's `COLORMAP_` constants, over the grey levels |
| `TintFilter` | `new TintFilter(color, strength)` | Blended with a solid colour, `strength` from 0 to 1 |
| `BlurFilter` | `new BlurFilter(type, kernelSize)` or with the Gaussian sigmas | A `NORMAL` (box), `MEDIAN`, `GAUSSIAN` or `STACK` blur; the last three need an odd kernel size |
| `BilateralFilter` | `new BilateralFilter(diameter, sigmaColor, sigmaSpace)` | A blur that keeps edges; expensive, a diameter of 5 to 9 suits real-time video |
| `DilationFilter`, `ErosionFilter` | `new DilationFilter(kernelSize)` | Grows, or shrinks, the bright regions |
| `FlipFilter` | `new FlipFilter(FlipDirection.HORIZONTAL)` | Mirrored `HORIZONTAL`ly, `VERTICAL`ly or `BOTH` |
| `RotationFilter` | `new RotationFilter(Rotation.ROTATE_90_CLOCKWISE)` | Rotated by 90, 180 or 270 degrees; 90 and 270 swap the width and height |
| `TransposeFilter` | `new TransposeFilter()` | Rows and columns swapped |
| `ResizeFilter` | `new ResizeFilter(width, height)` | Scaled to a fixed size, averaging areas when it shrinks |
| `CropFilter` | `new CropFilter(left, top, width, height)` | Cropped to a rectangle, clamped to the frame |
| `RectangleFilter`, `CircleFilter`, `EllipseFilter`, `LineFilter` | the shape's position and size, then its colour | The outline of a shape drawn onto every frame |
| `RegionScalarFilter` | `new RegionScalarFilter(left, top, width, height, color)` | A rectangle filled with a solid colour |
| `TextFilter` | `new TextFilter(text, left, baseline, font, scale, color)` | A line of text in one of OpenCV's `FONT_HERSHEY_` fonts |
| `OverlayImageFilter` | `new OverlayImageFilter(image, left, top)` | An image drawn on top of every frame; the image is copied, so it may be released |
| `BlendFilter` | `new BlendFilter(image, alpha)` | Every frame blended with a fixed image of the same size |
| `ZeroFilter` | `new ZeroFilter()` | A black frame |
| `FPSFilter` | `new FPSFilter()` | The frame rate of the pipeline written into the top left corner, smoothed over a second |
| `FaceDetectionFilter` | `new FaceDetectionFilter(cascadeFile, color)` | A rectangle around every face an OpenCV Haar cascade finds |

```{warning}
`FaceDetectionFilter` needs OpenCV's object detection natives, which link GTK 2 on Linux and are missing on many
headless servers. Check the `FACE_DETECTION` [capability](instance.md#capabilities) before you create one. It needs a
cascade file too, such as OpenCV's `haarcascade_frontalface_default.xml`, which MCAV does not ship.
```

The display filters that end a pipeline come with their modules: `DitherFilter` of `mcav-common`, the map, block, chat,
entity and scoreboard results of `mcav-bukkit` ([Bukkit integration](../bukkit/bukkit.md)), `Mcv2Result` for
[MCV2](../bukkit/mcv2.md), and `GLTextureFilter` of `mcav-lwjgl` ([LWJGL module](lwjgl.md)).

## Audio Filters

| Filter | Module | Effect |
|---|---|---|
| `VolumeFilter` | `mcav-common` | Multiplies every sample by a volume from 0 to 2 (`VolumeFilter.MAX_VOLUME`), clipped; `setVolume` may be called from any thread while it runs |
| `DirectAudioOutput` | `mcav-common` | Plays through the default sound device of the machine; its `start()` throws a `PlayerException` where there is none |
| `DiscordPlayer` | `mcav-discord` | Sends to a Discord voice channel ([Discord module](discord.md)) |
| `HttpResult` | `mcav-http` | Streams to web browsers ([HTTP module](http.md)) |
| `SVCFilter` | `mcav-voicechat` | Plays through Simple Voice Chat ([Voice Chat module](voicechat.md)) |

A `VolumeFilter` before an output turns the output down or up; one filter in front of several outputs sets them all:

```java
  final VolumeFilter volume = new VolumeFilter();
  final AudioPipelineStepBuilder builder = PipelineBuilder.audio();
  builder.then(volume);
  builder.then(speakers);
  final AudioPipelineStep audioPipeline = builder.build();
  // ... attach it, start the player, and later:
  volume.setVolume(0.5); // half the sample amplitude, from the next chunk on
```

## Writing Your Own

A filter is a functional interface: `VideoFilter.applyFilter(ImageBuffer, OriginalVideoMetadata)` and
`AudioFilter.applyFilter(ByteBuffer, OriginalAudioMetadata)`, returning whether it changed the data. Filters that own
resources, such as a window or a connection, implement `FunctionalVideoFilter` or `FunctionalAudioFilter`, whose
`start()` and `release()` your code calls; the pipeline does not. Filters implemented with OpenCV can extend
`MatVideoFilter` and work on the frame's 8-bit BGR matrix directly. See [pipelines](pipeline.md) for the rules every
filter follows.
