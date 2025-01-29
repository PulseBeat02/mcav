## Playing Frames on Map
When you want to play images onto a map, you have to use a technique called dithering because Minecraft map palette is
limited to a bit over 100 colors, compared to the 256 * 256 * 256 (16,777,216) colors available in the RGB color space.
Dithering essentially tricks your eyes into seeing more colors than are actually present by mixing pixels of different colors.

```{figure} images/palette.png
Example of the Minecraft map palette
```

## Introduction to Dithering Algorithms
Dithering algorithms are used to approximate colors in images by using a limited palette. The most common dithering 
algorithms include:
- Error Diffusion: This method spreads the quantization error of a pixel to its neighboring pixels, allowing for a more 
  gradual transition between colors.
- Ordered Dithering: This method uses a fixed pattern to determine how to distribute the quantization error, which can 
  create a more uniform appearance.
- Random Dithering: This method randomly distributes the quantization error, which can create a more natural appearance.
- Nearest Neighbor: This method simply replaces a pixel with the nearest color in the palette, which can create a more 
  blocky appearance.

To use these dithering algorithms, you must use the specific builders for each algorithm. For example, to use the
`ErrorDiffusionDitherBuilder` for error diffusion dithering, the `OrderedDitherBuilder` for ordered dithering, and so
on. For example, for an error diffusion dither, you would use the following code.

```java
  final ErrorDiffusionDither dither = DitherAlgorithm.errorDiffusion()
    .withAlgorithm(ErrorDiffusionDitherBuilder.Algorithm.FILTER_LITE)
    .withPalette(new DefaultPalette()).build();
```

## Using Maps to Display Frames
To use maps, you need to create a `MapResult` object that containing information of your viewers, map resolutions,
block widths and heights, and map IDs. You should use the builder to create the `MapResult` object.

```java
  final Collection<UUID> viewers = ...;
  final DitherResultStep map = MapResult.builder()
    .map(0)
    .mapBlockWidth(5).mapBlockHeight(5)
    .mapHeightResolution(640).mapWidthResolution(640)
    .viewers(viewers)
    .build();
```

Now that MCAV knows the map metadata and the dithering algorithm, you can use the `DitherFilter` to apply the dithering
to be a part of the video pipeline for the last step.

```java
  final DitherAlgorith dither = ...;
  final DitherResultStep mapDisplay = ...;
  final VideoFilter filter = DitherFilter.dither(dither, mapDisplay);
  // add to pipeline builder
```

You can add this to the pipeline builder, and now the player will automatically send frames through the pipeline. Once
it reaches this step, it dithers the frame and sends it to the players as a map.