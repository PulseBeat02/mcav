# This file is part of mcav, a media playback library for Java
# Copyright (C) Brandon Li <https://brandonli.me/>
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""Bit-exact v3 reconstruction and the transactional stream receiver (§§2, 8)."""

import numpy as np

from . import format as fmt
from .v3 import Frame, parse_frame


def _signed(value: int, bits: int = 8) -> int:
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


def _prediction(reference: np.ndarray, x: int, y: int, size: int, dx: int, dy: int) -> np.ndarray:
    height, width = reference.shape[:2]
    xs = np.clip(np.arange(size) + x + dx, 0, width - 1)
    ys = np.clip(np.arange(size) + y + dy, 0, height - 1)
    return reference[ys[:, None], xs]


def _grid(body: bytes, size: int) -> np.ndarray:
    packed = np.frombuffer(body[:8], np.uint8)
    nibbles = np.stack((packed & 15, packed >> 4), axis=1).astype(np.int32).ravel()
    nodes = np.where(nibbles >= 8, nibbles - 16, nibbles).reshape(4, 4)
    # Dyadic fractions keep the specification's interpolation and final rounding exact.
    t = np.clip((np.arange(size) + 0.5) * 4 / size - 0.5, 0, 3)
    lower = t.astype(np.int32)
    upper = np.minimum(lower + 1, 3)
    fraction = t - lower
    horizontal = nodes[:, lower] * (1 - fraction) + nodes[:, upper] * fraction
    return horizontal[lower] * (1 - fraction[:, None]) + horizontal[upper] * fraction[:, None]


def _decode(frame: Frame, reference: np.ndarray | None, reference_id: int | None) -> np.ndarray:
    if not frame.keyframe:
        if reference is None or reference_id != frame.reference_id:
            raise ValueError("missing or mismatched reference id")
        if reference.shape != (frame.height, frame.width, 3) or reference.dtype != np.uint8:
            raise ValueError("reference must be a matching height x width x 3 uint8 picture")
    picture = np.empty((frame.height, frame.width, 3), np.uint8)
    for leaf in frame.leaves:
        x, y, size, mode, record = leaf.x, leaf.y, leaf.size, leaf.mode, leaf.record
        if mode == fmt.SKIP:
            block = (np.broadcast_to(np.array(frame.default_color, np.uint8), (size, size, 3))
                     if frame.keyframe else _prediction(reference, x, y, size, 0, 0))
        elif mode == fmt.SOLID:
            block = np.broadcast_to(np.frombuffer(record, np.uint8), (size, size, 3))
        elif mode in (fmt.PALETTE, fmt.PATTERN):
            if mode == fmt.PATTERN:
                axis = np.unpackbits(np.frombuffer(record[7:], np.uint8), bitorder="little")
                selectors = np.broadcast_to(axis[None, :] if record[6] == 0 else axis[:, None], (size, size))
            else:
                selectors = np.unpackbits(np.frombuffer(record[6:], np.uint8), bitorder="little").reshape(size, size)
            colors = np.frombuffer(record[:6], np.uint8).reshape(2, 3)
            block = colors[selectors]
        elif mode == fmt.MOTION:
            block = _prediction(reference, x, y, size, _signed(record[0]), _signed(record[1]))
        else:
            kind, form = record[0] & 15, record[0] >> 4
            dx = dy = 0
            if form == 1:
                dx, dy = _signed(record[1] & 15, 4), _signed(record[1] >> 4, 4)
            elif form == 2:
                dx, dy = _signed(record[1]), _signed(record[2])
            body = record[1 + form:]
            prediction = _prediction(reference, x, y, size, dx, dy).astype(np.float64)
            luma = _signed(body[0]) if kind == fmt.DC else _grid(body, size)
            co, cg = (_signed(body[8]), _signed(body[9])) if kind == fmt.GRID else (0, 0)
            residual = np.stack(np.broadcast_arrays(luma + co - cg, luma + cg, luma - co - cg), axis=-1)
            block = np.clip(np.floor(prediction + (1 << leaf.q) * residual + 0.5), 0, 255).astype(np.uint8)
        shown_width, shown_height = min(size, frame.width - x), min(size, frame.height - y)
        if shown_width > 0 and shown_height > 0:
            picture[y:y + shown_height, x:x + shown_width] = block[:shown_height, :shown_width]
    return picture


def decode(data: bytes, reference: np.ndarray | None = None, reference_id: int | None = None) -> np.ndarray:
    """Decode a validated frame, requiring the exact reference picture for a P frame."""
    return _decode(parse_frame(data), reference, reference_id)


class Decoder:
    """Keep the last committed picture on every error; accept only newer u32 ids."""

    def __init__(self):
        self.reference: np.ndarray | None = None
        self.frame_id: int | None = None

    def accept(self, data: bytes) -> np.ndarray:
        frame = parse_frame(data)
        if self.frame_id is not None and not 0 < (frame.frame_id - self.frame_id) & fmt.ID_MASK < fmt.ID_HALF_RANGE:
            raise ValueError("frame id is not newer under the half-range rule")
        picture = _decode(frame, self.reference, self.frame_id)
        # A caller may edit the returned picture without changing future predictions.
        self.reference = picture.copy()
        self.frame_id = frame.frame_id
        return picture
