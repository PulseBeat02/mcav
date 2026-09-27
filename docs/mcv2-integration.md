# MCV2 in mcav: integration design

Status: **design, written before the integration code** (2026-09-25), updated as evidence arrived and as built. The decisions
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
  on which the Java decoder and the reference knowingly disagree: the reference decodes MCV1, modes 21/22, and flag 256
  with mode 23 on derived-offset frames, and ignores flag 256 on stored-index frames (table in
  [mcv2-format.md](mcv2-format.md), section 1).
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
| frame parser and validator, decoder, reconstruction kernels, residual books, receiver state machine | `mcav-bukkit` | `me.brandonli.mcav.bukkit.media.mcv2` |
| transport pages, CRC, page assembler, map alphabet | `mcav-bukkit` | `me.brandonli.mcav.bukkit.media.mcv2.transport` |
| encoder, tree serializer, fits, motion search, profiles, Java and native kernels | `mcav-bukkit` | `me.brandonli.mcav.bukkit.media.mcv2.encode` |
| video result step, map packets, player sessions, configuration, resource pack generation | `mcav-bukkit` | `me.brandonli.mcav.bukkit.media.mcv2` |
| demo command | `sandbox/plugin` | |

MCV2 is a Bukkit feature, so all of it lives in `mcav-bukkit`: the Java code and its tests, the residual books and
fitting matrices, the native kernels' sources (`src/main/native/mcv2`) and their six committed libraries, the native
tests (`src/test/native/mcv2`), the fuzz inputs and the Gradle tasks that rebuild and format the kernels. `mcav-common`
holds nothing of it.

The core is pure Java with no Python; the live searches can run their pixel kernels in an optional native library,
which computes exactly what the Java kernels compute (§13). Static data it needs is committed as checked resources:
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
- **GOP parallelism** for files was measured and not built: at the budgets a pre-encode gets on a server (2-4 threads),
  block parallelism already keeps every thread busy (CPU time / wall time / threads = 1.06 and 1.00), and a GOP run
  beside another would move keyframes (a scene cut depends on the reconstruction), so the stream would no longer be
  byte-identical to a sequential encode.
- **Measurement:** warm JIT (the first frames of a run are discarded), best of three runs, mean, p50 and p95 ms/frame,
  CPU and allocation per frame, thread scaling at 1, 2, 4, 8 and 12 threads, and the machine load and running Gradle
  workers recorded, on Temurin 25. The harness is `tools/mcv2/Mcv2Bench.java`; it is not part of CI. Measured: `ship`
  570 ms and `low_bandwidth` 463 ms per 1080p30 frame on 12 threads (1,899 ms on 2, 878 on 4); the live profile is
  §12.
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

As built in `mcav-bukkit/src/main/resources/mcav/mcv2/pack` and assembled by `Mcv2Pack` (pack format 88, Minecraft
26.2). The pass sources are fixed; what depends on the screen is generated: the video size and page slots, the stream
id, the page frames' outline colour, the transport alphabet (the RGB of map colours 4..67 from the server's own
`MapColor` table, which is the client's) and the residual books (from the bytes the Java decoder uses).

- **Transport strip.** `core/text.vsh` recognises a page map by its MCP1 header and an anchor map by its eight-symbol
  signature (21, 3, 58, 44, 9, 37, 60, 17); every other map and all other text (signs, names, GUI, see-through and
  grayscale variants) is drawn exactly as by vanilla. A page quad moves to the rows of the slot its header names:
  slot p starts p·R rows from the top of the screen, R = ceil(4,096 / screen width), and `core/text.fsh` packs four
  six-bit symbols into the three bytes of one pixel, so a page needs 4,096 pixels (three rows at 1920 wide). The
  anchors' descriptor row follows the slots, so the strip is SLOTS·R + 1 rows (13 at 1920 wide with four slots). Alpha
  is 1, so the `TRANSLUCENT` blend writes the bytes unchanged.
- **Anchors.** The screen's own item frames (the wall's maps) carry small anchor patches in their top map rows: the
  signature, the frame's column and row, the screen's size in blocks, its facing and a checksum. The vertex shader of
  any visible anchor writes the screen's corner, right and down vectors in view space and the projection matrix into
  the descriptor row. The matrix leaves the vertex shader as four `flat vec4` varyings: a `flat mat4` varying crashes
  Mesa llvmpipe's shader JIT (found in-game, reduced offline to that one declaration).
- **Post chain** (`post_effect/entity_outline.json`; the vanilla outline passes run after ours, unchanged):
  1. `mcv2_bytes`: strip → the frame's bytes (128 wide), 2. `mcv2_crc`: the CRC of every 192-byte chunk of every
  slot, one fragment per chunk, 3. `mcv2_pages`: every slot's header, frame id, page index, and its CRC32 chained from
  the chunks, 4. `mcv2_status`: the decision for this client frame, 5. `mcv2_resolve`: one fragment per 8x8 cell
  resolves the cell's leaf once - the frame's header checks and gpu-codec's descriptor, split and payload-cursor
  walk - into a cells target (one texel: the descriptor word and the leaf size; local motion unpacked to its two
  bytes) with a row of frame facts, 6. `mcv2_decode` (with its own vertex shader, which reads the status and the frame
  facts once for all pixels): gpu-codec's reconstruction (`mcvideo_codec.glsl` at the pinned commit, split into
  `mcvideoFrame`, `mcvideoResolve` and `mcvideoLeaf` with its arithmetic unchanged) of every pixel from its cell's
  leaf, with a short path for SKIP and local motion, 7. blit → persistent `mcv2_previous`, 8. `mcv2_keyframe` + blit →
  persistent `mcv2_key` (a decoded keyframe replaces it), 9. `mcv2_state` + blit → persistent state (shown flag, last
  id, key id, decoded-frame counter), 10. `mcv2_view`: the anchor descriptor's 28 floats and the box of pixels the
  screen can cover, once per frame, 11. `mcv2_screen` (with its own vertex shader, which passes the geometry and the
  projection as flat varyings) + blit → main: ray-cast every pixel in the box onto the screen plane, depth-test
  against the scene, take the picture's pixel, and cover the strip with the scene row below it, 12. `mcv2_outline` +
  blit: remove the page frames' outline colour from the outline target.
- **Two references, one pipeline.** A frame is decoded when every page of it is valid, it is newer than the last
  decoded frame, and it is a keyframe or predicts from the last decoded frame (`mcv2_previous`) or from the last
  keyframe (`mcv2_key`). A keyframe is accepted whenever its id differs from the last decoded id, so a stream that
  restarts (a playlist loop, a server restart) is not refused as older. This is what lets the server choose model
  A, B or D per screen with the same pack (§4).
- **Trigger and outline colour.** The chain runs only while a glowing entity is drawn. The page frames hide two blocks
  behind the wall, glow on the team `mcav_mcv2`, and are shown only to viewers whose pack loaded. Their colour
  (default `DARK_PURPLE`) is removed from the outline target by the last pass, so no glow is ever visible. **Black is
  rejected**: in 26.2 an outline colour of 0 is `EntityRenderState.NO_OUTLINE`, so the chain would never run (found
  in-game).
- **Debug view** (`-Dmcav.mcv2.debugView=true` on the server, baked into the pack): the decoded picture is also drawn
  one to one below the strip, and to its right one square per page slot (green: a valid page, red: none), one for
  this client frame's decision (green: decoded, blue: nothing new, red: a frame that cannot be decoded) and four grey
  squares for the bytes of the decoded-frame counter. The in-game conformance test captures this view.
- **Lighting.** The picture is drawn at full brightness, like a map in a glow item frame, while ordinary maps darken
  at night: the post chain has no world light at the wall. The anchor's vertex shader does have the frame's light
  (`UV2`, `Sampler2`), so a lit screen is possible by carrying it in the descriptor row; not done.
- **Resource reloads** drop persistent targets; the picture returns with the next keyframe (at most the key interval,
  2 s for the shipped profiles).
- **Known limits.** Iris/Sodium shader pipelines, other packs overriding `core/text` or `entity_outline.json`, and the
  26.2 Vulkan backend are outside what was tested; Fabulous graphics composites translucency after our pass. One
  MCV2 screen per client at a time. Seen from behind the wall, the page frames show a map item for a page map the
  client has no data for yet (vanilla draws the item when a map id has no data), which is cosmetic.

### 5.1 Verified on the real client (E3, 2026-09-25)

Headless 26.2 client (Mesa llvmpipe, 1920x1080, display :103), Paper 26.2 with the sandbox plugin built from this
branch, the pack served on the game port and auto-accepted. Three 30-frame 768x384 streams cut from the frontier's
1080p30 source and encoded by the Java encoder with the ship lambda, one per model (A previous frame, B last keyframe,
D all-intra), played on a 6x3 wall with `/mcav mcv2 play` at 40 ticks (two seconds) per frame and captured with ffmpeg
`x11grab` at 2 fps for 70 s. Every capture of the debug view was compared with the reference decoder's pictures
(`tools/mcv2/capture_check.py`):

| stream | captures | exact captures | frames seen exactly | PSNR | SSIM |
|---|---:|---:|---:|---:|---:|
| A previous frame (first run) | 140 | 140 | 17 of 30 | inf | 1.0 |
| A previous frame (rerun) | 140 | 140 | 30 of 30 | inf | 1.0 |
| B last keyframe | 140 | 140 | 30 of 30 | inf | 1.0 |
| D all-intra | 140 | 140 | 30 of 30 | inf | 1.0 |

All 560 captured pictures equal a reference picture byte for byte (the first run started capturing after frames 2-14
had played; its captures are still all exact). VMAF of the captures is 97.428, which is libvmaf's score for identical
pictures without motion between compared pictures (the reference scored against itself gives 97.428 on its first
frame). Raw strip bytes captured from the screen decode to a valid page (MCP1 header, CRC32 correct), so transport
through the real client is byte-exact. The client log has no shader errors or warnings (its only GL error is Xvfb's missing cursor shape). Ordinary dithered maps render
normally beside the MCV2 screen, and a screen rebuilt by `/mcav screen` faces the player (the defect 1 fix,
`e16ab5d0`). A second opinion on four screenshots (agy) confirmed the seamless wall and the untouched ordinary maps;
its two geometry objections were checked and refuted: gold blocks placed directly above the wall sit flush on the
picture's top edge, and the picture's framing on the wall matches the debug view to one pixel.

## 6. Server integration

- **`Mcv2Configuration`** (builder, in the style of `MapConfiguration`): viewers, the wall's top-left block and
  facing, the first map id and size in blocks (at most 63 on a side), the video size (default 128 pixels per block),
  the first page map id (default 2,000,000,000, far from any world's maps) and the page slots (default
  min(8, blocks), at most 8: 98 KB a frame), the stream id, the encoder settings, and the page frames' outline colour.
- **`Mcv2Screen`** spawns the hidden, glowing, invulnerable, fixed item frames that hold the page maps (slot
  (column + row) mod slots, so every slot is spread over the wall), shows them per player with the team packet, and
  sends the anchor patches.
- **`Mcv2Viewers`** follows each player's resource-pack status (`PlayerResourcePackStatusEvent`: requested, loaded,
  refused) and forgets players who quit.
- **`Mcv2Channel`** shows the screen to a viewer whose pack loaded (on the main thread) before that viewer receives
  frames, starts every new viewer on a keyframe, and sends each frame's pages with the existing
  `MapPacketFactory` path as **one bundle per frame**, so all pages of a frame arrive together. **Every frame fits the
  screen's slots**: the screen gives its encoder the slots' capacity (12,256 bytes a slot, 98 KB with the default
  eight; `Mcv2Encoder.setFrameLimit`), and a live frame that would take more is searched again at twice the lambda, up
  to four times. A live gameplay keyframe at 1080p takes ~150 KB at the default lambda, P frames 46 KB on average at
  60 fps, so without the bound such a stream would never show; with four slots even its P frames would be searched
  twice. A frame that still has more pages than the screen has slots is not sent, and the next frame is a keyframe. Each viewer receives the stream
  through its own **`Mcv2Link`** (§10): a frame only when the viewer can decode it and its connection's unwritten
  video is within the backlog limit.
- **`Mcv2Result`** is the video filter: it resizes each frame to the video size, hands the newest frame to a dedicated
  encoder thread (frames that arrive while it works replace each other: the previous-frame reference allows skipping
  source frames), which hands each encoded frame to a sender thread (one frame queued, in order), and gives players
  without the pack the dithered maps of the same wall through `CompressedMapResult`, so nobody sees a screen their
  client cannot show.
- **`Mcv2Pack`** builds the pack through `SimpleResourcePack` (which gained generated entries) and serves it through
  the existing `PackHosting` strategies; its description and `mcav_mcv2.json` name the codec, the gpu-codec commit,
  the profile and the page geometry.
- **Sandbox commands.** `/mcav video mcv2 <players> <player> <audio> <resolution> <blocks> <mapId> <profile>
  <dithering> <flags> <mrl>` plays media with an encoder profile (`ship`, `low`, `keyframe` = model B, `intra` = model
  D, `live`, `live_keyframe`), offering the pack to the selected players. `/mcav mcv2 play <players> <blocks> <mapId>
  <ticks> <file>` loops a pre-encoded stream (u32 little-endian length + frame, the gpu-codec archive layout) every few
  ticks, `/mcav mcv2 stream ... <fps> <file>` at a frame rate from its own thread, and `/mcav mcv2 stop` stops it;
  `/mcav mcv2 encode <video> <output> <resolution> <profile>` pre-encodes a file in the shared encoder budget and
  `/mcav mcv2 cancel` stops that. Stream files live in `plugins/MCAV/mcv2`: a name that leads out of that folder is
  refused, and only a regular file of at most 1 GiB is read. How to test all this on your own client:
  `docs/mcv2-testing.md`.

## 7. Transport and wire accounting

Each frame is split into pages of 16,384 six-bit symbols (12,256 payload bytes after a 32-byte header with a CRC32).
A page is sent as whole 128-symbol map rows, so the charged map rate is `rows * 128 + 18` bytes per page, which is the
frontier's accounting. Minecraft compresses packets with zlib; because map bytes carry at most six bits each, it
saves about a third, measured on the map packets mcav sends (every page as its map-data packet, deflated as Paper does at
its default level, inflated as the client does): **the first `live` profile at 1080p60 5.38 -> 3.50 Mbit/s (-34.9%),
`ship` 1080p30 3.40 -> 2.19 (-35.5%)**, for 2.0% and 1.3% of a core per viewer on the server (331 and 440 us of deflate
per frame) and 55-76 us of inflate per frame on the client. The TCP payload measured on the lab's far listener agrees (3.4-3.6 Mbit/s for
that `live` stream, the first live profile's). The shipped `live` at its default lambda 72 and 1080p30: **2.85 -> 1.86
Mbit/s on the 1080p30 proxy (-35%) and 27.7 -> 17.0 on real gameplay (-39%)** (600 frames each, the same compression
model). No compression threshold is recommended: skipping the map packets would give up a third of the bandwidth to
save 1-2% of a core per viewer.

## 8. Hostile input

Every field of a frame or page is treated as hostile: the parser validates every count, offset and length against
the frame's own size before using it, allocates in proportion to the input and to the header's dimensions (at most
16,384 root entries, for a 4096x4096 frame), and throws only `Mcv2Exception`.
Property tests (jqwik, `propertyTest`) run over mutated frames of every index form, random frames behind a plausible
header, and pages in any order; coverage-guided fuzzing (Jazzer, `fuzzTest`) covers the frame parser and decoder, the page
checks (CRC included) and the page assembler, seeded from the committed edge streams; `tools/mcv2/differential.py`
compares the decoder with the reference on generated streams outside the build (24,864 frames, no disagreement). The
security review of the whole diff is in the report.

## 9. Decoder speed

The resource pack's chain measured pass by pass with GPU timer queries (`GL_TIME_ELAPSED`,
`tools/mcv2/shader_timing.py`), with the harness running the pack's own `entity_outline.json` pass for pass, a 6x3
screen three blocks in front of the camera (57% of the view) so the ray cast runs as it does while a player watches,
and the GPU kept busy between chains as a rendering client keeps it. Per 1080p frame, ship stream (mcav's encoder,
the shipped lambda), warm:

| pass (ms), Intel UHD 630, EGL | original | now |
|---|---:|---:|
| pages CRC (`mcv2_crc` + `mcv2_pages`) | 5.71 | 0.25 |
| resolve (new) | - | 0.33 |
| decode, new P frame | 41.56 | 2.14 |
| decode, keyframe | 52.56 | 3.1 |
| decode without new video (a copy) | 1.11 | 0.46 |
| screen (`mcv2_view` + `mcv2_screen`) | 11.50 | 1.88 |
| the blits, keyframe, state and outline passes | 2.8 | 2.8 |
| **chain, new P frame** | **61.5** | **7.9** |
| **chain, new keyframe** | **77.4** | **8.8** |
| **chain, rendered frame without new video** | **21.7** | **6.6** |

On Mesa llvmpipe (the headless client's renderer; CPU, measured under a machine load of 10-17, so noisier): new P
frame 262 -> 68 ms, keyframe 348 -> 36 ms, a frame without new video 85 -> 51 ms; decode 175 -> 18 ms, screen 54 ->
12 ms. On llvmpipe every full-screen blit costs about 4 ms of CPU, so the chain's structural copies dominate there.
With the final pack the first live profile's stream cost 8.0 ms per new frame on the UHD 630 and low_bandwidth 8.3 ms; the
shipped `live` (§12) costs 7.4 ms on quiet content and 8.7 ms on gameplay.

**Reconciling 15.065 and 27 ms.** gpu-codec's 15.065 ms is its own harness (`scripts/gpu_v2.py`: one decode draw
with uniforms, a 1024-wide byte texture, 30 repeats per frame averaged over 30 frames) on its lambda-44.86 ship
stream, and that harness gives 15.28 ms on the same stream here. The same harness gives 27.16 ms on mcav's ship
stream: that stream is the round-19 format with derived offsets, packed symbols, the two-level walk and endpoint and
selector tables, whose bytes are saved by every pixel recovering its record address with checkpoint popcounts, the
split-prefix walk and the payload-cursor walk, where gpu-codec's stream stores three-byte descriptors. mcav's chain
then added about 55% on top (41.6 ms): the frame facts came from texels at every pixel instead of uniforms. The
resolve pass removes the per-pixel walk altogether: what the derived form still costs is 0.33 ms of resolve against
0.17 ms for the stored-descriptor stream, so **a format change back to stored offsets could buy about 0.16 ms per frame
on the UHD 630 now**; no format change is made.

**When the decode runs.** The chain runs on every rendered frame (the page frames glow on every frame). The decode
proper runs once per video frame, on the first rendered frame whose strip carries a complete new frame; every other
rendered frame runs the chain with a copy in place of the decode - 6.6 ms on the UHD 630, of which the screen ray
cast is 1.9 ms and the chain's own copies about 2.8 ms (each persistent target is updated through a blit, because a
pass cannot write the target it reads).

**Does 1080p60 fit the UHD 630?** The chain now takes 7.4-8.8 ms of a 16.7 ms frame when every rendered frame brings a
new video frame (a 30 fps video brings one every other frame at 60 fps), leaving about 8 ms for Minecraft's own rendering, which at 1080p on a UHD 630 usually needs more:
expect 35-50 fps on that GPU. A GPU about twice as fast (Intel Iris Xe with 80-96 EUs, AMD 680M, or any discrete GPU
since a GTX 1050) runs the chain in under 4 ms and fits 60 fps with room for the game. A client that renders fewer
frames per second than the video has decodes at most one video frame per rendered frame; the others are overwritten
on the page maps before the chain sees them (see §10 for what that does to a previous-frame reference).

**What was measured and kept**, each change output-neutral (the conformance and edge fixtures, the A/B/D clips,
crops, ship, low_bandwidth and live streams exact on the UHD 630 and llvmpipe, drop-every-7th unchanged, the composed
screen byte-identical in four camera views): (1) the per-cell resolve pass; (2) motion bytes unpacked into the cell;
(3) page CRCs in 192-byte chunks, three bytes per fetch, chained with the exact GF(2) advance; (4) a SKIP and
local-motion path that reads the cell and the reference only; (5) the facts all pixels share read once in the passes'
vertex shaders (the Intel compiler reported 776 instructions per SIMD8 thread for the screen pass, most of them
converting 27 RGBA8 texels into floats at every pixel), no `floor` in UNORM-to-byte conversions (exact), and a constant
bytes-target size. **Measured and rejected**: a stored projection inverse (16 more fetches, 13.5 ms instead of 6.8, and
not byte-identical: it rounds differently from the inline inverse) and a vertex-stage inverse (fast, but it moved
the screen's edge by a pixel in grazing views).

## 10. Far viewers

Everything reaches a player over its one Minecraft TCP connection, so a viewer whose connection cannot keep up with the
stream must not hold the other viewers back, nor let a growing backlog of video delay its own game packets. A screen's
stream is encoded once for all its viewers; each viewer receives it through its own **`Mcv2Link`**:

- **Only frames the viewer can decode.** A keyframe, or a P frame whose reference is the last frame or the last keyframe
  that viewer was sent - the two pictures its client holds. A viewer who missed a frame therefore waits for the next
  frame it can decode: the next keyframe when P frames predict from the frame before (the shipped profiles), the next
  frame when they predict from the last keyframe. Its client keeps showing the last picture it decoded; it never
  receives a frame it could not decode, which would only add to its backlog.
- **A bounded backlog.** Mcv2Link counts the video bytes handed to the viewer's connection and not yet written (a Netty
  write listener takes them off); a frame goes out only while the backlog is at most `Mcv2Configuration.backlogLimit`
  (128 KiB by default), or twice that for a keyframe: a keyframe is where a viewer who missed frames starts over, and
  holding one costs every frame up to the next.
- **No backlog hidden in the operating system.** Linux takes up to megabytes of unsent data into a socket's buffer at
  once (measured: 1.2 MB queued in the kernel on a 200 ms link, with the backlog limit seeing nothing), so a viewer
  that starts receiving a screen has its connection's unsent bytes capped with `TCP_NOTSENT_LOWAT`
  (`Mcv2Configuration.unsentLimit`, 32 KiB; on Linux, where Paper uses the epoll transport). Bytes in flight do not
  count, so the cap does not slow a connection down; the rest waits in the connection's own queue, where the backlog
  limit sees it, and a game packet waits behind at most the cap in the system.

**Measured** on four simulated links (the host's netem on the lab server's port, the real 26.2 client, pre-encoded
1080p streams, backpressure on and off; the full table is in the report's FAR VIEWERS section):

| link | the first `live` profile's 1080p60 proxy stream (5.4 Mbit/s of map packets), backpressure on: frames held, game round trip p95 | backpressure off |
| --- | --- | --- |
| nearby (15 ms, no loss) | 0%, 36 ms (game alone 35) | 0%, 37 ms |
| other continent (100 ms ±10) | 6.6%, 239 ms (game alone 217) | 0%, 259 ms, max 759 |
| lossy far (100 ms ±20, 1% loss) | 7.5%, 412 ms (game alone 335) | 0%, **1,572 ms, max 3.2 s, backlog 6 MB** |
| thin (40 ms, 0.2% loss, 6 Mbit/s) | 8.2%, 182 ms (game alone 90) | 0%, 319 ms, max 1.2 s |

On the lossy link CUBIC instead of BBR delivered only 19% of the frames (BBR 92.5%). A stream faster than the link (the
keyframe-reference variant, 9.5 Mbit/s, on 6 Mbit/s) keeps the server's backlog bounded but not what TCP already has in
flight: game packets then waited seconds; bounding in-flight bytes as well is a next step.

**For server owners:** after the game's compression a viewer needs about **1.9 Mbit/s for `live` 1080p30 on quiet
content and 17 Mbit/s on fast gameplay** at the default lambda (8 Mbit/s at lambda 210), and **2.2 Mbit/s for `ship`
1080p30** on quiet content, with headroom; run the server with BBR (`net.ipv4.tcp_congestion_control=bbr`); keep
backpressure on (the default): it costs nothing nearby and keeps a far viewer's game playable.

## 11. Server viability

A Minecraft server is usually a 2-8 core machine or a container with a CPU limit, runs HotSpot (Temurin), and must
keep its own tick at 20 per second. Everything below is measured on Temurin 25.0.4 (C2), the JVM the common server
images ship.

**One encoder budget per server.** `EncoderPool` is the threads MCV2 encoding may use; every screen, and every
pre-encode, of a server shares `EncoderPool.shared()`: half the processors the JVM may use, at least one, unless
`mcv2.encoder-threads` in the sandbox's `config.yml` (1-256, 0 for the default) says otherwise.
`Runtime.availableProcessors()` follows a container's CPU quota and CPU set on JDK 25, so a 4-CPU container gets two
encoder threads on a 64-core host. A whole encode runs inside the budget - its sequential steps too - and the pool never
starts a spare thread beyond its size, not even while a thread waits in a join (maximumPoolSize = the size, a
saturation predicate that lets the waiter block). Two screens share the threads, taking turns frame by frame, instead of
each taking the machine. The threads are daemon threads in a thread group capped at the lowest priority, which
Windows honours; HotSpot on Linux ignores Java priorities unless run as root with `-XX:ThreadPriorityPolicy=1`, so on
Linux the size of the budget is what keeps processors free for the game.

**Adaptive, never overload.** `Mcv2Pacer` watches the encode time of every P frame (keyframes, which come every few
seconds and cost more, are left out) against the time the video gives a frame. When the smoothed time has been over it
for a second, the screen steps down to the first rung that the measured time predicts to fit in 85% of its frame time:
first a faster preset of the ladder (§12: `ship`'s search, `live`, `live-fast`; a faster preset that keeps every frame
only has to keep up, and each is an encoder of its own, whose first frame is a keyframe), then the frame rate (every
second, third, fourth or sixth frame of the video, not below 10 fps), then a smaller video size the screen offers, then
the dithered maps every other viewer sees, which need no encoder (the page frames are removed, so a viewer with the
pack sees the dithered wall). It climbs back when a rung above has been predicted to fit
in 70% of its frame time for five seconds, keeps off a rung it had to leave for ten seconds (twice as long each time it
has to leave it again, up to ten minutes), and from the dithered maps tries encoding again after 30 seconds (twice as
long after every failed try). Each step is logged, a step down as a warning, with the numbers that decided it - for
example `MCV2 screen steps down to 1920x1080 at 30 fps with the live-fast search: encoding 1920x1080 with the live
search takes 36.0 ms per frame, more than the 33.3 ms a frame has at 30 fps with the encoder threads it has` - and the
sandbox sends it to whoever started the screen.

**Measured** (Temurin 25; details in the report's SERVER VIABILITY and LIVE SPEED sections). The server's tick with
live 1080p screens encoding in the default budget: TPS 20.0, MSPT p95 0.55 ms without a screen, 0.63 with one, 0.83
with two (all 12 processors: 1.08 and 2.21; measured with the first `live` profile, whose encoder threads load the
machine the same way). Encode time of `live-fast`, the screens' default, per frame (mean / p95 ms, and CPU ms per
frame) by encoder threads, native AVX2 kernels, verified as a screen encodes, 30 fps sources, 330 frames (30 warm-up
frames left out), the host at load 4-7:

| source | 1 thread | 2 threads | 3 threads | 4 threads | 6 threads | 10 threads | 12 threads |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1920x1080 proxy | 73.1 / 79.7 (79) | 44.0 / 68.3 (84) | 32.9 / 44.2 (101) | 24.8 / 31.0 (96) | 20.4 / 23.8 (113) | 18.3 / 21.2 (144) | 18.1 / 20.6 (149) |
| 1920x1080 gameplay | 109.4 / 135.9 (121) | 62.9 / 97.5 (121) | 44.7 / 59.6 (135) | 35.7 / 48.4 (141) | 27.9 / 36.0 (154) | 24.8 / 31.7 (204) | 24.9 / 31.6 (214) |
| 1280x720 proxy | 37.7 / 47.7 (48) | 22.2 / 31.5 (52) | 17.6 / 26.0 (61) | 13.9 / 17.5 (57) | 11.8 / 13.8 (67) | 12.3 / 17.7 (83) | 10.8 / 14.0 (80) |
| 1280x720 gameplay | 59.9 / 82.1 (73) | 33.9 / 53.9 (76) | 25.1 / 32.8 (86) | 19.7 / 26.8 (80) | 17.0 / 21.4 (94) | 14.8 / 19.1 (112) | 15.1 / 19.0 (116) |

**Cores needed ~= CPU-ms x fps / 1000** with the one-thread CPU time (1080p30: ~2.4 cores of this CPU on quiet
content, ~3.6 on fast gameplay; 720p30: ~1.4 and ~2.2), but a frame also has 9-11 ms of work outside the parallel
search (the verification's decode, the writer, the global motion), so the frame time stops falling past ~6 threads:
the 32 ms p95 of 1080p30 needs 4 threads on quiet content and 10 on gameplay; 720p30 needs 2 and 4. More threads cost
more CPU per frame on shared cores (hyperthreads, memory bandwidth). The frames per second a server encodes live with
the default budget (half its processors, `1000 / mean ms`, at most the source's 30), the pacer stepping to the rungs
that fit:

| server | default encoder threads | 1080p30, quiet content | 1080p30, gameplay | 720p30, quiet content | 720p30, gameplay |
| --- | ---: | --- | --- | --- | --- |
| 2 processors | 1 | 13 fps | 9 fps | 26 fps | 16 fps |
| 4 processors | 2 | 22 fps | 15 fps | 30 fps (full rate) | 29 fps |
| 6 processors | 3 | 30 fps (full rate) | 22 fps | 30 fps (full rate) | 30 fps (full rate) |
| 8 processors | 4 | 30 fps (full rate) | 28 fps | 30 fps (full rate) | 30 fps (full rate) |
| 12 processors | 6 | 30 fps (full rate) | 30 fps (full rate) | 30 fps (full rate) | 30 fps (full rate) |
| 20 processors | 10 | 30 fps (full rate) | 30 fps (full rate) | 30 fps (full rate) | 30 fps (full rate) |

These are the native kernels; where the Java kernels run (`mcv2.native: off`, or a platform without a library, §13)
a live frame costs about 2.7 times the CPU on gameplay and 1.8 times on quiet content (measured on the live search
before the presets of §12: 664 against 249 ms, and 310-333 against 177 ms). A small server should pre-encode (`ship`: a minute of 1080p30
takes 57 minutes on 2 threads, 26 on 4). ARM64 servers are not measured: the AArch64 library passed its kernel tests
under emulation only (§13).

**Pre-encoding** is the path for a server too small to encode live: `Mcv2FileEncoder` decodes a video file with FFmpeg
and encodes it frame by frame inside a budget into a stream file; the sandbox's `/mcav mcv2 encode <file> <output>
<resolution> <profile>` runs it on a thread of its own in the shared budget, never on the server tick, tells its
progress every thirty seconds, and `/mcav mcv2 cancel` stops it; `/mcav mcv2 play` and `stream` play the result.

## 12. LIVE 1080p60: the `live` profile

A separate profile beside `ship` and `low_bandwidth` (which stay byte-identical to the reference), for sources that
play while they are encoded. The same pack decodes it; no format change. The owner's gate (revised 2026-09-26) is
1080p at 30 fps with a p95 under 32 ms per frame, 1080p60 a stretch goal; the quality rule is VMAF mean >= 75 at the
default setting on the 1080p30 source and on real gameplay, and at most 10% more bandwidth than `ship` at equal VMAF.

**The profile** (`EncoderSettings.LIVE`, `LiveSearch.LIVE`): **lambda 72**, a keyframe every 120 frames (4 s at 30 fps),
scene cut at a mean absolute luma change of 45 after prediction, P frames predicting from the previous frame; **one
trial** instead of the reference's four (the global vector, zero or the projection estimate, chosen before the search by
what SKIP would cost with each on a 1-in-16 sample, `LiveAnalysis`; RGB565 endpoints); the tree searched **from the
top**: a block whose SKIP costs at most **26.5 lambda** is SKIP without a search (the largest threshold proven never to
change a decision), a 32-pixel block is split only above **150 lambda** where the previous frame split that superblock
and above **450 lambda** where it did not, a 16-pixel block above **300 lambda**, down to 8 pixels. P-frame leaves are
the modes `ship` uses on real gameplay: local **motion**, **solid** colours, **palettes**, the **2x2 intra grid**, the
**reduced intra grid** (luma 4x4, chroma 1x1), **compact** records and **patterns**. Compact records use three classes
(the luma offset and the two 4-bit 4x4 luma grids, with and without chroma) at **the one quantizer their fitted values
need** (`FIT_ONE`: the finest that holds them unclipped; a compact record's length does not depend on its quantizer, so
a coarser one only adds error). Local motion is a diamond seeded from the previous frame's 8x8 vectors, searched down to
16-pixel blocks, **first on the pictures at half resolution** (`HALF_MOTION`: a half-resolution half pixel is a whole
pixel), then refined at full resolution to half pixels; smaller blocks inherit their parent's vector. Keyframes try
every intra mode; palettes and intra grids use the cheaper fits (`FastFits`); compact records are tried on the closer
of the global and the local prediction only. The early-exit thresholds have property tests (monotonic in lambda; SKIP
exactly at or below the threshold, `LiveSearchPropertyTest`) and the profile's output is pinned by a digest on a small
scene (`LiveEncoderTest`). **Verification** is on by default: the tree the bytes describe, and the decoder's picture
equal to the one the search assembled. An optional **frame budget** (`Mcv2Encoder.setFrameBudget`) ends a frame that runs
long by giving the superblocks not yet searched their cheapest choice (SKIP, or one colour in a keyframe); it is off by
default: it bounds the worst frame, but on busy video it gives up most of the picture, and the output then depends on
the machine's speed.

Why these choices (the report's lever table has every measurement): a candidate set chosen on the SKIP-heavy 1080p60
proxy alone needed 80% more rate than `ship` on real gameplay, whose P frames `ship` codes with solid colours and intra
grids; the owner's levers then took the CPU back down inside the quality cap - the fitted quantizer (lever 5) and the
half-resolution motion search (lever 6), which is what brings gameplay inside the cap; three compact classes instead of
five cost the same rate for 15% less CPU. Rejected by measurement: content-adaptive pre-selection (lever 1, no speed-up),
reusing the previous frame's decision (lever 2, +18% rate on gameplay).

**Lambda 72, raised with the motion** (addendum 13 item 6, `MotionLambda`). 72 is the largest round value that keeps
the 1080p30 proxy at VMAF mean >= 75 (75.7). One lambda cannot also hold fast gameplay near 75 (it scores ~89 at 72
and reaches 76 near 195, where the proxy is at ~60), so a frame's lambda rises with the motion of the source, where
VMAF forgives more: `lambda = base * min(4, max(1, (TI / 4.6) ^ 0.79))`, TI the mean absolute change of the 3x3-blurred
luma of every fourth pixel from the frame before, smoothed over 16 frames and restarted at a scene cut or a size
change. Quiet content (TI under 4.6: the proxy, Sintel) stays at the base; the knee and the exponent are fitted to six
contents (the 1080p30 and 1080p60 proxies, 30 and 60 fps gameplay, a 30 fps dinner scene, Sintel at 24 fps), and each
gameplay capture predicts the other's lambda within 1%. It raises only, never lowers; its property tests hold it
bounded, steady on a steady source, monotone in the motion and restarting at a cut. At the default, gameplay gets a
lambda of 195 on average (154-214 per frame) and scores VMAF 76.1 at 13.1 Mbit/s of map colours instead of 27.6.

**The preset ladder** (addendum 13 item 4, addendum 14 item E). Four presets, each faster than the one above, all
decoded by the same pack: `ship` (the reference's exhaustive search, byte-identical to it), `live` (this section's
search, lambda 72), `adaptive` (`EncoderSettings.LIVE_ADAPTIVE`: `live` on calm pictures and `live-fast`'s search at
lambda 55 once the source moves: its average temporal information, the measure of the motion lambda, above 8 until it
falls below 6; the calm sources measure 1.5-5.9 - the 1080p30 and 1080p60 proxies, Sintel, a dinner scene - the gameplay
captures 7.8-19.2) and `live-fast` (`EncoderSettings.LIVE_FAST`: `LiveSearch.LIVE_FAST`, lambda 55). `live-fast` is `live` with SKIP taken
without a search up to 60 lambda, a 32-pixel block whose superblock the previous frame coded whole split only above
900 lambda and a 16-pixel block above 600, and the motion search starting at half resolution; at lambda 55 it reaches
the VMAF `live` reaches at 72. Each rung's rate against `ship` at equal VMAF mean (600 frames; map / after compression):
`live` -5.1% / -3.4% (1080p30 proxy) and +7.9% / +6.9% (30 fps gameplay), inside its +10%; `live-fast` +17.7% / +18.4%
and +29.4% / +17.1%, inside its +30%. **A screen's default is `live-fast`** (`Mcv2Configuration`): the default for a
source that plays while it is encoded is the slowest rung that meets the 1080p30 gate, else the fastest within +30%;
`live-fast` meets it on quiet content and on gameplay, `live` on quiet content only (below).
A screen that cannot keep up steps **down the ladder first** (`EncoderSettings.faster()`: `ship`'s search to `live`'s,
`live` to `adaptive` and `adaptive` to `live-fast` with the lambda of the live-fast search scaled by 55/72, so the
picture keeps its quality), then the frame rate, then the size, then the dithered maps (`Mcv2Pacer`, §11). **Between
live presets the encoder switches without a keyframe** (`Mcv2Encoder.switchTo`): every live search writes the same
format from the same pictures, so the frames after a switch are P frames any viewer decodes; the screen switches once
the frame in flight is sent. The adaptive profile switches the same way from frame to frame, and its choice depends on
the source alone, so its stream is as deterministic as any other: pinned digests (a calm clip, where it codes as `live`
does, and a fast pan, where it switches) and the pack decoding adaptive and switching streams bit-exactly
(`shader_check.py`). Only a step to or from `ship`'s search starts a new encoder, whose first frame is a keyframe.

**Keyframes, scene cuts and resync.** A keyframe (every 120 frames - 4 s at 30 fps, 2 s at 60 fps - or at a scene
cut) runs the full intra search and costs no more than a P frame: at 1080p60 (live-fast, 12 threads) its search and
write take 14.0 ms against 15.1 on the proxy and 20.1 against 21.7 on gameplay (no motion search), its verification
about 1 ms more on the proxy, and the p95 interval with every keyframe and the frame after it left out is the same to
0.05 ms, so keyframes do not set the p95 (addendum 15, item 2). No intra refresh: a P frame that refreshes part of the
picture still predicts the rest from the frame before, so it cannot let a viewer back in. **A viewer who fell behind**
(its backlog over the limit, §10) or starts watching is sent nothing more until the next keyframe - at most 120 frames
- and from it every frame; its client holds the last picture it decoded meanwhile. Keyframes come from the key
interval, scene cuts, a viewer shown the screen, a frame that could not be sent and a step to or from `ship`'s encoder;
not from a switch between live presets.

**Pipelining.** A screen searches and writes frame N+1 while frame N is verified (`Mcv2Encoder.begin` and `finish`,
one extra frame in flight at most): a frame is sent only once verified, a frame that fails stops the screen as before,
the stream is identical by digest to one encoded a frame at a time, and a keyframe or preset asked for while a frame is
in flight comes with the frame after it (one frame of extra resync latency). At 1080p60 (live-fast, 12 threads,
interleaved runs, host load 24-65 from other work) it sustains 74 frames a second on the proxy against 63 one at a
time, and 52 against 45 on gameplay, at about the same p95 interval (19.9 against 19.3 ms; 30.8 against 29.6): with
frames back to back, frame N's verification (5-7 ms) already runs entirely beside frame N+1's search, so the interval
between finished frames is the search's own time and spread. **Giving the verification precedence** over the search was
tried three ways (addendum 15, item 1): a lane whose urgent loops every search worker helps between items, and the
same with at most three, one or two helpers. The verification got faster (7.0 to 5.3 ms on the proxy), but the search
slowed as much or more and the rate fell 10-20 % (proxy 67, 61, 57 and 59 frames a second against 74) with no better
p95 interval or latency, so none was kept. Two screens on one budget share it evenly either way.

**Measured** (Temurin 25, 12 threads of the i7-8700, native AVX2 kernels, verify on, as a screen encodes, 660 frames
with 60 warm-up frames left out, three runs each; the report's LIVE SPEED section has every run):

| rung | 1080p30 proxy: mean / p95 | 30 fps gameplay: mean / p95 | CPU per frame | VMAF mean / min (proxy; gameplay) | Mbit/s map / zlib (proxy; gameplay) |
| --- | --- | --- | --- | --- | --- |
| `live` | 19.0-19.6 / 21.7-23.2 ms | 27.8-28.6 / 35.1-36.9 ms | 159-160; 242-244 ms | 75.7 / 69.0; 76.1 / 65.2 | 2.80 / 1.83; 13.1 / 8.3 |
| `live-fast` | 17.5-18.1 / 20.3-21.2 ms | 24.0-24.6 / 30.3-31.2 ms | 143-146; 206-211 ms | 76.1 / 71.9; 76.1 / 66.3 | 3.27 / 2.12; 15.3 / 8.7 |

**`live-fast` meets the 32 ms p95 gate on quiet content and on gameplay** (every run; the host carried a load of 4-13
from other work); `live` meets it on quiet content only. About 8 ms of a gameplay frame lies outside the parallel
search - the verification's decode (3.7 ms), the writer (1.5), the global motion (1.1), the tree check (0.7) - which
is why more threads stop helping (the hardware guide is §11). Decode on the Intel UHD 630: 7.4 ms (proxy) and 8.7 ms (gameplay) per new
frame of the first live profile, 5.7-5.9 ms per rendered frame without new video; the pack decodes both rungs'
streams bit-exactly (`shader_check.py`, 60 of 60 frames each, EGL on the UHD 630 and GLX on llvmpipe).

## 13. Native kernels (addendum 13)

The live searches spend most of their time in pixel kernels: reconstructing and scoring each candidate leaf (motion,
solid colour, palette, intra and residual grids, reduced grids, compact records), predicting and searching local
motion, the least-squares and cell-mean fits, palette clustering, and colour conversion. `mcav-bukkit` ships them as a
small C++17 library per platform (`src/main/native/mcv2`), called through the Foreign Function & Memory API (no JNI).
**The native kernels compute exactly what the Java kernels compute** - the Java kernels (`JavaKernels`) are the
oracle, and a stream is byte-identical whichever kernels encoded it - so the decoder, the pack and every pinned digest
are unchanged. The reference's exhaustive search (`ship`, `low_bandwidth`) always runs Java; everything that is not a
pixel kernel (the decisions, the tree, the writer, the verification, the transport) is Java.

**How exactness holds.** One source over a small vector type (`simd.hpp`: lanes of int, float or double) is compiled
once per level - scalar, SSE2, SSE4.1, AVX2 and AVX-512 (x86-64), NEON and SVE at 256 and 512 bits (AArch64) - with
IEEE floating point and no fused multiply-add (`-ffp-contract=off`, never `-ffast-math`) and Java's wrapping integer
arithmetic (`-fwrapv`); every sum is taken in the order Java takes it. The Java side checks every argument before a
call (the library trusts its caller) and passes the Java arrays themselves (`Linker.Option.critical`, no copies).

**Which level runs.** The library tells which levels the CPU runs, and the most preferred of them is used: AVX-512,
then AVX2, SSE4.1, SSE2 on x86-64; SVE 512, then SVE 256, NEON on AArch64. No instruction a CPU lacks runs before the
library has asked:

- x86-64: `cpuid` and `xgetbv` (AVX2 and AVX-512 also need the operating system to save their registers). AVX-512 runs
  only with the Ice Lake set - F, DQ, BW, VL, VBMI, VBMI2, VNNI and BITALG - so never on Skylake-SP or Cascade Lake,
  which slow down at 512 bits, and never on macOS, whose `XCR0` shows the AVX-512 state only once a thread has used it
  (macOS stays on AVX2). Every x86-64 CPU has SSE2.
- AArch64: every CPU has NEON. SVE runs where Linux lists it in the process's `AT_HWCAP`, which Java reads from
  `/proc/self/auxv` and passes in (the library imports nothing, so it cannot ask); then the vector length picks the
  level: 32 bytes SVE 256, 64 bytes SVE 512, and NEON at any other length (the 128-bit SVE of Neoverse N2 or V2 gains
  nothing over NEON). Windows and macOS on ARM run NEON.
- Scalar runs only when asked for: `-Dmcv2.native.level=scalar`. The same property caps the level for a measurement
  (`-Dmcv2.native.level=avx2` on an AVX-512 machine).
- A block narrower than a vector - 8 pixels, and 16 for the colour clustering, which takes two vectors of a row - goes
  from the AVX-512 kernels to the AVX2 ones and from the SVE 512 kernels to the NEON ones, so no kernel reads or writes
  past a row.

**Tests.** In the JVM: every kernel at every level the CPU runs against Java (unit, jqwik 2,000 tries, Jazzer in
`fuzzTest` with the kernels as a differential target), whole encodes byte-identical, the reference conformance and the
live profiles' pinned digests through each level, and the levels the library reports against the features
`/proc/cpuinfo` lists. Standalone (`src/test/native/mcv2/run-native-tests.sh`; the tools are listed at its top):

- every level against scalar under AddressSanitizer and UndefinedBehaviorSanitizer: the x86-64 levels natively and,
  AVX-512 included, under Intel SDE's Ice Lake server; NEON and SVE under qemu-aarch64 on a Cortex-A72 and at SVE
  vector lengths of 16, 32 and 64 bytes; a planted heap overflow must be caught under each emulator;
- llvm-cov coverage of the sources, reported per file;
- the shipped Linux libraries loaded by glibc and by Alpine's musl loader (`ld-musl-*.so.1`), and the level the
  dispatcher takes on each emulated CPU: SDE Sapphire Rapids and Ice Lake server AVX-512, Skylake server and Haswell
  AVX2, Merom (no SSE4.1) and qemu64 SSE2, Cortex-A72 and 128-bit SVE NEON, 256-bit SVE 256, 512-bit SVE 512. Every
  run's digests must be identical, and the JVM tests hold the x86-64 library equal to Java.

Emulators prove correctness only; no speed was measured under one.

**Loading.** The jar holds `natives/<platform>/<library>` next to `Mcv2Natives`, whose SHA-256 is compiled into
`Mcv2Natives.DIGESTS`. The first live encoder extracts the library into the folder the plugin gives
`Mcv2Natives.install` - the sandbox gives `plugins/<plugin>/natives`, never `/tmp`, which hosted servers often mount
without execution - as `mcv2kernels-<sha256>-<library>` (written to a temporary name and moved into place; a file of
that name with the right content is reused), checks it against the compiled-in SHA-256, loads it, checks its ABI
version and logs once which kernels run: `MCV2 kernels: native avx2 (linux-x86_64)`, or `MCV2 kernels: Java, <why>`.
Anything that stops the library - no library for the platform, a checksum, the extraction, the load, the JVM refusing
native access - is logged once as a warning, and the Java kernels run.

**Turning it off.** `mcv2.native: off` in the sandbox's `config.yml` (`auto`, the default, uses the library), or
`-Dmcv2.native=off` on the server's command line, which wins over the configuration. A library that is not there has
the same effect: `MCV2 kernels: Java, no library for <platform>`.

**Native access.** Paper's launcher jar declares `Enable-Native-Access: ALL-UNNAMED` in its manifest, which Java
honours for `java -jar`, so on a Paper server the kernels load without any flag and nothing is printed (checked on Paper
26.2 build 123: `MCV2 kernels: native avx2 (linux-x86_64)` at startup, no warning). Another launcher gets Java 25's
default: the library loads and the JVM prints one warning (`WARNING: A restricted method in
java.lang.foreign.SymbolLookup has been called ...`), which `--enable-native-access=ALL-UNNAMED` silences;
`--illegal-native-access=deny` refuses the load, and the Java kernels run.

**Speed.** On AVX2 the reconstruction kernels run 2-3x faster than Java at 8 pixels and 2.4-4.6x at 32, the motion
prediction and search 2.2-4.6x (its whole-pixel costs as byte differences), the source loading 3-7x, the palette
clustering 4.8-7.5x, the colour clusters 3.8-4.5x, the luma residual 2.6-7.2x, the cell means 1.3-3.0x and the halving
1.3-3.4x (8 to 32 pixels); a call costs some 50-90 ns of checks and arguments before any work (a bare downcall 6 ns).
A wider level is never slower than a narrower one at any block size on this machine (scalar, SSE2, SSE4.1, AVX2), where
lanes do not pay a kernel runs one lane at a time and lets the compiler vectorize it. The whole live encoder spends 2.7x
less CPU on a gameplay frame and 1.8x less on quiet content (the report's LIVE SPEED section has every kernel).

**Platforms.** Six libraries ship; each was tested as far as a machine or an emulator for it was at hand:

| platform | library | levels | tested |
| --- | --- | --- | --- |
| Linux x86-64 | `libmcv2kernels.so`, 213 KB | scalar, SSE2, SSE4.1, AVX2, AVX-512 | every JVM test at scalar, SSE2, SSE4.1 and AVX2 (i7-8700); every level standalone, AVX-512 under Intel SDE; glibc and musl; a Paper server end to end |
| Linux AArch64 | `libmcv2kernels.so`, 122 KB | scalar, NEON, SVE 256, SVE 512 | the standalone tests under qemu-aarch64 (Cortex-A72; SVE at 16, 32 and 64 bytes), glibc and musl, digests equal to the x86-64 library's |
| Windows x86-64 | `mcv2kernels.dll`, 292 KB | scalar, SSE2, SSE4.1, AVX2, AVX-512 | built from the sources tested above; the previous build (scalar, SSE4.1, AVX2) passed the native JVM tests in a Windows VM, which was down for this one |
| Windows AArch64 | `mcv2kernels.dll`, 66 KB | scalar, NEON | built, **not tested** (no machine) |
| macOS x86-64 | `libmcv2kernels.dylib`, 166 KB | scalar, SSE2, SSE4.1, AVX2 | built from the sources tested above; the previous build (scalar, SSE4.1, AVX2) passed the native JVM tests in a macOS VM, which was down for this one |
| macOS AArch64 | `libmcv2kernels.dylib`, 99 KB | scalar, NEON | built, **not tested** (no machine) |

Anything else - another processor, another operating system - runs the Java kernels, which compute the same stream.

**Rebuilding.** The libraries are committed; the normal build needs no C or C++ toolchain.
`./gradlew :mcav-bukkit:buildMcv2Natives -Pmcav.natives=build` rebuilds all six with Zig 0.16.0 (its clang 21.1.0
and linkers; `ZIG=/path/to/zig`, and the script refuses another version) and writes `SHA256SUMS`; the new digests go
into `Mcv2Natives.DIGESTS`, which `Mcv2NativesTest` checks against the resources. The script also writes `SOURCES`,
the SHA-256 of every source file it built from (all of `src/main/native/mcv2` but `.clang-format`), and
`NativeLibrariesTest`, part of the default build, compares it with the sources in the tree: a source changed without
the libraries rebuilt from it fails the build, and so does a library that is not built from the committed sources. The
same test reads every library's headers, with no emulator: its machine (ELF `e_machine`, PE `Machine`, Mach-O
`cputype`), its exports - exactly `mcv2_abi`, `mcv2_cpu_levels` and each kernel of the header's `MCV2_KERNELS` list at
each level its platform dispatches to (x86-64 Linux and Windows: scalar, SSE2, SSE4.1, AVX2, AVX-512; macOS x86-64:
scalar to AVX2; Linux AArch64: scalar, NEON, SVE256, SVE512; Windows and macOS AArch64: scalar, NEON), nothing else
but the two symbols the Mach-O linker adds to every dylib - and, on Linux, no `DT_NEEDED` entry and no undefined
symbol.
`./gradlew :mcav-bukkit:formatMcv2Natives -Pmcav.natives=build` formats the sources with clang-format (LLVM style, 120
columns; opt-in). Every library is reproducible: two builds give the same bytes (`SOURCE_DATE_EPOCH=0` keeps the
link time out of the PE header, and a macOS library is named `@rpath/libmcv2kernels.dylib` rather than the path it was
built at, which its UUID would hash). The Linux libraries import nothing (no `DT_NEEDED`, no undefined symbol), so they
ask nothing of the C library - glibc or musl alike - and have no executable stack; every library is stripped and uses
no C++ runtime. Warnings are errors (`-Wall -Wextra -Werror`).

## Handover notes

- The conformance fixtures are in `mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2/conformance`; the
  scripts that produced their digests with the reference decoder are in `tools/mcv2/` and take the gpu-codec checkout
  as an argument.
- `mcav-overhaul-handover.md` is not edited by this work (mcav-features deletes it); notes belong here.
