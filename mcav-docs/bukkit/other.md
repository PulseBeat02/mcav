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
when it is over. On release, `BlockResult` shows the original blocks again, `EntityResult` removes its text display,
`ScoreboardResult` restores each viewer's previous scoreboard, and `ChatResult` clears the chat. During shutdown, call
`release()` on the main thread, because a disabled plugin cannot schedule the restore anymore.

The method below attaches a chat display to a player before it starts; call it on the main thread, and call `release()`
on the returned filter when the video is over.

```java
  public FunctionalVideoFilter showVideoInChat(final Collection<UUID> viewers, final VideoPlayerMultiplexer player) {
    final int chatWidth = 16;
    final int chatHeight = 16;
    final ChatConfigurationBuilder builder = ChatConfiguration.builder();
    builder.character(Characters.BLACK_SQUARE);
    builder.chatWidth(chatWidth);
    builder.chatHeight(chatHeight);
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

The block, entity and scoreboard displays apply frames on the main thread, one tick at a time, so their frame rate is
limited to 20 frames per second. The chat display sends every frame as a new chat message as soon as it arrives, from
the thread of the video player.
