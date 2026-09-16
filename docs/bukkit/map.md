# Displaying Videos on Maps

Minecraft [maps](https://minecraft.wiki/w/Map) can only show a bit over 200 colors, compared to the 16,777,216 colors
of the RGB color space. To show images and video on maps anyway, MCAV uses dithering: pixels of the available colors
are mixed so that your eyes blend them into the colors in between.

```{figure} palette.png
The Minecraft map palette
```

## Dithering Algorithms

MCAV implements the common families of dithering algorithms:

- [Error Diffusion](https://en.wikipedia.org/wiki/Error_diffusion) spreads the error of every pixel to its neighbors,
  which gives the smoothest results.
    - **Filter Lite**: the fastest error diffusion algorithm, with results close to Floyd-Steinberg. The recommended
      default.
    - [Floyd-Steinberg](https://en.wikipedia.org/wiki/Floyd%E2%80%93Steinberg_dithering): spreads the error to four
      neighbors.
    - **Temporal Floyd-Steinberg**: Floyd-Steinberg that keeps the dither pattern stable between frames of a video, which
      reduces flickering.
    - [Atkinson](https://en.wikipedia.org/wiki/Atkinson_dithering): spreads only part of the error to six neighbors,
      which gives crisper, higher contrast images.
    - **Jarvis-Judice-Ninke**, **Stucki**, and **Burkes**: spread the error over twelve or seven neighbors for smoother
      gradients at a higher cost.
    - **Stevenson-Arce**: a hexagonal pattern over twelve neighbors.
- [Ordered Dithering](https://en.wikipedia.org/wiki/Ordered_dithering) compares every pixel with a threshold matrix,
  such as a Bayer matrix. It is fast, parallel, and stable between frames, with a visible pattern.
- **Random Dithering** adds noise before choosing the nearest color.
- **Nearest Color** picks the closest palette color for every pixel without dithering. It is the fastest and looks the
  worst, because nothing blends the colors.

```{note}
All error diffusion algorithms in MCAV process rows in serpentine order (alternating left to right and right to left),
which reduces directional artifacts. Some use fast approximations whose error is below one percent.
```

The common algorithms have shortcuts, and every family has a builder for the remaining options:

```java
  final DitherAlgorithm filterLite = DitherAlgorithm.filterLite();
  final DitherAlgorithm temporal = DitherAlgorithm.temporalFloydSteinberg();

  final ErrorDiffusionDitherBuilder<ErrorDiffusionDither, ErrorDiffusionDitherBuilderImpl> builder = DitherAlgorithm.errorDiffusion();
  builder.withAlgorithm(ErrorDiffusionDitherBuilder.Algorithm.ATKINSON);
  builder.withPalette(DitherPalette.DEFAULT_MAP_PALETTE);
  final ErrorDiffusionDither atkinson = builder.build();
```

## Using Maps to Display Frames

Describe the wall of maps with a `MapConfiguration`: the id of the top-left map, the size of the wall in maps, the
resolution frames are scaled to, and the players who see the maps.

```java
  public static MapConfiguration createMapWall(final Collection<UUID> viewers) {
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
    builder.map(0);
    builder.mapBlockWidth(5);
    builder.mapBlockHeight(5);
    builder.mapWidthResolution(640);
    builder.mapHeightResolution(640);
    builder.viewers(viewers);
    final MapConfiguration configuration = builder.build();
    return configuration;
  }
```

Then dither the frames onto the maps with a `DitherFilter` at the end of the video pipeline. The
`CompressedMapResult` only sends the parts of every map that changed since the last frame, which saves most of the
bandwidth of a video; use `MapResult` to always send whole maps. The method below attaches the filter to a player
before it starts; call `release()` on the returned filter when the video is over.

```java
  // call on the main thread
  public static FunctionalVideoFilter showVideoOnMaps(final MapConfiguration configuration, final VideoPlayerMultiplexer player) {
    final DitherAlgorithm algorithm = DitherAlgorithm.filterLite();
    final CompressedMapResult result = new CompressedMapResult(configuration);
    final FunctionalVideoFilter mapFilter = DitherFilter.dither(algorithm, result);
    mapFilter.start();

    final VideoPipelineStep pipeline = VideoPipelineStep.of(mapFilter);
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(pipeline);
    return mapFilter;
  }
```

Players who join while the video is running receive the full maps automatically.
