"""Bounded compact temporal residual classes for MCV2 (mode 17)."""

import numpy as np

from .pixels import expand_grid, fit_grid, to_rgb, to_ycocg

COMPACT = 17
# Body lengths exclude the control byte and optional motion bytes.
BODY_BYTES = (1, 6, 10, 8, 18, 4, 5, 4, 5)
CLASS_NAMES = (
    "DC_Y",
    "GRID2_YC",
    "GRID4_N4_YC",
    "GRID4_N4_Y",
    "GRID4_YC",
    "VQ64",
    "PQ64",
    "GAIN_BIAS",
    "LOW2",
)


def motion_prefix(vector):
    """Global=0 bytes, signed nibble pair=1 byte, signed byte escape=2 bytes."""
    x, y = map(int, vector)
    if x == y == 0:
        return 0, b""
    if -8 <= x <= 7 and -8 <= y <= 7:
        return 1, bytes([(x & 15) | ((y & 15) << 4)])
    if not -128 <= x <= 127 or not -128 <= y <= 127:
        raise ValueError("motion out of range")
    return 2, bytes([x & 255, y & 255])


def parse_record(data, offset, q):
    if not 0 <= offset < len(data) or not 0 <= q <= 7:
        raise ValueError("truncated compact control")
    control = data[offset]
    kind = control & 15
    form = control >> 4
    if kind >= len(BODY_BYTES) or form > 2 or (kind == 7 and q):
        raise ValueError("invalid compact class/control")
    length = 1 + form + BODY_BYTES[kind]
    if offset + length > len(data):
        raise ValueError("truncated compact record")
    delta = (0, 0)
    if form == 1:
        value = data[offset + 1]
        delta = (((value & 15) ^ 8) - 8, ((value >> 4) ^ 8) - 8)
    elif form == 2:
        delta = tuple(np.frombuffer(data, np.int8, 2, offset + 1).astype(int))
    body = data[offset + 1 + form : offset + length]
    if kind == 5 and body[-1] >= 64:
        raise ValueError("invalid VQ index")
    if kind == 6 and body[-1] & 0xF0:
        raise ValueError("noncanonical PQ padding")
    return kind, delta, body, length


def decode_body(kind, body, q, predicted, book=None):
    """Vectorized normative reconstruction, all interpolation is dyadic."""
    size = predicted.shape[1]
    count = len(predicted)
    step = 1 << q
    values = (
        np.frombuffer(body, np.int8).reshape(count, BODY_BYTES[kind]).astype(np.float32)
    )
    if kind == 0:
        color = np.zeros((count, 1, 1, 3), np.float32)
        color[:, 0, 0, 0] = values[:, 0] * step
        return predicted + to_rgb(color)
    if kind in (1, 2, 3, 4, 5, 6):
        grid_size = 2 if kind == 1 else 4
        if kind in (2, 3):
            raw = values[:, :8].astype(np.uint8)
            nodes = np.stack(
                ((raw & 15).astype(int), (raw >> 4).astype(int)), axis=-1
            ).reshape(count, 16)
            luma = ((nodes ^ 8) - 8).astype(np.float32).reshape(count, 4, 4, 1)
            chroma = values[:, 8:10] if kind == 2 else np.zeros((count, 2), np.float32)
        elif kind in (5, 6):
            if book is None:
                from .codebooks import load_books

                book = load_books()
            raw = values.astype(np.int8).view(np.uint8)
            if kind == 5:
                luma = book["vq"][raw[:, 3]].reshape(count, 4, 4, 1).astype(np.float32)
            else:
                ids = raw[:, 3].astype(int) + (raw[:, 4].astype(int) << 8)
                luma = np.concatenate(
                    (
                        book["pq"][0, ids & 63].reshape(count, 4, 2, 1),
                        book["pq"][1, ids >> 6].reshape(count, 4, 2, 1),
                    ),
                    axis=2,
                ).astype(np.float32)
            luma += values[:, 0, None, None, None]
            chroma = values[:, 1:3]
        else:
            luma = values[:, : grid_size**2].reshape(count, grid_size, grid_size, 1)
            chroma = values[:, grid_size**2 :]
        color = (
            np.concatenate(
                (
                    expand_grid(luma, size),
                    np.broadcast_to(chroma[:, None, None], (count, size, size, 2)),
                ),
                axis=-1,
            )
            * step
        )
        return predicted + to_rgb(color)
    if kind == 7:
        return predicted * (1 + values[:, 0, None, None, None] / 64) + to_rgb(
            values[:, None, None, 1:]
        )
    if kind == 8:
        axis = (np.arange(size, dtype=np.float32) + 0.5) / size * 2 - 1
        y = (
            values[:, 0, None, None]
            + values[:, 1, None, None] * axis[None, None, :]
            + values[:, 2, None, None] * axis[None, :, None]
        )
        color = (
            np.concatenate(
                (
                    y[..., None],
                    np.broadcast_to(values[:, None, None, 3:], (count, size, size, 2)),
                ),
                axis=-1,
            )
            * step
        )
        return predicted + to_rgb(color)
    raise ValueError("unknown compact class")


def consider_compact(choices, predicted, vectors, classes):
    """Fit classes independently; RDO charges each actual motion prefix and body."""
    if predicted is None:
        return
    prefixes = [motion_prefix(v) for v in vectors]
    color = to_ycocg(choices.source) - to_ycocg(predicted)
    for kind in classes:
        # Group by record length so the existing exact bound remains valid.
        for form in (0, 1, 2):
            length = 1 + form + BODY_BYTES[kind]
            eligible = set(choices.eligible(length).tolist())
            indices = np.array(
                [i for i, p in enumerate(prefixes) if p[0] == form and i in eligible],
                int,
            )
            if not len(indices):
                continue
            target = color[indices]
            pred = predicted[indices]
            size = choices.block_size
            if kind == 0:
                fitted = target.mean(axis=(1, 2))[:, :1]
            elif kind in (1, 2, 3, 4, 5, 6):
                grid_size = 2 if kind == 1 else 4
                grid = fit_grid(target[..., :1], grid_size).reshape(len(indices), -1)
                dc = target.mean(axis=(1, 2))
                fitted = np.concatenate((grid, dc[:, 1:]), axis=1)
            elif kind == 7:
                src = choices.source[indices].astype(np.float32)
                pc = pred - pred.mean(axis=(1, 2), keepdims=True)
                sc = src - src.mean(axis=(1, 2), keepdims=True)
                gain = np.sum(pc * sc, axis=(1, 2, 3)) / np.maximum(
                    np.sum(pc * pc, axis=(1, 2, 3)), 1
                )
                gain = np.clip(np.floor((gain - 1) * 64 + 0.5), -32, 32)
                bias = to_ycocg(src - pred * (1 + gain[:, None, None, None] / 64)).mean(
                    axis=(1, 2)
                )
                fitted = np.concatenate((gain[:, None], bias), axis=1)
            else:
                axis = (np.arange(size, dtype=np.float32) + 0.5) / size * 2 - 1
                y = target[..., 0]
                fitted = np.column_stack(
                    (
                        y.mean(axis=(1, 2)),
                        (y * axis[None, None, :]).mean(axis=(1, 2)) / np.mean(axis**2),
                        (y * axis[None, :, None]).mean(axis=(1, 2)) / np.mean(axis**2),
                        target[..., 1:].mean(axis=(1, 2)),
                    )
                )
            for q in (0,) if kind == 7 else (0, 1, 2, 3, 4):
                quant = np.clip(np.floor(fitted / (1 << q) + 0.5), -128, 127).astype(
                    np.int8
                )
                if kind in (2, 3):
                    y = (
                        np.clip(np.floor(grid / (1 << q) + 0.5), -8, 7)
                        .astype(np.int8)
                        .view(np.uint8)
                        & 15
                    )
                    packed = y[:, ::2] | y[:, 1::2] << 4
                    body = (
                        np.concatenate((packed, quant[:, -2:].view(np.uint8)), axis=1)
                        if kind == 2
                        else packed
                    )
                elif kind in (5, 6):
                    from .codebooks import load_books, nearest

                    books = load_books()
                    mean = np.clip(np.floor(dc / (1 << q) + 0.5), -128, 127).astype(
                        np.int8
                    )
                    shapes = grid / (1 << q) - mean[:, 0, None]
                    if kind == 5:
                        ids = nearest(shapes, books["vq"]).astype(np.uint8)[:, None]
                    else:
                        shapes = shapes.reshape(-1, 4, 4)
                        a = nearest(shapes[:, :, :2].reshape(-1, 8), books["pq"][0])
                        b = nearest(shapes[:, :, 2:].reshape(-1, 8), books["pq"][1])
                        ids = np.column_stack(((a | (b << 6)) & 255, b >> 2)).astype(
                            np.uint8
                        )
                    body = np.concatenate((mean.view(np.uint8), ids), axis=1)
                else:
                    body = quant.view(np.uint8)
                raw = body.tobytes()
                pixels = decode_body(kind, raw, q, pred)
                payloads = [
                    bytes([kind | form << 4]) + prefixes[i][1] + b.tobytes()
                    for i, b in zip(indices, body, strict=True)
                ]
                choices.consider(COMPACT, q, pixels, payloads, length, indices)
