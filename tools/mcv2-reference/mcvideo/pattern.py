"""Lossless row/column selector patterns for MCV2 two-color palettes."""

import numpy as np

PATTERN_PALETTE = 18


def selector_word(record, size, indexed_endpoints=False):
    """The orientation byte and axis bits a pattern record carries after its endpoints."""
    head = 1 if indexed_endpoints else 6
    return bytes(record[head : head + 1 + size // 8])


def record_size(size, indexed=False, selectors=False):
    """Bytes a pattern record occupies.

    Six bytes are the two endpoints and the rest is an orientation byte plus one axis bit
    per row or column. Either half can be replaced by a one-byte index into a per-frame
    table, so the length depends on the mode, the block size and two frame flags - all of
    them things a fragment knows before it reads the record, which is what keeps the walk's
    arithmetic intact.
    """
    if size not in (8, 16, 32):
        raise ValueError("invalid palette pattern size")
    ends = 1 if indexed else 6
    rest = 1 if selectors else 1 + size // 8
    return ends + rest


def expand_record(data, offset, size, table=None, words=None):
    """Rebuild the full endpoints-plus-selectors record this pattern stands for.

    The one place every reader goes through - the decoder, the repacker's semantic
    equality proof and the leaf validator - so resolving either index here teaches all
    three at once.
    """
    length = record_size(size, table is not None, words is not None)
    head = 1 if table is not None else 6
    if offset < 0 or offset + length > len(data):
        raise ValueError("invalid palette pattern record")
    if table is None:
        colors = data[offset : offset + 6]
    else:
        which = data[offset]
        if (which + 1) * 6 > len(table):
            raise ValueError("endpoint index outside the table")
        colors = bytes(table[which * 6 : which * 6 + 6])
    if words is None:
        word = data[offset + head : offset + head + 1 + size // 8]
    else:
        entry = 1 + size // 8
        which = data[offset + head]
        if (which + 1) * entry > len(words):
            raise ValueError("selector index outside the table")
        word = bytes(words[which * entry : (which + 1) * entry])
    if word[0] > 1:
        raise ValueError("invalid palette pattern record")
    axis = np.unpackbits(np.frombuffer(word, np.uint8, size // 8, 1), bitorder="little")
    selectors = np.broadcast_to(
        axis[None, :] if word[0] == 0 else axis[:, None], (size, size)
    )
    return colors + np.packbits(selectors, bitorder="little").tobytes()


def index_record(record, which, word=None):
    """The stored form of a pattern record whose tables name its endpoints or selectors."""
    tail = record[6:] if word is None else bytes([word])
    return (record[:6] if which is None else bytes([which])) + tail


def compact_record(record, size):
    if len(record) != 6 + size * size // 8:
        raise ValueError("invalid source palette record")
    selectors = np.unpackbits(
        np.frombuffer(record, np.uint8, offset=6), bitorder="little"
    ).reshape(size, size)
    for kind, axis in ((0, selectors[0]), (1, selectors[:, 0])):
        predicted = axis[None, :] if kind == 0 else axis[:, None]
        if np.all(selectors == predicted):
            return (
                record[:6]
                + bytes([kind])
                + np.packbits(axis, bitorder="little").tobytes()
            )
    return None


def consider_patterns(choices, quantize=False):
    """Charge the short selector form during leaf RDO, including palette fitting.

    `quantize` rounds the fitted endpoints to RGB565 before the selectors are assigned, so
    the RDO sees the true distortion of the coarser endpoints and can reject the pattern in
    favour of another mode. The coarse palette modes deliberately do not take it: they store
    their endpoints inline rather than in the frame's table, so they would pay the error
    without saving a byte.
    """
    from .encoder import palette_candidate

    size = choices.block_size
    length = record_size(size)
    indices = choices.eligible(length)
    if not len(indices):
        return
    colors, selectors, pixels = palette_candidate(choices.source[indices], quantize)
    records, accepted = [], []
    for i, (color, selector) in enumerate(zip(colors, selectors, strict=True)):
        full = color.tobytes() + np.packbits(selector, bitorder="little").tobytes()
        record = compact_record(full, size)
        if record is not None:
            records.append(record)
            accepted.append(i)
    if accepted:
        choices.consider(
            PATTERN_PALETTE, 0, pixels[accepted], records, length, indices[accepted]
        )
