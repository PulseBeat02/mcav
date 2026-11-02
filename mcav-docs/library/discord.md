# Discord Module

MCAV provides a module that plays audio into Discord voice channels with the
[Java Discord API](https://github.com/discord-jda/JDA) (JDA). Add the `mcav-discord` module to your project.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-discord:1.0.0-SNAPSHOT")
}
```

Install the `JdaModule` together with the library. The bot itself is created and logged in by your code with JDA.

```java
  final MCAVApi api = MCAV.api();
  api.install(JdaModule.class);
```

The `DiscordPlayer` is both an audio filter, which you attach to the audio pipeline of any player, and a JDA
`AudioSendHandler`, which JDA asks for 20 millisecond frames of audio. A bot that only plays audio does not need any
privileged gateway intents, but it needs the voice state intent and cache. The example reads the token and ids from
environment variables, so that no secret ends up in the source:

```java
  final String tokenVariable = "DISCORD_TOKEN";
  final String guildVariable = "DISCORD_GUILD";
  final String channelVariable = "DISCORD_CHANNEL";
  final String token = System.getenv(tokenVariable);
  final String guildId = System.getenv(guildVariable);
  final String channelId = System.getenv(channelVariable);

  final JDABuilder builder = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES);
  builder.enableCache(CacheFlag.VOICE_STATE);
  final JDA jda = builder.build();
  jda.awaitReady();

  final @Nullable Guild guild = jda.getGuildById(guildId);
  if (guild == null) {
    final String message = "Guild %s must have the bot as a member!".formatted(guildId);
    throw new IllegalStateException(message);
  }

  final @Nullable VoiceChannel voiceChannel = guild.getVoiceChannelById(channelId);
  if (voiceChannel == null) {
    final String message = "Voice channel %s does not exist!".formatted(channelId);
    throw new IllegalStateException(message);
  }

  final DiscordPlayer discord = DiscordPlayer.voice(jda);
  final AudioManager audioManager = guild.getAudioManager();
  audioManager.setSendingHandler(discord);
  audioManager.openAudioConnection(voiceChannel);

  final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(discord);
  final VideoPlayerMultiplexer multiplexer = VideoPlayer.ffmpeg();
  final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
  audioCallback.attach(audioPipelineStep);

  final String fileName = "video.mp4";
  final Path path = Path.of(fileName);
  final Source source = FileSource.path(path);
  multiplexer.start(source);
  discord.setPlaying("My Video");

  // at shutdown
  multiplexer.release();
  audioManager.closeAudioConnection();
  jda.shutdown();
  api.release();
```

## Activity

`setPlaying(String)` sets the bot online and shows the title as its "Playing" activity. `setCurrentMedia(UrlParseDump)`
does the same with the title yt-dlp resolved. Any title is accepted and adjusted to the rules of Discord:

- Surrounding whitespace and control characters up to U+001F are removed.
- A title left blank, or media without a title, shows "Unknown title".
- A title longer than 128 characters (`Activity.MAX_ACTIVITY_NAME_LENGTH`) is cut to 127 characters and ends with an
  ellipsis (`…`), so it fits the limit. An emoji or other character outside the Basic Multilingual Plane is never
  split in half.

## Buffering

The player converts the little-endian samples of the pipeline to the big-endian order Discord expects and queues up to
three seconds of audio; when the pipeline runs ahead of Discord, the oldest audio is dropped. Call `flush()` after
seeking or switching media to drop the queued audio immediately, and `getQueuedMilliseconds()` to see how many
milliseconds of audio are waiting to be sent.

```{warning}
Never put a bot token into your source code or configuration files that you share. Anyone with the token can control
the bot. Read it from an environment variable or a private configuration file instead.
```
