"""Transactional CPU reference decoder. This is a testing tool, not a client mod."""

import numpy as np

from .format import (
    DEFAULT_SOLID,
    KEYFRAME,
    MAX_FRAME_BYTES,
    MODE_INTRA,
    MODE_MOTION,
    MODE_PALETTE,
    MODE_RESIDUAL,
    MODE_SOLID,
    is_residual,
    parse_frame,
    reduced_grids,
)
from .pixels import expand_grid, prediction, rgb8, to_rgb, unblock


def decode(
    data: bytes, reference: np.ndarray | None = None, reference_id: int | None = None
) -> np.ndarray:
    """Decode a complete frame, rejecting invalid metadata before any work.

    P frames require an exact ID and dimensions. A caller should retain its last
    displayed frame on ValueError; this pure function never changes reference state.
    """
    frame = parse_frame(data)
    if data[:4] == b"MCV2":
        from .v2 import decode_frame

        return decode_frame(frame, reference, reference_id)
    return decode_legacy(frame, reference, reference_id)


def decode_legacy(frame, reference=None, reference_id=None):
    """Reconstruct validated uniform leaves; also used by the mixed-size oracle."""
    data = frame.data
    if not frame.flags & KEYFRAME:
        if (
            reference_id != frame.reference_id
            or reference is None
            or reference.shape != (frame.height, frame.width, 3)
            or reference.dtype != np.uint8
        ):
            raise ValueError("reference frame mismatch")
    count, block_size = len(frame.descriptors), frame.block_size
    output = np.zeros((count, block_size, block_size, 3), np.float32)
    words = np.array(frame.descriptors, np.uint32)
    modes, quantizers = (words >> 24) & 15, words >> 28
    offsets = words & MAX_FRAME_BYTES
    residual_modes = np.array([is_residual(int(mode)) for mode in modes])
    if frame.flags & DEFAULT_SOLID:
        output[modes == 0] = frame.default_color
    motion = np.tile([frame.global_x, frame.global_y], (count, 1)).astype(np.int32)
    for index in np.flatnonzero((modes == MODE_MOTION) | residual_modes):
        offset = int(offsets[index])
        motion[index] += np.frombuffer(data, np.int8, 2, offset).astype(np.int32)
    if not frame.flags & KEYFRAME:
        predicted = prediction(reference, block_size, motion)
        temporal = (modes < MODE_SOLID) | residual_modes
        output[temporal] = predicted[temporal]
    for mode in range(MODE_SOLID, 16):
        indices = np.flatnonzero(modes == mode)
        if not len(indices):
            continue
        if mode == MODE_SOLID:
            for index in indices:
                output[index] = np.frombuffer(data, np.uint8, 3, int(offsets[index]))
        elif mode == MODE_PALETTE:
            for index in indices:
                offset = int(offsets[index])
                colors = np.frombuffer(data, np.uint8, 6, offset).reshape(2, 3)
                selectors = np.unpackbits(
                    np.frombuffer(
                        data, np.uint8, block_size * block_size // 8, offset + 6
                    ),
                    bitorder="little",
                ).reshape(block_size, block_size)
                output[index] = colors[selectors]
        elif mode >= 12:
            residual = is_residual(mode)
            luma_size, chroma_size = reduced_grids(mode)
            luma_grids, chroma_grids = [], []
            for index in indices:
                offset = int(offsets[index]) + (2 if residual else 0)
                luma_grids.append(
                    np.frombuffer(
                        data, np.int8 if residual else np.uint8, luma_size**2, offset
                    ).reshape(luma_size, luma_size, 1)
                )
                chroma_grids.append(
                    np.frombuffer(
                        data, np.int8, 2 * chroma_size**2, offset + luma_size**2
                    ).reshape(chroma_size, chroma_size, 2)
                )
            color = np.concatenate(
                (
                    expand_grid(np.array(luma_grids, np.float32), block_size),
                    expand_grid(np.array(chroma_grids, np.float32), block_size),
                ),
                axis=-1,
            )
            if residual:
                output[indices] += to_rgb(
                    color * (1 << quantizers[indices])[:, None, None, None]
                )
            else:
                output[indices] = to_rgb(color)
        else:
            residual = mode >= MODE_RESIDUAL
            grid_size = 1 << (mode - (MODE_RESIDUAL if residual else MODE_INTRA))
            grids = np.stack(
                [
                    np.frombuffer(
                        data,
                        np.int8 if residual else np.uint8,
                        3 * grid_size * grid_size,
                        int(offsets[index]) + (2 if residual else 0),
                    ).reshape(grid_size, grid_size, 3)
                    for index in indices
                ]
            ).astype(np.float32)
            if residual:
                grids *= (1 << quantizers[indices])[:, None, None, None]
                output[indices] += to_rgb(expand_grid(grids, block_size))
            else:
                output[indices] = expand_grid(grids, block_size)
    return rgb8(unblock(output, frame.width, frame.height))


class Decoder:
    """Reference state machine: freeze on loss, resume on a newer keyframe.

    Unsigned frame ordering uses the half-range rule; no jump of 2^31 frames is
    allowed. Stream changes require a fresh Decoder and matching Assembler.
    """

    def __init__(self):
        self.reference: np.ndarray | None = None
        self.frame_id: int | None = None

    def accept(self, data: bytes) -> np.ndarray:
        """Atomically commit a newer decoded frame or raise without mutation."""
        frame = parse_frame(data)
        if (
            self.frame_id is not None
            and not 0 < ((frame.frame_id - self.frame_id) & 0xFFFFFFFF) < 0x80000000
        ):
            raise ValueError("stale or ambiguous frame number")
        result = decode(data, self.reference, self.frame_id)
        self.reference, self.frame_id = result, frame.frame_id
        return result
