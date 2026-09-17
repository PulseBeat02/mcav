# mcav overhaul — handover for the next agent

This document describes, in detail, the complete overhaul of the mcav repository
(`C:\Users\brand\IdeaProjects\mcav`) done across several long sessions ending on 2026-09-15. Read it top to bottom
before touching the code: it explains the owner's rules, the state of every module, how everything was verified, what
changed and why, the breaking API changes, and what is still open. Section 14 is the full chronological working log
appended verbatim.

---

## 1. What mcav is

- **mcav** is a Java 25 media library (players, pipelines, dithering, audio/video sources) plus a **Paper 26.2**
  sandbox plugin (`sandbox/plugin`) that shows videos, images, browsers and virtual machines in Minecraft.
- Modules (Gradle subprojects): `mcav-common` (core library), `mcav-installer` (zero-dependency bootstrap that
  downloads the library jars), `mcav-http` (Spring Boot web page that streams audio), `mcav-jda` (Discord audio),
  `mcav-svc` (Simple Voice Chat audio), `mcav-bukkit` (maps, blocks, entities, scoreboards, resource packs),
  `mcav-browser` (Selenium/Playwright), `mcav-vnc`, `mcav-vm` (QEMU), `mcav-lwjgl` (OpenGL texture filter),
  `sandbox:plugin` (the Paper plugin). The Fabric mod (`sandbox:mod`) was **removed** on request.
- Website for the HTTP module: `mcav-http/mcav-website` (Next.js 16, React 19).
- Documentation: `docs/` (Jupyter Book 1 / Sphinx, deployed through ReadTheDocs).

## 2. The owner's rules (apply to every change)

- **Paper 26.2 only**; older Minecraft versions are not supported (the plugin disables itself on other versions).
- **No Java records** — use normal classes with getters.
- **No chained method calls and no method calls passed as arguments** — use a `final` local for every step
  (Mockito `when(...).thenReturn(...)`/`verify(...)` stubbing in tests is the tolerated exception).
- Descriptive, full variable names (no `ctx`, `msg`, `e`, `tmp`, `buf`…; coordinates `x`, `y` are fine).
- Uncle Bob style: methods of about 25 lines or fewer, blank lines between sections.
- Thorough Javadoc on public/protected members; **never `{@inheritDoc}`**.
- Guava `Preconditions` on every public entry point (exception: `mcav-installer` uses `Objects.requireNonNull`
  because it must not depend on Guava — it is the bootstrap that downloads Guava).
- Checker Framework (Nullness) clean; **no `@SuppressWarnings`**; IntelliJ warnings fixed except duplicate-code and
  "can be record" hints; **zero compiler warnings**; every file ends with a newline (LF, except `*.bat` = CRLF).
- **100% line and branch coverage** of production code; tests must assert the *correct* behaviour — when a test
  fails, fix the code, never loosen the test.
- Must work **headless** on Windows, macOS, Unix and Linux **without sudo**.
- **Never commit or stage**; changes stay unstaged in the working tree; new files are registered with `git add -N`
  (intent-to-add) so the IDE shows them as tracked changes, not unversioned files.
- No secrets in code (the old JDA example contained two Discord bot tokens — removed from the code, but they are
  still in git history and **must be revoked by the owner**).
- Run `./gradlew spotlessApply` at the very end.

## 3. Environment and how to verify

- Windows 11, Git Bash, Gradle 9.7.1 wrapper. **Tests run on GraalVM JDK 25**: pass
  `"-Pmcav.testJavaHome=C:/Program Files/Java/graalvm-jdk-25.0.2+10.1"` (JetBrains JBR cannot load the bundled OpenCV
  natives on this machine).
- Headless Linux: WSL Ubuntu 22.04, JDK at `~/jdk-25.0.4.1+1`, no sudo, `DISPLAY`/`WAYLAND_DISPLAY` unset,
  `java.awt.headless=true`.
- Helper scripts live **outside** the repo in `C:\Users\brand\mcav-work\` (see section 13).

Commands:

| Purpose | Command |
|---|---|
| Everything (Windows) | `bash /c/Users/brand/mcav-work/final-verify.sh` → summary + logs in `C:\Users\brand\mcav-work\final-verify\` |
| Coverage lint of one module | `./gradlew :<module>:coverageLint "-Pmcav.testJavaHome=C:/Program Files/Java/graalvm-jdk-25.0.2+10.1" --offline` |
| `check` enforcing coverage | `./gradlew check -Pmcav.coverage` (only on a fully equipped machine: VLC, Chrome, QEMU, GPU, audio) |
| All tests headless on Linux | `MSYS_NO_PATHCONV=1 wsl -d Ubuntu-22.04 -- bash -lc "export MCAV_LINUX_TARGET=\$HOME/mcav-linux-tests MCAV_LINUX_LOG=\$HOME/linux-tests.log; sed 's/\r$//' /mnt/c/Users/brand/mcav-work/linux-headless.sh > ~/lh.sh; bash ~/lh.sh test coverageLint --continue --max-workers=4"` |
| Paper 26.2 E2E (headless Linux) | `MSYS_NO_PATHCONV=1 wsl -d Ubuntu-22.04 -- bash -lc "sed 's/\r$//' /mnt/c/Users/brand/mcav-work/linux-headless.sh > ~/linux-headless.sh; bash ~/linux-headless.sh :sandbox:plugin:e2eTest -Pmcav.e2e=true -Pmcav.acceptMinecraftEula=true"` |
| Docs | `jupyter-book build docs --path-output <dir> --warningiserror --keep-going` (venv with `docs/requirements.txt`) |
| Website | in `mcav-http/mcav-website`: `eslint src`, `tsc --noEmit`, `npm run build` (Node 24.21.0 from `mcav-http/build/nodejs`) |

**State verified 2026-09-17 on a headless Linux box (Ubuntu 24.04, GraalVM 25, no display, no sudo):**

- `./gradlew spotlessCheck compileJava compileTestJava :sandbox:plugin:compileE2eTestJava javadoc --rerun-tasks
  --continue` → **142 tasks, all executed** (nothing up to date), **0 javac / Error Prone / Checker Framework
  warnings**, javadoc clean, spotlessCheck clean.
- `./gradlew test --rerun-tasks --continue` → **2640 tests, 0 failures, 0 errors, 39 skipped**
  (24 mcav-common, 14 mcav-lwjgl, 1 mcav-vm — every skip is environment-gated).
- `./gradlew coverageLint --continue` → green for **browser, http, svc, jda, installer, vnc, bukkit, sandbox**;
  gaps remain only in **lwjgl (113)**, **mcav-common (45)** and **vm (5)**, and every one of those traces to a test
  that skips for a missing dependency on this box: no OpenGL context (GLFW reports `GLFW_PLATFORM_UNAVAILABLE`
  even against Xvfb), `libvlc5` present but with **zero plugins** so libvlc cannot initialise, no GTK 2, no
  `/dev/snd`, no QEMU, and the bundled OpenCV Linux build has no video-file backend.
- Text scans: 0 files without a final newline, 0 `@SuppressWarnings`, 0 records, 0 `{@inheritDoc}`,
  0 `-AsuppressWarnings`, 0 `AssertionError` subclasses, 0 TODO/FIXME, 0 CRLF outside `.bat`,
  **0 chained calls** `).x(` across 409 production files.
- **Installing Chrome for Testing is what closed mcav-browser's coverage.** Without a browser every
  `mcav-browser` test skips itself and the module reports 59 uncovered lines; with one it is green. Anyone
  reproducing these numbers needs Chrome + chromedriver on `PATH`.
- **Not verified here:** Windows and macOS (no hardware — those paths are reasoned from the code only), the Paper
  26.2 end-to-end test (opt-in, ~707 MiB download), and IntelliJ inspections (no IDE).

**Earlier state (verified 2026-09-15 on the owner's Windows machine):**

- `final-verify.sh` run 2 (after every change): spotlessCheck 0 violations; forced recompile of every source set
  (incl. `e2eTest`) 0 javac warnings; javadoc 0 warnings; **coverageLint green for every module (0 gaps, 0 failed
  tests)**; docs build with `--warningiserror` succeeded; website eslint/tsc/next build pass; 0 `@SuppressWarnings`,
  0 records, 0 `{@inheritDoc}`, 0 `AssertionError` subclasses; 0 files without a final newline; 0 CRLF outside
  `.bat`; 0 untracked files.
- Headless Linux: 2437 tests across 11 modules, 0 failures after one test bug fix, 47 environment skips (no Chrome,
  QEMU, VLC, GPU/GLFW, sound device, GTK 2; Windows-only paths). Symlink tests (which skip on this Windows account)
  run and pass on Linux.
- Paper 26.2 E2E on headless Linux with Simple Voice Chat 2.6.23: **passes** (cold 556 s incl. a 707 MiB library
  download; cached 95 s). See section 9.
- Final `spotlessApply` + git index normalization: see section 12.

## 4. Build system

- **Root `build.gradle.kts`** is minimal: applies `mcav.java-conventions` to every subproject and a Spotless
  `repository` format (trailing whitespace + final newline) for repo-level files: `*.md *.yml *.yaml *.json
  *.properties *.gradle.kts HEADER LICENSE .editorconfig .gitattributes .gitignore`, `checker-framework/*.astub`,
  `gradle/wrapper/gradle-wrapper.properties`, buildSrc, docs, `.github`, and the website sources/root configs.
  `*.bat` is deliberately excluded (Spotless writes one line ending everywhere; `.gitattributes` keeps `.bat` CRLF).
- **`buildSrc`** (precompiled script plugins):
  - `mcav.java-conventions`: java-library, Checker Framework 3.53.1 (NullnessChecker, production code only, one
    `-Astubs` option joining the stub folders), Spotless (prettier 3.3.3 + prettier-plugin-java 2.6.4, printWidth 140,
    license header, import order), node-gradle (Node **24.21.0** downloaded per module), JUnit 6.1.3, Mockito 5.23.0,
    toolchain Java 25, `-parameters -Xlint:all -Xlint:-processing`, forked javac with 4 GB. Test JVM args:
    `--enable-native-access=ALL-UNNAMED -XX:+EnableDynamicAgentLoading -Xshare:off --sun-misc-unsafe-memory-access=allow`
    (the last two silence the Mockito CDS warning and JOML's `sun.misc.Unsafe` warning). `mcav.testJavaHome`
    overrides the test JVM. A `maven-publish` `endToEnd` repository (`build/e2e-repository`) is added for the E2E.
  - `mcav.coverage-lint`: JaCoCo 0.8.15 XML+HTML; typed `CoverageLintTask` (`buildSrc/src/main/kotlin/me/brandonli/mcav/gradle/`)
    prints every uncovered line/branch as `file:line`, fails on gaps, supports `coverage-exceptions.txt` next to a
    module's build file (entry = `path | exact source line | reason`; stale entries fail the lint), skips when tests
    were filtered. `check` depends on it only with `-Pmcav.coverage`.
- **Configuration cache** cannot be enabled: `checker-framework-gradle-plugin` 1.0.2 (latest) captures `Project` in
  JavaCompile actions (upstream). **Gradle deprecation** printed on every build comes from `paperweight-userdev`
  (`PaperweightUser.kt:403/408`, upstream).
- **Renovate** (`renovate.json`): automerge + platformAutomerge on; holds `typescript < 6.1.0` (typescript-eslint
  supports < 6.1) and `eslint < 10.0.0` (eslint-plugin-react supports ESLint 9).
- **Website build** (`mcav-http/build.gradle.kts`): `npmProjectInstall` now runs `npm ci`.

## 5. Changes by module (summary — details in section 14)

### mcav-common
- **New/moved reusable utilities**: `utils.audio.MonoDownmixer` (**new**; `master` had no such class, svc
  downmixed through `javax.sound.sampled`), `utils.audio.AudioResampler` +
  `SampleFormat` (restored, libswresample-backed), `utils.ffmpeg.AudioExtractor` (from bukkit `SoundExtractorUtils`),
  `utils.http.NetworkUtils` (generic half of bukkit `NetworkUtils`), `utils.http.IdleTimeoutInputStream`
  (package-private), `utils.ThrowableUtils.throwIfFatal`, `capability.CapabilityGuard`.
- **Lifecycle (important)**: `MCAV.install()` returns once the JavaCV natives are loaded, modules started and lookup
  tables built; **VLC and yt-dlp install in the background** (`BackgroundInstallation`, daemon threads).
  New `CompletableFuture<Boolean> MCAVApi.whenCapabilityReady(Capability)`; `hasCapability` = available now.
  `VideoPlayer.vlc()` throws `IllegalStateException` while VLC is being prepared or when it is unavailable; the yt-dlp
  parser refuses while pending. `release()` interrupts and joins the installers (max 10 s for a thread ignoring the
  interrupt), downloads abort and delete their `.part`, AppImage extraction is killed. This fixed a real bug found by
  the E2E: the plugin blocked the server start for many minutes while downloading VLC.
- **Installers/downloads**: `Installer.findInstallation()` (reuse private VLC without network), AppImage downloads
  verified against the GitHub `sha256:` digest, `OS.OTHER`/`Arch.OTHER`/`Platform.isKnown()`, per-read idle timeout
  (60 s), `CommandTask.run(Duration)`/`runChecked(Duration)` kill the process tree on timeout, yt-dlp args get `--`
  and only absolute http(s) URLs, `--playlist-items 1`, unique temp files per download, `IOUtils.moveReplacing` with a
  bounded `AccessDeniedException` retry, and a per-target `Striped<Lock>` in `HttpDownloader` (fixed a Windows-only
  race: concurrent same-target downloads failing in `MoveFileEx`).
- **Exceptions**: none extends `AssertionError` any more (see section 6). `me.brandonli.mcav.utils.UncheckedIOException`
  was deleted in favour of `java.io.UncheckedIOException`.
- **VLC `AudioRenderer`**: a bounded queue of `QUEUE_CAPACITY = 64` chunks with a drop-oldest strategy, so a
  pipeline that falls behind loses the oldest audio instead of growing without bound; pause, seek and stop drop the
  queue. (Previously this document mentioned only its `ThrowableUtils.throwIfFatal` call.)
- **Media/pipeline performance**: pooled frame images (no per-frame allocation), VLC renders at the attached size,
  `VideoFrameCopier` resizes straight from decoder memory, `MatVideoFilter` round-trips raw BGR (no BufferedImage),
  spare-Mat reuse via `MatImageBuffer.transformMat` in Crop/Resize/Rotation/Transpose/Bilateral, `TintFilter` cache,
  `GrayscaleFilter` `ReusableMat`, `FaceDetectionFilter` reuse (synchronized — OpenCV classifier is not thread-safe),
  `TemporalDitherAlgorithm` reuses its index array, `ImageSupplier` bulk raster reads, per-thread random dither,
  seek refused on live sources, start opens the new session before stopping the old **in `AbstractVideoPlayerCV`
  only** (`VLCPlayer` still calls `stopPlayback()` before `created.start()`, so a source VLC accepts but cannot
  open destroys the current playback), `resume()` returns false after
  the end (start replays), OpenCV frames get real timestamps (`VideoTimestamps`), flaky tests fixed deterministically
  (injected clock/threshold/audio lead in `PlaybackSession`).
- **Pixel contract**: `ImageBuffer.getPixels()` is a shared read-only array; new `copyPixels()` (modifiable copy) and
  `getReadOnlyPixels()` (zero-copy read-only `IntBuffer`).
- **FFmpegCommand**: `execute()` renamed `createTask()`; builder methods stay fluent (return the builder).
- **Filter contract**: a filter returns true only if it changed (or may have changed) the data; `NO_OP` filters and
  read-only examples return false.

### mcav-bukkit
- 18 review findings fixed: `BlockRenderer` resends the whole wall to new/returning viewers and every 20 ticks,
  restores blocks for removed viewers; `MapImage` resizes only when configured (centre/crop otherwise); resource-pack
  hosting (`MCPackHosting` revalidates cached URLs, `SimpleResourcePack` path validation and atomic moves,
  `FileServerHandler` length from the open channel + read timeout removed after headers, IPv6 bracketing (now in
  core as `NetworkUtils.formatHostForUrl`; `HttpHosting.getRawUrl` had been missed and produced `http://::1:8080`),
  `NettyHosting` doesn't cache a localhost fallback); `MapLayout` overflow checks; `EntityRenderer` respawns invalid
  displays only in loaded chunks; `BukkitModule` restarts correctly; `DeltaMapEncoder` 128 KiB budget documented.
- `ServerAddress` (server-ip / public address with cache and localhost fallback) replaces the old `NetworkUtils`.
- `UnsupportedServerVersionException` is an `IllegalStateException`; only Paper 26.2 is accepted (`ServerEnvironment`).
- `MapConfiguration.Builder#resize` is **not deprecated** any more. (An earlier version of this line justified that
  with "images have no player", which is **wrong**: `media.player.image.ImagePlayer`/`ImagePlayerImpl` exist. The
  real reason is that an image is shown once, so there is no stream whose size a player could attach.) The Javadoc says to prefer
  the player's `DimensionAttachableCallback` for videos).
- Paper's experimental `Position`/`sendMultiBlockChange` is kept on purpose (the stable route allocates a BlockState
  per changed block per frame).

### sandbox:plugin
- Commands/Javadoc complete; 16 findings fixed (video release on the main thread, overlapping browser/VM starts, JVM
  argument redaction in `/mcav dump`, broken config reported, browser URLs restricted to http(s), scheduling after
  disable, audio outputs start in the background with "not ready" messages, scoreboard ≤ 15 lines, help query fix).
- `MCAVSandbox.onEnable` logs **one line** `MCAV cannot be enabled: <reason> (<what to do>)` and disables cleanly for
  `UnsupportedServerVersionException` and the new `MissingVoiceChatException` (Simple Voice Chat audio enabled but the
  `voicechat` plugin missing); other failures propagate.
- `AudioProvider` logs `The audio web page is available at <url>`; `MCAVVoiceChatPlugin` logs
  `Simple Voice Chat audio is ready`.
- `DitheringArgument.createAlgorithm()` gives each player a fresh temporal dither; `/mcav video resume` reports when
  resume is impossible; VLC/yt-dlp pending messages (`mcav.command.player.preparing`, `mcav.command.ytdlp.preparing`).
- **Runtime download filter**: `org.bytedeco.gradle-javacpp-platform` 1.5.10 + `sandbox/plugin/gradle.properties`
  (`javacppPlatform=linux-x86_64,linux-arm64,macosx-x86_64,macosx-arm64,windows-x86_64`) cut the plugin's first-start
  download from **937 MiB (245 artifacts) to 707 MiB (225)** by dropping Android, iOS, 32-bit and ppc64le natives.
- `mcav-svc` is shaded from this build (`runtimeClasspath` substitution).

### mcav-http / website
- `HttpResult.builder()` (`HttpResultBuilder`: domain, port, directory, bindAddress), `stop()` never blocked by a stuck
  listener (closeAsync), Spring logging left alone, little-endian samples (the module byte-copies, so the buffer's
  order flag cannot affect it — **`master` had no endianness bug here**; its real bugs were one `BinaryMessage`
  shared across sessions so only the first listener got data, `sendMessage` on the pipeline thread, and
  `return false` with no clients), IPv6 URLs, path traversal
  test. Website (`page.tsx`): stale closures fixed (volume kept on reconnect), refs for long-lived callbacks,
  DPR-aware canvas with `ResizeObserver`, no overlapping `/media` fetches and no re-render when unchanged, `next/image`
  unoptimized, no `setState` in effects; TypeScript `~6.0.3`, ESLint `^9.39.5` with the flat `eslint-config-next` config.

### mcav-jda / mcav-svc / mcav-installer
- JDA: Discord activity title stripped/"Unknown title"/cut to 127 chars + "…" without splitting a **surrogate
  pair** (a ZWJ sequence, a skin-tone modifier or a regional-indicator flag can still be cut); buffering
  documented (big-endian conversion, 3 s queue).
- SVC: failed `start()` releases everything it created; half-second queue per speaker (25 frames x 20 ms); the new
  `MonoDownmixer` in common replaces the `javax.sound.sampled` conversion. The real `master` bug: **one shared
  queue was handed to every speaker**, so N speakers were competing consumers and each played 1/N of the audio.
- Installer: jars at `<folder>/<artifact>/<groupId>/<artifactId>/<artifactId-version[-classifier].ext>`, byte-for-byte
  comparison (old hash files deleted), repositories with checksum policy **fail** and daily updates, path escape
  check; `InstallationError` renamed **`InstallationException`** (RuntimeException).

### mcav-browser / mcav-vnc / mcav-vm / mcav-lwjgl
- Browser: `ChromeArguments` (Linux `--disable-dev-shm-usage`, `--no-sandbox` for root/containers, `--headless=new`
  unless `BrowserPlayer.SHOW_WINDOW`), cached-driver fallback per platform and Chrome version, first frame kept,
  Playwright start/stop races and installer timeout (30 min, kills the tree), decode errors reported, restart after a
  lost browser.
- VNC: atomic handshake→session hand-off, restart after failure, first screen update shown.
- VM: VNC port bind-tested, QEMU liveness check, repeatable options (`getAll`, `has`, `remove`), USB tablet handling,
  Windows lookup only `.com/.exe/.bat/.cmd` (no binary planting), QEMU fallbacks (`/etc/paths(.d)`, Homebrew,
  `/usr/local/bin`, `%ProgramFiles%\qemu`), `ExecutableNotInPathException` is a RuntimeException, `OS.OTHER` handled.
  QEMU is **not** installed by mcav (needs admin rights) — it must be pre-installed.
- LWJGL: the four unpack parameters it changes (`ALIGNMENT`, `ROW_LENGTH`, `SKIP_ROWS`, `SKIP_PIXELS`) are saved
  and restored, along with the bound texture; `SWAP_BYTES` and `LSB_FIRST` are untouched and cannot affect a
  `GL_BGR` + `GL_UNSIGNED_BYTE` upload, because both apply only to multi-byte components and to bitmap data.
  Clear error without a current context, double-buffered upload.

## 5a. Defects found and fixed by the post-rewrite review (2026-09-17)

A skeptical review of the whole branch, plus a second-opinion council pass, found the following in code the
rewrite had already shipped. All are fixed on the branch; each behavioural fix has a regression test that was
run against the unfixed code first to prove it fails there.

**Security**

- `/mcav dump` published secrets to a public paste site. `DumpUtils.appendProperties` redacted a property only
  when its *name* matched a secret word, but `sun.java.command` holds the whole program command line, so a server
  started with `--api-token=abc` uploaded that token verbatim to `paste.helpch.at`. Every property *value* is now
  scrubbed word by word with the same rule the JVM arguments use.

**Correctness**

- **A check-then-act race on the dimension callback, in four places.** `isAttached()` followed by `retrieve()`
  lets a `detach()` in between return the empty `Dimension.NONE` fallback. In `VideoFrameCopier.copy` that scaled
  the frame to 0x0, threw out of `PlaybackSession.decode`, and **ended playback**; in
  `VideoRenderer.scaleIfRequested` it rejected every frame; in `AbstractVideoPlayerCV.configureGrabber` it told
  FFmpeg to scale to 0x0; in `VideoRenderer.createBufferFormat` it handed VLC an `RV32BufferFormat(0, 0)` from
  inside a native callback. All four now read the size once and treat an empty size as "not attached".
- `BayerDither.NORMAL_8X8` was the **transpose** of the canonical Bayer 8x8 (56 of 64 cells wrong), contradicting
  the same class's own `createBayerMatrix(8)`. The 2x2 and 4x4 constants were already correct.
- `CompressedMapResult` could not be restarted: `released` was a one-way latch and `start()` was a no-op, so a
  reused result rendered nothing at all for a second video.
- `NettyHosting` installed its handler under a constant pipeline name although the class supports several
  instances, so a second running instance threw `Duplicate handler name` on every new player connection.
- `HttpHosting.getRawUrl()` did not bracket IPv6, producing the unparseable `http://::1:8080`.
- `EntityRenderer` and `ScoreboardRenderer` showed their display/board only at spawn. Both `Player#showEntity`
  and `Player#setScoreboard` are per session, so a viewer added later, or one who relogged, saw nothing. Both now
  refresh in `onTick`, as `BlockRenderer` already did.
- Four `Filter` implementations (`HttpResultImpl`, `DiscordPlayerImpl`, `SVCFilterImpl`, `GLTextureFilter`)
  returned `true` from `applyFilter` although they only read the sample. The contract says `false`, and three of
  them justified `true` with a Javadoc sentence that is simply wrong ("so the pipeline continues with the next
  filter" — pipelines continue regardless).
- `HttpResultImpl` leaked a permanently parked virtual thread: a WebSocket handshake finishing during `stop()`
  was re-inserted after `listeners.clear()` and then parked in an uninterruptible wait nothing could signal.
- Browser resource leaks: `AbstractBrowserPlayer.openPage` rethrew without closing (and set IDLE, not FAILED, so
  the next start would not clean up either); `SeleniumPlayer.open` caught only `WebDriverException`, leaking a
  live Chrome process on any other failure; `ChromeDriverProvider.getService` replaced a stopped service without
  closing it.
- `XoroshiroRandomProvider.nextInt` reduced with `%`, i.e. from the low bits the xoroshiro128+ authors warn
  against, while the same class already took `nextDouble` from the high bits. It now uses a multiply-shift.
- `MatVideoFilter` used `rewind()` where it needed `slice()`, so a third-party `ImageBuffer` whose view starts
  past zero was rejected.
- Fields written under a lock but read without one are now `volatile`: `VNCPlayerImpl.client` (its siblings
  already were), `VMPlayerImpl.process`, `VMProcess.process`/`drainThread`.
- The VLC plugin path is accepted only if it is a **directory**, not merely something with that name.

**The coverage lint itself had a hole.** `CoverageReport.describeGap` ignored a line with missed instructions but
no missed branches, which is exactly what a lambda whose body never runs looks like — JaCoCo attributes the body
to the declaring line. It now reports those too.

**Tests that could not fail** (all strengthened):

- `NativeVLCDiscoveryTest`'s skip guard only checked that libvlc *libraries* existed, while the test required a
  *loadable* VLC. On a server with `libvlc5` but no plugins — the normal state of a machine that pulled libvlc in
  transitively — it **failed** instead of skipping. The guard now also requires a plugin directory. No assertion
  was weakened.
- `MapLayoutTest.numbersMapsRowByRowFromTheStartId` asserted only `getRegion(4)` on a 3x2 grid, and `4 % 3` and
  `4 / 3` are both 1, so a swapped row/column decomposition produced the identical region. The one test named
  after ordering could not detect a transposed numbering.
- `EntityRendererTest` used position `(1, 70, 3)` → chunk `(0,0)`, where `>> 4` and `/ 16` agree, so the floor
  semantics were unasserted.
- `OrderedDitherTest`'s `assertEvenRanking` checked only dimensions and equal level counts, which any
  permutation passes — which is why the transposed 8x8 matrix went unnoticed.

## 6. Exception hierarchy (no class extends AssertionError)

| Exception | Superclass |
|---|---|
| PlayerException, ProcessException, YTDLPParseException, NoMatchingFormatException, InputMetadataException, ZipEntryIntegrityException, MCAVLoadingException, ModuleException | RuntimeException |
| NativeLoadingException, PaletteLoadingException, UnsupportedServerVersionException, InjectorException, MissingVoiceChatException | IllegalStateException |
| UnsupportedOperatingSystemException | UnsupportedOperationException |
| MCPacksException, HttpServerException, HttpException, InstallationException, ExecutableNotInPathException | RuntimeException |
| (deleted) me.brandonli.mcav.utils.UncheckedIOException | → use java.io.UncheckedIOException |

Thread boundaries that still catch `RuntimeException | Error` from user code call `ThrowableUtils.throwIfFatal`
first (never swallow `VirtualMachineError`).

## 7. Breaking API changes (for release notes)

- Removed: the Fabric mod; the old Netty injector classes (`ByteBuddyBukkitInjector`, `HttpInjector`, `HttpByteBuf`,
  `HttpRequest`, `Injector`, `InjectorContext`, `InjectorHandler`, `ReflectBukkitInjector`, `ResourcePackInjector`);
  bukkit `LocationData`, `ServerVersion`, `NetworkUtils`, `SoundExtractorUtils`; vm `QemuInstaller` (+ qemu.json,
  patch-elf, pget); installer `JarEntryIntegrityException`; common `VideoPlayerCV`, `FramePacket`, `JNACallbackPin`,
  `PhantomDebug`, `CLibrary`, `PackageInstaller`, `FFmpegLogger`, `InvalidErrorDiffusionAlgorithmException`,
  `MurmurHash3`, `Xoroshiro128PlusRandom`, `LoadRed/Green/Blue`, `CollectionUtils`, `CopyUtils`,
  `CriticalTaskException`, `LockUtils`, `QuadConsumer`, `TriConsumer`, `TriFunction`, `ReflectionUtils`,
  `NativeUtils`, `UnsafeProvider`, `UnsafeUtils`, the `Examinable` family, `utils.UncheckedIOException`; browser
  `ChromeDriverServiceProvider`, `PlaywrightServiceProvider`, `InvalidMouseClickArgument`; sandbox
  `ConfigurationManager`, `JsonUtils`, `SkullUtils` (+heads.json), `VideoArgument`, `DebugFilter`, `MutableInt`.
- Renamed/changed: `InstallationError` → `InstallationException`; `FFmpegCommand.execute()` → `createTask()`;
  all exceptions now unchecked `RuntimeException` subtypes; `MCAV.install()` no longer waits for VLC/yt-dlp;
  `VideoPlayer.resume()` returns false after the end; `FFmpegDirectSource.isStatic()` is false; `DitheringArgument.
  getAlgorithm()` → `createAlgorithm()`; `MapDisplaySettings.getAlgorithm()` → `createAlgorithm()`.
- **Visible behaviour changes that earlier versions of this document omitted** (they belong in the release notes):
  - `ResizeFilter` interpolation changed `INTER_NEAREST` → `INTER_AREA`/`INTER_LINEAR`. This changes **every map and
    block-wall frame**, so rendered output is not pixel-identical to `master`.
  - `OverlayImageFilter` changed from additive `addWeighted` to opaque `copyTo`.
  - `RectangleFilter`'s bottom-right corner changed from `x + width` to `x + width - 1`.
  - `BlurFilter`'s Gaussian hint changed `ALGO_HINT_APPROX` → `ALGO_HINT_DEFAULT`.
  - `FPSFilter` was rewritten: 1 s window, font 0.25 → 0.6, outlined, y 20 → 24.
  - `FaceDetectionFilter(double[])` was deleted; `BlendFilter` and `OverlayImageFilter` constructors were narrowed
    from `MatImageBuffer` to `ImageBuffer`; several filters now throw `IllegalArgumentException` for inputs `master`
    accepted (Blur even/zero kernels, Luminance negative contrast, Threshold range, Crop/Region/Overlay negative
    origin, Tint strength, Blend alpha).
  - New public `VideoPipelineStepBuilder`/`AudioPipelineStepBuilder` abstract classes; `ResizeFilter.matches(int,int)`.
  - `AbstractVideoPlayerCV.getPositionMillis()` and `isPlaying()` are declared on **no interface**, and the factories
    return `VideoPlayerMultiplexer`, so library users cannot reach them at all. Either promote them to
    `ControllablePlayer`/`VideoPlayer` and implement them in `VLCPlayer`, or drop them.
- Added: `MCAVApi.whenCapabilityReady`, `CapabilityGuard`, `Capability.FACE_DETECTION`/`getDisplayName()`,
  `HttpResult.builder()`, `ImageBuffer.copyPixels()/getReadOnlyPixels()`, `MatImageBuffer.transformMat()/setSize()`,
  `BrowserPlayer.SHOW_WINDOW`, `VMConfiguration.getAll()`, `OS.OTHER`, `Arch.OTHER`, `Platform.isKnown()`, and the
  utilities listed in section 5.

## 8. Documentation

- Every example and API claim in `docs/` and `README.md` was checked against the final source: no `...`
  placeholders, examples are small valid methods, style rules followed, no secrets (VNC password from
  `VNC_PASSWORD`). New page `docs/library/utilities.md`. Plugin docs describe the startup log lines, the self-disable
  messages, background VLC/yt-dlp, and the ~707 MiB first download with supported platforms.
- Site: Jupyter Book **1** pinned (`jupyter-book>=1.0.4,<2` — v2 is a different tool and breaks ReadTheDocs'
  `jupyter-book config sphinx`), `myst_heading_anchors: 3`, unused/deprecated extensions removed (only sphinx-design
  kept), logo `docs/images/logo.png` (128×128 wordmark restored from git), favicon, copyright 2026, branch `master`,
  `docs/conf.py` regenerated from `_config.yml`. Builds with zero warnings.
- `CONTRIBUTING.md` documents the coverage lint, `-Pmcav.coverage`, `coverage-exceptions.txt`, `mcav.testJavaHome`
  and the E2E test.

## 9. The Paper 26.2 end-to-end test

- Source set `sandbox/plugin/src/e2eTest` (`PaperServerEndToEndTest`, `ServerProcess`, `LocalMavenRepositoryServer`,
  `LibraryCache`); task `:sandbox:plugin:e2eTest`, opt-in with `-Pmcav.e2e=true -Pmcav.acceptMinecraftEula=true`
  (running a Minecraft server means accepting the EULA).
- It downloads Paper 26.2 build 123 (SHA-256 verified) and Simple Voice Chat 2.6.23 (SHA-512 verified) into
  `sandbox/plugin/build/e2e-cache`, publishes this build's modules to `build/e2e-repository`, serves that folder over
  HTTP on `127.0.0.1:<free port>` (gremlin only lists http(s) repositories and downloads with `HttpClient`), writes
  offline/flat-world `server.properties`, enables the web page and Simple Voice Chat, runs `plugins` and `mcav help`,
  requests `/media`, stops the server and asserts: exit 0, "MCAV loaded in", "JavaCV natives loaded in",
  "Simple Voice Chat audio is ready", files served by the local repository, no `ERROR]` lines.
- Results: cold run 556 s (gremlin 7m47s for 707 MiB at ~1.5 MiB/s; server itself ~14 s to Done); cached run 95 s.
  The stop arrives while VLC still downloads in the background → "Preparing VLC was cancelled", clean exit.

## 10. Platforms, headless, sudo, download size

- No `sudo`/admin anywhere: JavaCV natives extract into the JavaCPP cache, VLC (AppImage on Linux, bundled zip/dmg on
  Windows/macOS via `hdiutil attach -nobrowse -readonly` into a user folder) and yt-dlp download into `~/.mcav/cache`,
  ChromeDriver/Playwright into user caches. QEMU and Chrome (Selenium backend) must be pre-installed.
- Headless: Chrome `--headless=new`, QEMU `-display none`, VLC renders through callbacks (no window, no sound device),
  no AWT windows in main code.
- Linux limits: the bundled OpenCV Linux build has **no video-file backend** (only V4L2 cameras) → `VideoPlayer.opencv()`
  cannot open files on Linux (use FFmpeg); face detection needs GTK 2 (optional `Capability.FACE_DETECTION`).
- FreeBSD/other BSD: detected as `OS.FREEBSD`/`OTHER`; nothing is downloaded there and JavaCV ships no natives for it.
- **macOS was not tested** (no Mac available); code paths are unit-tested with seams only.
- Plugin first-start download: 707 MiB (Playwright driver-bundle alone 193 MiB). Later starts reuse `libraries/mcav`.

## 11. Known limitations, decisions and recommendations

- **Revoke the two Discord bot tokens** that are in git history (removed from the code).
- Configuration cache blocked by the Checker Framework Gradle plugin; paperweight deprecation warning is upstream.
- `FFmpegPlayerTest` still has **two** wall-clock assertions: real-time playback (> 24 fps, ~1.2 s margin) and
  `assertPausingHoldsTheFrames`, which sleeps 200 ms then asserts a counter is unchanged over another 500 ms.
  `mcav-http`'s `AudioListenerTest:267` also still has a `Thread.sleep(100)` against a real 50 ms limit.
- Audio `ByteBuffer`s are still allocated per chunk (the `AudioFilter` contract lets filters keep them); VLC's
  native→`int[]` copy is kept (a pooled hand-off could race with stop and write freed memory).
- `CapabilityGuard.shared()` is JVM-wide; `release()` waits at most 10 s for an installer thread ignoring interrupts.
- Coverage is 100% on the fully equipped Windows machine; on headless Linux, coverageLint reports gaps only where
  tests skip for missing Chrome/GPU/QEMU/VLC/audio/GTK (by design `check` enforces coverage only with
  `-Pmcav.coverage`). Linux-runnable tests could still be added for `VLCInstaller` lines 229-231 and 258-260.
  **Installing Chrome is what makes `mcav-browser` count**: without a browser its tests skip and the module reports
  59 uncovered lines, and with Chrome for Testing on `PATH` it is green. Anyone reproducing the coverage numbers
  needs `google-chrome` and `chromedriver` available.
- The coverage lint now also reports a line the tests only **partly** cover, not just a wholly uncovered line or an
  uncovered branch. That case is what a lambda whose body no test invokes looks like, since JaCoCo charges the body
  to the line that declares it, and it used to pass silently. Expect the lint to be slightly stricter than before.
- The resource pack format for 26.2 is not derived by the code — users pass `packFormat` (docs point to the
  Minecraft Wiki table).
- The E2E is the only test of real Minecraft integration; live clients (map rendering seen by a player, voice chat
  heard by a player) were not tested interactively.
- Old installs of the installer keep jars in the previous flat folder layout (not loaded any more).
- **`AudioFilter`'s Javadoc says nothing about buffer lifetime**, only about not disturbing the position. The claim
  above that "the contract lets filters keep them" is not backed by the contract as written; either the contract
  should say so or the buffers can be pooled.

### Open defects the 2026-09-17 review found and did NOT fix

These are real. They are listed here so the next person does not have to rediscover them.

- `sandbox/.../AbstractInteractiveCommand.java:248-262` — a browser/VM start superseded by a second `create` that
  then **succeeds** is never released, leaking a Chrome or QEMU process. Only the failure path is guarded, so the
  "overlapping browser/VM starts" fix is partial.
- `sandbox/.../ImageManager.java:74` plus five `scheduler.runTask(this.plugin, …)` sites in the video commands
  bypass `TaskUtils.runOnMainThread`, so they throw during shutdown and the image leaks native memory. The
  "scheduling after disable" fix is likewise partial.
- `sandbox/.../ArgumentUtils.java:57` — `parseDimensions` has no upper bound, so `/mcav screen 100000x100000 …`
  freezes the main thread. OP-gated, but a typo kills the server. A cap is a policy decision.
- `mcav-installer/.../InstallationManager.java:215` — `this.folder.resolve(artifactId)` is unchecked and the escape
  test anchors on the already-escaped path, so an `artifactId` of `..` passes every check. The documented "path
  escape check" covers dependency coordinates only.
- **`mcav-installer` will stop working on a future JDK.** On Java 25,
  `MethodHandles.privateLookupIn(URLClassLoader.class, …)` throws without
  `--add-opens java.base/java.net=ALL-UNNAMED`, so `ReflectiveInjector.ADD_URL` is always null and every real run
  takes the `sun.misc.Unsafe` fallback, which is deprecated for removal. `README.md` and
  `docs/library/prerequisites.md` both claim "no extra JVM arguments". **This is the most urgent item here.**
- `mcav-installer/build.gradle.kts:39` relocates `org.slf4j` with no provider shipped, so the installer's own
  progress lines go to the NOP logger: users see nothing during a multi-minute ~1 GB first download.
- `mcav-installer/.../InstallationManager.java:349` — `hasSameContent` runs `Files.mismatch` over every jar on every
  start with no size fast path (~2 GB of reads per start when nothing changed).
- `buildSrc` `tasks.build { dependsOn("spotlessApply") }` has no ordering against `compileJava` or the
  `spotlessCheck` that `check` pulls in, and `org.gradle.parallel=true`, so `./gradlew build` can compile
  pre-format sources and can fail `spotlessCheck` on the same run that fixes them.
- `DeltaMapEncoder.holdBackNoise` zeroes a tile's noise counter before the budget stage knows whether the map fits,
  so a budget-dropped map restarts its deferral. Bounded by the `waitingFrames` priority bonus.
- `CompressedMapResult.getEncoder` does not clear maps that fall out of a **shrinking** grid; they keep the stale
  picture.
- `ChromeDriverProvider.useCachedDriver` sets the global `webdriver.chrome.driver` property and never clears it.
- `FPSFilter`'s `windowStart`/`framesInWindow`/`displayedFrameRate` are mutated from the render thread without
  `volatile`; a torn `long` read shows "0 fps". Cosmetic only.
- `HttpResultImpl.getFullUrl()` reports the configured port, not the bound one, so a server on port 0 logs
  `http://localhost:0/`.
- The website's `package.json` declares six unused dependencies (`@mui/*`, `@emotion/*`, `howler`,
  `@fontsource/roboto`) that are installed on every `npm ci`.
- `Await.java` is duplicated and divergent between the `mcav-http` and `mcav-lwjgl` test helpers.
- `settings.gradle.kts` — `project(":X").name = "X"` is a no-op for all ten modules.
- `checker-framework/Objects.astub:8` — `Supplier<String>` is unresolved inside `package java.util;`, so that stub
  overload is inert.
- **There is no `-Werror` anywhere in `buildSrc`**, so the zero-warning policy is a convention, not an enforcement,
  and with no CI nothing checks it automatically.

## 12. Git state

**The branch is now committed and pushed.** The 2026-09-17 review ended by splitting its own work into one commit
per logical change and pushing them to `origin rewrite` with an ordinary (non-force) push. Before that point the
whole overhaul sat in the working tree as unstaged changes, which is what the rest of this section describes and
why it reads the way it does.

- The standing "never commit or stage" rule applied to the overhaul sessions. It does **not** describe the branch
  any more: `git log master..rewrite` is the history, and `git status` on a clean checkout is empty.
- `logs/` folders are ignored (tests and servers write logs there); an accidentally staged test log archive was removed.
- `gradlew.bat` always shows as modified and that is **not** anyone's change: `.gitattributes` declares
  `*.bat text eol=crlf` while the committed blob already contains CRLF, so git reports it dirty forever.
  Do not commit it and do not run `git add --renormalize` to "fix" it.

### Final git state

Final `spotlessApply` run (2026-09-15, started 15:35:30): exit 0 and it **changed no file** — no file in the repo
(outside build output) has a modification time after 15:35:30, so the tree was already formatted. (The
"864 files changed" line in `C:\Users\brand\mcav-work\final-apply.log` is `git diff --stat` against HEAD, i.e. the
size of the whole overhaul, not what spotlessApply touched.) The follow-up `spotlessCheck compileJava
compileTestJava :sandbox:plugin:compileE2eTestJava --offline -q` exited 0; being quiet and mostly up-to-date, it is
not itself evidence about warnings. The authoritative results are `final-verify.sh` run 2 (started about 15:21:30,
after the last source edit at 15:21:26 to `docs/plugin/plugin.md`): spotlessCheck clean, forced recompile of all 52
compile tasks with 0 javac warnings, coverageLint green, docs/website green, text-file scans clean. Javadoc was
re-run afterwards with `--rerun-tasks` to confirm 0 warnings (run 2's javadoc tasks were up-to-date, and an
up-to-date task does not reprint warnings).

The git index was then normalized (`git reset` + `git add -N` on every non-ignored untracked file). Final
`git status --porcelain` breakdown:

| Status | Count |
|---|---|
| Added (`A`, includes intent-to-add) | 391 |
| Deleted (`D`) | 86 |
| Modified (`M`) | 438 |
| Renamed (`R`) | 11 |
| **Total changed paths** | **926** |
| Staged entries (first column non-blank) | **0** |
| Untracked, not ignored | **0** |

**Final verification of the whole tree**, after the mutation-testing work of section 14.3 and this document moving
into the repository, in one Gradle invocation:

| Check | Result |
|---|---|
| `spotlessApply` | exit 0; reformatted 14 files, all of them touched during that session |
| `spotlessCheck` | 0 violations |
| Forced recompile of every source set, including `e2eTest` | **0 javac, Error Prone and Checker Framework warnings** |
| `javadoc`, every module | 0 warnings |
| `coverageLint`, every module | 0 gaps, 0 failed tasks |
| Tests, every module | **2621 tests, 0 failures, 0 errors, 6 skipped** |

The six skips are the environment-dependent ones: the Windows symlink tests and the opt-in yt-dlp network tests,
which pass when run with `-Pmcav.networkTests=true` and on Linux.

Every one of the 921 changed paths shows up in the IDE's unstaged/changes view, not as an unversioned file. Nothing
is committed or staged, per the standing rule.

## 13. Helper scripts (outside the repo, `C:\Users\brand\mcav-work\`)

- `final-verify.sh` — every final check with a summary.
- `linux-headless.sh` — rsync the repo into WSL (`MCAV_LINUX_TARGET`, `MCAV_LINUX_LOG`) and run Gradle headless.
- E2E: `e2e-diagnose.sh`, `e2e-status.sh`, `e2e-result.sh`, `e2e-server-output.py`, `e2e-gremlin-probe.sh`,
  `e2e-live.sh`, `e2e-hang.sh`, `e2e-stall.sh`, `e2e-growth.sh`, plus the E2E agent's `e2e-wait-deps.sh`,
  `e2e-watch-log.sh`, `e2e-wait-end.sh`, `e2e-platform-check.sh`, `e2e-live-progress.sh`, `e2e-key-lines.sh`.
- `download-size.py` — size of everything gremlin downloads, per platform.
- `diff-audit.py` — flags suspicious diffs (fewer tests/asserts, deleted tests, whitespace-only, debug output, TODOs,
  secrets, binaries).
- `junit-summary.py`, `linux-kept-test.sh`, `linux-kept-check.sh` — Linux test helpers.

## 14. Follow-up session: yt-dlp 2026.08.19, Error Prone, PIT

### 14.1 yt-dlp pinned to release 2026.08.19

- `mcav-common/src/main/resources/installers/yt-dlp.json` now points every platform at
  `https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/<file>` (previously 2026.02.04, and 2025.08.27 for
  ARMv7). Eight entries, one per platform:

  | Platform | File | SHA-256 |
  |---|---|---|
  | windows-x86-64 | `yt-dlp.exe` | `66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a` |
  | windows-x86-32 | `yt-dlp_x86.exe` | `a8f91bd41452506bc81ebd2f369b186fea0ee7075413ba00cef9fd346a0a5d0c` |
  | windows-arm-64 (new, native) | `yt-dlp_arm64.exe` | `05b438997bafc3affdfda9d041353c9d73e04dc842207254b655b0887c4445b0` |
  | linux-x86-64 | `yt-dlp_linux` | `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a` |
  | linux-arm-64 | `yt-dlp_linux_aarch64` | `b16e4dab368a816cd05d477d698a605a6ae87ccee1c8ffd38fa21d7254141fcc` |
  | linux-arm-32 | `yt-dlp_linux_armv7l.zip` | `bd51eb5fed7788008f4f30d281a182376a1297910b6c78e747403e757a546508` |
  | mac-arm-64 / mac-x86-64 | `yt-dlp_macos` (universal) | `0f192b7ec147ab6288885d6351d9ab67367640029b4377576ef46dd79cf7b202` |

- **How the hashes were established (three independent sources agree for every file):** (1) each file was downloaded
  and hashed locally with `sha256sum` (and `sha512sum`); (2) the release's `SHA2-256SUMS` and `SHA2-512SUMS` were
  checked with `sha256sum -c` / `sha512sum -c` (all OK), and their GPG signatures verified as "Good signature" from the
  official yt-dlp signing key `AC0C BBE6 848D 6A87 3464 AF4E 57CF 6593 3B5A 7581` (imported from the repo's
  `public.key` into a throwaway GNUPGHOME); (3) the GitHub API asset `digest` of every file matches `SHA2-256SUMS`.
  (A `yt-dlp: FAILED` line in the local `sha256sum -c` output is a Git Bash artifact — the zipimport `yt-dlp` asset
  was not downloaded, and MSYS silently opened `yt-dlp.exe` for the missing name.)
- **Upstream change handled:** yt-dlp no longer ships a standalone 32-bit ARM Linux binary, only
  `yt-dlp_linux_armv7l.zip` (PyInstaller onedir: a top-level `yt-dlp_linux_armv7l` launcher, mode 0755, plus
  `_internal/` with 171 files, 73 MiB unpacked, no symlinks or unsafe names, CRC-clean). `YTDLPInstaller` therefore
  gained zip support: `isArchive()` is true when the current platform's download ends in `.zip` (case-insensitive);
  the zip is extracted with the hardened `IOUtils.unzip` into `yt-dlp-unpacking/`, the top-level executable named like
  the zip without `.zip` is required and marked executable, the folder then replaces `yt-dlp-unpacked/`, and the zip
  and temporary folder are removed (a failure never leaves a half-unpacked install). `getDefaultPath()` points at the
  unpacked executable so later runs reuse it without a download. Windows ARM64 now gets its native build; the
  x64-emulation fallback remains for custom download lists.
- Supporting refactors: `IOUtils.deleteRecursively(Path)` (moved from `ManualInstallationStrategy`, which now
  delegates to it, so the yt-dlp installer does not depend on a VLC class); `AbstractInstaller.markExecutable` is now
  `protected static`; `IOUtils`'s single-use format constant was inlined (Error Prone `InlineFormatString`).
- **Tests:** `YTDLPInstallerTest` now asserts the eight pinned URLs and hashes per platform, one download per platform,
  lower-case SHA-256 format, zip detection and paths, a real zip installation served by `LocalHttpServer` (content,
  `_internal` libraries, executable bit on POSIX, zip and temp folder removed), reuse without a second request,
  replacement of stale folders, and failures for a zip without the executable, a non-zip, and an entry escaping the
  folder. `IOUtilsTest` covers `deleteRecursively`. New opt-in `YTDLPReleaseTest` (runs with
  `-Pmcav.networkTests=true`, a new Gradle property forwarded as a system property) compares every bundled hash with
  the live `SHA2-256SUMS` and installs the current platform's real build, then runs `--version` expecting `2026.08.19`.
- **Automated network runs:** `YTDLPReleaseTest` was run with `-Pmcav.networkTests=true` on both platforms, and both
  of its tests passed each time.
  - **Windows:** the pinned hashes match the live `SHA2-256SUMS`. `YTDLPInstaller` downloaded `yt-dlp.exe`, verified
    its hash and ran `--version`, which printed `2026.08.19`; the install took 91 s.
  - **Headless WSL Ubuntu 22.04** (no display, unprivileged user, separate copy `~/mcav-linux-tests`): the same two
    tests passed with `yt-dlp_linux`. `YTDLPInstallerTest` (27), `IOUtilsTest` (36), `AttachableCallbackTest` (5) and
    `ManualInstallationStrategyTest` (13, including the symlink tests that skip on Windows) also passed there, with
    0 failures and 0 skipped.
- **Manual runs:** `yt-dlp.exe` and `yt-dlp_x86.exe` print `2026.08.19` on Windows; `yt-dlp_linux` prints it on
  headless WSL Ubuntu 22.04 (glibc 2.35) as an unprivileged user. A YouTube `-J` probe with the parser's flags returns
  24 formats (12 with video, 10 audio-only) on both. Without a JavaScript runtime yt-dlp warns that YouTube extraction
  without one is deprecated and some formats may be missing (the parser passes `--no-warnings`, so nothing fails);
  documented in `docs/library/yt-dlp.md` (new "Installation" section with the platform table, hash pinning, glibc/musl
  and Deno notes). `docs/library/licensing.md` notes that the PyInstaller-built executables are GPLv3+ as a whole.
- Not testable here: the ARM builds (Windows ARM64, Linux aarch64/armv7l) and the macOS build were hash-verified but
  not executed (no such hardware). musl-based Linux (Alpine) is not supported by the glibc builds; yt-dlp ships
  `yt-dlp_musllinux*` builds, but the `Platform` model has no libc dimension — a possible follow-up.

### 14.2 Error Prone on every module

- **Setup:** `net.ltgt.errorprone` **5.1.1** (buildSrc dependency `net.ltgt.gradle:gradle-errorprone-plugin:5.1.1`)
  with `com.google.errorprone:error_prone_core:`**2.50.0** in the `errorprone` configuration. Both are applied in
  `buildSrc/src/main/kotlin/mcav.java-conventions.gradle.kts` to every `JavaCompile` task: main, test and the sandbox
  `e2eTest` source set. It runs the default checks on production and test code, with
  `disableWarningsInGeneratedCode = true`. It runs next to the Checker Framework's nullness checker, which covers
  production code only.
- **Forked-javac arguments:** the Checker Framework's `--add-exports=jdk.compiler/com.sun.tools.javac.parser` is now
  added through `forkOptions.jvmArgumentProviders`. Before, it was assigned to `jvmArgs`, which would have replaced
  the exports and opens Error Prone adds to the forked javac.
- **Only real modules get the conventions:** the root `build.gradle.kts` now applies them only to projects that have a
  build file. That keeps the empty `:sandbox` container project, which exists because of `include(":sandbox:plugin")`,
  from getting Java, Error Prone or PIT tasks.
- **Proof that it runs everywhere:** an init script, `C:\Users\brand\.claude\jobs\65ad9c21\tmp\analysis-report.init.gradle.kts`,
  reads each task's Error Prone `enabled` property reflectively. Every one of the 11 modules reports `errorprone` on
  `compileJava` and `compileTestJava`, `:sandbox:plugin` also on `compileE2eTestJava`, and every module has a `pitest`
  task. `:sandbox` reports none.
- **First run: 102 findings**, 101 warnings and 1 error, plus 1 in e2eTest:
  - The e2eTest finding appeared only once its processor path resolved online, because `error_prone_core` pulls in
    `checker-qual:3.19.0` there.
  - By check: ReferenceEquality 22, MutablePublicArray 19, FutureReturnValueIgnored 14, UnnecessaryLambda 10,
    AddressSelection 9+1, DirectInvocationOnMock 8, InlineFormatString 4, StringSplitter 3,
    InputStreamSlowMultibyteRead 3, ImmutableEnumChecker 3, ClassInitializationDeadlock 2, and one each of
    WaitNotInLoop, ThreadPriorityCheck, CheckReturnValue (the error), CanonicalDuration and BooleanLiteral.
  - Every finding was fixed in code. There is no `@SuppressWarnings`, and no check was disabled.
  - The full list with context is in `C:\Users\brand\.claude\jobs\65ad9c21\tmp\errorprone-findings.txt`.
- **Behaviour and API changes from those fixes** (for release notes):
  - `BrowserPlayer.DEFAULT_CHROME_ARGUMENTS` is now an unmodifiable `List<String>`; it was `String[]`.
  - `BayerDither` matrices are now instances of the new immutable `ThresholdMatrix` (`of(int[]...)` copies and
    validates, `toArray()` returns a copy, plus `getRowCount()` and `getColumnCount()`), not `int[][]`. `PixelMapper`
    gained `ThresholdMatrix` overloads, and `createBayerMatrix` still returns a fresh `int[][]`.
  - `AudioPipelineStep.NO_OP` and `VideoPipelineStep.NO_OP` are now instances of a nested `NoOperationStep` with a
    private constructor, which removes the class-initialization deadlock risk. The new `PipelineStep.isNoOp()`
    replaces `== NO_OP` checks.
  - `AbstractAttachableCallback` records the attached value directly, with null meaning detached. `isAttached()` is
    therefore true after any `attach`, even of a value equal to the fallback. The agent's first version compared with
    `equals`, which failed the Checker Framework and would have changed behaviour for value types; a new
    `AttachableCallbackTest` case pins the fixed behaviour.
  - `PlaybackClock.awaitResume` waits in a loop against a deadline, so a spurious wakeup keeps waiting. A timeout of
    0 or less no longer waits forever; every caller passes a positive timeout.
  - `VLCPlayback` keeps its scheduled future and cancels it on shutdown.
  - In the bukkit `FileServerHandler` and `ResourcePackHttpHandler`, header writes close the channel on failure, and
    failed closes are logged.
  - The sandbox has a new `TaskUtils.whenComplete(future, callback)` that logs a failing callback. After a failed
    release, `/mcav image|video release` now logs the failure instead of claiming the player was released.
  - `DitheringArgument`'s shared-instance cache moved into a nested holder, so the enum is immutable. The agent's
    first version indexed an `AtomicReferenceArray` by `ordinal()`, which Error Prone flags as `EnumOrdinal`. It is now
    a `ConcurrentHashMap` keyed by the constant. Reads don't lock; the first creation uses double-checked locking on
    the constant, so each stateless algorithm is still created at most once.
    - `computeIfAbsent` was tried first, but it removed the lock that
      `DitheringArgumentTest.threadsThatRaceForTheFirstUseShareOneStatelessAlgorithm` holds to force the race
      deterministically. The code was changed back rather than the test weakened.
    - `VideoFlagsParser` splits with Guava `Splitter`.
  - In `AbstractInteractiveCommand.releaseIfCurrent`, the agent's `failed.equals(this.player)` failed the Checker
    Framework, because the type variable can be null. It now reads the current player into a local, checks it for
    null, and only then compares it with the failed one; this is still an identity check, since players do not
    override `equals`.
  - `VMProcess` and the e2e `LocalMavenRepositoryServer` bind to the IPv4 loopback literal, parsed with
    `InetAddress.ofLiteral("127.0.0.1")` rather than looked up.
  - Tests: Mockito `doReturn`/`doAnswer` replace direct calls on mocks in the svc and lwjgl tests, and the test
    streams override `read(byte[], int, int)`.
- **Failures found by the full test run after the fixes (2,520 tests on Windows) and their root causes:**
  - `ResourcePackHttpHandlerTest.dropsEverythingThatArrivesAfterThePackWasServed` threw a
    `NullPointerException`. The handler now listens on the future of `context.write(headers)`, but the test's mocked
    context stubbed only `writeAndFlush`, so `write` returned null, which no real Netty channel does. The mock now
    returns a future from `write` as well; no assertion changed.
  - `StandardVideoHologramTest.replacesThePreviousDisplayOnANewRequest` failed with "World unloaded". Bukkit's
    `Location` holds its world through a weak reference, and the test's mocked `World` was only a local variable in
    `@BeforeEach`, so the garbage collector could clear it mid-test. This was a latent flake; the test now keeps the
    world in a field.
  - `DitheringArgumentTest` race test: see the `DitheringArgument` bullet above.
- **Coverage kept at 100%:** a new `YTDLPInstallerTest.installsAStandaloneExecutableAsDownloaded` covers the
  non-zip branch of `YTDLPInstaller.install`. A new `AbstractInstallerTest.treatsDownloadsAsProgramsUnlessAnInstallerSaysOtherwise`
  uses a `PlainInstaller` that overrides nothing, which covers `AbstractInstaller.isArchive()`'s default now that
  every other test installer overrides it. A new
  `AbstractInteractiveCommandTest.leavesAFailedPlayerAloneThatWasAlreadyReleased` covers the null branch of the
  null-safe `releaseIfCurrent` check. It releases the screen while the start is still pending, then fails the start,
  and asserts that the player and its maps are released exactly once and the failure is still reported.

### 14.3 PIT mutation testing on every module

- **Setup:**
  - Plugin: `info.solidsoft.pitest` **1.19.0**, buildSrc dependency
    `info.solidsoft.gradle.pitest:gradle-pitest-plugin:1.19.0`.
  - Versions: PIT **1.30.0**, `pitest-junit5-plugin` **1.2.3**. The junit5 plugin works with JUnit 6.1.3: the tests
    are discovered and run.
  - Options: `targetClasses = me.brandonli.mcav.*`, 4 threads, HTML and XML reports in `build/reports/pitest`, not
    timestamped.
  - JVM: `jvmArgs` is read lazily from each module's own `test` task, and `jvmPath` follows `-Pmcav.testJavaHome`.
    Without the module's JVM arguments, 4 `URLClassLoaderInjectorTest` tests failed under PIT because they need
    mcav-installer's `--add-opens java.base/java.net`.
  - PIT is **not** part of `check`, for build speed; run it with `./gradlew :<module>:pitest`. CONTRIBUTING.md has
    new "Static Analysis" and "Mutation Testing" sections.
- **mcav-installer:**
  - The first run generated 73 mutations and killed 67, a test strength of 92%.
  - Four surviving mutants were real test gaps, so four tests were added: jars already up to date are returned
    unchanged, `close()` stops the copy threads, copy threads are daemon threads, and the JVM's system properties
    reach the resolver session. They use package-private test hooks `createCopyThread`, `copySystemProperties` and
    `isStopped()`.
  - The rerun generated 75 mutations and killed 73, a test strength of **97%**, with 100% line coverage.
  - The two remaining survivors are explained. `RepositorySystem.shutdown()` cannot be observed without injecting the
    repository system, and the `MCAVInstaller` elapsed-time subtraction only feeds a log message.
- **Results of the whole-project run** (`./gradlew pitest --continue`, 2026-09-15; "killed" counts timed-out and
  errored mutants, which PIT treats as detected):

  | Module | Mutants | Killed | Survived | No coverage | Kill rate |
  |---|---|---|---|---|---|
  | mcav-jda | 35 | 35 | 0 | 0 | 100% |
  | mcav-installer | 75 | 73 | 2 | 0 | 97% |
  | mcav-svc | 52 | 50 | 2 | 0 | 96% |
  | mcav-lwjgl | 53 | 50 | 3 | 0 | 94% |
  | sandbox plugin | 847 | 796 | 51 | 0 | 94% |
  | mcav-http | 145 | 135 | 10 | 0 | 93% |
  | mcav-bukkit | 1112 | 1037 | 75 | 0 | 93% |
  | mcav-vm | 279 | 256 | 23 | 0 | 92% |
  | mcav-vnc | 220 | 194 | 26 | 0 | 88% |

  No module has a single mutant without coverage, which matches the 100% line coverage. The 192 survivors are places
  where a test runs the line but would not notice that change; they are listed in each module's
  `build/reports/pitest/index.html`. Killing them is worthwhile follow-up work, not a defect list.
- **Survivor triage, mcav-vnc / vm / http / svc / lwjgl (64 survivors):** 12 mutants killed with 6 edits, all of them
  real test gaps:
  - `AudioListenerTest.startReturnsTheSenderThatStopWakesAndEnds` asserts that `start` returns the named sender thread
    and that `stop` wakes the parked sender so it ends.
  - `ExecutableFinderTest.rejectsInvalidNames` now asserts the message "Name must not be blank". **It had been passing
    for the wrong reason:** on Windows `Path.of(" ")` throws `InvalidPathException`, a subclass of the expected
    `IllegalArgumentException`, so the assertion held even without the precondition.
  - `VMSettingsTest` pins the hash code to `((port * 31 + width) * 31 + height) * 31 + frameRate` and asserts that
    settings differing in one value hash differently, which kills six mutants that dropped a field from the hash.
  - The vnc `EqualityAssertions` helper now also requires unequal hash codes. Collisions are legal in general, so its
    Javadoc states the contract: every "different value" passed in differs in exactly one property, so an equal hash
    code would mean that property is missing from `hashCode`.
  - `VMProcessTest.forgetsTheOutputOfTheAttemptThatFailed` asserts a retry's failure message does not carry the first
    attempt's output.
  - Two `SVCFilterImplTest` tests assert that a `VirtualMachineError` passes through `throwIfFatal` without rolling the
    speaker back.
  - About 25 survivors are genuinely harmless, most of them PIT replacing `return false` with `false`, plus
    timing-only calls (`parkNanos`, `unpark`), redundant defaults verified in the vernacular library's bytecode, and
    GPU-bound lines whose tests skip without hardware.
  - About 8 real gaps were left, all thread-lifecycle mutants (`release`, `stopRenderThread`, `join`, daemon flags,
    `unlock` removals). Killing them needs cross-thread contention assertions with timeouts, which these modules avoid
    on purpose to stay deterministic. One `HttpResult.http:82` boundary survivor is unexplained: ports 0, 1, 65535 and
    65536 are already covered.
- **Survivor triage, sandbox plugin (51 survivors):** 36 mutants killed with 7 new tests. Several were latent bugs, not
  just weak assertions:
  - `DumpUtils` could multiply by a megabyte instead of dividing, and could drop the OS, Java, memory and uptime lines
    of `/mcav dump` entirely, with no test noticing. Covered by `describesTheOperatingSystemAndTheJavaRuntime` and
    `reportsTheMemoryInMegabytesAndTheUptime`.
  - `HelpCommand` could lose every translation lookup and silently show untranslated English; covered by
    `translatesEveryTextOfTheHelp`. `createMessages` became package-private `@VisibleForTesting` for it.
  - `MCAVSandbox.onDisable` could skip stopping the listener, the commands and the audio — a real resource leak;
    covered by `stopsTheListenerTheCommandsAndTheAudioWhenDisabled`.
  - `InteractUtils` wall-column and ray-length arithmetic errors were invisible because the test walls start at
    coordinate 0 and the test look direction was level; three new tests fix both blind spots.
  - `MCAVLoaderTest.removesCachedLibrariesThatAreNoLongerListed` first asserted that Gremlin deletes any unlisted jar,
    which fails: `DependencyCache.cleanup()` is `cleanup(1, ChronoUnit.HOURS)` and only deletes a cached `.jar` whose
    sibling `<jar>.last-used.txt` says it was last used longer ago than that; a jar without that file is always kept.
    The test now writes a two-hour-old `last-used.txt` and asserts the jar and the sidecar are deleted while the
    listed jar stays, which still fails if `cleanup()` is removed.
  - 3 mutants in `findLookedAtPoint` were left: they shift the ray by an exact whole number of blocks, so the pixel
    inside the frame is unchanged for an axis-aligned look.
- **Survivor triage, mcav-bukkit (75 survivors):** 22 mutants killed with 13 tests. The best catches were tests that
  could not fail: `CompressedMapResultTest` released twice, which hid a negated conditional, and `MapLayoutTest` only
  ever tested horizontal splits. Others cover exact bundle boundaries, buffer release on close, and dithering a colour
  no block has. About 40 survivors are harmless (capacity hints, redundant re-checks, an unreachable
  `columns * 128 == 2³¹ − 1`), and ~19 were left: missing `unlock` calls that are only observable as a deadlock, and
  the tile-merge geometry internals, whose fixtures would have to be derived from the algorithm itself.
- **Repairs I had to make to the agents' work:** a missing `org.bukkit.Material` import broke mcav-bukkit's test
  compile; `ServerPackHostingTest` needed `ArrayList`, `List`, `Map`, `Set` and `assertFalse`; a redundant
  magic-number assertion in `VMSettingsTest` was removed; and the `MCAVLoaderTest` rewrite above. After them, all
  seven touched modules are green: mcav-bukkit 325 tests, sandbox 578, mcav-vnc 60, mcav-vm 103, mcav-http 65,
  mcav-svc 32, mcav-lwjgl 16 — zero failures, zero warnings, zero coverage gaps.
- **Measured effect of the triage** (a second full PIT run against the new tests, not the agents' own claims):

  | Module | Survivors before | Survivors after | Kill rate after |
  |---|---|---|---|
  | mcav-svc | 2 | **0** | 100% |
  | sandbox plugin | 51 | **18** | 98% |
  | mcav-bukkit | 75 | **54** | 95% |
  | mcav-vm | 23 | **14** | 95% |
  | mcav-http | 10 | 9 | 94% |
  | mcav-vnc | 26 | 25 | 89% |
  | mcav-lwjgl | 3 | 3 | 94% |

  86 survivors were converted into kills. mcav-lwjgl is unchanged by design: all three of its survivors were
  classified harmless (PIT replacing `return false` with `false`, and a GPU-bound line whose test skips without
  hardware). The survivors that remain are the documented ones: thread-lifecycle and `unlock` mutants that need
  cross-thread contention assertions, tile-merge geometry internals, and float-distance mutants in the colour palette.
- **Second round: the deferred real gaps were killed too.** The owner asked that only genuinely equivalent mutants
  remain, so the clusters the first round had deferred were attacked with deterministic techniques rather than left
  documented: a retained lock is proven by a bounded `tryLock` from another thread inside `assertTimeoutPreemptively`,
  a missing `join` or `interrupt` by a latch the worker counts down plus a bounded join, a daemon flag by asserting on
  the thread the factory creates, and geometry by fixtures derived from observable patch counts rather than from the
  algorithm. Four agents covered vnc/vm, bukkit, sandbox/http/lwjgl and mcav-common, and a fifth took mcav-common's
  remaining dither, filter, player, loader and discovery clusters.
- **Measured mutation state of every module.** Read the "measured on" column before comparing any two
  rows: **a mutation score is a function of which native dependencies the machine has.** Where a module's
  tests skip for a missing dependency, nothing covers that code and almost every mutant survives.

  | Module | Mutants | Killed | Survived | Kill rate | Measured on |
  |---|---|---|---|---|---|
  | mcav-jda | 35 | 35 | 0 | **100%** | Linux 2026-09-17 |
  | mcav-bukkit | 1120 | 1085 | 35 | 97% | Linux 2026-09-17 |
  | mcav-common | 3260 | 3010 | 250 | 92% | Linux 2026-09-17 (see below) |
  | sandbox plugin | 844 | 837 | 7 | 99% | Windows 2026-09-15 |
  | mcav-http | 149 | 146 | 3 | 98% | Windows 2026-09-15 |
  | mcav-installer | 75 | 73 | 2 | 97% | Windows 2026-09-15 |
  | mcav-vm | 279 | 268 | 11 | 96% | Windows 2026-09-15 |
  | mcav-vnc | 222 | 209 | 13 | 94% | Windows 2026-09-15 |
  | mcav-lwjgl | 53 | 1 | 52 | **2%** | Linux 2026-09-17 — **environment, not a regression** |

  `mcav-lwjgl` is the warning example. It scores 96% on a machine with a GPU and 2% here, because all 14
  of its tests skip when GLFW cannot get an OpenGL context, so nothing covers `GLTextureFilter` at all.
  Do not read that row as a defect; re-measure it on a machine with a display.

  **`mcav-common` now includes the media players**, which the earlier runs excluded, and that is where the
  jump from 2580 to 3260 mutants comes from. Splitting the run by package:

  | Package | Mutants | Detected | Rate |
  |---|---|---|---|
  | `media.player.multimedia.*` (was excluded) | 677 | 590 | **87%** |
  | everything else | 2583 | 2420 | 94% |

  So removing the exclusion put **677 mutants under test that were previously not tested at all**, and 87%
  of them are detected — 469 killed outright and 121 more killed on the timeout. The 2583 for the rest
  lines up with the 2580 the earlier Windows run reported, which confirms that figure was the
  non-multimedia total.

  Measure this **serially**. A contended run of the same module on the same code reported only 1679
  mutants, because Gradle's parallelism plus PIT's own threads starved the minions and lost whole batches.
  A number from a loaded machine is not comparable with one from an idle machine.

  **Mutating the players is slow.** A `:mcav-common:pitest` run with them included takes **42m51s** on an
  unloaded 12-core Linux box, because a mutant that breaks playback makes its test *wait*
  rather than fail and so burns the whole timeout. `:mcav-browser:pitest` is worse still: it drives real
  Chrome, and a run spawns dozens of browser processes. If that becomes a problem, the fix is to reuse one
  browser across the tests of `SeleniumPlayerTest` and `PlaywrightPlayerTest`, or to move more of their
  logic behind a seam like `AbstractBrowserPlayerTest` already uses — **not** to shorten the timeout,
  which converts a loaded machine into fake kills, and **not** to exclude the classes again.

  Run heavy modules **one at a time**: `org.gradle.parallel=true` plus PIT's own 4 threads put this box at
  load 25 and the resulting timeouts are CPU starvation, not real detections.

  About **340 survivors were converted into kills** in total. Everything left is in a documented equivalent class:
  PIT replacing a `return false`/`return true` with the same literal, calls that change only latency
  (`unpark`, `parkNanos`), resource releases observable only as a leak, library defaults verified in the dependency's
  bytecode, log-only text and numbers, and a handful of parallel task-splitting mutants whose output is identical.
- **The mutation work exposed four tests that could never have failed**, which is worth more than the percentages:
  - `CompressedMapResultTest` released twice, which hid a negated conditional.
  - `MapLayoutTest` only ever tested horizontal splits, so vertical splitting was unasserted.
  - `ExecutableFinderTest.rejectsInvalidNames` passed for the wrong reason on Windows, where `Path.of(" ")` throws
    `InvalidPathException`, a subclass of the expected exception.
  - The vnc "frame dropped" tests were races: the new colour overwrote the pending frame before the render thread
    could read it, so the stale frame vanished under both the correct code and the mutants.
- **Latent bugs found by mutation testing, not by review:** `/mcav dump` could multiply by a megabyte instead of
  dividing; every `HelpCommand` translation lookup could be dropped, silently showing untranslated English;
  `MCAVSandbox.onDisable` could skip stopping the listener, the commands and the audio; `InteractUtils` wall-column
  and ray-length arithmetic was invisible because the fixtures started at coordinate 0 with a level look; and the
  shared `EqualityAssertions` never checked that unequal values hash differently, so `hashCode` could have returned a
  constant across a dozen value types.
- **mcav-browser: still the slowest module, and still unmeasured.** Its tests drive a real Chrome and Playwright, so
  a mutant that breaks browser startup makes every test in its batch *wait* until PIT's timeout. A 2026-09-17 run
  with the bounded timeout got further than the earlier attempt but was still going after 25 minutes with dozens of
  Chrome processes alive, and was stopped.
  Two things that were suggested earlier should **not** be done: narrowing `targetClasses` to the pure helper
  classes, or excluding the player classes, both of which hide the mutants rather than killing them; and shortening
  `timeoutConstInMillis`, which turns a loaded machine into kills the tests did not earn.
  What would actually work is reusing **one** browser across the cases of `SeleniumPlayerTest` and
  `PlaywrightPlayerTest` instead of starting one per test, or moving more of their logic behind a seam, the way
  `AbstractBrowserPlayerTest` already drives `AbstractBrowserPlayer` through a player that opens no browser at all.
  Neither was done, so this is open work.
- **Running PIT on everything at once pins the machine.** `gradle.properties` sets `org.gradle.parallel=true`, so
  Gradle runs several modules' `pitest` tasks at the same time, each with 4 worker JVMs. On a 14-thread machine that is
  about 20 busy JVMs. It also made mcav-common's PIT fail its "all tests must pass first" pre-check, because the
  real-time VLC test `VLCPlayerIntegrationTest.takesTheAudioFromASeparateSource` was starved of CPU. Run heavy modules
  one at a time.

### 14.4 Final review of every unstaged diff

Before the work was committed, all 926 changed paths were reviewed for deliberateness, first by an automated screen
(`C:\Users\brand\mcav-work\diff-audit.py`) and then by four reviewers reading the diffs module by module.

- **Automated screen:** 0 lost `@Test` methods, 0 lost assertions, 0 new TODO/FIXME, 0 commented-out code. The flags
  it did raise were all explained: 29 whitespace-only diffs are the newline and LF normalisation; the "debug output"
  is a `System.out.println` inside a `<pre><code>` Javadoc example in `HttpResult`; the "possible secret" is a
  path-traversal test fixture; the two binaries are the docs favicon and logo; the five deleted test files are scratch
  classes (the deleted svc `BufferTest` contained **0 `@Test` and 0 assert lines**); and all 56 deleted main files map
  to documented removals — the Fabric mod, the old Netty injector, moved utilities, the QEMU installer, and seven
  sandbox classes.
- **Fixed as a result of the review:** the `javacppPlatform` comment claimed "about 248 MiB" of excluded natives,
  where the measured reduction is 937 → 707 MiB (~230 MiB); and `CompressedMapResultTest.runInAnotherThread` swallowed
  failures of its probe thread, so a regression that threw rather than deadlocked would have passed silently — it now
  captures the throwable and rethrows it, matching `MCPackHostingTest`.
- **Documented as a result of the review:** `AudioListener`'s writer waits uninterruptibly, so `stop()` is the only
  way to end it; `isStuck` depends on `takeNext` setting the clock under the lock; `YTDLPInstaller.install` replaces
  the unpacked folder non-atomically, so a crash in the window costs only a re-download; `MatImageBuffer.exchange`
  recognises shared memory by the pixel start address. The earlier note here claimed a region of interest "starts at
  another address and would be kept as the spare"; that is **backwards**. OpenCV's `Mat(const Mat&, const Rect&)`
  copies the *parent's* `datastart`, so a ROI compares equal and is correctly treated as shared — the described
  hazard does not exist and the code is safer than it was advertised to be; and
  `ColorPalette.perceptualDistance`'s red and blue weights pass each other near the middle without ever being equal.
- **Deliberately not changed:** `DitheringArgument` locks on the enum constant rather than a private object, because
  the deterministic race test holds that monitor to force the interleaving — trading a real test for a theoretical
  hazard would be the wrong bargain. And there are **no CI workflows**: nothing automatically enforces the zero-warning
  policy, the coverage lint or the end-to-end test, which is a genuine gap a future contributor should close.
- **`docs/conf.py` is both generated and Spotless-formatted**, so regenerating it with `jupyter-book config sphinx`
  will fight the formatter over trailing whitespace. Harmless today; worth an exclude if it recurs.

## 15. Appendix — chronological working log

The following is the working draft kept during the session, in order. It contains the detailed reasoning, numbers
and file lists behind every section above.


**Read this appendix as history.** It was written while the work was happening. Words such as "running",
"pending", "in progress" and "open items" describe that moment; every item was finished afterwards, and
sections 1–13 give the final state. In particular, task #9 (exception hierarchy) is complete, the core/media/
clients merges are done, and the only open item is revoking the Discord tokens.

### Build
- Root build.gradle.kts minimal; conventions in buildSrc (mcav.java-conventions, mcav.coverage-lint), typed CoverageLintTask + CoverageReport.
- Test JVM: --enable-native-access, EnableDynamicAgentLoading, -Xshare:off (Mockito CDS warning), --sun-misc-unsafe-memory-access=allow (JOML).
- Node 24.21.0 for prettier-java and the website; npm ci.
- Configuration cache blocked by checker-framework-gradle-plugin 1.0.2 (captures Project in JavaCompile actions; latest version).
- Deprecation warning from paperweight-userdev PaperweightUser.kt:403/408 (upstream).

### Website (mcav-http/mcav-website)
- TypeScript pinned ~6.0.3 and ESLint ^9.39.5 (typescript-eslint < 6.1, eslint-plugin-react supports ESLint 9 only); renovate rules hold them.
- eslint.config.mjs flat config; lint script `eslint src`; page.tsx fixes (stale closures, volume reset, refs, ResizeObserver+DPR canvas, next/image unoptimized, media info dedupe + no overlapping fetches, no setState in effects).

### Utilities moved into mcav-common
- utils.audio.MonoDownmixer (from svc), utils.audio.AudioResampler + SampleFormat (restored, libswresample), utils.ffmpeg.AudioExtractor (from bukkit SoundExtractorUtils), utils.http.NetworkUtils (generic half of bukkit NetworkUtils; bukkit keeps ServerAddress).

### Merged fix agents
- services (installer jar layout/checksum policy, http builder/bind address/stop, jda activity, svc cleanup), bukkit (18 findings), sandbox (16 findings), + core/media/clients (pending).

### E2E
- sandbox/plugin e2eTest source set; -Pmcav.e2e publishes modules to build/e2e-repository; Paper 26.2-123 + SVC 2.6.23 hash-verified.

### Later findings and fixes (this session, after the usage-limit restart)
- AudioProvider logs "The audio web page is available at <url>" when the HTTP server is up; MCAVVoiceChatPlugin (Javadoc, Preconditions) logs "Simple Voice Chat audio is ready". Documented in docs/plugin/tutorial.md and config.md.
- SVCFilterImpl.Speaker.player is @MonotonicNonNull (IntelliJ nullable-setter warning).
- Sandbox tests ImageBlock/ImageEntity/VideoBlock/VideoEntity used worldless Locations; the merged bukkit correctly rejects them, so they now use FakeWorld locations (no assertions changed).
- bukkit renames: ctx -> context and msg -> message in ResourcePackHttpHandler and FileServerHandler; out -> fileOutput in SimpleResourcePack.
- Website package.json and package-lock.json converted to LF; every changed file ends with a newline and has no trailing whitespace (scan of 882 files).
- Javadoc: `./gradlew javadoc` found 124 problems, in mcav-common (KeyUtils errors; BayerDither, IOUtils, HttpStatusException) and in the sandbox (about 100 undocumented members). Agents are fixing them. The first E2E run failed on this because publishing builds Javadoc jars.
- No sudo, runas, pkexec or msiexec in main code; the macOS VLC install uses `hdiutil attach -nobrowse -readonly` into a user folder. No AWT windows or Swing in main code.
- 21 @SuppressWarnings remained (UnsafeInjector, MapUtils, tests); an agent is removing them, and browser test ones come after the clients merge.
- Docs: nested calls in examples split into final locals across 6 pages.

### Core merge (mcav-common core agent, 66 files)
- 28 of 29 findings fixed. Finding 5 (setting org.bytedeco.openblas.load=none) was skipped because OpenCV's native library links OpenBLAS; this is documented in configureJavaCpp and a test pins it.
- New pieces:
  - Installer.findInstallation(), which reuses an existing private VLC install without the network
  - Capability.FACE_DETECTION
  - OS.OTHER, Arch.OTHER and Platform.isKnown()
  - IdleTimeoutInputStream, a 60 s per-read timeout on downloads
  - CommandTask.run(Duration) and runChecked(Duration), which kill the process tree on timeout
  - IOUtils.moveReplacing
  - GitHub AppImage downloads verified against their sha256 digest
  - yt-dlp arguments get `--`, and only absolute http/https URLs are accepted
  - `--playlist-items 1`
- Also changed: an install/release lifecycle lock; MCAVLoadingException and ModuleException now extend RuntimeException; FFmpegCommand.execute() renamed to createTask(); a TemporaryUserHome test helper, so tests never touch ~/.mcav.
- Fixes I made at merge time:
  - AudioExtractor now calls createTask()
  - VMProcess treats OS.OTHER as software acceleration; ExecutableFinder searches only PATH for OS.OTHER
  - the docs/library/ffmpeg.md examples use createTask()
  - I did not take the agent's stale root build.gradle.kts
- Merge base for the media merge saved in C:\Users\brand\mcav-work\common-base (the repo's mcav-common before the core copy).

### Other fixes
- Resize: MapConfiguration.Builder#resize is no longer deprecated. Images have no player, so the option is needed; the Javadoc explains to prefer the player's DimensionAttachableCallback for videos. The 4 remaining test suppressions were removed.
- Spotless: the root targets now also cover *.yaml, gradle-wrapper.properties and the website root json/mjs/ts files. *.bat is excluded, because Spotless writes one line ending everywhere and .gitattributes needs CRLF for batch files. build-docs.bat and publish-artifacts.bat got final CRLF newlines; gradle-wrapper.properties and tsconfig.json were converted to LF.
- Docs: docs/requirements.txt pins jupyter-book>=1.0.4,<2, because an unpinned install gets Jupyter Book 2, which cannot read _config.yml/_toc.yml and lacks the `jupyter-book config sphinx` command that ReadTheDocs runs. An agent is fixing the 17 docs build warnings.
- Gradle's website build (npm ci on Node 24, then next build) succeeds.

### Docs site
- The site is built with Jupyter Book 1 (Sphinx); ReadTheDocs runs `jupyter-book config sphinx docs/` and then Sphinx with docs/conf.py.
- Checked: `jupyter-book build docs --warningiserror` gives zero warnings, both in a fresh venv and with `python -m sphinx -W`.
- docs/requirements.txt: jupyter-book>=1.0.4,<2, matplotlib, numpy, sphinx-design. sphinx-panels, sphinx-tabs, sphinx-inline-tabs, sphinx-proof, sphinx-examples and hoverxref were removed; no page used them, and panels/tabs clashed with sphinx-design.
- _config.yml: myst_heading_anchors 3, logo images/logo.png (the 128x128 mcav wordmark from git history), favicon images/favicon.ico (from the website), copyright 2026, repository.branch master. docs/conf.py was regenerated from it and converted to LF.
- Page fixes: faq.md (heading level, no trailing ---); config.md added to _toc.yml; plugin.md section renamed "Installing the Plugin" and the tutorial link fixed. Pygments treats `module` as a Java keyword, so the `module` variables in bukkit.md, extension.md and voice.md were renamed.

### Sweeps in progress
- IntelliJ and style sweep agents: (1) bukkit and sandbox, (2) installer, http, jda, svc, browser, vnc, vm and lwjgl. mcav-common follows after the media merge.

### Media merge (mcav-common media agent)
- Three-way merge against the saved base: 77 files copied, 7 added (ImagePool, VideoFrameCopier, VideoTimestamps and their tests; RandomDitherImplTest), 0 files changed by both agents.
- The merge script wrongly deleted 9 of this session's utility files: they were in the base but not in the older media copy. All 9 were restored byte-identical; lesson: take the merge base from the agent's starting point, not the current repo.
- Media highlights:
  - pooled frame copies (no per-frame allocation)
  - VLC renders at the attached size
  - seek refused on live sources
  - start opens the new session before stopping the old one
  - per-thread random dither
  - resume() returns false after the end (start replays)
  - OpenCV frames get real timestamps (VideoTimestamps)
  - error-diffusion remainder handling; Atkinson's loss kept
  - filter return values mean "changed" consistently
  - flaky VLC and playback tests fixed at their cause
- GTK findings: the bundled Linux OpenCV build has NO video-file backend (only V4L2 cameras), so VideoPlayer.opencv() cannot open files on Linux; that is unrelated to GTK. Face detection (objdetect/highgui) does link GTK 2 on Linux and is optional (Capability.FACE_DETECTION). CONTRIBUTING.md, the coverage-lint comment and docs/library/player.md were corrected.
- Follow-ups sent to the bukkit+sandbox sweep agent: a fresh TemporalDitherAlgorithm per player (DitheringArgument shared one instance), and an accurate message when resume() returns false.

### Usage-limit interruptions
- Two usage limits interrupted the agents: about 02:40 and about 07:40 on 2026-09-15 (America/New_York). Each time, every agent was resumed with SendMessage so it kept its context; the sweep coordinators resumed their own sub-agents by ID instead of spawning duplicates.
- At the second limit, still running: the core agent (fixing a Windows-only concurrent-download race in HttpDownloader/IOUtils.moveReplacing: two same-target downloads each move their own .part onto the target, and MoveFileEx fails with AccessDeniedException; plan: a Guava Striped<Lock> per target plus a bounded, tested retry), the docs agent (utilities page and README review), and the two IntelliJ/style sweep coordinators with 10 per-module sub-agents.
- E2E run 2 got through publishing every module, then failed at compile because the rsync snapshot caught a sweep agent mid-edit (an import added a moment later). Rerun after the sweeps.

### Windows concurrent-download fix (core agent, after the merge)
- The bug: two downloads of the same target each move their own .part file onto it; on Windows, MoveFileEx fails with AccessDeniedException when the other thread is replacing the file at that moment (POSIX rename never does). It showed up intermittently in HttpDownloaderTest.downloadsTheSameFileConcurrentlyWithoutMixingTheDownloads.
- The fix:
  - HttpDownloader.moveIntoPlace holds a Guava Striped<Lock> (64 stripes, keyed by the normalised target path) during the final move.
  - IOUtils.moveReplacing retries an AccessDeniedException up to 3 times (100 ms, then 200 ms apart); an interrupt raises InterruptedIOException and keeps the flag set. It is tested deterministically through the FileMover seam.
  - Known limitation, documented in the Javadoc: another process replacing the same file at the same moment is not coordinated with.
- Evidence: 30/30 passes with a temporary @RepeatedTest(30), which was removed afterwards; coverageLint green; zero compiler and Javadoc warnings.

### Exception hierarchy (task #9)
- Phase 1, running: the 10 mcav-common AssertionError exceptions become RuntimeException subtypes. mcav's UncheckedIOException gets java.io.UncheckedIOException as its superclass and keeps its name, because bukkit imports it. The AssertionError catches and the thread-boundary `RuntimeException | Error` catches are updated (keep LinkageError, never swallow VirtualMachineError).
- Phase 2, after the sweeps:
  - remove mcav's UncheckedIOException
  - convert the bukkit, installer and http exceptions
  - update the 5 tests asserting AssertionError.class
  - simplify the svc catches
  - make MCAVSandbox.onEnable disable cleanly, with one clear log line, on configuration and startup errors
- Already converted earlier: MCAVLoadingException and ModuleException (core agent), ExecutableNotInPathException (clients agent).

### Sweep decisions (8-module coordinator)
- All sweeps are done, with each module passing its own compile, Javadoc and coverageLint: installer, http with jda, svc with lwjgl, browser, vnc, vm. No IntelliJ WARNING is left in the changed files.
- mcav-installer keeps `Objects.requireNonNull` instead of Guava Preconditions, on purpose: the installer is the zero-dependency bootstrap that plugins shade to download everything else, Guava included, so it must not depend on Guava.
- IntelliJ reports "Could not autowire" in mcav-http MediaController and HttpServerApplication. This is a false positive from its Spring support: the MediaInfo supplier bean is registered in code before the server starts.

### Bukkit and sandbox sweep (208 files)
- Zero compiler and Javadoc warnings; coverageLint green, 100% coverage; IntelliJ clean apart from the excluded warnings.
- Sandbox bugs fixed:
  - DitheringArgument.createAlgorithm() builds a fresh FLOYD_STEINBERG_TEMPORAL per player; stateless constants are shared.
  - /mcav video resume sends mcav.command.video.resume.failed when resume() returns false; MessageTest count is 39.
- Decisions made by me (accepted):
  - PacketUtils: removed the always-false null check, because Paper declares the connection non-null. It also gained
    the join/quit listener registration (`LOWEST`/`MONITOR`), `shutdown()`, `isConnected()` and the seeding of the
    currently online players, none of which this line used to mention.
  - Deleted the unused locale Sender type, and the type parameter it filled.
  - MapUtils rejects a negative map id and a non-positive size; Locale.fromString uppercases with Locale.ROOT.
  - Kept Paper's @ApiStatus.Experimental Position/sendMultiBlockChange in BlockRenderer: the stable route (BlockState#copy, then sendBlockChanges) allocates a BlockState per changed block per frame, which costs efficiency, and 26.2 is the only supported version. Also kept: PluginLoader in MCAVLoader (the only way for a Paper plugin to load libraries), internal LibraryStore in MCAVLoaderTest, and the Server#getLogger stub in TestServer.
- Mockito stubbing chains (when/thenReturn, verify(...)) are kept in tests for readability.

### Exception phase 1 result (mcav-common)
- New supertypes, each justified in its Javadoc:
  - PlayerException, ProcessException, YTDLPParseException (deliberately not IllegalArgumentException, because SourceUtils multi-catches that type), NoMatchingFormatException, InputMetadataException and ZipEntryIntegrityException extend RuntimeException.
  - NativeLoadingException and PaletteLoadingException extend IllegalStateException.
  - UnsupportedOperatingSystemException extends UnsupportedOperationException.
- New public me.brandonli.mcav.utils.ThrowableUtils.throwIfFatal(Throwable) rethrows VirtualMachineError. Thread boundaries that still catch `RuntimeException | Error` from user code call it first (ModuleLoader, AudioRenderer, VideoRenderer, PlaybackSession), each with a comment saying why.
- DependencyLoader: installVLC catches IOException | RuntimeException | LinkageError; installYTDLP catches IOException | RuntimeException. WinInstallationStrategy catches java.io.UncheckedIOException | ZipEntryIntegrityException.
- Tests per exception check supertype, message and cause. VlcInstallerFailures test support was added.
- Whole project compiles, zero warnings, Javadoc clean, coverage 100%.
- Phase 2 and the mcav-common media sweep are running in parallel on disjoint files. The rest-of-common sweep follows phase 2.

### 8-module sweep result (installer, http, jda, svc, browser, vnc, vm, lwjgl)
- Final combined build with --rerun-tasks: 32 compile, Javadoc and coverageLint tasks, BUILD SUCCESSFUL, zero warnings.
- Tests, with 0 skipped, failed or errored: installer 56, http 62, jda 22, svc 29, browser 131 (Chrome and Playwright ran), vnc 60, vm 100 (including a real QEMU VM), lwjgl 16 (real OpenGL).
- The flaky vnc test's root cause: JavaCV loading the OpenCV natives the first time counted against the first frame's 10 s timeout. Fixed with a @BeforeAll warm-up and by releasing each player before its server; no assertion changed. The test went from 6.8 s to 0.08 s.
- Notable changes:
  - mcav-http: a new test/Await helper replaces sleep polling.
  - browser: PlaywrightPlayer.returnToOpenPage now reacts only to the followed page closing.
  - vm: static factories replace constructors that made calls inside this(...); a new OS.OTHER test.
  - svc: MonoDownmixExample waits for Enter and closes its resources.
  - Unused test helpers deleted in lwjgl and vm.
- Kept: IntelliJ's Spring "Could not autowire" false positive in mcav-http; Mockito stubbing chains; installer Objects.requireNonNull (zero-dependency bootstrap).

### Third usage-limit interruption (about 08:47, reset 12:40 on 2026-09-15)
- Interrupted: exception phase 2 (it had one step left, removing two leftover imports of the old UncheckedIOException, then its verification), the mcav-common media sweep coordinator and its two sub-agents (a609c5a13e378a81e for the multimedia root, cv and image player; a35be38b67f4dbe2a), and the utils/json/module sweep. All were resumed at 12:41 with SendMessage; the media coordinator resumes its own sub-agents by ID.
- New explicit user instruction: run `./gradlew spotlessApply` once absolutely everything is finished (task #13). Then check spotlessCheck and compile, normalize the git index, and write the Desktop handover.

### mcav-common utils/json/module sweep result (95 files)
- Checks: zero compiler and Javadoc warnings; coverageLint green (4th attempt, after three runs broken by other agents' builds); IntelliJ clean apart from 4 kept warnings.
- Changes:
  - The MonoDownmixer and AudioResampler examples now return false.
  - HttpDownloader: `in`, `out` and `buffer` renamed to `body`, `partOutput` and `chunk`; its lock and retry are untouched.
  - HttpStatusException uses Java 25 statements before super(...).
  - ByteUtils uses a pattern switch.
  - The yt-dlp JSON model field docs (Format, URLParseDump, DownloaderOptions and the rest) are now real descriptions.
  - Example mains use Java 25 `static void main()` and log through SLF4J.
  - Many test fixes: try-with-resources, `_`, calls moved into locals.
- Under review: FFmpegCommand builder methods were changed to return void, a public API break made only to silence "return value never used". Preferred: keep them fluent (return this), consistent with the other builders, and add tests asserting each returns the same builder.
- The kept polling loops in MCAVTest (thread BLOCKED) and HttpDownloaderTest (a request reaching LocalHttpServer) go to the last slice.
- Performance note: IdleTimeoutInputStream is package-private; HttpDownloader reads 64 KiB at a time through it, so starting a task per read is cheap.

### mcav-common media sweep result (about 110 files)
- Checks: compile and Javadoc 0 warnings; coverageLint green, 1051 tests, 0 failures, no gaps (the 4 skips are the Windows symlink tests in the installer package); IntelliJ clean.
- Fixes:
  - a native temporary-buffer leak in BlendFilter and OverlayImageFilter, via the new MatVideoFilter.copyToMat
  - AudioFilter and VideoFilter NO_OP are now `(_, _) -> false`
  - steps reject null samples and metadata
  - FaceDetectionFilter links Capability#FACE_DETECTION
  - VideoRenderer.recyclePixels reports whether it kept the array
  - long main methods split
  - new test helpers media/Polling (replacing about 15 sleep loops) and media/ResourceAssertions
- Kept: calls inside super()/this() in MapPalette and VLCPlayer (now being fixed by the perf agent); unchecked per-pixel getters in DiffusionKernel (hot path); Mockito chains.
- The performance findings led to a dedicated pipeline performance agent (running): VideoFrameCopier resizes from the decoder buffer; MatVideoFilter writes back without BufferedImage; reuse of audio ByteBuffers and native Mats (thread-safe); TintFilter cache; TemporalDitherAlgorithm clone; ImageSupplier direct raster reads; RepeatingFrameSource shared-pixel contract. The VLC native double copy changes only if the thread hand-off is provably safe. ImageBuffer.getPixels needs a read-only/shared Javadoc in the last slice.

### Exception phase 2 result
- me.brandonli.mcav.utils.UncheckedIOException is deleted. Its users (IOUtils, AbstractInstaller, ImageBuffer, MapPaletteLoader, bukkit BlockPaletteLookup and SimpleResourcePack, their tests, docs/library/utilities.md) use java.io.UncheckedIOException, with real IOException causes where there used to be only a message: BindException ("no free port"), FileNotFoundException (missing resource), IOException (empty block palette).
- New superclasses, each justified in its Javadoc: bukkit InjectorException is an IllegalStateException; MCPacksException, HttpServerException, http HttpException and InstallationException are RuntimeExceptions.
- InstallationError is renamed InstallationException everywhere, including docs/library/installer.md.
- The 5 old-hierarchy tests now assert the new type, each split into message, cause and supertype tests.
- Catches:
  - SVCFilterImpl keeps catching Error, because Simple Voice Chat loads the native Opus codec, but calls throwIfFatal first; new tests for UnsatisfiedLinkError rollback and InternalError pass-through.
  - Browser deliverFrame and VNCPlayerImpl.deliver use throwIfFatal.
  - PlaywrightPlayer.runSession reports the failure to the waiting start first (so start fails at once), then rethrows fatal errors.
  - UnsafeInjector.readField rethrows VirtualMachineError before wrapping.
- MCAVSandbox.onEnable catches UnsupportedServerVersionException and the new MissingVoiceChatException (an IllegalStateException thrown by AudioProvider.initialize), logs "MCAV cannot be enabled: <reason> (<what to do>)", and calls PluginManager.disablePlugin; onDisable releases what was created, and other exceptions propagate. Three new MCAVSandboxTest tests.
- `grep "extends AssertionError"` over every src folder returns nothing.

### Follow-up and last mcav-common slice
- FFmpegCommand: all 12 add* methods return the builder again (the library's convention); FFmpegCommandTest.everyBuilderMethodReturnsTheSameBuilder asserts it.
- Test polling: MCAVTest.awaitBlocked spins with Thread.onSpinWait() against a deadline; testing/LocalHttpServer.awaitRequests(path, count, timeout) is a real signal, used by HttpDownloaderTest and AbstractInstallerTest; VLCLoadStateTest uses onSpinWait. No Thread.sleep polling loops remain in mcav-common.
- ImageBuffer pixel contract: getPixels() is documented as shared and read-only (it is replaced, never updated, when the image changes). New default copyPixels() returns a modifiable copy; getReadOnlyPixels() returns a zero-copy read-only IntBuffer that throws ReadOnlyBufferException on writes. New ImageBufferTest (6 tests). No existing consumer writes into the shared array.
- Last slice (the files directly in utils/, capability/installer/**, loader/**, testing/**):
  - statements before this()/super() in the installer and discovery classes
  - short names renamed (os, in, out, arch, src, dest, clazz)
  - null checks on public entry points
  - long methods and tests split
  - ReleasePackageManager uses toArray(Download[]::new), which fixed a Checker error
  - TemporaryUserHome.getHome() removed the try-with-resources lint conflicts
  - testing/Images.encode fails when ImageIO has no writer
- All green: zero compiler and Javadoc warnings, coverageLint passing (tests really ran), IntelliJ clean apart from the exclusions.

### Docs verification against the final API
- A docs agent checked every Java example and API claim in docs/ and README.md against the source and fixed:
  - yt-dlp.md: yt-dlp failures throw the unchecked YTDLPParseException; IOException is only for installing, running or timing out, and IllegalArgumentException is for non-http(s) URLs. It now has an exception table and PREFER_MP4_VIDEO.
  - bukkit/resourcepack.md: the example would have thrown, because zip() needs meta() first. The examples are now valid methods taking packFormat and paths, and the page points to https://minecraft.wiki/w/Pack_format, because nothing in the code derives the format.
  - plugin/config.md and plugin.md: the exact one-line self-disable messages for a missing voicechat plugin and a non-26.2 server.
  - pipeline.md and examples.md: read-only filters return false, and the return contract is explained.
  - image.md: a "Reading Pixels" section (getPixels shared and read-only, copyPixels, getReadOnlyPixels, updateArgb).
  - player.md: resume() returns false after the end; start replays; RepeatingFrameSource shares its arrays.
  - ffmpeg.md: createTask, run vs runChecked, timeouts, fluent builder.
  - utilities.md: ThrowableUtils.
  - instance.md: FACE_DETECTION.
  - commands.md: argument names match the @Command annotations.
- Running: replacing the 15 remaining `final X y = ...;` placeholders in docs examples with valid code (method parameters or real constructions).
- The docs build passes with --warningiserror.

### Pipeline performance result (the last code agent)
- Verified with no other builds running: compile and Javadoc have 0 warnings; the VLCPlayer, VLCPlayerIntegration, DrawingFilters and FrameFormatCallback tests pass in isolation (69 tests, 0 failed; the 72 earlier failures were build collisions); the timing classes pass in isolation (48); the full media suite passes (56 classes, 502 tests); coverageLint has 1090 tests, 0 failed, 100% coverage; IntelliJ clean.
- Fixed:
  - VideoFrameCopier resizes straight from decoder memory: one full-frame copy fewer, and no spare unscaled image.
  - MatVideoFilter on non-OpenCV frames uses raw BGR getData/updateData: one copy fewer, with no BufferedImage and no temporary array.
  - TintFilter caches its tint Mat per size and type.
  - GrayscaleFilter reuses a Mat through the new ReusableMat helper: it lends the Mat to one frame at a time, a concurrent caller gets its own Mat, and the Mat is freed when the filter is garbage-collected.
  - FaceDetectionFilter reuses its grey Mat and detection list and is synchronized, because the OpenCV cascade classifier is not thread-safe.
  - Crop, Resize, Rotation, Transpose and Bilateral no longer allocate native memory per frame at a steady size: each image keeps a spare Mat, swapped in through the new MatImageBuffer.transformMat, with setSize added.
  - TemporalDitherAlgorithm reuses the previous index array.
  - ImageSupplier reads rows in bulk for INT_ARGB/INT_RGB images through raster.getDataElements (safe for sub-images, keeps acceleration), with getRGB as the fallback.
  - copyToBgr skips a copy for 3-channel images of other depths.
  - RepeatingFrameSource, RepeatingFrameSourceImpl and SampleSupplier.getFrameSamples document the shared read-only pixels and link to the ImageBuffer contract.
  - MapPalette and VLCPlayer compute values before super()/this().
  - 20 long test methods split.
- Deterministic timing: PlaybackSession takes its audio lead, late-frame threshold and nanosecond clock through the constructor (players pass 50 ms, 100 ms and System::nanoTime, unchanged); there is a test-only AbstractVideoPlayerCV.neverDropLateFrames(); dropsFramesThatAreTooLate uses a fake clock. The one remaining wall-clock assertion: FFmpegPlayerTest requires a playback rate above 24 fps (fails only if the last frame is more than about 1.2 s late).
- Kept, with reasons:
  - per-chunk audio ByteBuffers: the AudioFilter contract lets filters keep the buffer, so reuse could corrupt a user's audio; the cost is about 190 KB of short-lived heap per second
  - the VLC native-to-int[] copy: delivering into pooled Mats risks a late display() callback writing freed memory during stop
  - RepeatingFrameSource is documented rather than cloned per frame
- Public API additions: MatImageBuffer.transformMat and setSize; ImageBuffer.copyPixels and getReadOnlyPixels. Building a pipeline is unchanged.

### Final pass (in progress)
- `./gradlew --stop`, then final-verify.sh run 1: log C:\Users\brand\mcav-work\final-verify-run1.log, per-check logs in C:\Users\brand\mcav-work\final-verify\.
- E2E run 3 in WSL: log C:\Users\brand\mcav-work\e2e-run3.log.

### E2E finding (run 3, the first run that reached the server)
- The Paper 26.2 server started (Done in 234 s on a cold cache) and Simple Voice Chat started, but MCAV failed to load: gremlin reported "Exception(s) resolving dependencies" for every mcav module (timestamped local snapshots such as me.brandonli:mcav-browser:1.0.0-20260915.181542-1).
- Root causes (confirmed with a probe script):
  1. gremlin-gradle writes only http(s) repositories into dependencies.txt `__repos__`, so the local build/e2e-repository folder was never listed, and the runtime looked for local snapshot versions in public repositories.
  2. gremlin-runtime downloads with java.net.http.HttpClient, which cannot read file: URIs, so listing a folder would not work anyway.
- Also: "[ERROR]: No key layers in MapLike[{}]" came from the test's flat world having no generator-settings; it is not an mcav error.
- Fix (agent running): the test serves build/e2e-repository over HTTP on 127.0.0.1:<free port> (LocalMavenRepositoryServer); the build adds that http repository, content-filtered to me.brandonli and ordered after the folder repository, so gremlin writes it while Gradle still resolves from the folder; port and folder reach the test as system properties; the flat world gets real layers.
- Not a production bug: production plugins resolve from https repositories (repo.brandonli.me/snapshots).
- Helpers: e2e-result.sh, e2e-server-output.py, e2e-gremlin-probe.sh and e2e-diagnose.sh in C:\Users\brand\mcav-work, run with `MSYS_NO_PATHCONV=1 wsl -d Ubuntu-22.04 -- ...`.
- The full headless Linux test run works in a separate copy, ~/mcav-linux-tests (linux-headless.sh reads MCAV_LINUX_TARGET and MCAV_LINUX_LOG).

### Headless Linux test run (WSL, no sudo, no display)
- 2437 tests across 11 modules; 47 environment skips (Chrome, QEMU, VLC, GPU/GLFW, sound device, GTK 2, Windows-only paths, no OpenCV file backend on Linux); no javac warnings.
- One failure, a test bug: VLCInstallerTest.neverFollowsLinksWhileDeletingOldInstallations never created kept.txt (its assertion could never pass; hidden on Windows, where the symlink tests skip). Fixed by writing the file; the code under test was correct.
- coverageLint gaps on Linux (common 55, browser 59, vm 5, lwjgl 113 lines) are exactly the skipped hardware/software paths. check enforces coverage only with -Pmcav.coverage on a fully equipped machine (this Windows box has VLC, Chrome, QEMU, a GPU and audio), as CONTRIBUTING.md documents.

### Final verification run 1 (Windows) and diff audit
- final-verify.sh run 1 is all green:
  - spotlessCheck: 0 violations
  - forced recompile of every source set: 0 warnings
  - javadoc: 0 warnings
  - coverageLint: every module, 0 gaps, 0 failures
  - docs build with --warningiserror
  - website: eslint, tsc and next build
  - scans: 0 @SuppressWarnings, 0 records, 0 {@inheritDoc}, 0 AssertionError subclasses
  - 0 files missing a final newline, 0 CRLF outside .bat
  - one untracked file (ReusableMatTest), since marked intent-to-add
- Diff audit (C:\Users\brand\mcav-work\diff-audit.py over 929 changed paths):
  - no test file lost an @Test or an assert call
  - 30 whitespace-only diffs are the newline and LF fixes the user asked for
  - the flagged "debug output" is a Javadoc example, the "secret" is a test fixture, and the images are the docs logo and favicon
  - 13 deleted test files: moved (NetworkUtilsTest, SoundExtractorUtilsTest, MonoDownmixerTest), unused helpers (EqualityAssertions, UtilityClassAssertions), replaced (svc BufferTest), and scratch mains in mcav-common (FFmpegSandbox, SandboxMain, SingleCombinedInputExample, TestHack, YTDLPTest, which had a main and no JUnit test)
- Deleted main files (breaking changes to list):
  - the Fabric mod sandbox/mod/**
  - the old Netty injector (ByteBuddyBukkitInjector, HttpInjector, HttpByteBuf, HttpRequest, Injector, InjectorContext, InjectorHandler, ReflectBukkitInjector, ResourcePackInjector), replaced by ResourcePackHttpHandler/NettyHosting
  - bukkit LocationData; ServerVersion, replaced by ServerEnvironment (26.2 only)
  - bukkit NetworkUtils and SoundExtractorUtils, moved to common
  - vm QemuInstaller plus installers/qemu.json, patch-elf and pget (QEMU must be pre-installed; no admin installs)
  - installer JarEntryIntegrityException (jars are compared byte for byte)
  - common VideoPlayerCV and FramePacket (replaced by AbstractVideoPlayerCV and PlaybackSession), JNACallbackPin, PhantomDebug, CLibrary, PackageInstaller, FFmpegLogger
  - the dither InvalidErrorDiffusionAlgorithmException, MurmurHash3, Xoroshiro128PlusRandom and LoadRed/Green/Blue
  - the utils CollectionUtils, CopyUtils, CriticalTaskException, LockUtils, QuadConsumer, TriConsumer, TriFunction, ReflectionUtils, NativeUtils, UnsafeProvider, UnsafeUtils and the Examinable family
  - browser ChromeDriverServiceProvider, PlaywrightServiceProvider and InvalidMouseClickArgument
  - sandbox ConfigurationManager (replaced by PluginDataConfigurationMapper), JsonUtils, SkullUtils plus heads.json, VideoArgument, DebugFilter and MutableInt
- AudioResampler shows as DA only because of a leftover index state; the new file exists. Fixed by the final index normalization.
- A test-run log archive (mcav-bukkit/logs/2026-09-14-1.log.gz) had been staged as a new file, probably by an IDE auto-add. It was removed from the index and deleted; logs/ is ignored.
- Problem found after run 1: the E2E agent's sandbox/plugin/build.gradle.kts used `java.net.ServerSocket` inside the script, where `java` is Gradle's extension, so every build failed to configure. The agent was told the fix (import java.net.ServerSocket). Rerun after: the Linux VLCInstallerTest rerun, the sandbox checks, and final-verify.

### E2E run 4: gremlin fix works, and a real startup bug found
- With the local repository served over HTTP (gremlin `repos` property set to http://127.0.0.1:<port>/ followed by the public ones; LocalMavenRepositoryServer in the e2eTest sources; `import java.net.ServerSocket` in the sandbox build), gremlin downloaded everything and MCAV loaded on headless Linux: the modules started, "JavaCV natives loaded in 1580 ms", yt-dlp and ChromeDriver ready, the map colour table built. Expected warnings: no QEMU; face detection unavailable (no GTK 2).
- REAL BUG (thread dump): Paper's Server thread parks in CompletableFuture.join() at MCAV.runInstallation(MCAV.java:113), called from MCAVSandbox.onEnable, while a worker downloads the VLC AppImage (VLC-media-player_3.0.21-29-jre8-with-plugins-archimage4.3-x86_64.AppImage, about 116 KB/s) into ~/.mcav/cache. A fresh VLC-less server never finishes starting until VLC is downloaded and extracted. A fix agent is making install() return once the library is usable (natives, modules, tables), install VLC/yt-dlp in the background with a capability-ready future on MCAVApi, give vlc() a clear "still being prepared" error, cancel installs on release, and make the sandbox commands say VLC is pending.
- Download size (C:\Users\brand\mcav-work\download-size.py): 937 MiB in 245 artifacts per fresh server. By classifier (MiB): code 285.8 (Playwright driver-bundle 192.9); windows-x86_64 121.6; linux-x86_64 86.7; macosx-x86_64 77.4; linux-arm64 72.2; android-x86_64 63.6; android-arm64 57.6; macosx-arm64 56.0; ios-x86_64 40.4; ios-arm64 39.6; linux-x86 9.2; windows-x86 8.9; linux-armhf 8.3.
- Planned (task #14): apply org.bytedeco.gradle-javacpp-platform 1.5.10 to the sandbox with javacppPlatform = linux-x86_64,linux-arm64,macosx-x86_64,macosx-arm64,windows-x86_64 (it filters the dependencies of every "-platform" artifact through a metadata rule, so it works with 1.5.14), dropping about 227 MiB of Android, iOS and 32-bit natives. mcav-common keeps javacv-platform, so library users choose their own platforms.
- The gremlin downloads ran at about 1.3 MB/s; the E2E agent is adding a gremlin library cache in build/e2e-cache so reruns are fast.

### E2E agent final state (waiting for the startup fix before the last run)
- The build sets gremlin's writeDependencies `repos` directly: http://127.0.0.1:<port>/ first, then the project's http(s) repositories, so Gradle never sees the port. The port comes from -Pmcav.e2e.repositoryPort or a free port picked at configuration time. The e2eTest task gets mcav.e2e.repositoryDirectory and mcav.e2e.repositoryPort.
- New e2eTest classes:
  - LocalMavenRepositoryServer serves GET and HEAD on 127.0.0.1, answers 404 for missing files or paths outside the folder and 405 for other methods, and is closed after Paper stops. The test asserts the server downloaded at least one file from it.
  - LibraryCache moves build/e2e-cache/libraries into the server's libraries/mcav before the start and back after the stop (a rename, with a copy as fallback). gremlin still verifies sha256.
- The flat world has generator-settings (bedrock, dirt, grass_block, plains), so the "No key layers" ERROR is gone.
- Platform filter applied: dependencies.txt went from 245 to 225 artifacts and from 937.0 to 707.0 MiB.
- The last run before these changes had no ERROR lines; it timed out only on the VLC startup hang. The expected WARN: face detection unavailable (no jniopencv_highgui, because GTK 2 is missing).
- Helper scripts in C:\Users\brand\mcav-work: e2e-wait-deps.sh, e2e-watch-log.sh, e2e-wait-end.sh, e2e-platform-check.sh, e2e-live.sh, e2e-hang.sh, e2e-stall.sh, download-size.py.

### Open items
- Exception hierarchy (18 AssertionError subclasses) — task #9.
- Discord tokens in git history must be revoked.
