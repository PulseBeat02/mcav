# Upgrading from the May 2026 Snapshot

The rewrite changes public APIs from the `1.0.0-SNAPSHOT` artifacts published on May 11, 2026
(`20260511.021315`). It is not a binary-compatible replacement for that snapshot. Recompile your applications and
plugins
against the rewritten modules, and update the calls and implementations described below before replacing their runtime
libraries. Updating a jar without rebuilding its callers can produce linkage errors even when its Maven version still
reads `1.0.0-SNAPSHOT`.

Use modules from the same build. In particular, building the sandbox plugin from this source tree does not update the
public snapshot repository. Its loader checks that the downloaded modules contain the classes the plugin needs and
refuses modules that are too old. The [end-to-end build](../manual-test.md#2-start-the-sandbox-server-on-the-devbox)
uses a local repository to test the plugin with its own modules. A distributable plugin needs matching modules available
from its dependency repositories.

The release check compared all ten published modules with japicmp 0.26.2, including public and protected members and
synthetic bridges. These comparisons ignored no missing classes. Counts below are incompatible
classes, not independent problems; one class can have several changed members.

| Module | Binary-incompatible classes | Source-incompatible classes |
| --- | ---: | ---: |
| `mcav-browser` | 7 | 7 |
| `mcav-bukkit` | 11 | 11 |
| `mcav-common` | 91 | 101 |
| `mcav-http` | 2 | 3 |
| `mcav-installer` | 3 | 3 |
| `mcav-jda` | 3 | 3 |
| `mcav-lwjgl` | 0 | 0 |
| `mcav-svc` | 0 | 1 |
| `mcav-vm` | 6 | 6 |
| `mcav-vnc` | 6 | 6 |

These are existing rewrite changes. Bundled third-party classes are outside these counts. Compatibility of compiled
calls does not guarantee unchanged behavior or dependencies.

Since that comparison, two modules have new names, and every one of their classes moved to a new package. Change the
dependency, the imports and the installer's `Artifact` constant:

| May 2026 snapshot | Current |
| --- | --- |
| `me.brandonli:mcav-jda`, package `me.brandonli.mcav.jda`, `Artifact.JDA` | `me.brandonli:mcav-discord`, package `me.brandonli.mcav.discord`, `Artifact.DISCORD` |
| `me.brandonli:mcav-svc`, package `me.brandonli.mcav.svc`, `Artifact.SVC` | `me.brandonli:mcav-voicechat`, package `me.brandonli.mcav.voicechat`, `Artifact.VOICECHAT` |

Types and methods of `mcav-common` whose names held an upper-case acronym now case it as a word. Change the
imports and calls:

| Former name | Current name |
| --- | --- |
| `AbstractVideoPlayerCV` | `AbstractVideoPlayerCv` |
| `FFmpegCommand` | `FfmpegCommand` |
| `FFmpegDirectSource` | `FfmpegDirectSource` |
| `FFmpegDirectSourceDetector` | `FfmpegDirectSourceDetector` |
| `FFmpegDirectSourceImpl` | `DefaultFfmpegDirectSource` |
| `FFmpegExecutableProvider` | `FfmpegExecutableProvider` |
| `FFmpegPlayer` | `FfmpegPlayer` |
| `FFmpegTemplates` | `FfmpegTemplates` |
| `IOUtils` | `IoUtils` |
| `OpenCVPlayer` | `OpenCvPlayer` |
| `OS` | `Os` |
| `OSUtils` | `OsUtils` |
| `OSXInstallationStrategy` | `OsxInstallationStrategy` |
| `URLParseDump` | `UrlParseDump` |
| `VLCDiscoveryStrategies` | `VlcDiscoveryStrategies` |
| `VLCInstallationKit` | `VlcInstallationKit` |
| `VLCInstaller` | `VlcInstaller` |
| `VLCPlayer` | `VlcPlayer` |
| `YTDLPInstaller` | `YtdlpInstaller` |
| `YTDLPParseException` | `YtdlpParseException` |
| `YTDLPParser` | `YtdlpParser` |
| `YTDLPParserImpl` | `ExecutableYtdlpParser` |
| `FFmpegExecutableProvider.getFFmpegPath()` | `getFfmpegPath()` |
| `IOUtils.getSHA1Hash` | `getSha1Hash` |
| `IOUtils.getSHA256Hash` | `getSha256Hash` |
| `IOUtils.getNextFreeVNCPort` | `getNextFreeVncPort` |
| `OSUtils.getOS()` | `getOs()` |
| `Platform.getOS()` | `getOs()` |
| `ReleasePackageManager.readVLCDownloadsFromJsonResource` | `readVlcDownloadsFromJsonResource` |
| `DependencyLoader.installVLC()` | `installVlc()` |
| `DependencyLoader.installYTDLP()` | `installYtdlp()` |

Names that abbreviated a word now spell it out:

| Former name | Current name |
| --- | --- |
| `AbstractInstaller.writePathToConfig` | `writePathToConfiguration` |
| `AbstractVideoPlayerCV.MIN_SPEED` | `MINIMUM_SPEED` |
| `AbstractVideoPlayerCV.MAX_SPEED` | `MAXIMUM_SPEED` |
| `AbstractVideoPlayerCV.getPositionMillis()` | `getPositionMilliseconds()` |
| `AudioResampler.MAX_CHANNELS` | `MAXIMUM_CHANNELS` |
| `BayerDither.*_MAX` | `*_MAXIMUM`, such as `NORMAL_8X8_MAXIMUM` |
| `DelayedAudioOutput.MAX_QUEUED_MILLIS` | `MAXIMUM_QUEUED_MILLISECONDS` |
| `DelayedAudioOutput.SILENCE_MILLIS` | `SILENCE_MILLISECONDS` |
| `DiffusionKernel.MAX_OFFSET` | `MAXIMUM_OFFSET` |
| `DiffusionKernel.getMaxOffsetX()` | `getMaximumOffsetX()` |
| `DiffusionKernel.getMaxOffsetY()` | `getMaximumOffsetY()` |
| `DirectoryProviderDiscoveryStrategy.MAX_VISITED_DIRECTORIES` | `MAXIMUM_VISITED_DIRECTORIES` |
| `DirectoryProviderDiscoveryStrategy.PLUGIN_ENV_NAME` | `PLUGIN_ENVIRONMENT_NAME` |
| `FPSFilter` | `FramesPerSecondFilter` |
| `IOUtils.MAX_IMAGE_BYTES` | `IoUtils.MAXIMUM_IMAGE_BYTES` |
| `PixelMapper.MIN_STRENGTH` | `MINIMUM_STRENGTH` |
| `PixelMapper.MAX_STRENGTH` | `MAXIMUM_STRENGTH` |
| `VMConfiguration.buildArgs()` | `buildArguments()` |
| `VMSettings.getTargetFps()` | `getTargetFrameRate()` |
| `VolumeFilter.MAX_VOLUME` | `MAXIMUM_VOLUME` |
| `WinInstallationStrategy` | `WindowsInstallationStrategy` |

Implementation classes take their names from how they work, or start with `Default` when nothing else sets them apart:

| Former name | Current name |
| --- | --- |
| `AudioAttachableCallbackImpl` | `DefaultAudioAttachableCallback` |
| `AudioPipelineStepImpl` | `DefaultAudioPipelineStep` |
| `DeviceSourceImpl` | `DefaultDeviceSource` |
| `DimensionAttachableCallbackImpl` | `DefaultDimensionAttachableCallback` |
| `DynamicImageBufferImpl` | `FfmpegDynamicImageBuffer` |
| `FileSourceImpl` | `DefaultFileSource` |
| `FrameSourceImpl` | `SupplierFrameSource` |
| `ImagePlayerImpl` | `ThreadedImagePlayer` |
| `NearestDitherImpl` | `ColorMapNearestDither` |
| `OriginalAudioMetadataImpl` | `DefaultOriginalAudioMetadata` |
| `OriginalVideoMetadataImpl` | `DefaultOriginalVideoMetadata` |
| `RandomDitherImpl` | `UniformNoiseDither` |
| `RepeatingFrameSourceImpl` | `DefaultRepeatingFrameSource` |
| `StrategySelectorImpl` | `DefaultStrategySelector` |
| `UriSourceImpl` | `DefaultUriSource` |
| `VideoAttachableCallbackImpl` | `DefaultVideoAttachableCallback` |
| `VideoPipelineStepImpl` | `DefaultVideoPipelineStep` |
| `WritableImpl` | `DefaultWritable` |

## Browser players

The browser backend is now embedded Chromium through JCEF, in a helper process. The rewrite removes the Selenium and
Playwright player
and service-provider classes, `BrowserPlayer.selenium(...)`, `BrowserPlayer.playwright(...)`, and
`DEFAULT_CHROME_ARGUMENTS`.

Replace those factories with `BrowserPlayer.create()` or `BrowserPlayer.create(BrowserOptions)`. Configure the
browser through `BrowserOptions.builder()` instead of passing arbitrary Chrome arguments. A source still describes
the page URI, dimensions and frame interval. The [browser chapter](browser.md) shows the current factories, options,
input methods, audio callback and release lifecycle.

Replace the five-argument `BrowserSource.uri` overload with `uri(uri, width, height, frameInterval)`; it no longer
takes a screencast quality setting. Replace `getScreencastWidth`, `getScreencastHeight` and `getScreencastNthFrame`
with `getWidth`, `getHeight` and `getFrameInterval`. `getScreencastQuality` has no replacement.

Custom implementations of `BrowserPlayer` must also implement its new methods, including `scroll`, `isPlaying` and
`getAudioAttachableCallback`. Prefer the supplied factory unless your application needs another backend.

## Runtime preparation

Use `MCAV.api()` and `MCAVApi.install(...)` in your application, with the capability API described in the
[instance chapter](instance.md). The rewrite removes the old `CLibrary` and system package installer helpers.
MCAV prepares FFmpeg and OpenCV before installation returns, and VLC and yt-dlp asynchronously. If your code
needs an optional capability, inspect it or wait for `whenCapabilityReady` before using it. Custom
implementations of `MCAVApi` must implement the new readiness method as well.

MCAV installs QEMU from Debian packages on x86-64 and ARM64 Linux when the system has none; on other platforms,
install QEMU yourself. The public `QemuInstaller` of earlier versions is gone, and the new one is internal: use the
[VM API](vm.md) instead. The browser prepares its own runtime on first use, as described above.

If your application extends the installation infrastructure, also update `Installer.getPath()`, the constructors
of `AbstractInstaller`, and the VLC installation strategies. `InstallationStrategy.execute` now takes a destination
`Path`; its installed-path result has changed generic types. The rewrite removes or restricts the old release-package
and native-discovery
helpers. Prefer the capability API over invoking those implementation details directly.

## Images, playback and pipelines

Image buffers now have explicit ownership and lifetime operations. Custom `Image` and `ImageBuffer` implementations
must implement the current close, copy, cache-invalidation and ARGB-update contracts. `ImageBuffer.updateData` is now
abstract; implementations can no longer inherit its old default. `ImageBuffer.image(BufferedImage)` no longer
declares its old checked exception, so remove an unreachable catch if that call was the only operation in the block
that threw it. The rewrite removes the examinable-property access to `MatImageBuffer`. Use the image interfaces and
release
owned buffers.

The rewrite removes `VideoPlayer.jcodec()`. Choose a current `VideoPlayer` factory for the backend that your application
needs.
Subclasses of `AbstractVideoPlayerCv` must use its current constructor and implement `createFrameGrabber` instead of
`getFrameGrabber`. Several supplied player and callback implementations are now final, and
lose their old `retrieve` helpers; use the callback interfaces to attach and invoke pipelines. The rewrite also removes
`OriginalVideoMetadata.NO_OP`: use the metadata factories and their documented values.

Pipeline builders and steps use more specific generic types. Recompile calls to `PipelineBuilder.audio()` and `video()`
even if their source still compiles. `AudioPipelineStepBuilder` and `VideoPipelineStepBuilder` are now interfaces,
and their implementations are internal. `PipelineStep.next()` now returns an `Optional` of the next pipeline step
rather than `Object`; custom steps must also implement `self` and the filter accessors, with the current audio
processing contract. Prefer the supplied builders and `AudioPipelineStep.of` / `VideoPipelineStep.of` factories.
The rewrite removes `FrameSource.getFrameSupplier()`; use the source's current frame-production interface rather than
extracting its implementation supplier.

`BlendFilter` and `OverlayImageFilter` now accept the image interface through their current constructors, and
`FaceDetectionFilter` has a different construction API. Update callers against those signatures rather than
constructing concrete image-buffer implementations.

## Dithering

The types in `media.player.pipeline.filter.video.dither` now use typed builder interfaces and immutable threshold
matrices. Replace setter calls with the relevant builder's fluent methods, and keep the returned builder. Recompile
all fluent calls: several return descriptors and generic bounds changed even when the method name stayed the same.
Error-strength and temporal settings belong to the error-diffusion builder, rather than the general builder. The
builder interfaces no longer take type parameters, and their implementations are internal: get a builder from the
`DitherAlgorithm` factories, such as `DitherAlgorithm.errorDiffusion()`.

`BayerDither` constants now contain `ThresholdMatrix` values instead of `int[][]`. Pass the matrix to the current
mapper API, or call `toArray()` when you need an independent array; create a custom matrix with
`ThresholdMatrix.of(...)`. Custom `PixelMapper` implementations must supply `getStrength`, and custom
`DitherPalette` implementations must supply `getReservedIndices` and `getSize`.

The abstract and temporal dither constructors, protected diffusion hooks and native-entry helpers changed.
Rework subclasses against the current APIs; do not call the removed `FilterLiteDither.ditherNatively` entry point.
The rewrite removes the old `MurmurHash3` and `Xoroshiro128PlusRandom` utility classes. If your application uses them
outside
dithering, it needs its own random-number implementation; this guide does not promise the same sequence from another
generator.

## Bukkit, HTTP and audio integrations

The Bukkit rewrite targets Minecraft 26.3. The rewrite removes the old `ServerVersion` enumeration, all its version
constants, and
`ServerEnvironment.getNMSRevision()`. Use `ServerEnvironment.getMinecraftVersion()` and
`isSupported()` / `checkSupported()` instead of comparing old NMS revision strings. The rewrite removes
`MapConfiguration.getMapIds()`
and the old `LocationData` type; use the current map configuration and result APIs. Internal resource-pack
injection hooks and several resource-pack exception constructors changed as well.

Types of `mcav-bukkit` whose names held an upper-case acronym now case it as a word:

| Former name | Current name |
| --- | --- |
| `MCPackHosting` | `McPackHosting` |
| `MCPacksException` | `McPacksException` |

The configuration builders are now top-level interfaces with internal implementations. Declare builder variables as
`MapConfigurationBuilder`, `BlockConfigurationBuilder`, `ChatConfigurationBuilder`, `EntityConfigurationBuilder` or
`ScoreboardConfigurationBuilder` rather than `MapConfiguration.Builder<?>` and the other nested builders; the rewrite
removes the nested
result builders, such as `MapConfiguration.MapResultBuilder`.

The exceptions of `mcav-bukkit` now extend `BukkitException`, an unchecked subtype of `MCAVException`.
`HttpServerException`, `InjectorException` and `McPacksException` no longer extend `AssertionError`, so a catch of
`AssertionError` around resource-pack hosting no longer sees them; catch `BukkitException` instead.

Custom `HttpResult` implementations must now supply `getCurrentMedia`, `setCurrentMedia`, `getListenerCount` and
`isRunning`. Custom `DiscordPlayer` implementations must supply `flush`, `getQueuedMilliseconds` and `setPlaying`.
`SVCFilter` and `SVCModule` are now `SvcFilter` and `SvcModule`, and custom `SvcFilter` implementations must supply
`getQueuedFrames`. If you only call the factories, recompiling is usually enough; the new `final` modifiers may also
break your subclasses of the implementations.

The Discord module cases its acronym as a word: install `JdaModule` instead of `JDAModule`. The player that
`DiscordPlayer.voice(jda)` returns, formerly `DiscordPlayerImpl`, is now `PcmDiscordPlayer`.

## Virtual machines and VNC

Replace `VMPlayer.vm()` with `VmPlayer.create()` and `VNCPlayer.vm()` with `VncPlayer.create()`. Replace
`VNCSource.vnc()` with `VncSource.builder()` and declare builder variables as `VncSourceBuilder`, rather than
`VNCSourceImpl.Builder`. Custom players must implement `isPlaying`; the VM player also exposes an audio callback.

`VMConfiguration.builder()` returned the configuration itself; `VmConfiguration.builder()` now returns a
`VmConfigurationBuilder` that holds the setters. Declare builder variables as `VmConfigurationBuilder` and call its
`build()` for the immutable `VmConfiguration` that `VmPlayer.start` takes.

Types of `mcav-vm` and `mcav-vnc` whose names held an upper-case acronym now case it as a word, and implementation
classes take their names from how they work:

| Former name | Current name |
| --- | --- |
| `VMConfiguration` | `VmConfiguration` |
| `VMModule` | `VmModule` |
| `VMPlayer` | `VmPlayer` |
| `VMPlayerImpl` | `QemuVmPlayer` |
| `VMSettings` | `VmSettings` |
| `VNCModule` | `VncModule` |
| `VNCPlayer` | `VncPlayer` |
| `VNCPlayerImpl` | `VernacularVncPlayer` |
| `VNCSource` | `VncSource` |
| `VNCSourceImpl` | `DefaultVncSource` |

`ExecutableFinder.find` now returns an `Optional<Path>` rather than a string; handle a missing executable before
using its path. The rewrite removes `VMConfiguration.audio`, `diskSize`, `display`, `graphics` and `kvm`. Use the
builder
methods, such as `drive` and `accelerator`, for the QEMU command that your application needs. Create disk images
separately. The player controls its own display and audio options; consult the [VM chapter](vm.md) before adding raw
options.

The VM player reports failures that come from QEMU or the machine as a `VmException`, the base of `mcav-vm` under
`MCAVException`, rather than a `PlayerException`: a VNC port that is taken, a password file that cannot be written,
and a QEMU that cannot start, exits early or never opens its display. `ExecutableNotInPathException` now extends
`VmException` and is final. The player refuses a configuration that sets an option it reserves with an
`IllegalArgumentException`.

## Maven installer failures

The installer reports failed resolution, verification and copying through `InstallationException`, an unchecked
exception. Replace catches of the removed `InstallationError` and `JarEntryIntegrityException` with that exception when
handling those failures. Class-loader injection failures use `JarInjectorException`. The [installer
chapter](installer.md) describes the current factory, loading callbacks and failure handling.
`MCAVInstaller` is now final. Obtain it through `MCAVInstaller.injector(...)`; use a `JarLoader` callback to control
where the installer loads downloaded jars instead of subclassing the installer.

## Utility APIs

The rewrite removes the old MCAV `UncheckedIOException`. `IoUtils` reports I/O failures as a `FileOperationException`
and a
port search that finds no free port as an `MCAVException`; update imports and catch clauses.
Audio extraction uses `AudioExtractor` in `me.brandonli.mcav.utils.ffmpeg`, replacing the old Bukkit
`SoundExtractorUtils`. Network helpers are in `me.brandonli.mcav.utils.http.NetworkUtils`; update imports from the
old Bukkit utility and use the current return types and failure handling described in [utilities](utilities.md).

The rewrite also removes general-purpose collection, reflection, unsafe-memory, functional-interface and examinable
helpers. If your application called them, it needs its own equivalents or suitable Java library APIs; there is no
general
one-to-one compatibility adapter. Avoid importing a player's implementation classes for construction or inheritance:
use the public factories, interfaces and callbacks documented in the relevant module chapter.

The removed utility types include `CollectionUtils`, `CopyUtils`, `LockUtils`, `ReflectionUtils`, `NativeUtils`,
`UnsafeProvider`, `UnsafeUtils`, `CriticalTaskException`, `QuadConsumer`, `TriConsumer`, `TriFunction`, and the types in
`me.brandonli.mcav.utils.examinable`. Review both imports and exception handling when replacing these helpers.

Some implementation classes and their constructors are now package-private or final. For example, use the factories
on `DynamicImageBuffer` instead of constructing `DynamicImageBufferImpl`. If your code subclasses an implementation or
calls its former constructors, move it to the public interface and its factories, even if its playback behavior
does not otherwise change.

Other utility callers need these updates:

| Old usage | Current usage |
| --- | --- |
| `ModuleLoader.loadPlugins` / `shutdownPlugins` | `loadModules` / `shutdownModules` |
| `AudioResampler` constructors and `resample(byte[])` | `create` or `fromPipelineFormat`, `resample(ByteBuffer)`, then `flush` and `close` as appropriate |
| `FFmpegCommand.execute()` | `FfmpegCommand.createTask()`, then explicitly run the returned `CommandTask` |
| `CommandTask.run(String...)` | Construct the task with its command and call `run()` or `runChecked()` |
| `CommandTask.getCommand()` returning `String[]` | Consume the returned `List<String>` |
| Checked-exception catches around `getOutput()` / `getErrorOutput()` | Handle execution failures at the run operation; the getters no longer declare that exception |
| `IOUtils.getSHA256Hash(String)` | `IoUtils.getSha256Hash(Path)` |
| `KeyCode.charAt(int)` | Use `getKeyChar`, `getCodePoint` or `asString` for the needed representation |
| Removed `SourceUtils.getSource` and `ByteUtils` conversion helpers | Use the current source and buffer APIs; do not depend on their former implementation helpers |
| `ByteUtils.isLittleEndian()` / `isBigEndian()` | Compare `ByteOrder.nativeOrder()` with `ByteOrder.LITTLE_ENDIAN` or `ByteOrder.BIG_ENDIAN` |
| `DirectoryProviderDiscoveryStrategy` plugin path formats such as `"%s/plugins"` | Pass the plugin directories relative to the library directory as a `List<Path>`, such as `List.of(Path.of("plugins"))` |

The yt-dlp DTOs in `me.brandonli.mcav.json.ytdlp.format` are now immutable value classes with `equals`, `hashCode` and
`toString`. Their public snake_case fields are gone: read each value through a camel-case getter with the abbreviations
spelled out, such as `Format.getAudioExtension()` for `audio_ext`, `getFramesPerSecond()` for `fps`,
`getApproximateFileSize()` for `filesize_approx`, `isDrmProtected()` for `has_drm`, `UrlParseDump.isLive()` for
`is_live` and `UrlParseDump.getFileNameAlias()` for `filename`. A value yt-dlp can leave out is an `Optional`, and a
list is unmodifiable and empty when yt-dlp reports none. `new UrlParseDump()` and field assignment are gone: create
metadata for a source that yt-dlp did not resolve with `UrlParseDump.of(title)` or
`UrlParseDump.of(title, description)`, and parse any other metadata from JSON with Gson. Counts and sizes are `long`,
and `UrlParseDump.getDuration()` and `getFramesPerSecond()` are `double`.

Several exception types no longer inherit the removed MCAV unchecked-I/O type; review catches for the concrete exception
or its current superclass, not just imports. The exceptions of `mcav-common` now extend `MCAVException`, an unchecked
base for failures outside your control: `NativeLoadingException` and `PaletteLoadingException` are no longer an
`IllegalStateException`, and `UnsupportedOperatingSystemException` is no longer an `UnsupportedOperationException`.

## Operational failures

Public methods of `mcav-common` no longer declare `IOException`. They report a failure outside your control as
an unchecked subtype of `MCAVException`, so remove `throws IOException` clauses that only existed for these calls and
replace catches of `IOException` around them with the exception below:

| Failure | Exception |
| --- | --- |
| An HTTP request or download fails | `DownloadException` in `me.brandonli.mcav.utils.http` |
| A file cannot be opened, moved or deleted | `FileOperationException` in `me.brandonli.mcav.utils` |
| A process cannot be started or read, times out or is interrupted | `ProcessException` in `me.brandonli.mcav.utils.runtime` |
| VLC or yt-dlp cannot be installed or located | `InstallationException` in `me.brandonli.mcav.capability.installer` |
| An animated image cannot be decoded | `ImageDecodingException` in `me.brandonli.mcav.media.image` |

`HttpStatusException`, `ChecksumMismatchException`, `DownloadTooLargeException`, `DownloadTooSlowException` and
`RedirectRefusedException` now extend `DownloadException` instead of `IOException`. The affected calls are
`HttpDownloader.download`, `openStream` and `fetchText`; `IoUtils.deleteRecursively` and `moveReplacing`; the
`CommandTask` constructor, `run` and `runChecked`; `AudioExtractor.extractOggVorbis`; `Installer.download` and
`findInstallation`; `VlcInstallationKit.start`; `YtdlpParser.parse`; `DynamicImageBuffer.path` and `uri`; and
`Writable.newInputStream` and `newOutputStream`. Implementations of `Installer`, `InstallationStrategy`,
`ProcessRunner` and `YtdlpParser` drop `throws IOException` from their overrides and report failures with the
exceptions above.

The `FaceDetectionFilter` constructors throw `NativeLoadingException` in `me.brandonli.mcav.utils.natives` instead of
`IllegalStateException` when they cannot load the OpenCV object detection natives.

## Bundled dependencies

The Maven installer bundles its resolver dependencies under `me.brandonli.mcav.libs`. Those classes change with the
resolver and its dependencies. If your code imported them, declare the upstream library it needs and use that
library's original package names. The supported installation entry points are in `me.brandonli.mcav.installer`.

Use the dependency metadata of the new modules when rebuilding. In particular, the ordinary JNA artifacts already
supply the classes vlcj needs; adding the alternative `jna-jpms` and `jna-platform-jpms` artifacts puts duplicate
definitions on the same class path. If you use the HTTP or Discord module, keep the Jackson and Tomcat versions
it declares when excluding or overriding transitive dependencies.
