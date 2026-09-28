(mcv2-client)=
# Decoding Cost

Decoding costs the player GPU time on every frame their client renders while an MCV2 wall is in view. It was measured
outside Minecraft with `tools/mcv2/shader_timing.py`, which runs the pack's own post chain pass for pass in moderngl
with GPU timer queries, a 6x3 screen three blocks in front of the camera covering 57% of the view (so the ray cast runs
as it does while a player watches), and the GPU kept busy between chains as a rendering client keeps it. The GPUs at
hand were an **Intel UHD 630** (a 2018 desktop integrated GPU) and **Mesa llvmpipe** (software rendering on the CPU).
The tables are on the [results page](results.md#the-decoder-pass-by-pass).

## What a Rendered Frame Costs

Per 1080p frame of the `ship` stream on the UHD 630, with the final pack:

| Rendered frame | Chain | Of which |
|---|---:|---|
| brings a new P frame | **7.9 ms** | decode 2.14, screen 1.87, resolve 0.33, page CRCs 0.20, copies and state 3.31 |
| brings a new keyframe | **8.8 ms** | decode 3.11 |
| brings no new video | **6.6 ms** | the ray cast onto the wall 2.30, the chain's copies about 3.6 |

The shipped `live` preset costs 7.4 ms per new frame on quiet content and 8.7 ms on gameplay, 5.7 to 5.9 ms per rendered
frame without new video. The chain runs on every rendered frame while a page frame is in view; the decode proper runs
once per video frame, on the first rendered frame that holds a complete new frame, and a copy stands in on the others.
A good part of the remaining cost is structural: a persistent target is updated through a copy, because a pass cannot
write the target it reads.

**At 60 frames a second.** The chain takes 7.4 to 8.8 ms of a 16.7 ms frame when a rendered frame brings a new video
frame; a 30 fps video brings one on every other rendered frame at 60 fps. That leaves about 8 ms for Minecraft itself,
which at 1080p on a UHD 630 usually needs more: expect 35 to 50 frames a second on that GPU with the wall in view. A
GPU about twice as fast (Intel Iris Xe with 80 to 96 execution units, AMD 680M, any discrete GPU since a GTX 1050) runs
the chain in under 4 ms and fits 60 frames a second with room for the game. These are projections from the UHD 630's
measurements; no faster GPU was measured.

## The Decoder Speed Work

The research's decoder (`mcvideo_codec.glsl`) found every pixel's leaf and record at every pixel. Ported as it was, the
chain cost 61.5 ms per new P frame on the UHD 630. Five changes, each output-neutral (the conformance and edge streams,
every test clip and live stream bit-exact on both GPUs, frames dropped on purpose handled the same, the composed screen
byte-identical in four camera views, and the in-game captures still exact), took it to 7.9:

| Change | Measured | Kept |
|---|---|---|
| A resolve pass: one fragment per 8x8 cell finds the cell's leaf once, and the decode reads it | decode 42.3 to 5.8 ms, plus 0.6 ms of resolve | yes |
| Records unpacked once: motion bytes into the cell; the screen's 28 descriptor floats read once per frame, and only the box the wall can cover ray-cast | screen 11.7 to 6.8 ms | yes |
| Fewer dependent fetch chains: page CRCs in 192-byte chunks in parallel, three bytes per fetch, chained with the exact GF(2) shift | pages 5.9 to 0.25 ms | yes |
| A fast path for SKIP and local motion: one cell lookup, then the reference | decode 6.2 to 3.8 ms | yes |
| Integer and branch work: the values all pixels share read in the passes' vertex shaders, integer conversions without `floor` | chain 12.6 to 7.9 ms | yes |
| A stored projection inverse | 13.5 ms instead of 6.8, and it rounded differently | rejected |
| A projection inverse in the vertex stage | fast, but it moved the wall's edge by a pixel in grazing views | rejected |

Pass by pass, before and after:

| Intel UHD 630, ms | new P frame | new keyframe | no new video |
|---|---:|---:|---:|
| page CRCs | 5.71 to 0.20 | 10.31 to 0.18 | 5.67 to 0.20 |
| resolve (new) | 0.33 | 0.40 | 0.01 |
| decode | 41.56 to 2.14 | 52.56 to 3.11 | 1.11 to 0.46 |
| screen | 11.50 to 1.87 | 11.62 to 1.88 | 12.07 to 2.30 |
| **chain** | **61.5 to 7.9** | **77.4 to 8.8** | **21.7 to 6.6** |

On llvmpipe, where every full-screen copy costs about 4 ms of CPU, the chain went from 262 to 68 ms for a new P frame,
from 348 to 36 ms for a keyframe and from 85 to 51 ms for a frame without new video (measured before the last change,
under a machine load of 10 to 17).

**Why the port started slower than the research's figure.** The research's harness measured 15.065 ms per decode on its
own stream; the same harness gives 27.16 ms on MCAV's `ship` stream, because that stream uses the round-19 format:
its saved bytes are paid for by every pixel re-deriving its record's address (popcounts, the split prefix, the payload
cursor), where the older stream stored three-byte descriptors. The port then added about 55% of its own (41.6 ms),
reading the frame's facts from textures at every pixel. The resolve pass removes the per-pixel walk altogether: what
the derived form still costs is 0.33 ms of resolve against 0.17 ms for the stored form, so storing offsets again would
save about 0.16 ms per frame on the UHD 630, and give back the 2 to 7% of rate that derived offsets saved. No format
change was made.

## What Was Not Measured

Real GPUs other than the UHD 630, NVIDIA and AMD drivers, macOS (OpenGL 4.1 over Metal), Windows clients, and the frame
rate a player sees at full speed: the lab's client rendered on llvmpipe at about 3 frames a second. The
[testing guide](../mcv2-testing.md) is the procedure for measuring them on your own client.
