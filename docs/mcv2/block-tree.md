(mcv2-block-tree)=
# The Block Tree and its Modes

An MCV2 frame is a 48-byte header, an index, and a payload of records. The index describes a tree per 32x32 superblock;
the payload holds what each leaf of the trees needs to draw its pixels. This page explains what is stored and why it is
laid out the way it is. Every field, flag and rule is in the [specification](format.md); the names in `code` are the
ones the specification and the research's cost audit (`data/round19_cost_audit.json`) use, so the three agree.

## The Tree

The superblocks cover the picture in raster order, so a 1920x1080 frame has 60 x 34 = 2,040 of them (the last row
reaches past the picture; those pixels are coded but never shown). A node is a **leaf** or a **split** into four
children of half its size, in the order top left, top right, bottom left, bottom right. Only 32- and 16-pixel nodes
split, so every leaf is 32, 16 or 8 pixels square, and every leaf mode is allowed at all three sizes.

## The Leaf Modes

| Mode | Name | Record | What the decoder draws |
|---:|---|---|---|
| 0 | SKIP | none | The reference at the global vector (on a keyframe: the header's default colour) |
| 1 | MOTION | 2 bytes | The reference at the global vector plus a local vector |
| 2 | SOLID | 3 bytes | One RGB colour |
| 3 | PALETTE | 6 bytes + a bit a pixel | Two colours, one chosen per pixel |
| 4-7 | INTRA 1x1 to 8x8 | 3 bytes a node | A bilinear RGB grid |
| 8-11 | RESIDUAL 1x1 to 8x8 | 2 + 3 bytes a node | The prediction plus a YCoCg grid scaled by `2^q` |
| 12, 14 | reduced intra | 18 or 72 bytes | A luma grid (4x4 or 8x8) with a single chroma pair or a 2x2 chroma grid |
| 13, 15 | reduced residual | 20 or 74 bytes | The same as a residual on the prediction |
| 17 | COMPACT | 2 to 21 bytes | The prediction plus a compact residual (nine classes, of which the encoder tries six; a book of 4x4 trained vectors and two sets of 4x2 product vectors) |
| 18 | PATTERN | 2 to 11 bytes | Two colours, one chosen per column or per row |

Temporal modes (0, 1, 8-11, 13, 15, 17, and the immediate motion form, 20) predict from the reference and are refused
on keyframes, except SKIP in a keyframe whose header sets `DEFAULT_SOLID`, which draws the default colour. Every value a leaf
draws is exact: prediction at half-pixel positions averages one, two or four pixels, grid weights are dyadic, and every
channel ends as `floor(clamp(v, 0, 255) + 0.5)`, so the Java decoder, the reference and the shader produce the same
bytes ([specification, section 5](format.md#5-reconstruction)).

At the 5 Mbps rung of the 1080p60 ladder (4.944 map Mbps, VMAF 75.76) the most frequent descriptors are MOTION
(46,544 in 60 frames), SKIP (29,940), split (20,296), PATTERN (18,355) and COMPACT (11,626); 105,395 leaves are 32
pixels, 64,729 are 16 and 13,164 are 8 (`data/round19_cost_audit.json`).

## Motion Records

Motion is stored as a local vector **less the global vector**, in half pixels, which is small for a pan and zero for a
static wall. There are three forms:

- a **MOTION** leaf (mode 1): two signed bytes;
- the motion of a residual record: the same two bytes in front of the grid, or in a compact record one of three forms
  chosen by its control byte: none (the global vector alone), one byte of two signed nibbles, or two signed bytes;
- in the stored index forms, an **immediate** motion leaf (mode 20, round 1): the two bytes live in the descriptor word
  itself and cost no record.

The search range is 24 pixels around the global vector, so a stored delta of at most 48 half pixels needs seven signed
bits an axis; quarter-pixel motion would need eight, the whole of the two-byte record. The research stopped at half
pixels because round 19 gained less than the 2% the owner asked of it before a quarter-pixel round
([results](results.md#the-per-frame-tables-rounds-16-to-19)).

## Descriptors and Addressing

A **descriptor** is one byte, `mode | q << 5`. The question the index answers is, for any pixel: which leaf covers it,
and where is that leaf's record? The format has had two answers.

**Stored forms** (the older ones, still accepted): every descriptor stores the absolute offset of its record or of its
split's children, in a four-byte word or a three-byte short form. A pixel follows at most two child pointers. It is
simple and costs bytes on every leaf.

**The derived-offsets form** (rounds 4, 7, 9 and 10; flags 2, 16, 32, 64, 128), which every production frame uses,
stores **no address at all** and recovers each one:

- **Presence masks** (`directory_masks`): one bit per superblock, in groups of 32. A superblock whose bit is clear is
  SKIP, with no descriptor.
- **Directory checkpoints** (`directory_checkpoints`): for every eighth group, how many superblocks are present before
  it. A superblock's position in the descriptor list is its checkpoint plus at most seven mask popcounts plus the
  popcount of its own mask below its bit.
- **Descriptors in level order**: the present superblocks' descriptors first (`n0`), then the four children of every
  split among them (`n1`), then the four children of every split among those (`n2`). The first child of the split at
  position `i` is then position `n0 + 4 x (splits before i)`: counting splits replaces a child pointer.
- **Packed symbols** (flag 64, round 9): a frame uses few distinct descriptor bytes, so the plane stores `w`-bit indexes
  into a per-frame symbol table of at most 32 entries (`symbol_table`, `descriptor_modes`, `descriptor_quantizers`).
- **Walk checkpoints** (`descriptor_walkpoints`): for every eighth descriptor, the payload bytes and the splits of all
  descriptors before it. A record's offset is its checkpoint's cursor plus the record lengths of at most seven
  descriptors before it, and a record's length follows from its mode, its size, the frame's flags and, for a compact
  record, its own control byte, so no earlier record has to be parsed. With the **two-level walk** (flag 128, round
  10), every fourth checkpoint is an absolute anchor and the others are 16-bit deltas from it, at the smallest widths
  that hold the frame's deltas.

```{figure} figures/walk.png
:name: mcv2-walk-flow
:alt: Flow chart of how one pixel finds its superblock, descriptor, children and record in a derived-offsets frame.

Finding the leaf of a pixel in the derived-offsets form. Every step is a bounded amount of work that depends only on
the frame's bytes: at most seven popcounts, twice at most seven descriptors, and at most seven record lengths. No pixel
needs a neighbour's result, and nothing scans the frame from its start.
```

This is the heart of the format's design. A decoder in a fragment shader runs once per pixel, independently, with no
memory of other pixels: it can afford a bounded walk, but never a sequential scan of the frame or a dependency on
another fragment. Every addressing trick above trades a few stored bytes for a few more steps of that bounded walk,
and each round measured both sides: the round had to save bits and keep the reference's GLSL decode within twice the
draw time of the older form ([the ceiling](design.md)).

## The Per-Frame Tables

The last kept rounds added tables at the end of the frame, named by one-byte indexes (flags 512 to 8192):

- **The endpoint table** (round 16, flag 512). A pattern palette carries two RGB endpoints, six bytes. A frame names
  82 to 276 distinct pairs, far fewer than it has pattern leaves, so the pairs go in a table (`endpoint_table`) and each
  pattern names one in a byte (`palette_endpoint_indexes`). With **RGB565 entries** (round 18, flag 8192) an entry is
  four bytes instead of six, chosen per frame by the encoder's own trial comparison.
- **The selector tables** (round 17, flags 1024, 2048, 4096). A pattern's selector word, an orientation byte and one bit
  per column or row, is named by a byte from a table per block size (`selector_table`, `palette_selector_indexes`).

Why only pattern endpoints and selector words? The research's measured rule: **a dictionary pays when an entry is
large against its index and reuse is high.** Endpoint pairs are 48 bits against an 8-bit index, 6:1, at a reuse of 1.8
per frame, and saved 6.0% of the map rate at unchanged VMAF; selector words saved another 6.3%. Solid colours are 3:1
at a reuse of 1.9, and a table of them cost rate instead of saving it (-36.4% of `solid_rgb` at 5 Mbps); motion
vectors are 2:1 and lost (round 15, reverted); a dictionary of residual bodies measured a reuse of 1.05. All of these
figures are on the [results page](results.md#the-per-frame-tables-rounds-16-to-19).

The tables sit at the end of the frame, and whether each exists is a flag in the header, so no other field's address
depends on their size: the first form of round 17 read the table sizes on every fragment's path and missed the draw
ceiling by 2.24%; this layout left 10.77% of headroom for the same bytes.

## Where the Bits Go

The share of the map rate of every field at the 5 Mbps rung of the 1080p60 ladder (`data/round19_cost_audit.json`,
`round19-seed-compact_final-81p686291`: 4.944 map Mbps, VMAF 75.76):

| Group | Fields | Share |
|---|---|---:|
| Transport | `symbol_expansion` 24.82%, `row_rounding` 0.57%, `page_headers` 0.32%, `packet_envelope` 0.18% | 25.89% |
| Motion | `motion_vectors` 16.55%, `motion_form` 0.94% | 17.49% |
| Residuals | `residual_luma` 13.42%, `residual_class` 0.94%, `residual_chroma` 0.37% | 14.73% |
| Index | `descriptor_walkpoints` 6.94%, `descriptor_modes` 6.81%, `descriptor_quantizers` 4.09%, `directory_masks` 2.49%, `frame_headers` 0.47%, `directory_checkpoints` 0.31%, `symbol_table` 0.14%, `level_counts` 0.06% | 21.31% |
| Palettes and patterns | `endpoint_table` 4.61%, `palette_endpoint_indexes` 2.97%, `palette_selector_indexes` 2.89%, `palette_selectors` 1.87%, `palette_endpoints` 1.30%, `selector_table` 1.13%, `palette_pattern_metadata` 0.08% | 14.85% |
| Solid colours and grids | `solid_rgb` 3.05%, `intra_luma` 1.73%, `intra_rgb` 0.75%, `intra_chroma` 0.22% | 5.75% |

A quarter of the map rate is the six-bit transport itself (`symbol_expansion`: four map bytes carry three payload
bytes), and a fifth is the index that makes per-pixel random access possible. The next pages explain both.
