# Displaying Images

Displaying images is incredibly simple. To start, you need to create a configuration based on the way you want to
display
your image in-game. This could be a `MapConfiguration`, `ScoreboardConfiguration` or more.

```{note}
When using the `MapConfiguration`, you have to pass in a `DitherAlgorithm` implementation in order for the image to be
properly dithered in advance before displaying it.
```

You must call the `release()` method on the `DisplayableImage` instance when you are done with it. This will properly
dispose of any displays and clean up everything.

```java
  final Collection<UUID> viewers = null;
  final ScoreboardConfiguration configuration = ScoreboardConfiguration.builder()
    .character(Characters.BLACK_SQUARE)
    .lines(16).width(16)
    .viewers(viewers)
    .build();
  final DisplayableImage display = DisplayableImage.scoreboard(configuration);
  final StaticImage image = null;
  display.displayImage(image);
  // do some playback
  display.release();
  image.release();
```