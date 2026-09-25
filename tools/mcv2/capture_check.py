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


def psnr(a, b):
    mse = np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2)
    return float("inf") if mse == 0 else float(10 * np.log10(255.0**2 / mse))


def ssim(a, b):
    """Mean SSIM of the luma planes, 8x8 windows, the usual constants."""
    def luma(x):
        x = x.astype(np.float64)
        return 0.299 * x[..., 0] + 0.587 * x[..., 1] + 0.114 * x[..., 2]
    x, y = luma(a), luma(b)
    h, w = (x.shape[0] // 8) * 8, (x.shape[1] // 8) * 8
    x = x[:h, :w].reshape(h // 8, 8, w // 8, 8).swapaxes(1, 2)
    y = y[:h, :w].reshape(h // 8, 8, w // 8, 8).swapaxes(1, 2)
    mx, my = x.mean(axis=(2, 3)), y.mean(axis=(2, 3))
    vx, vy = x.var(axis=(2, 3)), y.var(axis=(2, 3))
    cov = ((x - mx[..., None, None]) * (y - my[..., None, None])).mean(axis=(2, 3))
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    return float(np.mean((2 * mx * my + c1) * (2 * cov + c2) / ((mx**2 + my**2 + c1) * (vx + vy + c2))))


def vmaf(ffmpeg, reference, captured, width, height):
    with tempfile.TemporaryDirectory() as folder:
        ref, dis, log = Path(folder, "ref.rgb"), Path(folder, "dis.rgb"), Path(folder, "vmaf.json")
        ref.write_bytes(b"".join(r.tobytes() for r in reference))
        dis.write_bytes(b"".join(c.tobytes() for c in captured))
        raw = lambda p: ["-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", f"{width}x{height}", "-framerate", "30", "-i", str(p)]
        graph = "[0:v]format=yuv420p[ref];[1:v]format=yuv420p[dis];[dis][ref]libvmaf=n_threads=8:log_fmt=json:log_path=" + str(log)
        subprocess.run([ffmpeg, "-nostdin", "-v", "error", "-y", *raw(ref), *raw(dis), "-lavfi", graph, "-f", "null", "-"], check=True)
        frames = json.loads(log.read_text())["frames"]
        scores = [f["metrics"]["vmaf"] for f in frames]
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
    w, h = arguments.width, arguments.height
    reference = np.fromfile(arguments.reference, np.uint8).reshape(-1, h, w, 3)
    index = {hashlib.sha256(frame.tobytes()).hexdigest(): i for i, frame in enumerate(reference)}
    exact, near = {}, []
    pairs = []
    for path in sorted(Path(arguments.captures).glob("*.png")):
        screen = np.asarray(Image.open(path).convert("RGB"))
        crop = np.ascontiguousarray(screen[arguments.top : arguments.top + h, :w])
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
    matched = [reference[i] for i, _ in pairs]
    psnrs = [psnr(c, r) for c, r in zip(captured, matched)]
    finite = [p for p in psnrs if p != float("inf")]
    summary = {
        "captures": len(pairs),
        "exact_captures": len(pairs) - len(near),
        "frames_seen_exactly": len(exact),
        "frames": len(reference),
        "psnr_min_db": min(finite) if finite else "inf",
        "ssim_mean": float(np.mean([ssim(c, r) for c, r in zip(captured, matched)])),
    }
    if arguments.vmaf:
        summary["vmaf_mean"], summary["vmaf_min"] = vmaf(arguments.vmaf, matched, captured, w, h)
    print(json.dumps(summary))
    sys.exit(0 if not near and not missing else 1)


if __name__ == "__main__":
    main()
