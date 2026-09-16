# Audio Resource Packs

Resource packs let players hear the audio of media through the Minecraft sound system. MCAV helps with every step:
extracting the audio, building the pack, and hosting it.

`AudioExtractor.extractOggVorbis` (in `me.brandonli.mcav.utils.ffmpeg` of `mcav-common`) converts the audio of any
source to stereo Ogg Vorbis, the format Minecraft plays, with the FFmpeg program bundled with MCAV. It waits until
FFmpeg is done, so call it off the main thread; see [extracting audio](../library/utilities.md#extracting-audio) for
the exceptions it throws. Add the file to a `SimpleResourcePack`, set the `pack.mcmeta` information with `meta`, and
write the pack to a zip file. `zip` throws an `IllegalStateException` if `meta` was never called.

Minecraft compares the `pack_format` in `pack.mcmeta` with the version of the client, so pass the resource pack
format of your server's Minecraft version; the [Minecraft Wiki](https://minecraft.wiki/w/Pack_format) lists the
format of every version.

```java
  public static void buildAudioPack(final int packFormat, final Path destination) throws IOException {
    final URI mediaUri = URI.create("https://example.com/video.mp4");
    final UriSource media = UriSource.uri(mediaUri);
    final Path sound = AudioExtractor.extractOggVorbis(media);

    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.meta(packFormat, "MCAV audio");
    pack.sound("mcav:example", sound);
    pack.zip(destination);
  }
```

Players then hear the audio when the sound `mcav:example` is played to them.

## Hosting Resource Packs

Players download resource packs from a URL. `PackHosting` offers three ways to provide one:

| Factory                                    | How the pack is served                                                                                      |
|--------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `PackHosting.website(zip)`                 | Uploaded to [mc-packs.net](https://mc-packs.net/). No port forwarding needed; identical packs are uploaded once. |
| `PackHosting.http(zip, hostName, port)`    | Served by a small HTTP server in the plugin. The port must be reachable by the players.                    |
| `PackHosting.injector(zip)`                | Served on the port of the Minecraft server itself, so no extra port is needed.                              |

```java
  public static PackHosting hostAudioPack(final Path zip) {
    final PackHosting hosting = PackHosting.website(zip);
    hosting.start();

    final String url = hosting.getRawUrl();
    // send the pack to players with this URL, and call hosting.shutdown() when the pack is no longer needed
    return hosting;
  }
```
