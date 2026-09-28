---
orphan: true
---

(mcv2-results)=
# MCV2 results

What the MCV2 codec reaches, what mcav ships and why, and what limits it, with the measurements behind every number.
The research ran in the gpu-codec repository; its result files at commit `85445433aeb9f8a35a5ce528d47d8829976d1401` are
copied unchanged into `docs/mcv2/data/` (list at the end). The format itself is in [format.md](format.md), the encoder in
[encoder.md](encoder.md), and how mcav runs the codec in Minecraft in [the design doc](../mcv2-integration.md).

## Status

**The research frontier was stopped by the owner, after round 19 (owner decision 6, 2026-09-25). It did not converge.**
The stopping rule the research set itself wanted three consecutive rounds under 1% BD-rate; the streak never got past
zero, since rounds 17, 18 and 19 scored +2.16%, +5.48% and +1.72%. The research's final report opens with "Status:
converged (owner decision 6: stopped by the owner after round 19)" only because the tooling that read the report looked
for that word; the report itself says the same as this page. The owner stopped it because the research gated the
Minecraft integration and the whole work had to finish before 2026-10-14, and because every kept format feature must be
ported bit-exactly into mcav's Java decoder and shader, a cost BD-rate does not show. Known work was left: the ideas never tried are listed in the
[handover](HANDOVER.md#open-items).

## How it was measured

- **Source.** A deterministic procedural Minecraft proxy, 1920x1080, one second: 60 frames at 60 fps (SHA-256 of the
  raw RGB `ea6a4463...8cc7`), and for 1080p30 every other of those frames, 30 frames (`acf2ab47...05bd`). Three short
  public excerpts (Twitch Minecraft, Dinner, Sintel, 640x360) served as held-out content. The proxy is not captured
  gameplay, and VMAF was trained on natural video, so on this source VMAF compares codecs rather than being an
  absolute scale.
- **Scores.** VMAF (libvmaf in ffmpeg 7.0.2) as mean and minimum over the frames, RGB PSNR, SSIM. A clip's first frame
  has no motion feature, so a bit-exact reconstruction scores 97.428 rather than 100 on it.
- **Rates.** *Map Mbps* charges everything a stream costs on the Minecraft wire: every header, directory, index, mode,
  payload and keyframe, the six-bit transport's 4/3 expansion, page headers, row rounding and packet envelopes.
  *Logical Mbps* is the frame bytes alone. AV1 rates are *container* Mbps, which charge none of the map transport. The
  two bases are never mixed in one comparison.
- **Reproducibility.** 630 measured points, and all 630 reproduce bit-exactly from their recorded configurations;
  every stream any round produced re-decodes to its recorded PSNR and SSIM under the final code (371 of 371 at
  round 10's gate, repeated at every later round).

## The frontier at 1080p60

Nineteen rounds were scored against a fully measured round-0 baseline: twelve kept, seven reverted, and one
feasibility study (Round T, below) rejected. The cumulative BD-rate improvement over round 0 is **+43.097%**, pooled
over both research profiles in the VMAF range 48.37 to 99.96 (`compact_final` +43.281%, `final` +10.570%), with every
rung of both ladders improved.

| # | mechanism | decision | frontier BD-rate |
|---|---|---|---:|
| 1 | immediate motion records | kept | +8.2830% |
| 2 | half-pixel motion | reverted | +0.4699% |
| 3 | coarse palette selectors | reverted | +0.3450% |
| 4 | derived root directory | kept | +1.2396% |
| 5 | perceptual RD weighting | reverted | +0.1821% |
| 6 | VBV rate control | reverted | +0.0063% |
| 7 | derived descriptor offsets | kept | +2.2475% |
| 8 | matched RD cost model | kept | +1.6085% |
| 9 | packed (mode, quantizer) symbols | kept | +5.7952% |
| 10 | two-level walk plane | kept | +14.2141% (of which +6.42 points belong to rounds 1-9, a sampling artefact) |
| 11 | captured VQ/PQ codebooks | reverted | -0.0433% |
| 12 | wider motion search | kept | +1.2851% |
| 13 | pattern-palette RDO | kept | +6.3753% |
| 14 | wire RDO | reverted | -0.0860% |
| 15 | motion table | reverted | +0.9509% |
| 16 | per-frame endpoint table | kept | +1.6725% |
| 17 | per-size selector tables | kept | +2.1643% |
| 18 | RGB565 endpoints, chosen per frame | kept | +5.4770% |
| 19 | half-pixel motion (round 2 re-tested) | kept | +1.7215% |

Round 19 is round 2 run again on a newer baseline: motion vectors had grown from 12.16% to 15.91% of the wire as
rounds 16 to 18 shrank everything around them, and +0.47% became +1.72%. A rejected mechanism is worth re-testing
whenever its field's neighbours move.

**Rate ladder**, the highest measured VMAF at or under each map budget (`data/frontier.json`, `rate_ladder`):

| map budget | stream | map Mbps | logical Mbps | VMAF mean / min | RGB PSNR | SSIM |
|---|---|---:|---:|---:|---:|---:|
| <= 3 | `round18-refine3-compact_final-rate3` | 2.9774 | 2.1905 | 67.564 / 61.179 | 33.255 | 0.84106 |
| <= 4 | `round19-seed-compact_final-114p559576` | 3.9543 | 2.9200 | 72.102 / 66.088 | 33.796 | 0.84858 |
| <= 5 | `round19-seed-compact_final-81p686291` | 4.9445 | 3.6648 | 75.757 / 68.592 | 34.161 | 0.85498 |
| <= 6 | `round19-seed-compact_final-62p302104` | 5.9808 | 4.4413 | 78.058 / 70.487 | 34.483 | 0.86098 |
| <= 8 | `round19-refine2-compact_final-rate8` | 7.9150 | 5.8845 | 81.282 / 74.023 | 34.967 | 0.87279 |
| <= 12 | `round19-refine2-compact_final-rate12` | 11.9286 | 8.8789 | 83.715 / 76.175 | 35.475 | 0.88785 |
| <= 20 | `round19-refine2-compact_final-rate20` | 19.6118 | 14.6203 | 87.299 / 81.244 | 36.297 | 0.90996 |

**Quality ladder**, the cheapest measured map rate reaching each VMAF mean (`quality_ladder`):

| VMAF | stream | map Mbps | logical Mbps | VMAF mean / min | RGB PSNR | SSIM |
|---|---|---:|---:|---:|---:|---:|
| 60 | `round19-seed-compact_final-371p431671` | 1.9696 | 1.4304 | 60.120 / 53.583 | 32.369 | 0.83124 |
| 65 | `round18-refine2-compact_final-quality65` | 2.5237 | 1.8487 | 65.042 / 59.601 | 32.923 | 0.83680 |
| 70 | `round19-seed-compact_final-137p730758` | 3.5160 | 2.5949 | 70.053 / 64.587 | 33.603 | 0.84569 |
| 73 | `round19-refine1-compact_final-quality73` | 4.1365 | 3.0595 | 73.043 / 66.552 | 33.873 | 0.84988 |
| 75 | `round19-seed-compact_final-86p946119` | 4.7100 | 3.4881 | 75.084 / 67.933 | 34.082 | 0.85363 |
| 78 | `round19-seed-compact_final-62p302104` | 5.9808 | 4.4413 | 78.058 / 70.487 | 34.483 | 0.86098 |
| 80 | `round19-seed-compact_final-50p027708` | 7.1194 | 5.2905 | 80.097 / 73.097 | 34.778 | 0.86763 |
| 85 | `round19-refine2-compact_final-quality85` | 14.7516 | 10.9912 | 85.027 / 78.062 | 35.765 | 0.89654 |
| max | `round13-seed-compact_final-0p000000` | 1273.3500 | 951.4452 | 99.957 / 97.428 | 99.000 | 1.00000 |

The max point is mathematically lossless (RGB MSE 0, SSIM 1): the format can represent anything, just not cheaply.
Against round 0, the 5 map Mbps rung gained 12.6 VMAF (63.136 to 75.757), and VMAF 80 costs 46.30% less (13.2569 to
7.1194 map Mbps). Twelve of these streams are mcav's conformance vectors ([conformance.md](conformance.md)).

On the three held-out excerpts the lossless mechanisms of rounds 1 and 4 re-measured at matched lambdas saved +2.141%
(Minecraft), +4.841% (Dinner) and +4.979% (Sintel) BD-rate with VMAF identical to the last digit at all twelve points,
which is what lossless mechanisms must produce.

## The 1080p30 operating points

The content MCV2 is for is 1080p at 30 fps. At 30 fps a map Mbps carries twice the bytes per frame it does at 60
(14,589 against 7,686 at the 5 Mbps point), while the doubled temporal distance makes prediction harder; the figures are
the net, not a codec improvement. The key interval is 60 frames, two seconds. Round 19's points
(`data/frontier_1080p30.json`, `points`, ids `p30r19-*`, every one encoded with the settings mcav's encoder
reproduces):

| lambda | stream | map Mbps | logical Mbps | VMAF mean / min | RGB PSNR | SSIM |
|---:|---|---:|---:|---:|---:|---:|
| 254.484 | `p30r19-compact_final-254p484095` | 1.5558 | 1.1434 | 65.041 / 59.165 | 32.921 | 0.83747 |
| 193.694 | `p30r19-compact_final-193p693711` | 1.7934 | 1.3227 | 67.754 / 60.750 | 33.250 | 0.84136 |
| **137.731** | `p30r19-compact_final-137p730758` (**low bandwidth**) | **2.1221** | 1.5676 | **70.580 / 64.587** | 33.602 | 0.84585 |
| 92.074 | `p30r19-compact_final-92p074354` | 2.6996 | 2.0001 | 74.868 / 67.403 | 34.011 | 0.85255 |
| **65.256** | `p30r19-compact_final-65p255994` (**ship**) | **3.4584** | 2.5690 | **77.933 / 70.117** | 34.444 | 0.85944 |
| 45.091 | `p30r19-compact_final-45p091398` | 4.4721 | 3.3218 | 80.978 / 73.845 | 34.860 | 0.86923 |
| 33.671 | `p30r19-compact_final-33p670698` | 6.4866 | 4.8310 | 83.564 / 75.966 | 35.320 | 0.88430 |
| 19.045 | `p30r19-compact_final-19p044923` | 11.9159 | 8.8870 | 87.792 / 81.585 | 36.335 | 0.91147 |

**The named target, VMAF mean 80 at 6 map Mbps or less on the 1080p30 basis (owner decision 3, 2026-09-19), is met**
since round 9, and at the end by `p30-r18b-compact_final-36p283303`: 5.6367 map Mbps at VMAF 82.821 mean / 75.319 min.
VMAF 80 itself costs 4.3921 map Mbps on this basis (`p30r18-compact_final-46p673709`), against 13.2569 at round 0 on
the 1080p60 one (`named_target` in `data/frontier.json`).

## What mcav ships, and why

**`ship` = `p30r19-compact_final-65p255994`**, lambda 65.255994022: **3.4584 map Mbps at VMAF 77.933 mean / 70.117
min**, RGB PSNR 34.444, SSIM 0.85944. **`low_bandwidth` = `p30r19-compact_final-137p730758`**, lambda 137.730758207:
2.1221 map Mbps at VMAF 70.580 / 64.587. Both keep round 19's settings except the lambda (listed in
[encoder.md](encoder.md#the-shipped-settings)).

- **Why these points.** The rule (mcav-mcv2, addendum 2): from the newest round with 1080p30 points, round 19, whose
  format and encoder mcav ports bit for bit, `ship` is the cheapest point reaching a VMAF mean of 75 and
  `low_bandwidth` the cheapest reaching 70. Round 19's 92.074 point stops at 74.868, so 65.256 is the cheapest at 75,
  and its per-frame floor of 70.1 matters more for a stream than the mean, since the worst frame is what a viewer
  notices. Older rounds' 1080p30 points (the 1080p30 ladders in the file name round-17 and round-18 streams) use
  settings of formats mcav does not encode: round 17 had no half-pixel motion and no RGB565 endpoints.
- **The research file's own recommendation is stale.** `recommendation.ship` in `frontier_1080p30.json` still names
  the point of 2026-09-19, `p30-compact_final-44p858993` (5.6705 map Mbps, VMAF 77.755 / 71.743, 15.065 ms per GPU
  draw). Round 19 reaches the same VMAF mean at 39% less rate; mcav does not use the stale point.
- **mcav's encoder reproduces them exactly.** On the 30-frame 1080p30 source, mcav's Java encoder writes archives
  byte-identical to the reference encoder's at both lambdas (`ship`: 321,250 bytes, SHA-256 `6fc68739...`, the research
  stream itself), so the quality above is mcav's quality with zero difference. It takes 570 ms per 1080p frame on 12
  threads of an i7-8700 (the Python reference: 172 s), so these profiles are for pre-encoded files.
- **Live sources use the live presets**, mcav's own, measured by the mcv2 stage on the 1080p30 proxy and on 30 fps
  gameplay (details: [design doc §12](../mcv2-integration.md), and the mcav-mcv2 report):

  | preset | lambda | VMAF mean at the default, proxy / gameplay | BD-rate vs `ship`, map / zlib, proxy | gameplay | 1080p30 p95 per frame, 12 threads, proxy / gameplay |
  |---|---:|---|---|---|---|
  | `live` (the default) | 72 | 75.7 / 76.1 | -5.1% / -3.4% | +7.9% / +6.9% | 20.09 / 31.21 ms |
  | `adaptive` | 72, 55 in motion | 75.7 / 76.1 | -5.5% / -3.8% | +24.9% / +14.3% | 20.37 / 27.44 ms |
  | `live-fast` | 55 | 76.1 / 76.1 | +17.7% / +18.4% | +29.4% / +17.1% | 17.61 / 27.24 ms |

  All three meet the 1080p30 gate (p95 under 32 ms at 12 threads) with the native kernels; `live` is the default
  because it is the slowest rung that does. **1080p60 is not met** by any preset: `live-fast` needs 19.72 ms (proxy)
  and 31.34 ms (gameplay) per frame at the 95th percentile against 16 ms, because a 60 fps gameplay frame costs 207 ms
  of CPU. A viewer of the default `live` at 1080p30 receives 2.80 map Mbit/s (1.83 on the wire, compressed) on the
  proxy and 13.0 (8.3) on gameplay.

## The ceiling, and why

**The gap to AV1 at the same nominal budget is 15.8 VMAF points.** libaom at cpu-used 6, CBR, reaches VMAF 91.532 mean
/ 88.941 min at 4.7097 container Mbps on the same second of video; MCV2's best under 5 map Mbps reaches 75.757 / 68.592
at 4.9445. The gap was 28.4 points at round 0 and 24.9 at round 18.

**The reason is per-fragment random access in a vanilla core shader.** The client decodes MCV2 in a post-processing
pass of an unmodified Minecraft: every fragment of that pass must find its own block and decode its own pixel directly
from the frame's bytes, with no client mod and no sequential or stateful decoding. The format keeps four rules for
that: a bounded decoder, no frame-wide serial entropy dependency, directly indexable representations, and six-bit map
transport. What each rule costs, measured on round-17 streams from 2.99 to 11.82 map Mbps
(`data/round17_rule_cost.json`):

| rule | what it forbids | measured cost |
|---|---|---|
| indexable representations | a variable-length descriptor symbol | +4.47 to +7.16% of logical (+0.2255 map Mbps at the 5 Mbps rung) |
| no serial entropy dependency | conditioning on the preceding symbol too | +0.85 to +1.41% of logical on top |
| indexable representations | a compressed walk-checkpoint plane | +2.11 to +3.29% of logical |
| indexable, and no entropy coder | a context-coded root directory | about 1.13% of wire, blocked by both rules |
| byte-aligned records | a 14-bit motion record | 1.52% of wire |
| the 2x draw ceiling | round 15's motion table | 1.7% of wire, about 1.3% BD-rate |
| bounded decoder | leaves below 8x8 | 0.02% of logical, bracketed above by 0.75% |
| six-bit map transport | three payload bytes per four map bytes | exactly 33.3% |

The rule one would expect to bind, the bounded decoder, is the cheapest; what binds is the pair that holds the index
together, no entropy coder and per-fragment random access, and they act on the same bits.

**Round T, the transform and entropy study (2026-09-19): why AV1's tools do not transfer.** Time-boxed, against the
vanilla-shader envelope (per-fragment random access, no new inter-frame state, GLSL 330, at most twice the measured
decode time per draw; `data/transform_study.json`, `data/walk_cost.json`):

- **T1, a bounded integer transform** (4x4 Hadamard on luma, fixed zigzag truncation, one chroma pair per cell), at
  matched payload bytes on 2,500 real blocks per size: three to ten times worse per byte than the palette modes it would
  replace (8x8: 14 bytes give MSE 59.4 against the palette's 15.9; 32x32: 162 bytes give 82.1 against 134 bytes'
  44.6). The proxy is hard-edged, and a two-colour palette with a one-bit selector codes a step edge exactly. On
  natural excerpts the transform does become competitive, but only above about 40 bytes per 8x8 block, and the ladder
  spends 2.2 to 15.2 logical bytes per 32x32 root block: an order of magnitude below where a transform starts to win.
- **T2, chunked entropy coding** (static canonical Huffman per payload category, tables in the resource pack): real on
  rate, 20.8-24.4% of the payload (11.4% of the map rate) at the 5 Mbps rung, but it removes random access inside a
  record, and GPU fragments run in lockstep, so a subgroup pays for its longest record. At a measured 0.6385 ms per
  dependent lookup per 1080p draw on the UHD 630, only palette patterns and solid colours (14.1% of the payload) fit
  the draw ceiling, which nets **1.92% of the map rate**: an ordinary syntax round, not a breakthrough.
- **Verdict:** neither path is worth pursuing inside the vanilla shader constraints. The binding constraint on this
  format is per-fragment random access, not the six-bit transport and not the syntax: transform coding needs byte
  budgets the ladder never reaches, and entropy coding needs sequential access inside a record. The study's shader
  costs are floors (a synthetic dependent-lookup chain, not a decoder), and its tables were built from the measured
  stream.

**The decoder's own ceiling.** Every format feature must keep the reference's GLSL decode within twice the draw time of
the pre-round-7 form on the same stream. At the most expensive rung (`round19-refine2-compact_final-rate20`, 19.6118
map Mbps, 20 frames at 120 interleaved repeats on the UHD 630) the final form draws in 30.1697 ms against a ceiling of
30.7433 ms: 1.87% of headroom, 1.36 standard errors (`data/frontier_gpu.json`). This ceiling is what forbids round 15's
motion table (about 1.3% BD-rate, 0.08 standard errors over the line). mcav's own post chain, which splits the
reference's decoder into passes, decodes a new 1080p ship frame in 7.9 ms on the UHD 630
([design doc §9](../mcv2-integration.md)).

**Where the budget binds depends on the frame rate.** At 1080p60 the payload alone (5.74 map Mbps with all addressing
free and ideal zero-order entropy coding) exceeded the old 5 Mbps target; at 1080p30 it is 3.74, and what exceeded the
6 Mbps budget was addressing, which ordinary syntax work (rounds 7 to 9) removed (`data/round19_cost_audit.json` holds
the final field accounting).

## AV1 reference points

84 reference encodes and 6,048 scored frames (`data/frontier_av1.json`, `data/frontier_av1_audit.json`): SVT-AV1 1.7.0
at presets 6 and 10 and libaom at cpu-used 6, through ffmpeg 6.1.1, at seven target rates, on the proxy and the three
public excerpts. aom6 and svt10 use CBR with a requested two-second buffer; svt6 is VBR, because SVT 1.7's low-delay CBR
forces presets below 8 to 8. Rates are the actual container rate of the one-second clip; decoding is ffmpeg's software
AV1 decoder, and VMAF is ffmpeg 7.0.2's libvmaf on the decoded RGB. On the 1080p60 proxy:

| target Mbps | aom6 CBR: Mbps, VMAF mean / min | svt10 CBR | svt6 VBR |
|---:|---|---|---|
| 3 | 3.1174, 90.028 / 88.659 | 2.8681, 74.365 / 43.913 | 3.1063, 88.906 / 86.378 |
| 4 | 3.9208, 90.874 / 88.881 | 3.7993, 81.413 / 56.223 | 4.6177, 90.781 / 88.726 |
| 5 | 4.7097, 91.532 / 88.941 | 4.7644, 84.437 / 61.567 | 6.1790, 92.298 / 90.561 |
| 6 | 5.4912, 92.322 / 89.412 | 5.6492, 86.263 / 64.239 | 8.0656, 93.293 / 91.765 |
| 8 | 7.0390, 93.235 / 89.604 | 7.4889, 89.199 / 73.439 | 11.2716, 94.553 / 92.960 |
| 10 | 8.5683, 94.030 / 89.972 | 9.4459, 90.786 / 77.023 | 14.6937, 95.744 / 94.316 |
| 12 | 9.9938, 94.500 / 90.034 | 11.4698, 91.434 / 77.146 | 17.6768, 96.326 / 94.525 |

AV1 has no GLSL decoder and no way to stream through map packets, so these are references for picture quality at a
rate, not alternatives inside Minecraft. `data/references_final.json` holds the first research phase's references:
libaom AV1, VP9, H.264 and H.265 at CRF 32 on eight 1080p60 frames of the proxy and of a procedural panning scene, scored
with PSNR and SSIM.

## The data files

Copied byte for byte from gpu-codec's `results/` at commit `85445433aeb9f8a35a5ce528d47d8829976d1401`:

| file | size | what it holds |
|---|---:|---|
| `data/frontier.json` | 1,267,680 | the 1080p60 frontier: both ladders, all 630 measured points with their settings, commit and stream hashes, and the named target recomputed each round |
| `data/frontier_1080p30.json` | 220,819 | the 105 1080p30 points, their ladders, GPU timings and the (stale) recommendation |
| `data/frontier_av1.json` | 1,543,930 | the 84 AV1 reference encodes, each with its command, encoder log and per-frame scores |
| `data/frontier_av1_audit.json` | 523 | the audit of that file |
| `data/references_final.json` | 9,347 | the first phase's CRF 32 references (AV1, VP9, H.264, H.265) |
| `data/round19_cost_audit.json` | 96,961 | the last kept round's exact field accounting |
| `data/round17_rule_cost.json` | 4,632 | what the format's rules cost |
| `data/transform_study.json` | 282,324 | Round T, with its shader timings and the assumptions behind every projection |
| `data/walk_cost.json` | 1,214 | the dependent-lookup timings Round T charges |
| `data/frontier_gpu.json` | 30,357 | GPU decode timings of the ladder winners and the draw-ceiling measurement |

`frontier.json` and `frontier_av1.json` are over 1 MB because they hold every measured point and every reference
encode with its settings and scores; the documentation's charts and any re-analysis need all of them, and the research
data they summarise (93 GB of streams and sources) is not part of mcav.
