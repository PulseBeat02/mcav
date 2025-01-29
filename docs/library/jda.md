# JDA Module

MCAV provides a module to supply audio into Discord voice channels using the [Java Discord API](https://github.com/discord-jda/JDA) (JDA). To get started, you have to import the `mcav-jda` module into your project like so.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-jda:1.0.0-SNAPSHOT")
}
```

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