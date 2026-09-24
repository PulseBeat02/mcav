# Displaying Images

To show a still image, create the configuration of the display you want, such as a `MapConfiguration` or a
`ScoreboardConfiguration`, and turn it into a `DisplayableImage`.

```{note}
Map images are dithered, so `DisplayableImage.map` also takes a `DitherAlgorithm`.
```

Scoreboard, block, entity, and chat displays resize the `ImageBuffer` in place. Map displays resize it only when
the map configuration enables resizing; otherwise, they center or crop it to the map wall. The scoreboard reads the image
before `displayImage` returns, so the method below releases the buffer right away. Call `release()` on the returned
`DisplayableImage` when the image should disappear.

```java
  public static DisplayableImage showImageOnScoreboard(final Collection<UUID> viewers, final Path imageFile) {
    final ScoreboardConfiguration.Builder<?> builder = ScoreboardConfiguration.builder();
    builder.character(Characters.BLACK_SQUARE);
    builder.lines(15);
    builder.width(16);
    builder.viewers(viewers);
    final ScoreboardConfiguration configuration = builder.build();

    final DisplayableImage display = DisplayableImage.scoreboard(configuration);
    final FileSource file = FileSource.path(imageFile);
    try (final ImageBuffer image = ImageBuffer.path(file)) {
      display.displayImage(image);
    }
    return display;
  }
```

The other factories are `DisplayableImage.map(configuration, algorithm)`, `DisplayableImage.block(configuration)`,
`DisplayableImage.entity(configuration)`, and `DisplayableImage.chat(configuration)`. `displayImage` and `release`
may be called from any thread; during shutdown, release displays on the main thread, for example in `onDisable`, so
the original state is restored right away.
