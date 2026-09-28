"""Pixel-domain arithmetic shared by encoder analysis and CPU reconstruction.

RGB and YCoCg use gamma-coded 0..255 units, not linear light. YCoCg is floating
point: Y=(R+2G+B)/4, Co=(R-B)/2, Cg=(-R+2G-B)/4. No chroma offset.
Decoded references are canonical RGB8 after floor(value+0.5) and saturation.
"""

from functools import lru_cache

import numpy as np


def rgb8(values: np.ndarray) -> np.ndarray:
    """Saturate then round half upward, identically to the GLSL output path."""
    return np.floor(np.clip(values, 0, 255) + 0.5).astype(np.uint8)


def to_ycocg(rgb: np.ndarray) -> np.ndarray:
    """Convert RGB in 0..255 units to Y, signed Co, signed Cg."""
    red, green, blue = np.moveaxis(np.asarray(rgb, np.float32), -1, 0)
    return np.stack(
        (
            (red + 2 * green + blue) * 0.25,
            (red - blue) * 0.5,
            (-red + 2 * green - blue) * 0.25,
        ),
        axis=-1,
    )


def to_rgb(color: np.ndarray) -> np.ndarray:
    """Invert the floating YCoCg transform, without clipping or rounding."""
    luma, orange, green = np.moveaxis(color, -1, 0)
    return np.stack(
        (luma + orange - green, luma + green, luma - orange - green), axis=-1
    )


def blocks(image: np.ndarray, block_size: int) -> np.ndarray:
    """Split into raster BxB blocks; extend odd frame edges by replication."""
    height, width, channels = image.shape
    padded = np.pad(
        image,
        ((0, (-height) % block_size), (0, (-width) % block_size), (0, 0)),
        mode="edge",
    )
    rows, columns = padded.shape[0] // block_size, padded.shape[1] // block_size
    return (
        padded.reshape(rows, block_size, columns, block_size, channels)
        .transpose(0, 2, 1, 3, 4)
        .reshape(-1, block_size, block_size, channels)
    )


def unblock(values: np.ndarray, width: int, height: int) -> np.ndarray:
    """Reverse raster block order and crop replicated edge pixels."""
    block_size = values.shape[1]
    rows, columns = (
        (height + block_size - 1) // block_size,
        (width + block_size - 1) // block_size,
    )
    return (
        values.reshape(rows, columns, block_size, block_size, 3)
        .transpose(0, 2, 1, 3, 4)
        .reshape(rows * block_size, columns * block_size, 3)[:height, :width]
    )


@lru_cache(maxsize=32)
def interpolation(block_size: int, grid_size: int) -> np.ndarray:
    """Grid samples are cell-centered; edge extrapolation is clamped.

    Pixel center x maps to (x+0.5)*G/B-0.5. GLSL uses exactly the same mapping.
    """
    position = np.clip(
        (np.arange(block_size) + 0.5) * grid_size / block_size - 0.5, 0, grid_size - 1
    )
    lower = np.floor(position).astype(int)
    upper = np.minimum(lower + 1, grid_size - 1)
    fraction = position - lower
    matrix = np.zeros((block_size, grid_size), np.float32)
    matrix[np.arange(block_size), lower] += 1 - fraction
    matrix[np.arange(block_size), upper] += fraction
    return matrix


@lru_cache(maxsize=32)
def fitting_matrix(block_size: int, grid_size: int) -> np.ndarray:
    """Least-squares inverse avoids systematic blur from naive cell averages."""
    return np.linalg.pinv(interpolation(block_size, grid_size)).astype(np.float32)


def fit_grid(values: np.ndarray, grid_size: int) -> np.ndarray:
    """Fit independent RGB or residual grids to a batch of equal-size blocks."""
    matrix = fitting_matrix(values.shape[1], grid_size)
    return np.einsum("iy,nyxc,jx->nijc", matrix, values, matrix, optimize=True)


def expand_grid(grid: np.ndarray, block_size: int) -> np.ndarray:
    """Reconstruct a batch of bilinear grids; at most four nodes per pixel."""
    matrix = interpolation(block_size, grid.shape[1])
    return np.einsum("yi,nijc,xj->nyxc", matrix, grid, matrix, optimize=True)


def prediction(
    reference: np.ndarray, block_size: int, motion: np.ndarray
) -> np.ndarray:
    """Sample reference at each current pixel plus signed half-pixel displacement.

    motion is [block_count,2], (dx,dy) in half-pixel units. Explicit bilinear
    interpolation removes sampler-filtering and texture-origin ambiguities.
    """
    height, width = reference.shape[:2]
    columns = (width + block_size - 1) // block_size
    indices = np.arange(len(motion))
    if np.all(np.remainder(motion, 2) == 0):
        # Integer motion has exactly one contributing sample. Avoid allocating
        # four gathered images and float64 interpolation intermediates.
        integer = (motion // 2).astype(np.int64)
        xx = np.clip(
            (indices % columns)[:, None, None] * block_size
            + np.arange(block_size)[None, None, :]
            + integer[:, 0, None, None],
            0,
            width - 1,
        )
        yy = np.clip(
            (indices // columns)[:, None, None] * block_size
            + np.arange(block_size)[None, :, None]
            + integer[:, 1, None, None],
            0,
            height - 1,
        )
        return reference[yy, xx].astype(np.float32)
    position_x = (
        (indices % columns)[:, None, None] * block_size
        + np.arange(block_size)[None, None, :]
        + motion[:, 0, None, None] * 0.5
    )
    position_y = (
        (indices // columns)[:, None, None] * block_size
        + np.arange(block_size)[None, :, None]
        + motion[:, 1, None, None] * 0.5
    )
    position_x, position_y = (
        np.clip(position_x, 0, width - 1),
        np.clip(position_y, 0, height - 1),
    )
    lower_x, lower_y = (
        np.floor(position_x).astype(int),
        np.floor(position_y).astype(int),
    )
    upper_x, upper_y = (
        np.minimum(lower_x + 1, width - 1),
        np.minimum(lower_y + 1, height - 1),
    )
    fraction_x, fraction_y = (
        (position_x - lower_x)[..., None],
        (position_y - lower_y)[..., None],
    )
    top = (
        reference[lower_y, lower_x] * (1 - fraction_x)
        + reference[lower_y, upper_x] * fraction_x
    )
    bottom = (
        reference[upper_y, lower_x] * (1 - fraction_x)
        + reference[upper_y, upper_x] * fraction_x
    )
    return (top * (1 - fraction_y) + bottom * fraction_y).astype(np.float32)


def distortion(source: np.ndarray, reconstructed: np.ndarray) -> np.ndarray:
    """Weighted SSE per block (Y:Co:Cg=4:1:1), in gamma-coded 8-bit units.

    SSE rather than MSE makes lambda have consistent error-per-bit units across
    block sizes. Metrics separately report conventional PSNR and local SSIM.
    """
    # The transform is linear and dyadic. Transforming the difference is exact
    # for RGB8 inputs and avoids converting the source afresh for every candidate.
    error = to_ycocg(source.astype(np.float32) - reconstructed.astype(np.float32))
    return np.sum(error * error * np.array([4, 1, 1], np.float32), axis=(1, 2, 3)) / 6
