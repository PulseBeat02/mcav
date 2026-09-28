(mcv2-design)=
# Why It Is Designed This Way

MCV2 looks unlike a video codec: no transform, no entropy coder, no prediction from neighbouring blocks, a tree that is
never deeper than three levels, records that are whole bytes. None of that is an oversight. Each is a consequence of
decoding in a vanilla core shader, and the research measured what each one costs. Every figure on this page is on the
[results page](results.md#the-ceiling-and-why) with its source file.

## The Constraints of a Vanilla Client

1. **Per-fragment random access.** The decoder is a fragment shader: every pixel is decoded by its own invocation,
   in parallel, with no view of what the others compute. So every pixel must find its own block and its own record
   directly from the frame's bytes, with a bounded amount of work, and nothing may depend on having decoded something
   else first. There can be no sequential scan of the frame, and no dependency between neighbouring blocks.
2. **No state between frames**, except a post chain's persistent render targets. The only thing that survives from one
   frame to the next is a texture the chain wrote, which is where the reference frames live
   ([Decoding in the Client](decoding.md#two-reference-frames-one-pipeline)). There is no bitstream state, no adaptive
   probability model, no motion vector history.
3. **GLSL 330**, the language of the game's core shaders: textures and integer arithmetic, but no compute shaders, no
   storage buffers, no writes except to the fragment's own output.
4. **Six-bit transport.** Bytes reach the client only as map colours, and only 64 of them can be told apart exactly, so
   every payload byte costs four thirds of a map byte.
5. **A bounded decoder.** The research held every format feature to at most twice the draw time of the pre-round-7
   form on the same stream, so that the decode stays affordable on the GPUs players have.

The format keeps four rules that follow from these: a bounded decoder, no frame-wide serial entropy dependency,
directly indexable representations, and six-bit map transport.

## What Each Rule Costs

Measured on round-17 streams from 2.99 to 11.82 map Mbps (`data/round17_rule_cost.json`):

| Rule | What it forbids | Measured cost |
|---|---|---|
| Indexable representations | A variable-length descriptor symbol | +4.47 to +7.16% of the logical rate (+0.2255 map Mbps at the 5 Mbps rung) |
| No serial entropy dependency | Conditioning on the preceding symbol too | +0.85 to +1.41% of the logical rate on top |
| Indexable representations | A compressed walk-checkpoint plane | +2.11 to +3.29% of the logical rate |
| Indexable, and no entropy coder | A context-coded root directory | About 1.13% of the wire, blocked by both rules |
| Byte-aligned records | A 14-bit motion record | 1.52% of the wire |
| The 2x draw ceiling | Round 15's motion table | 1.7% of the wire, about 1.3% BD-rate |
| Bounded decoder | Leaves below 8x8 | 0.02% of the logical rate, bracketed above by 0.75% |
| Six-bit map transport | Three payload bytes per four map bytes | Exactly 33.3% |

The rule one would expect to bind, the bounded decoder, costs least. What binds is the pair that holds the index
together, no entropy coder and per-fragment random access, and they act on the same bits.

## The Ceiling

At the same nominal budget, **AV1 {cite}`av1spec` is 15.8 VMAF points ahead**: libaom at cpu-used 6 reaches VMAF 91.532 mean at 4.7097
container Mbps on the 1080p60 proxy, MCV2's best point under 5 map Mbps 75.757 at 4.9445. The gap was 28.4 points at
the research's round 0 and 24.9 at round 18. The two rates are not the same kind of number: MCV2's map rate charges the
six-bit transport, page headers and packet envelopes, AV1's container rate charges none of that. The
[codec comparison](quality.md#four-codecs-on-one-chart) puts both on one chart, with MCV2 also after the game's
compression.

## Why AV1's Tools Do Not Transfer: Round T

A codec like AV1 wins with two tools: a transform (a block of residual becomes a few frequency coefficients) and
entropy coding (frequent symbols cost fewer bits). The research tested both inside the vanilla-shader envelope, in a
time-boxed study on 2026-09-19 (`data/transform_study.json`, `data/walk_cost.json`):

- **T1, a bounded integer transform** (a 4x4 Hadamard on luma, fixed zigzag truncation, one chroma pair per cell), at
  matched payload bytes on 2,500 real blocks per size: **three to ten times worse per byte** than the palette modes it
  would replace. At 8x8, 14 bytes of transform gave a mean squared error of 59.4 against the palette's 15.9; at 32x32, 162 bytes
  gave 82.1 where 134 bytes of palette gave 44.6. The content is hard-edged, and a two-colour palette with one bit per
  pixel codes a step edge exactly. On natural video the transform does become competitive, but only above about 40
  bytes per 8x8 block, and the ladder spends 2.2 to 15.2 logical bytes per 32x32 block: an order of magnitude below
  where a transform starts to win.
- **T2, chunked entropy coding** (a static canonical Huffman code per payload category, its tables in the resource
  pack): real on rate, 20.8 to 24.4% of the payload (11.4% of the map rate) at the 5 Mbps rung. But it removes random
  access inside a record, and GPU fragments run in lockstep, so a group of fragments pays for its longest record. At a
  measured 0.6385 ms per dependent lookup per 1080p draw on the UHD 630, only palette patterns and solid colours (14.1%
  of the payload) fit the draw ceiling, which nets **1.92% of the map rate**: an ordinary syntax round, not a
  breakthrough.

The verdict: neither path is worth pursuing inside the vanilla shader constraints. The binding constraint is
per-fragment random access, not the six-bit transport and not the syntax: transform coding needs byte budgets the
ladder never reaches, and entropy coding needs sequential access inside a record. (The study's shader costs are floors,
a synthetic chain of dependent lookups rather than a decoder, and its tables were built from the measured stream.)

## Why the Format Has the Features It Has

Every kept round of the research had to save bits at unchanged quality and keep the decoder inside its draw ceiling, so
the features are the ones that pass both tests on this content:

- **Palettes and patterns** code the hard edges of Minecraft's blocks exactly in a few bytes; round 13 made the search
  prefer patterns, and rounds 16 to 18 made them cheaper still with per-frame tables and RGB565 endpoints.
- **Bilinear grids** fitted to what the decoder interpolates code smooth gradients with a handful of nodes.
- **Motion relative to one global vector** makes a pan nearly free, and half-pixel positions (round 19) pay once
  everything around motion had shrunk.
- **Derived addresses** (rounds 4, 7, 9 and 10) replaced stored offsets with counts a fragment can recompute, the
  largest score of any round (round 10's two-level walk: +14.2% BD-rate, of which 6.4 points belong to rounds 1 to 9,
  a sampling artefact the results page explains).

At the most expensive rung (19.6118 map Mbps), the final format decodes in 30.1697 ms per draw on the UHD 630 against
a ceiling of 30.7433 ms: **1.87% of headroom**. That ceiling is what forbade round 15's motion table (about 1.3%
BD-rate, 0.08 standard errors over the line). MCAV's own post chain, which splits the decoder into passes, decodes a new
1080p `ship` frame in 7.9 ms on the same GPU ([client decode](client.md)).

## Where the Budget Binds

At 1080p60, the payload alone (5.74 map Mbps with all addressing free and ideal zero-order entropy coding) was over
the research's old 5 Mbps target. At 1080p30 it is 3.74, and what exceeded the 6 Mbps budget was addressing, which
ordinary syntax work (rounds 7 to 9) removed. So at 30 fps, the frame rate MCAV encodes live at, the index was the
problem and it was solved; what remains is the payload, which the constraints above keep from shrinking the way AV1's
does.
