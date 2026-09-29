(mcv2-encoding)=
# Encoding a Frame

The format does not fix how a frame is searched: any stream the [specification](format.md) accepts decodes the same
way everywhere. MCAV has two searches. The **reference search** of the `ship` and `low_bandwidth` profiles is a port of
the research's Python encoder, and writes its bytes exactly at both shipped lambdas. The **live searches** (`live`,
`adaptive`, `live-fast`) are MCAV's own, cheaper, for sources that play while they are encoded. This page walks through
the reference search in the order `Mcv2Encoder` runs it, then lists what the live searches do differently.

```{figure} figures/encoder.png
:name: mcv2-encoder-flow
:alt: Flow chart of the encoder, from the source frame through the keyframe decision, global motion, trials, block search, tree, serialization and trial comparison to the decoded reference.

The reference search, one frame. Every decision the encoder makes is a comparison of the same cost, the error of what
the decoder will reconstruct plus lambda times the bits, and every comparison is made against the **decoded**
previous frame, which is what every client holds. The note marks where the live presets differ.
```

## Keyframe or P Frame

A frame is a **keyframe** when there is no previous decoded frame of the same size, when the key interval has passed
(60 frames for the shipped profiles, two seconds at 30 fps; 120 for the live presets), or at a **scene cut**. A scene
cut is measured after the global translation below: the mean absolute difference of the luma `(R + 2G + B) / 4`
between the source and the previous decoded frame moved by the global vector, over the frame padded to whole 32x32
blocks, above the scene threshold of 45. Every other frame is a **P frame** and predicts from the previous decoded
frame.

A keyframe may hold no temporal leaf (no SKIP, motion, residual or compact record). The previous decoded frame is not
the only possible reference: the encoder can predict from the last keyframe instead (`ReferencePolicy.LAST_KEYFRAME`),
or make every frame a keyframe (a key interval of 1). Both cost far more rate for the same picture, 73% and 144% more
at matched VMAF, and exist for viewers who cannot keep a chain of frames (see
[references](decoding.md#two-reference-frames-one-pipeline)).

## One Translation for the Whole Frame

Before any block is searched, the encoder estimates one global vector, the translation of the whole picture since the
previous frame, as a camera pan would move it (`GlobalMotion`, the reference's `encoder.estimate_global`):

1. The luma of the source and of the previous decoded frame, averaged down to a quarter of the resolution in each
   direction.
2. Phase correlation {cite}`kuglin1975phase`: both are transformed with a 2D FFT, the cross-power spectrum is normalized to unit magnitude,
   and its inverse transform peaks at the shift between the two. The peak, times four, is a coarse vector of at most
   128 pixels each way.
3. Refinement at full resolution: the zero vector and a 7x7 window of whole-pixel vectors around the coarse one are
   scored by their absolute error on every twelfth pixel of every twelfth row. The lowest wins, the first on a tie.

The vector is stored in the frame header in half pixels. SKIP leaves copy the reference at this vector, and every
other temporal leaf stores its motion relative to it, which is why a pan costs almost nothing.

## Trials

The frame is searched once per **trial**, and the trial whose written bytes cost least is kept. A P frame has up to
four: the global vector, and the zero vector as well when the global vector is not zero, each once with full RGB
endpoints for its pattern palettes and once with RGB565 endpoints (round 18). A keyframe has two. The trials share
most of the work: intra candidates do not depend on the vector and are measured once, pattern candidates once per
endpoint precision, temporal candidates once per vector.

## Every Block, Every Candidate

The frame is a grid of 32x32 superblocks, and every block of every level, the 32x32 itself, its four 16x16 blocks and
their sixteen 8x8 blocks, is searched on its own. A block's candidates depend only on the source and the decoded
reference, never on a neighbour of the same frame (the format has no spatial prediction and no entropy coder), so all
blocks are searched in parallel, and every result is combined in a fixed order, so the stream is the same on any
number of threads.

```{figure} figures/blocktree.png
:name: mcv2-block-flow
:alt: Flow chart of one block's candidates and of the bottom-up split decision of a superblock.

How one block arrives at one leaf mode, and how a superblock arrives at its tree. The blue decision is taken by the
live presets only; the reference search measures every candidate.
```

For each block the candidates are tried in the reference's order:

1. **SKIP** at each trial vector: the reference moved by the global vector, no record at all.
2. **Local motion**: an eight-neighbour diamond search {cite}`zhu2000diamond` around the global vector, on 16 sampled pixels of the block,
   in steps from 24 pixels down to one pixel and then half a pixel (`MotionSearch`, the reference's
   `encoder.local_motion`). Its result is a **MOTION** leaf (two signed bytes, the local vector less the global one)
   and the prediction every residual candidate below builds on.
3. **SOLID**, one colour; **PALETTE**, two colours and one selector bit per pixel.
4. **Grids**: bilinear RGB grids of 2x2, 4x4 and 8x8 nodes (**INTRA** modes 5 to 7), and on P frames the same grids in
   YCoCg as a residual on the prediction, with a quantizer of 0 to 3 (**RESIDUAL** modes 8 to 11; 1x1 is a residual
   only). A grid is fitted with the pseudo-inverse of the decoder's own bilinear interpolation, so the fit minimizes
   the error of what the decoder draws, not of the nodes.
5. **Reduced chroma**: a 4x4 luma grid with one chroma pair (mode 12), and an 8x8 luma grid with a 2x2 chroma grid
   (mode 14), each also as a residual (13, 15).
6. **PATTERN** (18): a two-colour palette whose selectors repeat along one axis, so a selector per column or per row
   codes it; tried with full and with RGB565 endpoints.
7. **COMPACT** (17): a residual in one of six compact classes (a DC value, 2x2 and 4x4 grids, two with four-bit
   nodes, a low-order ramp), on the prediction at the global vector and then at the local one.

Each candidate is reconstructed exactly as the decoder will reconstruct it, and scored:

$$
\text{cost} = D + \lambda \cdot (8 \cdot \text{record bytes} + 10.5), \qquad
D = \sum_{\text{pixels}} \frac{4\,\Delta Y^2 + \Delta Co^2 + \Delta Cg^2}{6}
$$

where the error is taken in YCoCg {cite}`malvar2003ycocg` on the gamma-coded 8-bit values, weighted 4:1:1 for luma. MCAV keeps it as an exact
integer, 96 times the reference's value, so no summation order can change a decision. The 10.5 bits are what the
matched cost model (round 8) charges a leaf for its descriptor and its share of the walk plane in the derived form;
a SKIP superblock costs one bit. Per trial the **first strictly cheapest** candidate wins, which is exactly the
reference's rule, ties included. A candidate stops being measured as soon as its partial error plus its rate reaches
the cost it has to beat: exact, since it cannot win anymore.

## The Tree, Bottom Up

For each trial, the tree of every superblock is chosen from the bottom: four 8x8 leaves replace their 16x16 block when
their costs plus lambda times 10.5 bits for the split's descriptor are strictly lower than the 16x16 block's best leaf,
and then the same for the four 16x16 choices against the 32x32 block's best leaf. So a superblock is one leaf, or four
16x16 nodes that are each one leaf or four 8x8 leaves. Leaves are never smaller than 8x8, which bounds the decoder.
Finally, every palette whose selectors repeat along one axis is rewritten as a pattern (`TreeReader.withPatterns`).

## Writing the Frame

`FrameWriter` serializes each trial's trees the way the reference's `v2.pack_frame` does, byte for byte, and parses
every frame it writes before it hands it on. The production form, which [the next page](block-tree.md) explains, is
the **derived-offsets form**: presence masks for the superblocks, one-byte descriptors in level order packed as small
indexes into a per-frame table of at most 32 descriptor bytes, a two-level walk plane of checkpoints instead of stored
addresses, and the records. An **endpoint table** is written when pattern endpoint pairs repeat enough to pay for it
(1 to 255 distinct pairs, and `5 x uses - entry size x pairs - 1 > 0`), and a **selector table** per block size on the
same terms. On a keyframe, the commonest solid colour becomes the header's default colour, and its SOLID leaves become
free SKIP leaves. A frame with more than 65,535 descriptors or payload bytes, beyond the reach of the 16-bit counts,
falls back to the stored-index form.

## The Rate-Distortion Compare, Against the Decoded Picture

Each trial's bytes are decoded, and the trial is priced as a whole: the weighted squared error of the decoded picture
against the source, over the frame padded to whole 32x32 blocks, plus lambda times 8 times its length in bytes. The
cheapest trial is kept, the first on a tie; the others are dropped. With verification on (the default of a screen),
the kept bytes are parsed again and must describe exactly the chosen tree, and every leaf must decode to exactly the
error the search measured for it.

The decoded picture, not the source, becomes the reference for the next frame. That closes the loop: the encoder
predicts from exactly what every client holds, so errors never accumulate between the two.

## What the Live Presets Change

The reference search takes 570 ms per 1080p frame on 12 threads of an i7-8700
([results](results.md#what-mcav-ships-and-why)), far from the 33 ms a 30 fps frame has. The live presets write the
same format, which the same pack decodes, from a cheaper search ([design doc §12](../mcv2-integration.md#12-live-1080p60-the-live-profile)):

- **One trial.** The vector, zero or a global estimate from row and column luma projections at half resolution, is
  chosen before the search by what SKIP would cost with each on a one-in-sixteen sample (`LiveAnalysis`), which also
  decides a scene cut. Pattern endpoints are always RGB565.
- **The tree from the top.** A block whose SKIP costs at most 26.5 lambda is SKIP without a search: every other leaf
  costs at least that many bits, so the threshold never changes a decision. A 32x32 block is split only when its best
  leaf costs more than 150 lambda where the previous frame split that superblock, or 450 where it coded it whole; a
  16x16 block above 300. `live-fast` takes SKIP up to 60 lambda and splits above 900 and 600.
- **Fewer, cheaper candidates.** The leaf modes the reference picks on real gameplay (motion, solid, palettes, the 2x2
  and reduced intra grids, compact records, patterns), compact records in three classes at the one quantizer their
  fitted values need, cheaper fits for palettes and grids, and compact records on the closer of the two predictions
  only.
- **Hierarchical motion.** Local motion starts from the previous frame's 8x8 vectors, is searched on the pictures at
  half resolution first (`live` adds a quarter-resolution level before that), then refined at full resolution to
  half pixels; 8x8 blocks inherit their parent's vector.
- **A lambda that rises with motion.** `lambda = base x min(4, max(1, (TI / 4.6) ^ 0.79))`, where TI is the mean
  absolute change of the blurred luma of every fourth pixel from the previous frame, smoothed over 16 frames and
  restarted at a scene cut. VMAF forgives more error in motion; quiet content keeps the base lambda (72 for `live`, 55
  for `live-fast`).
- **Frames that fit the screen.** A frame larger than the page slots of its screen carry is searched again at twice the
  lambda, up to four times.
- **Pipelining.** Frame N+1 is searched while frame N is verified; a frame is sent only once verified.
- **Native kernels.** The pixel kernels can run in a SIMD library that computes exactly what the Java kernels compute
  ([live encoding](live.md#the-native-kernels)).

Between live presets the encoder switches without a keyframe, because every live search writes the same format from
the same pictures; only a switch to or from the reference search starts a new encoder, whose first frame is a keyframe.
