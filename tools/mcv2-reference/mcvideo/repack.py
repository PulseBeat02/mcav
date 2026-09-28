"""Lossless MCV2 index repacking; leaf decisions and reference pixels are preserved."""

from .compact import COMPACT, parse_record
from .format import DEFAULT_SOLID, KEYFRAME, record_size
from .pattern import PATTERN_PALETTE, compact_record, expand_record
from .pattern import record_size as pattern_size
from .v2 import IMMEDIATE_MOTION, SPLIT, Node, pack_frame, parse_frame


def roots_from_frame(frame):
    leaves = {}
    for x, y, size, mode, q, offset in frame.leaves:
        if mode == IMMEDIATE_MOTION:
            # Normalize back to the stored form so repacking is idempotent and
            # the semantic-equality proof below compares like with like.
            leaves[x, y, size] = Node(1, record=(offset & 0xFFFF).to_bytes(2, "little"))
            continue
        length = (
            pattern_size(
                size,
                frame.endpoint_table is not None,
                size in (frame.selector_tables or {}),
            )
            if mode == PATTERN_PALETTE
            else parse_record(frame.data, offset, q)[3]
            if mode == COMPACT
            else record_size(mode, size)
        )
        node = Node(mode, q, frame.data[offset : offset + length])
        if mode == PATTERN_PALETTE:
            node = Node(
                3,
                record=expand_record(
                    frame.data,
                    offset,
                    size,
                    frame.endpoint_table,
                    (frame.selector_tables or {}).get(size),
                ),
            )
        if mode == 0 and frame.flags & DEFAULT_SOLID:
            node = Node(2, record=bytes(frame.default_color))
        leaves[x, y, size] = node

    def visit(x, y, size):
        if (x, y, size) in leaves:
            return leaves[x, y, size]
        if size == 8:
            raise ValueError("missing leaf")
        return Node(
            SPLIT,
            children=tuple(
                visit(x + i % 2 * size // 2, y + i // 2 * size // 2, size // 2)
                for i in range(4)
            ),
        )

    return [
        visit(x, y, 32)
        for y in range(0, frame.height, 32)
        for x in range(0, frame.width, 32)
    ]


def repack(
    data,
    short_index=True,
    palette_patterns=False,
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
):
    original = parse_frame(data)
    roots = roots_from_frame(original)

    def patterns(node, size):
        if node.mode == SPLIT:
            return Node(
                SPLIT, children=tuple(patterns(n, size // 2) for n in node.children)
            )
        if node.mode == 3:
            record = compact_record(node.record, size)
            if record is not None:
                return Node(PATTERN_PALETTE, record=record)
        return node

    result = pack_frame(
        original.width,
        original.height,
        original.frame_id,
        original.reference_id,
        bool(original.flags & KEYFRAME),
        (original.global_x, original.global_y),
        [patterns(n, 32) for n in roots] if palette_patterns else roots,
        short_index=short_index,
        sparse_children=sparse_children,
        immediate_motion=immediate_motion,
        derived_directory=derived_directory,
        derived_offsets=derived_offsets,
        packed_symbols=packed_symbols,
        two_level_walk=two_level_walk,
        motion_table=motion_table,
        endpoint_table=endpoint_table,
        endpoint_565=endpoint_565,
        selector_table=selector_table,
    )
    # A semantic equality proof independent of pointer arithmetic: every leaf's
    # coordinates, mode, quantizer and record are unchanged (defaults normalized).
    if roots_from_frame(parse_frame(result)) != roots:
        raise AssertionError("index repacking changed leaf semantics")
    return result
