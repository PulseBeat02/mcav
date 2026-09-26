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
  min(4, blocks), at most 8), the stream id, the encoder settings, and the page frames' outline colour.
- **`Mcv2Screen`** spawns the hidden, glowing, invulnerable, fixed item frames that hold the page maps (slot
  (column + row) mod slots, so every slot is spread over the wall), shows them per player with the team packet, and
  sends the anchor patches.
- **`Mcv2Viewers`** follows each player's resource-pack status (`PlayerResourcePackStatusEvent`: requested, loaded,
  refused) and forgets players who quit.
- **`Mcv2Channel`** shows the screen to a viewer whose pack loaded (on the main thread) before that viewer receives
  frames, starts every new viewer on a keyframe, and sends each frame's pages with the existing
  `MapPacketFactory` path as **one bundle per frame**, so all pages of a frame arrive together. A frame with more
  pages than the screen has slots is not sent, and the next frame is a keyframe. Each viewer receives the stream
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
its default level, inflated as the client does): **`live` 1080p60 5.38 -> 3.50 Mbit/s (-34.9%), `ship` 1080p30 3.40 ->
2.19 (-35.5%)**, for 2.0% and 1.3% of a core per viewer on the server (331 and 440 us of deflate per frame) and 55-76 us
of inflate per frame on the client. The TCP payload measured on the lab's far listener agrees (3.4-3.6 Mbit/s for
`live`). No compression threshold is recommended: skipping the map packets would give up a third of the bandwidth to
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
With the final pack the live stream costs 8.0 ms per new frame on the UHD 630 and low_bandwidth 8.3 ms.

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

**Does 1080p60 fit the UHD 630?** The chain now takes 7.9-8.8 ms of a 16.7 ms frame when every rendered frame brings a
new video frame, leaving about 8 ms for Minecraft's own rendering, which at 1080p on a UHD 630 usually needs more:
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

| link | live 1080p60, backpressure on: frames held, game round trip p95 | backpressure off |
| --- | --- | --- |
| nearby (15 ms, no loss) | 0%, 36 ms (game alone 35) | 0%, 37 ms |
| other continent (100 ms ±10) | 6.6%, 239 ms (game alone 217) | 0%, 259 ms, max 759 |
| lossy far (100 ms ±20, 1% loss) | 7.5%, 412 ms (game alone 335) | 0%, **1,572 ms, max 3.2 s, backlog 6 MB** |
| thin (40 ms, 0.2% loss, 6 Mbit/s) | 8.2%, 182 ms (game alone 90) | 0%, 319 ms, max 1.2 s |

On the lossy link CUBIC instead of BBR delivered only 19% of the frames (BBR 92.5%). A stream faster than the link (the
keyframe-reference variant, 9.5 Mbit/s, on 6 Mbit/s) keeps the server's backlog bounded but not what TCP already has in
flight: game packets then waited seconds; bounding in-flight bytes as well is a next step.

**For server owners:** a viewer needs about **3.6 Mbit/s for `live` 1080p60 and 2.3 Mbit/s for `ship` 1080p30** after
the game's compression, with headroom; run the server with BBR (`net.ipv4.tcp_congestion_control=bbr`); keep
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
first the frame rate (every second, third, fourth or sixth frame of the video, not below 10 fps), then a smaller video
size the screen offers, then the dithered maps every other viewer sees, which need no encoder (the page frames are
removed, so a viewer with the pack sees the dithered wall). It climbs back when a rung above has been predicted to fit
in 70% of its frame time for five seconds, keeps off a rung it had to leave for ten seconds (twice as long each time it
has to leave it again, up to ten minutes), and from the dithered maps tries encoding again after 30 seconds (twice as
long after every failed try). Each step is logged, a step down as a warning, with the numbers that decided it - for
example `MCV2 screen steps down to 1920x1080 at 30 fps: encoding 1920x1080 takes 25.0 ms per frame, more than the 16.7
ms a frame has at 60 fps with the encoder threads it has` - and the sandbox sends it to whoever started the screen.

**Measured** (Temurin 25; details in the report's SERVER VIABILITY section). The server's tick with live 1080p
screens encoding in the default budget: TPS 20.0, MSPT p95 0.55 ms without a screen, 0.63 with one, 0.83 with two
(all 12 processors: 1.08 and 2.21). Encode time of `live` per frame (mean / p95 ms) by encoder threads, verified as a
screen encodes:

| source | 1 thread | 2 threads | 3 threads | 4 threads | 6 threads | 10 threads | CPU ms per frame (1 thread) |
| --- | --- | --- | --- | --- | --- | --- | ---: |
| 1920x1080 at 60 fps | 100.0 / 118.2 (104) | 55.7 / 65.7 (113) | 40.2 / 48.8 (117) | 33.8 / 44.5 (127) | 26.4 / 32.2 (143) | 22.9 / 29.3 (176) | 104 |
| 1920x1080 at 30 fps | 111.5 / 131.8 (114) | 62.1 / 73.3 (125) | 45.4 / 58.1 (130) | 35.7 / 45.2 (133) | 29.3 / 34.4 (156) | 27.5 / 36.1 (197) | 114 |
| 1280x720 at 60 fps | 51.6 / 62.4 (57) | 31.7 / 45.1 (66) | 22.2 / 30.1 (70) | 17.7 / 23.9 (71) | 14.4 / 17.5 (79) | 12.5 / 15.8 (93) | 57 |
| 1280x720 at 30 fps | 57.7 / 74.7 (59) | 30.7 / 38.0 (66) | 27.2 / 35.2 (82) | 23.8 / 35.2 (86) | 16.2 / 20.9 (86) | 13.8 / 17.7 (103) | 59 |

**Cores needed ~= CPU-ms x fps / 1000** (1080p: 104 ms per frame on one thread, so ~6.3 cores for 60 fps and 3.1 for
30; 720p: 57 ms). What a server encodes live with the default budget (half its processors), the pacer stepping down to
fit:

| server | default encoder threads | 1080p (60 fps source) | 1080p (30 fps source) | 720p (60 fps source) | 720p (30 fps source) |
| --- | ---: | --- | --- | --- | --- |
| 2 cores | 1 | 10 fps | 9 fps | 19 fps | 17 fps |
| 4 cores | 2 | 18 fps | 16 fps | 32 fps | 30 fps (full rate) |
| 6 cores | 3 | 25 fps | 22 fps | 45 fps | 30 fps (full rate) |
| 8 cores | 4 | 30 fps | 28 fps | 56 fps | 30 fps (full rate) |
| 12 cores | 6 | 38 fps | 30 fps (full rate) | 60 fps (full rate) | 30 fps (full rate) |
| 20 cores | 10 | 44 fps | 30 fps (full rate) | 60 fps (full rate) | 30 fps (full rate) |

A 2-core server should pre-encode (`ship`: a minute of 1080p30 takes 57 minutes on 2 threads, 26 on 4; `live` at 30
fps runs in real time on 4 threads). ARM64 hosts are untested (no ARM machine here); the encoder is plain Java.

**Pre-encoding** is the path for a server too small to encode live: `Mcv2FileEncoder` decodes a video file with FFmpeg
and encodes it frame by frame inside a budget into a stream file; the sandbox's `/mcav mcv2 encode <file> <output>
<resolution> <profile>` runs it on a thread of its own in the shared budget, never on the server tick, tells its
progress every thirty seconds, and `/mcav mcv2 cancel` stops it; `/mcav mcv2 play` and `stream` play the result.

## 12. LIVE 1080p60: the `live` profile

A separate profile beside `ship` and `low_bandwidth` (which stay byte-identical to the reference), for sources that
play while they are encoded. The same pack decodes it; no format change.

**The profile** (`EncoderSettings.LIVE`, `LiveSearch.LIVE`): the shipped lambda 65.26, a keyframe every 120 frames (2 s
at 60 fps), scene cut at a mean absolute luma change of 45 after prediction, P frames predicting from the previous frame;
**one trial** instead of the reference's four (the global vector, zero or the projection estimate, chosen before the
search by what SKIP would cost with each on a 1-in-16 sample, `LiveAnalysis`; RGB565 endpoints); the tree searched **from
the top**: a block whose SKIP costs at most **26.5 lambda** is SKIP without a search (the largest threshold proven never to
change a decision), a 32-pixel block is split only above **150 lambda** where the previous frame split that superblock and
above **450 lambda** where it did not (the steady split), a 16-pixel block above **300 lambda**, down to 8 pixels; P-frame
leaves are local **motion** (a diamond seeded from the previous frame's 8x8 vectors, searched down to 16-pixel blocks,
smaller blocks inherit their parent's vector), **palette**, one **compact** class (`GRID4_N4_Y`) at the quantizer lambda
suggests, and **pattern**; keyframes try every intra mode; palettes and intra grids use the cheaper fits (`FastFits`).
The early-exit thresholds have property tests (monotonic in lambda; SKIP exactly at or below the threshold,
`LiveSearchPropertyTest`) and the profile's output is pinned by a digest on a small scene (`LiveEncoderTest`).
**Verification** is on by default: the tree the bytes describe, and the decoder's picture equal to the one the search
assembled. An optional **frame budget** (`Mcv2Encoder.setFrameBudget`) ends a frame that runs long by giving the
superblocks not yet searched their cheapest choice (SKIP, or one colour in a keyframe); it is off by default because the
output then depends on the machine's speed.

**Keyframes, scene cuts and resync.** A keyframe (every 2 s, or at a scene cut) runs the full intra search and costs
about as much as a P frame; the slowest frames of 600 are 113-176 ms on a quiet machine, 55-64 ms with a 33 ms frame
budget. No intra refresh: a P frame that refreshes part of the picture still predicts the rest from the frame before, so
it cannot let a viewer back in. **A viewer who fell behind** (its backlog over the limit, §10) or starts watching is sent
nothing more until the next keyframe - at most 2 s at 60 fps - and from it every frame; its client holds the last picture
it decoded meanwhile.

**Measured** (Temurin 25, 12 threads, verify on, the host quiet; the report's LIVE 1080p60 section has every run and the
lever table): on the 1080p60 proxy 22.1 ms per frame mean, **26.5 ms p95** (20.0 ms p95 without verification), on the
high-motion gameplay clip 48.0 / 66.7 ms; **the 16 ms p95 target is not met** on this 6-core machine. Quality against
`ship` on the 1080p60 proxy (600 frames, five lambdas, VMAF): **-14.8% map rate, -13.8% after compression at equal VMAF
mean** (-4.7% / -4.0% at equal VMAF min); at the default lambda VMAF 79.19 mean / 69.80 min against ship's 77.78 / 70.12.
Decode on the Intel UHD 630: 8.0 ms per new frame. Transport: 60 frames a second leave the server off its 20 ticks
(9,619 frames to one viewer in 160 s), 5.4 Mbit/s of map packets, 3.5 after compression.

## Handover notes

- The conformance fixtures are in `mcav-common/src/test/resources/me/brandonli/mcav/media/mcv2/conformance`; the
  scripts that produced their digests with the reference decoder are in `tools/mcv2/` and take the gpu-codec checkout
  as an argument.
- `mcav-overhaul-handover.md` is not edited by this work (mcav-features deletes it); notes belong here.
