# Upgrading from the May 2026 snapshot

The rewrite changes public APIs from the `1.0.0-SNAPSHOT` artifacts published on May 11, 2026
(`20260511.021315`). It is not a binary-compatible replacement for that snapshot. Recompile applications and plugins
against the rewritten modules, and update the calls and implementations described below before replacing their runtime
libraries. Updating a jar without rebuilding its callers can produce linkage errors even when its Maven version still
reads `1.0.0-SNAPSHOT`.

Use modules from the same build. In particular, building the sandbox plugin from this source tree does not update the
public snapshot repository. Its loader checks that the downloaded modules contain the classes the plugin needs and
refuses modules that are too old. The [end-to-end build](../manual-test.md#2-start-the-sandbox-server-on-the-devbox) uses a local repository to test the plugin
with its own modules. A distributable plugin needs matching modules available from its dependency repositories.

The release check compared all ten published modules with japicmp 0.26.2, including public and protected members and
synthetic bridges. No missing classes were ignored in these module comparisons. Counts below are incompatible
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

## Browser players

The browser backend is now embedded Chromium through JCEF, in a helper process. The Selenium and Playwright player
and service-provider classes, `BrowserPlayer.selenium(...)`, `BrowserPlayer.playwright(...)`, and
`DEFAULT_CHROME_ARGUMENTS` are removed by the rewrite.

Replace those factories with `BrowserPlayer.create()` or `BrowserPlayer.create(BrowserOptions)`. Configure the
browser through `BrowserOptions.builder()` instead of passing arbitrary Chrome arguments. A source still describes
the page URI, dimensions and frame interval. The [browser chapter](browser.md) shows the current factories, options,
input methods, audio callback and release lifecycle.

Replace the five-argument `BrowserSource.uri` overload with `uri(uri, width, height, frameInterval)`; it no longer
takes a screencast quality setting. Replace `getScreencastWidth`, `getScreencastHeight` and `getScreencastNthFrame`
with `getWidth`, `getHeight` and `getFrameInterval`. `getScreencastQuality` has no replacement.

Custom implementations of `BrowserPlayer` must also implement its new methods, including `scroll`, `isPlaying` and
`getAudioAttachableCallback`. Prefer the supplied factory unless an application specifically needs another backend.

## Runtime preparation

Application code should use `MCAV.api()` and `MCAVApi.install(...)`, with the capability API described in the
[instance chapter](instance.md). The rewrite removes the old `CLibrary` and system package installer helpers.
FFmpeg and OpenCV are prepared before installation returns; VLC and yt-dlp are prepared asynchronously. Code that
needs an optional capability should inspect it or wait for `whenCapabilityReady` before using it. Custom
implementations of `MCAVApi` must implement the new readiness method as well.

QEMU is installed by the server administrator. The rewrite removes `QemuInstaller`; use the [VM API](vm.md) with an
installed QEMU instead. The browser prepares its own runtime on first use, as described above.

Applications that extend the installation infrastructure must also update `Installer.getPath()`, the constructors
of `AbstractInstaller`, and the VLC installation strategies. `InstallationStrategy.execute` now takes a destination
`Path`; its installed-path result has changed generic types. The old release-package and native-discovery helpers
are removed or restricted. Prefer the capability API over invoking those implementation details directly.

## Images, playback and pipelines

Image buffers now have explicit ownership and lifetime operations. Custom `Image` and `ImageBuffer` implementations
must implement the current close, copy, cache-invalidation and ARGB-update contracts. `ImageBuffer.updateData` is now
abstract; implementations can no longer inherit its old default. `ImageBuffer.image(BufferedImage)` no longer
declares its old checked exception, so remove an unreachable catch if that call was the only operation in the block
that threw it. The examinable-property access to `MatImageBuffer` is removed. Use the image interfaces and release
owned buffers.

`VideoPlayer.jcodec()` is removed. Choose a current `VideoPlayer` factory for the backend needed by the application.
Subclasses of `AbstractVideoPlayerCV` must use its current constructor and implement `createFrameGrabber` instead of
`getFrameGrabber`. Several supplied player and callback implementations are now final, and their old `retrieve`
helpers are removed; use the callback interfaces to attach and invoke pipelines. `OriginalVideoMetadata.NO_OP` is
also removed: use the metadata factories and their documented values.

Pipeline builders and steps use more specific generic types. Recompile calls to `PipelineBuilder.audio()` and
`video()` even if their source still compiles. Custom builders need the current `self`, `createStep` and
`createEmptyStep` methods. `PipelineStep.next()` now returns a pipeline step rather than `Object`; custom steps must
also implement `self` and the filter accessors, with the current audio processing contract. Prefer the supplied
builders and `AudioPipelineStep.of` / `VideoPipelineStep.of` factories. `FrameSource.getFrameSupplier()` is removed;
use the source's current frame-production interface rather than extracting its implementation supplier.

`BlendFilter` and `OverlayImageFilter` now accept the image interface through their current constructors, and
`FaceDetectionFilter` has a different construction API. Update callers against those signatures rather than
constructing concrete image-buffer implementations.

## Dithering

The types in `media.player.pipeline.filter.video.dither` now use typed builder interfaces and immutable threshold
matrices. Replace setter calls with the relevant builder's fluent methods, and keep the returned builder. Recompile
all fluent calls: several return descriptors and generic bounds changed even when the method name stayed the same.
Error-strength and temporal settings belong to the error-diffusion builder, rather than the general builder.

`BayerDither` constants now contain `ThresholdMatrix` values instead of `int[][]`. Pass the matrix to the current
mapper API, or call `toArray()` when an independent array is required; create a custom matrix with
`ThresholdMatrix.of(...)`. Custom `PixelMapper` implementations must supply `getStrength`, and custom
`DitherPalette` implementations must supply `getReservedIndices` and `getSize`.

The abstract and temporal dither constructors, protected diffusion hooks and native-entry helpers changed.
Rework subclasses against the current APIs; do not call the removed `FilterLiteDither.ditherNatively` entry point.
The old `MurmurHash3` and `Xoroshiro128PlusRandom` utility classes are removed. Applications using them outside
dithering need their own random-number implementation; this guide does not promise the same sequence from another
generator.

## Bukkit, HTTP and audio integrations

The Bukkit rewrite targets Minecraft 26.3. The old `ServerVersion` enumeration, all its version constants, and
`ServerEnvironment.getNMSRevision()` are removed. Use `ServerEnvironment.getMinecraftVersion()` and
`isSupported()` / `checkSupported()` instead of comparing old NMS revision strings. `MapConfiguration.getMapIds()`
and the old `LocationData` type are removed; use the current map configuration and result APIs. Internal resource-pack
injection hooks and several resource-pack exception constructors changed as well.

Custom `HttpResult` implementations must now supply `getCurrentMedia`, `setCurrentMedia`, `getListenerCount` and
`isRunning`. Custom `DiscordPlayer` implementations must supply `flush`, `getQueuedMillis` and `setPlaying`.
Custom `SVCFilter` implementations must supply `getQueuedFrames`. Existing factory users usually need only to
recompile, while implementation subclasses may also be affected by the new final modifiers.

## Virtual machines and VNC

Replace `VMPlayer.vm()` and `VNCPlayer.vm()` with their `create()` factories. Replace `VNCSource.vnc()` with
`VNCSource.builder()` and declare builder variables as `VNCSource.Builder`, rather than
`VNCSourceImpl.Builder`. Custom players must implement `isPlaying`; the VM player also exposes an audio callback.

`ExecutableFinder.find` now returns an `Optional<Path>` rather than a string; handle a missing executable before
using its path. `VMConfiguration.audio`, `diskSize`, `display`, `graphics` and `kvm` are removed. Use the current
configuration methods, such as `drive` and `accelerator`, for the QEMU command needed by the application. Create disk
images separately. The player controls its own display and audio options; consult the [VM chapter](vm.md) before
adding raw options.

## Maven installer failures

The installer reports failed resolution, verification and copying through `InstallationException`, an unchecked
exception. Replace catches of the removed `InstallationError` and `JarEntryIntegrityException` with that exception
when handling those failures. Class-loader injection failures use `JarInjectorException`. The [installer chapter](installer.md)
describes the current factory, loading callbacks and failure handling.

`MCAVInstaller` is now final. Obtain it through `MCAVInstaller.injector(...)`; use a `JarLoader` callback to control
where downloaded jars are loaded instead of subclassing the installer.

## Utility APIs

The old MCAV `UncheckedIOException` is replaced by `java.io.UncheckedIOException`; update imports and catch clauses.
Audio extraction uses `AudioExtractor` in `me.brandonli.mcav.utils.ffmpeg`, replacing the old Bukkit
`SoundExtractorUtils`. Network helpers are in `me.brandonli.mcav.utils.http.NetworkUtils`; update imports from the
old Bukkit utility and use the current return types and failure handling described in [utilities](utilities.md).

The rewrite also removes general-purpose collection, reflection, unsafe-memory, functional-interface and examinable
helpers. Applications that called them need their own equivalents or suitable Java library APIs; there is no general
one-to-one compatibility adapter. Avoid importing a player's implementation classes for construction or inheritance:
use the public factories, interfaces and callbacks documented in the relevant module chapter.

The removed utility types include `CollectionUtils`, `CopyUtils`, `LockUtils`, `ReflectionUtils`, `NativeUtils`,
`UnsafeProvider`, `UnsafeUtils`, `CriticalTaskException`, `QuadConsumer`, `TriConsumer`, `TriFunction`, and the types in
`me.brandonli.mcav.utils.examinable`. Review both imports and exception handling when replacing these helpers.

Some implementation classes and their constructors are now package-private or final. For example, use the factories
on `DynamicImageBuffer` instead of constructing `DynamicImageBufferImpl`. Code that subclasses an implementation or
calls its former constructors must move to the public interface and its factories, even if its playback behavior
does not otherwise change.

Other utility callers need these updates:

| Old usage | Current usage |
| --- | --- |
| `ModuleLoader.loadPlugins` / `shutdownPlugins` | `loadModules` / `shutdownModules` |
| `AudioResampler` constructors and `resample(byte[])` | `create` or `fromPipelineFormat`, `resample(ByteBuffer)`, then `flush` and `close` as appropriate |
| `FFmpegCommand.execute()` | `createTask()`, then explicitly run the returned `CommandTask` |
| `CommandTask.run(String...)` | Construct the task with its command and call `run()` or `runChecked()` |
| `CommandTask.getCommand()` returning `String[]` | Consume the returned `List<String>` |
| Checked-exception catches around `getOutput()` / `getErrorOutput()` | Handle execution failures at the run operation; the getters no longer declare that exception |
| `IOUtils.getSHA256Hash(String)` | Pass a `Path` |
| `KeyCode.charAt(int)` | Use `getKeyChar`, `getCodePoint` or `asString` for the needed representation |
| Removed `SourceUtils.getSource` and `ByteUtils` conversion helpers | Use the current source and buffer APIs; do not depend on their former implementation helpers |

The yt-dlp DTOs also have field-type changes: `Format.filesize` and `filesize_approx`, and the count/size fields of
`URLParseDump`, use `long`; `URLParseDump.duration` and `fps` use `double`. Update arithmetic, serialization bindings
and reflective field access. Several exception types no longer inherit the removed MCAV unchecked-I/O type; review
catches for the concrete exception or its current superclass, not just imports.

## Bundled dependencies

The Maven installer bundles its resolver dependencies under `me.brandonli.mcav.libs`. Those classes change with the
resolver and its dependencies. Code that imported them should declare the upstream library it needs and use that
library's original package names. The supported installation entry points are in `me.brandonli.mcav.installer`.

Use the dependency metadata of the new modules when rebuilding. In particular, the ordinary JNA artifacts already
supply the classes needed by VLCJ; adding the alternative `jna-jpms` and `jna-platform-jpms` artifacts puts duplicate
definitions on the same class path. HTTP and Discord consumers should retain the Jackson and Tomcat versions declared
by their modules when excluding or overriding transitive dependencies.
