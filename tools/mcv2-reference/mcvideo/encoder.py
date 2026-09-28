"""RDO server encoder: spend search work to keep the shader bounded and simple."""

import time
from dataclasses import dataclass, replace

import numpy as np

from .decoder import decode
from .format import (
    COARSE_PALETTE_2,
    COARSE_PALETTE_4,
    MAX_DIMENSION,
    MODE_MOTION,
    MODE_SKIP,
    MODE_SOLID,
    pack_frame,
    parse_frame,
)
from .pixels import (
    blocks,
    distortion,
    prediction,
    rgb8,
    to_ycocg,
    unblock,
)
from .rdo import (
    BlockChoices,
    consider_coarse_palette,
    consider_full_grid,
    consider_palette,
    consider_reduced_grid,
)


@dataclass(frozen=True)
class Settings:
    """Encoder-only choices; lambda is weighted squared error per logical bit."""

    block_size: int = 16
    lambda_value: float = 100.0
    motion_range: int = 16  # local pixels around frame-global predictor, <=63
    half_pixel: bool = False
    global_motion: bool = True
    compare_global: bool = False
    palette: bool = True
    coarse_palette: tuple[int, ...] = ()  # selector subsampling factors to try
    # Leaky-bucket rate control over the charged map wire rate. Zero disables it and
    # the encoder keeps its sequence-average behaviour.
    vbv_bitrate: float = 0.0  # target map bits per second, for the record
    vbv_fps: float = 0.0  # frame rate the drain is computed at; must match the source
    vbv_buffer_bits: float = 0.0  # bucket size in bits
    vbv_initial_fill: float = 0.5  # starting and steady-state target fullness
    vbv_strength: float = 2.0  # proportional log-lambda gain on fullness
    vbv_integral: float = 0.25  # integral gain on the per-frame rate error
    reduced_chroma: bool = True
    sparse: bool = True
    default_solid: bool = True
    grids: tuple[int, ...] = (1, 2, 4, 8)
    key_interval: int = 60
    scene_threshold: float = 45.0  # mean absolute luma change after global prediction

    def __post_init__(self):
        if (
            self.block_size not in (4, 8, 16, 32)
            or self.lambda_value < 0
            or not np.isfinite(self.lambda_value)
        ):
            raise ValueError("invalid block size or lambda")
        if (
            not 0 <= self.motion_range <= 63
            or self.key_interval < 1
            or self.scene_threshold <= 0
            or not np.isfinite(self.scene_threshold)
        ):
            raise ValueError("invalid temporal settings")
        if not self.grids or any(
            grid not in (1, 2, 4, 8) or grid > self.block_size for grid in self.grids
        ):
            raise ValueError("invalid grid classes")
        if any(factor not in (2, 4) for factor in self.coarse_palette) or len(
            set(self.coarse_palette)
        ) != len(self.coarse_palette):
            raise ValueError("invalid coarse palette factors")
        if (
            self.vbv_bitrate < 0
            or self.vbv_buffer_bits < 0
            or self.vbv_strength < 0
            or self.vbv_integral < 0
        ):
            raise ValueError("invalid rate control settings")
        if bool(self.vbv_bitrate) != bool(self.vbv_buffer_bits):
            raise ValueError("rate control needs both a bitrate and a buffer size")
        if bool(self.vbv_bitrate) != bool(self.vbv_fps):
            # The encoder cannot see the source frame rate, so the drain per frame is
            # stated explicitly rather than inferred and silently wrong.
            raise ValueError("rate control needs the frame rate its drain assumes")
        if not 0 < self.vbv_initial_fill < 1 and self.vbv_bitrate:
            raise ValueError("initial buffer fill must lie strictly inside the bucket")


def estimate_global(
    source: np.ndarray, reference: np.ndarray, max_range: int = 128
) -> tuple[int, int]:
    """Phase correlation supplies a frame-global integer-pixel predictor.

    Subsample at four-pixel spacing for bounded encoder cost, then refine a 7x7
    neighborhood with sparse image samples. Return half-pixel units. Wrapped FFT
    coordinates are converted to signed displacement before range limiting.
    """
    luma, previous = to_ycocg(source)[::4, ::4, 0], to_ycocg(reference)[::4, ::4, 0]
    spectrum = np.fft.rfft2(previous) * np.conj(np.fft.rfft2(luma))
    correlation = np.fft.irfft2(
        spectrum / np.maximum(np.abs(spectrum), 1e-8), s=luma.shape
    )
    peak_y, peak_x = np.unravel_index(np.argmax(correlation), correlation.shape)
    coarse_x = (
        int(peak_x if peak_x <= luma.shape[1] // 2 else peak_x - luma.shape[1]) * 4
    )
    coarse_y = (
        int(peak_y if peak_y <= luma.shape[0] // 2 else peak_y - luma.shape[0]) * 4
    )
    coarse_x, coarse_y = np.clip([coarse_x, coarse_y], -max_range, max_range)
    sample_y, sample_x = np.mgrid[0 : source.shape[0] : 12, 0 : source.shape[1] : 12]
    target = source[sample_y, sample_x].astype(np.float32)
    best, vector = float("inf"), (0, 0)
    candidates = [(0, 0)] + [
        (int(coarse_x + dx), int(coarse_y + dy))
        for dy in range(-3, 4)
        for dx in range(-3, 4)
    ]
    for dx, dy in candidates:
        if abs(dx) > max_range or abs(dy) > max_range:
            continue
        candidate = reference[
            np.clip(sample_y + dy, 0, source.shape[0] - 1),
            np.clip(sample_x + dx, 0, source.shape[1] - 1),
        ]
        error = float(np.mean(np.abs(candidate.astype(np.float32) - target)))
        if error < best:
            best, vector = error, (dx * 2, dy * 2)
    return vector


def local_motion(
    source_blocks: np.ndarray,
    reference: np.ndarray,
    global_motion: tuple[int, int],
    settings: Settings,
) -> np.ndarray:
    """Hierarchical diamond search on 16 stratified samples per block.

    Final RDO evaluates all pixels. This search is deliberately an approximation,
    not exhaustive or AV1-quality. Absolute vectors never depend on neighbors.
    """
    count, block_size = source_blocks.shape[:2]
    columns = (reference.shape[1] + block_size - 1) // block_size
    indices = np.arange(count)
    sample = np.minimum(
        np.arange(4) * block_size // 4 + block_size // 8, block_size - 1
    )
    position_x = (indices % columns)[:, None, None] * block_size + sample[None, None, :]
    position_y = (indices // columns)[:, None, None] * block_size + sample[
        None, :, None
    ]
    target = source_blocks[:, sample[:, None], sample[None, :]].astype(np.float32)
    vectors = np.tile(global_motion, (count, 1)).astype(np.int32)

    def cost(candidate: np.ndarray) -> np.ndarray:
        """Sample a candidate field, including exact half-pixel interpolation."""
        xx = np.clip(
            position_x + candidate[:, 0, None, None] * 0.5, 0, reference.shape[1] - 1
        )
        yy = np.clip(
            position_y + candidate[:, 1, None, None] * 0.5, 0, reference.shape[0] - 1
        )
        x0, y0 = xx.astype(int), yy.astype(int)
        if np.all(np.remainder(candidate, 2) == 0):
            # Keep float64 mean arithmetic identical to the bilinear path.
            candidate_pixels = reference[y0, x0].astype(np.float64)
            return np.mean(np.abs(candidate_pixels - target), axis=(1, 2, 3))
        x1, y1 = (
            np.minimum(x0 + 1, reference.shape[1] - 1),
            np.minimum(y0 + 1, reference.shape[0] - 1),
        )
        fx, fy = (xx - x0)[..., None], (yy - y0)[..., None]
        candidate_pixels = (reference[y0, x0] * (1 - fx) + reference[y0, x1] * fx) * (
            1 - fy
        ) + (reference[y1, x0] * (1 - fx) + reference[y1, x1] * fx) * fy
        return np.mean(np.abs(candidate_pixels - target), axis=(1, 2, 3))

    best = cost(vectors)
    step = 1 << (max(1, settings.motion_range).bit_length() - 1)
    steps = []
    if settings.motion_range:
        while step:
            steps.append(step * 2)
            step //= 2
        if settings.half_pixel:
            steps.append(1)
    for step in steps:
        center = vectors.copy()
        for dx, dy in (
            (-step, 0),
            (step, 0),
            (0, -step),
            (0, step),
            (-step, -step),
            (step, step),
            (-step, step),
            (step, -step),
        ):
            candidate = center + [dx, dy]
            candidate = np.clip(
                candidate,
                np.array(global_motion) - settings.motion_range * 2,
                np.array(global_motion) + settings.motion_range * 2,
            )
            errors = cost(candidate)
            wins = errors < best
            best[wins], vectors[wins] = errors[wins], candidate[wins]
    return vectors


def quantize565(colors: np.ndarray) -> np.ndarray:
    """Round to RGB565 and expand back, exactly as a decoder reading the stored pair would.

    The encoder must see precisely what the decoder will reconstruct, so this is the same
    arithmetic on both sides: five bits of red and blue and six of green, expanded by
    replicating the high bits into the low ones.
    """
    c = colors.astype(np.uint16)
    r, g, b = c[..., 0] >> 3, c[..., 1] >> 2, c[..., 2] >> 3
    return np.stack(
        ((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)), axis=-1
    ).astype(np.uint8)


def palette_candidate(
    source: np.ndarray, quantize: bool = False
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Fit two RGB endpoints and binary selectors with four Lloyd iterations.

    Luma extrema seed the fit. Equal-luma seeds can coincide; retaining the empty
    cluster's endpoint lets later Lloyd iterations separate a two-color block
    after the occupied cluster moves to its mean. Ties remain deterministic.
    """
    count, size = source.shape[:2]
    flat = source.reshape(count, size * size, 3).astype(np.float32)
    luma = to_ycocg(flat)[..., 0]
    colors = np.stack(
        (
            flat[np.arange(count), luma.argmin(axis=1)],
            flat[np.arange(count), luma.argmax(axis=1)],
        ),
        axis=1,
    )
    for _ in range(4):
        errors = np.sum((flat[:, :, None] - colors[:, None]) ** 2, axis=-1)
        selectors = np.argmin(errors, axis=-1)
        for index in range(2):
            mask = selectors == index
            weights = mask.sum(axis=1)
            means = np.sum(flat * mask[..., None], axis=1) / np.maximum(
                weights[:, None], 1
            )
            colors[weights > 0, index] = means[weights > 0]
    colors = rgb8(colors)
    if quantize:
        # Before the final assignment, so the selectors and the reconstruction below are
        # both derived from the endpoints the decoder will actually see. Quantizing after
        # would leave the fit optimal for endpoints that are not stored.
        colors = quantize565(colors)
    selectors = np.argmin(
        np.sum((flat[:, :, None] - colors[:, None]) ** 2, axis=-1), axis=-1
    ).astype(np.uint8)
    reconstructed = colors[np.arange(count)[:, None], selectors].reshape(source.shape)
    return colors, selectors, reconstructed


def coarse_palette_candidate(source: np.ndarray, factor: int):
    """Fit the two endpoints on factor x factor group means, one selector each.

    The encoder sees exactly what the decoder reconstructs: every pixel of a
    group takes its group's endpoint, so the fit is run on the group means
    rather than on full-resolution pixels that the syntax cannot address.
    """
    count, size = source.shape[:2]
    cells = size // factor
    groups = (
        source.reshape(count, cells, factor, cells, factor, 3)
        .astype(np.float32)
        .mean(axis=(2, 4))
    )
    colors, selectors, _ = palette_candidate(rgb8(groups))
    reconstructed = np.repeat(
        np.repeat(
            colors[np.arange(count)[:, None], selectors].reshape(
                count, cells, cells, 3
            ),
            factor,
            axis=1,
        ),
        factor,
        axis=2,
    )
    return colors, selectors, reconstructed


class Encoder:
    """Closed-loop encoder: only reconstructed RGB8 frames become references."""

    def __init__(self, settings: Settings | None = None):
        settings = settings or Settings()
        self.settings = settings
        self.reference: np.ndarray | None = None
        self.reference_id = 0
        self.frames_since_key = settings.key_interval
        self.stats: dict = {}

    def encode(self, source: np.ndarray, frame_id: int) -> bytes:
        """Optionally compare complete zero/global-motion encodes before commit.

        A photometric global-motion estimate can waste override records on a
        stationary background. Spending two complete encoder trials measures the
        actual indexed frame size and decoded distortion. Both trials begin with
        the same accepted reference; neither can mutate this encoder before the
        winner is chosen. This is an encoder-only quality preset.
        """
        if (
            source.dtype != np.uint8
            or source.ndim != 3
            or source.shape[2] != 3
            or not all(1 <= size <= MAX_DIMENSION for size in source.shape[:2])
            or not 0 <= frame_id <= 0xFFFFFFFF
        ):
            raise ValueError("expected 1..4096 HxWx3 uint8 source and uint32 frame ID")
        if self.reference is not None and not (
            0 < ((frame_id - self.reference_id) & 0xFFFFFFFF) < 0x80000000
        ):
            raise ValueError("stale or ambiguous frame number")
        if (
            not self.settings.compare_global
            or not self.settings.global_motion
            or self.reference is None
            or self.frames_since_key >= self.settings.key_interval
            or source.shape != self.reference.shape
        ):
            return self._encode_once(source, frame_id)
        started = time.perf_counter()
        source_blocks = blocks(source, self.settings.block_size)
        candidates = []
        for use_global in (False, True):
            trial = Encoder(
                replace(self.settings, compare_global=False, global_motion=use_global)
            )
            trial.reference = self.reference
            trial.reference_id = self.reference_id
            trial.frames_since_key = self.frames_since_key
            data = trial._encode_once(source, frame_id)
            error = float(
                distortion(
                    source_blocks, blocks(trial.reference, self.settings.block_size)
                ).sum()
            )
            cost = error + self.settings.lambda_value * len(data) * 8
            candidates.append((cost, len(data), trial, data))
        _, _, winner, data = min(candidates, key=lambda candidate: candidate[:2])
        self.reference = winner.reference
        self.reference_id = winner.reference_id
        self.frames_since_key = winner.frames_since_key
        self.stats = dict(winner.stats, seconds=time.perf_counter() - started)
        return data

    def _encode_once(self, source: np.ndarray, frame_id: int) -> bytes:
        """Choose block records by D+lambda*bits, serialize, and verify reference.

        The sparse-table rate is approximated as zero descriptor bits for SKIP
        and 32 for other modes. Final bitrate always uses actual serialization.
        Page rounding is measured, but is not included in this local RDO model.
        """
        started = time.perf_counter()
        settings = self.settings
        height, width = source.shape[:2]
        keyframe = (
            self.reference is None
            or self.reference.shape != source.shape
            or self.frames_since_key >= settings.key_interval
        )
        global_vector = (0, 0)
        if not keyframe and settings.global_motion:
            global_vector = estimate_global(source, self.reference)
        source_blocks = blocks(source, settings.block_size)
        count = len(source_blocks)
        global_vectors = np.tile(global_vector, (count, 1))
        global_prediction = None
        if not keyframe:
            global_prediction = prediction(
                self.reference, settings.block_size, global_vectors
            )
            scene_error = np.mean(
                np.abs(
                    to_ycocg(source_blocks)[..., 0]
                    - to_ycocg(global_prediction)[..., 0]
                )
            )
            if scene_error > settings.scene_threshold:
                keyframe, global_vector = True, (0, 0)
        choices = choose_blocks(
            source_blocks,
            self.reference if not keyframe else None,
            global_vector,
            settings,
            global_prediction,
        )
        modes, quantizers, records = choices.modes, choices.quantizers, choices.records
        data = pack_frame(
            width,
            height,
            settings.block_size,
            frame_id,
            frame_id if keyframe else self.reference_id,
            keyframe,
            global_vector,
            [
                (int(m), int(q), r)
                for m, q, r in zip(modes, quantizers, records, strict=True)
            ],
            settings.sparse,
            settings.default_solid,
        )
        reconstructed = decode(data, self.reference, self.reference_id)
        expected = unblock(choices.pixels, width, height)
        if not np.array_equal(reconstructed, expected):
            raise AssertionError("encoder and reference decoder disagree")
        self.reference, self.reference_id = reconstructed, frame_id
        self.frames_since_key = 1 if keyframe else self.frames_since_key + 1
        frame = parse_frame(data)
        self.stats = dict(
            seconds=time.perf_counter() - started,
            keyframe=keyframe,
            global_motion=global_vector,
            modes=np.bincount(
                (np.array(frame.descriptors, np.uint32) >> 24) & 15, minlength=16
            ).tolist(),
            header_bytes=48,
            index_bytes=frame.payload_start - 48,
            payload_bytes=len(data) - frame.payload_start,
        )
        return data


def choose_blocks(
    source_blocks,
    reference,
    global_vector,
    settings,
    global_prediction=None,
    weights=None,
    retain_prediction=False,
    index_bits=32,
    skip_bits=None,
):
    """Evaluate the same leaf candidates for uniform and bounded-tree encoders."""
    count = len(source_blocks)
    keyframe = reference is None
    if not keyframe and global_prediction is None:
        global_prediction = prediction(
            reference, settings.block_size, np.tile(global_vector, (count, 1))
        )
    choices = BlockChoices(
        source_blocks,
        settings.lambda_value,
        settings.sparse,
        weights,
        index_bits=index_bits,
        skip_bits=skip_bits,
    )
    motion_bytes = []
    predicted = None
    if not keyframe:
        choices.consider(MODE_SKIP, 0, global_prediction, [b""] * count, 0)
        vectors = local_motion(source_blocks, reference, global_vector, settings)
        predicted = prediction(reference, settings.block_size, vectors)
        if retain_prediction:
            choices.temporal = (global_prediction, vectors, predicted)
        deltas = (vectors - np.array(global_vector)).astype(np.int8)
        motion_bytes = [delta.tobytes() for delta in deltas]
        choices.consider(MODE_MOTION, 0, predicted, motion_bytes, 2)

    solid = rgb8(source_blocks.mean(axis=(1, 2)))
    choices.consider(
        MODE_SOLID,
        0,
        np.broadcast_to(solid[:, None, None], source_blocks.shape),
        [color.tobytes() for color in solid],
        3,
    )
    if settings.palette:
        consider_palette(choices, palette_candidate)
    for mode, factor in ((COARSE_PALETTE_2, 2), (COARSE_PALETTE_4, 4)):
        if factor in settings.coarse_palette:
            consider_coarse_palette(
                choices,
                mode,
                lambda source, factor=factor: coarse_palette_candidate(source, factor),
            )
    for grid_size in settings.grids:
        consider_full_grid(choices, grid_size, predicted, motion_bytes)
    if settings.reduced_chroma:
        for luma_size, chroma_size, intra_mode in ((4, 1, 12), (8, 2, 14)):
            consider_reduced_grid(
                choices, luma_size, chroma_size, intra_mode, predicted, motion_bytes
            )
    return choices
