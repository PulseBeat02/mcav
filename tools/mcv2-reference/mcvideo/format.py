"""Little-endian random-access frame contract, independent of encoder decisions.

All offsets count bytes from the start of a frame. A descriptor is one uint32:
31       28 27       24 23                                         0
+----------+-----------+-------------------------------------------+
| Q log2   | mode      | absolute payload offset                   |
+----------+-----------+-------------------------------------------+
Zero is the canonical global-motion skip descriptor. See FORMAT.md.
"""

import struct
from collections import Counter
from dataclasses import dataclass

MAGIC = int.from_bytes(b"MCV1", "little")
VERSION = 1
HEADER = struct.Struct("<12I")
HEADER_BYTES = HEADER.size
MAX_FRAME_BYTES = (1 << 24) - 1
MAX_DIMENSION = 4096
GROUP_SIZE = 32
KEYFRAME = 1
SPARSE = 2
DEFAULT_SOLID = 4
DERIVED_DIRECTORY = 16  # Root directory stores masks plus a sparse checkpoint.
DERIVED_OFFSETS = 32  # Level-ordered one-byte descriptors; every address recovered.
PACKED_SYMBOLS = 64  # Descriptors are indexes into a per-frame (mode, quantizer) table.
TWO_LEVEL_WALK = 128  # Walk checkpoints are a coarse plane plus anchor-relative deltas.
MOTION_TABLE = 256  # Frame carries a table of its commonest motion vectors.
ENDPOINT_TABLE = 512  # Pattern palettes name their endpoints in a per-frame table.
# One flag per block size, rather than one for the whole frame: a record's length has to
# follow from its mode and size alone, and the walk reads flags from a register while a
# count byte costs it a fetch. Whether a size is tabled is therefore said in the flags.
SELECTOR_TABLE_8 = 1024  # Eight-blocks name their selector word in a per-frame table.
SELECTOR_TABLE_16 = 2048  # Sixteen-blocks likewise.
SELECTOR_TABLE_32 = 4096  # Thirty-two-blocks likewise.
SELECTOR_TABLE = SELECTOR_TABLE_8 | SELECTOR_TABLE_16 | SELECTOR_TABLE_32
SELECTOR_TABLES = (SELECTOR_TABLE_8, SELECTOR_TABLE_16, SELECTOR_TABLE_32)
# Endpoint pairs are stored as two little-endian RGB565 colours, four bytes rather than six.
# The quantization happens in the palette fitter, so the stored record already holds values
# that survive the round trip exactly and the repack proof is unaffected.
ENDPOINT_565 = 8192
MODE_SKIP = 0
MODE_MOTION = 1
MODE_SOLID = 2
MODE_PALETTE = 3
MODE_INTRA = 4  # 4..7: 1, 2, 4, 8 grid samples per axis
MODE_RESIDUAL = 8  # 8..11: same grid, signed residual + local motion
COARSE_PALETTE_2 = 21  # Two endpoints, one selector per 2x2 pixel group.
COARSE_PALETTE_4 = 22  # Two endpoints, one selector per 4x4 pixel group.
MODE_INDEXED_MOTION = 23  # One-byte index into the frame's motion table.
MODE_NAMES = (
    "skip",
    "motion",
    "solid",
    "palette",
    "intra1",
    "intra2",
    "intra4",
    "intra8",
    "residual1",
    "residual2",
    "residual4",
    "residual8",
    "intra_y4c1",
    "residual_y4c1",
    "intra_y8c2",
    "residual_y8c2",
)


def is_residual(mode: int) -> bool:
    """Identify temporal grid modes, including reduced-chroma extensions."""
    return 8 <= mode <= 11 or mode in (13, 15)


def reduced_grids(mode: int) -> tuple[int, int]:
    """Return luma/chroma sample-grid widths for modes 12..15."""
    if mode not in (12, 13, 14, 15):
        raise ValueError("not a reduced-chroma mode")
    return (4, 1) if mode < 14 else (8, 2)


def encode_signed(value: int, width: int) -> int:
    """Encode a checked two's-complement field of 1..31 bits."""
    if not 1 <= width <= 31 or not -(1 << (width - 1)) <= value < (1 << (width - 1)):
        raise ValueError("signed field out of range")
    return value & ((1 << width) - 1)


def decode_signed(value: int, width: int) -> int:
    """Sign-extend the low width bits without relying on host signed shifts."""
    if not 1 <= width <= 31:
        raise ValueError("invalid field width")
    value &= (1 << width) - 1
    return (value ^ (1 << (width - 1))) - (1 << (width - 1))


def extract_bits(data: bytes, bit_offset: int, width: int) -> int:
    """Read 0..32 LSB-first bits, including unaligned cross-word fields."""
    if not 0 <= width <= 32 or bit_offset < 0 or bit_offset + width > len(data) * 8:
        raise ValueError("bit field outside buffer")
    start = bit_offset // 8
    count = (bit_offset % 8 + width + 7) // 8
    return (
        int.from_bytes(data[start : start + count], "little") >> (bit_offset % 8)
    ) & ((1 << width) - 1)


def coarse_palette_factor(mode: int) -> int:
    """Selector subsampling factor for the coarse palette modes, else zero."""
    return {COARSE_PALETTE_2: 2, COARSE_PALETTE_4: 4}.get(mode, 0)


def record_size(mode: int, block_size: int) -> int:
    """Return the fixed payload size for a legal mode and partition size."""
    factor = coarse_palette_factor(mode)
    if factor:
        # Two RGB endpoints, then one selector bit per factor x factor group.
        cells = block_size // factor
        if cells < 1 or block_size % factor or cells * cells % 8:
            raise ValueError("illegal coarse palette geometry")
        return 6 + cells * cells // 8
    if mode == MODE_SKIP:
        return 0
    if mode == MODE_MOTION:
        return 2
    if mode == MODE_SOLID:
        return 3
    if mode == MODE_PALETTE:
        return 6 + block_size * block_size // 8
    if MODE_INTRA <= mode < MODE_RESIDUAL:
        grid_size = 1 << (mode - MODE_INTRA)
        if grid_size <= block_size:
            return 3 * grid_size * grid_size
    if MODE_RESIDUAL <= mode < 12:
        grid_size = 1 << (mode - MODE_RESIDUAL)
        if grid_size <= block_size:
            return 2 + 3 * grid_size * grid_size
    if 12 <= mode < 16:
        luma_size, chroma_size = reduced_grids(mode)
        if luma_size <= block_size:
            return luma_size**2 + 2 * chroma_size**2 + (2 if is_residual(mode) else 0)
    raise ValueError("illegal mode for block size")


def descriptor(mode: int, quantizer_log: int, offset: int) -> int:
    """Pack only representable fields; payload bounds are checked by parse_frame."""
    if (
        not 0 <= mode < 16
        or not 0 <= quantizer_log <= 7
        or not 0 <= offset <= MAX_FRAME_BYTES
    ):
        raise ValueError("invalid block descriptor")
    if mode == MODE_SKIP and (quantizer_log or offset):
        raise ValueError("noncanonical skip")
    if not is_residual(mode) and quantizer_log:
        raise ValueError("quantizer applies only to residuals")
    return offset | (mode << 24) | (quantizer_log << 28)


@dataclass(frozen=True)
class Frame:
    """Validated frame; descriptors are expanded for CPU convenience only."""

    data: bytes
    width: int
    height: int
    block_size: int
    frame_id: int
    reference_id: int
    flags: int
    global_x: int  # half-pixel units, current position -> reference position
    global_y: int
    descriptors: tuple[int, ...]
    payload_start: int
    default_color: tuple[int, int, int]


def pack_frame(
    width: int,
    height: int,
    block_size: int,
    frame_id: int,
    reference_id: int,
    keyframe: bool,
    global_motion: tuple[int, int],
    records: list[tuple[int, int, bytes]],
    sparse: bool = True,
    default_solid: bool = True,
) -> bytes:
    """Serialize raster blocks; automatically choose the smaller index form.

    A sparse group holds a mask and descriptor-table offset. Popcount of the
    preceding mask bits gives direct access in bounded work, never a chain.
    """
    if (
        block_size not in (4, 8, 16, 32)
        or not 1 <= width <= MAX_DIMENSION
        or not 1 <= height <= MAX_DIMENSION
    ):
        raise ValueError("invalid dimensions or block size")
    if not 0 <= frame_id <= 0xFFFFFFFF or not 0 <= reference_id <= 0xFFFFFFFF:
        raise ValueError("frame IDs must be uint32")
    count = ((width + block_size - 1) // block_size) * (
        (height + block_size - 1) // block_size
    )
    if len(records) != count or block_size not in (4, 8, 16, 32):
        raise ValueError("invalid block count or size")
    # Validate input before lossless rewriting so an invalid quantizer cannot be
    # hidden by replacing its record with an implicit default-color block.
    for mode, quantizer_log, record in records:
        descriptor(mode, quantizer_log, 0)
        if len(record) != record_size(mode, block_size):
            raise ValueError("wrong input payload size")
    default_color = 0
    use_default = False
    if keyframe and sparse and default_solid:
        if any(mode == MODE_SKIP for mode, _, _ in records):
            raise ValueError("encoder must supply explicit keyframe blocks")
        solid_colors = Counter(
            record for mode, _, record in records if mode == MODE_SOLID
        )
        if solid_colors:
            # Lossless syntax optimization: the most frequent solid color becomes
            # the implicit block. The reserved header word costs no extra bytes.
            color = solid_colors.most_common(1)[0][0]
            if len(color) != 3:
                raise ValueError("invalid default color")
            default_color = int.from_bytes(color, "little")
            records = [
                (MODE_SKIP, 0, b"")
                if mode == MODE_SOLID and record == color
                else (mode, quantizer, record)
                for mode, quantizer, record in records
            ]
            use_default = True
    active = sum(mode != MODE_SKIP for mode, _, _ in records)
    groups = (count + GROUP_SIZE - 1) // GROUP_SIZE
    use_sparse = sparse and groups * 8 + active * 4 < count * 4
    index_bytes = groups * 8 + active * 4 if use_sparse else count * 4
    payload_start = HEADER_BYTES + index_bytes
    payload = bytearray()
    words = []
    for mode, quantizer_log, record in records:
        if len(record) != record_size(mode, block_size):
            raise ValueError("wrong payload size")
        words.append(
            descriptor(mode, quantizer_log, payload_start + len(payload) if mode else 0)
        )
        payload.extend(record)
    index = bytearray()
    if use_sparse:
        packed = bytearray()
        for start in range(0, count, GROUP_SIZE):
            mask = sum(
                (1 << bit)
                for bit, word in enumerate(words[start : start + GROUP_SIZE])
                if word
            )
            index.extend(
                struct.pack("<II", mask, HEADER_BYTES + groups * 8 + len(packed))
            )
            packed.extend(
                b"".join(
                    struct.pack("<I", word)
                    for word in words[start : start + GROUP_SIZE]
                    if word
                )
            )
        index.extend(packed)
    else:
        index.extend(struct.pack(f"<{count}I", *words))
    flags = (
        int(keyframe)
        | (SPARSE if use_sparse else 0)
        | (DEFAULT_SOLID if use_default else 0)
    )
    motion = encode_signed(global_motion[0], 16) | (
        encode_signed(global_motion[1], 16) << 16
    )
    total = payload_start + len(payload)
    config = VERSION | (block_size.bit_length() - 1) << 8 | flags << 16
    header = HEADER.pack(
        MAGIC,
        config,
        width | height << 16,
        frame_id,
        reference_id,
        motion,
        count,
        payload_start,
        total,
        default_color,
        0,
        0,
    )
    result = header + index + payload
    parse_frame(result)
    return result


def parse_frame(data: bytes) -> Frame:
    """Validate the entire frame before reference state may change.

    Reject aliases, holes, trailing bytes and reserved bits, keeping one canonical
    layout. Bounds are checked before iteration/allocation based on metadata.
    """
    if data[:4] == b"MCV2":
        from .v2 import parse_frame as parse_v2

        return parse_v2(data)
    if not HEADER_BYTES <= len(data) <= MAX_FRAME_BYTES:
        raise ValueError("invalid frame length")
    (
        magic,
        config,
        dimensions,
        frame_id,
        reference_id,
        motion,
        count,
        payload_start,
        total,
        default_color,
        *reserved,
    ) = HEADER.unpack_from(data)
    block_log = (config >> 8) & 255
    flags = config >> 16
    width, height = dimensions & 65535, dimensions >> 16
    if (
        magic != MAGIC
        or config & 255 != VERSION
        or block_log not in (2, 3, 4, 5)
        or flags & ~7
        or any(reserved)
    ):
        raise ValueError("unsupported frame header")
    if (
        not 1 <= width <= MAX_DIMENSION
        or not 1 <= height <= MAX_DIMENSION
        or total != len(data)
    ):
        raise ValueError("invalid dimensions or total")
    block_size = 1 << block_log
    expected = ((width + block_size - 1) // block_size) * (
        (height + block_size - 1) // block_size
    )
    if count != expected or not HEADER_BYTES <= payload_start <= total:
        raise ValueError("invalid block table")
    if flags & KEYFRAME and (reference_id != frame_id or motion):
        raise ValueError("keyframe has temporal metadata")
    if (
        (flags & DEFAULT_SOLID and not flags & KEYFRAME)
        or default_color > 0xFFFFFF
        or (not flags & DEFAULT_SOLID and default_color)
    ):
        raise ValueError("invalid default solid metadata")
    if not flags & KEYFRAME and reference_id == frame_id:
        raise ValueError("self reference")
    words = []
    if flags & SPARSE:
        groups = (count + GROUP_SIZE - 1) // GROUP_SIZE
        cursor = HEADER_BYTES + groups * 8
        if cursor > payload_start:
            raise ValueError("truncated directory")
        for group in range(groups):
            mask, offset = struct.unpack_from("<II", data, HEADER_BYTES + group * 8)
            valid_bits = min(GROUP_SIZE, count - group * GROUP_SIZE)
            if (
                offset != cursor
                or mask >> valid_bits
                or cursor + mask.bit_count() * 4 > payload_start
            ):
                raise ValueError("invalid sparse index")
            for bit in range(valid_bits):
                word = 0
                if mask & (1 << bit):
                    word = struct.unpack_from("<I", data, cursor)[0]
                    cursor += 4
                    if word == 0:
                        raise ValueError("explicit sparse skip")
                words.append(word)
        if cursor != payload_start:
            raise ValueError("noncanonical index size")
    else:
        if HEADER_BYTES + count * 4 != payload_start:
            raise ValueError("wrong dense index size")
        words = list(struct.unpack_from(f"<{count}I", data, HEADER_BYTES))
    cursor = payload_start
    for word in words:
        mode, quantizer_log, offset = (
            (word >> 24) & 15,
            word >> 28,
            word & MAX_FRAME_BYTES,
        )
        descriptor(mode, quantizer_log, offset)
        size = record_size(mode, block_size)
        if flags & KEYFRAME and (
            mode == MODE_MOTION
            or is_residual(mode)
            or (mode == MODE_SKIP and not flags & DEFAULT_SOLID)
        ):
            raise ValueError("temporal block in keyframe")
        if mode and (offset != cursor or size > total - offset):
            raise ValueError("invalid payload address")
        cursor += size
    if cursor != total:
        raise ValueError("noncanonical payload size")
    return Frame(
        data,
        width,
        height,
        block_size,
        frame_id,
        reference_id,
        flags,
        decode_signed(motion, 16),
        decode_signed(motion >> 16, 16),
        tuple(words),
        payload_start,
        (default_color & 255, (default_color >> 8) & 255, default_color >> 16),
    )
