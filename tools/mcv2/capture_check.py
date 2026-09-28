"""Compare pictures captured from a client with the MCV2 pack's debug view against the reference decode.

    python tools/mcv2/capture_check.py <reference.rgb> <width> <height> <captures folder> [--top ROWS] [--vmaf FFMPEG]

The server runs with -Dmcav.mcv2.debugView=true, so the pack draws the decoded picture one to one in the top-left
corner of the screen, starting below the transport strip (--top: page slots times ceil(4096 / screen width), plus one
descriptor row; 13 rows at 1920 wide with four slots). The captures are screenshots of the whole screen, for example
ffmpeg's x11grab at two per second while a stream plays slowly with /mcav mcv2 play, so every frame of the stream is
on screen for several captures. The reference is the reference decoder's pictures of the same stream, raw RGB, one
after another.

Every capture is matched with the reference frame it equals byte for byte; a capture that equals none is matched with
the frame it is closest to, and reported with its PSNR. The summary gives how many of the stream's frames were seen
exactly, and PSNR, SSIM and (with --vmaf, the path of an ffmpeg built with libvmaf) VMAF of the captured pictures
against their reference frames.
"""

import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image


def psnr(captured, expected):
    mse = np.mean((captured.astype(np.float64) - expected.astype(np.float64)) ** 2)
    return float("inf") if mse == 0 else float(10 * np.log10(255.0**2 / mse))


def ssim(captured, expected):
    """Mean SSIM of the luma planes, 8x8 windows, the usual constants."""
    def luma(picture):
        picture = picture.astype(np.float64)
        return 0.299 * picture[..., 0] + 0.587 * picture[..., 1] + 0.114 * picture[..., 2]
    captured_luma, expected_luma = luma(captured), luma(expected)
    height, width = (captured_luma.shape[0] // 8) * 8, (captured_luma.shape[1] // 8) * 8
    captured_luma = captured_luma[:height, :width].reshape(height // 8, 8, width // 8, 8).swapaxes(1, 2)
    expected_luma = expected_luma[:height, :width].reshape(height // 8, 8, width // 8, 8).swapaxes(1, 2)
    captured_mean, expected_mean = captured_luma.mean(axis=(2, 3)), expected_luma.mean(axis=(2, 3))
    captured_variance, expected_variance = captured_luma.var(axis=(2, 3)), expected_luma.var(axis=(2, 3))
    covariance = ((captured_luma - captured_mean[..., None, None]) * (expected_luma - expected_mean[..., None, None])).mean(axis=(2, 3))
    luminance_constant, contrast_constant = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    return float(
        np.mean(
            (2 * captured_mean * expected_mean + luminance_constant)
            * (2 * covariance + contrast_constant)
            / ((captured_mean**2 + expected_mean**2 + luminance_constant) * (captured_variance + expected_variance + contrast_constant))
        )
    )


def vmaf(ffmpeg, reference, captured, width, height):
    with tempfile.TemporaryDirectory() as folder:
        ref, dis, log = Path(folder, "ref.rgb"), Path(folder, "dis.rgb"), Path(folder, "vmaf.json")
        ref.write_bytes(b"".join(frame.tobytes() for frame in reference))
        dis.write_bytes(b"".join(frame.tobytes() for frame in captured))
        raw = lambda path: [
            "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", f"{width}x{height}", "-framerate", "30", "-i", str(path)
        ]
        graph = "[0:v]format=yuv420p[ref];[1:v]format=yuv420p[dis];[dis][ref]libvmaf=n_threads=8:log_fmt=json:log_path=" + str(log)
        subprocess.run([ffmpeg, "-nostdin", "-v", "error", "-y", *raw(ref), *raw(dis), "-lavfi", graph, "-f", "null", "-"], check=True)
        frames = json.loads(log.read_text())["frames"]
        scores = [frame["metrics"]["vmaf"] for frame in frames]
        return float(np.mean(scores)), float(np.min(scores))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reference")
    parser.add_argument("width", type=int)
    parser.add_argument("height", type=int)
    parser.add_argument("captures")
    parser.add_argument("--top", type=int, default=13)
    parser.add_argument("--vmaf")
    arguments = parser.parse_args()
    width, height = arguments.width, arguments.height
    reference = np.fromfile(arguments.reference, np.uint8).reshape(-1, height, width, 3)
    paths = sorted(Path(arguments.captures).glob("*.png"))
    # a picture as tall as the screen does not fit below the strip: its rows that do are compared
    screen_height, screen_width = np.asarray(Image.open(paths[0]).convert("RGB")).shape[:2]
    visible = min(height, screen_height - arguments.top)
    shown = min(width, screen_width)
    if visible < height or shown < width:
        print("the screen shows %dx%d of the %dx%d picture; the rest is not compared" % (shown, visible, width, height))
        reference = np.ascontiguousarray(reference[:, :visible, :shown])
        height, width = visible, shown
    index = {hashlib.sha256(frame.tobytes()).hexdigest(): frame_index for frame_index, frame in enumerate(reference)}
    exact, near = {}, []
    pairs = []
    for path in paths:
        screen = np.asarray(Image.open(path).convert("RGB"))
        crop = np.ascontiguousarray(screen[arguments.top : arguments.top + height, :width])
        key = hashlib.sha256(crop.tobytes()).hexdigest()
        if key in index:
            exact.setdefault(index[key], path.name)
            pairs.append((index[key], crop))
            continue
        scores = [psnr(crop, frame) for frame in reference]
        best = int(np.argmax(scores))
        near.append((path.name, best, scores[best]))
        pairs.append((best, crop))
    print("captures:", len(pairs), "- frames seen exactly:", len(exact), "of", len(reference))
    for name, best, score in near:
        print("  %s is not exact: closest frame %d, PSNR %.2f dB" % (name, best, score))
    missing = sorted(set(range(len(reference))) - set(exact))
    if missing:
        print("  frames never seen exactly:", missing)
    captured = [crop for _, crop in pairs]
    matched = [reference[frame_index] for frame_index, _ in pairs]
    psnrs = [psnr(capture, expected) for capture, expected in zip(captured, matched)]
    finite = [value for value in psnrs if value != float("inf")]
    summary = {
        "captures": len(pairs),
        "exact_captures": len(pairs) - len(near),
        "frames_seen_exactly": len(exact),
        "frames": len(reference),
        "psnr_min_db": min(finite) if finite else "inf",
        "ssim_mean": float(np.mean([ssim(capture, expected) for capture, expected in zip(captured, matched)])),
    }
    if arguments.vmaf:
        summary["vmaf_mean"], summary["vmaf_min"] = vmaf(arguments.vmaf, matched, captured, width, height)
    print(json.dumps(summary))
    sys.exit(0 if not near and not missing else 1)


if __name__ == "__main__":
    main()
