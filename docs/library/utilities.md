# Utilities

`mcav-common` contains a few helpers that are useful on their own, outside of the players: converting the audio of a
pipeline into the format another output expects, extracting the audio track of media into a file, small network
checks, and handling failures on threads of your own. They live in the `me.brandonli.mcav.utils` packages and need no
extra module.

Every player hands its audio pipeline signed 16-bit little-endian PCM at 48 kHz with two interleaved channels,
whatever the source format is (see `AudioFilter.SAMPLE_RATE`, `AudioFilter.CHANNELS` and `AudioFilter.FRAME_SIZE`).
The audio helpers below take exactly that buffer, so the `samples` of an `AudioPipelineStep.of((samples, metadata) ->
...)` filter can be passed to them directly. They read the buffer without changing its position, so the next steps
of the pipeline still see every sample.

```{note}
A filter returns whether it changed the samples. The filters on this page only read them, so they return `false`;
the pipeline passes the samples on to the next step either way.
```

## Mono Downmixing

`MonoDownmixer` in `me.brandonli.mcav.utils.audio` mixes the stereo samples of a pipeline down to mono for outputs
that carry a single channel, such as Simple Voice Chat, a mono speaker, or a speech recognizer.

`MonoDownmixer.downmix(ByteBuffer)` reads the interleaved 16-bit little-endian stereo samples between the position and
the limit of the buffer, averages the left and right channel of every sample, and returns a `short[]` with one mono
sample for every stereo sample. The buffer is not changed, and a trailing incomplete sample is ignored. Passing `null`
throws a `NullPointerException`. The class only has static methods and cannot be instantiated.

The example below plays a file through the speakers of your computer in mono, exactly as Simple Voice Chat receives
it, which checks the downmix without a Minecraft server:

```java
  final MCAVApi api = MCAV.api();
  api.install();

  final AudioFormat format = new AudioFormat(48_000, 16, 1, true, false);
  final SourceDataLine line = AudioSystem.getSourceDataLine(format);
  line.open(format);
  line.start();

  final AudioPipelineStep pipeline = AudioPipelineStep.of((samples, metadata) -> {
    final short[] mono = MonoDownmixer.downmix(samples);
    final ByteBuffer bytes = ByteBuffer.allocate(mono.length * 2);
    bytes.order(ByteOrder.LITTLE_ENDIAN);
    for (final short sample : mono) {
      bytes.putShort(sample);
    }
    final byte[] array = bytes.array();
    line.write(array, 0, array.length);
    return false;
  });

  final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
  final AudioAttachableCallback audio = player.getAudioAttachableCallback();
  audio.attach(pipeline);
  final Path path = Path.of("video.mp4");
  final FileSource source = FileSource.path(path);
  player.start(source);
```

## Resampling Audio

`AudioResampler` in `me.brandonli.mcav.utils.audio` converts interleaved PCM audio between sample rates, channel
counts, and sample formats with FFmpeg's libswresample, which is bundled with MCAV. Use it when an output expects
something other than the 48 kHz stereo audio of the pipeline, such as a speech recognizer at 16 kHz mono, a telephony
bridge at 8 kHz, or an engine that mixes floating point samples.

There are two factories. Both return a new resampler that the caller must close:

| Factory                                                                                                  | Input                                                   |
|----------------------------------------------------------------------------------------------------------|---------------------------------------------------------|
| `AudioResampler.fromPipelineFormat(outputSampleRate, outputChannels, outputFormat)`                     | The audio of the pipeline: signed 16-bit stereo at 48 kHz |
| `AudioResampler.create(inputSampleRate, inputChannels, inputFormat, outputSampleRate, outputChannels, outputFormat)` | Any interleaved PCM format                              |

Sample rates are in hertz and must be positive, and channel counts must be between 1 and
`AudioResampler.MAX_CHANNELS`, which is 8, the number of channels of a 7.1 layout. Otherwise the factories throw an
`IllegalArgumentException`; a `null` format throws a `NullPointerException`, and an `IllegalStateException` is thrown
if FFmpeg cannot set up the conversion. Channel counts map to FFmpeg's default layouts (mono, stereo, 2.1, quad, 5.0,
5.1, 6.1, and 7.1): mixing down averages the channels, and mixing up spreads them without raising the volume.

The resampler has three methods:

- `resample(ByteBuffer)` converts the samples between the position and the limit of the buffer without changing the
  buffer, so the buffer of an audio pipeline can be passed directly. It returns the converted interleaved samples as a
  `byte[]`, which can be empty. A trailing incomplete frame is ignored.
- `flush()` returns the samples that earlier calls held back, then resets the resampler, so it can be reused for a new
  stream that does not continue the previous one. Changing the sample rate needs a few samples of look-ahead, so
  every call to `resample` holds back a short tail and emits it with the next call; the output of a single call can be
  slightly shorter than the rate ratio suggests, while the output of the whole stream is not. Call `flush()` at the end
  of a stream to get that tail.
- `close()` frees the native resampling context. Samples still held back are discarded, so call `flush()` first to
  keep them. Closing a closed resampler does nothing. `AudioResampler` implements `AutoCloseable`, so it can be used
  in a try-with-resources statement.

After `close()`, `resample` and `flush` throw an `IllegalStateException`, which they also throw if FFmpeg fails to
convert, drain, or reset. `isClosed()` tells whether the resampler has been closed, and `getInputSampleRate()`,
`getInputChannels()`, `getInputFormat()`, `getOutputSampleRate()`, `getOutputChannels()`, and `getOutputFormat()`
return the formats it was created with.

```{warning}
A resampler keeps state between calls and is not thread-safe: use it from one thread at a time. A filter of an audio
pipeline is used that way, because a player runs its audio pipeline on a single thread. Release the player before you
flush and close the resampler, so the pipeline does not use it at the same time.
```

The example below converts the audio of a file to 16 kHz mono, the format of most speech recognizers, and plays it
through the speakers of your computer, so you can hear the result:

```java
  final MCAVApi api = MCAV.api();
  api.install();

  final AudioFormat speechFormat = new AudioFormat(16_000, 16, 1, true, false);
  final SourceDataLine line = AudioSystem.getSourceDataLine(speechFormat);
  line.open(speechFormat);
  line.start();

  final AudioResampler resampler = AudioResampler.fromPipelineFormat(16_000, 1, SampleFormat.SIGNED_16_BIT);
  final AudioPipelineStep pipeline = AudioPipelineStep.of((samples, metadata) -> {
    final byte[] speech = resampler.resample(samples);
    line.write(speech, 0, speech.length);
    return false;
  });

  final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
  final AudioAttachableCallback audio = player.getAudioAttachableCallback();
  audio.attach(pipeline);
  final Path path = Path.of("video.mp4");
  final FileSource source = FileSource.path(path);
  player.start(source);
  // ... once playback has ended

  player.release();
  final byte[] remaining = resampler.flush();
  line.write(remaining, 0, remaining.length);
  resampler.close();
  line.drain();
  line.close();
```

To convert other audio, describe the input yourself with `create`. For example, turning 44.1 kHz 5.1 surround audio
into 48 kHz stereo floating point samples:

```java
  public static byte[] convertSurroundToStereo(final ByteBuffer surroundSamples) {
    try (final AudioResampler resampler = AudioResampler.create(44_100, 6, SampleFormat.SIGNED_16_BIT, 48_000, 2, SampleFormat.FLOAT_32_BIT)) {
      final byte[] converted = resampler.resample(surroundSamples);
      final byte[] tail = resampler.flush();
      final byte[] stereo = Arrays.copyOf(converted, converted.length + tail.length);
      System.arraycopy(tail, 0, stereo, converted.length, tail.length);
      return stereo;
    }
  }
```

### Sample Formats

`SampleFormat` is the encoding of a single sample. Every format is packed (interleaved): the samples of all channels
of one frame follow each other, so a stereo stream is laid out as `left, right, left, right, ...`. Samples are stored in
the native byte order of the platform, which is little-endian on every platform MCAV runs on (x86 and ARM), so the
little-endian audio of the pipeline can be passed to `resample` as it is. Floating point formats use the nominal range
`-1.0` to `1.0`.

| Constant         | Encoding                                                      | Bytes per sample |
|------------------|---------------------------------------------------------------|------------------|
| `UNSIGNED_8_BIT` | Unsigned 8-bit integers, where `128` is silence               | 1                |
| `SIGNED_16_BIT`  | Signed 16-bit integers, the format of the audio pipeline      | 2                |
| `SIGNED_32_BIT`  | Signed 32-bit integers                                        | 4                |
| `SIGNED_64_BIT`  | Signed 64-bit integers                                        | 8                |
| `FLOAT_32_BIT`   | 32-bit IEEE 754 floating point numbers                        | 4                |
| `FLOAT_64_BIT`   | 64-bit IEEE 754 floating point numbers                        | 8                |

`getBytesPerSample()` returns the size of one sample of one channel, and `getFfmpegFormat()` returns the matching
packed `avutil.AV_SAMPLE_FMT_*` constant, for use with the FFmpeg bindings of JavaCV.

## Extracting Audio

`AudioExtractor` in `me.brandonli.mcav.utils.ffmpeg` extracts the audio track of media into a standalone audio file
with the FFmpeg program bundled with MCAV. The audio is written as stereo Ogg Vorbis, a compact and royalty-free format
that is played by Minecraft resource packs, web browsers, game engines, and most media players. The
[audio resource packs](../bukkit/resourcepack.md) of the Bukkit module use it.

`AudioExtractor.extractOggVorbis(Source)` transcodes the audio of the source, such as a file or a URL, to stereo Ogg
Vorbis: mono and surround audio is mixed to two channels, and any video is dropped. Every call writes a new file with
a random name into the MCAV cache folder, `~/.mcav/cache`, and returns its absolute path, so extracting the same source
twice yields two files. The file belongs to you: move or delete it when you no longer need it.

The method blocks until FFmpeg finishes, which can take a while for long media or slow network sources, so call it off
threads that must stay responsive, such as the main thread of a Minecraft server. It fails in the following cases:

| Exception                     | Meaning                                                                                                   |
|-------------------------------|-----------------------------------------------------------------------------------------------------------|
| `NullPointerException`        | The source is `null`.                                                                                     |
| `IOException`                 | FFmpeg cannot be started, its output cannot be read, or the thread was interrupted while waiting for it. |
| `ProcessException`            | FFmpeg exited with a non-zero exit code, for example because the source does not exist, cannot be decoded, or has no audio track. |
| `java.io.UncheckedIOException` | The cache folder cannot be created.                                                                      |
| `UnsatisfiedLinkError`        | The bundled FFmpeg is not available for this platform.                                                    |

`IOException` is the only checked exception. `ProcessException` and `UncheckedIOException` are ordinary unchecked
exceptions that extend `RuntimeException`, so `catch (RuntimeException exception)` catches them too. Catch
`ProcessException` (in `me.brandonli.mcav.utils.runtime`) explicitly if you want to handle a source that FFmpeg cannot
convert:

```java
  final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  executor.execute(() -> {
    final URI mediaUri = URI.create("https://example.com/video.mp4");
    final UriSource media = UriSource.uri(mediaUri);
    try {
      final Path oggFile = AudioExtractor.extractOggVorbis(media);
      // ... use the Ogg Vorbis file, then delete it
    } catch (final ProcessException exception) {
      // the source does not exist, cannot be decoded, or has no audio track
    } catch (final IOException exception) {
      // FFmpeg could not be run
    }
  });
```

## Network Helpers

`NetworkUtils` in `me.brandonli.mcav.utils.http` answers two questions: what the public address of this machine is,
and whether a URL can still be downloaded. Every method blocks for at most a few seconds per request, so call them off
threads that must stay responsive. They never throw because of network failures; a failure is reported as an empty
result or `false` instead. If the calling thread is interrupted while waiting, the method gives up and the interrupt
flag of the thread stays set.

- `lookUpPublicAddress()` returns the public IPv4 address of this machine as an `Optional<String>`: the address other
  machines on the internet see, which differs from the local address behind a NAT router. It asks
  `NetworkUtils.DEFAULT_ADDRESS_SERVICE`, which is `https://ipv4.icanhazip.com/` and answers with the address of the
  caller as plain text.
- `lookUpPublicAddress(URI)` asks another service with a `GET` request instead. The service must answer with status 200
  and a body that consists of an IPv4 or IPv6 address as plain text; surrounding whitespace, such as a trailing line
  break, is ignored, and redirects are not followed. It throws a `NullPointerException` if the URI is `null` and an
  `IllegalArgumentException` if it is not an absolute `http` or `https` URI with a host.
- `isReachable(URI)` sends a `HEAD` request and returns `true` only if the resource answers with status 200. Redirects
  are followed, and the scheme may be written in any case. It returns `false` for any other status, for a URI that is
  not an absolute `http` or `https` URI with a host, when the server cannot be reached, or when the thread is
  interrupted, and throws a `NullPointerException` if the URI is `null`.

Both lookups return an empty optional if the service cannot be reached, fails, or answers with anything but an IP
address. The results are not cached, so every call sends a request.

A typical use is telling players where to reach a server that runs next to your plugin, such as the
[HTTP audio server](http.md) or a resource pack host, and checking that a hosted file still exists before handing out
its URL:

```java
  final Optional<String> publicAddress = NetworkUtils.lookUpPublicAddress();
  final String hostName = publicAddress.orElse("localhost");

  final URI customService = URI.create("https://api.ipify.org/");
  final Optional<String> addressFromCustomService = NetworkUtils.lookUpPublicAddress(customService);

  final URI packUri = URI.create("https://example.com/pack.zip");
  final boolean stillHosted = NetworkUtils.isReachable(packUri);
  if (!stillHosted) {
    // upload the pack again before sending the URL to players
  }
```

## Handling Fatal Errors

`ThrowableUtils` in `me.brandonli.mcav.utils` helps code that catches failures at a thread boundary, such as a render
thread of your own, and must keep running after a failure without hiding the failures no code can recover from.
`ThrowableUtils.throwIfFatal(Throwable)` rethrows the failure if it is a `VirtualMachineError`, such as an
`OutOfMemoryError` or a `StackOverflowError`, and returns normally otherwise; passing `null` throws a
`NullPointerException`. Call it first in a `catch (RuntimeException | Error ...)` block and handle every other failure
as usual:

```java
  private void renderFrameSafely() {
    try {
      this.renderFrame();
    } catch (final RuntimeException | Error failure) {
      ThrowableUtils.throwIfFatal(failure); // rethrows an OutOfMemoryError or a StackOverflowError
      LOGGER.error("Rendering a frame failed, skipping it", failure);
    }
  }
```
