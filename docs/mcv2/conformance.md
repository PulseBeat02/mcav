---
orphan: true
---

(mcv2-conformance)=
# MCV2 conformance

How mcav proves that its MCV2 implementation - the Java decoder and encoder in `mcav-bukkit`, the native kernels and
the resource pack's shaders - equals the reference, which test vectors that takes, and how to regenerate them without
the research repository.

## The reference

The oracle is the Python reference of the gpu-codec research repository at commit
`85445433aeb9f8a35a5ce528d47d8829976d1401`, the commit the port is pinned to. mcav keeps the part of it that the
fixtures and checks need, byte for byte and unchanged, in `tools/mcv2-reference` (its README lists every file with its
SHA-256, why exactly those files, and the pinned Python requirements). Nothing in the build, the tests or the docs reads
the research repository.

## The vectors

All under `mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2` (8.9 MB in all), written by the reference
itself unless marked otherwise:

| path | what | size | read by |
|---|---|---:|---|
| `conformance/*.mcs` | the 12 round-19 streams of the 1080p60 frontier ladders ([results.md](results.md)) and the two shipped 1080p30 streams (`ship`, `low_bandwidth`): 724 frames. The three streams over 1 MB are kept as their longest whole-frame prefix within 1,000,000 bytes, which starts at the keyframe: `quality85` 41 of 60 frames, `rate12` 53 of 60, `rate20` 30 of 60 | 7.6 MB | `ConformanceTest`, `Mcv2DecoderTest`, `FrameWriterConformanceTest`, `Mcv2ParserPropertyTest`, `PageAssemblerTest` |
| `conformance/digests.json` | per stream: the frame count and size, those of the full stream it was cut from, and the SHA-256 of the reference decoder's RGB output for every frame | 54 KB | `ConformanceTest` |
| `conformance/pages.json` | the reference's map pages (`make_pages`, stream id 7) of four frames at 6, 7 and 8 bits: the SHA-256 and length of every page's symbols, and the wire model with and without whole maps | 6 KB | `TransportPagesTest`, `PageAssemblerTest` |
| `edge/*.mcs`, `edge/digests.json` | 14 edge-case streams, 79 frames, built from random block trees with the reference's own serializer (`tools/mcv2/edge_streams.py`): every leaf mode, compact class, motion form and index form, quantizers up to 7, edges crossing blocks | 329 KB | `ConformanceTest`, `FrameWriterConformanceTest`, `FrameMutationTest`, `Mcv2ParserPropertyTest`, `NativeConformanceTest` |
| `edge/rejected.json` | three frames of syntax the reference accepts and mcav refuses on purpose (MCV1, round 3's coarse palettes, round 15's motion table), which must fail as unsupported | | `ConformanceTest` |
| `encoder/crop-320x180x4.rgb` | four frames of the 1080p30 source, cropped at (1472, 360) | 691 KB | `EncoderConformanceTest`, `NativeConformanceTest` |
| `encoder/crop-ship.mcs`, `crop-low.mcs` | the reference encoder's streams of that crop at both shipped lambdas, which mcav's encoder must reproduce byte for byte, on one to four threads | 4 KB | `EncoderConformanceTest`, `NativeConformanceTest` |
| `FrameParserFuzzTestInputs/`, `transport/`, `encode/` | Jazzer seed corpora of the fuzz tests; not reference output | 189 KB | the `*FuzzTest` classes |

The committed streams are the vectors: their prefixes are cut from research streams, and the research data they came
from is not part of mcav. Everything else is derived from them and from the reference.

## What is proven, and where

- **Decoder, bit-exact.** Every committed frame decodes to the reference's RGB output (SHA-256 per frame), and all 780
  frames of the full streams did once, when the port was made (mcav-mcv2 report, "Conformance"). Syntax the port
  refuses is refused as unsupported, never misread. Every committed frame's block tree also serializes back to the same
  bytes (`FrameWriterConformanceTest`).
- **Encoder, byte-identical.** The `ship` and `low_bandwidth` profiles write the reference's bytes: in JUnit on the
  crop, and once on the whole 30-frame source, where both archives equal the research's streams (SHA-256
  `6fc68739...` for `ship`, 321,250 bytes; the check needs the source, see `EncoderConformanceTest`). Through the
  native kernels, at every SIMD level the processor runs, the search writes the same bytes and every edge picture
  re-encodes as with the Java kernels (`NativeConformanceTest`).
- **Transport, bit-exact.** Every page of `pages.json`, CRC included.
- **Beyond the corpus.** `tools/mcv2/differential.py` decodes generated streams with both decoders frame by frame:
  random block trees, reference-encoder streams on random pictures, and mutated copies of both; 24,864 frames and no
  disagreement when the port was made.
- **The resource pack.** `tools/mcv2/shader_check.py` runs the pack's post chain outside Minecraft, pass for pass, and
  compares every picture with the reference decoder, plain and compiled the way Minecraft 26.3 compiles it
  (`--spirv`); `tools/mcv2/capture_check.py` and `strip_check.py` do the same with screenshots of the real client
  (for Minecraft 26.3: [design doc §5.2](../mcv2-integration.md)).

## Regenerating the fixtures

With the pinned requirements of `tools/mcv2-reference/requirements.txt`:

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
