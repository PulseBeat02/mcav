"""Immutable MCV2 residual books, shared by the CPU and GLSL decoder."""

import hashlib
from functools import lru_cache
from pathlib import Path

import numpy as np

BOOK_PATH = (
    Path(__file__).resolve().parents[1] / "research_artifacts/residual_books.bin"
)


@lru_cache(maxsize=1)
def book_bytes():
    data = BOOK_PATH.read_bytes()
    manifest = BOOK_PATH.with_suffix(".sha256").read_text().strip()
    if len(data) != 2048 or hashlib.sha256(data).hexdigest() != manifest:
        raise ValueError("MCV2 residual codebook checksum/length mismatch")
    return data


@lru_cache(maxsize=1)
def load_books():
    data = np.frombuffer(book_bytes(), np.int8)
    return {"vq": data[:1024].reshape(64, 16), "pq": data[1024:].reshape(2, 64, 8)}


def nearest(vectors, centers):
    vectors = vectors.astype(np.float32)
    centers = centers.astype(np.float32)
    # Avoid an N x K x D temporary and bound the distance matrix.
    return np.concatenate(
        [
            np.argmin(
                np.sum(v * v, axis=1)[:, None]
                + np.sum(centers * centers, axis=1)[None]
                - 2 * (v @ centers.T),
                axis=1,
            )
            for v in np.array_split(vectors, max(1, (len(vectors) + 1023) // 1024))
        ]
    )
