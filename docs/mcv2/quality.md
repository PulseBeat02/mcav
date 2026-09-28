(mcv2-quality)=
# Rate and Quality

How good a picture MCV2 delivers for a rate, from the research that built it to a comparison with H.264, VP9 and AV1 on
the same sources. Rates are Mbit/s; quality is VMAF {cite}`li2016vmaf`, the mean over a clip's frames unless a figure
says otherwise, and a BD-rate is the average rate difference of two curves at equal quality {cite}`bjontegaard2001`.
Every number is on the [results page](results.md) with its method and its data file.

## The Research Frontier

The format was tuned over nineteen scored rounds on the 1080p60 proxy, each round kept only when it improved the
BD-rate by at least 1% without losing quality at any rung: twelve kept, seven reverted, **+43.1% cumulative BD-rate**
over the measured starting point ([the rounds](results.md#the-frontier-at-1080p60)). At 1080p60, the best points it
reached under each map budget:

| Map budget | Map Mbit/s | VMAF mean / min |
|---:|---:|---|
| 3 | 2.98 | 67.6 / 61.2 |
| 5 | 4.94 | 75.8 / 68.6 |
| 8 | 7.92 | 81.3 / 74.0 |
| 12 | 11.93 | 83.7 / 76.2 |
| 20 | 19.61 | 87.3 / 81.2 |

## The 1080p30 Operating Points

MCAV encodes at 30 frames a second. At 30 fps a megabit carries twice the bytes per frame it does at 60, while the
doubled distance between frames makes prediction harder. Round 19's 1080p30 points on the 30-frame proxy, with the rate
after the game's compression measured for this chapter:

| Lambda | Map Mbit/s | After zlib | VMAF mean / min | |
|---:|---:|---:|---|---|
| 254.48 | 1.556 | 0.907 | 65.0 / 59.2 | |
| 193.69 | 1.793 | 1.074 | 67.8 / 60.7 | |
| **137.73** | **2.122** | **1.308** | **70.6 / 64.6** | `low_bandwidth` |
| 92.07 | 2.700 | 1.716 | 74.9 / 67.4 | |
| **65.26** | **3.458** | **2.206** | **77.9 / 70.1** | `ship` |
| 45.09 | 4.472 | 2.888 | 81.0 / 73.8 | |
| 33.67 | 6.487 | 4.196 | 83.6 / 76.0 | |
| 19.04 | 11.916 | 7.534 | 87.8 / 81.6 | |

The rates after zlib are those of MCAV's own encode of each point, which is byte-identical to the research's stream from
lambda 65.26 up and differs by at most 0.3% of the map rate below it ([the comparison](results.md#the-codec-comparison)).

`ship` is the cheapest round-19 point that reaches a VMAF mean of 75, `low_bandwidth` the cheapest that reaches 70
([what MCAV ships, and why](results.md#what-mcav-ships-and-why)). Their per-frame minimum matters as much as the mean:
the worst frame is what a viewer notices. The research's own named target, VMAF 80 at 6 map Mbit/s or less on this
basis, is met: 5.64 map Mbit/s for VMAF 82.8.

## Four Codecs on One Chart

```{figure} figures/codecs.png
:name: mcv2-codecs
:alt: Two charts of rate against VMAF mean for MCV2 (map rate and after zlib), H.264, VP9 and AV1, on the procedural proxy and on real Minecraft gameplay.

Rate (log scale) against VMAF mean for MCV2, H.264 (x264 `veryslow`), VP9 (libvpx, `good`, `cpu-used 0`) and AV1
(libaom, `cpu-used 6`), all from the same raw sources, decoded to RGB and scored the same way. MCV2's solid curve is its
map rate, which charges the six-bit transport, page headers, row rounding and packet envelopes; its dashed curve is the
same stream after the game's zlib compression; the other codecs are container rates, which charge no transport at all.
Stars mark `ship` and `low_bandwidth`. **Left**, the 1080p30 procedural proxy (30 frames): its large flat regions and
hard edges suit MCV2's palettes and hurt the transform codecs, so this panel flatters MCV2; VMAF was trained on natural
video, so here it ranks the codecs rather than scoring them absolutely. **Right**, real Minecraft gameplay (60 frames
of Xiph's Twitch recording at 30 fps): where MCV2 loses.
```

What the chart says, as the rate each codec needs for the same VMAF mean
([the table](results.md#the-codec-comparison)):

| | MCV2 map | MCV2 after zlib | H.264 | VP9 | AV1 |
|---|---:|---:|---:|---:|---:|
| Proxy, VMAF 75 | 2.73 | 1.73 | 2.18 | 0.51 | 0.51 |
| Proxy, VMAF 80 | 4.12 | 2.65 | 2.47 | 0.72 | 0.70 |
| Gameplay, VMAF 75 | 11.74 | 7.46 | 3.58 | 2.25 | 2.02 |
| Gameplay, VMAF 80 | 14.62 | 9.33 | 4.20 | 2.79 | 2.51 |

- **The transport's tax.** The two MCV2 curves are the same stream: the six-bit transport costs a third on top of the
  frame's bytes, and the game's compression takes 35 to 45% of the map rate back, the most at low rates.
- **On the proxy**, MCV2 after compression beats H.264 at `veryslow` up to about VMAF 78 and ties it at 80; VP9 and AV1
  need a third of its rate or less.
- **On real gameplay**, MCV2 needs about twice H.264's rate after compression and three to four times VP9's and AV1's.
  Natural pictures are textured and noisy, where transforms and entropy coding win and palettes do not.

That is the price of decoding in a vanilla client's fragment shader, measured in [the design page](design.md): MCV2
cannot use the transforms and entropy coding the other three are built on. In exchange it needs no client mod, and a
player's GPU decodes a 1080p frame in under 9 ms ([decoding cost](client.md)).

## Against AV1 at the Same Nominal Budget

The research's own reference encodes (84 AV1 encodes: SVT-AV1 at presets 6 and 10 and libaom at cpu-used 6, at seven
target rates in rate-controlled modes, on the 1080p60 proxy) put the gap at the 5 Mbps rung at **15.8 VMAF points**:
libaom reaches 91.5 at 4.71 container Mbit/s, MCV2 75.8 at 4.94 map Mbit/s. The gap was 28.4 points at the research's
start and 24.9 at round 18 ([AV1 reference points](results.md#av1-reference-points)).

## The Live Presets

The live presets trade rate for speed, within fixed caps: `live`, the default of every live screen, costs 5.1% less
rate than `ship` at equal VMAF on the 1080p30 proxy and 7.9% more on 30 fps gameplay; `live-fast` up to 29.4% more. At
its default a `live` 1080p30 screen sends 2.80 map Mbit/s (1.83 after compression) of the proxy at VMAF 75.7, and 13.0
(8.3) of gameplay at VMAF 76.1: a lambda that rises with motion keeps gameplay near the same VMAF instead of spending
three times the rate on it ([live encoding](live.md)).
