# MCV2 in mcav: integration design

Status: **design, written before the integration code** (2026-09-25), updated as evidence arrived. The decisions
below cite the measurement or experiment behind them; where a decision is provisional it says what would settle it.
Handover notes for later stages are at the end.

MCV2 is the block codec of the gpu-codec research repository: a server-side rate-distortion encoder, a bitstream of
32→16→8 block trees without an entropy coder, a transport that carries each frame as six-bit symbols in Minecraft
map colours, and a GLSL 330 fragment decoder. mcav sends a vanilla client one or two "data" maps per frame instead of
135 dithered maps, and a resource-pack shader reconstructs the picture.

## 1. What is ported, from where

- **Normative bitstream:** the Python reference decoder of gpu-codec at commit
  `85445433aeb9f8a35a5ce528d47d8829976d1401` (`mcvideo/format.py`, `v2.py`, `compact.py`, `pattern.py`,
  `decoder.py`, `pixels.py`), not `FORMAT.md`, which predates every kept frontier round. The complete syntax as
  implemented is written down in [mcv2-format.md](mcv2-format.md).
- **Kept rounds** (EXPERIMENTS.md): 1 immediate motion (mode 20), 4 derived root directory (flag 16), 7 derived
  offsets (flag 32), 8 matched cost model (encoder), 9 packed symbols (flag 64), 10 two-level walk (flag 128), 12 motion
  range 24 (encoder), 13 pattern RDO (encoder), 16 endpoint table (flag 512), 17 selector tables (flags 1024, 2048,
  4096), 18 RGB565 endpoints (flag 8192), 19 half-pixel motion (encoder). The pre-frontier MCV2 syntax (short index,
  sparse child quartets, compact classes 0-8 with the static 2,048-byte residual books, pattern palettes) is kept.
- **Not ported, rejected with `UnsupportedSyntaxException`:** MCV1 frames (magic `MCV1`), the coarse palette modes 21
  and 22 of round 3, and the motion table flag 256 with indexed motion mode 23 of round 15. These are the only inputs
  on which the Java decoder and the reference knowingly disagree; the reference accepts modes 21/22 and ignores flag
  256 on stored-index frames.
- **Profiles shipped** (owner addendum 2, rule: newest round with 1080p30 points, cheapest `wire_mbps` at VMAF mean
  >= 75, and its >= 70 alternative from the same round, in `results/frontier_1080p30.json`):

  | role | point | lambda | map Mbps | VMAF mean / min | Python encode |
  |---|---|---:|---:|---:|---:|
  | ship | `p30r19-compact_final-65p255994` | 65.255994022 | 3.458416 | 77.933 / 70.117 | 172 s/frame |
  | low bandwidth | `p30r19-compact_final-137p730758` | 137.730758207 | 2.122096 | 70.580 / 64.587 | 147 s/frame |

  `recommendation.ship` in that file still names the stale 09-19 point `p30-compact_final-44p858993` (5.670 map
  Mbps, VMAF 77.75); round 19 reaches a higher VMAF for 39% less rate, so it is not shipped.

## 2. Module placement

| part | module | package |
|---|---|---|
| frame parser and validator, decoder, reconstruction kernels, residual books, receiver state machine | `mcav-common` | `me.brandonli.mcav.media.mcv2` |
| transport pages, CRC, page assembler, map alphabet | `mcav-common` | `me.brandonli.mcav.media.mcv2.transport` |
| encoder, tree serializer, fits, motion search, profiles | `mcav-common` | `me.brandonli.mcav.media.mcv2.encode` |
| video result step, map packets, player sessions, configuration, resource pack generation | `mcav-bukkit` | `me.brandonli.mcav.bukkit.media.mcv2` |
| demo command | `sandbox/plugin` | |

The core is pure Java with no Python and no native code. Static data it needs is committed as checked resources:
`residual_books.bin` (SHA-256 `1737842f…e788`, the profile's VQ/PQ books) and `fitting_matrices.bin`
(SHA-256 `b575fd1e…af0e`, the reference encoder's float32 least-squares matrices). Nothing reads a path inside
gpu-codec at build or run time.

**Conformance is the bar.** The decoder reproduces the reference's float32 and float64 operations in their order
(numpy 2.5.3 semantics were pinned by experiment: negative float→uint8 wraps, reduced-chroma residuals are scaled in
float64, channel sums associate left to right), and is bit-exact on every frame of the round-19 corpus and both
shipped streams (780 of 780 frames, per-frame SHA-256 of the RGB output). The serializer reproduces all 780 frames
byte for byte from their rebuilt block trees. The encoder only had to decode with the reference and land within
0.3 VMAF / 0.1 dB of the reference encoder; it does better: it reproduces the reference encoder's output **byte for
byte** on both shipped 30-frame 1080p30 streams (archive SHA-256 `6fc68739…` and `4399bb6f…`), so its quality and
rate are the reference's exactly. The float32 least-squares fits that looked irreproducible are the reference's own
pseudo-inverse matrices, loaded from `fitting_matrices.bin` and applied separably in float64.

## 3. Threading and the real-time budget

- **Budget:** 1080p30 is 33.3 ms per frame (1080p60, a stretch goal, 16.7 ms). The reference encoder takes 172 s per
  frame for the ship profile on this 12-core machine, 5,200 times too slow.
- **Structure:** every block's mode decision depends only on the previous decoded frame, never on a current-frame
  neighbour (no entropy coder, no left/above prediction, no spatial intra predictor). All blocks of all three levels
  are independent, so candidate evaluation is parallel over blocks with no shared mutable state: each worker owns its
  scratch buffers and writes only its blocks' results. Reductions (trial choice, tree costs) run in fixed block order,
  so the output never depends on the thread count. Distortion is an exact integer (`16 * 6 * weighted SSE` over 8-bit
  values), so summation order cannot change a decision.
- **Trials:** the reference encodes each frame up to four times (global motion on/off, RGB565 endpoints on/off) and
  keeps the cheapest. Intra candidates do not depend on the global vector and are evaluated once for all trials;
  pattern candidates once per endpoint precision; temporal candidates once per vector. Each trial's winner is the first
  minimum of its own candidate sequence, which is what the reference computes; the exact rate bound only prunes
  candidates that cannot win any trial.
- **GOP parallelism** for files: keyframes every 60 frames make GOPs independent, so a pre-encode job runs GOPs
  concurrently on top of block parallelism. Live sources cannot use it.
- **Measurement:** warm JIT (the first frames of a run are discarded), best of three runs, mean and median ms/frame,
  thread scaling at 1, 2, 4, 8 and 12 threads, and the machine load recorded (`pgrep -fc GradleWorkerMain`). The
  measurement command is committed; it is not part of CI.
- **Feasibility probe (item 4c):** a naive Java version of the inner loop (full candidate set, one trial,
  superblock-parallel) ran at 1.05–1.28 s per frame on 12 shared threads and 6.4 s on one. Real time at 1080p30 with
  the shipped search is therefore not expected on this machine; the speed campaign reports what it reaches.

## 4. The reference-frame problem

P frames predict from the previous decoded frame. Vanilla core shaders keep no state between frames, but the client
does offer one legitimate mechanism: **persistent post-chain targets**.

### Evidence gathered on the real 26.2 client (headless, llvmpipe) and its unobfuscated code

1. Maps are per-map 128x128 RGBA8 `DynamicTexture`s drawn by `RenderTypes.text` → pipeline `TEXT`, shaders
   `core/text.vsh` and `core/text.fsh`, which a resource pack overrides. Blend is `TRANSLUCENT`; depth is reversed-Z
   with `glClipControl(LOWER_LEFT, ZERO_TO_ONE)` where supported.
2. **E1 — bytes survive.** A `text.vsh` override reads `Sampler0` in the vertex shader, recognises a transport page by
   its first seven symbols, and moves the quad to an exact screen strip; `text.fsh` writes the texels unmodified with
   alpha 1. A post pass reading `minecraft:main` recomputed the CRC32 of both pages of a real keyframe of the ship
   stream: both matched.
3. `LevelRenderer` runs `post_effect/entity_outline.json` after the main pass whenever any glowing entity drew outline
   geometry. That chain may read **and write** `minecraft:main`. `PostChainConfig` targets accept a fixed width and
   height and `"persistent": true`; `PostChain.getOrCreatePersistentTarget` reallocates only when the size changes.
   **E1 — state survives:** a 1x1 persistent counter incremented every frame (505 → 703 → 732 across screenshots).
4. **E2 — placement.** Invisible "anchor" frames at the screen's corners write the screen's view-space geometry and
   the projection matrix into a descriptor row via flat varyings; a post pass ray-casts every pixel onto the screen
   plane and depth-tests against the scene. The test pattern landed exactly on the 4x2 wall, correctly oriented, and
   objects in front of the screen occluded it. No per-pixel colour marker is needed, so nothing in the scene can be
   mistaken for video.

### Options and what they cost (ship profile, 1080p30)

| option | client state | extra bandwidth | robustness |
|---|---|---|---|
| **A** persistent previous-frame target in the post chain | one fixed-size persistent target | none: the modeled 3.458 map Mbps | every P frame must be decoded, in order; a frame the client never renders breaks the chain until the next keyframe (≤ 2 s) |
| **B** P frames predict only from the last keyframe | the decoded keyframe | measured: 5.447 map Mbps at the ship lambda (+57.5%) with VMAF 76.53; **+73% at matched VMAF 77.93** | any render cadence works: each P frame decodes on its own against the keyframe |
| **C** raw RGB8 reference sent as maps | none | 6.22 MB (507 maps) per refresh: ~33 Mbps at one refresh per 2 s; ~1 Gbps per frame | fails the budget by an order of magnitude |
| **D** all-intra (key interval 1) | none | measured: 6.287 map Mbps at the ship lambda (+81.8%) with VMAF 73.36; **+144% at matched VMAF 77.93** | any cadence, no state at all |

**Council (Codex, Antigravity), 2026-09-25**, record in the report: both reject A as the default. The render loop is
decoupled from the 30 fps video: at 20 render fps, looking away (the data frames and the trigger are culled), during
a stall, or after F3+T, frames go unseen and A's reference chain breaks; the server receives no telemetry to know.
Codex: ship D first, B if it measures well, A experimental, and give A map banks plus bounded catch-up if kept.
Antigravity: ship B, D as the server-side escape.

**Measurement (2026-09-25).** The Java encoder has a reference-policy option (`PREVIOUS_FRAME`, `LAST_KEYFRAME`) and a
key interval, so the three models were encoded from the same 30 frames of the frontier's 1080p30 source and scored
with the frontier's own VMAF filter (option A reproduces the frontier's 77.933209 / 34.4437 dB exactly):

| model | lambda | map Mbps | VMAF mean / min | PSNR dB |
|---|---:|---:|---:|---:|
| A previous frame (ship) | 65.26 | 3.458 | 77.933 / 70.117 | 34.444 |
| B last keyframe | 65.26 | 5.447 | 76.529 / 70.117 | 34.355 |
| B last keyframe | 45 | 6.497 | 79.329 | |
| B last keyframe | 32 | 10.313 | 82.733 | |
| D all-intra | 65.26 | 6.287 | 73.357 / 70.117 | 34.208 |
| D all-intra | 45 | 7.704 | 77.344 | |
| D all-intra | 32 | 11.716 | 80.587 | |
| D all-intra | 22 | 17.599 | 84.553 | |
| D all-intra | 15 | 23.718 | 87.995 | |

Interpolated at A's VMAF 77.93: B needs about 5.97 map Mbps (+73%) and D about 8.43 (+144%). The raw sweep is in the
report's evidence.

**Decision.** The shipped default is **A**, the codec as the frontier tuned it: the council's objection is about
robustness, not correctness, and B and D cost 73% and 144% more bandwidth for the same picture. The resource pack
implements **one decode pipeline with two persistent references**, the previous decoded frame and the last decoded
keyframe; a P frame's reference id selects which one it predicts from. So the same pack decodes all three models and
the server chooses per screen: A by default, B (`ReferencePolicy.LAST_KEYFRAME`) where viewers render below the video
rate or look away often, D (key interval 1) where no client state may be assumed. A client that misses a frame under A
shows the last good picture until the next keyframe (at most 2 s at the ship key interval); map banks with catch-up
decoding would remove that gap and remain a stretch goal.

## 5. Client shader architecture (resource pack)

- **Transport strip.** `core/text.vsh` recognises page maps (header prefix) and anchor maps (signature), reads the
  slot from the page header in the vertex shader, and moves the quad to a fixed rectangle at the top of the screen.
  `core/text.fsh` packs four six-bit symbols into three RGB bytes per pixel, so a page needs 4,096 pixels (3 rows at
  1920 wide) instead of 16,384. Alpha is 1 so the `TRANSLUCENT` blend is exact; depth is nearest so nothing later
  overdraws the strip. All other text rendering (signs, names, GUI, grayscale and see-through variants) is unchanged.
- **Anchors.** Invisible item frames at the screen's corners hold static anchor maps (signature, the anchor's column
  and row in the screen, the screen's size in blocks, the frame's facing, a checksum). Their vertex shader writes the
  screen's top-left corner, right and down vectors in view space and the projection matrix into a descriptor row. Any
  one visible anchor places the whole screen.
- **Post chain** (`post_effect/entity_outline.json`, the vanilla outline passes kept after ours): bytes pass
  (strip → RGBA8 frame bytes of the slot being decoded), status pass (page headers, frame ids, CRC32), decode pass (the
  gpu-codec GLSL decoder reading the byte target and the persistent reference, writing a scratch target), commit pass
  (scratch → persistent reference and state), screen pass (ray-cast, depth test, draw the picture into main), strip
  restore (the strip rows are refilled from the rows below them).
- **Trigger.** The chain runs only when a glowing entity drew outline geometry, so the data frames behind the screen
  are visible glowing item frames on a dedicated team colour, and the first pass removes that colour from the outline
  target so the frames never show a glow.
- **Known limits.** Iris/Sodium shader pipelines, other packs overriding `core/text` or `entity_outline.json`, and the
  26.2 Vulkan backend are outside what was tested; Fabulous graphics composites translucency after our pass. One
  MCV2 screen per client at a time.

## 6. Server integration

- **Result step** `Mcv2Result` beside `MapResult`: frames are encoded on an encoder thread, each frame's pages are
  mapped through the six-bit alphabet (symbol + 4) into whole map rows and sent with the existing
  `MapPacketFactory`/bundle path, one bundle per frame so both pages of a keyframe arrive together.
- **Configuration** `Mcv2Configuration` in the style of `MapConfiguration`: profile, key interval, prediction model,
  the first data map id and the number of page slots, the anchor map ids, the screen size in blocks, the viewers.
- **Players without the pack** never see a broken screen: the result tracks resource-pack status per player and sends
  those players the existing dithered map path (or a chat message when no fallback wall exists).
- **Pack generation** reuses `SimpleResourcePack` and every `PackHosting` strategy; the pack's description and a
  `mcav_mcv2.json` entry name the codec profile, the gpu-codec commit and the page geometry, so a pack is matched to
  the stream it can decode.

## 7. Transport and wire accounting

Each frame is split into pages of 16,384 six-bit symbols (12,256 payload bytes after a 32-byte header with a CRC32).
A page is sent as whole 128-symbol map rows, so the charged map rate is `rows * 128 + 18` bytes per page, which is the
frontier's accounting. Minecraft compresses packets with zlib; because map bytes carry at most six bits each, the
owner's measurement on archive pages found a **35% saving**, and mcav measures it on the real packets it sends, in
both directions, before recommending any compression threshold.

## 8. Hostile input

Every field of a frame or page is treated as hostile: the parser validates every count, offset and length against
the frame's own size before using it, allocates only in proportion to the input, and throws only `Mcv2Exception`.
Property tests (jqwik, `propertyTest`) and coverage-guided fuzzing (Jazzer, `fuzzTest`) run over mutated conformance
streams, and a security review of the whole diff is in the report.

## Handover notes

- The conformance fixtures are in `mcav-common/src/test/resources/me/brandonli/mcav/media/mcv2/conformance`; the
  scripts that produced their digests with the reference decoder are in `tools/mcv2/` and take the gpu-codec checkout
  as an argument.
- `mcav-overhaul-handover.md` is not edited by this work (mcav-features deletes it); notes belong here.
