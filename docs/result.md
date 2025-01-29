## Other Ways to Display Frames
There are other ways besides maps to display frames, such as scoreboards, chat, or entities. You may use their proper
builders to create them. Unlike the map, these builders directly build into a VideoFilter and do not require an extra
step to convert them into a VideoFilter.

The following example constructs a `ChatResult` filter that sends the video frames to the chat of the viewers.
```java
  final Collection<UUID> viewers = ...;
  final VideoFilter filter = ChatResult.builder()
    .character(Characters.BLACK_SQUARE) // sets the character to use
    .chatHeight(16).chatWidth(16) // sets the chat height and width
    .viewers(viewers)
    .build();
```

Now just append the filter directly to the video pipeline as you would with any other filter, and it will send the video
frames to the viewers chat.

```{note}
For certain players that fall under the `FunctionalPlayer`, like the `EntityPlayer` and the `ScoreboardPlayer`. You 
**must** call the `start()` method to prepare the player, and the `release()` method to release the player.
```java
    final Collection<UUID> viewers = ...;
    final FunctionalVideoFilter filter = ScoreboardResult.builder()
            .character(Characters.BLACK_SQUARE) // sets the character to use
            .width(16).lines(10) // sets the scoreboard width and height
            .viewers(viewers)
            .build();
    filter.start();
    // do some play back
    filter.release();
```
```