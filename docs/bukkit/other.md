# Other Ways to Display Videos

Besides maps, frames can be shown as colored blocks, as text display entities, on the scoreboard, or in chat. Each
display has a configuration builder and a result filter that you put at the end of the video pipeline. Unlike maps,
these displays are not dithered, so the filter is used directly.

| Display    | Configuration             | Filter             |
|------------|---------------------------|--------------------|
| Blocks     | `BlockConfiguration`      | `BlockResult`      |
| Entities   | `EntityConfiguration`     | `EntityResult`     |
| Scoreboard | `ScoreboardConfiguration` | `ScoreboardResult` |
| Chat       | `ChatConfiguration`       | `ChatResult`       |

Every result is a `FunctionalVideoFilter`: call `start()` on the main thread before the video starts, and `release()`
when it is over, which removes the blocks, entities, or scoreboard it created.

The method below attaches a chat display to a player before it starts; call `release()` on the returned filter when
the video is over.

```java
  // call on the main thread
  public static FunctionalVideoFilter showVideoInChat(final Collection<UUID> viewers, final VideoPlayerMultiplexer player) {
    final ChatConfiguration.Builder<?> builder = ChatConfiguration.builder();
    builder.character(Characters.BLACK_SQUARE);
    builder.chatWidth(16);
    builder.chatHeight(16);
    builder.viewers(viewers);
    final ChatConfiguration configuration = builder.build();

    final FunctionalVideoFilter chatResult = new ChatResult(configuration);
    chatResult.start();
    final VideoPipelineStep pipeline = VideoPipelineStep.of(chatResult);
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(pipeline);
    return chatResult;
  }
```

Frames are applied on the main thread, one tick at a time, so the frame rate of these displays is limited to 20 frames
per second.
