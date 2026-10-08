# MCV2 tools

Scripts that tie mcav's MCV2 port to its reference, and the lab tools of the in-game proofs. None of them runs during
the build: the Java tests read only the fixtures committed under
`mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2`. The Python scripts import the reference from
`tools/mcv2-reference` (the independent normative version-3 parser, serializer and decoder)
and need the versions pinned in `tools/mcv2-reference/requirements.txt`: numpy, moderngl for the shader checks, Pillow
for the capture checks.

The scripts' own tests are in `mcav-bukkit/src/test/python`: strict differential agreement, v3 fixture coverage and
regeneration, six-bit strip extraction, fitting tables, how `capture_check.py` counts pictures that repeat, and how
`latency.py` matches its events. Like the scripts, they are
not part of the build; run them from the repository root with the same requirements:

```
python -m unittest discover -s mcav-bukkit/src/test/python
```

| script | what it does |
|---|---|
| `fixtures.py <fixture root> [conformance\|edge\|pages\|encoder\|all]` | regenerates v3 edge streams, reference RGB digests and six-bit page vectors; validates Java golden encoder streams without rewriting them; skips non-v3 committed streams by name ([conformance fixtures](#conformance-fixtures)) |
| `tables.py [--check]` | regenerates or checks `fitting_matrices.bin` using the specified interpolation and numpy least-squares fits; preserves the resource layout, including unused grid slots; no residual books and no dependency on a reference encoder |
| `edge_streams.py <out> [seed]` | builds v3 streams through the independent serializer: every leaf size/mode, COMPACT at q 0–2 with extreme vectors and luma, PATTERN orientations and axis extremes, cropped and extreme dimensions, long walks, length and id bounds; writes exact RGB digests and the frames every decoder must reject (`rejected.json`) |
| `shader_check.py <streams...> [--slots N] [--drop K] [--backend egl\|glx] [--pack DIR] [--spirv CLASSPATH] [--second-screen] [--restart-check]` | runs the v3 pack chain and compares every decoded picture byte for byte with `mcvideo.decoder.decode(data, reference, reference_id)`. The pack holds one picture, accepts a keyframe with any different id so a sender can restart, and requires P frames to be newer with the held reference. `--restart-check` verifies an older second keyframe, duplicate retention, strict P-frame ordering and held ids/pictures. Run every v3 edge and conformance stream on Intel EGL and llvmpipe GLX, each directly and through the 26.3 compiler (`--spirv`); also run `--drop 7` on two conformance streams. An alternate source pack requires its own sibling `chain.json` |
| `Mcv2ShaderCompile.java <pack> <generated includes> <out> [--vanilla DIR] [--post-only]` | compiles every stage the pack takes part in the way Minecraft 26.3's OpenGL backend does outside its shader debug mode: GLSL through shaderc into SPIR-V for Vulkan 1.2 (uniforms bound automatically, debug info, `#include <namespace:path>` resolved, the renderer's macros defined), then through SPIRV-Cross back into GLSL 330 with the game's options (no separate shader objects, stage inputs and outputs named after their locations, variables zero-initialised); the text shaders with every define set the game uses for text (world, grayscale, see-through, GUI, the three improved-transparency stages, those also with the depth-invariance workaround; `--vanilla` names an extracted 26.3 client jar for Minecraft's own includes), and every post pass. Samplers keep their GLSL names, where the game names them after their binding, so `shader_check.py` can bind them. Run with a JDK and the LWJGL 3.4.3 jars of the 26.3 client (`lwjgl`, `lwjgl-shaderc`, `lwjgl-spvc` and their natives) on the class path; the exit code counts the stages that failed |
| `strip_fit_check.py [--backend egl\|glx] [--spirv CLASSPATH]` | runs the post chain as `shader_check.py` does on screens too small for the transport strip (one screen of eight slots at 160x90 and 64x400) over a random scene, and checks that the chain leaves the scene exactly as it is and decodes nothing, and that at 854x480, where the strip fits, it is still covered with the scene row below it; supports the same Minecraft compilation path as shader_check; run with the other shader checks before a commit that touches the pack |
| `shader_timing.py <streams...> [--backend egl\|glx] [--slots N] [--rounds R] [--repeats K] [--pack DIR] [--reference DIR] [--spirv CLASSPATH] [--json OUT]` | times every mcav pass with GL_TIME_ELAPSED for new video and repeated pages, with a wall screen in view. Each copy target has its own timing; the total includes all copies and excludes vanilla outline passes. For a v2 baseline, copy the old pack and its sibling chain.json, retain its generated books include, and point `--reference` at an archived v2 Python package. A v3 keyframe has equal frame and reference ids; v2 baseline flags are byte 6. Warm-up rounds are excluded; a missed, repeated, or nondeterministic decode fails the run |
| `shader_timing_test.py` | checks shader_timing.py's exit codes without a GPU: its main() runs on a fake GL context with a fake post chain, so a frame not decoded, decoded again or decoded to another picture fails the run (exit 1), and a run that would time nothing is refused (exit 2); also checks v3 frame classification and exact accounting of repeated copy passes; needs numpy only |
| `differential.py <mcav-bukkit classpath> [--streams N] [--conformance N] [--corpus DIR] [--mutants N] [--seed S] [--out DIR] [--java JAVA]` | compares random serialized trees, committed conformance streams and mutated/truncated copies with the Python and Java decoders; every frame must agree on rejection or RGB digest, including frames after a disagreement. Defaults: 200 random streams, 40 conformance samples, one mutation of each. Writes all disagreements to `summary.json`, exits nonzero on any difference or launcher failure |
| `Mcv2Bench.java key=value...` | the encoder benchmark behind the report's encode times: encodes a raw RGB source with a profile (or a custom live search), leaves out the warm-up frames, and prints one JSON line - mean/p50/p95/max ms per frame, CPU and bytes allocated per frame, keyframes, map and zlib rates, PSNR. Compile with a JDK against mcav-bukkit, run on the JVM being measured (the report: Temurin 25); its Javadoc has the arguments and an example |
| `Mcv2Digests.java <archive>...` | prints the SHA-256 of each frame decoded by mcav's receiver or `reject`; run with a JDK, `java -cp <classes>:<resources>:<guava> tools/mcv2/Mcv2Digests.java`. The differential comparison accepts no unsupported-syntax exemption |
| `counter_video.py <clip.rgb> <w> <h> <fps> <seconds> <out.mp4>` | a test video whose frames carry their own number in the first 24 blocks of their top row (the pixels the `Mcv2Frame` flight recorder event fingerprints), played forward and backward from a raw clip |
| `latency.py <server.jfr> <capture.nut> [--json OUT]` | end-to-end numbers of a run from the server's `Mcv2Frame` events and an x11grab capture of the client's debug view: frames encoded, sent, held back and displayed, displayed fps, backlog, and the glass-to-glass latency of every displayed frame |
| `capture_check.py <reference.rgb> <w> <h> <captures> [--top ROWS] [--vmaf FFMPEG]` | compares screenshots of the pack's debug view with raw RGB from `mcvideo.decoder.decode` for the same v3 stream: distinct pictures seen byte for byte, PSNR, SSIM and VMAF; identical reference pictures stay indistinguishable |
| `strip_check.py <captures> --slots N --video-width W` | extracts six-bit symbols from the debug strip, removes map row padding and validates each exact page through the v3 reference transport; checks anchors and status squares, reports a JSON summary and exits nonzero on failures |

The scripts that measure the curves of [mcav-docs/mcv2.md](../../mcav-docs/mcv2.md) and draw its pictures:

| script | what it does |
|---|---|
| `codec_curves.py --ffmpeg FFMPEG --source SRC --name NAME ... --out data/codec_curves.json` | the rate-VMAF points of H.264, VP9 and AV1 on a raw RGB source, every encode decoded back to RGB and scored the way MCV2 is; resumable (`test_codec_curves.py` checks the resuming, `python -m unittest tools/mcv2/test_codec_curves.py`) |
| `DitherBench.java key=value...` | what MCAV's dithered maps send and show on the same sources: map rate, zlib rate and the pictures a viewer's maps show, for VMAF |
| `figures/charts.py [--tables]` | draws `mcav-docs/images/mcv2/codecs.png` and `features.png` from `data/codec_curves.json` and `data/ablation.json`, and prints the article's tables |
| `figures/samples.py tree\|leaves\|quality\|bytes ...` | draws the article's sample pictures from real streams (`tree.png`, `frame.png`: every leaf outlined and coloured by mode; `leaves.png`: a palette and a pattern leaf blown up; `quality.png`: one frame at several rates) with the reference decoder, and prints a frame's bytes field by field; its docstring has the arguments |
| `figures/render.sh` | renders the article's diagrams from the Graphviz sources next to it |

`data/codec_curves.json` holds every point of the codec comparison with its settings and commands (version 2's MCV2
curves are kept under `mcv2_version2` and `mcv2_version2_live`), and
`data/ablation.json` the curves of the encoder with each feature turned off, with how they were measured, and what the
features MCV2 no longer has were worth when they were measured; `data/ablation-v2.json` is the same measurement for
version 2 of MCV2, before it was simplified.

## Conformance fixtures

The independent Python reference in `tools/mcv2-reference` defines MCV2 version 3 together with
[mcav-docs/mcv2.md](../../mcav-docs/mcv2.md). Its README lists source/test SHA-256 hashes, the public API and pinned requirements.
The fixture root is `mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2`.
Archives (`.mcs`) contain repeated little-endian u32 frame lengths followed by the frame bytes, matching
`Mcv2FileEncoder`.

| path | contents and writer | regeneration |
|---|---|---|
| `edge/*.mcs` | 13 deterministic streams, 49 frames, built by the Python serializer from block trees. Covers all six leaf modes at 32/16/8 pixels; COMPACT at q 0–2 with extreme vectors and luma nodes; PATTERN at every size and orientation with all-zero, all-one and alternating axes and endpoints at full RGB precision; 1×N/N×1/cropped pictures; all-absent frames (black in a keyframe); clamped s8 motion; long compact walks; over 20,000 split prefixes; the 131,071-byte limit; u32 id wrap | `edge` |
| `edge/digests.json` | SHA-256 of the Python decoder's RGB output for every edge frame | `edge` |
| `edge/rejected.json` | 78 frames refused by every decoder: each entry has hex `frame`, specification `rule` and expected error `reason`. Covers every §9 rule, valid old MCV1/v2 examples, and malformed off-picture leaves | `edge` |
| `conformance/*.mcs` | Java-written v3 streams, each within 1,000,000 bytes: DEFAULT proxy/gameplay, FAST, ADAPTIVE, keyframes only and a cropped size. The reference never rewrites these streams | Java encoder |
| `conformance/digests.json` | stream size/count and SHA-256 of every frame's independently decoded RGB | `conformance` |
| `conformance/pages.json` | six-bit pages, stream id 7, for four frames. Includes source archive/index, each page's symbol digest and length, and wire sizes for rows/full maps. The `source` field names committed v3 conformance streams or the edge fallback when none exist | `pages` |
| `encoder/*.rgb`, `encoder/*.mcs` | committed source crop and Java encoder's DEFAULT/FAST golden streams, used for thread-count and Java/native determinism checks | Java encoder; `encoder` only checks that the v3 archives decode |
| `FrameParserFuzzTestInputs/`, `transport/`, `encode/` | existing fuzz inputs, retained as rejection/decoding inputs | retained independently of the generator |

The edge folder is 826,065 bytes, including JSON catalogs; the edge-derived pages manifest is 1,756 bytes.
Non-v3 conformance and golden encoder archives are printed by name and skipped. Their bytes and existing digests
are preserved until replaced with Java-written v3 streams. A v3 stream containing any invalid frame still fails.

Run from the repository root with the pinned requirements:

```sh
python -m unittest discover -s tools/mcv2-reference/tests
python -m unittest discover -s mcav-bukkit/src/test/python
python tools/mcv2/fixtures.py mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2 all
```

The final argument can be `conformance`, `edge`, `pages` or `encoder`. `conformance` recomputes v3 digests and
checks the keyframe-first and archive-length rules. `edge` rebuilds the streams and catalogs from a fixed seed,
and verifies each rejection's reason. `pages` uses the first four available v3 conformance frames (cycling if fewer
than four exist), or four edge frames. `encoder` never encodes or writes files. Running `all` twice reproduces
these committed fixtures byte for byte.

`differential.py` keeps both receivers' stream state and compares every result, even after a prior disagreement.
There is no Python encoder and no unsupported-syntax exemption. Its default corpus is `conformance/`; both its
original streams and mutated copies are checked, including old-version inputs which both v3 decoders must reject.
The resource pack and capture checks use the same reference reconstruction and six-bit transport APIs.
