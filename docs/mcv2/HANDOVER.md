---
orphan: true
---

(mcv2-handover)=
# MCV2 handover

For whoever maintains MCV2 in mcav next: what exists, why it is the way it is, what is known to be wrong or missing,
and how to check it in the game. It is short on purpose and points to the documents and reports that hold the detail.

This is the only handover for MCV2. The repository-wide `mcav-overhaul-handover.md` was removed by the owner
(mcav-features, 2026-09-25) and is not recreated; MCV2's notes live here, next to its specification, so they move with
it. The gpu-codec research repository MCV2 comes from is being retired: everything needed to understand, rebuild,
verify, operate and extend MCV2 is in mcav, and nothing in the build, the tests or the docs needs that repository.

## What exists

- **The codec** (gpu-codec, 2026-09-15 to 09-25): a block codec whose frames travel as Minecraft map colours and are
  decoded by a vanilla client's shaders. Its format is fixed at the research's round 19 ([format.md](format.md)); its
  results and limits are in [results.md](results.md).
- **The port** (stage mcav-mcv2, 2026-09-25 to 09-27), all in `mcav-bukkit`, package `me.brandonli.mcav.bukkit.media.mcv2`:
  the parser and decoder, bit-exact with the reference; the encoder, byte-identical with it for the shipped profiles,
  plus mcav's live presets and optional native SIMD kernels ([encoder.md](encoder.md)); the map-page transport; the
  resource pack (core text shader overrides and a post chain) that decodes in the client; per-viewer backpressure, the
  adaptive pacer and the shared encoder budget. The sandbox plugin plays pre-encoded streams (`/mcav mcv2 play`,
  `stream`, `stop`), encodes files (`/mcav mcv2 encode`, `cancel`) and plays live video through MCV2
  (`/mcav video mcv2 ...`).
- **Minecraft 26.3** (stage mcav-consolidate, 2026-09-27): the pack ported to 26.3's new shader compiler and proven
  bit-exact in the real 26.3 client ([design doc §5.2](../mcv2-integration.md)).
- **Self-containment** (the same stage): the reference's code vendored unchanged in `tools/mcv2-reference`, every
  fixture and table regenerable from it ([conformance.md](conformance.md)), the research's result files in `data/`.
- **Every wall of maps** (the same stage, Part 2, 2026-09-28): `--codec dither|mcv2` on the video, image, browser,
  VM and VNC screens, with `mcv2.default-codec`; one pack for every screen from `Mcv2PackServer` (up to 8 slots, reused
  by size so the pack seldom changes; hosted by the injector, an HTTP server or the website); page frames that hold
  their chunks loaded for late joiners; a frame-rate cap (`Mcv2Result.takeFrame`) that thins sources faster than a
  client draws ([design doc §14](../mcv2-integration.md)).

| what | where |
|---|---|
| format specification | [format.md](format.md) |
| encoder notes, tables and their provenance | [encoder.md](encoder.md) |
| results: frontier, shipped profile, ceiling, AV1 references | [results.md](results.md), `data/` |
| test vectors and how to regenerate them | [conformance.md](conformance.md) |
| design as built, with every measurement (threading, pack, transport, far viewers, server load, live, natives) | [../mcv2-integration.md](../mcv2-integration.md) |
| testing on your own client | [../mcv2-testing.md](../mcv2-testing.md) |
| code, pack, natives, fixtures | `mcav-bukkit/src/{main,test}/java/.../media/mcv2`, `src/main/resources/mcav/mcv2/pack`, `src/main/native/mcv2`, `src/test/resources/.../media/mcv2` |
| scripts that tie the port to the reference, and the lab tools | `tools/mcv2` (README), `tools/mcv2-reference` |

## Decisions that shape it, with their evidence

- **Frames predict from the previous decoded frame** (model A), because predicting from the last keyframe (B) costs
  73% more rate and all-intra (D) 144% at matched VMAF; the pack keeps both references, so B and D are per-screen
  choices, and the far-viewer backpressure never sends a frame a viewer cannot decode. Both council members rejected A as
  the default for robustness; the measured cost of the alternatives decided (design doc §4).
- **The shipped profile is round 19's `p30r19-compact_final-65p255994`**: the cheapest round-19 1080p30 point at VMAF
  75, 3.458 map Mbps, which mcav's encoder reproduces byte for byte (results.md). The research file's own
  recommendation is a stale, 64% more expensive point.
- **Live sources use `live` at 1080p30.** The live presets meet 1080p30 (p95 under 32 ms at 12 threads); none meets
  1080p60 on the 6-core machine it was measured on (design doc §12; results.md).
- **The client decodes in the entity-outline post chain**, the one vanilla hook that runs every frame with persistent
  targets and may write the screen: it runs while a glowing entity is drawn, so each screen hides glowing page frames
  behind its wall; black outlines are rejected because an outline colour of 0 means none (design doc §5).
- **Everything in `mcav-bukkit`** (the owner's addendum 16): MCV2 is a Bukkit feature, and `mcav-common` holds none of it.
- **Native kernels on by default, Java as the fallback**, bit-exact with each other, loaded only after their SHA-256
  checks (design doc §13).
- **On 26.3 the pack copies its own targets** with an exact texel fetch, because 26.3's `post/blit` loses a level of
  bright values on every rendered frame (design doc §5.2).
- **The reference is vendored, not reimplemented**: the definition mcav is proven against must not drift, so its files
  stay byte-identical to the pinned commit (conformance.md).

## Review passes, in a few lines each

The full reports are in the owner's home folder, named below; this is their conclusion, not a copy.

- **Pass 1** (`mcav-review-report.md`, 2026-09-16/17): `master` had no tests at all, so every one of the rewrite's
  2,328 tests was checked against the behaviour an authoritative source defines rather than against the code; it fixed
  what it could prove and listed the defects it left to the owner, with reasons (its §7).
- **Pass 2** (`mcav-pass2-report.md`; `mcav-pass2-task4-report.md` for the live server): a verdict for every one of the
  923 changed paths, lifecycle, geometry, ownership and numeric defects repaired; on a real Paper server and client,
  no leak over an hour of play and release, and the "blank wall" was stacked item frames, fixed (`3249ba9e`).
- **Pass 3** (`mcav-pass3-report.md`): settled where passes 1 and 2 disagreed (FPS counter synchronisation, temporal
  dithering history, quoted media locations, an error for a missing video codec); the branch "largely ready to merge".
- **Pass 4** (`mcav-pass4-report.md`, 2026-09-24): a security review with hostile input: 12 findings (1 high, 7 medium,
  3 low, 1 informational), 8 fixed with tests; safe for a server whose operators are trusted, with the browser's
  missing OS sandbox in containers, OpenCV's native decode size and pack hosting on the game port left open.
- **Hardening** (`mcav-hardening-report.md`): seven bugs found by new property, concurrency and fuzz tests and fixed;
  the default build runs tests, the coverage lint, the property tests and a replay of every fuzz input.
- **mcav-features** (`mcav-features-report.md`) and **its security review** (`mcav-features-sec-report.md`): the JCEF
  browser with no JVM options on any OS, and virtual machines with sound; both ended GATE NOT MET on PIT survivors
  only; Chromium without its sandbox and QEMU's user networking and VNC display remain open.
- **mcav-mcv2** (`mcav-mcv2-report.md`): the port above, with conformance, 30-minute fuzzing per target, a
  differential test and a security review (two findings, fixed); GATE NOT MET on 1080p60 live encoding only.
- **mcav-consolidate** (`mcav-consolidate-report.md`): the merge of the browser and VM work, the upgrades and
  Minecraft 26.3, this documentation, and the plugin integration.

## Known issues

- **1080p60 live encoding is not met**: `live-fast` needs 19.7 ms (proxy) and 31.3 ms (gameplay) per frame at the 95th
  percentile against 16 ms on 12 threads of an i7-8700; a 60 fps gameplay frame costs 207 ms of CPU. At the default 6
  encoder threads, `live` misses the 32 ms 1080p30 target on fast gameplay (36.4 ms) while its mean still fits.
- **Improved Transparency** (a 26.3 video option) draws text into its transparency targets, where a page's bytes
  cannot reach the screen unchanged, so the pack discards page fragments there and such a client shows no MCV2 picture.
- **Minecraft 26.3's Vulkan backend** was not tried beyond a software renderer, on which loading the pack's shaders
  took over ten minutes; real GPUs other than the Intel UHD 630 and Mesa's llvmpipe are untested (the owner's
  procedure: `docs/mcv2-testing.md`).
- **Paper 26.3 has only alpha builds** (build 49 is the one tested); its bundled spark profiler stalled the server thread
  under the lab's load twice.
- **Untested platforms**: ARM64 servers (the AArch64 libraries pass under emulation only), AVX-512 speed, Windows and
  macOS on Apple silicon and Windows on ARM64, and Windows and macOS servers beyond the kernels' JVM tests.

## Open items

From the research ("Untried ideas" of its final report). Leads, with their known obstacles; none is a recommendation:

- **Quarter-pixel motion**: needs nine signed bits a component where the two-byte record holds eight, so the search
  range would have to halve; round 19's near-lossless point already regressed 22% of rate for the same VMAF.
- **Multiple reference frames**: a second texture and fetch on every predicted fragment against 1.87% of draw headroom;
  measure the draw first.
- **Deep partitions below 8x8**: priced at 0.02% of logical rate; not what holds the codec back.
- **Four-colour palettes**: doubles the selector field, grows endpoints by a third; never measured, and round 17's
  selector tables change the arithmetic.
- **Perceptual quantisation matrices**: untried (round 5 weighted the RD decision, not the quantiser).
- **Gradual intra refresh**: would smooth the keyframe spike (about 2.7 times an inter frame); a delivery gain, little
  BD-rate.
- **Low-frequency DCT at fixed record sizes**: four coefficients at a fixed size keep records indexable; Round T
  rejected a full transform on cost, not admissibility.
- **Affine motion**: six parameters and a matrix multiply per fragment; the most speculative.
- **Measured dead**: RGB565 solid colours (+0.15% at the bottom rung, negative above) and a dictionary of residual
  bodies (reuse 1.05). **Measured alive and forbidden**: round 15's motion table, about 1.3% BD-rate, 0.08 standard
  errors over the draw ceiling; if that ceiling is ever renegotiated, re-run it first.

From mcav's side:

- **1080p60 live** needs fewer CPU milliseconds per frame or more cores: about 10 (proxy) to 15 (gameplay) fully used
  cores of an i7-8700 by the hardware guide, or a faster preset outside the +30% quality cap.
- **Surviving PIT mutants** of the live search heuristics (mcav-mcv2 report, "Coverage and PIT").
- **The untested platforms and GPUs** above.

## Testing in the game

- **On your own client**: `docs/mcv2-testing.md` - a server with the sandbox plugin, a screen, a stream, what to look for,
  shader errors, and the numbers to compare.
- **Headless, as the stages did**: a Paper server with the plugin built by `-Pmcav.e2e=true` and the debug view
  (`-Dmcav.mcv2.debugView=true`), and a client started with portablemc (`portablemc start 26.3`, and on Xvfb
  `SDL_VIDEO_FORCE_EGL=1`, which gives 26.3 its OpenGL backend); play a stream slowly (one frame every two seconds), grab
  the screen with ffmpeg's `x11grab`, compare the pictures with the reference decode with `tools/mcv2/capture_check.py`,
  and check the transport strip - page headers and checksums, descriptor rows, status squares - with
  `tools/mcv2/strip_check.py`. With several screens, each screen has its own debug view and its own slots of the strip:
  `strip_check.py --screens N --screen I --first-slot S --total-slots T --debug-top Y` checks screen I. Clear the
  weather first: rain draws over the debug view.
- **Without the game**: `tools/mcv2/shader_check.py`, plain and `--spirv` (Minecraft 26.3's compile path, through
  `Mcv2ShaderCompile.java`, which also compiles each screen's copy of the passes in `post/s<screen>/`), on the
  conformance and edge streams; `--second-screen` runs them as the second screen of a two-screen pack.
- **In the build**: the conformance, property, fuzz and differential tests run with `./gradlew build`;
  `:sandbox:plugin:e2eTest -Pmcav.e2e=true -Pmcav.acceptMinecraftEula=true` runs a real Paper server.
