# Introduction to Pipelines

In MCAV, a pipeline is a chain of filters that processes every video frame or audio sample a player produces. Filters
transform the data, for example by resizing, cropping, or recoloring frames, or by adjusting the volume of audio, and
the last filters usually display or send the result.

Each link of a pipeline is a `PipelineStep`: an `AudioPipelineStep` for audio or a `VideoPipelineStep` for video. A
step holds one filter, an `AudioFilter` or a `VideoFilter`, and a pointer to the next step. The player runs the steps
in order for every frame or chunk of samples.

```{figure} pipeline.png
Example of a pipeline from video players.
```

Create pipelines with `PipelineBuilder`. Filters run in the order they are added:

```java
  public static VideoPipelineStep createGrayscaleNegativePipeline(final VideoFilter display) {
    final GrayscaleFilter grayscale = new GrayscaleFilter(); // converts the frame to gray scale
    final InvertFilter invert = new InvertFilter(); // inverts the colors of the frame
    final VideoPipelineStepBuilder videoBuilder = PipelineBuilder.video();
    videoBuilder.then(grayscale);
    videoBuilder.then(invert);
    videoBuilder.then(display); // your filter that shows the frame
    final VideoPipelineStep videoPipelineStep = videoBuilder.build();
    return videoPipelineStep;
  }
```

A single filter is a pipeline as well: `VideoPipelineStep.of(filter)` or `AudioPipelineStep.of(filter)`. Use the
`NO_OP` constants, `VideoPipelineStep.NO_OP` and `AudioPipelineStep.NO_OP`, when you are not interested in video or
audio.

Filters are functional interfaces, so small filters can be lambdas. A filter returns `true` only if it changed the
frame or the samples (or may have changed them), and `false` if it only read them. The pipeline passes the data on to
the next step either way; filters that work on a copy use the result to skip copying unchanged data back. The filter
below only reads the frame, so it returns `false`:

```java
  final VideoFilter logSize = (image, metadata) -> {
    final int width = image.getWidth();
    final int height = image.getHeight();
    System.out.println(width + "x" + height);
    return false;
  };
```

```{note}
Filters run on the threads of the player, one frame at a time. A filter that takes long lowers the frame rate of the
whole pipeline, so hand expensive or blocking work to another thread. Frames are modified in place; copy an
`ImageBuffer` with `copy()` if you want to keep it after the filter returns. The array of `getPixels()` is shared and
read-only; see [reading pixels](image.md#reading-pixels).
```
