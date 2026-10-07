(mcv2-live)=
# Live Encoding

A video file can be encoded ahead of time with the exhaustive `ship` search, but a browser, a virtual machine, a VNC
desktop, a stream or a camera plays while it is encoded, and MCAV plays files live as well, so seeking, pausing,
speed, looping and the audio outputs follow the player. The `ship` search takes 570 ms per 1080p frame on 12 threads
of an i7-8700, 17 times too slow for 30 frames a second. MCAV's answer is a ladder of cheaper searches that write the
same format. Every figure on this page was measured by the mcv2 stage on that 6-core i7-8700 (12 threads) under
Temurin 25, and is on the [results page](results.md#the-live-presets) with its method, or in the
[design doc, section 12](../mcv2-integration.md#12-live-1080p60-the-live-profile). These are the original integration
measurements; later bit-exact CPU improvements and loaded-host limits are summarized in
[current usage](using.md#troubleshooting).

## The Preset Ladder

| Preset | For | Lambda | Rate against `ship` at equal VMAF, map / after zlib: 1080p30 proxy; 30 fps gameplay | VMAF mean at the default: proxy; gameplay |
|---|---|---:|---|---|
| `ship` | pre-encoded files (`/mcav mcv2 encode`) | 65.26 | 0 / 0; 0 / 0 | 77.66; 90.95 |
| `live` | **the default of every live screen** | 72, raised with motion | -5.1% / -3.4%; +7.9% / +6.9% | 75.7; 76.1 |
| `adaptive` | the first step down: `live` on calm pictures, `live-fast`'s search once the source moves | 72 / 55 | -5.5% / -3.8%; +24.9% / +14.3% | 75.7; 76.1 |
| `live-fast` | the fastest step | 55, raised with motion | +17.7% / +18.4%; +29.4% / +17.1% | 76.1; 76.1 |

The owner's rules for the ladder: every preset reaches a VMAF mean of at least 75 at its default on both 30 fps
sources, `live` may cost at most 10% more rate than `ship` at equal VMAF, and a faster preset at most 30%. All three
live presets are inside their caps, map and after compression. On the proxy `live` and `adaptive` even beat `ship`,
because the motion-dependent lambda and the half- and quarter-resolution motion search suit its quiet content; on real
gameplay, where `ship`'s exhaustive search finds more, they cost 7.9% and 24.9% more rate.

**Which preset a live source uses.** A screen starts with `live`, chosen as the slowest preset that met the original
1080p30 gate below. Its performance on another source or host must be measured. When the encoder
cannot keep up, the screen steps **down the ladder** first (`live` to `adaptive` to `live-fast`, without a keyframe:
every live search writes the same format from the same pictures), then to fewer frames a second, then to a smaller
video, and at worst to the dithered maps ([server cost](server.md#adaptive-never-overload)). The plugin caps every screen it
encodes at 30 frames a second (`Mcv2Configuration.maxFrameRate`): no preset meets 1080p60, and a client decodes at most
one video frame per frame it draws. A pre-encoded stream played with `/mcav mcv2 stream` is not encoded and plays at
the rate the command gives, up to 240 frames a second.

## The Gates

**1080p30: met in the original integration.** The p95 of the time to encode a frame, every frame verified, as a screen encodes it, over 660 frames
with keyframes and scene cuts, best of three runs, with the native AVX2 kernels:

| Preset | 12 threads: proxy; gameplay | 6 threads (the default budget of a 12-thread machine): proxy; gameplay |
|---|---|---|
| `live` | **20.09 ms; 31.21 ms** (six runs 31.21 to 31.89) | 22.63 ms; 36.37 ms (not met) |
| `adaptive` | 20.37 ms; 27.44 ms | 22.53 ms; 31.55 ms |
| `live-fast` | 17.61 ms; 27.24 ms | 20.93 ms; 31.81 ms |

With the default budget of 6 threads, `live` misses the p95 on fast gameplay while its mean, 29 ms, still fits a 30 fps
frame, so a screen keeps it at the full rate with a late frame now and then; the pacer steps to `adaptive` only when the
mean stops fitting. A server owner with fast gameplay screens should pick `adaptive` for them, or give the encoders
more threads.

**1080p60: not met.** Two measurements, on the 1080p60 proxy and on 60 fps gameplay, with `live-fast`, the fastest rung
inside the quality cap, 12 threads, best of three:

| Measurement | Gate | 1080p60 proxy | 60 fps gameplay |
|---|---|---|---|
| throughput: p95 of the interval between finished frames, fed back to back | under 16 ms | 19.72 ms (70.6 to 72.7 frames a second) | 31.34 ms (48.5 to 49.2) |
| latency: p95 from arrival (every 16.667 ms) to finished | under 33 ms, nothing dropped | **25.91 ms** (met) | unbounded: 49 frames a second arrive at 60 |

A 60 fps gameplay frame costs 207 ms of CPU even with `live-fast`: 17 ms at a perfect split over 12 hardware threads,
which are hyperthreads adding about 25% over the 6 cores, before the 5 to 10 ms of each frame that do not split (the
verification's decode, the writer, the global motion). Pipelining already hides that serial part behind the next
frame's search. Of the original search levers tried, nothing inside the quality cap cut more than about 3%; the changes that cut
10 to 20% left the cap on gameplay by 38.6 to 56%. This predates the later bit-exact CPU optimizations. By the hardware guide, about 10 (proxy) to 15 (gameplay) fully used cores of this CPU would
hold 1080p60; on this machine, a 60 fps screen steps down to `adaptive`, `live-fast` and then fewer frames.

## What Each Lever Bought

The live search was built lever by lever, each kept only inside the quality cap, measured by CPU time per frame
(the load-robust speed measure) and by BD-rate against `ship`. The full table, with what was rejected and why, is on the
[results page](results.md#the-live-presets); the ones that made the presets:

- **One trial** instead of four, the vector chosen before the search; **early SKIP** at the proven-exact 26.5 lambda;
  **split thresholds** from the top (`live-fast`: SKIP to 60 lambda, splits at 900 and 600).
- **The leaf modes `ship` chooses on real gameplay**: a set chosen on the SKIP-heavy proxy alone needed 80% more rate
  than `ship` on gameplay, whose P frames `ship` codes largely with solid colours and intra grids.
- **One fitted quantizer** per compact record (-8 to -15% CPU on gameplay), **three compact classes** instead of five
  (-15% CPU), **half-resolution motion** first (-3% rate at equal VMAF: what brings gameplay inside the cap), a
  **quarter-resolution level** in `live` (-1.3% and -1.9% rate).
- **The motion lambda**: gameplay at the default went from 27.6 to 13.1 map Mbit/s at VMAF 76.1, and -33% CPU.
- **Native SIMD kernels** (below): 664 to 249 ms of CPU per gameplay frame, 310 to 177 on the proxy.
- **Pipelining**: 74 against 63 frames a second on the 1080p60 proxy, 52 against 45 on gameplay.

Rejected by measurement: content-adaptive mode pre-selection (no speed-up), reusing the previous frame's decision (+18%
rate on gameplay), a two-stage mode decision (+18.8 to +33.9%), SVT-AV1's {cite}`svtav1` early termination and depth prediction
(+38.6 to +56% combined), aligned buffers, LTO and PGO for the kernels (no net gain), and giving the verification
priority over the next search (slower overall).

## The Native Kernels

A live search spends most of its time in pixel kernels: reconstructing and scoring every candidate leaf, predicting and
searching local motion, fitting grids and palettes, converting colours. `mcav-bukkit` ships them as a small C++17
library per platform, called through Java's Foreign Function & Memory API {cite}`jep454`, no JNI and no JVM flag. **They compute
exactly what the Java kernels compute**: the Java kernels are the oracle, one source is compiled per SIMD level with
IEEE arithmetic and no fused multiply-add (`-ffp-contract=off`) and Java's wrapping integers (`-fwrapv`), every sum
is taken in Java's order, and a stream is byte-identical whichever kernels encoded it. The reference search of `ship`
always runs Java; so does everything that is not a pixel kernel.

**Which level runs.** The library reports the levels the CPU runs and the best is used: AVX-512, AVX2, SSE4.1 or SSE2
on x86-64 (AVX-512 only with the Ice Lake instruction set, so never on Skylake-SP or Cascade Lake, and never on macOS);
SVE at 512 or 256 bits, or NEON, on AArch64 (SVE only where Linux lists it, and 128-bit SVE stays on NEON). Blocks
narrower than a vector drop to a narrower level, so no kernel reads past a row.

| Platform | Library | Levels | Tested |
|---|---|---|---|
| Linux x86-64 | `libmcv2kernels.so`, 253 KB | scalar, SSE2, SSE4.1, AVX2, AVX-512 | every JVM test at scalar to AVX2 (i7-8700); every level standalone, AVX-512 under Intel SDE; glibc and musl; a Paper server end to end |
| Linux AArch64 | `libmcv2kernels.so`, 146 KB | scalar, NEON, SVE 256, SVE 512 | standalone under qemu (Cortex-A72; SVE at 16, 32 and 64 bytes), glibc and musl, digests equal to the x86-64 library's |
| Windows x86-64 | `mcv2kernels.dll`, 335 KB | scalar, SSE2, SSE4.1, AVX2, AVX-512 | the native JVM tests in a Windows VM, `MCV2 kernels: native avx2 (windows-x86_64)` |
| Windows AArch64 | `mcv2kernels.dll`, 79 KB | scalar, NEON | built, **not tested** |
| macOS x86-64 | `libmcv2kernels.dylib`, 199 KB | scalar, SSE2, SSE4.1, AVX2 | an earlier build passed the native JVM tests in a macOS VM |
| macOS AArch64 | `libmcv2kernels.dylib`, 118 KB | scalar, NEON | built, **not tested** |

Any other processor or operating system runs the **Java fallback**, which writes the same stream. The Linux libraries
import nothing, not even the C library, so one library per architecture serves glibc and musl alike. The full matrix,
with what each level was verified on natively and in which emulator, is on the
[results page](results.md#the-native-kernels).

**Speed.** On AVX2 the reconstruction kernels run 2 to 4.6 times faster than Java, the palette clustering 4.8 to 7.5
times, and the whole live encoder spends **2.7 times less CPU** on a gameplay frame and 1.8 times less on quiet content.
The smallest kernels gain nothing: a call costs 50 to 90 ns of argument checks before any work.

**What the server log says.** The first live encoder extracts the library into the plugin's data folder (never `/tmp`,
which hosted servers often mount without execution), checks it against the SHA-256 compiled into MCAV, loads it and
logs once which kernels run; the plugin does this when it starts, before any screen:

```text
MCV2 kernels: native avx2 (linux-x86_64)
```

or `MCV2 kernels: Java, <why>` when anything stops the library: no library for the platform, a checksum, the extraction,
the load, or a JVM that refuses native access. Paper's launcher declares native access for its plugins, so on Paper
the library loads without any flag and without a warning.

**Turning them off.** Set `mcv2.native: off` in `plugins/MCAV/config.yml`, or start the server with
`-Dmcv2.native=off`, which wins over the configuration; the log then says
`MCV2 kernels: Java, turned off by mcv2.native=off`. `-Dmcv2.native.level=avx2` (or `avx512`, `sse41`, `sse2`, `neon`,
`sve256`, `sve512`, `scalar`) caps the level, for a measurement. The Java kernels cost about 2.7 times the CPU on
gameplay and 1.8 times on quiet content.

**Rebuilding the libraries.** The libraries are committed, so the normal build needs no C or C++ toolchain.
`./gradlew :mcav-bukkit:buildMcv2Natives -Pmcav.natives=build` rebuilds all six with Zig 0.16.0 (its clang and linkers;
`ZIG=/path/to/zig`, and the script refuses another version) and writes their `SHA256SUMS` and `SOURCES`, the digest of
every source file they were built from; the new digests go into `Mcv2Natives.DIGESTS`. The default build's
`NativeLibrariesTest` reads every library's headers, checks its machine and its exported kernels, and fails when a
source changed without the libraries being rebuilt from it. Two builds give the same bytes.
`./gradlew :mcav-bukkit:formatMcv2Natives -Pmcav.natives=build` formats the sources with clang-format.

## What a Viewer Receives

At 1080p30 with the default `live`, a viewer receives **2.80 Mbit/s of map packets on quiet content, 1.83 after the
game's compression**, and **13.0 (8.3 after compression) on fast gameplay**; `adaptive` sends 2.80 / 1.83 and 15.2 /
8.6, `live-fast` 3.28 / 2.12 and 15.3 / 8.7. The client decodes a new frame of the `live` stream in 7.4 ms (quiet
content) and 8.7 ms (gameplay) on an Intel UHD 630 ([client decode](client.md)).
