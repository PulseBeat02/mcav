# JDA Module

MCAV provides a module that plays audio into Discord voice channels with the
[Java Discord API](https://github.com/discord-jda/JDA) (JDA). Add the `mcav-jda` module to your project.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-jda:1.0.0-SNAPSHOT")
}
```

Install the `JDAModule` together with the library. The bot itself is created and logged in by your code with JDA.

```java
  final MCAVApi api = MCAV.api();
  api.install(JDAModule.class);
```

The `DiscordPlayer` is both an audio filter, which you attach to the audio pipeline of any player, and a JDA
`AudioSendHandler`, which JDA asks for 20 millisecond frames of audio. A bot that only plays audio does not need any
privileged gateway intents, but it needs the voice state intent and cache. The example reads the token and ids from
environment variables, so that no secret ends up in the source:

```java
  final String token = System.getenv("DISCORD_TOKEN");
  final String guildId = System.getenv("DISCORD_GUILD");
  final String channelId = System.getenv("DISCORD_CHANNEL");

  final JDABuilder builder = JDABuilder.createLight(token, GatewayIntent.GUILD_VOICE_STATES);
  builder.enableCache(CacheFlag.VOICE_STATE);
  final JDA jda = builder.build();
  jda.awaitReady();

  final Guild guild = jda.getGuildById(guildId);
  if (guild == null) {
    throw new IllegalStateException("The bot is not a member of guild " + guildId);
  }
  final VoiceChannel voiceChannel = guild.getVoiceChannelById(channelId);
  if (voiceChannel == null) {
    throw new IllegalStateException("Voice channel " + channelId + " does not exist");
  }

  final DiscordPlayer discord = DiscordPlayer.voice(jda);
  final AudioManager audioManager = guild.getAudioManager();
  audioManager.setSendingHandler(discord);
  audioManager.openAudioConnection(voiceChannel);

  final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(discord);
  final VideoPlayerMultiplexer multiplexer = VideoPlayer.ffmpeg();
  final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
  audioCallback.attach(audioPipelineStep);

  final Path path = Path.of("video.mp4");
  final Source source = FileSource.path(path);
  multiplexer.start(source);
  discord.setPlaying("My Video"); // shows "Playing My Video" as the activity of the bot

  // at shutdown
  multiplexer.release();
  audioManager.closeAudioConnection();
  jda.shutdown();
  api.release();
```

## Activity

`setPlaying(String)` sets the bot online and shows the title as its "Playing" activity. `setCurrentMedia(URLParseDump)`
does the same with the title yt-dlp resolved. Any title is accepted and adjusted to the rules of Discord:

- Surrounding whitespace is removed.
- A blank title, or media without a title, shows "Unknown title".
- A title longer than 128 characters (`Activity.MAX_ACTIVITY_NAME_LENGTH`) is cut to 127 characters and ends with an
  ellipsis (`…`), so it fits the limit. An emoji or other character outside the Basic Multilingual Plane is never
  split in half.

## Buffering

The player converts the little-endian samples of the pipeline to the big-endian order Discord expects and queues up to
three seconds of audio; when the pipeline runs ahead of Discord, the oldest audio is dropped. Call `flush()` after
seeking or switching media to drop the queued audio immediately, and `getQueuedMillis()` to see how many milliseconds
of audio are waiting to be sent.

```{warning}
Never put a bot token into your source code or configuration files that you share. Anyone with the token can control
the bot. Read it from an environment variable or a private configuration file instead.
```
