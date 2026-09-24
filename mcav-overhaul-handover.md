# mcav rewrite — current handover after pass 2

This document describes the branch state, not a chronological session log. Detailed commands, per-file verdicts,
assertion classifications, sources, mutation identities and unresolved findings are in
`/home/dev/mcav-pass2-report.md`; resumable progress is `/home/dev/mcav-pass2-progress.md`, with evidence under
`/home/dev/mcav-pass2-state`. Pass 2 began at `0a2baf3f37e1ea1b6de571e9ac99db0760148140` against `master`.
Historical Windows/WSL results are not revalidation of this tree.

## Scope and workflow

mcav is a Java 25 media library with eleven Java modules: common, installer, HTTP, JDA, SVC, Bukkit, browser,
VNC, VM, LWJGL and `sandbox:plugin`. The sandbox targets Paper 26.2; the Fabric sandbox was removed.
The HTTP page is Next.js/React. Documentation is Jupyter Book/Sphinx.

The review's authorized Finish includes logical commits and a normal push to `origin rewrite`, superseding the
old no-commit instruction. Never modify master, rebase or force-push. Preserve the pre-existing `gradlew.bat`
line-ending change. For subsequent work follow the owner's current instructions over historical handovers.

House rules: descriptive names, normal classes rather than records, no suppressions or bare inheritDoc, explicit
locals rather than call chains, short cohesive methods, detailed public contracts, compiler warnings fatal.
Installer avoids Guava because it bootstraps library dependencies. 100% reachable line/branch coverage is required;
mutation assurance is separate and remains incomplete. The large-method/class style goal is not universally met.

## Verification environment and commands

Pass 2 ran on Linux with GraalVM 25, native VLC/plugins, QEMU, GTK2, Chrome/Playwright and software OpenGL via the
owner's Xvfb `:99`. `/dev/snd` is present and a real audio-line test passed; this does not demonstrate audible
fidelity. Xvfb is environment-owned: never stop/restart it. Preserve Windows assumptions. Two pre-existing OpenCV
file-player skips reflect the bundled Linux build lacking the FFMPEG backend and are assigned to pass 4.
Windows/macOS/big-endian behavior of this final tree is untested.

Use `./gradlew`; serialize Gradle/PIT commands, cap each at 900 seconds, write background output to logs and check
results no more often than every 10 minutes. Do not edit sources during compilation or snapshot freezing. Run one
Codex worker; no parallel Codex delegation. Only terminate recorded owned PIDs after identity verification.

- Ordinary verification: `DISPLAY=:99 ./gradlew test coverageLint --continue`.
- Build/style: `./gradlew spotlessApply` last, then `./gradlew build`.
- API/source sets: `./gradlew javadoc :sandbox:plugin:compileE2eTestJava`.
- Website: `./gradlew :mcav-http:buildWebsite :mcav-http:jar`; confirm `static/index.html` in the jar.
- Network opt-in tests: `-Pmcav.networkTests=true`; do not describe ordinary skips as executed downloads.
- Tests can use `-Pmcav.testJavaHome=<Java 25 home>` when native loading needs a different JDK.
- Coverage enforcement in `check` is opt-in with `-Pmcav.coverage`; per-task filtered tests skip only their own
  coverage verification. The build no longer rewrites source through an unordered spotlessApply dependency.

No in-repo CI workflow exists. Renovate automerge is configured; branch protection is unverified. Installer
updates are disabled in Renovate because relocation changes need review, including security updates. No IDE
inspection run was available. Static text scans are not equivalent to a complete IDE audit.

## Pass 2 changes

- BuildSrc: -Werror, scoped test-filter coverage detection, XML/exception and cross-module TestKit regressions.
- Common: correct raster offsets/strides and image cache/lifetime behavior; temporal still-image threshold/strength;
  wide diffusion products; exclusive finite RNG ranges; native/filter numeric and ownership oracles; async default
  contracts; image-worker failure/interruption handling; CV/VLC startup, cancellation and cleanup ownership;
  no lock held around CV acquisition/stop; saturating seek; signed timestamps; deterministic FPS test clock.
- Native installation/I/O: validate VLC companion libraries/nonempty files, improve extraction cleanup, enforce
  cumulative ZIP limits and reject symlink escapes; preserve underlying fatal/unchecked stream failures; close
  failed HTTP bodies; bound command completion and process output/drain lifetimes.
- Bukkit: long-safe map geometry and patch coordinates; protocol limit of 4,096 packets per bundle cap with honest split semantics;
  synchronize compressed-map release clearing with restart; viewer removal restores scoreboards/hides entities;
  shutdown-safe main-thread rendering; stronger resource-pack paths, per-instance HTTP routing and IPv6 handling.
- Browser: per-class browser sharing with state reset and destructive tests isolated; bounded input/work queues;
  cleanup on failed startup/driver loss; popup/detached-frame and synchronous CDP disconnect handling. Real crash
  ordinary tests pass at the recorded snapshot; mutation completion remains open.
- VM/VNC/LWJGL/SVC: preserve process ownership after failed termination, bound drained output, monotonic startup
  deadline, clamp large coordinates before narrowing, synchronize VNC pause/frame publication, retain GL upload
  dimensions, aggregate voice resource cleanup. Audio bytes remain JDA big-endian, HTTP little-endian and SVC mono.
- Sandbox: own resources through asynchronous preparation, reject stale successful starts, safely release pending
  images/video results on disable, aggregate shutdown failures, repair help navigation, parse repeated QEMU options,
  protect translated URL components, redact space-separated secret options and bound log-tail memory; screen UUID
  identity and map-ID range validation; quoted/unquoted image MRL support and blank-source rejection.
- Council followups: reject unsafe root installer artifact IDs before directory creation; reject/close native empty
  cascade classifiers; stop pre-start HTTP listeners; share pending website metadata requests; correct misleading
  resume failure text; build website assets as a jar prerequisite with managed Node and complete input tracking.
  npm installs track their generated lockfile receipt to avoid hashing the 70,000-file dependency tree. Specific tests and final measurements are in the report.
- Documentation/examples: asynchronous readiness, metadata before resource work, Swing EDT ownership, release bounds,
  optional natives, source compatibility, resize semantics and licensing links corrected; unsupported visual error
  percentages removed. API migration changes below are not binary compatibility guarantees.

## Coverage and mutation evidence

Final post-cherry build and coverageLint passed. JUnit reports 3,160 tests, 8 skips and 0 failures/errors across eleven modules. Results reused by Gradle remain identified by their original timestamps in `sept24-post-cherry-validation/counts.json`. Sandbox retains its one declared Paper-only constructor line.

| Module | Tests reported | Skips | Covered / total lines | Covered / total branches |
|---|---:|---:|---:|---:|
| mcav-browser | 177 | 0 | 1158 / 1158 | 312 / 312 |
| mcav-bukkit | 390 | 0 | 2532 / 2532 | 718 / 718 |
| mcav-common | 1392 | 8 | 6823 / 6823 | 1962 / 1962 |
| mcav-http | 76 | 0 | 348 / 348 | 80 / 80 |
| mcav-installer | 75 | 0 | 384 / 384 | 52 / 52 |
| mcav-jda | 22 | 0 | 83 / 83 | 18 / 18 |
| mcav-lwjgl | 22 | 0 | 118 / 118 | 24 / 24 |
| mcav-svc | 37 | 0 | 144 / 144 | 42 / 42 |
| mcav-vm | 129 | 0 | 666 / 666 | 246 / 246 |
| mcav-vnc | 85 | 0 | 424 / 424 | 133 / 133 |
| sandbox/plugin | 755 | 0 | 3051 / 3052 | 754 / 754 |

Final formatter: 83.89 seconds. Combined build/coverage/Javadoc/E2E compilation: 146.11 seconds. No compiler warnings.
An upstream Gradle 10 deprecation remains in paperweight-userdev 2.0.0-beta.23 (PaperweightUser.kt:403/408), traced
in `sept24-finish-build3.log`. Both normal and source HTTP jars contain `static/index.html` and 24 static files
(31 entries including directories). ESLint passed separately. The final isolated Jupyter Book build passed with
warnings as errors and 34 source pages, including the manual checklist.

Separate final class scopes: FaceDetectionFilter 14/14 KILLED; image commands 56/56 KILLED;
InstallationManager 34 = 32 KILLED + 2 TIMED_OUT; HttpResultImpl 50 = 46 KILLED + 4 TIMED_OUT;
VNCPlayerImpl 158 = 138 KILLED + 7 SURVIVED + 13 TIMED_OUT. Initial exact-class attempts that accidentally inherited
production names as test selectors are retained as failed discovery attempts, never assurance.

After Claude integration, MapUtils* measured 73 = 68 KILLED + 5 TIMED_OUT. All three mutations in the new frame-cleanup
path were killed. Five timeout identities in `buildMapScreen` and `createMapsUpTo` remain open; see
`sept24-post-cherry-map-dispositions.json`. This scoped result does not replace the full sandbox baseline.
The final commands, counts, source hashes and logs are under `/home/dev/mcav-pass2-state/sept24-post-cherry-*`.

Independent full common baseline: 3,556 = 2,982 KILLED + 296 SURVIVED + 192 TIMED_OUT + 78 NO_COVERAGE + 8 RUN_ERROR,
snapshot 5b48cea3ca44c998f0f6160fc4c0b89d5fafbb04d8d6a62c47976f3488021010.
Targeted 36-class followup: 1,604 = 1,325 KILLED + 151 TIMED_OUT + 120 SURVIVED + 8 RUN_ERROR, zero NO_COVERAGE,
snapshot ed48e19165da4f4ae19923ae431eb6863617cbe14fc6506123922cf6a37ee1e1.
VideoFrameCopier followup: 50 = 48 KILLED + 2 SURVIVED,
snapshot 651e6ba2fee532174666fdd7771e42520dd8e5961480f0a2bb4ef179e157cf2b.
These are distinct scopes, not a synthetic full-module score. All unresolved measured IDs have individual
classifications. Seven matching minion crash logs show SIGSEGV in libswresample; exact mutant mapping and an eighth
crash log are missing. All eight remain RUN_ERROR, never assertion kills.

Completed other baseline scopes (K/S/T): Bukkit 1,183 = 1,146/24/13; sandbox 1,095 = 1,064/10/21; VNC 232 = 207/9/16;
VM 310 = 300/7/3; HTTP 153 = 126/2/25; JDA 35 = 34/0/1; SVC 58 = 56/0/2; LWJGL 53 = 51/1/1; installer 75 = 73/0/2.
Later source changes and targeted followups must be read with their separate report provenance.

### OPEN ITEM: browser mutation completion

The owner's three-strategy stop bound is exhausted. Do not restart another browser mutation strategy in pass 2.
Earlier snapshot: 363/446 accounted = 286 KILLED + 34 SURVIVED + 43 TIMED_OUT, 83 missing. Later snapshot
87818480b310bfb5e1fe523b7122a6fb39cf8c58e3ee652fe844f4fc683df112: 248/452 accounted =
216 KILLED + 9 SURVIVED + 23 TIMED_OUT, 204 missing. CDP acknowledgement hung after an owned Chromium crash fixture.
No exclusion or shorter mutation grace was added to pretend completeness. Exact commands, attempts, logs and
next requirements: `/home/dev/mcav-pass2-state/browser-open-item.md`. Later passes inherit this OPEN item.

### OPEN ITEM: common/native mutation assurance

High ordinary coverage does not eliminate resource, timing, malformed-input, native-discovery, platform and
performance mutants. See `common-open-item.md`, the full and targeted disposition JSON/Markdown, and the narrow
copier comparison. The remaining copier needed-byte multiply/divide and omitted release mutants are not equivalent.
No near-100% mutation claim is made. Controlled native resource instrumentation and isolated JVM/failure fixtures
are required; timeouts/crashes do not supply those oracles.

## Live server and visual evidence — Claude snapshot only

Tasks 4b/4c were delegated to Claude. `/home/dev/mcav-pass2-task4-report.md` describes 854eb587, the September 18
snapshot, with later `3249ba9e` visual followup. Media/map, renderer and sandbox video code changed afterward, so this
is not a final-tree live re-test. No new Paper/bot/client process was run in pass 2 after delegation.

Claude measured 15 play/release cycles in 59.7 minutes, 116 samples: 87 threads before/after, 375→372 MB forced-GC heap,
worst 1.5 ms MSPT, 20 TPS, zero ERROR/exception lines. Counts came from server-side probe; the bot's aliased 26.1
protocol was corroborating only. Contended CPU/decode timing is not a clean throughput benchmark.

Repeated screen construction stacked frames and hid valid map pixels: 270 frames for 135 maps. The proposed fix
clears ItemFrames in each destination frame cell before spawning; Claude's followup showed 135 frames and visible
still/video content, then clear on release. The original MapView/client-decoding conjecture was refuted.
After the own Finish commits and first push, both accepted commits were cherry-picked without conflicts:
`035f5bd0` → `6b1eff1a` (manual checklist), `3249ba9e` → `e16ab5d0` (frame cleanup). The later screen UUID tags were
preserved. `ce72b81d` adds the non-frame/adjacent-frame boundary regression and the explicit Location local;
`d8a24819` corrects local-build setup, image quoting, relative commands, ownership and measurement claims in the
manual checklist and links it in the documentation TOC. The final ordinary build, scoped mutation followup and
docs rebuild passed. These local checks do not turn Claude's historical measurements into a final-tree live run.

### OPEN ITEM: clear pacing and decoder-progress telemetry

`MapPacketFactory.clear()` is synchronous and unbudgeted: 16,384 color bytes/map/viewer plus overhead, about 9.4 MB
for 576 maps. 4,096 patch bundles can carry about 67 MB of color data. Delta playback's 128 KiB budget does not apply to
clear or joining-viewer snapshots. Pacing needs generation cancellation and ordering so an old clear cannot erase
new playback; naive scheduled chunks would violate the fixed release/restart ordering. Deferred, documented.

No decoder-progress warning currently distinguishes slow AV1 decode from stalls. Static/paused video can also
produce no packets; a correct diagnostic needs decoded/rendered/dropped counters and state-aware thresholds.
Tutorial documents the lower-resolution H.264 control and limits of the playing message. Deferred, not fixed.

## Remaining review risks

All 69 council bundle decisions and pass 1 disagreements are in the report and
`sept24-council-reconciliation.md`. An OPEN concern is not automatically a proven defect; missing reproduction
is explicitly distinguished there. Important followups include:

- Native frame-copy allocation on failure and format validation for custom non-BGR decoders; negative VLC time
  values; separate-audio lifecycle; invalid source-plane shapes; safe lifetime after timed-out user callbacks.
- MCAV installation CAS/lock ordering, shared capability guard and SVC static API across multiple instances;
  temporal same-length/different-width history and errorStrength semantics for reused indices.
- Unbudgeted snapshots/clears, shrinking-map stale borders, noise deferral fairness and extreme custom diffusion
  amplification; per-frame allocation and performance claims without benchmark.
- Browser zoom coordinates, tab ordering, cached-driver/global property behavior; VNC DNS time not bounded by
  connect timeout; macOS stale mounts and Windows archive filename rules.
- Resource-pack slow-reader/write limits and event-loop I/O; generated pack metadata acceptance; installer logger
  packaging, future-JDK Unsafe fallback, website PCM scheduling without dedicated JS tests.
- Enormous but arithmetically valid operator configurations still need a resource policy. OP permissions do not
  make input-domain limits irrelevant. Browser HTTP scheme restrictions are not a network isolation boundary.
- No final-tree Windows/macOS run, big-endian run, IDE inspection, live audible-fidelity evaluation or long-term
  native-memory soak. Tokens removed before this review may remain in git history; owner revocation status unknown.

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
  `BrowserPlayer.SHOW_WINDOW`, `VMConfiguration.getAll()`, `OS.OTHER`, `Arch.OTHER`, `Platform.isKnown()`, and common utility APIs.


Additional migration notes: dither setters became `with*`; MapConfiguration requires explicit map ID;
PixelMapper gained getStrength; pipeline builder descriptors changed; MatVideoFilter is a public extension point;
source detector tie order is deterministic, numeric filenames need `./`; SourceUtils direct-extension allowlist
changes yt-dlp routing; VNC requests 24-bit color; SVC default distance of 32 blocks; infinite repeating-source sentinel
was removed; invalid range arguments can now throw rather than clamp. Consult current API docs and release notes.

## Git and evidence

The own Finish series contains 23 logical review commits and a separate handover commit, ending at `b3f85dc2`;
that series was pushed before the two accepted Claude cherry-picks. Integration corrections are separate commits,
followed by this final handover update. The external report contains the full final commit list and normal-push
verification. Pre-existing `gradlew.bat` remains the only excluded worktree change. No review commit changes it.
The report retains all open findings; this is completion of the bounded review, not a claim of complete mutation
assurance or final-tree live/cross-platform validation.

The completion marker `/home/dev/.session-done/mcav-pass2` starts pass 3. Write it only after own logical commits,
accepted Claude cherry-picks, final build/push, `git log origin/rewrite..rewrite` empty, report finalization and owned
cleanup. Its HEAD must be the post-cherry-pick commit. Do not infer completion from this handover alone.
