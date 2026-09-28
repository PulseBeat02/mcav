---
orphan: true
---

(mcv2-encoder)=
# MCV2 encoder

The encoder is not normative: any stream that [format.md](format.md) accepts is valid, and the decoder, the resource
pack and the transport do not care how it was searched. mcav's encoder (`Mcv2Encoder` in
`me.brandonli.mcav.bukkit.media.mcv2.encode`) has two kinds of search. The **reference search** of the shipped profiles
reproduces the Python reference encoder (`tools/mcv2-reference`, `v2.TreeEncoder`) byte for byte. The **live searches**
are mcav's own, cheaper, for sources that play while they are encoded. How the encoder runs on a server - the shared
budget, the pacer, the page slots - is in [the design doc](../mcv2-integration.md), sections 3, 11 and 12.

## The reference search

For every frame, as `Mcv2Encoder` and the reference do it:

1. **Frame type.** A keyframe on the first frame, every key interval, on a size change, or on a scene cut: a mean
   absolute luma change above the scene threshold after global prediction. Other frames are P frames and predict from
   the previous decoded frame.
2. **Global motion.** One vector for the frame, from phase correlation on a quarter-resolution picture refined at full
   resolution (`GlobalMotion`, `Fft`; the reference's `encoder.estimate_global`).
3. **Trials.** The frame is searched once per trial: the global vector and, when it is not zero, the zero vector, each
   with full and with RGB565 pattern endpoints (round 18) - up to four. Each trial is serialized in the production form,
   and the trial with the lowest distortion plus lambda times its actual bits is kept, the first on a tie.
4. **Blocks.** Every candidate record of every block at 32, 16 and 8 pixels is evaluated against the decoded
   reference: SKIP, local motion (a search within the motion range around the global vector, refined to half pixels,
   stored as immediate motion records, rounds 1, 12 and 19), solid colours, two-colour palettes and their pattern forms
   (round 13's pattern RDO), RGB intra grids of 1, 2, 4 and 8 nodes, reduced-chroma grids, and the compact residual
   classes 0, 1, 2, 3, 4 and 8 with their quantizers. The cost is the squared error in YCoCg weighted 4:1:1 and divided
   by 6, on gamma-coded 8-bit values, plus lambda times the bits the serializer will write for the record (round 8's
   matched cost model). Each trial keeps, per block, the first strictly cheapest candidate.
5. **Tree.** The block tree is chosen bottom-up by the same cost, and written with every index form of the kept rounds:
   short indexes and sparse child quartets, the derived root directory (round 4) and descriptor offsets (round 7),
   packed (mode, quantizer) symbols (round 9), the two-level walk plane (round 10), the per-frame endpoint table
   (round 16), per-size selector tables (round 17) and RGB565 endpoints (round 18); a keyframe's most common solid
   colour becomes the header's default colour. Grids are fitted with the pseudo-inverse of the decoder's bilinear
   interpolation, not by averaging cells, so a fit minimises the error of what the decoder reconstructs.
6. **Verification.** The kept frame is decoded, and only that decoded picture becomes the next frame's reference.

mcav's port differs from the reference only where the reference is not a specification:

- **Parallel and deterministic.** Block evaluation runs on the shared encoder budget's threads (`EncoderPool`). A block's
  result depends only on the previous decoded frame, never on a neighbour, and every reduction runs in a fixed order, so
  the output is the same for any number of threads. 570 ms per 1080p frame on 12 threads of an i7-8700; the Python
  reference takes 172 s.
- **Arithmetic.** The reference computes in numpy float32 and float64; the port reproduces its operations in their
  order, and loads the reference's own float32 pseudo-inverses (`fitting_matrices.bin`) rather than recomputing them.
- **One serializer defect worked around.** The reference's `v2.pack_derived` writes a zero-entry packed-symbol table
  for a frame without descriptors, which its own parser rejects; `FrameWriter` leaves the table out then (tested).
- **Pipelining and bounds.** `begin` searches and writes a frame, `finish` verifies it, so a caller can verify frame N
  while it searches frame N+1. `setFrameLimit` keeps every frame within the bytes a screen's page slots carry.

## The shipped settings

The two shipped profiles are round 19's 1080p30 operating points ([results.md](results.md#what-mcav-ships-and-why));
they differ only in lambda. In the reference's terms (`encoder.Settings`, `v2.TreeSettings`):

| setting | value |
|---|---|
| `lambda_value` | 65.255994022 (`ship`), 137.730758207 (`low_bandwidth`) |
| `block_size`, `grids` | 32; 1, 2, 4, 8 |
| `motion_range`, `half_pixel`, `global_motion`, `compare_global` | 24, true, true, true |
| `palette`, `reduced_chroma`, `sparse`, `default_solid` | true, true, true, true |
| `key_interval`, `scene_threshold` | 60 (two seconds at 30 fps), 45.0 |
| `adaptive`, `short_index`, `sparse_children` | true, true, true |
| `palette_patterns`, `pattern_rdo` | true, true |
| `residual_classes` | 0, 1, 2, 3, 4, 8 |
| `immediate_motion`, `derived_directory`, `derived_offsets`, `matched_cost_model`, `packed_symbols`, `two_level_walk` | true |
| `endpoint_table`, `selector_table`, `endpoint_565` | true, true, true |
| `symbol_bits`, `wire_rdo`, `perceptual` | 6, false, false |

In mcav these are `EncoderSettings.SHIP` and `EncoderSettings.LOW_BANDWIDTH` (`ReferencePolicy.PREVIOUS_FRAME`, no
live search). A screen whose viewers cannot keep the previous frame can predict from the last keyframe instead
(`ReferencePolicy.LAST_KEYFRAME`, +73% rate at matched VMAF) or code every frame on its own (a key interval of 1, +144%)
(design doc §4).

## The live searches

mcav's own, for live sources (streams, the browser, virtual machines). Same format, same decoder; only which candidates
are tried differs, so a live stream spends more bits for the same picture. A live frame is encoded **once**, with the
global vector chosen before the search (zero or the estimate, by what SKIP would cost with each on a sample) and one
endpoint precision, and its tree is searched **from the top**: a block whose SKIP costs at most 26.5 lambda is SKIP
without a search (a bound that never changes a decision, since every other leaf costs at least 26.5 bits), and a block
is split only when its best leaf costs more than a threshold. P frames try the leaf modes the reference picks on real
gameplay (motion, solid, palettes, the 2x2 and reduced intra grids, compact records, patterns), compact records use
three classes at the one quantizer their fitted values need, local motion is a diamond seeded from the previous frame's
vectors and searched at half resolution first, and palettes and grids use cheaper fits (`FastFits`).

| preset | `EncoderSettings` | search | lambda | key interval |
|---|---|---|---:|---:|
| `live` (default) | `LIVE` | `LiveSearch.LIVE` | 72, raised with the motion | 120 |
| `adaptive` | `LIVE_ADAPTIVE` | `LIVE` on calm pictures, `LIVE_FAST` in motion (average temporal information above 8, until below 6) | 72 / 55 | 120 |
| `live-fast` | `LIVE_FAST` | `LiveSearch.LIVE_FAST`: SKIP without a search up to 60 lambda, split thresholds doubled, motion searched at a quarter resolution first | 55, raised with the motion | 120 |

**Lambda rises with motion** (`MotionLambda`): `lambda = base * min(4, max(1, (TI / 4.6) ^ 0.79))`, where TI is the mean
absolute change of the 3x3-blurred luma of every fourth pixel from the previous frame, smoothed over 16 frames and
restarted at a scene cut. VMAF forgives more error in motion; quiet content keeps the base lambda.

**Native kernels.** A live search's pixel kernels (the candidates' reconstructions and errors, the grid fits and palette
clustering, motion prediction and the seeded motion search) can run in a C++17 library called through
the Foreign Function and Memory API (`NativeKernels`), at the best SIMD level the processor has (SSE2, SSE4.1, AVX2 or
AVX-512 on x86-64; NEON or SVE on AArch64), and compute exactly what the Java kernels (`JavaKernels`) compute, so the
output does not depend on which ran. Six libraries are committed (Linux, macOS and Windows on x86-64 and AArch64:
1,130,393 bytes together) with their `SHA256SUMS` and the `SOURCES` digest of the C++ they were built from; the
loader (`Mcv2Natives`) checks the hash, logs the level at startup (`MCV2 kernels: native avx2 (linux-x86_64)`) and
falls back to Java when there is no library or `mcv2.native` is `off`. Design doc §13 has the measurements and the
platform matrix.

The measured speed and quality of each preset are on the [results page](results.md#what-mcav-ships-and-why); why each
search choice was made, with the rejected alternatives, is in design doc §12.

## Tables and their provenance

Everything the encoder and the decoder load at run time, with where it comes from. Each is checked against its SHA-256
when loaded.

| table | in mcav | from the reference (commit `85445433`) | regenerate or check |
|---|---|---|---|
| residual books, 2,048 bytes, SHA-256 `1737842f...e788`: a 64x16 signed-byte VQ book, then two 64x8 product books | `mcav-bukkit/src/main/resources/me/brandonli/mcav/bukkit/media/mcv2/residual_books.bin`, `ResidualBooks`; the pack's codebook texture is generated from the same bytes | `research_artifacts/residual_books.bin`, trained offline once (`residual_books.json`: seed 19781, procedural sources, 18,000 samples, 16 iterations) and immutable since | `tools/mcv2/tables.py --check` |
| grid fitting matrices, 3,360 bytes, SHA-256 `b575fd1e...af0e`: the float32 pseudo-inverses for block sizes 8, 16, 32 and grids 1, 2, 4, 8 | `fitting_matrices.bin` beside the books, `Fits` | computed by `pixels.fitting_matrix` (numpy's `pinv` of the bilinear interpolation matrix) | `tools/mcv2/tables.py` writes it, byte for byte with numpy 2.5.3 |
| format constants: modes, flags, record sizes, the compact classes, the pattern orientations | `Mcv2Format`, `CompactRecord`, `PatternRecord` | `format.py`, `v2.py`, `compact.py`, `pattern.py` | the conformance tests ([conformance.md](conformance.md)) |
| the map alphabet: the 64 map colours that carry six-bit symbols | `transport/MapAlphabet` | not the reference's: chosen from Minecraft's map palette, whose 64 values must stay distinct (checked on 26.3) | `MapAlphabetTest` |
| the GLSL decoder | `mcav/mcv2/pack/assets/mcav/shaders/include/mcv2_codec.glsl` | `mcvideo_codec.glsl`, split into the pack's passes with its arithmetic unchanged | `tools/mcv2/shader_check.py` |
