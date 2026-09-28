"""MCV2 bounded 32->16->8 trees. No serial parsing in the fragment decoder."""

import struct
import time
from collections import Counter
from dataclasses import dataclass, replace

import numpy as np

from .compact import COMPACT, consider_compact, decode_body, parse_record
from .encoder import Encoder, Settings, choose_blocks, estimate_global
from .format import (
    COARSE_PALETTE_2,
    COARSE_PALETTE_4,
    DEFAULT_SOLID,
    DERIVED_DIRECTORY,
    DERIVED_OFFSETS,
    ENDPOINT_565,
    ENDPOINT_TABLE,
    HEADER,
    KEYFRAME,
    MAX_DIMENSION,
    MAX_FRAME_BYTES,
    MODE_INDEXED_MOTION,
    MOTION_TABLE,
    PACKED_SYMBOLS,
    SELECTOR_TABLE,
    SELECTOR_TABLES,
    SPARSE,
    TWO_LEVEL_WALK,
    Frame,
    coarse_palette_factor,
    decode_signed,
    encode_signed,
    is_residual,
    record_size,
)
from .pattern import (
    PATTERN_PALETTE,
    consider_patterns,
    expand_record,
    index_record,
    selector_word,
)
from .pattern import record_size as pattern_size
from .pixels import blocks, distortion, prediction, rgb8, to_ycocg, unblock

MAGIC = int.from_bytes(b"MCV2", "little")
SPLIT = 16
SPARSE_SPLIT = 19  # Short-index child mask followed by only active descriptors.
SHORT_INDEX = 8  # Header flag: u24 descriptors with a u16 absolute offset.
CHECKPOINT_GROUPS = 8  # Root groups between stored directory checkpoints.
WALK_SPAN = 8  # Descriptors between stored (payload cursor, split prefix) pairs.
COARSE_SPAN = 32  # Descriptors between absolute pairs when the walk plane is two-level.
DELTA_BITS = (
    16  # An anchor-relative checkpoint entry, so the read is two aligned bytes.
)
MAX_SYMBOLS = 32  # Distinct (mode, quantizer) pairs a packed frame may name.
INDEXED_MOTION = MODE_INDEXED_MOTION  # One byte naming a vector in the frame's table.
MAX_MOTION_TABLE = 255  # Entries a frame may name; the count is one byte.
MAX_ENDPOINT_TABLE = 255  # Endpoint pairs a frame may name; the count is one byte.
MAX_SELECTOR_TABLE = 255  # Selector words a frame may name per block size.
PATTERN_SIZES = (8, 16, 32)  # Block sizes a pattern palette can cover.


def symbol_width(count):
    """Bits per descriptor for a table of `count` symbols.

    Derived from the stored count rather than fixed, because a frame using 16 or fewer
    pairs needs 4 bits where one using 17 needs 5, and the measured spread is 9 to 19 - so
    most frames save a further fifth of the plane by not paying for a width they do not
    use. A walk window of WALK_SPAN descriptors is then exactly `width` bytes.
    """
    return max(1, (max(1, count) - 1).bit_length())


IMMEDIATE_MOTION = 20  # Leaf whose two-byte motion record rides in the offset field.
COARSE_PALETTE = (COARSE_PALETTE_2, COARSE_PALETTE_4)
LEAF_MODES = frozenset(range(PATTERN_PALETTE + 1)) | set(COARSE_PALETTE)


@dataclass
class Node:
    """Encoder tree; leaves carry one independently decodable record."""

    mode: int
    q: int = 0
    record: bytes = b""
    children: tuple = ()


@dataclass(frozen=True)
class TreeFrame:
    data: bytes
    width: int
    height: int
    frame_id: int
    reference_id: int
    flags: int
    global_x: int
    global_y: int
    payload_start: int
    default_color: tuple
    leaves: tuple
    partition_bytes: int
    root_index_bytes: int
    child_mask_bytes: int = 0
    block_size: int = 32
    motion_table_entries: int = 0
    endpoint_table: bytes | None = None
    selector_tables: dict | None = None


class DerivedOverflow(ValueError):
    """The derived form cannot address this frame, so the caller falls back.

    Its cursors are sixteen bits, which is the whole point - a wider cursor would cost
    every frame to serve the few that need it. A frame with more than 65535 payload bytes
    simply uses the stored index instead, exactly as an over-long frame already abandons
    the short index.
    """


def record_length(mode, q, record, size, endpoints=False, selectors=False):
    """The length a descriptor implies, which is what makes the address derivable.

    `endpoints` and `selectors` are the frame's table flags for the two halves of a
    pattern record - the second per block size, since a frame may table one size and not
    another. Both are header data a fragment reads once, so a length that depends on them
    is still derivable without touching the payload.
    """
    if mode == PATTERN_PALETTE:
        return pattern_size(size, endpoints, selectors)
    if mode == INDEXED_MOTION:
        # One byte naming a table entry. The entry itself lives in the index, not the
        # payload, so the cursor advances by the index and not by the vector.
        return 1
    if mode == COMPACT:
        return parse_record(record, 0, q)[3]
    return record_size(mode, size)


def level_order(roots):
    """Active roots, then every split's four children, level by level.

    Depth-first order forces a child group's address to depend on every subtree before
    it, which no fragment can recover cheaply. In level order a split's children sit at
    four times the number of splits before it, so a prefix count replaces the address.
    """
    levels = [[node for node in roots if node.mode], [], []]
    for depth in (0, 1):
        children = []
        for node in levels[depth]:
            if node.mode == SPLIT:
                if len(node.children) != 4 or node.q or node.record:
                    raise ValueError("invalid split")
                children.extend(node.children)
        levels[depth + 1] = children
    if any(node.mode == SPLIT for node in levels[2]):
        raise ValueError("split below the bounded depth")
    if any(len(level) > 65535 for level in levels):
        raise ValueError("level exceeds the derived count range")
    return levels


def coarse_stride(walk_span):
    """Walk checkpoints per absolute pair when the plane is two-level."""
    return max(1, COARSE_SPAN // walk_span)


def walk_widths(pairs, stride):
    """The narrowest delta widths that hold every anchor-relative step in a frame.

    A delta is measured from the coarse checkpoint that governs it rather than from its
    predecessor, so a fragment reads one absolute pair and one delta however wide the
    coarse span is. That is what keeps the fetch count flat as the span grows; the price
    is a wider delta, and whether it still fits is a property of the frame rather than of
    the design, so it is measured here and the frame that overflows keeps absolute pairs.
    """
    cursor = splits = 0
    for index, pair in enumerate(pairs):
        anchor = pairs[index - index % stride]
        cursor = max(cursor, pair[0] - anchor[0])
        splits = max(splits, pair[1] - anchor[1])
    return max(1, cursor.bit_length()), max(1, splits.bit_length())


def pack_walk(pairs, stride, two_level):
    """The walk region: absolute pairs, or a coarse plane with anchor-relative deltas."""
    if two_level:
        cursor_bits, splits_bits = walk_widths(pairs, stride)
        if cursor_bits + splits_bits <= DELTA_BITS:
            out = bytearray([cursor_bits, splits_bits])
            for index in range(0, len(pairs), stride):
                out.extend(struct.pack("<HH", *pairs[index]))
            for index, pair in enumerate(pairs):
                if index % stride == 0:
                    continue
                anchor = pairs[index - index % stride]
                out.extend(
                    struct.pack(
                        "<H",
                        (pair[0] - anchor[0]) | (pair[1] - anchor[1]) << cursor_bits,
                    )
                )
            # A frame too small to amortize the two width bytes is larger this way: one
            # walk checkpoint is 6 bytes against 4, and two is a tie. Take the form only
            # when it actually wins, so the flag never costs a frame anything.
            if len(out) < len(pairs) * 4:
                return bytes(out), True
    out = bytearray()
    for pair in pairs:
        out.extend(struct.pack("<HH", *pair))
    return bytes(out), False


def walk_bytes(walkpoints, stride, two_level):
    """How long the walk region is, which the canonical-length check needs up front."""
    if not two_level:
        return walkpoints * 4
    coarse = (walkpoints + stride - 1) // stride
    return 2 + coarse * 4 + (walkpoints - coarse) * 2


def walk_entry(data, offset, walkpoints, stride, widths, index):
    """The (payload cursor, split prefix) pair at walk checkpoint `index`.

    Exactly the two reads the shader makes: the absolute pair at the coarse anchor, and,
    for a checkpoint that is not itself an anchor, one two-byte delta added to it.
    """
    if widths is None:
        return struct.unpack_from("<HH", data, offset + index * 4)
    cursor_bits, splits_bits = widths
    base, splits = struct.unpack_from("<HH", data, offset + 2 + index // stride * 4)
    if index % stride == 0:
        return base, splits
    coarse = (walkpoints + stride - 1) // stride
    at = offset + 2 + coarse * 4 + (index - 1 - (index - 1) // stride) * 2
    (entry,) = struct.unpack_from("<H", data, at)
    if entry >> (cursor_bits + splits_bits):
        raise ValueError("noncanonical walk delta padding")
    return base + (entry & ((1 << cursor_bits) - 1)), splits + (entry >> cursor_bits)


def motion_table_for(flat, sizes):
    """The frame's commonest motion vectors, and the size that saves the most bits.

    Only the *frequent* vectors are worth naming. A motion record is 16 bits and an index
    is 8, a 2:1 ratio, so an entry pays for itself only after two uses - measured reuse
    across a whole frame is 1.6 to 2.0, which is why a full dictionary loses and a
    frequency-truncated one wins. The size is chosen per frame by maximising the saving
    rather than fixed, because the optimum moves with rate: 32 entries at 3 map Mbps and
    48 at 5 on the streams this was measured on.
    """
    counts = Counter(
        node.record
        for node, size in zip(flat, sizes, strict=True)
        if node.mode == 1 and len(node.record) == 2
    )
    if not counts:
        return ()
    ordered = [record for record, _ in counts.most_common(MAX_MOTION_TABLE)]
    best, table = 0, ()
    covered = 0
    for count, record in enumerate(ordered, start=1):
        covered += counts[record]
        # Each covered leaf drops 16 bits to 8; each entry costs its own 16 bits, and the
        # one-byte count is charged once whenever the table is used at all.
        saving = covered * 8 - count * 16 - 8
        if saving > best:
            best, table = saving, tuple(ordered[:count])
    return table


def pack565(colour):
    """Two bytes, little-endian, R5 in bits 15..11, G6 in 10..5, B5 in 4..0."""
    value = (colour[0] >> 3) << 11 | (colour[1] >> 2) << 5 | colour[2] >> 3
    return bytes((value & 0xFF, value >> 8))


def unpack565(pair):
    """The inverse, matching `encoder.quantize565` bit for bit."""
    value = pair[0] | pair[1] << 8
    r, g, b = value >> 11 & 31, value >> 5 & 63, value & 31
    return bytes((r << 3 | r >> 2, g << 2 | g >> 4, b << 3 | b >> 2))


def endpoint_table_for(flat, sizes, entry=6):
    """Distinct endpoint pairs worth naming, or () when naming them does not pay.

    Six bytes against a one-byte index is a 6:1 ratio, so an entry pays for itself after
    about 1.2 uses - which is why this clears the same arithmetic that rejected a motion
    dictionary at 2:1 and solid colours at 3:1. Measured reuse is 1.8, and the table is
    taken whole rather than frequency-truncated because at 6:1 even a single extra use
    covers the entry.
    """
    counts = Counter(
        bytes(node.record[:6])
        for node in flat
        if node.mode == PATTERN_PALETTE and len(node.record) >= 6
    )
    if not counts or len(counts) > MAX_ENDPOINT_TABLE:
        return ()
    # The per-leaf saving is five bytes whichever precision the entry uses, because the
    # inline record stays six; only the entry shrinks. So a 565 table pays more often.
    saving = sum(n * 5 for n in counts.values()) - len(counts) * entry - 1
    return tuple(counts) if saving > 0 else ()


def selector_tables_for(flat, sizes):
    """One table of distinct selector words per block size, when naming them pays.

    Reuse here is far higher than anything else this codec tables - about 7.7 against the
    1.8 of endpoints - because most blocks take one of a few simple row or column
    patterns. The tables are per size because an entry must be fixed width and a 32-block's
    word is five bytes against an 8-block's two.
    """
    counts = {size: Counter() for size in PATTERN_SIZES}
    for node, size in zip(flat, sizes, strict=True):
        if node.mode == PATTERN_PALETTE:
            # A node's record is always the full unpacked form here, endpoints first,
            # whatever the frame will later store - so the word is always at offset six.
            counts[size][selector_word(node.record, size, False)] += 1
    # Three count bytes sit in the index whenever any size is tabled, so they are a
    # frame-level cost weighed once against the sum of the per-size savings rather than
    # charged to whichever size happens to be considered first.
    tables, total = {}, -3
    for size, ctr in counts.items():
        if not ctr or len(ctr) > MAX_SELECTOR_TABLE:
            continue
        entry = 1 + size // 8
        saving = sum(n * (entry - 1) for n in ctr.values()) - len(ctr) * entry
        if saving > 0:
            tables[size] = tuple(ctr)
            total += saving
    return tables if total > 0 else {}


def pack_derived(
    roots,
    count,
    walk_span=WALK_SPAN,
    packed_symbols=False,
    two_level=False,
    motion_table=False,
    endpoint_table=False,
    selector_table=False,
    endpoint_565=False,
):
    """Level-ordered index carrying no offsets at all.

    A descriptor is one byte, mode and quantizer. Every address a fragment needs is
    recovered instead: a root's descriptor index by popcount over the presence masks, a
    split's child group by the split prefix, and a record by the sum of the record
    lengths before it. One checkpoint every `walk_span` descriptors carries the payload
    cursor and the split prefix together, so a single bounded walk over the same
    descriptor bytes serves both recoveries rather than each needing its own plane.
    """
    groups = (count + 31) // 32
    levels = level_order(roots)
    flat = levels[0] + levels[1] + levels[2]
    sizes = [32] * len(levels[0]) + [16] * len(levels[1]) + [8] * len(levels[2])
    # Decide capacity before emitting anything, so an over-long frame is declined rather
    # than discovered half-written by a struct that will not hold the cursor.
    payload_bytes = sum(
        0 if node.mode == SPLIT else record_length(node.mode, node.q, node.record, size)
        for node, size in zip(flat, sizes, strict=True)
    )
    table_vectors = motion_table_for(flat, sizes) if motion_table else ()
    index_of = {record: i for i, record in enumerate(table_vectors)}
    # Only the pattern fitter quantizes. A MODE_PALETTE leaf is fitted at full precision
    # and `repack` may convert it to a pattern, so a frame can hold pairs that do not
    # survive the 565 round trip - and storing those as 565 would change pixels the RDO
    # never priced. Such a frame declines the coarser entry rather than quantize silently.
    if endpoint_565:
        endpoint_565 = all(
            unpack565(pack565(pair[:3])) == pair[:3]
            and unpack565(pack565(pair[3:6])) == pair[3:6]
            for node in flat
            if node.mode == PATTERN_PALETTE and len(node.record) >= 6
            for pair in (bytes(node.record[:6]),)
        )
    pair_entry = 4 if endpoint_565 else 6
    endpoints = endpoint_table_for(flat, sizes, pair_entry) if endpoint_table else ()
    endpoint_of = {pair: i for i, pair in enumerate(endpoints)}
    if endpoints:
        # Each pattern record loses five of its six endpoint bytes to a one-byte index.
        payload_bytes -= sum(5 for node in flat if node.mode == PATTERN_PALETTE)
    words = selector_tables_for(flat, sizes) if selector_table else {}
    word_of = {
        size: {entry: i for i, entry in enumerate(table)}
        for size, table in words.items()
    }
    if words:
        # And its orientation plus axis bytes for another, wherever the size is tabled.
        payload_bytes -= sum(
            size // 8
            for node, size in zip(flat, sizes, strict=True)
            if node.mode == PATTERN_PALETTE and size in words
        )
    if table_vectors:
        # Every leaf the table covers spends one byte instead of two, so the capacity
        # check has to price the frame as it will actually be written.
        payload_bytes -= sum(
            1
            for node, size in zip(flat, sizes, strict=True)
            if node.mode == 1 and node.record in index_of
        )
    if payload_bytes > 65535 or len(flat) > 65535:
        raise DerivedOverflow("frame exceeds the derived cursor range")
    masks, checkpoints = bytearray(), bytearray()
    seen = 0
    for group in range(groups):
        part = roots[group * 32 : (group + 1) * 32]
        mask = sum(1 << i for i, node in enumerate(part) if node.mode)
        masks.extend(struct.pack("<I", mask))
        if group % CHECKPOINT_GROUPS == 0:
            checkpoints.extend(struct.pack("<I", seen))
        seen += mask.bit_count()
    descriptors, payload, pairs = bytearray(), bytearray(), []
    splits = cursor = 0
    for index, (node, size) in enumerate(zip(flat, sizes, strict=True)):
        if index % walk_span == 0:
            pairs.append((cursor, splits))
        if not 0 <= node.q <= 7 or (node.mode != SPLIT and node.mode not in LEAF_MODES):
            raise ValueError("illegal leaf")
        if node.mode == IMMEDIATE_MOTION:
            # An immediate rides in the offset field, and there is no offset field here.
            # Its record belongs in the payload, where it costs the same sixteen bits.
            raise ValueError("immediate motion cannot be carried without an offset")
        if node.mode == PATTERN_PALETTE and (endpoints or size in words):
            which = endpoint_of[bytes(node.record[:6])] if endpoints else None
            slot = (
                word_of[size][selector_word(node.record, size, False)]
                if size in words
                else None
            )
            descriptors.append(node.mode | node.q << 5)
            payload.extend(index_record(node.record, which, slot))
            cursor += pattern_size(size, bool(endpoints), size in words)
            continue
        if node.mode == 1 and node.record in index_of:
            # The vector is named by the table, so the payload carries one byte naming it.
            descriptors.append(INDEXED_MOTION | node.q << 5)
            payload.append(index_of[node.record])
            cursor += 1
            continue
        descriptors.append(node.mode | node.q << 5)
        if node.mode == SPLIT:
            splits += 1
            continue
        if len(node.record) != record_length(node.mode, node.q, node.record, size):
            raise ValueError("record length disagrees with its descriptor")
        payload.extend(node.record)
        cursor += len(node.record)
    counts = struct.pack("<HHH", *(len(level) for level in levels))
    plane = bytes(descriptors)
    table = b""
    if packed_symbols:
        # A frame names far fewer (mode, quantizer) pairs than a byte can express, so the
        # descriptor becomes an index into a table of the pairs it actually uses. Sorted,
        # so the same tree always produces the same table and the form stays canonical.
        symbols = sorted(set(descriptors))
        if len(symbols) <= MAX_SYMBOLS:
            width = symbol_width(len(symbols))
            lookup = {symbol: index for index, symbol in enumerate(symbols)}
            bits = bytearray((len(descriptors) * width + 7) // 8)
            for index, symbol in enumerate(descriptors):
                position = index * width
                value = lookup[symbol] << (position & 7)
                bits[position >> 3] |= value & 255
                if value >> 8:
                    bits[(position >> 3) + 1] |= value >> 8
            table = bytes([len(symbols)]) + bytes(symbols)
            plane = bytes(bits)
    walk, two = pack_walk(pairs, coarse_stride(walk_span), two_level)
    # The count sits in the index, where the canonical-length check can size the frame;
    # the vectors sit at the end of the payload, so a leaf pointing at one still points at
    # or after payloadStart and the decoder's "records live in the payload" guard holds.
    head = bytes([len(table_vectors)]) if table_vectors else b""
    head += bytes([len(endpoints)]) if endpoints else b""
    if words:
        # One count per block size, always all three, so the reader needs no bitmap.
        head += bytes(len(words.get(size, ())) for size in PATTERN_SIZES)
    index = bytes(masks + checkpoints) + counts + table + plane + walk + head
    # Selector words smallest size first, then motion vectors, then endpoint pairs - all
    # at the payload tail so that every record offset stays at or after payloadStart.
    # Pairs sit last so a reader finds them from the pair count alone; the selector region
    # is the only one whose base needs all three word counts, and putting it first means
    # a fragment that decodes no pattern leaf never has to read them.
    tail = b""
    for size in PATTERN_SIZES:
        tail += b"".join(words.get(size, ()))
    stored_pairs = (
        [pack565(p[:3]) + pack565(p[3:]) for p in endpoints]
        if endpoint_565
        else list(endpoints)
    )
    tail += b"".join(table_vectors) + b"".join(stored_pairs)
    return (
        index,
        bytes(payload) + tail,
        bool(table),
        two,
        bool(table_vectors),
        bool(endpoints),
        # The sizes themselves, not just whether any exist: the flags name them one by one
        # so a record's length follows from its mode and size without reading the index.
        frozenset(words),
        # Whether the coarser entry was actually taken, which is not the same as asked for.
        endpoint_565,
    )


def parse_derived(data, flags, count, columns, start, total, walk_span=WALK_SPAN):
    """Recover every address `pack_derived` left out, exactly as the shader does.

    Each recovery is also a validation: a stored checkpoint that disagrees with the
    walk, a level count that disagrees with the split descriptors, or a payload that
    does not end where the last record leaves the cursor rejects the frame. There is no
    address to range-check because there is no address.
    """
    groups = (count + 31) // 32
    stored = (groups + CHECKPOINT_GROUPS - 1) // CHECKPOINT_GROUPS
    head = 48 + groups * 4 + stored * 4
    if head + 6 > start:
        raise ValueError("short derived index")
    n0, n1, n2 = struct.unpack_from("<HHH", data, head)
    base = head + 6
    descriptors = n0 + n1 + n2
    walkpoints = (descriptors + walk_span - 1) // walk_span
    table, width = b"", 8
    if flags & PACKED_SYMBOLS:
        # The table is the frame's own alphabet of (mode, quantizer) pairs, and the width
        # follows from its size, so neither is guessed. A symbol repeated in the table, or
        # one out of order, would make the form non-canonical and is refused.
        if base >= start:
            raise ValueError("short symbol table")
        size = data[base]
        table = data[base + 1 : base + 1 + size]
        if not 1 <= size <= MAX_SYMBOLS or len(table) != size:
            raise ValueError("invalid symbol table size")
        if list(table) != sorted(set(table)):
            raise ValueError("noncanonical symbol table")
        width = symbol_width(size)
        base += 1 + size
    plane = (descriptors * width + 7) // 8
    stride = coarse_stride(walk_span)
    widths = None
    if flags & TWO_LEVEL_WALK:
        # The widths are stored because the shader cannot walk the frame to find them,
        # and they are checked against the walk below so the stream cannot claim wider
        # fields than it needs and stay canonical.
        if base + plane + 2 > start:
            raise ValueError("short walk widths")
        widths = (data[base + plane], data[base + plane + 1])
        if not all(1 <= bits <= 16 for bits in widths) or sum(widths) > DELTA_BITS:
            raise ValueError("invalid walk delta widths")
    region = walk_bytes(walkpoints, stride, widths is not None)
    vectors, table_bytes = (), 0
    pairs_at, pair_count = 0, 0
    if flags & ENDPOINT_TABLE:
        at = base + plane + region + (1 if flags & MOTION_TABLE else 0)
        if at >= start:
            raise ValueError("short endpoint table")
        pair_count = data[at]
        if not 1 <= pair_count <= MAX_ENDPOINT_TABLE:
            raise ValueError("invalid endpoint table size")
    word_counts = ()
    if flags & SELECTOR_TABLE:
        # The head is the motion count, then the endpoint count, then three selector
        # counts, each present only when its own flag is - so this offset has to be taken
        # while `region` is still just the walk region.
        at = (
            base
            + plane
            + region
            + (1 if flags & MOTION_TABLE else 0)
            + (1 if flags & ENDPOINT_TABLE else 0)
        )
        if at + 3 > start:
            raise ValueError("short selector table sizes")
        word_counts = tuple(data[at : at + 3])
        # The counts and the flags say the same thing, and a record's length is read off
        # the flags, so a frame whose count contradicts its flag is not decodable two ways
        # - it is refused rather than left to the reader to resolve.
        for n, flag in zip(word_counts, SELECTOR_TABLES, strict=True):
            if bool(n) != bool(flags & flag):
                raise ValueError("selector table size contradicts its flag")
    # Pairs sit at the very end of the frame and are four bytes each when they are 565, so
    # everything measured back from the end has to use that stride. Reading it as six was a
    # latent round 18 defect: MOTION_TABLE has never been adopted, so no committed stream
    # sets both flags and nothing exercised it. The shader had it right.
    pair_entry = 4 if flags & ENDPOINT_565 else 6
    if flags & MOTION_TABLE:
        at = base + plane + region
        if at >= start:
            raise ValueError("short motion table")
        size = data[at]
        if not 1 <= size <= MAX_MOTION_TABLE:
            raise ValueError("invalid motion table size")
        table_bytes = size * 2
        if total - pair_count * pair_entry - table_bytes < start:
            raise ValueError("truncated motion table")
        # Offsets into the payload tail, so a leaf can point straight at one and every
        # decoder reads two bytes at a computable address.
        vectors = tuple(
            total - pair_count * pair_entry - table_bytes + i * 2 for i in range(size)
        )
        if len({bytes(data[o : o + 2]) for o in vectors}) != size:
            # A repeated entry would let two indexes name one vector and make the form
            # non-canonical, exactly as a repeated symbol would in the packed table.
            raise ValueError("noncanonical motion table")
        region += 1
    if flags & ENDPOINT_TABLE:
        region += 1
    if flags & SELECTOR_TABLE:
        region += 3
    if flags & ENDPOINT_565 and not flags & ENDPOINT_TABLE:
        raise ValueError("565 endpoints without an endpoint table")
    if base + plane + region != start:
        raise ValueError("noncanonical derived index length")
    word_bytes = sum(
        n * (1 + size // 8) for n, size in zip(word_counts, PATTERN_SIZES, strict=False)
    )
    endpoint_bytes = pair_count * pair_entry
    if endpoint_bytes:
        pairs_at = total - endpoint_bytes
        if pairs_at - table_bytes - word_bytes < start:
            raise ValueError("truncated endpoint table")
        raw = bytes(data[pairs_at : pairs_at + endpoint_bytes])
        # Expanded to the six-byte form every downstream reader already expects, so the
        # decoder, the repacker's equality proof and the leaf validator are all unchanged.
        book = (
            b"".join(
                unpack565(raw[i * 4 : i * 4 + 2])
                + unpack565(raw[i * 4 + 2 : i * 4 + 4])
                for i in range(pair_count)
            )
            if flags & ENDPOINT_565
            else raw
        )
        if len({book[i * 6 : i * 6 + 6] for i in range(pair_count)}) != pair_count:
            # Two indexes naming one pair would make the form non-canonical, exactly as a
            # repeated symbol or a repeated motion vector would.
            raise ValueError("noncanonical endpoint table")
    else:
        book = None
    books = {}
    if word_bytes:
        at = total - endpoint_bytes - table_bytes - word_bytes
        if at < start:
            raise ValueError("truncated selector table")
        for n, size in zip(word_counts, PATTERN_SIZES, strict=True):
            entry = 1 + size // 8
            if n:
                chunk = bytes(data[at : at + n * entry])
                if len({chunk[i * entry : (i + 1) * entry] for i in range(n)}) != n:
                    raise ValueError("noncanonical selector table")
                books[size] = chunk
            at += n * entry
    present, seen = [], 0
    for group in range(groups):
        (mask,) = struct.unpack_from("<I", data, 48 + group * 4)
        valid = min(32, count - group * 32)
        if mask >> valid:
            raise ValueError("bad root directory")
        if group % CHECKPOINT_GROUPS == 0:
            (checkpoint,) = struct.unpack_from(
                "<I", data, 48 + groups * 4 + group // CHECKPOINT_GROUPS * 4
            )
            if checkpoint != seen:
                raise ValueError("noncanonical descriptor checkpoint")
        present.extend(group * 32 + bit for bit in range(valid) if mask >> bit & 1)
        seen += mask.bit_count()
    if seen != n0:
        raise ValueError("root count differs from the level-0 count")

    def descriptor(index):
        if not table:
            byte = data[base + index]
            return byte & 31, byte >> 5
        position = index * width
        at = base + (position >> 3)
        # Two bytes always cover a symbol of at most eight bits. The read may run one byte
        # past the plane into the walk checkpoints, which the mask discards.
        word = int.from_bytes(data[at : at + 2], "little")
        symbol = (word >> (position & 7)) & ((1 << width) - 1)
        if symbol >= len(table):
            raise ValueError("symbol index outside the table")
        byte = table[symbol]
        return byte & 31, byte >> 5

    if (
        4 * sum(descriptor(i)[0] == SPLIT for i in range(n0)) != n1
        or 4 * sum(descriptor(i)[0] == SPLIT for i in range(n0, n0 + n1)) != n2
    ):
        raise ValueError("level counts disagree with the split descriptors")
    if any(descriptor(i)[0] == SPLIT for i in range(n0 + n1, descriptors)):
        raise ValueError("split below the bounded depth")
    positions = [None] * descriptors
    for index, root in enumerate(present):
        positions[index] = ((root % columns) * 32, (root // columns) * 32, 32)
    child = n0
    for index in range(n0 + n1):
        if descriptor(index)[0] != SPLIT:
            continue
        x, y, size = positions[index]
        for corner in range(4):
            half = size // 2
            positions[child] = (x + corner % 2 * half, y + corner // 2 * half, half)
            child += 1
    if child != descriptors:
        raise ValueError("unreachable descriptor")
    leaves, splits, cursor, pairs = [], 0, 0, []
    for index in range(descriptors):
        mode, q = descriptor(index)
        if index % walk_span == 0:
            stored = walk_entry(
                data, base + plane, walkpoints, stride, widths, index // walk_span
            )
            if stored != (cursor, splits):
                raise ValueError("noncanonical walk checkpoint")
            pairs.append(stored)
        if mode == SPLIT:
            splits += 1
            continue
        x, y, size = positions[index]
        validate_leaf(data, flags, mode, q, size, start + cursor, book, books.get(size))
        if mode == INDEXED_MOTION:
            # Reported as an ordinary motion leaf pointing at its table entry, so every
            # decoder reads two bytes at a computable address and needs no new mode path.
            which = data[start + cursor]
            if which >= len(vectors):
                raise ValueError("motion index outside the table")
            leaves.append((x, y, size, 1, q, vectors[which]))
        else:
            leaves.append((x, y, size, mode, q, start + cursor if mode else 0))
        cursor += (
            0
            if not mode
            else record_length(
                mode,
                q,
                data[start + cursor : total],
                size,
                bool(flags & ENDPOINT_TABLE),
                size in books,
            )
        )
        if start + cursor > total:
            raise ValueError("invalid leaf payload")
    if start + cursor != total - table_bytes - word_bytes - endpoint_bytes:
        raise ValueError("noncanonical payload length")
    if widths is not None and walk_widths(pairs, stride) != widths:
        # Anything but the narrowest widths would make two encodings of one tree, so a
        # stream that stored wider fields is refused rather than silently accepted.
        raise ValueError("walk delta widths are not minimal")
    # A root the masks leave out is an implicit skip and still covers its pixels, so it
    # has to reach the decoder as a leaf the way the sparse form's zero descriptor does.
    # It carries no record, so it cannot move the cursor and is emitted after the walk.
    absent = set(range(count)) - set(present)
    for root in sorted(absent):
        validate_leaf(data, flags, 0, 0, 32, 0)
        leaves.append(((root % columns) * 32, (root // columns) * 32, 32, 0, 0, 0))
    return tuple(leaves), base, plane, region, table_bytes // 2, book, books


def validate_leaf(data, flags, mode, q, size, offset, book=None, words=None):
    """The leaf rules, shared by both index forms so neither can drift from the other."""
    if (
        (
            mode > PATTERN_PALETTE
            and mode not in COARSE_PALETTE
            and mode != INDEXED_MOTION
        )
        or (q and not (is_residual(mode) or mode == COMPACT))
        or mode == SPLIT
        or mode == SPARSE_SPLIT
        or (mode == INDEXED_MOTION and not flags & MOTION_TABLE)
    ):
        raise ValueError("invalid leaf descriptor")
    if flags & KEYFRAME and (
        mode == 1
        or mode == INDEXED_MOTION
        or mode == COMPACT
        or is_residual(mode)
        or (mode == 0 and not flags & DEFAULT_SOLID)
    ):
        raise ValueError("temporal keyframe leaf")
    if mode == INDEXED_MOTION:
        pass  # The index is range-checked against the table where the table is known.
    elif mode == PATTERN_PALETTE:
        expand_record(data, offset, size, book, words)
    elif mode == COMPACT:
        parse_record(data, offset, q)
    elif mode:
        record_size(mode, size)


def finish_frame(
    width, height, frame_id, reference_id, motion, count, flags, default, index, payload
):
    """Pack the header around a built index and payload, then prove the result parses."""
    payload_start = 48 + len(index)
    header = HEADER.pack(
        MAGIC,
        2 | 5 << 8 | flags << 16,
        width | height << 16,
        frame_id,
        reference_id,
        encode_signed(motion[0], 16) | encode_signed(motion[1], 16) << 16,
        count,
        payload_start,
        payload_start + len(payload),
        int.from_bytes(default, "little") if default else 0,
        0,
        0,
    )
    data = header + bytes(index) + bytes(payload)
    parse_frame(data)
    return data


def pack_frame(
    width,
    height,
    frame_id,
    reference_id,
    keyframe,
    motion,
    roots,
    short_index=False,
    sparse_children=False,
    immediate_motion=False,
    derived_directory=False,
    derived_offsets=False,
    packed_symbols=False,
    two_level_walk=False,
    motion_table=False,
    endpoint_table=False,
    selector_table=False,
    endpoint_565=False,
    walk_span=WALK_SPAN,
):
    """Canonical root index, depth-first child groups, then depth-first payloads."""
    roots = list(roots)
    colors = Counter()

    def visit(node):
        if node.mode == SPLIT:
            for child in node.children:
                visit(child)
        elif node.mode == 2:
            colors[node.record] += 1

    for root in roots:
        visit(root)
    default = colors.most_common(1)[0][0] if keyframe and colors else b"\0\0\0"
    use_default = keyframe and bool(colors)

    def rewrite(node):
        if node.mode == SPLIT:
            return Node(SPLIT, children=tuple(rewrite(n) for n in node.children))
        if use_default and node.mode == 2 and node.record == default:
            return Node(0)
        return node

    roots = [rewrite(n) for n in roots]
    count = len(roots)
    stride = 3 if short_index else 4
    if derived_offsets:
        # The presence masks are not an RD choice here but part of the layout, because a
        # root's descriptor index is their popcount; likewise the checkpoints. Immediate
        # motion is not a contradiction at this level but the fallback's business: this
        # form has no field to carry a record, and the stored form below still does.
        try:
            (
                index,
                payload,
                packed,
                two_level,
                vectors,
                pairs,
                picks,
                coarse_used,
            ) = pack_derived(
                roots,
                count,
                walk_span,
                packed_symbols,
                two_level_walk,
                motion_table,
                endpoint_table,
                selector_table,
                endpoint_565,
            )
        except DerivedOverflow:
            pass
        else:
            return finish_frame(
                width,
                height,
                frame_id,
                reference_id,
                motion,
                count,
                int(keyframe)
                | SPARSE
                | (DEFAULT_SOLID if use_default else 0)
                | DERIVED_DIRECTORY
                | DERIVED_OFFSETS
                | (PACKED_SYMBOLS if packed else 0)
                | (TWO_LEVEL_WALK if two_level else 0)
                | (MOTION_TABLE if vectors else 0)
                | (ENDPOINT_TABLE if pairs else 0)
                | (ENDPOINT_565 if pairs and coarse_used else 0)
                | sum(
                    flag
                    for flag, size in zip(SELECTOR_TABLES, PATTERN_SIZES, strict=True)
                    if size in picks
                ),
                default if use_default else None,
                index,
                payload,
            )

    def put_word(location, word):
        if short_index:
            word = (word & 0xFFFF) | ((word >> 24) << 16)
        index[location - 48 : location - 48 + stride] = word.to_bytes(stride, "little")

    groups = (count + 31) // 32
    active = sum(n.mode != 0 for n in roots)
    # Every group pointer equals the running cursor, so only one checkpoint per
    # CHECKPOINT_GROUPS groups is stored; the rest is recovered by popcount over
    # at most CHECKPOINT_GROUPS - 1 masks, which stays a bounded fragment cost.
    checkpoints = (groups + CHECKPOINT_GROUPS - 1) // CHECKPOINT_GROUPS
    directory = groups * 4 + checkpoints * 4 if derived_directory else groups * 8
    sparse = directory + active * stride < count * stride
    index = bytearray(directory + active * stride if sparse else count * stride)
    locations = []
    cursor = 48 + (directory if sparse else 0)
    for group in range(groups):
        part = roots[group * 32 : (group + 1) * 32]
        if sparse:
            mask = sum(1 << i for i, n in enumerate(part) if n.mode)
            if derived_directory:
                struct.pack_into("<I", index, group * 4, mask)
                if group % CHECKPOINT_GROUPS == 0:
                    struct.pack_into(
                        "<I", index, groups * 4 + group // CHECKPOINT_GROUPS * 4, cursor
                    )
            else:
                struct.pack_into("<II", index, group * 8, mask, cursor)
        for i, node in enumerate(part):
            if sparse:
                locations.append(cursor if node.mode else None)
                cursor += stride if node.mode else 0
            else:
                locations.append(48 + (group * 32 + i) * stride)
    leaves = []

    def emit(node, location):
        if location is None:
            if node.mode:
                raise ValueError("missing root descriptor")
            return
        if node.mode == SPLIT:
            if len(node.children) != 4 or node.q or node.record:
                raise ValueError("invalid split")
            offset = 48 + len(index)
            mask = sum(1 << i for i, child in enumerate(node.children) if child.mode)
            packed = sparse_children and stride == 3 and mask != 15
            if packed:
                index.extend(bytes([mask]) + bytes(mask.bit_count() * stride))
                put_word(location, offset | SPARSE_SPLIT << 24)
                child_offset = offset + 1
                for child in node.children:
                    emit(child, child_offset if child.mode else None)
                    child_offset += stride if child.mode else 0
            else:
                index.extend(bytes(4 * stride))
                put_word(location, offset | SPLIT << 24)
                for i, child in enumerate(node.children):
                    emit(child, offset + i * stride)
        else:
            leaves.append((node, location))

    for node, location in zip(roots, locations, strict=True):
        emit(node, location)
    payload_start = 48 + len(index)
    payload = bytearray()
    for node, location in leaves:
        if not 0 <= node.q <= 7 or node.mode not in LEAF_MODES:
            raise ValueError("illegal leaf")
        # A two-byte motion record is exactly the width of the offset field, so
        # carry it there and spend no payload bytes and no extra fetch on it.
        # The form has no quantizer, so a node carrying one must not take it:
        # it falls through and parse_frame rejects it rather than losing it.
        if (
            immediate_motion
            and node.mode == 1
            and node.q == 0
            and len(node.record) == 2
        ):
            put_word(
                location,
                int.from_bytes(node.record, "little") | IMMEDIATE_MOTION << 24,
            )
            continue
        offset = payload_start + len(payload) if node.mode else 0
        put_word(location, offset | node.mode << 24 | node.q << 29)
        payload.extend(node.record)
    if short_index and payload_start + len(payload) > 65535:
        # No truncated addresses: retry the same tree using the wide layout.
        # Restore keyframe defaults rewritten above before repacking.
        def restore(node):
            if node.mode == SPLIT:
                return Node(SPLIT, children=tuple(restore(n) for n in node.children))
            return Node(2, record=default) if use_default and node.mode == 0 else node

        return pack_frame(
            width,
            height,
            frame_id,
            reference_id,
            keyframe,
            motion,
            [restore(n) for n in roots],
            short_index=False,
            immediate_motion=immediate_motion,
            derived_directory=derived_directory,
        )
    return finish_frame(
        width,
        height,
        frame_id,
        reference_id,
        motion,
        count,
        int(keyframe)
        | (SPARSE if sparse else 0)
        | (DEFAULT_SOLID if use_default else 0)
        | (SHORT_INDEX if short_index else 0)
        | (DERIVED_DIRECTORY if sparse and derived_directory else 0),
        default if use_default else None,
        index,
        payload,
    )


def parse_frame(data):
    """Validate every pointer and node with depth <=2 before frame acceptance."""
    if not 48 <= len(data) <= MAX_FRAME_BYTES:
        raise ValueError("invalid frame length")
    magic, config, dims, fid, rid, motion, count, start, total, color, r0, r1 = (
        HEADER.unpack_from(data)
    )
    w, h, flags = dims & 65535, dims >> 16, config >> 16
    if magic != MAGIC or config & 65535 != 0x502 or flags & ~16383 or r0 or r1:
        raise ValueError("unsupported MCV2 header")
    if not 1 <= w <= MAX_DIMENSION or not 1 <= h <= MAX_DIMENSION or total != len(data):
        raise ValueError("invalid dimensions/total")
    stride = 3 if flags & SHORT_INDEX else 4
    if stride == 3 and total > 65535:
        raise ValueError("short index frame exceeds address range")

    def get_word(offset):
        word = int.from_bytes(data[offset : offset + stride], "little")
        return (word & 65535) | ((word >> 16) << 24) if stride == 3 else word

    columns = (w + 31) // 32
    if count != columns * ((h + 31) // 32) or not 48 <= start <= total:
        raise ValueError("invalid root table")
    if (flags & KEYFRAME and (fid != rid or motion)) or (
        not flags & KEYFRAME and fid == rid
    ):
        raise ValueError("invalid reference metadata")
    if (
        color > 0xFFFFFF
        or (flags & DEFAULT_SOLID and not flags & KEYFRAME)
        or (not flags & DEFAULT_SOLID and color)
    ):
        raise ValueError("invalid default color")
    if flags & TWO_LEVEL_WALK and not flags & DERIVED_OFFSETS:
        # The two-level plane is a layout of the derived walk checkpoints, and no other
        # index form has walk checkpoints to lay out.
        raise ValueError("two-level walk requires derived offsets")
    if flags & PACKED_SYMBOLS and not flags & DERIVED_OFFSETS:
        # The symbol table indexes the derived form's descriptor plane; there is no other
        # plane for it to index.
        raise ValueError("packed symbols require derived offsets")
    if flags & DERIVED_OFFSETS:
        if not flags & SPARSE or not flags & DERIVED_DIRECTORY or flags & SHORT_INDEX:
            raise ValueError("derived offsets require the level-ordered sparse form")
        leaves, base, plane, region, entries, book, books = parse_derived(
            data, flags, count, columns, start, total
        )
        return TreeFrame(
            data,
            w,
            h,
            fid,
            rid,
            flags,
            decode_signed(motion, 16),
            decode_signed(motion >> 16, 16),
            start,
            (color & 255, (color >> 8) & 255, color >> 16),
            leaves,
            plane + region,
            base - 48,
            motion_table_entries=entries,
            endpoint_table=book,
            selector_tables=books or None,
        )
    roots = []
    if flags & DERIVED_DIRECTORY and not flags & SPARSE:
        raise ValueError("derived directory requires the sparse root form")
    if flags & SPARSE:
        groups = (count + 31) // 32
        derived = bool(flags & DERIVED_DIRECTORY)
        checkpoints = (groups + CHECKPOINT_GROUPS - 1) // CHECKPOINT_GROUPS
        cursor = 48 + (groups * 4 + checkpoints * 4 if derived else groups * 8)
        if cursor > start:
            raise ValueError("short root directory")
        for group in range(groups):
            if derived:
                (mask,) = struct.unpack_from("<I", data, 48 + group * 4)
                ptr = cursor
                if group % CHECKPOINT_GROUPS == 0:
                    (ptr,) = struct.unpack_from(
                        "<I", data, 48 + groups * 4 + group // CHECKPOINT_GROUPS * 4
                    )
            else:
                mask, ptr = struct.unpack_from("<II", data, 48 + group * 8)
            valid = min(32, count - group * 32)
            if (
                ptr != cursor
                or mask >> valid
                or cursor + mask.bit_count() * stride > start
            ):
                raise ValueError("bad root directory")
            for bit in range(valid):
                word = 0
                if mask & 1 << bit:
                    word = get_word(cursor)
                    cursor += stride
                    if not word:
                        raise ValueError("explicit sparse skip")
                roots.append(word)
    else:
        cursor = 48 + count * stride
        if cursor > start:
            raise ValueError("short root index")
        roots = [get_word(48 + i * stride) for i in range(count)]
    root_end = cursor
    child_mask_bytes = 0
    leaves = []

    def walk(word, x, y, size):
        nonlocal cursor, child_mask_bytes
        mode, q, offset = (word >> 24) & 31, word >> 29, word & 0xFFFFFF
        if mode in (SPLIT, SPARSE_SPLIT):
            if size == 8 or q or offset != cursor:
                raise ValueError("invalid split address/depth")
            if mode == SPARSE_SPLIT:
                if stride != 3 or cursor >= start or data[cursor] >= 15:
                    raise ValueError("invalid sparse child mask/layout")
                mask = data[cursor]
                cursor += 1
                child_mask_bytes += 1
                if cursor + mask.bit_count() * stride > start:
                    raise ValueError("truncated sparse children")
                children = []
                for i in range(4):
                    word = get_word(cursor) if mask & (1 << i) else 0
                    if mask & (1 << i):
                        if not word:
                            raise ValueError("explicit sparse child skip")
                        cursor += stride
                    children.append(word)
            else:
                if cursor + 4 * stride > start:
                    raise ValueError("truncated children")
                children = [get_word(cursor + i * stride) for i in range(4)]
                cursor += 4 * stride
            for i, child in enumerate(children):
                walk(
                    child, x + (i % 2) * size // 2, y + (i // 2) * size // 2, size // 2
                )
            return
        if (
            (mode > PATTERN_PALETTE and mode not in (IMMEDIATE_MOTION, *COARSE_PALETTE))
            or (q and not (is_residual(mode) or mode == COMPACT))
            or (mode == 0 and word)
            or (mode == IMMEDIATE_MOTION and offset >> 16)
        ):
            raise ValueError("invalid leaf descriptor")
        if flags & KEYFRAME and (
            mode == 1
            or mode == COMPACT
            or mode == IMMEDIATE_MOTION
            or is_residual(mode)
            or (mode == 0 and not flags & DEFAULT_SOLID)
        ):
            raise ValueError("temporal keyframe leaf")
        if mode not in (COMPACT, PATTERN_PALETTE, IMMEDIATE_MOTION):
            record_size(mode, size)
        leaves.append((x, y, size, mode, q, offset))

    for i, word in enumerate(roots):
        walk(word, (i % columns) * 32, (i // columns) * 32, 32)
    if cursor != start:
        raise ValueError("noncanonical node table")
    immediates = sum(mode == IMMEDIATE_MOTION for *_, mode, _q, _o in leaves)
    # An immediate record is materialized beside the frame when decoding, so it
    # is charged the same address space a stored record would have occupied.
    if total + 2 * immediates > MAX_FRAME_BYTES:
        raise ValueError("immediate records exceed the frame address range")
    for _x, _y, size, mode, _q, offset in leaves:
        if mode == IMMEDIATE_MOTION:
            continue
        length = (
            # The stored index form never carries an endpoint table: the flag requires
            # derived offsets, which this path is the alternative to.
            pattern_size(size)
            if mode == PATTERN_PALETTE
            else parse_record(data, offset, _q)[3]
            if mode == COMPACT
            else record_size(mode, size)
        )
        if mode == PATTERN_PALETTE:
            expand_record(data, offset, size)
        if mode and (offset != cursor or length > total - offset):
            raise ValueError("invalid leaf payload")
        cursor += length
    if cursor != total:
        raise ValueError("noncanonical payload length")
    return TreeFrame(
        data,
        w,
        h,
        fid,
        rid,
        flags,
        decode_signed(motion, 16),
        decode_signed(motion >> 16, 16),
        start,
        (color & 255, (color >> 8) & 255, color >> 16),
        tuple(leaves),
        start - root_end,
        root_end - 48,
        child_mask_bytes=child_mask_bytes,
    )


def decode_frame(frame, reference=None, reference_id=None):
    """Batch mixed leaves through the normative legacy pixel arithmetic."""
    from .decoder import decode_legacy

    if not frame.flags & KEYFRAME and (
        reference is None
        or reference_id != frame.reference_id
        or reference.shape != (frame.height, frame.width, 3)
        or reference.dtype != np.uint8
    ):
        raise ValueError("reference frame mismatch")
    output = np.zeros((frame.height, frame.width, 3), np.uint8)
    # Immediate motion records carry the same two bytes a stored record would,
    # so materialize them beside the frame and let the normative legacy pixel
    # arithmetic run untouched. parse_frame guarantees the addresses fit.
    data = frame.data
    materialized = {}
    immediates = [leaf for leaf in frame.leaves if leaf[3] == IMMEDIATE_MOTION]
    if immediates:
        extra = bytearray()
        for leaf in immediates:
            materialized[leaf[:3]] = len(data) + len(extra)
            extra += (leaf[5] & 0xFFFF).to_bytes(2, "little")
        data = bytes(data) + bytes(extra)
    for size in (32, 16, 8):
        leaves = [leaf for leaf in frame.leaves if leaf[2] == size]
        if not leaves:
            continue
        columns = (frame.width + size - 1) // size
        count = columns * ((frame.height + size - 1) // size)
        words = [0] * count
        for x, y, _, mode, q, offset in leaves:
            if x >= frame.width or y >= frame.height:
                continue
            if mode == IMMEDIATE_MOTION:
                words[y // size * columns + x // size] = (
                    materialized[x, y, size] | 1 << 24
                )
            elif mode not in (COMPACT, PATTERN_PALETTE, *COARSE_PALETTE):
                words[y // size * columns + x // size] = offset | mode << 24 | q << 28
        legacy = Frame(
            data,
            frame.width,
            frame.height,
            size,
            frame.frame_id,
            frame.reference_id,
            frame.flags | DEFAULT_SOLID if frame.flags & KEYFRAME else frame.flags,
            frame.global_x,
            frame.global_y,
            tuple(words),
            frame.payload_start,
            frame.default_color,
        )
        decoded = decode_legacy(legacy, reference, reference_id)
        compact_leaves = [
            leaf
            for leaf in leaves
            if leaf[3] == COMPACT and leaf[0] < frame.width and leaf[1] < frame.height
        ]
        if compact_leaves:
            vectors = np.tile([frame.global_x, frame.global_y], (count, 1))
            groups = {}
            for x, y, _, _mode, q, offset in compact_leaves:
                kind, delta, body, _ = parse_record(data, offset, q)
                index = y // size * columns + x // size
                vectors[index] += delta
                groups.setdefault((kind, q), []).append((index, body))
            predicted = prediction(reference, size, vectors)
            pixel_blocks = blocks(decoded, size)
            for (kind, q), entries in groups.items():
                indices = [entry[0] for entry in entries]
                pixels = decode_body(
                    kind, b"".join(entry[1] for entry in entries), q, predicted[indices]
                )
                pixel_blocks[indices] = rgb8(pixels)
            decoded = unblock(pixel_blocks, frame.width, frame.height)
        for x, y, _, mode, _, offset in leaves:
            factor = coarse_palette_factor(mode)
            if not factor or x >= frame.width or y >= frame.height:
                continue
            cells = size // factor
            colors = np.frombuffer(data, np.uint8, 6, offset).reshape(2, 3)
            selectors = np.unpackbits(
                np.frombuffer(data, np.uint8, cells * cells // 8, offset + 6),
                bitorder="little",
            ).reshape(cells, cells)
            block = colors[selectors].repeat(factor, axis=0).repeat(factor, axis=1)
            decoded[y : y + size, x : x + size] = block[
                : min(size, frame.height - y), : min(size, frame.width - x)
            ]
        for x, y, _, mode, _, offset in leaves:
            if mode == PATTERN_PALETTE and x < frame.width and y < frame.height:
                record = expand_record(
                    data,
                    offset,
                    size,
                    frame.endpoint_table,
                    (frame.selector_tables or {}).get(size),
                )
                colors = np.frombuffer(record, np.uint8, 6).reshape(2, 3)
                selectors = np.unpackbits(
                    np.frombuffer(record, np.uint8, offset=6), bitorder="little"
                ).reshape(size, size)
                decoded[y : y + size, x : x + size] = colors[selectors][
                    : min(size, frame.height - y), : min(size, frame.width - x)
                ]
        for x, y, _, _mode, _q, _offset in leaves:
            output[y : y + size, x : x + size] = decoded[y : y + size, x : x + size]
    return output


@dataclass(frozen=True)
class TreeSettings:
    adaptive: bool = True
    short_index: bool = False
    palette_patterns: bool = False
    pattern_rdo: bool = False
    sparse_children: bool = False
    residual_classes: tuple[int, ...] = ()
    wire_rdo: bool = False
    symbol_bits: int = 6
    perceptual: bool = False
    immediate_motion: bool = False
    derived_directory: bool = False
    derived_offsets: bool = False
    packed_symbols: bool = False
    two_level_walk: bool = False
    motion_table: bool = False
    endpoint_table: bool = False
    endpoint_565: bool = False
    selector_table: bool = False
    matched_cost_model: bool = False

    def descriptor_bits(self, matched=False):
        """What one descriptor actually costs on the wire in this index form.

        Returned only when `matched` is set, so that a configuration which has not asked
        for the corrected model keeps the 32 bits the search has always charged and
        reproduces its recorded numbers exactly. 32 is a wide four-byte descriptor; the
        adopted short index has been three bytes for several rounds, and round 7's derived
        form is one byte plus its share of a checkpoint every WALK_SPAN descriptors.
        """
        if not matched:
            return 32
        if self.derived_offsets:
            if self.two_level_walk:
                # One absolute pair per COARSE_SPAN descriptors, and a two-byte delta
                # for every other checkpoint in the run.
                fine = DELTA_BITS * (coarse_stride(WALK_SPAN) - 1)
                return 8 + (32 + fine) / COARSE_SPAN
            return 8 + 32 // WALK_SPAN
        return 24 if self.short_index else 32

    def __post_init__(self):
        if self.sparse_children and not self.short_index:
            raise ValueError("sparse child tables require short indexes")
        if self.packed_symbols and not self.derived_offsets:
            raise ValueError("packed symbols require derived offsets")
        if self.two_level_walk and not self.derived_offsets:
            raise ValueError("two-level walk requires derived offsets")
        if self.motion_table and not self.derived_offsets:
            # The table lives in the derived index, and no other form has one.
            raise ValueError("motion table requires derived offsets")
        if self.endpoint_565 and not self.endpoint_table:
            raise ValueError("endpoint_565 needs endpoint_table")
        if self.endpoint_table and not (self.derived_offsets and self.palette_patterns):
            # It names pattern-palette endpoints, so both are prerequisites.
            raise ValueError("endpoint table requires derived offsets and patterns")
        if self.selector_table and not (self.derived_offsets and self.palette_patterns):
            raise ValueError("selector table requires derived offsets and patterns")
        if self.derived_offsets and not self.derived_directory:
            # The derived form always carries masks and checkpoints, so asking for it
            # without the directory that holds them would be a contradiction.
            raise ValueError("derived offsets require the derived directory")
        if self.symbol_bits not in (6, 7) or any(
            k not in range(9) for k in self.residual_classes
        ):
            raise ValueError("invalid tree settings")


def edge_weights(source):
    """Optional encoder-only edge emphasis; not a claim of perceptual optimality."""
    y = to_ycocg(source)[..., 0]
    dx = np.abs(np.diff(y, axis=1, prepend=y[:, :1]))
    dy = np.abs(np.diff(y, axis=0, prepend=y[:1]))
    return 1 + np.minimum((dx + dy) / 32, 2)


class TreeEncoder(Encoder):
    """Bottom-up temporal RDO with two optional splits, sharing one feedback frame."""

    def __init__(self, settings=None, tree_settings=None):
        super().__init__(settings or Settings(block_size=32))
        self.tree_settings = tree_settings or TreeSettings()
        # Leaky bucket over the charged map wire rate, not the logical byte count,
        # because the map rate is what a stream actually has to fit inside.
        self.vbv_bits = self.settings.vbv_buffer_bits * self.settings.vbv_initial_fill
        self.vbv_offset = 0.0
        self.vbv_events = []

    def _serialize(self, data, coarse):
        """The bytes this configuration actually writes for an RDO-decided frame.

        Candidate scoring calls this before measuring the rate, because a mechanism whose
        saving appears only in the repacked form is otherwise invisible to the choice while
        its distortion is fully visible. That asymmetry is why a 565 trial could never win:
        the wide-index frame it was scored on still stores endpoints inline.
        """
        settings = self.tree_settings
        if not (
            settings.short_index
            or settings.palette_patterns
            or settings.immediate_motion
            or settings.derived_directory
            or settings.derived_offsets
            or settings.packed_symbols
            or settings.two_level_walk
            or settings.motion_table
            or settings.endpoint_table
            or settings.selector_table
            or settings.endpoint_565
        ):
            return data
        from .repack import repack

        # Preserve the measured wide-index RDO decisions, then pack losslessly.
        return repack(
            data,
            short_index=settings.short_index,
            palette_patterns=settings.palette_patterns,
            sparse_children=settings.sparse_children,
            immediate_motion=settings.immediate_motion,
            derived_directory=settings.derived_directory,
            derived_offsets=settings.derived_offsets,
            packed_symbols=settings.packed_symbols,
            two_level_walk=settings.two_level_walk,
            motion_table=settings.motion_table,
            endpoint_table=settings.endpoint_table,
            selector_table=settings.selector_table,
            endpoint_565=coarse,
        )

    def _vbv_scale(self):
        """Lambda multiplier from bucket state; 1.0 when rate control is off.

        Proportional-integral control in log-lambda. The proportional term reacts to
        bucket fullness so a burst is damped immediately; the integral term
        accumulates the per-frame rate error so the mean rate converges on the target
        however far the configured lambda starts from it. A purely proportional
        controller cannot do that: its steady-state gain is bounded by the fullness
        error, which saturates, so it settles at whatever rate that gain happens to
        produce. Both terms are clamped so no single frame can swing the quantiser
        wildly, and the integral is clamped so it cannot wind up.
        """
        if not self.settings.vbv_bitrate:
            return 1.0
        fullness = self.vbv_bits / self.settings.vbv_buffer_bits
        error = fullness - self.settings.vbv_initial_fill
        proportional = np.clip(self.settings.vbv_strength * error, -1.5, 1.5)
        return float(np.exp(np.clip(proportional + self.vbv_offset, -3.0, 3.0)))

    def _vbv_update(self, data):
        """Charge the chosen frame's wire bits and drain one frame interval."""
        if not self.settings.vbv_bitrate:
            return None
        from .transport import make_pages, wire_bytes

        wire = (
            wire_bytes(make_pages(data, symbol_bits=self.tree_settings.symbol_bits)) * 8
        )
        drain = self.settings.vbv_bitrate / self.settings.vbv_fps
        filled = self.vbv_bits + wire
        overflow = max(0.0, filled - self.settings.vbv_buffer_bits)
        self.vbv_bits = max(0.0, min(filled, self.settings.vbv_buffer_bits) - drain)
        underflow = filled < drain
        # Integral term: accumulate the relative overshoot of this frame against the
        # per-frame budget, so lambda keeps moving until the mean rate matches.
        self.vbv_offset = float(
            np.clip(
                self.vbv_offset + self.settings.vbv_integral * (wire - drain) / drain,
                -3.0,
                3.0,
            )
        )
        record = dict(
            vbv_wire_bits=wire,
            vbv_offset=self.vbv_offset,
            vbv_fullness=self.vbv_bits / self.settings.vbv_buffer_bits,
            vbv_overflow_bits=overflow,
            vbv_underflow=bool(underflow),
        )
        self.vbv_events.append(record)
        return record

    def encode(self, source, frame_id):
        # Reuse the baseline's input/ordering guard without committing a trial.
        if (
            source.dtype != np.uint8
            or source.ndim != 3
            or source.shape[2] != 3
            or not all(1 <= s <= 4096 for s in source.shape[:2])
            or not 0 <= frame_id <= 0xFFFFFFFF
        ):
            raise ValueError("invalid source or frame ID")
        if (
            self.reference is not None
            and not 0 < ((frame_id - self.reference_id) & 0xFFFFFFFF) < 0x80000000
        ):
            raise ValueError("stale or ambiguous frame number")
        started = time.perf_counter()
        key = (
            self.reference is None
            or source.shape != self.reference.shape
            or self.frames_since_key >= self.settings.key_interval
        )
        motion = (
            (0, 0)
            if key or not self.settings.global_motion
            else estimate_global(source, self.reference)
        )
        if not key:
            pred = prediction(
                self.reference, 32, np.tile(motion, (len(blocks(source, 32)), 1))
            )
            if (
                np.mean(
                    np.abs(
                        to_ycocg(blocks(source, 32))[..., 0] - to_ycocg(pred)[..., 0]
                    )
                )
                > self.settings.scene_threshold
            ):
                key, motion = True, (0, 0)
        candidates = []
        scale = self._vbv_scale()
        for vector in (
            [(0, 0), motion]
            if self.settings.compare_global and motion != (0, 0)
            else [motion]
        ):
            for factor in (0.85, 1.0, 1.18) if self.tree_settings.wire_rdo else (1.0,):
                # `endpoint_565` asks for the choice, not for the coarser endpoints: a
                # frame is fitted both ways and scored by the same rate-distortion sum
                # below. A single global setting would be worse than useless above about
                # 5 Mbps, where the measurement says the bits saved cost more distortion
                # than they are worth, because the fitter emits one candidate and the leaf
                # RDO would push good patterns onto a dearer mode rather than decline the
                # quantization.
                for coarse in (
                    (False, True) if self.tree_settings.endpoint_565 else (False,)
                ):
                    trial = TreeEncoder(
                        replace(
                            self.settings,
                            lambda_value=self.settings.lambda_value * factor * scale,
                        ),
                        replace(self.tree_settings, endpoint_565=coarse),
                    )
                    trial.reference, trial.reference_id = (
                        self.reference,
                        self.reference_id,
                    )
                    data, expected = trial._tree(source, frame_id, key, vector)
                    data = trial._serialize(data, coarse)
                    if self.tree_settings.perceptual:
                        delta = to_ycocg(source.astype(np.float32) - expected)
                        error = (
                            np.sum(
                                delta
                                * delta
                                * np.array([4, 1, 1], np.float32)
                                * edge_weights(source)[..., None]
                            )
                            / 6
                        )
                    else:
                        error = distortion(
                            blocks(source, 32), blocks(expected, 32)
                        ).sum()
                    rate = len(data)
                    if self.tree_settings.wire_rdo:
                        from .transport import make_pages, wire_bytes

                        rate = (
                            wire_bytes(
                                make_pages(
                                    data, symbol_bits=self.tree_settings.symbol_bits
                                )
                            )
                            * self.tree_settings.symbol_bits
                            / 8
                        )
                    candidates.append(
                        (
                            error + self.settings.lambda_value * 8 * rate,
                            data,
                            expected,
                            coarse,
                        )
                    )
        _, data, expected, coarse = min(candidates, key=lambda c: c[0])
        frame = parse_frame(data)
        reconstructed = decode_frame(frame, self.reference, self.reference_id)
        if not np.array_equal(reconstructed, expected):
            raise AssertionError("MCV2 encoder/decoder disagreement")
        self.reference, self.reference_id = reconstructed, frame_id
        self.frames_since_key = 1 if key else self.frames_since_key + 1
        self.stats = dict(
            seconds=time.perf_counter() - started,
            keyframe=key,
            global_motion=[frame.global_x, frame.global_y],
            header_bytes=48,
            index_bytes=frame.payload_start - 48,
            payload_bytes=len(data) - frame.payload_start,
            partition_bytes=frame.partition_bytes,
            root_index_bytes=frame.root_index_bytes,
            leaves=len(frame.leaves),
            leaf_sizes=dict(Counter(str(leaf[2]) for leaf in frame.leaves)),
            modes=np.bincount(
                [leaf[3] for leaf in frame.leaves], minlength=16
            ).tolist(),
            vbv_lambda_scale=scale,
            **(self._vbv_update(data) or {}),
        )
        return data

    def _tree(self, source, frame_id, key, motion):
        height, width = source.shape[:2]
        # Extend to superblocks for valid child coordinates; crop only at output.
        padded = np.pad(
            source, ((0, (-height) % 32), (0, (-width) % 32), (0, 0)), mode="edge"
        )
        # Motion sampling must clamp to the actual reference dimensions, so leaf
        # candidates use original dimensions and nonexistent edge leaves are solid.
        choices = {}
        images = {}
        matched = self.tree_settings.matched_cost_model
        descriptor_bits = self.tree_settings.descriptor_bits(matched)
        for size in (32, 16, 8) if self.tree_settings.adaptive else (32,):
            settings = replace(self.settings, block_size=size, sparse=False)
            # A skipped root is carried by one presence-mask bit; a skipped child of a
            # split needs a real descriptor. The old model charged a full wide descriptor
            # for both, which pushed the search away from skips at exactly the low-rate
            # end where skips dominate.
            skip_bits = (1 if size == 32 else descriptor_bits) if matched else None
            weights = None
            if self.tree_settings.perceptual:
                weights = blocks(
                    np.repeat(edge_weights(source)[..., None], 3, axis=2), size
                )[..., 0]
            c = choose_blocks(
                blocks(source, size),
                None if key else self.reference,
                motion,
                settings,
                weights=weights,
                retain_prediction=bool(self.tree_settings.residual_classes),
                index_bits=descriptor_bits,
                skip_bits=skip_bits,
            )
            if self.tree_settings.pattern_rdo:
                consider_patterns(c, self.tree_settings.endpoint_565)
            if not key and self.tree_settings.residual_classes:
                global_pred, local_vectors, local_pred = c.temporal
                for vectors, pred in (
                    (np.tile(motion, (len(c.source), 1)), global_pred),
                    (local_vectors, local_pred),
                ):
                    consider_compact(
                        c,
                        pred,
                        vectors - np.array(motion),
                        self.tree_settings.residual_classes,
                    )
                del c.temporal
            choices[size] = c
            images[size] = unblock(c.pixels, width, height)
        output = np.zeros_like(padded)

        def select(x, y, size):
            if x >= width or y >= height:
                return Node(2, record=b"\0\0\0"), self.settings.lambda_value * 56, []
            c = choices[size]
            columns = (width + size - 1) // size
            i = y // size * columns + x // size
            node = Node(int(c.modes[i]), int(c.quantizers[i]), c.records[i])
            cost = c.costs[i]
            selected = [(x, y, size)]
            if self.tree_settings.adaptive and size > 8:
                children = [
                    select(x + (j % 2) * size // 2, y + (j // 2) * size // 2, size // 2)
                    for j in range(4)
                ]
                split_cost = (
                    sum(t[1] for t in children)
                    + self.settings.lambda_value * descriptor_bits
                )
                if split_cost < cost:
                    node = Node(SPLIT, children=tuple(t[0] for t in children))
                    cost = split_cost
                    selected = [leaf for t in children for leaf in t[2]]
            return node, cost, selected

        roots = []
        for y in range(0, height, 32):
            for x in range(0, width, 32):
                node, _, selected = select(x, y, 32)
                roots.append(node)
                for xx, yy, size in selected:
                    output[yy : min(yy + size, height), xx : min(xx + size, width)] = (
                        images[size][yy : yy + size, xx : xx + size]
                    )
        data = pack_frame(
            width,
            height,
            frame_id,
            frame_id if key else self.reference_id,
            key,
            motion,
            roots,
        )
        return data, output[:height, :width]
