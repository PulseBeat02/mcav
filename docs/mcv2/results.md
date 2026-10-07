(mcv2-results)=
# MCV2 results

What the MCV2 codec reaches, what mcav ships and why, and what limits it, with the measurements behind every number.
The research ran in the gpu-codec repository; its result files at commit `85445433aeb9f8a35a5ce528d47d8829976d1401` are
copied unchanged into `docs/mcv2/data/` (list at the end). mcav's own port was measured by the mcv2 stage
([Measured in mcav](#measured-in-mcav)), and the comparison with H.264, VP9 and AV1 and the cost of dithered maps were
measured for the documentation ([The codec comparison](#the-codec-comparison), [Dithered maps](#dithered-maps)). The
format itself is in [format.md](format.md), the encoder in [encoder.md](encoder.md), and how mcav runs the codec in
Minecraft in [the design doc](../mcv2-integration.md); the MCV2 chapter explains all of it for a reader who is new to it.

The timing tables retain the original measurements and their workloads; they do not guarantee the same throughput
on another server. See [troubleshooting](using.md#troubleshooting) for encoder configuration and client compatibility.

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

## The per-frame tables, rounds 16 to 19

The last four kept rounds, as the research recorded them (its experiment ledger at commit `85445433`, rounds 16 to
19), all on the 1080p60 proxy. The frontier BD-rates are the table above; what follows is what each round changed and
the counts it was sized by.

- **Round 16, the endpoint table** (flag 512). A pattern palette's two RGB endpoints are six bytes of its record. A
  frame names far fewer distinct pairs than it has pattern leaves: **82 to 276 distinct pairs per frame**, so a
  one-byte index (at most 255 entries, which held all 60 frames of both rungs that mattered) names a pair in a table at
  the end of the frame. Before the round, `palette_endpoints` was **18.11%** of the wire at the 5 Mbps rung (17.16% at
  3 Mbps); pattern palettes held **93%** of the leaves that carry endpoints, so only they use the table. Measured at
  nine seed lambdas against round 13: map rate **-6.035% median** (-6.50% best, better on 8 of 9), VMAF **+0.0000 on
  every pair**: a lossless re-serialization.
- **Why solid colours stay out of it, and why the motion table lost.** The research's rule: a dictionary pays when the
  entry is large against its index and reuse is high. Endpoint pairs are 48 bits against an 8-bit index, **6:1 at a
  reuse of 1.8**, and pay. Solid colours are **3:1 at 1.9** and do not: folded into the table they cost **-36.4%** of
  `solid_rgb` in a table of their own at 5 Mbps and **-32.7%** folded in as degenerate pairs (-69.3% and -87.3% at
  3 Mbps), and would push the entries to 428, past a one-byte index. Motion vectors were **2:1** and lost (round 15).
  A dictionary of residual bodies measured a reuse of **1.05**.
- **Round 17, the selector tables** (flags 1024, 2048, 4096). A pattern's selector word (its orientation byte and
  `size / 8` axis bytes) is named by a one-byte index into a table per block size. Fifteen seed pairs: map rate
  **-6.301% median** (-7.542% best, at 3.97 Mbps), VMAF delta **0.0000 on all fifteen** (lossless; the near-lossless
  point is byte-identical, because no table pays there). The first form read the three table sizes on every
  fragment's descriptor path: 27.81 ms against the 27.20 ms draw ceiling. Moving table presence into the header flags,
  with the tables anchored at the end of the frame, gave **+10.77%** headroom for no wire cost.
- **Round 18, RGB565 endpoints** (flag 8192). Entries of the endpoint table as two RGB565 colours, four bytes instead
  of six, chosen per frame by the encoder's own rate-distortion sum (the frame is encoded with and without, round 18's
  second pair of trials). Fifteen seed pairs: map rate **-5.221% median** (-9.690% best, at 2.99 Mbps), VMAF up on 13 of
  15, worst change -0.0069. The round was launched with a written prediction of 0.4 to 0.7% BD-rate; it scored +5.4770%,
  because cheaper endpoints let the leaf search choose pattern leaves in more places.
- **Round 19, half-pixel motion** (encoder only). Round 2's mechanism (+0.4699% then) re-tested once rounds 16 to 18
  had shrunk everything around motion: `motion_vectors` had grown from 12.16% to 15.91% of the wire. +1.7215%: rate
  -0.433% to -8.830% at 5 to 20 map Mbps for VMAF changes of +0.07 to -0.21, and **+22.411% at the near-lossless point**
  for identical VMAF, where every half-pixel vector is paid for and buys nothing. That point anchors the integral.
- **No round 20.** Owner decision 6 allowed quarter-pixel motion only if round 19 was kept at 2% or more. A stored
  motion delta is the vector less the global vector, at most 24 pixels apart, so it needs seven signed bits an axis;
  quarter pixels would need eight, all sixteen bits of a two-byte record.

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

  In those measurements all three met the 1080p30 gate (p95 under 32 ms at 12 threads) with the native kernels;
  `live` was chosen as the default because it was the slowest rung that did. **1080p60 was not met** by any preset:
  `live-fast` needed 19.72 ms (proxy)
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

## Measured in mcav

What the mcav-mcv2 stage measured on mcav's own port (2026-09-25 to 09-27), with the method of each table. Everything
ran on the build machine: an Intel i7-8700 (6 cores, 12 threads, AVX2, 3.2 to 4.6 GHz), shared with other work (every
timing below gives the host's load average), on Temurin 25.0.4 (HotSpot C2), the JVM the common server images ship.
The sources are the 1080p30 proxy (SHA-256 `acf2ab47...05bd`), the 1080p60 proxy (`ea6a4463...8cc7`) and real
Minecraft gameplay: frames 120 to 239 of Xiph's Twitch recording `MINECRAFT.y4m`
(<https://media.xiph.org/video/derf/twitch/y4m/MINECRAFT.y4m>, 1920x1080 at 60 fps; excerpt `9053e412...`, its RGB24
conversion `f69ab402...`), and every second frame of it for 30 fps. The live curves loop a source forward and back to
600 frames (660 with 60 warm-up frames for timings). VMAF is the frontier's filter; the map rate is what the transport
charges; the zlib rate deflates every page's map colours at zlib's default level, as the game's packet compression
does, and adds the 18-byte envelope (`tools/mcv2/Mcv2Bench.java`). BD-rates compare curves at equal VMAF mean over the
shared quality range (`tools/mcv2/bd_rate.py`, a cubic fit).

### The live presets

The ladder, each rung's rate against `ship` at equal VMAF mean (600-frame curves; map / after zlib) and its VMAF at its
default lambda:

| preset | search | lambda | 1080p30 proxy: BD-rate, VMAF mean / min | 30 fps gameplay: BD-rate, VMAF mean / min | 1080p60 pair: BD-rate proxy; gameplay |
| --- | --- | ---: | --- | --- | --- |
| `ship` | the reference's exhaustive search, four trials, Java kernels | 65.26 | 0 / 0, 77.66 / 70.12 | 0 / 0, 90.95 / 71.65 | 0; 0 |
| `live` (default) | `LiveSearch.LIVE`, quarter-resolution motion level, motion lambda | 72 | -5.1% / -3.4%, 75.69 / 69.03 | +7.9% / +6.9%, 76.10 / 65.21 | -8.0% / -6.1%; -2.2% / -2.2% |
| `adaptive` | `live` on calm pictures, `live-fast` in motion | 72 / 55 | -5.5% / -3.8%, 75.7 | +24.9% / +14.3%, 76.1 | - |
| `live-fast` | `LiveSearch.LIVE_FAST`: SKIP to 60 lambda, splits at 900 and 600, no quarter-resolution level | 55 | +17.7% / +18.4%, 76.11 / 71.90 | +29.4% / +17.1%, 76.10 / 66.31 | +14.8% / +15.6%; +16.9% / +6.6% |

Rejected rungs: `live-fast` with cell-mean fits (+39.9% / +38.6% on the proxy) and `live-fast` without patterns (+33.0%
/ +20.4% on gameplay, +35.8% / +36.9% on the proxy), both over the +30% a faster preset may give up.

**The 1080p30 gate** (p95 of the encode time of a frame under 32 ms): one frame at a time as a screen encodes it,
every frame verified, the final native AVX2 kernels, 660 frames with 60 warm-up frames left out, three interleaved
rounds at host load 11 to 41, then a tie-breaker's three rounds for `live` on gameplay (load 8 to 38):

| rung | threads | 1080p30 proxy p95 (runs) | 30 fps gameplay p95 (runs) | CPU ms per frame, proxy; gameplay | map / zlib Mbit/s, proxy; gameplay |
| --- | ---: | --- | --- | --- | --- |
| `live` | 12 | **20.09** (20.09 / 20.35 / 20.54) | **31.21** (31.21 / 31.62 / 31.56; 31.82 / 31.89 / 31.24) | 141; 210 | 2.80 / 1.83; 13.04 / 8.29 |
| `adaptive` | 12 | **20.37** (20.49 / 20.37 / 20.51) | **27.44** (27.91 / 28.05 / 27.44; 27.04 / 27.00 / 27.74) | 142; 183 | 2.80 / 1.83; 15.23 / 8.63 |
| `live-fast` | 12 | **17.61** (17.61 / 19.41 / 19.39) | **27.24** (27.95 / 28.13 / 27.24) | 128; 182 | 3.28 / 2.12; 15.26 / 8.65 |
| `live` | 6 | **22.63** (22.69 / 22.63 / 23.40) | 36.37 (36.37 / 36.51 / 37.45): not met | 103; 154 | |
| `adaptive` | 6 | **22.53** (22.77 / 22.70 / 22.53) | **31.55** (31.86 / 31.70 / 31.55) | 101; 137 | |
| `live-fast` | 6 | **20.93** (21.95 / 21.25 / 20.93) | **31.81** (32.17 / 31.81 / 32.30) | 96; 135 | |

**The 1080p60 gate** (owner addendum 14): the throughput p95, the interval between successive finished (written and
verified) frames fed back to back, under 16 ms; and the latency p95, from arrival to finished with frames arriving every
16.667 ms, under 33 ms with nothing dropped and the queue bounded. `live-fast`, the fastest rung inside the quality
cap, 660 frames with 60 warm-up frames (five keyframes each), verified, native AVX2, three interleaved rounds at host
load 12 to 35:

| threads | source | throughput: interval p95, three rounds (frames a second) | latency p95, three rounds (largest queue) | one frame at a time: p95 | CPU ms per frame |
| ---: | --- | --- | --- | --- | --- |
| 12 | 1080p60 proxy | 20.93 / **19.72** / 20.67 ms (70.6 to 72.7) | 132.71 / **25.91** / 26.77 ms (6, 0, 0) | 19.09 / **18.56** / 19.00 | 140 to 143 |
| 12 | 60 fps gameplay | 31.78 / **31.34** / 32.24 ms (48.5 to 49.2) | unbounded: 49 frames a second arrive at 60 (queue 111 to 122) | 30.44 / 30.05 / **29.00** | 203 to 207 |
| 6 | 1080p60 proxy | 21.61 / 20.46 / **19.93** ms (59.1 to 62.2) | 130.96 / **84.90** / 257.65 ms (7, 4, 14) | **20.76** / 20.87 / 21.40 | 107 to 110 |
| 6 | 60 fps gameplay | 32.74 / **32.70** / 34.15 ms (40.7 to 41.3) | unbounded (191 to 200) | 34.01 / 35.33 / **33.28** | 157 to 159 |

Frames average 8.3 KiB on the proxy and 46.6 KiB on gameplay; keyframes reach 22 and 97.7 KiB. A keyframe's search and
write take 14.0 ms against 15.1 for a P frame on the proxy, and 20.1 against 21.7 on gameplay; with every keyframe and
the frame after it left out, the interval p95 is the same to 0.05 ms, so keyframes do not set it.

**The levers**, each kept only inside the quality cap (VMAF mean at least 75 at the default, and at most +10% of
`ship`'s rate for `live`, +30% for a faster preset). CPU time per frame is the load-robust speed measure, compared
between runs of the same period:

| lever | what was built | measured | verdict |
| --- | --- | --- | --- |
| content-adaptive mode pre-selection | per-block statistics gating the intra candidates, learned from `ship`'s own choices on gameplay (2.84 million leaves) | CPU +2 to +8%: the statistics cost more than the candidates they pruned; the compact classes, the dear part, cannot be gated without losing 17 to 45% of their area | rejected |
| temporal reuse of the co-located decision | the leaf the previous frame chose there tried first, the search stopped when it costs at most 1.1 times what it did | CPU -8 to -24%; gameplay **+18.3%** map / +15.6% zlib | rejected (over the cap) |
| thread scaling | the verification parses the written frame once; the motion field filled inside the parallel search | 1 to 12 threads: 365 / 196 / 97 / 65.5 / 55.2 / 55.6 ms per 1080p60 proxy frame (1, 2, 4, 6, 10, 12); about 9 ms a frame is serial | kept (output identical) |
| one fitted quantizer | compact records at the one quantizer their fitted values need (`FIT_ONE`) | CPU -8 to -15% on gameplay, p95 -14%; rate within 0.4% of the best pair | kept |
| half-resolution motion | 32- and 16-pixel blocks search at half resolution first (`HALF_MOTION`) | CPU +3 to +9%; **-3%** rate at equal VMAF on both sources | kept: what brings gameplay inside the cap |
| three compact classes instead of five | the DC and the two 4-bit 4x4 grids | CPU **-15%**; rate against `ship` +8.9% (five: +8.4%) on gameplay, -3.3% (five: -1.7%) on the proxy | kept |
| a frame-time budget | past the budget, the rest of the superblocks SKIP or one colour | 22 ms budget: p95 31.9 / 34.0 ms, gameplay PSNR 16.5 dB against 31.8 | kept as an option, off by default |
| two-stage mode decision | cheap estimates of every candidate, the best N in full | N = 1: +33.9% on gameplay; N = 2: +18.8% / +15.4% for -11% CPU | rejected |
| split-above | a 32-pixel block split without trying its leaves above a cost | T = 3200 lambda: -12.9% CPU, -17.7% p95, +7.0% map on gameplay | built (`LiveSearch.splitAbove`), in no preset |
| quarter-resolution motion level | a level before the half-resolution one (`QUARTER_MOTION`) | CPU within noise; rate -1.3% (gameplay) and -1.9% (proxy) at equal VMAF | in `live` |
| motion lambda | lambda rises with the source's motion (`MotionLambda`) | gameplay at the default: 27.6 to 13.1 map Mbit/s at VMAF 76.1, CPU -33% | in `live` and `live-fast` |
| native SIMD kernels | the pixel kernels in C++17 through the FFM API, bit-exact with Java | whole encoder, 12 threads: gameplay 664 to 249 ms CPU per frame, proxy 310-333 to 177 | on by default |
| pipelining | frame N+1 searched while frame N is verified | 1080p60, 12 threads: 74 against 63 frames a second on the proxy, 52 against 45 on gameplay, the same interval p95 | kept |
| verification before the next search | three forms of priority lane | the search slowed as much as the verification gained: 67, 61, 57 and 59 frames a second against 74 | rejected |
| aligned buffers, LTO, PGO | on the native kernels | about 2%, about 1%, and no net gain over three attempts | rejected |
| SVT-AV1's early termination, depth prediction | on `live-fast`, one at a time and combined | four combined: +56.1% / +38.6% on gameplay (over the cap); single levers inside the cap 1 to 3% of time each | rejected |

### The native kernels

Each kernel on 64 random cases per block size, Java against the x86-64 levels after a warm-up over every kernel, the
median of nine passes on one thread (`NatBench`, the mcv2 stage's kernel benchmark), nanoseconds per call:

| kernel | Java (8 / 16 / 32 px) | AVX2 (8 / 16 / 32 px) | speed-up | best level at 16 px |
| --- | --- | --- | --- | --- |
| `predicted` | 256 / 788 / 2,660 | 126 / 340 / 1,132 | 2.0x / 2.3x / 2.4x | AVX2 |
| `solid` | 227 / 732 / 2,895 | 97 / 262 / 898 | 2.3x / 2.8x / 3.2x | AVX2 |
| `palette` | 403 / 1,403 / 5,341 | 139 / 378 / 1,164 | 2.9x / 3.7x / 4.6x | AVX2 |
| `intra_grid` | 951 / 2,464 / 8,054 | 301 / 675 / 1,918 | 3.2x / 3.7x / 4.2x | AVX2 |
| `residual_grid` | 990 / 2,911 / 9,897 | 355 / 804 / 2,255 | 2.8x / 3.6x / 4.4x | AVX2 |
| `reduced` | 885 / 2,716 / 9,503 | 309 / 748 / 2,165 | 2.9x / 3.6x / 4.4x | AVX2 |
| `compact` | 592 / 1,864 / 6,613 | 258 / 594 / 1,771 | 2.3x / 3.1x / 3.7x | AVX2 |
| `predict` | 192 / 668 / 2,144 | 86 / 158 / 462 | 2.2x / 4.2x / 4.6x | AVX2 |
| `fit` | 458 / 1,284 / 4,332 | 293 / 572 / 1,553 | 1.6x / 2.2x / 2.8x | AVX2 |
| `cell_sums` | 287 / 1,053 / 4,055 | 244 / 909 / 463 | 1.2x / 1.2x / 8.8x | SSE4.1 |
| `luma_residual` | 364 / 1,440 / 5,233 | 227 / 767 / 2,870 | 1.6x / 1.9x / 1.8x | SSE4.1 |
| `cluster` | 878 / 881 / 3,191 | 256 / 291 / 880 | 3.4x / 3.0x / 3.6x | AVX2 |
| `palette_cluster` | 1,460 / 5,603 / 22,089 | 1,464 / 5,623 / 21,620 | 1.0x / 1.0x / 1.0x | AVX2 |
| `finish` | 184 / 618 / 2,475 | 131 / 344 / 1,194 | 1.4x / 1.8x / 2.1x | SSE4.1 |
| `finish_pattern` | 176 / 543 / 2,123 | 135 / 291 / 841 | 1.3x / 1.9x / 2.5x | AVX2 |
| `seeded` | 2,128 / 2,224 / 2,212 | 800 / 851 / 873 | 2.7x / 2.6x / 2.5x | AVX2 |
| `load_source` | 179 / 699 / 2,671 | 59 / 111 / 394 | 3.0x / 6.3x / 6.8x | AVX2 |
| `halve` | 75 / 274 / 1,106 | 97 / 274 / 1,056 | 0.8x / 1.0x / 1.0x | SSE4.1 |
| `ycocg` | 115 / 440 / 3,769 | 64 / 357 / 724 | 1.8x / 1.2x / 5.2x | SSE4.1 |
| `residual_target` | 174 / 694 / 2,723 | 94 / 261 / 865 | 1.9x / 2.7x / 3.1x | AVX2 |
| `cell_means` | 125 / 315 / 998 | 181 / 330 / 945 | 0.7x / 1.0x / 1.1x | scalar |

A bare downcall costs 6.1 ns with `Linker.Option.critical` (10.7 ns without); with its argument checks a call costs 50
to 90 ns before any work, which is why the smallest kernels gain nothing. `palette_cluster`, `halve`, `cell_means` and the
narrow `cell_sums` were vectorized afterwards (`palette_cluster` 4.8 to 7.5 times Java in the final libraries).

**The SIMD matrix.** Every level is the same kernel source compiled for its architecture's baseline plus its own
extensions (Zig 0.16.0, `-O2 -ffp-contract=off -fwrapv`), exported as `mcv2_<level>_<kernel>` and chosen at run time.
"Emulator" is the standalone harness (`src/test/native/mcv2`) under AddressSanitizer and UndefinedBehaviorSanitizer and
on the shipped library, its digest equal on every level and CPU. Speed is `NatBench` on the i7-8700 only: the median
speed-up over Java of all kernels at 8, 16 and 32 pixels.

| platform | level | verified natively | verified in an emulator | dispatch picks it on | speed |
| --- | --- | --- | --- | --- | --- |
| Linux x86-64 | scalar | every JVM test, forced by `-Dmcv2.native.level=scalar` | SDE, qemu64 | never by itself | median 1.35x (slower than C2 on the reconstruction kernels) |
| Linux x86-64 | SSE2 | yes | qemu64, SDE Merom | CPUs without SSE4.1 (kvm64-style VPS) | median 1.85x |
| Linux x86-64 | SSE4.1 | yes | SDE | CPUs without AVX2 | median 2.26x |
| Linux x86-64 | AVX2 | yes; the Paper server end to end | SDE Haswell, Skylake server | Haswell and later; Skylake-SP and Cascade Lake | median 2.95x; the live encoder 2.7x less CPU on gameplay |
| Linux x86-64 | AVX-512 (Ice Lake set) | no (the build machine has none) | SDE Ice Lake and Sapphire Rapids | Ice Lake, Sapphire Rapids, Zen 4 | not measured |
| Linux x86-64, musl | all | the same library through Alpine's loader | - | - | - |
| Linux AArch64 | scalar, NEON | no ARM64 machine | qemu Cortex-A72; SVE at 16 bytes | every ARMv8-A | not measured |
| Linux AArch64 | SVE 256 | no | qemu at SVE 32 bytes | Graviton 3 | not measured |
| Linux AArch64 | SVE 512 | no | qemu at SVE 64 bytes | A64FX-class | not measured |
| Windows x86-64 | scalar to AVX-512 | in a Windows VM: the native JVM test classes 104 of 104, `MCV2 kernels: native avx2 (windows-x86_64)` | - | as Linux x86-64 | not measured |
| Windows AArch64 | scalar, NEON | **untested** (headers only) | - | Snapdragon X-class | not measured |
| macOS x86-64 | scalar to AVX2 | the previous libraries in a macOS VM (73 of 73); the final ones by their headers | - | Intel Macs | not measured |
| macOS AArch64 | scalar, NEON | **untested** (headers only) | - | Apple silicon | not measured |

AArch64 CPUs whose SVE is 128 bits wide (Graviton 4, Neoverse V2) stay on NEON. macOS gets no AVX-512 build: it turns
the AVX-512 register state on only at a thread's first use, so the `xgetbv` check cannot see it.

### The decoder, pass by pass

`tools/mcv2/shader_timing.py`: the pack's own `entity_outline.json` pass for pass in moderngl with `GL_TIME_ELAPSED`
queries, a 6x3 screen three blocks in front of the camera (57% of the view), the GPU kept busy between chains, warm,
the ship stream at 1080p, per rendered frame: one that brings a new P frame, a new keyframe, and one without new video.
Every change bit-exact (`shader_check.py` on the UHD 630 and llvmpipe, the in-game captures 300 of 300). The llvmpipe
"after" column was measured before the last lever (the one that took the UHD 630 from 12.6 to 7.9 ms), at a machine
load of 10 to 17.

| Intel UHD 630 (EGL), ms | new P frame: before | after | new keyframe: before | after | no new video: before | after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| pages CRC (`mcv2_crc` + `mcv2_pages`) | 5.71 | 0.20 | 10.31 | 0.18 | 5.67 | 0.20 |
| bytes + status | 0.03 | 0.03 | 0.05 | 0.03 | 0.03 | 0.03 |
| resolve (new pass) | - | 0.33 | - | 0.40 | - | 0.01 |
| decode | 41.56 | 2.14 | 52.56 | 3.11 | 1.11 | 0.46 |
| screen (`mcv2_view` + `mcv2_screen`) | 11.50 | 1.87 | 11.62 | 1.88 | 12.07 | 2.30 |
| blits, keyframe, state, outline | 2.69 | 3.31 | 2.87 | 3.21 | 2.77 | 3.57 |
| **chain** | **61.5** | **7.9** | **77.4** | **8.8** | **21.7** | **6.6** |

| Mesa llvmpipe (GLX, CPU), ms | new P frame: before | after | new keyframe: before | after | no new video: before | after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| pages CRC (`mcv2_crc` + `mcv2_pages`) | 0.77 | 0.54 | 1.41 | 0.23 | 0.91 | 0.88 |
| bytes + status | 1.86 | 2.16 | 2.38 | 1.23 | 1.76 | 2.20 |
| resolve (new pass) | - | 3.58 | - | 2.18 | - | 0.35 |
| decode | 175.24 | 18.34 | 287.26 | 12.63 | 4.48 | 4.75 |
| screen (`mcv2_view` + `mcv2_screen`) | 54.47 | 11.95 | 39.02 | 5.12 | 50.80 | 10.19 |
| blits, keyframe, state, outline | 29.85 | 31.86 | 17.50 | 14.93 | 27.29 | 32.51 |
| **chain** | **262.2** | **68.4** | **347.6** | **36.3** | **85.2** | **50.9** |

With the final pack on the UHD 630 (new P frame / keyframe / no new video): the first live profile's stream 8.0 / 8.3 /
6.5 ms, `low_bandwidth` 8.3 / 13.3 / 7.5, the research's lambda-44.86 stream 8.0 / 7.7 / 5.9, and the shipped `live`
7.4 / 7.9 / 5.9 ms on the 1080p30 proxy and 8.7 / 8.6 / 5.7 on 30 fps gameplay.

### Far viewers

The lab server (Paper on Temurin 25, its far listener on a loopback port) with the host's `tc netem` on that port only
(delay per direction, jitter, loss, rate, MTU 1500), the headless client connected through it, pre-encoded 1080p
counter streams played with `/mcav mcv2 stream` off the tick, about 160 s of video per run, backpressure on (the
default) and off (no backlog limit and no unsent cap), the server on BBR and once on CUBIC. "Held" counts the frames a
viewer was not sent, for the backlog and then the frames that predicted from a held one; the game's round trip is a
keep-alive answered on the client's network thread; "A/V offset" is estimated from the one-way delay plus the time
the server's backlog (p95) takes to drain, not measured end to end. "live" here is the first live profile at 60 fps
(5.39 map Mbit/s), "live, keyframe reference" its variant that predicts from the last keyframe (17.2 map Mbit/s).

| link | stream | backpressure | frames sent / held | round trip p50 / p95 / max (ms) | server backlog p95 / max (KB) | Mbit/s | A/V offset p95 (ms) |
| --- | --- | --- | --- | --- | --- | ---: | ---: |
| nearby (15 ms ±2) | none (the game only) | - | - | 31 / 35 / 52 | - | - | - |
| | live 1080p60 | on | 9619 / 0.0% | 32 / 36 / 116 | 36 / 79 | 3.6 | 96 |
| | live 1080p60 | off | 9605 / 0.0% | 32 / 37 / 41 | 35 / 119 | 3.6 | 93 |
| | ship 1080p30 | on | 4797 / 0.0% | 32 / 37 / 50 | 29 / 60 | 2.3 | 115 |
| | ship 1080p30 | off | 4802 / 0.0% | 32 / 38 / 60 | 29 / 60 | 2.3 | 116 |
| | live, keyframe reference | on | 9502 / 0.6% | 34 / 41 / 57 | 156 / 188 | 9.5 | 147 |
| | live 1080p60, CUBIC | on | 9674 / 0.0% | 33 / 39 / 55 | 44 / 82 | 3.6 | 114 |
| other continent (100 ms ±10) | none (the game only) | - | - | 201 / 217 / 372 | - | - | - |
| | live 1080p60 | on | 8969 / 6.6% | 215 / 239 / 693 | 39 / 136 | 3.4 | 192 |
| | live 1080p60 | off | 9588 / 0.0% | 214 / 259 / 759 | 47 / 377 | 3.6 | 205 |
| | ship 1080p30 | on | 4799 / 0.0% | 208 / 228 / 572 | 29 / 114 | 2.3 | 202 |
| | ship 1080p30 | off | 4959 / 0.0% | 208 / 222 / 525 | 29 / 142 | 2.3 | 202 |
| | live, keyframe reference | on | 9304 / 3.0% | 227 / 251 / 669 | 162 / 188 | 9.3 | 240 |
| | live 1080p60, CUBIC | on | 9245 / 3.1% | 213 / 228 / 527 | 41 / 137 | 3.5 | 195 |
| lossy far (100 ms ±20, 1% loss) | none (the game only) | - | - | 205 / 335 / 559 | - | - | - |
| | live 1080p60 | on | 8885 / 7.5% | 222 / 412 / 910 | 44 / 146 | 3.3 | 208 |
| | live 1080p60 | off | 10160 / 0.0% | 336 / 1572 / 3180 | 5149 / 6022 | 3.6 | 11544 |
| | ship 1080p30 | on | 4733 / 2.4% | 263 / 591 / 825 | 29 / 142 | 2.2 | 209 |
| | ship 1080p30 | off | 4788 / 0.0% | 267 / 544 / 958 | 2597 / 3222 | 2.3 | 9136 |
| | live, keyframe reference | on | 8584 / 10.4% | 569 / 987 / 3251 | 170 / 219 | 8.7 | 258 |
| | live 1080p60, CUBIC | on | 1812 / 81.1% | 647 / 1503 / 2136 | 135 / 138 | 0.7 | 1641 |
| thin link (40 ms ±5, 0.2% loss, 6 Mbit/s) | none (the game only) | - | - | 82 / 90 / 211 | - | - | - |
| | live 1080p60 | on | 8903 / 8.3% | 122 / 182 / 518 | 57 / 137 | 3.4 | 176 |
| | live 1080p60 | off | 9603 / 0.0% | 123 / 319 / 1248 | 39 / 428 | 3.6 | 129 |
| | ship 1080p30 | on | 4916 / 0.0% | 110 / 156 / 437 | 29 / 69 | 2.3 | 142 |
| | ship 1080p30 | off | 4802 / 0.0% | 110 / 130 / 563 | 29 / 120 | 2.3 | 142 |
| | live, keyframe reference | on | 5562 / 42.0% | 2685 / 4847 / 5240 | 212 / 262 | 5.6 | 344 |
| | live 1080p60, CUBIC | on | 8832 / 7.8% | 122 / 266 / 451 | 58 / 138 | 3.3 | 181 |

TPS was 20.0 in every run but one (19.7: lossy far without backpressure). On the thin link the keyframe-reference
variant (9.5 Mbit/s on a 6 Mbit/s link) kept the server's backlog at 212 KB, but TCP had 3.7 MB in flight and game
packets waited 2.7 s (p50) to 4.8 s (p95): what congestion control has put in flight, no backlog limit takes back.

### The server's tick

The lab's Paper server, one connected player, synthetic live screens encoding the 1080p60 proxy inside the shared
budget, 60 s warm, 120 s of ticks recorded every tick by a lab plugin, two interleaved repetitions. The first
measurement (the first live profile) and the final one (the final code, `adaptive`, which codes the proxy as `live`
does, bit for bit):

| screens | budget | TPS | MSPT mean / p95 / p99 / max (ms) | encode rate per screen | GC |
| --- | --- | ---: | --- | --- | --- |
| 0 | - | 20.0 | 0.37 / 0.55 / 0.73 / 34 | - | - |
| 1 | default (6 of 12) | 20.0 | 0.45 / 0.63 / 1.00 / 30 | 24.3 frames a second | 53 collections, longest 3.9 ms |
| 2 | default (6 of 12) | 20.0 | 0.52 / 0.83 / 1.84 / 44 | 13.7 + 13.7 | 59, 4.4 ms |
| 1 | all 12 | 20.0 | 0.53 / 1.08 / 2.47 / 43 | 28.4 | 65, 3.3 ms |
| 2 | all 12 | 20.0 | 0.77 / 2.21 / 3.55 / 45 | 16.4 + 16.4 | 69, 5.5 ms |

| screens (final code, default budget) | TPS, repetition 1 / 2 | MSPT p95 | MSPT p99 | MSPT max |
| --- | --- | --- | --- | --- |
| 0 | 19.83 / 20.00 | 0.72 / 0.53 ms | 1.34 / 0.72 | 3.3 / 1.2 |
| 1 | 20.00 / 19.29 | 0.90 / 0.78 ms | 2.89 / 2.56 | 5.6 / 35.0 |
| 2 | 20.00 / 20.00 | 0.91 / 0.79 ms | 3.77 / 2.77 | 36.6 / 12.9 |

The two dips below 20 TPS are one 1.3 s and one 4.5 s gap between ticks, with and without screens alike (the host's
disk). The live encoder keeps about 49 MB of heap per 1080p screen (about 20 MB at 720p; `ship` about 76 MB at 1080p).

## The codec comparison

MCV2, H.264, VP9 and AV1 on the same two sources, measured the same way on 2026-09-28 for this documentation
(`data/codec_curves.json`, every point with its settings and commands).

**Sources.**

- **The 1080p30 proxy** (`$SRC`, SHA-256 `acf2ab47e7d36aeefc53e0781d7b3934eb468efcbed610ddd6479aa5aece05bd`): the source
  of the 1080p30 frontier above, 30 frames of 1920x1080 RGB24. It is frames 0, 2, ..., 58 of
  `frame("minecraft_proxy", i, 1920, 1080)` of the research repository's `mcvideo/sources.py` at commit `85445433`
  (seed 20260915), which is not part of mcav.
- **Real Minecraft gameplay**: frames 120 to 239 of Xiph's Twitch recording `MINECRAFT.y4m`
  (<https://media.xiph.org/video/derf/twitch/y4m/MINECRAFT.y4m>, 1920x1080 at 60 fps; the excerpt's SHA-256
  `9053e412...fac`), converted to RGB24 by ffmpeg (`f69ab402...1a57`), and every second frame of that: 60 frames at 30
  fps (SHA-256 `2bd1c0b0658455d251682b0874ca4ec5f5afcb26a0e1c71963e34ba80484ab65`). None of the research's rounds was
  tuned on it.

**Method.** One ffmpeg for everything, the frontier's: `$FFMPEG` is ffmpeg 7.0.2 (a static build with libvmaf,
libx264, libvpx-vp9 and libaom). Every encode goes from raw RGB24 into the codec's 4:2:0, is **decoded back to raw RGB24
first** (`-fps_mode passthrough`, so no frame is dropped or repeated), and only then scored, raw against raw, with the
frontier's VMAF filter: both pictures converted to yuv420p, libvmaf's default `vmaf_v0.6.1` model, the pooled mean and
the per-frame minimum (`docs/mcv2/measure/codec_curves.py`). Every codec is scored on RGB decoded the same way, so each
curve includes its codec's chroma subsampling.

| Codec | Settings | Quality points |
|---|---|---|
| H.264 | x264 (core 164 r3191), `-preset veryslow -crf Q -pix_fmt yuv420p` | CRF 14, 18, 22, 26, 30, 34, 38, 42, 46 |
| VP9 | libvpx 1.11.0, `-crf Q -b:v 0 -deadline good -cpu-used 0 -row-mt 1 -pix_fmt yuv420p` | CRF 12, 20, 28, 36, 44, 52, 60, 63 |
| AV1 | libaom 3.2.0, `-crf Q -b:v 0 -cpu-used 6 -row-mt 1 -pix_fmt yuv420p` (the frontier's aom speed) | CRF 12, 20, 28, 36, 44, 52, 60, 63 |
| MCV2 | mcav's encoder, the reference search of the shipped profiles (`tools/mcv2/Mcv2Bench.java`, `profile=ship`) at each lambda | proxy: lambda 12 to 500; gameplay: 30 to 900 |

```
python3 docs/mcv2/measure/codec_curves.py --ffmpeg "$FFMPEG" --source "$SRC" --name proxy30 --width 1920 \
    --height 1080 --frames 30 --fps 30 --codecs x264 --qualities 14 18 22 26 30 34 38 42 46 --out codec_curves.json
python3 tools/mcv2/rate_quality.py --classpath CLASSPATH --main Mcv2Bench --source "$SRC" --frames 30 --fps 30 \
    --lambdas 12,19.0449229,33.670698488,45.091398359,65.255994022,92.074353848,137.730758207,193.69371062,254.48409453,360,500 \
    --ffmpeg "$FFMPEG" --out mcv2.json -- profile=ship threads=12 budget=true verify=false
```

(and the same with `--name gameplay30 --frames 60` and the gameplay source; CLASSPATH holds `Mcv2Bench` compiled
against `mcav-bukkit` and its dependencies).

**Rates.** H.264, VP9 and AV1 are container rates: the file's size over the clip's duration, which charges none of the
transport. MCV2 is shown twice: its **map rate**, which charges everything the map transport costs (the six-bit
expansion, page headers, row rounding and the 18-byte packet envelope), and its rate **after the game's compression**:
every page's map colours deflated at zlib's default level plus the envelope (`Mcv2Bench`'s zlib rate, which matched the
TCP payload of a real client connection within a few percent, see [the design doc, section
7](../mcv2-integration.md#7-transport-and-wire-accounting)). The gap between the two MCV2 curves is the transport's tax,
which the game's compression takes back in part.

**MCV2 against the research frontier.** On the proxy, mcav's encoder reproduces the research's round-19 points: at
lambda 65.256 (`ship`), 92.07, 137.73 (`low_bandwidth`), 193.69 and 254.48 every frame is byte-identical, and the map
rate and VMAF equal the frontier's; at 45.09 the first frame differs in a few bytes and the other 29 are identical; at
33.67 and 19.04 the first frame differs in the chroma of three reduced-chroma records and the streams diverge from
there (6.475 against 6.487 and 11.877 against 11.916 map Mbps, VMAF within 0.008).

**Rate needed for the same VMAF mean** (Mbit/s; log-linear between the measured points):

| VMAF mean | MCV2 map | MCV2 after zlib | H.264 | VP9 | AV1 |
|---:|---:|---:|---:|---:|---:|
| **1080p30 proxy** | | | | | |
| 70 | 2.050 | 1.256 | 1.979 | 0.355 | 0.379 |
| 75 | 2.729 | 1.734 | 2.177 | 0.505 | 0.507 |
| 80 | 4.118 | 2.649 | 2.471 | 0.720 | 0.695 |
| 85 | 7.956 | 5.118 | 3.193 | 1.225 | 1.066 |
| 90 | 16.307 | 10.301 | 4.973 | 2.442 | 2.089 |
| **Gameplay** | | | | | |
| 70 | 9.809 | 6.179 | 3.142 | 1.834 | 1.618 |
| 75 | 11.736 | 7.460 | 3.578 | 2.252 | 2.018 |
| 80 | 14.622 | 9.334 | 4.199 | 2.785 | 2.513 |
| 85 | 19.802 | 12.491 | 4.929 | 3.444 | 3.129 |
| 90 | 27.791 | 17.184 | 5.785 | 4.338 | 3.994 |

- **On the proxy**, MCV2 after compression needs less rate than H.264 at `veryslow` up to about VMAF 78 (1.73 against
  2.18 Mbit/s at 75) and about the same at 80; above that H.264 pulls away. VP9 and AV1 need about a third of MCV2's
  compressed rate at VMAF 75 and a quarter at 80.
- **On real gameplay** MCV2 loses clearly: after compression it needs about 2.1 times H.264's rate at VMAF 75, 3.3
  times VP9's and 3.7 times AV1's; by map rate 3.3, 5.2 and 5.8 times.
- The proxy's large flat regions and hard edges suit MCV2's palettes and patterns and starve the transform codecs, and
  VMAF was trained on natural video, so on the proxy it compares these codecs with each other rather than on an
  absolute scale. Real gameplay is the honest case.

## Dithered maps

What mcav's dithered maps send (`/mcav video map` without MCV2), on the same two sources at 30 fps, measured with
`docs/mcv2/measure/DitherBench.java`: every frame is dithered at 1920x1080 with mcav's `DitherAlgorithm` into the map
colours of a 15x9 wall, and `DeltaMapEncoder`, what `CompressedMapResult` sends a viewer who watches from the start,
picks the changed parts of each map within its budget per frame. 600 frames, the source looped forward and back, the
first 120 left out (the wall filling). The map rate counts every patch's colours plus `MapLayout.PATCH_OVERHEAD` (16
bytes); zlib deflates the colours at zlib's default level. VMAF compares the pictures the viewer's maps show with the
source frames they belong to.

```
javac -cp CLASSPATH -d build/bench docs/mcv2/measure/DitherBench.java
java -cp build/bench:CLASSPATH DitherBench source="$SRC" width=1920 height=1080 frames=600 warm=120 fps=30 \
    loop=pingpong algorithm=filter_lite budget=131072 decoded=dithered.rgb reference=reference.rgb
```

(CLASSPATH holds `mcav-bukkit`, `mcav-common` and their dependencies; `budget=0` sends every change, and
`algorithm=temporal` selects temporal Floyd-Steinberg.) The two pictures are then scored with the VMAF filter of the
codec comparison above. "Frames not complete" counts the scored frames on which at least one map still showed an older
picture than the current dithered frame.

| Source | Dithering | Budget per frame | Map Mbit/s | After zlib | Frames not complete | VMAF mean / min |
|---|---|---|---:|---:|---:|---|
| 1080p30 proxy | Filter Lite | 128 KiB | 30.4 | 10.4 | 480 of 480 | 33.4 / 23.4 |
| 1080p30 proxy | temporal Floyd-Steinberg | 128 KiB | 30.8 | 10.5 | 480 of 480 | 33.5 / 20.0 |
| 1080p30 proxy | Filter Lite | none (every change) | 367.2 | 125.5 | 0 of 480 | 96.9 / 93.3 |
| Gameplay | Filter Lite | 128 KiB | 30.4 | 10.9 | 480 of 480 | 10.9 / 0.0 |
| Gameplay | temporal Floyd-Steinberg | 128 KiB | 30.2 | 10.9 | 480 of 480 | 11.0 / 0.0 |
| Gameplay | Filter Lite | none (every change) | 498.2 | 178.3 | 8 of 480 | 99.5 / 88.8 |

With the plugin's default budget of 128 KiB per frame, the dithered wall uses the whole budget, about 30 Mbit/s per
viewer, and still never shows a complete frame: 128 KiB is a seventeenth of the wall's 2.2 MB of colours, every frame
leaves maps that show an older one, and that is what the low VMAF measures. On gameplay, where almost every map
changes on every frame, the wall becomes a mosaic of frames from different moments (VMAF 11). Sending every change
needs hundreds of megabits a second; even then, the encoder holds back tiles where only a few pixels changed for a few
frames, which is why 8 gameplay frames were not complete.

## The data files

The first ten are copied byte for byte from gpu-codec's `results/` at commit `85445433aeb9f8a35a5ce528d47d8829976d1401`;
the last was measured for the documentation, with the scripts in `docs/mcv2/measure`:

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
| `data/codec_curves.json` | 94,077 | not from the research: the codec comparison and the dithered maps above, measured for the documentation, every point with its settings and commands |

`frontier.json` and `frontier_av1.json` are over 1 MB because they hold every measured point and every reference
encode with its settings and scores; the documentation's charts and any re-analysis need all of them, and the research
data they summarise (93 GB of streams and sources) is not part of mcav.
