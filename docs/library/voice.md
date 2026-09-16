# SVC Module

MCAV provides a module that plays audio through [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat).
Add the `mcav-svc` module to your project.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-svc:1.0.0-SNAPSHOT")
}
```

```{warning}
Unlike the other modules, you must shade this module into your plugin, because its classes must be loaded by a class
loader that can see the classes of the Simple Voice Chat plugin.
```

Install the `SVCModule` when you create the library instance, and hand it the voice chat server API from your
voice chat plugin once voice chat has started:

```java
  public final class MyVoiceChatPlugin implements VoicechatPlugin {

    private final MCAVApi mcav;

    public MyVoiceChatPlugin(final MCAVApi mcav) {
      this.mcav = mcav;
    }

    @Override
    public String getPluginId() {
      return "my-plugin";
    }

    @Override
    public void initialize(final VoicechatApi api) {
      final VoicechatServerApi serverApi = (VoicechatServerApi) api; // on a server, the API is always a server API
      final SVCModule voiceChatModule = this.mcav.getModule(SVCModule.class);
      voiceChatModule.inject(serverApi);
    }
  }
```

On Paper, register the voice chat plugin with the `BukkitVoicechatService` of Simple Voice Chat, for example once the
server has loaded:

```java
  final MCAVApi mcav = MCAV.api();
  mcav.install(SVCModule.class);

  final Server server = Bukkit.getServer();
  final ServicesManager servicesManager = server.getServicesManager();
  final BukkitVoicechatService voicechatService = servicesManager.load(BukkitVoicechatService.class);
  if (voicechatService != null) {
    final MyVoiceChatPlugin voiceChatPlugin = new MyVoiceChatPlugin(mcav);
    voicechatService.registerPlugin(voiceChatPlugin);
  }
```

## Playing Audio

The `SVCFilter` is an audio filter that makes one or more entities speakers: nearby players hear the audio with
positional voice chat audio. Pass the platform entities, such as Bukkit `Player`s, to `SVCFilter.svc(Object...)`, or
use `SVCFilter.withDistance(float, Object...)` to set the distance in blocks the audio can be heard from instead of
the default of 32 blocks (`SVCFilter.DEFAULT_DISTANCE`). At least one entity is required, and the distance must be
positive.

Creating a filter throws an `IllegalStateException` if the voice chat API was not injected into the `SVCModule` yet.
Call `start()` before attaching the filter and `release()` when playback ends; the pipeline does not call them for you.

```java
  final Player[] speakers = {firstPlayer, secondPlayer};
  final SVCFilter svc = SVCFilter.withDistance(48.0f, (Object[]) speakers);
  svc.start();
  final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(svc);

  final VideoPlayerMultiplexer multiplexer = VideoPlayer.ffmpeg();
  final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
  audioCallback.attach(audioPipelineStep);

  final Path path = Path.of("video.mp4");
  final FileSource source = FileSource.path(path);
  multiplexer.start(source);
  // ... play some media

  multiplexer.release();
  svc.release();
```

The filter mixes the stereo samples down to mono and cuts them into 20 millisecond frames. Every speaker has its own
queue of up to half a second, so each of them plays every frame; a speaker that falls behind drops its oldest audio
instead of delaying the others, and a speaker without audio plays silence instead of stopping.
`getQueuedFrames()` returns the number of frames waiting for the slowest speaker. The samples are passed on to the next
step unchanged, and a filter that is not started simply ignores them.

If `start()` fails part way, for example because Simple Voice Chat refuses to create an audio channel for one of the
entities, it releases every speaker, encoder, and audio player it had already created before throwing. The filter
stays stopped and can simply be started again.

## Mono Downmixing

The downmix is available on its own as `MonoDownmixer` in the package `me.brandonli.mcav.utils.audio` of
`mcav-common`, for any output that carries a single channel, such as a mono speaker or a speech recognizer. See
[Mono Downmixing](utilities.md#mono-downmixing) on the utilities page for how it works and an example that plays a file
through the speakers of your computer in mono, exactly as Simple Voice Chat receives it, which checks the downmix
without a Minecraft server.
