"""Focused block candidate evaluation with an exact nonnegative-distortion bound."""

import numpy as np

from .format import (
    MODE_INTRA,
    MODE_PALETTE,
    MODE_RESIDUAL,
    MODE_SKIP,
    MODE_SOLID,
    record_size,
)
from .pixels import distortion, expand_grid, fit_grid, rgb8, to_rgb, to_ycocg


class BlockChoices:
    """Track best closed-loop reconstruction and record for each raster block.

    If the candidate's rate penalty alone is no better than the current cost,
    nonnegative distortion proves it cannot win. Pruning before reconstruction
    removes large amounts of encoder work without a heuristic quality cutoff.
    """

    def __init__(
        self,
        source: np.ndarray,
        lambda_value: float,
        sparse: bool,
        weights=None,
        index_bits: int = 32,
        skip_bits: int | None = None,
    ):
        """`index_bits` is what one descriptor actually costs in the chosen index form.

        The default of 32 is a wide four-byte descriptor, which is what this charged
        unconditionally for several rounds - including while `short_index`, at three bytes,
        was the adopted form. Round 7's derived form makes a descriptor 8 bits plus about 4
        of amortized checkpoint. Keeping 32 as the default means every caller that does not
        opt in reproduces its previous numbers exactly.

        `skip_bits` is charged instead for a skipped block, because a skip is not always a
        descriptor: a skipped *root* is carried by a presence mask bit, while a skipped
        child of a split needs a real descriptor. Defaulting it to the old
        `0 if sparse else index_bits` preserves the previous behaviour too.
        """
        self.source = source
        self.lambda_value = lambda_value
        self.sparse = sparse
        self.weights = weights
        self.index_bits = index_bits
        self.skip_bits = (
            (0 if sparse else index_bits) if skip_bits is None else skip_bits
        )
        self.block_size = source.shape[1]
        self.modes = np.full(len(source), MODE_SOLID, np.uint8)
        self.quantizers = np.zeros(len(source), np.uint8)
        self.records = [b""] * len(source)
        self.costs = np.full(len(source), np.inf)
        self.pixels = np.zeros_like(source)

    def eligible(self, payload_size: int) -> np.ndarray:
        """Return blocks whose current cost exceeds a non-skip candidate's bits."""
        return np.flatnonzero(
            self.costs > self.lambda_value * (payload_size * 8 + self.index_bits)
        )

    def consider(
        self,
        mode: int,
        quantizer: int,
        pixels: np.ndarray,
        payloads: list[bytes],
        payload_size: int,
        indices: np.ndarray | None = None,
    ):
        """Commit strict winners; indices maps a pruned batch to raster blocks."""
        if indices is None:
            indices = np.arange(len(self.source))
        pixels = rgb8(pixels)
        index_bits = self.skip_bits if mode == MODE_SKIP else self.index_bits
        if self.weights is None:
            errors = distortion(self.source[indices], pixels)
        else:
            delta = to_ycocg(self.source[indices].astype(np.float32) - pixels)
            errors = (
                np.sum(
                    delta
                    * delta
                    * np.array([4, 1, 1], np.float32)
                    * self.weights[indices, ..., None],
                    axis=(1, 2, 3),
                )
                / 6
            )
        costs = errors + self.lambda_value * (payload_size * 8 + index_bits)
        winners = np.flatnonzero(costs < self.costs[indices])
        destinations = indices[winners]
        self.costs[destinations] = costs[winners]
        self.modes[destinations] = mode
        self.quantizers[destinations] = quantizer
        self.pixels[destinations] = pixels[winners]
        for winner, destination in zip(winners, destinations, strict=True):
            self.records[destination] = payloads[winner]


def consider_full_grid(
    choices: BlockChoices,
    grid_size: int,
    prediction: np.ndarray | None,
    motion_bytes: list[bytes],
):
    """Evaluate the RGB intra and signed YCoCg residual versions of one grid."""
    payload_size = 3 * grid_size**2
    indices = choices.eligible(payload_size)
    if grid_size > 1 and len(indices):
        grid = rgb8(fit_grid(choices.source[indices].astype(np.float32), grid_size))
        choices.consider(
            MODE_INTRA + grid_size.bit_length() - 1,
            0,
            expand_grid(grid.astype(np.float32), choices.block_size),
            [value.tobytes() for value in grid],
            payload_size,
            indices,
        )
    indices = choices.eligible(payload_size + 2)
    if prediction is None or not len(indices):
        return
    predicted = prediction[indices]
    grid = fit_grid(to_ycocg(choices.source[indices]) - to_ycocg(predicted), grid_size)
    for quantizer_log in (0, 1, 2, 3):
        step = 1 << quantizer_log
        quantized = np.clip(np.floor(grid / step + 0.5), -128, 127).astype(np.int8)
        pixels = predicted + to_rgb(
            expand_grid(quantized.astype(np.float32) * step, choices.block_size)
        )
        payloads = [
            motion_bytes[index] + value.tobytes()
            for index, value in zip(indices, quantized, strict=True)
        ]
        choices.consider(
            MODE_RESIDUAL + grid_size.bit_length() - 1,
            quantizer_log,
            pixels,
            payloads,
            payload_size + 2,
            indices,
        )


def consider_reduced_grid(
    choices: BlockChoices,
    luma_size: int,
    chroma_size: int,
    intra_mode: int,
    prediction: np.ndarray | None,
    motion_bytes: list[bytes],
):
    """Fit separately sampled luma/chroma grids, retaining RGB-grid alternatives."""
    if luma_size > choices.block_size:
        return
    for residual in (False, True):
        if residual and prediction is None:
            continue
        payload_size = luma_size**2 + 2 * chroma_size**2 + (2 if residual else 0)
        indices = choices.eligible(payload_size)
        if not len(indices):
            continue
        color = to_ycocg(choices.source[indices])
        if residual:
            color -= to_ycocg(prediction[indices])
        luma_grid = fit_grid(color[..., :1], luma_size)
        chroma_grid = fit_grid(color[..., 1:], chroma_size)
        for quantizer_log in (0, 1, 2, 3) if residual else (0,):
            step = 1 << quantizer_log
            luma = np.clip(
                np.floor(luma_grid / step + 0.5),
                -128 if residual else 0,
                127 if residual else 255,
            ).astype(np.int8 if residual else np.uint8)
            chroma = np.clip(np.floor(chroma_grid / step + 0.5), -128, 127).astype(
                np.int8
            )
            pixels = to_rgb(
                np.concatenate(
                    (
                        expand_grid(luma.astype(np.float32), choices.block_size),
                        expand_grid(chroma.astype(np.float32), choices.block_size),
                    ),
                    axis=-1,
                )
                * step
            )
            if residual:
                pixels += prediction[indices]
            payloads = [
                (motion_bytes[index] if residual else b"")
                + first.tobytes()
                + second.tobytes()
                for index, first, second in zip(indices, luma, chroma, strict=True)
            ]
            choices.consider(
                intra_mode + int(residual),
                quantizer_log,
                pixels,
                payloads,
                payload_size,
                indices,
            )


def consider_coarse_palette(choices: BlockChoices, mode: int, fit_coarse):
    """Evaluate a subsampled selector plane only where its rate bound can win."""
    try:
        payload_size = record_size(mode, choices.block_size)
    except ValueError:
        return
    indices = choices.eligible(payload_size)
    if not len(indices):
        return
    colors, selectors, pixels = fit_coarse(choices.source[indices])
    packed = np.packbits(selectors.reshape(len(indices), -1), axis=1, bitorder="little")
    payloads = [
        color.tobytes() + selector.tobytes()
        for color, selector in zip(colors, packed, strict=True)
    ]
    choices.consider(mode, 0, pixels, payloads, payload_size, indices)


def consider_palette(choices: BlockChoices, fit_palette):
    """Evaluate endpoint/selector coding only where its rate bound can win."""
    payload_size = 6 + choices.block_size**2 // 8
    indices = choices.eligible(payload_size)
    if not len(indices):
        return
    colors, selectors, pixels = fit_palette(choices.source[indices])
    packed = np.packbits(selectors, axis=1, bitorder="little")
    payloads = [
        color.tobytes() + selector.tobytes()
        for color, selector in zip(colors, packed, strict=True)
    ]
    choices.consider(MODE_PALETTE, 0, pixels, payloads, payload_size, indices)
