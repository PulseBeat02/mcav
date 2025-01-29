## Using the JDA Module

The JDA module provides a class called the `DiscordPlayer` which wraps around the `AudioSendHandler` and
`AudioRecieveHandler`
classes that are provided by JDA. This class is also a `VideoFilter` which is designed to be used in conjunction with
your pipeline.

```java
final Guild guild = ...;
final VoiceChannel voiceChannel = ...;
final AudioManager audioManager = guild.getAudioManager();
audioManager.openAudioConnection(voiceChannel);

final DiscordPlayer player = DiscordPlayer.voice();
final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(player);
final VideoPipelineStep videoPipelineStep = VideoPipelineStep.NO_OP;
audioManager.setSendingHandler(player);

final VideoPlayerMultiplexer multiplexer = VideoPlayer.ffmpeg();
multiplexer.start(...);
```