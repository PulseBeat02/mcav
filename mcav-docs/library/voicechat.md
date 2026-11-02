# Voice Chat Module

MCAV provides a module that plays audio through [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat).
Add the `mcav-voicechat` module to your project.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-voicechat:1.0.0-SNAPSHOT")
}
```

```{warning}
Unlike the other modules, you must shade this module into your plugin, because a class
loader that can see the classes of the Simple Voice Chat plugin must load its classes.
```

Install the `SvcModule` when you create the library instance, and hand it the voice chat server API from your
voice chat plugin once voice chat has started. On a server, the `VoicechatApi` your plugin receives is always a
`VoicechatServerApi`:

```java
  public final class MyVoiceChatPlugin implements VoicechatPlugin {

    private static final String PLUGIN_ID = "my-plugin";

    private final MCAVApi mcav;

    public MyVoiceChatPlugin(final MCAVApi mcav) {
      this.mcav = mcav;
    }

    @Override
    public String getPluginId() {
      return PLUGIN_ID;
    }

    @Override
    public void initialize(final VoicechatApi api) {
      final VoicechatServerApi serverApi = (VoicechatServerApi) api;
      final SvcModule voiceChatModule = this.mcav.getModule(SvcModule.class);
      voiceChatModule.inject(serverApi);
    }
  }
```

On Paper, register the voice chat plugin with the `BukkitVoicechatService` of Simple Voice Chat, for example once the
server has loaded:

```java
  final MCAVApi mcav = MCAV.api();
  mcav.install(SvcModule.class);

  final Server server = Bukkit.getServer();
  final ServicesManager servicesManager = server.getServicesManager();
  final BukkitVoicechatService voicechatService = servicesManager.load(BukkitVoicechatService.class);
  if (voicechatService != null) {
    final MyVoiceChatPlugin voiceChatPlugin = new MyVoiceChatPlugin(mcav);
    voicechatService.registerPlugin(voiceChatPlugin);
  }
```

## Playing audio

The `SvcFilter` is an audio filter that makes one or more entities speakers: nearby players hear the audio with
positional voice chat audio. Pass the platform entities, such as Bukkit `Player`s, to `SvcFilter.svc(Object...)`, or
use `SvcFilter.withDistance(float, Object...)` to set the distance in blocks from which players hear the audio instead
of
the default of 32 blocks (`SvcFilter.DEFAULT_DISTANCE`). You must pass at least one entity, and the distance must be
positive.

Creating a filter throws an `IllegalStateException` if you have not injected the voice chat API into the `SvcModule`
yet.
Call `start()` before attaching the filter and `release()` when playback ends; the pipeline does not call them for you.

```java
  final Player[] speakers = {firstPlayer, secondPlayer};
  final float hearingDistance = 48.0f;
  final SvcFilter voiceChat = SvcFilter.withDistance(hearingDistance, (Object[]) speakers);
  voiceChat.start();

  final VideoPlayerMultiplexer multiplexer = VideoPlayer.ffmpeg();
  final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(voiceChat);
  final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
  audioCallback.attach(audioPipelineStep);

  final String fileName = "video.mp4";
  final Path path = Path.of(fileName);
  final FileSource source = FileSource.path(path);
  multiplexer.start(source);
  // ... play some media

  multiplexer.release();
  voiceChat.release();
```

The filter mixes the stereo samples down to mono and cuts them into 20 millisecond frames. Every speaker has its own
queue of up to half a second, so each of them plays every frame; a speaker that falls behind drops its oldest audio
instead of delaying the others, and a speaker without audio plays silence instead of stopping.
`getQueuedFrames()` returns the number of frames waiting for the slowest speaker. The filter passes the samples on to
the next
step unchanged, and a filter that is not started simply ignores them.

If `start()` fails part way, for example with an `SvcException` because Simple Voice Chat refuses to create an audio
channel for one of the entities, it releases every speaker, encoder, and audio player it had already created before
throwing. The filter stays stopped and you can start it again.

## Mono downmixing

The downmix is available on its own as `MonoDownmixer` in the package `me.brandonli.mcav.utils.audio` of
`mcav-common`, for any output that carries a single channel, such as a mono speaker or a speech recognizer. See
[Mono Downmixing](utilities.md#mono-downmixing) on the utilities page for how it works and an example that plays a file
through the speakers of your computer in mono, exactly as Simple Voice Chat receives it, which checks the downmix
without a Minecraft server.
