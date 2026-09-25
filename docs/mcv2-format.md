# MCV2 bitstream and transport

The complete syntax of an MCV2 frame and of the map pages that carry it, as mcav's Java implementation parses and
writes it. Why the codec is built this way, and the evidence for it, is in the design doc,
[mcv2-integration.md](mcv2-integration.md).

## 1. Scope and status

- **Normative source:** the Python reference decoder of gpu-codec at commit
  `85445433aeb9f8a35a5ce528d47d8829976d1401`: `mcvideo/format.py`, `v2.py`, `compact.py`, `pattern.py`, `decoder.py`,
  `pixels.py` and `transport.py`, with `codebooks.py` for the residual books. That repository's `FORMAT.md` predates
  every kept frontier round and is not a source.
- **What is described:** mcav's implementation in `mcav-common`, package `me.brandonli.mcav.media.mcv2`. Each rule
  names the Java method that enforces it and the reference function it mirrors. `FrameParser.parse` is written to
  accept exactly the frames the reference's `v2.parse_frame` accepts (reached through `format.parse_frame`), apart
  from the syntax below, and `Mcv2Decoder` is bit-exact with the reference on all 780 conformance frames (design doc,
  section 2).
- **Conventions:** integers are unsigned and little-endian unless marked signed (two's complement: s4, s8, s16);
  offsets count bytes from the first byte of the frame; bit fields and bit streams are least significant bit first;
  sizes are in pixels and motion in half pixels. Upper-case names are `Mcv2Format` constants unless another class is
  named; names in parentheses are the reference's.
- **Refused syntax.** `FrameParser.parse` throws `UnsupportedSyntaxException`, the only subclass of `Mcv2Exception`,
  for:

  | input | the reference at that commit |
  |---|---|
  | magic `MCV1` | decodes it (`format.parse_frame`, `decoder.decode_legacy`): the first format version, not described here |
  | leaf mode 21 or 22, the coarse palettes of round 3, in either index form | decodes them: 21 at every leaf size, 22 at 16 and 32 |
  | flag 256, the motion table of round 15, with derived offsets | decodes it: motion vectors at the end of the payload, named by leaves of mode 23 |
  | flag 256 on a stored-index frame | ignores it: the stored path never reads the flag |
  | leaf mode 23 | accepts it only with flag 256 and derived offsets |

  These are the only inputs on which mcav and the reference knowingly disagree. The reference is more lenient in two
  places: it accepts modes 21 and 22, and on stored-index frames it ignores flag 256 instead of refusing it. MCV1 and
  the round-15 motion table on derived-offset frames are syntax mcav does not port.
- **Out of scope:** containers of several frames (the research `.mcs` archives), the resource pack's GLSL decoder, and
  how the encoder chooses modes; section 3.4 only says how `FrameWriter` lays out a tree it is given.

## 2. Frame header

`FrameParser.parse` reads the first 48 bytes (`HEADER_BYTES`) as twelve u32 words, as `v2.parse_frame` does
(`struct.Struct("<12I")`). A frame is 48 to 16,777,215 bytes long (`MAX_FRAME_BYTES`: offsets are 24-bit).

| offset | size | field | contents and rule |
|---:|---:|---|---|
| 0 | 4 | magic | `MCV2`, u32 `0x3256434D` |
| 4 | 1 | version | 2 |
| 5 | 1 | root size | log2 of the root block size, 5; bytes 4-5 must read u16 `0x0502` (`CONFIGURATION`) |
| 6 | 2 | flags | the table below; bits 14 and 15 must be zero (`ALL_FLAGS` is 16383) |
| 8 | 2 | width | 1 to 4096 (`MAX_DIMENSION`) |
| 10 | 2 | height | 1 to 4096 |
| 12 | 4 | frame id | any u32; a P frame's must differ from its reference id |
| 16 | 4 | reference id | the frame this one predicts from; a keyframe's is its own frame id |
| 20 | 2 | global motion x | s16, half pixels; zero on a keyframe |
| 22 | 2 | global motion y | s16, half pixels; zero on a keyframe |
| 24 | 4 | root count | exactly `ceil(width / 32) * ceil(height / 32)` |
| 28 | 4 | payload start | the end of the index and the first record byte; from 48 to the total |
| 32 | 4 | total | the frame length; must equal the number of bytes |
| 36 | 4 | default colour | R, G, B in bytes 36-38, byte 39 zero; nonzero only with flag 4 |
| 40 | 4 | reserved | zero |
| 44 | 4 | reserved | zero |

| flag | name | meaning | constraints |
|---:|---|---|---|
| 1 | `KEYFRAME` | the frame decodes without a reference | reference id is the frame id, motion is zero |
| 2 | `SPARSE` | the root index is a directory of presence masks | |
| 4 | `DEFAULT_SOLID` | a keyframe's SKIP leaves take the default colour | keyframes only |
| 8 | `SHORT_INDEX` | stored descriptors are 3 bytes with a 16-bit offset | frame at most 65,535 bytes; not with 32 |
| 16 | `DERIVED_DIRECTORY` | the directory is every mask, then one u32 checkpoint per 8 groups | needs 2 |
| 32 | `DERIVED_OFFSETS` | level-ordered one-byte descriptors, no stored addresses | needs 2 and 16 |
| 64 | `PACKED_SYMBOLS` | descriptors are indexes into a per-frame table of descriptor bytes | needs 32 |
| 128 | `TWO_LEVEL_WALK` | walk checkpoints are anchors plus 16-bit deltas | needs 32 |
| 256 | `MOTION_TABLE` | the motion table of round 15 | refused |
| 512 | `ENDPOINT_TABLE` | pattern endpoints are named in a per-frame table | |
| 1024 | `SELECTOR_TABLE_8` | 8-pixel pattern selector words are named in a per-frame table | agrees with its count byte |
| 2048 | `SELECTOR_TABLE_16` | the same for 16-pixel patterns | agrees with its count byte |
| 4096 | `SELECTOR_TABLE_32` | the same for 32-pixel patterns | agrees with its count byte |
| 8192 | `ENDPOINT_565` | endpoint table entries are two RGB565 colours | needs 512 |

Flags 512 to 8192 belong to the derived-offsets form: on a stored-index frame neither mcav nor the reference reads or
checks them, and pattern records are stored whole.

The checks run in this order, each throwing `Mcv2Exception`:

1. `MCV1` magic: `UnsupportedSyntaxException`. Any other magic but `MCV2`: "Not an MCV2 frame".
2. The length: "Invalid frame length". The configuration, an undefined flag bit or a nonzero reserved word:
   "Unsupported MCV2 header".
3. Width, height and total: "Invalid dimensions or total". Flag 8 on a frame over 65,535 bytes: "Short index frame
   exceeds the address range".
4. Root count or payload start: "Invalid root table". A keyframe naming another reference or moving, or a P frame
   naming itself: "Invalid reference metadata". A colour above `0xFFFFFF`, flag 4 on a P frame, or a colour without
   flag 4: "Invalid default color".
5. Flag 128 or 64 without 32. Flag 256: `UnsupportedSyntaxException`. Flag 32 without 2 and 16, or with 8.
6. The index (section 3); in the stored forms flag 16 without 2 is refused first.

**Keyframes and P frames.** A keyframe may hold no temporal leaf: modes 1, 8-11, 13, 15, 17 and 20, and SKIP unless
flag 4 is set, are refused ("Temporal keyframe leaf"). A P frame predicts from the decoded picture whose frame id is its
reference id. `Mcv2Decoder.decode(frame, reference, referenceId)` refuses a P frame whose reference is missing, has
another id or another size ("Reference frame mismatch"), as `v2.decode_frame` does, and ignores the reference given
for a keyframe. Frame ids wrap at 2^32: `Mcv2Receiver.accept`, like the reference's `decoder.Decoder.accept`, commits a
frame only when `0 < (id - last) mod 2^32 < 2^31`, keeps one picture, the last one committed, and changes nothing when
it throws. It therefore starts at a keyframe and follows streams that predict from the previous frame
(`EncoderSettings.ReferencePolicy.PREVIOUS_FRAME`); a stream whose P frames name the last keyframe (`LAST_KEYFRAME`)
needs a receiver that keeps that keyframe as well, as the resource pack does (design doc, section 4).

**Dimensions.** The picture is `width * height` RGB pixels. The roots cover whole 32x32 blocks, so the tree also
describes pixels right of and below the picture; they are validated but never output.

## 3. Block tree and index

### 3.1 Tree and index forms

Roots are 32x32 blocks in raster order: root `i` has its top left corner at `x = (i % columns) * 32`,
`y = (i / columns) * 32`, with `columns = ceil(width / 32)`. A node is either a split (mode 16, or 19 in the short
stored index) whose four children of half the size follow in the order top left, top right, bottom left, bottom right,
or a leaf. Only 32- and 16-pixel nodes split, so leaves are 32, 16 or 8 pixels, and every leaf mode is legal at all
three sizes (the reference's `format.record_size` conditions, a grid no wider than its block, always hold). A leaf's
record is the bytes its mode needs (section 4, which also gives every record length); SKIP has none. `Mcv2Frame` lists
every leaf as (x, y, size, mode, q, record offset), including roots the index leaves out; the offset is zero for SKIP
and holds the motion bytes for mode 20.

| form | flags | descriptor | a split's children | a leaf's record |
|---|---|---|---|---|
| stored, wide | 8 and 32 clear; 2, and 16 with it, optional | u32 word | at the word's offset | at the word's offset |
| stored, short | 8; 2, and 16 with it, optional | 3 bytes | at the offset; mode 19 omits skipped children | at the offset |
| derived | 2, 16 and 32; 64, 128 and 512-8192 optional | one byte, or a packed symbol | counted: the first is at `n0 + 4 * (splits before it)` | summed: after the records of the leaves before it |

### 3.2 Stored index forms

`FrameParser.StoredParser`, reference `v2.parse_frame`. After the header come the root index, the node table and the
payload of records.

**Descriptor.** Wide: a u32 with the offset in bits 0-23, the mode in bits 24-28 and the quantizer q in bits 29-31.
Short: a u16 offset, then one byte `mode | q << 5`, read as the wide word with offset bits 16-23 zero
(`StoredParser.word`, the reference's `get_word`). (The MCV1 word in `format.py`'s docstring has a 4-bit mode and q in
bits 28-31.) The offset is absolute: the record of a leaf, the child table of a split, zero for SKIP, whose whole word
must be zero, and for mode 20 the motion itself, s8 `dx` in bits 0-7 and s8 `dy` in bits 8-15, bits 16-23 zero.

**Root index.** Dense (flag 2 clear): the descriptor of root `i` is at `48 + i * stride`, stride 4 or 3 ("Short root
index" if they pass the payload start). Sparse (flag 2, `StoredParser.readSparseRoots`): group `g` holds roots `32g` to
`32g + 31`; bit `b` of its u32 mask marks root `32g + b` present, bits at or above the group's root count
`min(32, roots - 32g)` must be zero, and an absent root is a SKIP leaf. Without flag 16 the directory is one (u32 mask,
u32 pointer) pair per group; with flag 16 it is every mask, then one u32 checkpoint for each group `8k`. A pointer or
checkpoint is the absolute offset of the group's first descriptor. The present descriptors of all groups follow the
directory back to back, so every other group's position is implied, and a stored pointer or checkpoint must equal the
running position. Failures: "Short root directory", "Bad root directory" (a pointer, a stray mask bit, or descriptors
past the payload start), "Explicit sparse skip" (a present root whose word is zero).

**Node table.** It follows the root descriptors. The parser walks the roots in raster order and each tree depth first
(`StoredParser.walk`), and every split's child table sits at the current position: the split's offset must equal it,
the split must not be an 8-pixel node, and its q must be zero ("Invalid split address or depth"). Mode 16 has four
descriptors, which may be zero (SKIP children; "Truncated children" if they pass the payload start). Mode 19, short
index only, has a mask byte below 15 (bit `i` marks child `i` present; a full quartet must be mode 16), then one nonzero
descriptor per present child; absent children are SKIP leaves ("Invalid sparse child mask or layout", "Truncated sparse
children", "Explicit sparse child skip"). After the last root the position must be the payload start ("Noncanonical
node table").

**Leaves and payload.** A leaf's mode must be 0-15, 17, 18 or 20 (21-23 are unsupported, the rest "Invalid leaf
descriptor"), its q zero unless the mode is 8-11, 13, 15 or 17, and it must pass the keyframe rule. Records follow in
the order the walk meets the leaves, without gaps (`StoredParser.checkPayload`): every leaf with a record must point at
the running payload position and end inside the frame ("Invalid leaf payload"), and the last must end at the frame's
end ("Noncanonical payload length"). Pattern records are whole here. The reference decodes a mode 20 leaf by
materialising its two bytes after the frame, so the frame length plus two bytes per mode 20 leaf may not exceed
16,777,215 ("Immediate records exceed the frame address range").

### 3.3 Derived-offsets form

`FrameParser.DerivedParser`, reference `v2.parse_derived`. No address is stored; each is recovered, and each recovery
is also a check. With `G = ceil(roots / 32)` groups and `D = n0 + n1 + n2` descriptors:

| region | bytes | contents |
|---|---|---|
| masks | `4 * G` | a u32 presence mask per group, as in the stored form |
| checkpoints | `4 * ceil(G / 8)` | a u32 for each group `8k`: the number of present roots before it |
| level counts | 6 | u16 `n0`, `n1`, `n2`: the descriptors of 32-, 16- and 8-pixel nodes |
| symbol table (flag 64) | `1 + S` | u8 `S`, 1 to 32, then `S` descriptor bytes in strictly increasing order |
| descriptor plane | `D`, or `ceil(D * w / 8)` with flag 64 | the descriptors in level order |
| walk region | `4 * W`, or as below with flag 128 | `W = ceil(D / 8)` checkpoints |
| endpoint count (flag 512) | 1 | u8 `P`, at least 1 |
| selector counts (1024, 2048 or 4096) | 3 | u8 `c8`, `c16`, `c32` |
| payload | | the leaf records in level order |
| selector words | `2*c8 + 3*c16 + 5*c32` | the 8-pixel words, then the 16-, then the 32-pixel words |
| endpoint pairs | `6 * P`, or `4 * P` with flag 8192 | the last bytes of the frame |

Every index region must lie before the payload start, the last ending exactly there ("Noncanonical derived index
length"), and the records must end exactly where the selector words begin ("Noncanonical payload length").

**Descriptors.** A descriptor byte is `mode | q << 5`. With flag 64 the plane holds `w`-bit indexes,
`w = max(1, bit length of S - 1)` (`Mcv2Format.symbolWidth`); index `i` starts at plane bit `i * w`, and the reader
takes the u16 at `plane + (i * w >> 3)`, which may reach into the walk region, shifted right by `i * w & 7`
(`DerivedParser.descriptor`). An index of `S` or more is refused ("Symbol index outside the table"); otherwise the
descriptor is the table byte it names. Leaf modes here are 0-15, 17 and 18: modes 19 and 20 exist only in the stored
forms.

**Level order.** Level 0 is the present roots in raster order, found by counting mask bits: a stray mask bit is "Bad
root directory", each checkpoint must equal the count before its group ("Noncanonical descriptor checkpoint"), and the
total must be `n0`. Level 1 is the four children of every level-0 split, split by split, and level 2 likewise from
level 1, so `4 * (splits in level 0) = n1`, `4 * (splits in level 1) = n2`, and level 2 holds no split ("Level counts
disagree with the split descriptors", "Split below the bounded depth"). The first child of the split at index `i` is
therefore descriptor `n0 + 4 * (splits before i)`. Absent roots become 32-pixel SKIP leaves, which a keyframe allows
only with flag 4.

**Records.** Leaf `i`'s record starts at the payload start plus the lengths of the records of the leaves before it in
level order; splits have none. A length follows from the mode, the size and the flags, and for mode 17 from the
record's own control byte (section 4), so no earlier record has to be parsed to find it.

**Walk checkpoints.** Checkpoint `k` is the pair (cursor, splits) before descriptor `8k` (`WALK_SPAN`): the payload
bytes and the splits among descriptors `0` to `8k - 1`, both u16. From it a reader finds any descriptor's record and
children by walking at most seven descriptors; `DerivedParser` walks the whole plane and refuses a checkpoint that
disagrees ("Noncanonical walk checkpoint"). Without flag 128 the region is `W` pairs of u16. With flag 128
(`DerivedParser.walkEntry`, the reference's `walk_entry`) it is:

- u8 `cursorBits` and u8 `splitsBits`, each 1 to 16 with a sum of at most 16 (`DELTA_BITS`; "Invalid walk delta
  widths");
- an absolute u16 pair for every fourth checkpoint (`WALK_STRIDE`: `k` = 0, 4, 8, ...), the anchors;
- a u16 for every other checkpoint, in order: `(cursor - anchor cursor) | (splits - anchor splits) << cursorBits`,
  measured from anchor `k - k % 4`, with zero bits above `cursorBits + splitsBits` ("Noncanonical walk delta padding").

That region is `2 + 4 * A + 2 * (W - A)` bytes with `A = ceil(W / 4)` (`Mcv2Format.walkBytes`), and the two widths
must be the smallest that hold the frame's largest deltas, bit length at least 1 (`minimalWidths`; "Walk delta widths
are not minimal").

**Tables.** Endpoint pairs start at `total - P * entry`, selector words just before them, and both must lie at or after
the payload start ("Truncated endpoint table", "Truncated selector table"). Pairs must be distinct after expansion
("Noncanonical endpoint table"). A selector word is `1 + size / 8` bytes (2, 3 or 5), the words of one size must be
distinct ("Noncanonical selector table"), and a size's count is nonzero exactly when its flag is set ("Selector table
size contradicts its flag"). An entry is checked further only when a leaf names it (section 4.2).

### 3.4 Writing a tree

`FrameWriter.write` reproduces the reference's `v2.pack_frame` byte for byte, and parses every frame it writes before
returning it (`FrameWriter.finish`). A `TreeNode` leaf carries its record in the wide form, a pattern with its
endpoints and selector word inline.

- On a keyframe with solid leaves, the commonest solid colour (on a tie the first met, roots in raster order and trees
  depth first) becomes the default colour: its SOLID leaves become SKIP and flag 4 is set.
- The production options (`FrameWriter.Options.production`) try the derived form first, with a symbol table when the
  frame has descriptors and at most 32 distinct descriptor bytes, the two-level walk when its widths fit 16 bits and it
  is smaller, an endpoint table when there are 1 to 255 distinct pairs and `5 * uses - entry * pairs - 1 > 0` (entries
  in first-use order, 4 bytes when RGB565 is asked for and every pair survives the round trip), and the selector table
  of each size with 1 to 255 distinct words and a positive saving, kept when the savings together exceed the 3 count
  bytes. More than 65,535 descriptors or payload bytes, the reach of the u16 counts and checkpoints, fall back to the
  stored form. The reference writes an empty symbol table for a frame with no descriptors, which its own parser
  refuses; `FrameWriter` writes none.
- The stored form uses the sparse directory when it is smaller than the dense one, mode 19 for every short-index split
  with a skipped child, and mode 20 for every motion leaf. A short-index frame over 65,535 bytes is written again in
  the wide form, without sparse children.
- `TreeReader.roots` rebuilds the tree of a parsed frame (`repack.roots_from_frame`); `TreeReader.withPatterns`
  rewrites palettes whose selectors repeat along one axis as patterns (`pattern.compact_record`).

## 4. Leaf modes

`s` is the leaf size. Temporal modes predict from the reference and are refused on keyframes. `dx` and `dy` are s8
half pixels added to the header's global vector; section 5 gives the half-pixel prediction. q must be zero except where
the table allows 0-7. Grid node (row, column) of a `g x g` grid is node `row * g + column`.

| mode | name (reference) | q | record bytes | layout | reconstruction |
|---:|---|---|---|---|---|
| 0 | `MODE_SKIP` (skip) | 0 | 0 | | P frame: the prediction at the global vector (temporal). Keyframe: the default colour (flag 4) |
| 1 | `MODE_MOTION` (motion) | 0 | 2 | `dx`, `dy` | the prediction at the global vector plus (`dx`, `dy`); temporal |
| 2 | `MODE_SOLID` (solid) | 0 | 3 | R, G, B | one colour |
| 3 | `MODE_PALETTE` (palette) | 0 | `6 + s*s/8`: 14, 38, 134 | R0 G0 B0 R1 G1 B1, then one selector bit per pixel in raster order | the selector picks endpoint 0 or 1 |
| 4-7 | `MODE_INTRA` + k (intra1 to intra8) | 0 | `3*g*g`, `g = 2^k`: 3, 12, 48, 192 | `g*g` nodes, each u8 R, G, B | bilinear RGB grid |
| 8-11 | `MODE_RESIDUAL` + k (residual1 to residual8) | 0-7 | `2 + 3*g*g`: 5, 14, 50, 194 | `dx`, `dy`, then `g*g` nodes, each s8 Y, Co, Cg | the prediction plus the YCoCg grid scaled by `2^q`; temporal |
| 12 | `MODE_INTRA_Y4C1` (intra_y4c1) | 0 | 18 | 4x4 u8 luma, s8 Co, s8 Cg | YCoCg: luma grid, one chroma pair |
| 13 | `MODE_RESIDUAL_Y4C1` (residual_y4c1) | 0-7 | 20 | `dx`, `dy`, 4x4 s8 luma, s8 Co, s8 Cg | the prediction plus the residual scaled by `2^q`; temporal |
| 14 | `MODE_INTRA_Y8C2` (intra_y8c2) | 0 | 72 | 8x8 u8 luma, 2x2 s8 (Co, Cg) pairs | YCoCg: luma grid, 2x2 chroma grid |
| 15 | `MODE_RESIDUAL_Y8C2` (residual_y8c2) | 0-7 | 74 | `dx`, `dy`, 8x8 s8 luma, 2x2 (Co, Cg) pairs | the prediction plus the residual scaled by `2^q`; temporal |
| 16 | `MODE_SPLIT` (`SPLIT`) | 0 | node | 32- and 16-pixel nodes | |
| 17 | `MODE_COMPACT` (`COMPACT`) | 0-7 | `1 + form + body`: 2 to 21 | section 4.1 | the prediction plus a compact residual; temporal |
| 18 | `MODE_PATTERN` (`PATTERN_PALETTE`) | 0 | 8, 9, 11 whole; less with tables | section 4.2 | an endpoint per column or per row |
| 19 | `MODE_SPARSE_SPLIT` (`SPARSE_SPLIT`) | 0 | node | 32- and 16-pixel nodes, short stored index only | |
| 20 | `MODE_IMMEDIATE_MOTION` (`IMMEDIATE_MOTION`) | 0 | none: `dx`, `dy` are in the descriptor | stored forms only | as mode 1; temporal |
| 21, 22 | `MODE_COARSE_PALETTE_2`, `_4` | | | round 3 | `UnsupportedSyntaxException` |
| 23 | `MODE_INDEXED_MOTION` | | | round 15 | `UnsupportedSyntaxException` |
| 24-31 | | | | | "Invalid leaf descriptor" |

### 4.1 Compact records (mode 17)

`CompactRecord.parse` (`compact.parse_record`) and `Reconstruction.compact` (`compact.decode_body`). The control byte
holds the class in bits 0-3 and the motion form in bits 4-7. Form 0 adds no bytes and uses the global vector alone;
form 1 adds one byte, s4 `dx` in bits 0-3 and s4 `dy` in bits 4-7; form 2 adds s8 `dx` and s8 `dy`. The body follows,
s8 bytes unless noted. A class above 8, a form above 2, or class 7 with a nonzero q is "Invalid compact class or
control".

| class | `CompactRecord` | body | fields | Y, Co, Cg |
|---:|---|---:|---|---|
| 0 | `DC_Y` | 1 | dc | Y = dc, Co = Cg = 0 |
| 1 | `GRID2_YC` | 6 | 2x2 luma nodes, Co, Cg | Y from the 2x2 grid |
| 2 | `GRID4_N4_YC` | 10 | 16 s4 luma nodes in 8 bytes (node `2i` in the low nibble of byte `i`, `2i + 1` in the high), Co, Cg | Y from the 4x4 grid |
| 3 | `GRID4_N4_Y` | 8 | 16 s4 luma nodes as in class 2 | Y from the 4x4 grid, Co = Cg = 0 |
| 4 | `GRID4_YC` | 18 | 4x4 luma nodes, Co, Cg | Y from the 4x4 grid |
| 5 | `VQ64` | 4 | dc, Co, Cg, u8 index below 64 | Y from the 4x4 grid `VQ[index] + dc` |
| 6 | `PQ64` | 5 | dc, Co, Cg, u16 `ids` below 4096 | Y from the 4x4 grid whose columns 0-1 are `PQ0[ids & 63]` and 2-3 are `PQ1[ids >> 6]`, plus dc |
| 7 | `GAIN_BIAS` | 4 | g, then a Y, Co, Cg bias | each channel is `P * (1 + g/64)` plus the bias converted to RGB, unscaled |
| 8 | `LOW2` | 5 | dc, sx, sy, Co, Cg | `Y = dc + sx * a(x) + sy * a(y)`, `a(i) = ((i + 0.5) / s) * 2 - 1` |

Every class but 7 reconstructs a pixel as the prediction plus the RGB of `(Y * 2^q, Co * 2^q, Cg * 2^q)`, with Co and
Cg flat over the block and the prediction taken at the global vector plus (`dx`, `dy`). A VQ index of 64 or more is
"Invalid VQ index"; a PQ record whose last byte has a nonzero high nibble is "Noncanonical PQ padding".

The **residual books** (`ResidualBooks`, `codebooks.load_books`) are the resource `residual_books.bin`, 2,048 s8 bytes,
checked on loading (`Mcv2Resources.load`) against SHA-256
`1737842f5fbaa3e23777a04b6869a2bbd7d088c40efd1a8d237d756458c3e788`, the reference's
`research_artifacts/residual_books.sha256`. Bytes 0-1023 are the 64 VQ vectors of 16 nodes (4x4, row-major); bytes
1024-2047 are PQ book 0, then PQ book 1, each 64 vectors of 8 nodes (4 rows of 2, row-major).

### 4.2 Pattern records (mode 18)

`PatternRecord.expand` (`pattern.expand_record`). A pattern is a two-colour palette whose selectors repeat along one
axis. Its record is:

- the endpoints: six bytes R0 G0 B0 R1 G1 B1, or with flag 512 a u8 index into the endpoint table ("Endpoint index
  outside the table");
- the selector word: an orientation byte, 0 when the selector follows x and 1 when it follows y (anything else is
  "Invalid palette pattern record"), then `s / 8` axis bytes whose bit `i` is the selector of column or row `i`; or,
  when the selector flag of this size is set, a u8 index into that size's table ("Selector index outside the table").

Pixel `(x, y)` takes endpoint `axis[orientation == 0 ? x : y]`. The record is 8, 9 or 11 bytes whole, 3, 4 or 6 with
an endpoint index, 7 with a selector index and 2 with both (`Mcv2Format.patternSize`). Endpoint table entries are six
bytes as above, or with flag 8192 two u16 RGB565 colours (R in bits 11-15, G in 5-10, B in 0-4) expanded as
`r << 3 | r >> 2`, `g << 2 | g >> 4`, `b << 3 | b >> 2` (`Mcv2Format.unpack565`, `v2.unpack565`).

## 5. Reconstruction

`Mcv2Decoder` reconstructs every leaf on its own into a whole `s * s` block and copies the part inside the picture
(`Mcv2Decoder.Context.leaf`); a leaf whose top left corner lies outside the picture is not decoded. The kernels are in
`Reconstruction`, which the encoder shares. What bit-exactness depends on:

- **Prediction** (`Reconstruction.predict`, `pixels.prediction`): pixel `(X, Y)` with vector `(mx, my)` samples the
  reference at the half-pixel position `hx = clamp(2X + mx, 0, 2 * (width - 1))`, `x0 = hx >> 1`,
  `x1 = min(x0 + 1, width - 1)`, and the same in y, averaging one, two or four pixels as `hx` and `hy` are even or
  odd. The kernels carry four times the prediction as an integer, so `P = 0.25 * that` is exact in float32.
- **Grids** (`Reconstruction.interpolate`, `pixels.interpolation` and `expand_grid`): along an axis of `g` nodes over
  `s` pixels, `t = clamp((p + 0.5) * g / s - 0.5, 0, g - 1)`, `i0 = floor(t)`, `i1 = min(i0 + 1, g - 1)`,
  `f = t - i0`, and the value is bilinear in the four nodes. The weights are dyadic and the nodes are integers times
  powers of two, so every value is exact and the order of the reference's `einsum` cannot change it.
- **Rounding** (`Reconstruction.rgb8`, `pixels.rgb8`): every channel ends as `floor(clamp(v, 0, 255) + 0.5)` in
  float32, so SKIP and motion leaves round half-pixel averages half up.
- **Colour:** YCoCg converts as `R = (Y + Co) - Cg`, `G = Y + Cg`, `B = (Y - Co) - Cg`, associated left to right, and
  a residual is converted first and then added to the prediction: `P + ((Y + Co) - Cg)`.
- **Precision:** intra grids (modes 4-7) are rounded as interpolated; modes 12 and 14 convert in float32. Modes 8-11
  scale the nodes by `2^q` before interpolating, all in float32 (`residualGrid`). Modes 13 and 15 interpolate in
  float32, then scale, convert and add the prediction in float64, narrowing to float32 once before `rgb8`: the
  reference multiplies its float32 grid by a uint32 array of steps, which numpy promotes to float64
  (`decoder.decode_legacy`, `Reconstruction.reduced`). Mode 17 is float32 throughout, with `2^q` applied after
  interpolation; class 7's gain `1 + g/64` is float32.
- **Nibble classes:** the reference reads a compact body as int8, converts it to float32 and, for classes 2 and 3,
  back to uint8 before splitting the nibbles. A negative float32 wraps modulo 256 in that conversion (numpy behaviour
  pinned by experiment, design doc section 2), which recovers the stored byte, so `Reconstruction.compact` reads the
  byte unsigned.

## 6. Transport

`me.brandonli.mcav.media.mcv2.transport`, reference `transport.py`. A frame travels as pages; a page is a 32-byte
header (`<4sBBHIIHHIII`) followed by a slice of the frame.

| offset | size | field | rule (`TransportPages.readPage`, `read_page`) |
|---:|---:|---|---|
| 0 | 4 | magic | `MCP1` |
| 4 | 1 | version | 1 |
| 5 | 1 | symbol bits | 6, 7 or 8, equal to the negotiated width |
| 6 | 2 | frame type | 1 for a keyframe, 0 for a P frame (the frame's flag 1) |
| 8 | 4 | stream id | chosen by the sender; an assembler takes only its own |
| 12 | 4 | frame id | the frame's |
| 16 | 2 | page number | below the page count |
| 18 | 2 | page count | `ceil(frame length / capacity)` |
| 20 | 4 | reference id | the frame's |
| 24 | 4 | frame length | 48 to 16,777,215 |
| 28 | 4 | CRC32 | see below |

**Pages.** A page holds `16384 * bits / 8 - 32` frame bytes: 12,256 at 6 bits, 14,304 at 7 and 16,352 at 8
(`TransportPages.capacity`). `makePages` (`make_pages`) validates the frame, then page `n` carries the frame bytes from
`n * capacity` to the next page or the end. The CRC32 is the IEEE CRC-32 (`java.util.zip.CRC32`, `zlib.crc32`) of the
header with bytes 28-31 zero, followed by the page's slice; it detects corruption and is not authentication.

**Symbols.** A page's bytes are read least significant bit first into one bit stream, which is cut into symbols of
`bits` bits, least significant bit first, the last one zero-filled (`toSymbols`, `to_symbols`): a page of `n` frame
bytes is `ceil((32 + n) * 8 / bits)` symbols, and a full page exactly 16,384, one 128x128 map. At 6 and 7 bits the
header ends inside a symbol. A receiver decodes the header from the first `ceil(256 / bits)` symbols (43, 37 or 32),
keeping the low `bits` of each, and checks it ("Truncated page header", "Unsupported page header", "Invalid page
metadata"); the symbol count must then be exact for `n = min(capacity, frame length - number * capacity)`, every symbol
below `2^bits` and the padding bits zero (`fromSymbols`), and the CRC must match ("Page CRC mismatch"). A page of more
than 16,384 symbols is "Oversize map page". `usefulSymbols` computes the exact count from the header, so a receiver of
whole map rows can drop the row padding first.

**Reassembly** (`PageAssembler.push`, `transport.Assembler.push`): pages of one stream may arrive in any order ("Wrong
stream" for another). At most four frames are pending (`MAX_PENDING`); a page of a fifth evicts the frame that became
pending first. A page must agree with the pages already held for its frame in page count, reference id, frame length
and frame type ("Inconsistent pages"), and a repeated page number must repeat the page exactly ("Conflicting page
duplicate"); either failure drops the whole frame. When every page is held, the slices are joined in page order, the
result is parsed by `FrameParser.parse`, and its frame id, reference id and keyframe flag must match the pages ("Frame
and page identity mismatch"). The assembler never touches a reference.

**Map alphabet** (`MapAlphabet`): at 6 bits, symbol `s` is sent as the map colour byte `s + 4`, the colour ids 4 to 67
(the four shades of base colours 1 to 16). `toMapColors` pads a page to whole 128-colour rows with symbol 0, colour 4;
`fromMapColors` refuses any other byte ("Map colour outside the alphabet"). The server writes page `n` of a frame into
the top rows of page map `pageMap + n`, all pages of a frame in one bundle (`Mcv2Channel.send` in `mcav-bukkit`).

**Wire accounting** (`TransportPages.wireBytes`, `wire_bytes`): a page is charged its whole rows plus 18 bytes,
`rows * 128 + 18` with `rows = ceil(symbols / 128)`, or `16384 + 18` when full maps are charged. The reference budgets
the 18 bytes as a 3-byte packet length, 2-byte packet id, 3-byte map id, scale, the locked and decorations flags, four
patch coordinates and extents and a 3-byte colour array length; no compression, and TCP/IP/TLS overhead is left out.
For example, the first frame of the ship stream (`conformance/p30r19-compact_final-65p255994.mcs`) is a 1920x1080
keyframe of 19,733 bytes (flags 7,927, every flag but 8, 256 and 8192; 2,040 roots; payload start 3,073). At 6 bits it
is two pages of 16,384 and 10,012 symbols, charged `16,402 + 10,130 = 26,532` bytes (`conformance/pages.json`).

## 7. Validation and limits

Every field is hostile until it is checked. `FrameParser.parse` copies its input, reads only inside it, checks every
count, offset and length against the frame before using it, and allocates in proportion to the input, apart from
per-root arrays of at most 16,384 entries. For any bytes, `FrameParser.parse`, `CompactRecord.parse`,
`PatternRecord.expand`, `Mcv2Decoder.decode`, `Mcv2Receiver.accept`, `TransportPages.readPage`, `fromSymbols` and
`usefulSymbols`, `PageAssembler.push` and `MapAlphabet.fromMapColors` either succeed or throw `Mcv2Exception`, whose
only subclass is `UnsupportedSyntaxException`. A null argument is a programming error (`NullPointerException`), and so
are invalid arguments on the writing side (`IllegalArgumentException` from `FrameWriter.write`,
`TransportPages.makePages`, `MapAlphabet.toMapColors` and the `PageAssembler` constructor).

| bound | limit | checked by |
|---|---|---|
| frame length | 48 to 16,777,215 bytes | `FrameParser.parse` |
| width, height | 1 to 4096, so at most 16,384 roots in 512 groups | `FrameParser.parse` |
| short-index frame | at most 65,535 bytes | `FrameParser.parse` |
| frame plus mode 20 records | at most 16,777,215 bytes | `StoredParser.checkPayload` |
| depth | splits at 32 and 16 pixels only | `StoredParser.walk`, `DerivedParser.parse` |
| q | 0-7; nonzero only in modes 8-11, 13, 15 and 17, zero in compact class 7 | both parsers, `CompactRecord.parse` |
| mode 19 child mask | 0 to 14 | `StoredParser.walk` |
| level counts, walk checkpoints | u16 | `DerivedParser.parse` |
| symbol table | 1 to 32 increasing entries; indexes below the count | `readSymbolTable`, `descriptor` |
| walk delta widths | 1 to 16 each, sum at most 16, minimal | `DerivedParser.parse`, `minimalWidths` |
| endpoint table | 1 to 255 distinct pairs | `readEndpointTable` |
| selector tables | 0 to 255 distinct words per size, nonzero exactly when flagged | `readSelectorTables` |
| compact record | class 0-8, form 0-2, VQ index below 64, PQ ids below 4096 | `CompactRecord.parse` |
| pattern record | orientation 0 or 1, indexes inside their tables | `PatternRecord.expand` |
| page | at most 16,384 symbols of 6 to 8 bits; at most 1,369 pages per frame at 6 bits | `TransportPages` |
| pending frames | 4 | `PageAssembler` |

The parsers make the layout canonical for what a frame declares, not for the writer's choices: the index form, the
optional tables, the default colour and the order of endpoint and selector table entries are the writer's (section
3.4), and any valid choice is accepted. Both parsers also accept a few layouts that neither writer produces, and a
conforming parser must accept them too: a present root whose derived descriptor is SKIP, table entries no leaf names (a
selector word with a bad orientation byte included), nonzero bits after the last packed descriptor, and flags 512 to
8192 on stored-index frames.
