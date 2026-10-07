# MCV2 tools

Scripts that tie mcav's MCV2 port to its reference, and the lab tools of the in-game proofs. None of them runs during
the build: the Java tests read only the fixtures committed under
`mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2`. The Python scripts import the reference from
`tools/mcv2-reference` (the research's reference code at commit `85445433aeb9f8a35a5ce528d47d8829976d1401`, unchanged)
and need the versions pinned in `tools/mcv2-reference/requirements.txt`: numpy, moderngl for the shader checks, Pillow
for the capture checks.

| script | what it does |
|---|---|
| `fixtures.py <fixture root> [conformance\|edge\|pages\|encoder\|all] [--source RGB]` | regenerates every MCV2 test fixture with the reference; on the committed fixtures it reproduces them byte for byte ([conformance fixtures](#conformance-fixtures)) |
| `tables.py [--check]` | regenerates (or checks) the tables `mcav-bukkit` loads at run time: the grid fitting matrices from the reference's `pixels.fitting_matrix`, and the residual books |
| `edge_streams.py <out> [seed]` | the edge-case streams: every leaf mode, compact class, motion form and index form, built with the reference serializer and decoded by the reference decoder, plus the syntax mcav refuses (`rejected.json`) |
| `shader_check.py <streams...> [--slots N] [--drop K] [--backend egl\|glx] [--pack DIR] [--spirv CLASSPATH]` | runs the resource pack's post chain under OpenGL 3.3 outside Minecraft, pass for pass as its `entity_outline.json` lists them, and compares every picture with the reference decoder, frame by frame, with the persistent references carried over as in the client; a frame with more pages than slots counts as never sent. With `--spirv` every pass is first compiled as Minecraft 26.3 compiles it (`Mcv2ShaderCompile.java`, the LWJGL jars on CLASSPATH), so the GLSL that runs is what the game's OpenGL backend hands the driver. Run before every commit that touches the pack, on the Intel GPU (EGL) and on llvmpipe (GLX), with and without `--spirv`: the conformance and edge fixtures, the A/B/D clips and crops, ship, low_bandwidth and live streams, and `--drop 7` on A and B |
| `Mcv2ShaderCompile.java <pack> <generated includes> <out> [--vanilla DIR] [--post-only]` | compiles every stage the pack takes part in the way Minecraft 26.3's OpenGL backend does outside its shader debug mode: GLSL through shaderc into SPIR-V for Vulkan 1.2 (uniforms bound automatically, debug info, `#include <namespace:path>` resolved, the renderer's macros defined), then through SPIRV-Cross back into GLSL 330 with the game's options (no separate shader objects, stage inputs and outputs named after their locations, variables zero-initialised); the text shaders with every define set the game uses for text (world, grayscale, see-through, GUI, the three improved-transparency stages, those also with the depth-invariance workaround; `--vanilla` names an extracted 26.3 client jar for Minecraft's own includes), and every post pass. Samplers keep their GLSL names, where the game names them after their binding, so `shader_check.py` can bind them. Run with a JDK and the LWJGL 3.4.3 jars of the 26.3 client (`lwjgl`, `lwjgl-shaderc`, `lwjgl-spvc` and their natives) on the class path; the exit code counts the stages that failed |
| `shader_timing.py <streams...> [--backend egl\|glx] [--rounds R] [--repeats K] [--pack DIR] [--json OUT]` | times every pass of that chain with GL_TIME_ELAPSED queries, for a frame that brings new video and for a rendered frame without new video, with a screen drawn in view; `--pack` times another pack folder, such as an older version, in the same window |
| `shader_timing_test.py` | checks shader_timing.py's exit codes without a GPU: its main() runs on a fake GL context with a fake post chain, so a frame not decoded, decoded again or decoded to another picture fails the run (exit 1), and a run that would time nothing is refused (exit 2); needs numpy only |
| `differential.py <mcav-bukkit classpath> [--streams N] [--encoded N] [--mutants N] [--seed S] [--out DIR]` | the differential test of addendum 4: generates archives (random block trees through the reference serializer at random sizes and index forms, reference-encoder streams of random moving pictures, and a mutated copy of each with bytes changed or a frame cut short), decodes every frame with the reference decoder and with mcav's (`Mcv2Digests.java`), and compares the per-frame digests; the only allowed difference is syntax mcav refuses as unsupported. Writes `summary.json`, exits non-zero on a disagreement |
| `Mcv2Bench.java key=value...` | the encoder benchmark behind the report's encode times: encodes a raw RGB source with a profile (or a custom live search), leaves out the warm-up frames, and prints one JSON line - mean/p50/p95/max ms per frame, CPU and bytes allocated per frame, keyframes, map and zlib rates, PSNR. Compile with a JDK against mcav-bukkit, run on the JVM being measured (the report: Temurin 25); its Javadoc has the arguments and an example |
| `Mcv2Digests.java <archive>...` | prints, per archive, the SHA-256 of every frame mcav's receiver decodes, or `reject` / `unsupported`; run with a JDK, `java -cp <classes>:<resources>:<guava> tools/mcv2/Mcv2Digests.java` |
| `counter_video.py <clip.rgb> <w> <h> <fps> <seconds> <out.mp4>` | a test video whose frames carry their own number in the first 24 blocks of their top row (the pixels the `Mcv2Frame` flight recorder event fingerprints), played forward and backward from a raw clip |
| `latency.py <server.jfr> <capture.nut> [--json OUT]` | end-to-end numbers of a run from the server's `Mcv2Frame` events and an x11grab capture of the client's debug view: frames encoded, sent, held back and displayed, displayed fps, backlog, and the glass-to-glass latency of every displayed frame |
| `capture_check.py <reference.rgb> <w> <h> <captures> [--top ROWS] [--vmaf FFMPEG]` | compares screenshots of a client running the pack's debug view (server started with `-Dmcav.mcv2.debugView=true`) with the reference decode: which frames were seen byte for byte, and PSNR, SSIM and VMAF |
| `strip_check.py <captures> --slots N --video-width W` | reads the rest of the debug view in the same screenshots: every slot of the transport strip, validated by the reference's `read_page` (header, extent, CRC32), the anchor descriptor row, and the status squares right of the picture (one exact colour each, slot squares in agreement with the pages of the same capture, no red decision, a decoded-frame counter that never goes down); one JSON summary line, exit code 1 on any failure |

The scripts that measure the curves of [docs/mcv2.md](../../docs/mcv2.md) and draw its pictures:

| script | what it does |
|---|---|
| `codec_curves.py --ffmpeg FFMPEG --source SRC --name NAME ... --out data/codec_curves.json` | the rate-VMAF points of H.264, VP9 and AV1 on a raw RGB source, every encode decoded back to RGB and scored the way MCV2 is; resumable (`test_codec_curves.py` checks the resuming, `python -m unittest tools/mcv2/test_codec_curves.py`) |
| `DitherBench.java key=value...` | what MCAV's dithered maps send and show on the same sources: map rate, zlib rate and the pictures a viewer's maps show, for VMAF |
| `figures/charts.py [--tables]` | draws `docs/images/mcv2/codecs.png` and `features.png` from `data/codec_curves.json` and `data/ablation.json`, and prints the article's tables |
| `figures/render.sh` | renders the article's diagrams from the Graphviz sources next to it |

`data/codec_curves.json` holds every point of the codec comparison with its settings and commands, and
`data/ablation.json` the curves of the encoder with each feature turned off, with how they were measured.

## Conformance fixtures

How mcav proves that its MCV2 implementation - the Java decoder and encoder in `mcav-bukkit`, the native kernels and
the resource pack's shaders - equals the reference, which test vectors that takes, and how to regenerate them.

The oracle is the Python reference in `tools/mcv2-reference` (its README lists every file with its SHA-256 and the
pinned Python requirements). The vectors are all under
`mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2` (8.9 MB in all), written by the reference itself
unless marked otherwise:

| path | what | size | read by |
|---|---|---:|---|
| `conformance/*.mcs` | 12 streams of the 1080p60 rate and quality ladders and the two shipped 1080p30 streams (`ship`, `low_bandwidth`): 724 frames. The three streams over 1 MB are kept as their longest whole-frame prefix within 1,000,000 bytes, which starts at the keyframe: `quality85` 41 of 60 frames, `rate12` 53 of 60, `rate20` 30 of 60 | 7.6 MB | `ConformanceTest`, `Mcv2DecoderTest`, `FrameWriterConformanceTest`, `Mcv2ParserPropertyTest`, `PageAssemblerTest` |
| `conformance/digests.json` | per stream: the frame count and size, those of the full stream it was cut from, and the SHA-256 of the reference decoder's RGB output for every frame | 54 KB | `ConformanceTest` |
| `conformance/pages.json` | the reference's map pages (stream id 7) of four frames at 6, 7 and 8 bits: the SHA-256 and length of every page's symbols, and the wire model with and without whole maps | 6 KB | `TransportPagesTest` |
| `edge/*.mcs`, `edge/digests.json` | 14 edge-case streams, 79 frames, built from random block trees with the reference's own serializer (`edge_streams.py`): every leaf mode, compact class, motion form and index form, quantizers up to 7, edges crossing blocks | 329 KB | `ConformanceTest`, `FrameWriterConformanceTest`, `FrameMutationTest`, `Mcv2ParserPropertyTest`, `NativeConformanceTest` |
| `edge/rejected.json` | three frames of syntax the reference accepts and mcav refuses on purpose (MCV1, the coarse palettes of modes 21 and 22, the motion table of flag 256), which must fail as unsupported | | `ConformanceTest` |
| `encoder/crop-320x180x4.rgb` | four frames of the 1080p30 source, cropped at (1472, 360) | 691 KB | `EncoderConformanceTest`, `NativeConformanceTest` |
| `encoder/crop-ship.mcs`, `crop-low.mcs` | the reference encoder's streams of that crop at both shipped lambdas, which mcav's encoder must reproduce byte for byte, on one to four threads | 4 KB | `EncoderConformanceTest`, `NativeConformanceTest` |
| `FrameParserFuzzTestInputs/`, `transport/`, `encode/` | Jazzer seed corpora of the fuzz tests; not reference output | 189 KB | the `*FuzzTest` classes |

What they prove:

- **Decoder, bit-exact.** Every committed frame decodes to the reference's RGB output (SHA-256 per frame). Syntax mcav
  refuses is refused as unsupported, never misread. Every committed frame's block tree also serializes back to the same
  bytes (`FrameWriterConformanceTest`).
- **Encoder, byte-identical.** The `ship` and `low_bandwidth` profiles write the reference's bytes: in JUnit on the
  crop, and on the whole 30-frame source, where both archives equal the reference encoder's streams (SHA-256
  `6fc68739...` for `ship`, 321,250 bytes; the check needs the source, see `EncoderConformanceTest`). Through the
  native kernels, at every SIMD level the processor runs, the search writes the same bytes and every edge picture
  re-encodes as with the Java kernels (`NativeConformanceTest`).
- **Transport, bit-exact.** Every page of `pages.json`, CRC included.
- **Beyond the corpus.** `differential.py` decodes generated streams with both decoders frame by frame: random block
  trees, reference-encoder streams on random pictures, and mutated copies of both.
- **The resource pack.** `shader_check.py` runs the pack's post chain outside Minecraft, pass for pass, and compares
  every picture with the reference decoder, plain and compiled the way Minecraft 26.3 compiles it (`--spirv`);
  `capture_check.py` and `strip_check.py` do the same with screenshots of the real client.

To regenerate them, with the pinned requirements of `tools/mcv2-reference/requirements.txt`:

```
python tools/mcv2/fixtures.py mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2 [conformance|edge|pages|encoder|all]
```

- `conformance` recomputes `digests.json` from the committed streams (and checks that each is a keyframe-first prefix
  within the size limit); the full-stream counts are carried over, since the full streams are not here.
- `edge` rebuilds the edge streams, their digests and `rejected.json` from a fixed seed.
- `pages` recomputes `pages.json` from the committed streams.
- `encoder` re-encodes the committed crop at both lambdas; `--source <raw 1920x1080 RGB>` cuts the crop again from the
  30-frame 1080p30 source (SHA-256 `acf2ab47e7d36aeefc53e0781d7b3934eb468efcbed610ddd6479aa5aece05bd`), which is not
  part of mcav.

Rerunning all four steps on the committed fixtures reproduces them byte for byte. The script refuses to run if the
reference's residual books and mcav's (`residual_books.bin` in `mcav-bukkit`'s resources) differ.
