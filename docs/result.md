## Other Ways to Display Frames

There are other ways besides maps to display frames, such as scoreboards, chat, or entities. You may use their proper
builders to create them. Unlike the map, these builders directly build into a VideoFilter and do not require an extra
step to convert them into a VideoFilter. Unlike the `MapResult`, you don't need to pass it into a `DitherFilter` because
dithering is only applied for maps.

The following example constructs a `ChatResult` filter that sends the video frames to the chat of the viewers.

```java
  final Collection<UUID> viewers = ...;
  final ChatConfiguration configuration = ChatConfiguration.builder()
        .character(Characters.BLACK_SQUARE)
        .chatWidth(16).chatHeight(16)
        .viewers(viewers)
        .build();
  final VideoFilter chatResult = new ChatResult(configuration);
```

Now just append the filter directly to the video pipeline as you would with any other filter, and it will send the video
frames to the viewers chat.

```{note}
For certain players that fall under the `FunctionalPlayer`, like the `EntityPlayer` and the `ScoreboardPlayer`. You 
**must** call the `start()` method to prepare the player, and the `release()` method to release the player.
```java
    final Collection<UUID> viewers = ...;
    final ScoreboardConfiguration configuration = ScoreboardConfiguration.builder()
            .character(Characters.BLACK_SQUARE)
            .lines(16).width(16)
            .viewers(viewers)
            .build();
    final FunctionalVideoFilter chatResult = new ScoreboardResult(configuration);
    chatResult.start();
    // do some play back
    chatResult.release();
```

```