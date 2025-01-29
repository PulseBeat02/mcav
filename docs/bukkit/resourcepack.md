# Audio Resource Packs

Audio resource packs allow you to play audio to players from media in-game. There are several utility methods to help
assist you with resource packs, such as creation, audio extraction and more.

To extract audio from a resource pack, you may use the `SoundExtractorUtils` class. This class provides the
`extractOggAudio` method, which extracts audio from a resource pack and saves it to a specified directory. You can use
this while constructing your resource packs using the `SimpleResourcePack` builder.

```java
  final UriSource audio = UriSource.uri(URI.create("https://example.com/audio.mp4"));
  final Path ogg = SoundExtractorUtils.extractOggAudio(audio); // temporary path
  final SimpleResourcePack pack = SimpleResourcePack.pack();
  pack.sound("example_audio", ogg);
  
  final Path dest = ..;
  pack.zip(dest);
```

This will extract the audio from the specified URI, convert it to OGG format, and then add it to the resource pack under
the name `example_audio`. The audio will be saved to a temporary path, which you can then use to create your resource
pack.
To play the audio, play the audio in Minecraft with the sound name `example_audio`.

## Hosting Resource Packs

Hosting resource packs is its own story. Currently, MCAV supports two ways to host resource packs: using an HTTP server
or by uploading to [MCPacks](https://mc-packs.net/). The latter is a community-driven project that allows you to upload
and share resource packs easily.

Here is an example using MCPacks.

```java
  final Path packZip = ...;
  final WebsiteHosting hosting = PackHosting.website(packZip);
  hosting.start();
  
  final String url = hosting.getRawUrl();
  // Use the URL to share your resource pack to players
```

Or if you want to use the ServerHosting method.

```java
  final Path packZip = ...;
  final String domainName = ...;
  final int port = ...;
  final HttpHosting hosting = PackHosting.http(packZip, domainName, port);
  hosting.start();
  
  final String url = hosting.getRawUrl();
  // Use the URL to share your resource pack to players

  hosting.shutdown(); // Stop the hosting when done
```

There is also an experimental method using `PackHosting.injector(...)` which allows you to inject a resource pack into
the current Netty stream, allowing you to not have to port-forward or host the resource pack. This is experimental and
many not always work depending on your server environment.