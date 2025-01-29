## Other Ways to Display Frames
There are other ways besides maps to display frames, such as scoreboards, chat, or entities. You may use their proper
builders to create them. Unlike the map, these builders directly build into a VideoFilter and do not require an extra
step to convert them into a VideoFilter.

```java
  final Collection<UUID> viewers = ...;
  final VideoFilter filter = ChatResult.builder()
    .character(Characters.BLACK_SQUARE)
    .chatHeight(16).chatWidth(16)
    .viewers(viewers)
    .build();
```

Now just append the filter directly to the video pipeline as you would with any other filter, and it will send the video
frames to the viewers chat.