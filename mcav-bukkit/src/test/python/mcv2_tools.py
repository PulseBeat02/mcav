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

"""MCV2 measurements, fixture generation, shader checks and documentation figures."""

import argparse
import hashlib
import json
import logging
import math
import os
import random
import re
import struct
import subprocess
import sys
import tempfile
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

import mcv2_reference
import numpy
from mcv2_reference import (
    PAGE_HEADER,
    PAGE_SYMBOLS,
    SYMBOL_BITS,
    Decoder,
    SOLID,
    decode,
    Node,
    make_pages,
    pack_frame,
    page_capacity,
    parse_frame,
    read_page,
    wire_bytes,
)


def bd_rate(reference_points, test):
    reference_rates = numpy.log([point[0] for point in reference_points])
    reference_qualities = numpy.array([point[1] for point in reference_points])
    test_rates = numpy.log([point[0] for point in test])
    test_qualities = numpy.array([point[1] for point in test])
    low = max(reference_qualities.min(), test_qualities.min())
    high = min(reference_qualities.max(), test_qualities.max())
    if high <= low:
        raise ValueError("the curves share no quality range")
    reference_fit = numpy.polyfit(reference_qualities, reference_rates, 3)
    test_fit = numpy.polyfit(test_qualities, test_rates, 3)
    reference_area = numpy.polyval(numpy.polyint(reference_fit), high) - numpy.polyval(
        numpy.polyint(reference_fit), low
    )
    test_area = numpy.polyval(numpy.polyint(test_fit), high) - numpy.polyval(numpy.polyint(test_fit), low)
    return ((math.exp((test_area - reference_area) / (high - low)) - 1) * 100, low, high)


def bd_rate_points(path, rate, metric):
    data = json.load(open(path))
    return sorted((float(point[rate]), float(point[metric])) for point in data)


def bd_rate_main():
    parser = argparse.ArgumentParser(
        description=(
            "Compare cubic log-rate/quality fits over their shared quality range; positive BD-rate means the test "
            "needs more rate. Each JSON curve needs at least four points. Exit 1 on invalid curve data; 2 on "
            "invalid options."
        ),
    )
    parser.add_argument("reference")
    parser.add_argument("test")
    parser.add_argument("--metric", default="vmaf_mean")
    parser.add_argument("--rate", default="map_mbps")
    arguments = parser.parse_args()
    reference_points = bd_rate_points(arguments.reference, arguments.rate, arguments.metric)
    test = bd_rate_points(arguments.test, arguments.rate, arguments.metric)
    if len(reference_points) < 4 or len(test) < 4:
        sys.exit("each curve needs at least four points")
    delta, low, high = bd_rate(reference_points, test)
    print(
        json.dumps(
            {
                "bd_rate_percent": round(delta, 3),
                "metric": arguments.metric,
                "rate": arguments.rate,
                "quality_low": round(low, 4),
                "quality_high": round(high, 4),
            }
        )
    )


codec_curves_CODECS = {
    "x264": {
        "container": "mkv",
        "arguments": ["-c:v", "libx264", "-preset", "veryslow", "-crf", "{q}", "-pix_fmt", "yuv420p"],
    },
    "vp9": {
        "container": "webm",
        "arguments": [
            "-c:v",
            "libvpx-vp9",
            "-crf",
            "{q}",
            "-b:v",
            "0",
            "-deadline",
            "good",
            "-cpu-used",
            "0",
            "-row-mt",
            "1",
            "-pix_fmt",
            "yuv420p",
        ],
    },
    "av1": {
        "container": "mkv",
        "arguments": [
            "-c:v",
            "libaom-av1",
            "-crf",
            "{q}",
            "-b:v",
            "0",
            "-cpu-used",
            "6",
            "-row-mt",
            "1",
            "-pix_fmt",
            "yuv420p",
        ],
    },
}


def vmaf_filter(log, threads=8):
    return (
        "[0:v]format=yuv420p[ref];[1:v]format=yuv420p[dis];"
        f"[dis][ref]libvmaf=n_threads={threads}:log_fmt=json:log_path={log}"
    )


codec_curves_VERSION_PATTERNS = {
    "x264": re.compile(rb"x264 - core \d+ r\d+ \w+"),
    "vp9": re.compile(r"\[libvpx-vp9 @ [^\]]+\] (v\d[^\s]*)"),
    "av1": re.compile(r"\[libaom-av1 @ [^\]]+\] (\d+\.\d+\.\d+[^\s]*)"),
}


def codec_curves_sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def codec_curves_raw(path, width, height, fps):
    return [
        "-f",
        "rawvideo",
        "-pixel_format",
        "rgb24",
        "-video_size",
        f"{width}x{height}",
        "-framerate",
        str(fps),
        "-i",
        path,
    ]


def codec_curves_placeholders(command, replacements):
    text = []
    for part in command:
        for local, name in replacements.items():
            part = part.replace(local, name)
        text.append(part)
    return text


def codec_curves_run(command):
    started = time.perf_counter()
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"{command[0]} failed: {result.stderr[-2000:]}")
    return (result, time.perf_counter() - started)


def codec_curves_rgb_psnr(source, decoded, frames, width, height):
    shape = (frames, height, width, 3)
    reference_frames = numpy.memmap(source, dtype=numpy.uint8, mode="r", shape=shape)
    picture = numpy.memmap(decoded, dtype=numpy.uint8, mode="r", shape=shape)
    errors = []
    for first, second in zip(reference_frames, picture, strict=True):
        difference = first.astype(numpy.float64) - second.astype(numpy.float64)
        errors.append(float(numpy.mean(difference * difference)))
    mean_squared_error = float(numpy.mean(errors))
    return 99.0 if mean_squared_error == 0 else float(10 * numpy.log10(255**2 / mean_squared_error))


def codec_curves_point(arguments, codec, quality, folder):
    settings = codec_curves_CODECS[codec]
    encoded = os.path.join(folder, f"{codec}-{quality}.{settings['container']}")
    decoded = os.path.join(folder, f"{codec}-{quality}.rgb")
    log = os.path.join(folder, f"{codec}-{quality}-vmaf.json")
    size = (arguments.width, arguments.height, arguments.fps)
    encode = [
        arguments.ffmpeg,
        "-hide_banner",
        "-nostdin",
        "-v",
        "verbose",
        "-y",
        *codec_curves_raw(arguments.source, *size),
        "-frames:v",
        str(arguments.frames),
        "-an",
        *[part.replace("{q}", str(quality)) for part in settings["arguments"]],
        encoded,
    ]
    result, encode_seconds = codec_curves_run(encode)
    decode_command = [
        arguments.ffmpeg,
        "-hide_banner",
        "-nostdin",
        "-v",
        "error",
        "-y",
        "-i",
        encoded,
        "-fps_mode",
        "passthrough",
        "-pix_fmt",
        "rgb24",
        "-f",
        "rawvideo",
        decoded,
    ]
    codec_curves_run(decode_command)
    frame_bytes = arguments.width * arguments.height * 3
    decoded_frames, remainder = divmod(os.path.getsize(decoded), frame_bytes)
    if remainder or decoded_frames != arguments.frames:
        raise RuntimeError(f"{codec} {quality}: decoded {decoded_frames} frames, expected {arguments.frames}")
    score = [
        arguments.ffmpeg,
        "-hide_banner",
        "-nostdin",
        "-v",
        "error",
        "-y",
        *codec_curves_raw(arguments.source, *size),
        *codec_curves_raw(decoded, *size),
        "-lavfi",
        vmaf_filter(log),
        "-f",
        "null",
        "-",
    ]
    codec_curves_run(score)
    vmaf = json.load(open(log))
    frames = [frame["metrics"]["vmaf"] for frame in vmaf["frames"]]
    if len(frames) != arguments.frames:
        raise RuntimeError(f"{codec} {quality}: libvmaf scored {len(frames)} frames")
    container_bytes = os.path.getsize(encoded)
    seconds = arguments.frames / arguments.fps
    version = codec_curves_VERSION_PATTERNS[codec]
    if codec == "x264":
        found = version.search(open(encoded, "rb").read())
        library = found.group(0).decode() if found else None
    else:
        found = version.search(result.stderr)
        library = found.group(1) if found else None
    names = {
        arguments.source: "$SRC",
        encoded: "ENCODED",
        decoded: "DECODED",
        log: "VMAF.json",
        arguments.ffmpeg: "$FFMPEG",
    }
    return {
        "source": arguments.name,
        "codec": codec,
        "library": library,
        "quality": quality,
        "container_bytes": container_bytes,
        "container_mbps": round(container_bytes * 8 / seconds / 1e6, 6),
        "vmaf_mean": round(vmaf["pooled_metrics"]["vmaf"]["mean"], 6),
        "vmaf_min": round(min(frames), 6),
        "rgb_psnr": round(
            codec_curves_rgb_psnr(
                arguments.source, decoded, arguments.frames, arguments.width, arguments.height
            ),
            4,
        ),
        "encode_seconds": round(encode_seconds, 1),
        "commands": {
            "encode": codec_curves_placeholders(encode, names),
            "decode": codec_curves_placeholders(decode_command, names),
            "score": codec_curves_placeholders(score, names),
        },
    }


def codec_curves_resume(out, name, identity):
    """The measurements already in the output, with the identity of this run's source recorded under its name.

    The curves are kept under "codecs", as in mcav-bukkit/src/test/resources/mcv2/data/codec_curves.json; the first runs wrote them under "points",
    which is read too. A name whose recorded source is other content is refused: its points would mix with this run's.
    """
    measured = json.load(open(out)) if os.path.exists(out) else {"sources": {}, "codecs": []}
    if "points" in measured:
        if "codecs" in measured:
            raise ValueError("%s holds both points and codecs; resolve the two datasets first" % out)
        measured["codecs"] = measured.pop("points")
    previous = measured["sources"].get(name)
    if previous is not None and any(previous.get(key) != value for key, value in identity.items()):
        raise ValueError("%s already has measurements of other content; use another --name" % name)
    measured["sources"][name] = identity
    return measured


def codec_curves_main():
    parser = argparse.ArgumentParser(
        description=(
            "Encode raw RGB24 at H.264, VP9 and AV1 CRFs, decode to RGB24, then score RGB PSNR and libvmaf after "
            "yuv420p conversion. Rates include container bytes only. Resume existing points; refuse a source name "
            "with different content. Exit 1 on an encode/score failure; 2 on conflicting source identity or "
            "invalid options."
        ),
    )
    parser.add_argument("--ffmpeg", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--frames", type=int, required=True)
    parser.add_argument("--fps", type=float, required=True)
    parser.add_argument(
        "--codecs", nargs="+", choices=sorted(codec_curves_CODECS), default=sorted(codec_curves_CODECS)
    )
    parser.add_argument("--qualities", nargs="+", type=int, required=True, help="the CRF values to encode at")
    parser.add_argument("--out", required=True)
    arguments = parser.parse_args()
    identity = {
        "width": arguments.width,
        "height": arguments.height,
        "frames": arguments.frames,
        "fps": arguments.fps,
        "sha256": codec_curves_sha256(arguments.source),
    }
    try:
        measured = codec_curves_resume(arguments.out, arguments.name, identity)
    except ValueError as error:
        parser.error(str(error))
    done = {(point["source"], point["codec"], point["quality"]) for point in measured["codecs"]}
    version = subprocess.run(
        [arguments.ffmpeg, "-version"], capture_output=True, text=True
    ).stdout.splitlines()[0]
    measured["ffmpeg"] = version
    with tempfile.TemporaryDirectory() as folder:
        for codec in arguments.codecs:
            for quality in arguments.qualities:
                if (arguments.name, codec, quality) in done:
                    continue
                measured["codecs"].append(codec_curves_point(arguments, codec, quality, folder))
                print(
                    json.dumps(measured["codecs"][-1]["codec"]),
                    quality,
                    measured["codecs"][-1]["container_mbps"],
                    measured["codecs"][-1]["vmaf_mean"],
                    flush=True,
                )
                measured["codecs"].sort(key=lambda point: (point["source"], point["codec"], point["quality"]))
                temporary = arguments.out + ".partial"
                with open(temporary, "w") as out:
                    json.dump(measured, out, indent=1)
                    out.write("\n")
                os.replace(temporary, arguments.out)


counter_video_BITS = 20
counter_video_BLOCK = 32
counter_video_SYNC = (1, 0, 1, 0)


def counter_video_stamp(frame, number):
    values = [number >> bit & 1 for bit in range(counter_video_BITS)] + list(counter_video_SYNC)
    for position, value in enumerate(values):
        frame[
            0:counter_video_BLOCK, position * counter_video_BLOCK : (position + 1) * counter_video_BLOCK
        ] = 255 if value else 0


def counter_video_read(luma):
    bits = [1 if value >= 128 else 0 for value in luma]
    if (
        len(bits) < counter_video_BITS + len(counter_video_SYNC)
        or tuple(bits[counter_video_BITS : counter_video_BITS + len(counter_video_SYNC)])
        != counter_video_SYNC
    ):
        return None
    return sum(bit << position for position, bit in enumerate(bits[:counter_video_BITS]))


def counter_video_main():
    parser = argparse.ArgumentParser(
        description=(
            "Loop an RGB24 clip forward and backward, stamping its frame number into twenty bits plus four sync "
            "blocks for latency measurements. Write raw RGB or CRF-12 H.264. Exit 1 on input failure; 2 on "
            "invalid options. Encoded output exits with ffmpeg's status."
        ),
    )
    parser.add_argument("clip")
    parser.add_argument("width", type=int)
    parser.add_argument("height", type=int)
    parser.add_argument("fps", type=int)
    parser.add_argument("seconds", type=float)
    parser.add_argument("out")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    arguments = parser.parse_args()
    width, height = (arguments.width, arguments.height)
    size = width * height * 3
    clip = numpy.memmap(arguments.clip, dtype=numpy.uint8, mode="r")
    count = clip.size // size
    if (
        count < 1
        or width < counter_video_BLOCK * (counter_video_BITS + len(counter_video_SYNC))
        or height < counter_video_BLOCK
    ):
        sys.exit(
            "the clip must have a frame at least %d pixels wide and %d high"
            % (counter_video_BLOCK * (counter_video_BITS + len(counter_video_SYNC)), counter_video_BLOCK)
        )
    frames = int(arguments.seconds * arguments.fps)
    raw = arguments.out.endswith(".rgb")
    encoder = (
        None
        if raw
        else subprocess.Popen(
            [
                arguments.ffmpeg,
                "-nostdin",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "rawvideo",
                "-pix_fmt",
                "rgb24",
                "-s",
                "%dx%d" % (width, height),
                "-r",
                str(arguments.fps),
                "-i",
                "-",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "12",
                "-g",
                str(2 * arguments.fps),
                "-pix_fmt",
                "yuv420p",
                arguments.out,
            ],
            stdin=subprocess.PIPE,
        )
    )
    sink = open(arguments.out, "wb") if raw else encoder.stdin
    period = max(1, 2 * (count - 1))
    for number in range(frames):
        phase = number % period
        index = phase if phase < count else period - phase
        frame = numpy.array(clip[index * size : (index + 1) * size]).reshape(height, width, 3)
        counter_video_stamp(frame, number)
        sink.write(frame.tobytes())
    sink.close()
    sys.exit(0 if raw else encoder.wait())


def capture_check_psnr(captured, expected):
    mean_squared_error = numpy.mean((captured.astype(numpy.float64) - expected.astype(numpy.float64)) ** 2)
    return float("inf") if mean_squared_error == 0 else float(10 * numpy.log10(255.0**2 / mean_squared_error))


def capture_check_ssim(captured, expected):

    def luma(picture):
        picture = picture.astype(numpy.float64)
        return 0.299 * picture[..., 0] + 0.587 * picture[..., 1] + 0.114 * picture[..., 2]

    captured_luma, expected_luma = (luma(captured), luma(expected))
    height, width = (captured_luma.shape[0] // 8 * 8, captured_luma.shape[1] // 8 * 8)
    captured_luma = captured_luma[:height, :width].reshape(height // 8, 8, width // 8, 8).swapaxes(1, 2)
    expected_luma = expected_luma[:height, :width].reshape(height // 8, 8, width // 8, 8).swapaxes(1, 2)
    captured_mean, expected_mean = (captured_luma.mean(axis=(2, 3)), expected_luma.mean(axis=(2, 3)))
    captured_variance, expected_variance = (captured_luma.var(axis=(2, 3)), expected_luma.var(axis=(2, 3)))
    covariance = (
        (captured_luma - captured_mean[..., None, None]) * (expected_luma - expected_mean[..., None, None])
    ).mean(axis=(2, 3))
    luminance_constant, contrast_constant = ((0.01 * 255) ** 2, (0.03 * 255) ** 2)
    return float(
        numpy.mean(
            (2 * captured_mean * expected_mean + luminance_constant)
            * (2 * covariance + contrast_constant)
            / (
                (captured_mean**2 + expected_mean**2 + luminance_constant)
                * (captured_variance + expected_variance + contrast_constant)
            )
        )
    )


def capture_check_vmaf(ffmpeg, reference_frames, captured, width, height):
    with tempfile.TemporaryDirectory() as folder:
        reference_picture, distorted_picture, log = (
            Path(folder, "ref.rgb"),
            Path(folder, "dis.rgb"),
            Path(folder, "vmaf.json"),
        )
        reference_picture.write_bytes(b"".join(frame.tobytes() for frame in reference_frames))
        distorted_picture.write_bytes(b"".join(frame.tobytes() for frame in captured))
        raw = lambda path: [
            "-f",
            "rawvideo",
            "-pixel_format",
            "rgb24",
            "-video_size",
            f"{width}x{height}",
            "-framerate",
            "30",
            "-i",
            str(path),
        ]
        graph = vmaf_filter(log)
        subprocess.run(
            [
                ffmpeg,
                "-nostdin",
                "-v",
                "error",
                "-y",
                *raw(reference_picture),
                *raw(distorted_picture),
                "-lavfi",
                graph,
                "-f",
                "null",
                "-",
            ],
            check=True,
        )
        frames = json.loads(log.read_text())["frames"]
        scores = [frame["metrics"]["vmaf"] for frame in frames]
        return (float(numpy.mean(scores)), float(numpy.min(scores)))


def capture_check_main():
    parser = argparse.ArgumentParser(
        description=(
            "Match debug-view PNG captures below --top to decoded RGB24 reference pictures; report exact, "
            "ambiguous and nearest matches, PSNR, SSIM and optional VMAF. Identical reference pictures cannot "
            "distinguish frame occurrences. Exit 1 if any capture is not exact or any distinct picture is never "
            "seen exactly; 2 on invalid options."
        ),
    )
    parser.add_argument("reference")
    parser.add_argument("width", type=int)
    parser.add_argument("height", type=int)
    parser.add_argument("captures")
    parser.add_argument("--top", type=int, default=13)
    parser.add_argument("--vmaf")
    arguments = parser.parse_args()
    from PIL import Image

    width, height = (arguments.width, arguments.height)
    reference_frames = numpy.fromfile(arguments.reference, numpy.uint8).reshape(-1, height, width, 3)
    paths = sorted(Path(arguments.captures).glob("*.png"))
    screen_height, screen_width = numpy.asarray(Image.open(paths[0]).convert("RGB")).shape[:2]
    visible = min(height, screen_height - arguments.top)
    shown = min(width, screen_width)
    if visible < height or shown < width:
        print(
            "the screen shows %dx%d of the %dx%d picture; the rest is not compared"
            % (shown, visible, width, height)
        )
        reference_frames = numpy.ascontiguousarray(reference_frames[:, :visible, :shown])
        height, width = (visible, shown)
    index = {}
    for frame_index, frame in enumerate(reference_frames):
        index.setdefault(hashlib.sha256(frame.tobytes()).hexdigest(), []).append(frame_index)
    exact, near = ({}, [])
    pairs = []
    for path in paths:
        screen = numpy.asarray(Image.open(path).convert("RGB"))
        crop = numpy.ascontiguousarray(screen[arguments.top : arguments.top + height, :width])
        key = hashlib.sha256(crop.tobytes()).hexdigest()
        if key in index:
            exact.setdefault(key, path.name)
            pairs.append((index[key][0], crop))
            continue
        scores = [capture_check_psnr(crop, frame) for frame in reference_frames]
        best = int(numpy.argmax(scores))
        near.append((path.name, best, scores[best]))
        pairs.append((best, crop))
    unique_frames_seen = sum(1 for key in exact if len(index[key]) == 1)
    ambiguous_frames = sum(len(occurrences) for occurrences in index.values() if len(occurrences) > 1)
    print("captures:", len(pairs), "- distinct pictures seen exactly:", len(exact), "of", len(index))
    if ambiguous_frames:
        print(
            " ",
            ambiguous_frames,
            "reference frame occurrences are indistinguishable; their individual display is unknown",
        )
    for name, best, score in near:
        print("  %s is not exact: closest frame %d, PSNR %.2f dB" % (name, best, score))
    missing = sorted(occurrences[0] for key, occurrences in index.items() if key not in exact)
    if missing:
        print("  pictures never seen exactly (first reference frame):", missing)
    captured = [crop for _, crop in pairs]
    matched = [reference_frames[frame_index] for frame_index, _ in pairs]
    psnrs = [capture_check_psnr(capture, expected) for capture, expected in zip(captured, matched)]
    finite = [value for value in psnrs if value != float("inf")]
    summary = {
        "captures": len(pairs),
        "exact_captures": len(pairs) - len(near),
        "frames_seen_exactly": unique_frames_seen,
        "frames": len(reference_frames),
        "pictures_seen_exactly": len(exact),
        "distinct_pictures": len(index),
        "ambiguous_reference_frames": ambiguous_frames,
        "psnr_min_db": min(finite) if finite else "inf",
        "ssim_mean": float(
            numpy.mean(
                [capture_check_ssim(capture, expected) for capture, expected in zip(captured, matched)]
            )
        ),
    }
    if arguments.vmaf:
        summary["vmaf_mean"], summary["vmaf_min"] = capture_check_vmaf(
            arguments.vmaf, matched, captured, width, height
        )
    print(json.dumps(summary))
    sys.exit(0 if not near and (not missing) else 1)


edge_streams_DEFAULT_SEED = 20261008


def edge_streams_rbytes(randomizer, count):
    return bytes(randomizer.randrange(256) for _ in range(count))


def edge_streams_compact_record(randomizer):
    return edge_streams_rbytes(randomizer, mcv2_reference.COMPACT_BYTES)


def edge_streams_pattern_record(randomizer, size):
    return (
        edge_streams_rbytes(randomizer, 6)
        + bytes([randomizer.randrange(2)])
        + edge_streams_rbytes(randomizer, size // 8)
    )


def edge_streams_leaf(randomizer, size, keyframe, mode=None):
    modes = (
        (mcv2_reference.SKIP, mcv2_reference.SOLID, mcv2_reference.PALETTE, mcv2_reference.PATTERN)
        if keyframe
        else tuple(range(mcv2_reference.SPLIT))
    )
    mode = randomizer.choice(modes) if mode is None else mode
    if mode == mcv2_reference.SKIP:
        return Node(mode)
    if mode == mcv2_reference.MOTION:
        return Node(mode, record=edge_streams_rbytes(randomizer, 2))
    if mode == mcv2_reference.SOLID:
        return Node(mode, record=edge_streams_rbytes(randomizer, 3))
    if mode == mcv2_reference.PALETTE:
        return Node(mode, record=edge_streams_rbytes(randomizer, 6 + size * size // 8))
    if mode == mcv2_reference.PATTERN:
        return Node(mode, record=edge_streams_pattern_record(randomizer, size))
    return Node(
        mcv2_reference.COMPACT,
        randomizer.randrange(mcv2_reference.MAX_QUANTIZER + 1),
        edge_streams_compact_record(randomizer),
    )


def edge_streams_tree(randomizer, size, keyframe, split_chance=0.65):
    if size > 8 and randomizer.random() < split_chance:
        return Node(
            mcv2_reference.SPLIT,
            children=tuple(
                edge_streams_tree(randomizer, size // 2, keyframe, split_chance) for _ in range(4)
            ),
        )
    return edge_streams_leaf(randomizer, size, keyframe)


def edge_streams_random_stream(randomizer, width=None, height=None, frame_count=6):
    width = randomizer.randint(1, 200) if width is None else width
    height = randomizer.randint(1, 130) if height is None else height
    count = (width + 31) // 32 * ((height + 31) // 32)
    stream = []
    for frame_id in range(frame_count):
        keyframe = frame_id == 0 or randomizer.random() < 0.2
        roots = {
            index: edge_streams_tree(randomizer, 32, keyframe)
            for index in range(count)
            if randomizer.random() > 0.2
        }
        stream.append(pack_frame(width, height, frame_id, frame_id if keyframe else frame_id - 1, roots))
    return stream


def edge_streams_repeated(node, size):
    while size < 32:
        node = Node(mcv2_reference.SPLIT, children=(node,) * 4)
        size *= 2
    return node


def edge_streams_mode_stream(randomizer):
    nodes = []
    for size in (8, 16, 32):
        nodes.extend(
            edge_streams_repeated(edge_streams_leaf(randomizer, size, False, mode), size)
            for mode in range(mcv2_reference.COMPACT)
        )
        for quantizer in range(mcv2_reference.MAX_QUANTIZER + 1):
            for vector, luma in (
                (b"\x00\x00", edge_streams_rbytes(randomizer, 8)),
                (b"\x80\x7f", bytes([119]) * 8),
                (b"\x7f\x80", bytes([136]) * 8),
                (edge_streams_rbytes(randomizer, 2), edge_streams_rbytes(randomizer, 8)),
            ):
                nodes.append(
                    edge_streams_repeated(Node(mcv2_reference.COMPACT, quantizer, vector + luma), size)
                )
    height = (len(nodes) + 15) // 16 * 32
    key_roots = {
        index: edge_streams_repeated(edge_streams_leaf(randomizer, size, True, mode), size)
        for index, (size, mode) in enumerate(
            (size, mode)
            for size in (8, 16, 32)
            for mode in (
                mcv2_reference.SKIP,
                mcv2_reference.SOLID,
                mcv2_reference.PALETTE,
                mcv2_reference.PATTERN,
            )
        )
    }
    return [pack_frame(512, height, 0, 0, key_roots), pack_frame(512, height, 1, 0, dict(enumerate(nodes)))]


def edge_streams_pattern_stream(randomizer):
    endpoints = (bytes([1, 254, 3, 255, 0, 129]), bytes([127, 128, 77, 2, 253, 250]))
    roots, index = ({}, 0)
    for size in (8, 16, 32):
        for orientation in (0, 1):
            for axis in (
                bytes(size // 8),
                bytes([255]) * (size // 8),
                bytes([165]) * (size // 8),
                edge_streams_rbytes(randomizer, size // 8),
            ):
                roots[index] = edge_streams_repeated(
                    Node(mcv2_reference.PATTERN, record=endpoints[index % 2] + bytes([orientation]) + axis),
                    size,
                )
                index += 1
    swapped = {
        index: Node(mcv2_reference.SKIP)
        if index % 3 == 0
        else edge_streams_repeated(
            Node(mcv2_reference.PATTERN, record=edge_streams_pattern_record(randomizer, 8)), 8
        )
        for index in roots
    }
    return [pack_frame(256, 96, 0, 0, roots), pack_frame(256, 96, 1, 0, swapped)]


def edge_streams_build_streams(seed=edge_streams_DEFAULT_SEED):
    randomizer = random.Random(seed)
    streams = {
        "edge-modes.mcs": edge_streams_mode_stream(randomizer),
        "edge-patterns.mcs": edge_streams_pattern_stream(randomizer),
    }
    for name, width, height in [
        ("tiny", 1, 1),
        ("vertical", 1, 97),
        ("horizontal", 97, 1),
        ("cropped", 97, 65),
        ("directory", 4096, 65),
    ]:
        streams[f"edge-{name}.mcs"] = edge_streams_random_stream(randomizer, width, height)
    streams["edge-absent.mcs"] = [pack_frame(33, 17, 0, 0, {}), pack_frame(33, 17, 1, 0, {})]
    motion = [
        pack_frame(
            33,
            17,
            0,
            0,
            {
                0: Node(mcv2_reference.SOLID, record=b"\xff\x80\x00"),
                1: Node(mcv2_reference.SOLID, record=b"\x04\x32\x96"),
            },
        )
    ]
    for index, vector in enumerate((b"\x80\x7f", b"\x7f\x80", b"\x80\x80", b"\x7f\x7f"), 1):
        motion.append(
            pack_frame(
                33,
                17,
                index,
                index - 1,
                {
                    0: Node(mcv2_reference.MOTION, record=vector),
                    1: edge_streams_repeated(Node(mcv2_reference.COMPACT, 2, vector + bytes(8)), 8),
                },
            )
        )
    streams["edge-motion.mcs"] = motion
    long_roots = {
        index: Node(
            mcv2_reference.SPLIT,
            children=tuple(
                Node(
                    mcv2_reference.SPLIT,
                    children=tuple(
                        Node(
                            mcv2_reference.COMPACT,
                            (index + entry_index) % (mcv2_reference.MAX_QUANTIZER + 1),
                            edge_streams_compact_record(randomizer),
                        )
                        for entry_index in range(4)
                    ),
                )
                for _ in range(4)
            ),
        )
        for index in range(440)
    }
    streams["edge-long-walk.mcs"] = [pack_frame(1024, 448, 0, 0, {}), pack_frame(1024, 448, 1, 0, long_roots)]
    roots = {index: edge_streams_repeated(Node(mcv2_reference.SKIP), 8) for index in range(4086)}
    streams["edge-max-splits.mcs"] = [pack_frame(4096, 4096, 0, 0, roots)]
    roots = {
        index: Node(mcv2_reference.PALETTE, record=edge_streams_rbytes(randomizer, 134))
        for index in range(965)
    }
    roots[965] = Node(mcv2_reference.PATTERN, record=edge_streams_pattern_record(randomizer, 32))
    roots.update(
        {
            index: Node(mcv2_reference.SOLID, record=edge_streams_rbytes(randomizer, 3))
            for index in range(966, 993)
        }
    )
    streams["edge-length-limit.mcs"] = [pack_frame(1024, 1024, 0, 0, roots)]
    streams["edge-wrap.mcs"] = [
        pack_frame(
            1,
            1,
            frame_id,
            frame_id if index == 0 else (frame_id - 1) & mcv2_reference.ID_MASK,
            {0: Node(mcv2_reference.SOLID, record=b"\x1b@\x80")} if index == 0 else {},
        )
        for index, frame_id in enumerate((0xFFFFFFFE, 0xFFFFFFFF, 0, 1))
    ]
    return streams


def edge_streams_generate(output, seed=edge_streams_DEFAULT_SEED):
    output.mkdir(parents=True, exist_ok=True)
    streams = edge_streams_build_streams(seed)
    for old in output.glob("edge-*.mcs"):
        if old.name not in streams:
            old.unlink()
    digests = {}
    for name, frames in streams.items():
        decoder = Decoder()
        digests[name] = [hashlib.sha256(decoder.accept(data).tobytes()).hexdigest() for data in frames]
        output.joinpath(name).write_bytes(archive_bytes(frames))
    output.joinpath("digests.json").write_text(json.dumps(digests, indent=1) + "\n")
    from rejection_cases import rejected_frames

    rejected = rejected_frames()
    for name, case in rejected.items():
        try:
            parse_frame(bytes.fromhex(case["frame"]))
        except ValueError as error:
            if case["reason"] not in str(error):
                raise ValueError(f"{name}: expected {case['reason']!r}, got {str(error)!r}") from error
        else:
            raise ValueError(f"{name}: rejected fixture was accepted")
    output.joinpath("rejected.json").write_text(json.dumps(rejected, indent=1) + "\n")
    print(
        json.dumps(
            {"streams": len(streams), "frames": sum(map(len, streams.values())), "rejected": len(rejected)}
        )
    )


def edge_streams_main():
    parser = argparse.ArgumentParser(
        description=(
            "Build deterministic v3 block-tree archives, RGB digests and a spec section 9 rejection catalog using "
            "the independent serializer. Exit 1 if a generated fixture fails validation; 2 on invalid options."
        ),
    )
    parser.add_argument("output", type=Path)
    parser.add_argument("seed", nargs="?", type=int, default=edge_streams_DEFAULT_SEED)
    arguments = parser.parse_args()
    edge_streams_generate(arguments.output, arguments.seed)


fixtures_PREFIX_LIMIT = 1_000_000


def archive_frames(data):
    offset = 0
    while offset < len(data):
        if offset + 4 > len(data):
            raise ValueError("truncated archive length")
        length = struct.unpack_from("<I", data, offset)[0]
        offset += 4
        if offset + length > len(data):
            raise ValueError("truncated archive frame")
        yield data[offset : offset + length]
        offset += length


def archive_bytes(chunks):
    return b"".join(struct.pack("<I", len(chunk)) + chunk for chunk in chunks)


def read_archive(path):
    return archive_frames(Path(path).read_bytes())


def fixtures_digests(data):
    decoder = Decoder()
    return [hashlib.sha256(decoder.accept(frame).tobytes()).hexdigest() for frame in archive_frames(data)]


def fixtures_write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=1) + "\n")


def fixtures_v3_stream(path):
    data = path.read_bytes()
    kept = list(archive_frames(data))
    if not kept:
        raise ValueError(f"{path}: empty archive")
    if kept[0][:5] != b"MCV2\x03":
        print(f"skip non-v3 stream: {path}", file=sys.stderr)
        return None
    return (data, kept)


def fixtures_conformance(root):
    output = root / "conformance"
    path = output / "digests.json"
    table = json.loads(path.read_text()) if path.exists() else {}
    updated = False
    for stream in sorted(output.glob("*.mcs")):
        result = fixtures_v3_stream(stream)
        if result is None:
            continue
        data, kept = result
        if len(data) > fixtures_PREFIX_LIMIT or not parse_frame(kept[0]).keyframe:
            raise ValueError(
                f"{stream}: conformance must start with a keyframe and fit within {fixtures_PREFIX_LIMIT} bytes"
            )
        table[stream.name] = dict(frames=len(kept), bytes=len(data), sha256_per_frame=fixtures_digests(data))
        updated = True
        print(f"{stream.name}: {len(kept)} v3 frames checked", file=sys.stderr)
    if updated:
        fixtures_write_json(path, table)


def fixtures_edge(root):
    edge_streams_generate(root / "edge")


def fixtures_pages(root):
    cases = []
    for stream in sorted((root / "conformance").glob("*.mcs")):
        result = fixtures_v3_stream(stream)
        if result is not None:
            cases.extend(
                (str(stream.relative_to(root)), index, frame) for index, frame in enumerate(result[1])
            )
    if cases:
        source = "committed v3 conformance streams"
        cases = [cases[index % len(cases)] for index in range(4)]
    else:
        source = "edge streams (no committed v3 conformance streams)"
        cases = [
            (
                f"edge/{name}.mcs",
                index,
                list(read_archive(root / "edge" / f"{name}.mcs"))[index],
            )
            for name, index in [("edge-modes", 0), ("edge-modes", 1), ("edge-tiny", 0), ("edge-long-walk", 1)]
        ]
    for name, index in (("edge-directory", 2), ("edge-length-limit", 0)):
        path = root / "edge" / f"{name}.mcs"
        if path.exists():
            cases.append((f"edge/{name}.mcs", index, list(read_archive(path))[index]))
    if len(cases) > 4 and source == "committed v3 conformance streams":
        source = "committed v3 conformance and edge streams"
    entries = []
    for stream, index, frame in cases:
        symbols = make_pages(frame, 7)
        entries.append(
            dict(
                stream=stream,
                frame=index,
                pages=[hashlib.sha256(page).hexdigest() for page in symbols],
                lengths=[len(page) for page in symbols],
                wire=wire_bytes(symbols),
                wire_full=wire_bytes(symbols, full_maps=True),
            )
        )
    fixtures_write_json(
        root / "conformance/pages.json", dict(source=source, stream_id=7, symbol_bits=6, frames=entries)
    )


def fixtures_encoder(root):
    checked = 0
    for stream in sorted((root / "encoder").glob("*.mcs")):
        result = fixtures_v3_stream(stream)
        if result is None:
            continue
        decoded = fixtures_digests(result[0])
        checked += 1
        print(f"{stream.name}: {len(decoded)} golden v3 frames decoded", file=sys.stderr)
    print(f"encoder: {checked} committed v3 golden streams checked; files unchanged", file=sys.stderr)


def fixtures_main():
    parser = argparse.ArgumentParser(
        description=(
            "Regenerate independent v3 RGB digests, transport pages and edge streams. Validate Java "
            "conformance/golden archives without rewriting them; leave non-v3 streams untouched. Archives repeat "
            "a little-endian u32 length and that many frame bytes. Exit 1 on validation failure; 2 on invalid "
            "options."
        ),
    )
    parser.add_argument("root", type=Path)
    parser.add_argument(
        "what", nargs="?", default="all", choices=("conformance", "edge", "pages", "encoder", "all")
    )
    arguments = parser.parse_args()
    steps = dict(
        conformance=fixtures_conformance, edge=fixtures_edge, pages=fixtures_pages, encoder=fixtures_encoder
    )
    for step in steps if arguments.what == "all" else [arguments.what]:
        steps[step](arguments.root)


differential_DEFAULT_CORPUS = (
    Path(__file__).resolve().parents[4] / "mcav-bukkit/src/test/resources/mcv2/conformance"
)


def differential_mutant(original, randomizer):
    mutated = [bytearray(frame) for frame in original]
    nonempty = [index for index, frame in enumerate(mutated) if frame]
    if not nonempty:
        raise ValueError("mutation requires a nonempty frame")
    if randomizer.random() < 0.15:
        victim = randomizer.choice(nonempty)
        mutated[victim] = mutated[victim][: randomizer.randrange(len(mutated[victim]))]
    else:
        for _ in range(randomizer.randint(1, 4)):
            victim = mutated[randomizer.choice(nonempty)]
            limit = min(len(victim), 256) if randomizer.random() < 0.7 else len(victim)
            victim[randomizer.randrange(limit)] ^= randomizer.randint(1, 255)
    if mutated == original:
        mutated[nonempty[0]][0] ^= 1
    return [bytes(frame) for frame in mutated]


def differential_reference_tokens(chunks):
    """Only ValueError is a rejection; unexpected reference exceptions must fail the comparison."""
    decoder, tokens = (Decoder(), [])
    for frame in chunks:
        try:
            tokens.append(hashlib.sha256(decoder.accept(frame).tobytes()).hexdigest())
        except ValueError:
            tokens.append("reject")
    return tokens


def differential_build_archives(arguments):
    randomizer = random.Random(arguments.seed)
    archives = {
        f"tree-{index:04d}": edge_streams_random_stream(randomizer) for index in range(arguments.streams)
    }
    corpus = sorted(arguments.corpus.glob("*.mcs"))
    if arguments.conformance and (not corpus):
        raise ValueError(f"no committed conformance archives in {arguments.corpus}")
    for index in range(arguments.conformance):
        path = corpus[index % len(corpus)]
        chunks = list(read_archive(path))
        if not chunks:
            raise ValueError(f"empty conformance archive: {path}")
        archives[f"conformance-{index:04d}-{path.stem}"] = chunks
    for name, chunks in list(archives.items()):
        for index in range(arguments.mutants):
            archives[f"{name}-mutant-{index}"] = differential_mutant(chunks, randomizer)
    return archives


def differential_compare(expected, actual):
    counts = dict(archives=len(expected), frames=0, decoded=0, refused=0, disagreements=0)
    disagreements = []
    for name, expected_tokens in expected.items():
        java = actual.get(name, [])
        if len(java) != len(expected_tokens):
            disagreements.append(
                dict(archive=name, reason="frame count", mcav=len(java), reference=len(expected_tokens))
            )
        for index, token in enumerate(expected_tokens):
            counts["frames"] += 1
            other = java[index] if index < len(java) else None
            if other != token:
                disagreements.append(dict(archive=name, frame=index, mcav=other, reference=token))
            else:
                counts["refused" if token == "reject" else "decoded"] += 1
    for name in actual.keys() - expected.keys():
        disagreements.append(dict(archive=name, reason="unexpected archive from Java"))
    counts["disagreements"] = len(disagreements)
    return (counts, disagreements)


def differential_run(arguments):
    archives = differential_build_archives(arguments)
    output = arguments.out
    (output / "archives").mkdir(parents=True, exist_ok=True)
    paths = {}
    for name, chunks in archives.items():
        paths[name] = output / "archives" / f"{name}.mcs"
        paths[name].write_bytes(archive_bytes(chunks))
    expected = {name: differential_reference_tokens(chunks) for name, chunks in archives.items()}
    java = subprocess.run(
        [
            arguments.java,
            "-cp",
            arguments.classpath,
            "me.brandonli.mcav.bukkit.media.mcv2.Mcv2Tools",
            "digests",
        ]
        + [str(path) for path in paths.values()],
        capture_output=True,
        text=True,
    )
    if java.returncode:
        print(java.stderr, file=sys.stderr)
        return 2
    actual = {}
    for line in java.stdout.splitlines():
        path, *tokens = line.split(" ")
        name = Path(path).stem
        if name in actual:
            raise ValueError(f"duplicate Java output for {name}")
        actual[name] = tokens
    counts, disagreements = differential_compare(expected, actual)
    summary = dict(seed=arguments.seed, counts=counts, disagreements=disagreements)
    (output / "summary.json").write_text(json.dumps(summary, indent=1) + "\n")
    print(json.dumps(counts))
    return 1 if disagreements else 0


def differential_main():
    parser = argparse.ArgumentParser(
        description=(
            "Require identical Python/Java v3 acceptance and RGB digests for random trees, conformance streams "
            "and mutations. Only ValueError counts as a reference rejection. Exit 1 on disagreement or another "
            "reference exception; 2 on a failed Java process or invalid options."
        ),
    )
    parser.add_argument("classpath")
    parser.add_argument("--streams", type=int, default=200, help="random-tree archives")
    parser.add_argument(
        "--conformance", type=int, default=40, help="committed archives, cycling when necessary"
    )
    parser.add_argument("--corpus", type=Path, default=differential_DEFAULT_CORPUS)
    parser.add_argument("--mutants", type=int, default=1, help="mutated copies of every archive")
    parser.add_argument("--seed", type=int, default=20260926)
    parser.add_argument("--out", type=Path, default=Path("build/mcv2-differential"))
    parser.add_argument("--java", default="java")
    arguments = parser.parse_args()
    if (
        min(arguments.streams, arguments.conformance, arguments.mutants) < 0
        or arguments.streams + arguments.conformance == 0
    ):
        parser.error("archive counts must be nonnegative and at least one archive is required")
    sys.exit(differential_run(arguments))


def rate_quality_vmaf(ffmpeg, source, decoded, width, height, frames, fps):
    with tempfile.TemporaryDirectory() as folder:
        log = os.path.join(folder, "vmaf.json")
        raw = [
            "-f",
            "rawvideo",
            "-pixel_format",
            "rgb24",
            "-video_size",
            f"{width}x{height}",
            "-framerate",
            str(fps),
        ]
        graph = vmaf_filter(log, os.cpu_count())
        subprocess.run(
            [
                ffmpeg,
                "-nostdin",
                "-v",
                "error",
                "-y",
                *raw,
                "-i",
                source,
                *raw,
                "-i",
                decoded,
                "-frames:v",
                str(frames),
                "-lavfi",
                graph,
                "-f",
                "null",
                "-",
            ],
            check=True,
        )
        scores = [frame["metrics"]["vmaf"] for frame in json.load(open(log))["frames"]]
        return (sum(scores) / len(scores), min(scores))


def rate_quality_main():
    parser = argparse.ArgumentParser(
        description=(
            "Run the Java benchmark at each lambda on a raw RGB24 source, score its decoded pictures with "
            "libvmaf, and write rates, PSNR, timings and VMAF mean/minimum as a JSON curve. Arguments after -- "
            "pass through to the encoder. Exit 1 on benchmark/scoring failure; 2 on invalid options."
        ),
    )
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--main", default="me.brandonli.mcav.bukkit.media.mcv2.Mcv2Tools")
    parser.add_argument("--source", required=True)
    parser.add_argument("--width", type=int, default=1920)
    parser.add_argument("--height", type=int, default=1080)
    parser.add_argument("--frames", type=int, default=60)
    parser.add_argument("--fps", type=float, default=60)
    parser.add_argument("--lambdas", required=True)
    parser.add_argument("--ffmpeg", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--out", required=True)
    parser.add_argument("encoder", nargs="*")
    arguments = parser.parse_args()
    points = []
    with tempfile.TemporaryDirectory() as folder:
        decoded = os.path.join(folder, "decoded.rgb")
        for value in arguments.lambdas.split(","):
            command = [
                arguments.java,
                "--enable-native-access=ALL-UNNAMED",
                "-Xmx8g",
                "-cp",
                arguments.classpath,
                arguments.main,
                *(["bench"] if arguments.main.endswith("Mcv2Tools") else []),
                f"source={arguments.source}",
                f"width={arguments.width}",
                f"height={arguments.height}",
                f"frames={arguments.frames}",
                f"fps={arguments.fps}",
                f"lambda={value}",
                f"decoded={decoded}",
                *arguments.encoder,
            ]
            result = subprocess.run(command, check=True, capture_output=True, text=True)
            point = json.loads(result.stdout.strip().splitlines()[-1])
            point["lambda"] = float(value)
            point["encoder"] = " ".join(arguments.encoder)
            point["vmaf_mean"], point["vmaf_min"] = rate_quality_vmaf(
                arguments.ffmpeg,
                arguments.source,
                decoded,
                arguments.width,
                arguments.height,
                arguments.frames,
                arguments.fps,
            )
            points.append(point)
            print(json.dumps(point), file=sys.stderr)
    with open(arguments.out, "w") as out:
        json.dump(points, out, indent=1)
        out.write("\n")


SPIRV_HELP = (
    "run compiled Mcv2Tools shader-compile; use the test runtime classpath from "
    "mcav-bukkit/build/mcv2-tools-classpath.txt"
)


shader_check_ROOT = Path(__file__).resolve().parents[4]
shader_check_PACK = shader_check_ROOT / "mcav-bukkit/src/main/resources/mcav/mcv2/pack"
shader_check_SCREEN = (1920, 1080)
shader_check_STREAM_ID = 7
shader_check_SCREEN_INDEX = 0
shader_check_FIRST_SLOT = 0
shader_check_IDLE_SLOTS = 8
shader_check_VERTEX = """#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) out vec2 texCoord;
void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
    texCoord = uv;
}
"""


def shader_check_cells_width(width):
    return (width + 7) // 8


def shader_check_cells_height(height):
    return (height + 7) // 8


def shader_check_placeholders(width, height, slots):
    capacity = 12256
    return {
        "VIDEO_WIDTH": width,
        "VIDEO_HEIGHT": height,
        "BYTES_WIDTH": 128,
        "BYTES_HEIGHT": (slots * capacity // 4 + 127) // 128,
        "PAGES_WIDTH": 4 * slots,
        "CRC_WIDTH": 64 * slots,
        "CELLS_WIDTH": shader_check_cells_width(width),
        "CELLS_HEIGHT": shader_check_cells_height(height) + 1,
    }


def shader_check_post_chain(width, height, slots):
    template_path = shader_check_PACK.parent / "chain.json"
    text = template_path.read_text().replace("@S@", str(shader_check_SCREEN_INDEX))
    for name, value in shader_check_placeholders(width, height, slots).items():
        text = text.replace("@%s@" % name, str(value))
    template = json.loads(text)
    targets = dict(template["screen_targets"])
    targets.update(template["targets"])
    return {"targets": targets, "passes": template["decode"] + template["draw"] + template["tail"]}


shader_check_BLIT = """#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location = 0) out vec4 fragColor;
void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
}
"""


def shader_check_screens_config(slots):
    if shader_check_SCREEN_INDEX == 0:
        return [
            "const int MCV2_SCREENS = 1;",
            "const int MCV2_TOTAL_SLOTS = %d;" % slots,
            "const uint MCV2_SCREEN_STREAMS[1] = uint[1](%du);" % shader_check_STREAM_ID,
            "const int MCV2_SCREEN_SLOTS[1] = int[1](%d);" % slots,
            "const int MCV2_SCREEN_FIRST_SLOTS[1] = int[1](0);",
        ]
    return [
        "const int MCV2_SCREENS = 2;",
        "const int MCV2_TOTAL_SLOTS = %d;" % (shader_check_IDLE_SLOTS + slots),
        "const uint MCV2_SCREEN_STREAMS[2] = uint[2](1u, %du);" % shader_check_STREAM_ID,
        "const int MCV2_SCREEN_SLOTS[2] = int[2](%d, %d);" % (shader_check_IDLE_SLOTS, slots),
        "const int MCV2_SCREEN_FIRST_SLOTS[2] = int[2](0, %d);" % shader_check_IDLE_SLOTS,
    ]


def shader_check_generated(width, height, slots):
    return {
        "mcav:mcv2_config.glsl": "\n".join(
            shader_check_screens_config(slots)
            + ["const bool MCV2_DEBUG_VIEW = false;", "const ivec3 MCV2_OUTLINE_COLOR = ivec3(0, 0, 0);", ""]
        ),
        "mcav:mcv2_screen.glsl": "\n".join(
            [
                "const int MCV2_SCREEN_INDEX = %d;" % shader_check_SCREEN_INDEX,
                "const int MCV2_PAGE_SLOTS = %d;" % slots,
                "const int MCV2_FIRST_SLOT = %d;" % shader_check_FIRST_SLOT,
                "const uint MCV2_STREAM_ID = %du;" % shader_check_STREAM_ID,
                "const int MCV2_VIDEO_WIDTH = %d;" % width,
                "const int MCV2_VIDEO_HEIGHT = %d;" % height,
                "const int MCV2_BYTES_WIDTH = 128;",
                "const int MCV2_BYTES_HEIGHT = %d;"
                % shader_check_placeholders(width, height, slots)["BYTES_HEIGHT"],
                "const int MCV2_CELLS_WIDTH = %d;" % shader_check_cells_width(width),
                "const int MCV2_CELLS_HEIGHT = %d;" % shader_check_cells_height(height),
                "const int MCV2_DEBUG_TOP = 0;",
                "",
            ]
        ),
    }


def shader_check_resolve(source, includes, seen=None):
    seen = set() if seen is None else seen

    def include(match):
        name = match.group(1)
        if name in seen:
            return ""
        seen.add(name)
        if name in includes:
            text = includes[name]
        else:
            namespace, path = name.split(":")
            text = (shader_check_PACK / "assets" / namespace / "shaders/include" / path).read_text()
        return shader_check_resolve(text, includes, seen)

    return re.sub("^#include <([^>]+)>", include, source, flags=re.M)


def shader_check_desktop(source):
    """The pack's GLSL for a desktop OpenGL 3.3 compiler: Minecraft 26.3 compiles it for Vulkan, whose vertex index is
    gl_VertexIndex."""
    return source.replace("gl_VertexIndex", "gl_VertexID")


class ShaderChain:
    def __init__(self, context, width, height, slots):
        self.context = context
        self.width, self.height, self.slots = (width, height, slots)
        self.includes = shader_check_generated(width, height, slots)
        self.programs = {}
        self.framebuffers = {}
        self.arrays = {}
        chain = shader_check_post_chain(width, height, slots)
        self.passes = chain["passes"]
        make = lambda width, height: context.texture((width, height), 4, dtype="f1")
        self.main = make(*shader_check_SCREEN)
        self.depth = context.depth_texture(shader_check_SCREEN)
        self.depth.compare_func = ""
        framebuffer = context.framebuffer(depth_attachment=self.depth)
        framebuffer.clear(depth=0.0)
        framebuffer.release()
        self.targets = {"minecraft:main": self.main, "minecraft:entity_outline": make(*shader_check_SCREEN)}
        self.persistent = []
        for name, spec in chain["targets"].items():
            self.targets[name] = make(
                spec.get("width", shader_check_SCREEN[0]), spec.get("height", shader_check_SCREEN[1])
            )
            if spec.get("persistent"):
                self.persistent.append(name)
        for texture in self.targets.values():
            texture.filter = (context.NEAREST, context.NEAREST)
            texture.write(bytes(texture.width * texture.height * 4))
        self.blit = context.program(vertex_shader=shader_check_VERTEX, fragment_shader=shader_check_BLIT)
        self.compiled = None

    def target(self, name):
        return self.targets[name if ":" in name else "mcav:mcv2_%s_%d" % (name, shader_check_SCREEN_INDEX)]

    def program(self, name, vertex="minecraft:core/screenquad"):
        key = (name, vertex)
        if key not in self.programs:
            if self.compiled is not None:
                source = (self.compiled / (name + ".fsh")).read_text()
            else:
                source = shader_check_desktop(
                    shader_check_resolve(
                        (shader_check_PACK / "assets/mcav/shaders/post" / (name + ".fsh")).read_text(),
                        self.includes,
                    )
                )
            if vertex == "minecraft:core/screenquad":
                vertex_source = shader_check_VERTEX
            elif self.compiled is not None:
                vertex_source = (self.compiled / (vertex.split("/")[-1] + ".vsh")).read_text()
            else:
                vertex_source = shader_check_desktop(
                    shader_check_resolve(
                        (
                            shader_check_PACK / "assets/mcav/shaders/post" / (vertex.split("/")[-1] + ".vsh")
                        ).read_text(),
                        self.includes,
                    )
                )
            self.programs[key] = self.context.program(vertex_shader=vertex_source, fragment_shader=source)
        return self.programs[key]

    def draw(self, program, inputs, output):
        for unit, (sampler, texture) in enumerate(inputs.items()):
            texture.use(unit)
            if sampler + "Sampler" in program:
                program[sampler + "Sampler"].value = unit
        if output not in self.framebuffers:
            self.framebuffers[output] = self.context.framebuffer(color_attachments=[self.targets[output]])
        self.framebuffers[output].use()
        if id(program) not in self.arrays:
            self.arrays[id(program)] = self.context.vertex_array(program, [])
        self.arrays[id(program)].render(mode=self.context.TRIANGLES, vertices=3)

    def steps(self):
        """The passes this harness runs, in order: (name, program, inputs, output). Minecraft's own outline passes
        (sobel, box blurs) only touch its outline target and are left out."""
        steps = []
        for index, step in enumerate(self.passes):
            shader = step["fragment_shader"]
            inputs = {}
            for entry in step.get("inputs", []):
                texture = self.depth if entry.get("use_depth_buffer") else self.targets[entry["target"]]
                inputs[entry["sampler_name"]] = texture
            if shader == "minecraft:post/blit":
                name = "blit %s -> %s" % (
                    step["inputs"][0]["target"].split(":")[-1],
                    step["output"].split(":")[-1],
                )
                steps.append((name, self.blit, inputs, step["output"]))
            elif shader.startswith("mcav:post/"):
                program = self.program(
                    shader.split("/")[-1], step.get("vertex_shader", "minecraft:core/screenquad")
                )
                steps.append((shader.split("/")[-1], program, inputs, step["output"]))
        return steps

    def reset(self):
        for name in self.persistent:
            texture = self.targets[name]
            texture.write(bytes(texture.width * texture.height * 4))

    def show(self, pages):
        screen = numpy.zeros((shader_check_SCREEN[1], shader_check_SCREEN[0], 4), numpy.uint8)
        rows = (4096 + shader_check_SCREEN[0] - 1) // shader_check_SCREEN[0]
        for page in pages:
            symbols = numpy.zeros(16384, numpy.uint32)
            symbols[: len(page)] = numpy.frombuffer(page, numpy.uint8)
            bits = symbols[0::4] | symbols[1::4] << 6 | symbols[2::4] << 12 | symbols[3::4] << 18
            packed = numpy.stack(
                [bits & 255, bits >> 8 & 255, bits >> 16, numpy.full_like(bits, 255)], axis=1
            ).astype(numpy.uint8)
            strip = numpy.zeros((rows * shader_check_SCREEN[0], 4), numpy.uint8)
            strip[:4096] = packed
            slot = shader_check_FIRST_SLOT + shader_check_page_number(page)
            for row in range(rows):
                screen[shader_check_SCREEN[1] - 1 - (slot * rows + row)] = strip[
                    row * shader_check_SCREEN[0] : (row + 1) * shader_check_SCREEN[0]
                ]
        self.main.write(screen.tobytes())

    def frame(self):
        for _, program, inputs, output in self.steps():
            self.draw(program, inputs, output)
        status = numpy.frombuffer(self.target("status").read(), numpy.uint8)
        picture = numpy.frombuffer(self.target("previous").read(), numpy.uint8).reshape(
            self.height, self.width, 4
        )[:, :, :3]
        return (bool(status[0]), picture)


def shader_check_compile_via_spirv(includes, classpath):
    work = Path(tempfile.mkdtemp(prefix="mcv2-spirv-"))
    generated = work / "generated"
    generated.mkdir()
    for name, text in includes.items():
        (generated / name.split(":")[1]).write_text(text)
    tool = "me.brandonli.mcav.bukkit.media.mcv2.Mcv2Tools"
    command = [
        "java",
        "--enable-native-access=ALL-UNNAMED",
        "-cp",
        classpath,
        tool,
        "shader-compile",
        str(shader_check_PACK),
        str(generated),
        str(work / "glsl"),
        "--post-only",
    ]
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL)
    return work / "glsl"


def shader_check_page_number(page):
    value = 0
    for bit in range(16):
        symbol_bit = 128 + bit
        value |= (page[symbol_bit // 6] >> symbol_bit % 6 & 1) << bit
    return value


def shader_check_check_restart(context, slots, classpath=None):
    """Known pictures and decisions: a restarted keyframe can move backwards, P frames cannot."""

    red, green, blue = ((201, 19, 31), (7, 231, 49), (23, 57, 211))
    cases = [
        (100, 100, blue, True, blue),
        (7, 7, red, True, red),
        (7, 7, green, False, red),
        (6, 7, green, False, red),
        (0x80000007, 7, green, False, red),
        (8, 100, green, False, red),
        (8, 7, green, True, green),
        (0, 0, blue, True, blue),
        (1, 0, red, True, red),
    ]
    chain = ShaderChain(context, 32, 32, slots)
    if classpath:
        chain.compiled = shader_check_compile_via_spirv(chain.includes, classpath)
    failures = committed = 0
    held_id = None
    for index, (frame_id, reference_id, color, should_decode, expected_color) in enumerate(cases):
        data = pack_frame(32, 32, frame_id, reference_id, {0: Node(SOLID, record=bytes(color))})
        chain.show(make_pages(data, shader_check_STREAM_ID, 6))
        did, picture = chain.frame()
        if should_decode:
            held_id = frame_id
            committed += 1
        state = struct.unpack("<4I", chain.target("state").read())
        expected = numpy.full((32, 32, 3), expected_color, numpy.uint8)
        if (
            did != should_decode
            or not numpy.array_equal(picture, expected)
            or state[1] != held_id
            or (state[3] != committed)
        ):
            failures += 1
            print(
                "  restart case %d (id %d): decoded %s, expected %s; held id %d, expected %d; commits %d, expected %d"
                % (index, frame_id, did, should_decode, state[1], held_id, state[3], committed)
            )
    print("keyframe restart: %d checks, %d wrong" % (len(cases), failures))
    return failures


def shader_check_main():
    parser = argparse.ArgumentParser(
        description=(
            "Run the pack post chain outside Minecraft and require byte-exact reference pictures and decode "
            "decisions. --drop exercises missing frames; --restart-check exercises keyframe restart ordering. "
            "Frames with more pages than slots cannot be decoded, as Mcv2Channel.send refuses them. Exit 1 on a "
            "failed check; 2 on invalid options."
        ),
    )
    parser.add_argument("streams", nargs="+")
    parser.add_argument("--slots", type=int, default=4)
    parser.add_argument("--drop", type=int, default=0)
    parser.add_argument("--backend", choices=("egl", "glx"), default=None)
    parser.add_argument("--pack", type=Path, help="another pack source folder with its sibling chain.json")
    parser.add_argument("--spirv", metavar="CLASSPATH", help=SPIRV_HELP)
    parser.add_argument(
        "--second-screen", action="store_true", help="play on the second screen of a two-screen pack"
    )
    parser.add_argument(
        "--restart-check", action="store_true", help="also check keyframe restart and P-frame ordering"
    )
    arguments = parser.parse_args()
    if arguments.second_screen:
        global shader_check_SCREEN_INDEX, shader_check_FIRST_SLOT
        shader_check_SCREEN_INDEX, shader_check_FIRST_SLOT = (1, shader_check_IDLE_SLOTS)
    if arguments.pack:
        global shader_check_PACK
        shader_check_PACK = arguments.pack
    import moderngl

    context = moderngl.create_standalone_context(
        require=330, **{"backend": "egl"} if arguments.backend == "egl" else {}
    )
    print(context.info["GL_RENDERER"])
    failures = 0
    for stream in arguments.streams:
        chain = None
        last_id = None
        shown = None
        decoded = skipped = wrong = 0
        for index, frame in enumerate(read_archive(stream)):
            width, height = struct.unpack_from("<HH", frame, 8)
            frame_id, reference_id = struct.unpack_from("<II", frame, 12)
            keyframe = frame_id == reference_id
            if chain is None:
                chain = ShaderChain(context, width, height, arguments.slots)
                if arguments.spirv:
                    chain.compiled = shader_check_compile_via_spirv(chain.includes, arguments.spirv)
            if arguments.drop and index % arguments.drop == arguments.drop - 1:
                continue
            pages = make_pages(frame, shader_check_STREAM_ID, 6)
            # Mcv2Channel.send refuses a frame with more pages than slots, so the client never receives it.
            delta = None if last_id is None else (frame_id - last_id) & 0xFFFFFFFF
            newer = delta is None or (delta > 0 and (keyframe or delta < 0x80000000))
            decodable = (
                newer
                and (keyframe or reference_id == last_id)
                and ((width, height) == (chain.width, chain.height))
                and (len(pages) <= arguments.slots)
            )
            chain.show(pages[: arguments.slots])
            did, picture = chain.frame()
            if did != decodable:
                wrong += 1
                print(
                    "  frame %d: the chain %s it, the client model %s"
                    % (index, "decoded" if did else "skipped", "can" if decodable else "cannot")
                )
                continue
            if did:
                decoded += 1
                expected = decode(frame, None if keyframe else shown, last_id)
                shown = expected
                last_id = frame_id
                if not numpy.array_equal(picture, expected):
                    wrong += 1
                    difference = numpy.abs(picture.astype(int) - expected.astype(int))
                    print(
                        "  frame %d differs: %d pixels, max %d"
                        % (index, int((difference.max(axis=2) > 0).sum()), difference.max())
                    )
            else:
                skipped += 1
                if shown is not None and (not numpy.array_equal(picture, shown)):
                    wrong += 1
                    print("  frame %d was skipped but the picture changed" % index)
        print("%s: %d frames decoded, %d skipped, %d wrong" % (Path(stream).name, decoded, skipped, wrong))
        failures += wrong
    if arguments.restart_check:
        failures += shader_check_check_restart(context, arguments.slots, arguments.spirv)
    sys.exit(1 if failures else 0)


def shader_timing_perspective(fov_y, aspect, near, far):
    """An OpenGL projection matrix, column-major like GLSL's mat4 constructor order."""
    focal_length = 1.0 / numpy.tan(numpy.radians(fov_y) / 2)
    matrix = numpy.zeros((4, 4), numpy.float32)
    matrix[0, 0] = focal_length / aspect
    matrix[1, 1] = focal_length
    matrix[2, 2] = (far + near) / (near - far)
    matrix[2, 3] = -1.0
    matrix[3, 2] = 2 * far * near / (near - far)
    return matrix


def shader_timing_descriptor_row(width):
    top_left, right, down, cells = ((-3.0, 1.5, -3.0), (1.0, 0.0, 0.0), (0.0, -1.0, 0.0), (6.0, 3.0))
    floats = [*top_left, cells[0], *right, cells[1], *down, 0.0]
    floats += list(shader_timing_perspective(70.0, 16 / 9, 0.05, 1000.0).reshape(-1))
    row = numpy.zeros((width, 4), numpy.uint8)
    row[:, 3] = 255
    row[0, :3] = tuple(b"MCV")
    row[1, 0] = 0xA1
    for index, value in enumerate(floats):
        packed = struct.pack("<f", value)
        row[2 + index * 2, :3] = (packed[0], packed[1], packed[2])
        row[3 + index * 2, 0] = packed[3]
    return row


class TimedShaderChain(ShaderChain):
    def __init__(self, context, width, height, slots):
        super().__init__(context, width, height, slots)
        self.queries = {}

    def show(self, pages):
        super().show(pages)
        rows = (4096 + shader_check_SCREEN[0] - 1) // shader_check_SCREEN[0]
        row = (
            shader_check_SCREEN[1]
            - 1
            - (shader_check_FIRST_SLOT + self.slots) * rows
            - shader_check_SCREEN_INDEX
        )
        self.main.write(
            shader_timing_descriptor_row(shader_check_SCREEN[0]).tobytes(),
            viewport=(0, row, shader_check_SCREEN[0], 1),
        )

    def warm(self):
        """Keeps the GPU busy for a few milliseconds, untimed, as a client drawing its world would: after the harness's
        own uploads and read-backs the GPU would otherwise start the chain at an idle clock."""
        for _ in range(20):
            self.draw(self.blit, {"In": self.main}, "mcav:mcv2_screen")

    def timed_frame(self, repeats):
        self.warm()
        times = {}
        for name, program, inputs, output in self.steps():
            if name.startswith("blit "):
                self.draw(program, inputs, output)
                continue
            if name == "mcv2_copy":
                name += " -> " + output.split(":")[-1].removeprefix("mcv2_")
            if name not in self.queries:
                self.queries[name] = self.context.query(time=True)
            query = self.queries[name]
            count = repeats if name == "mcv2_decode" else 1
            with query:
                for _ in range(count):
                    self.draw(program, inputs, output)
            times[name] = query.elapsed / count / 1e6
        status = numpy.frombuffer(self.target("status").read(), numpy.uint8)
        return (bool(status[0]), times)


def shader_timing_summarize(samples, names):
    out = {}
    for name in names + ("total",):
        values = numpy.array([sample[name] for sample in samples]) if samples else numpy.zeros(1)
        out[name] = dict(mean=float(values.mean()), p95=float(numpy.percentile(values, 95)), n=len(samples))
    return out


def shader_timing_main():
    global shader_check_PACK
    parser = argparse.ArgumentParser(
        description=(
            "Time GPU passes for new and repeated pages; the first round warms up and is excluded. Exit 1 if a "
            "frame does not decode on arrival, decodes again from the same pages, or gives different pixels in a "
            "later round; 2 on invalid options. --reference supplies an archived v2 mcvideo package."
        ),
    )
    parser.add_argument("streams", nargs="+")
    parser.add_argument("--backend", choices=("egl", "glx"), default=None)
    parser.add_argument("--slots", type=int, default=4)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--pack", type=Path, help="another pack source folder, with its sibling chain.json")
    parser.add_argument("--reference", type=Path, help="reference package root for an archived v2 baseline")
    parser.add_argument("--spirv", metavar="CLASSPATH", help=SPIRV_HELP)
    arguments = parser.parse_args()
    if arguments.rounds < 2 or arguments.repeats < 1 or arguments.slots < 1:
        parser.error("need at least two rounds, one repeat and one page slot")
    if arguments.pack:
        shader_check_PACK = arguments.pack
    import moderngl

    if arguments.reference:
        sys.path.insert(0, str(arguments.reference))
        from mcvideo.transport import make_pages as page_maker
    else:
        page_maker = make_pages
    options = {"backend": arguments.backend} if arguments.backend == "egl" else {}
    context = moderngl.create_standalone_context(require=330, **options)
    renderer = context.info["GL_RENDERER"]
    print(renderer, context.info["GL_VERSION"])
    report = dict(
        renderer=renderer,
        version=context.info["GL_VERSION"],
        rounds=arguments.rounds,
        repeats=arguments.repeats,
        streams={},
    )
    failures = 0
    for stream in arguments.streams:
        frames = list(read_archive(stream))
        width, height = struct.unpack_from("<HH", frames[0], 8)
        chain = TimedShaderChain(context, width, height, arguments.slots)
        pages = [page_maker(frame, shader_check_STREAM_ID, 6) for frame in frames]
        keyframes = [
            frame[12:16] == frame[16:20] if frame[4] == 3 else bool(frame[6] & 1) for frame in frames
        ]
        if arguments.spirv:
            chain.compiled = shader_check_compile_via_spirv(chain.includes, arguments.spirv)
        first_pictures = None
        new_key, new_p, idle = ([], [], [])
        mismatches = 0
        for round_index in range(arguments.rounds):
            chain.reset()
            pictures = []
            for index, page_list in enumerate(pages):
                chain.show(page_list)
                decoded, times = chain.timed_frame(arguments.repeats)
                names = tuple(times)
                times["total"] = sum(times[name] for name in names)
                chain.show(page_list)
                shown_again, again = chain.timed_frame(1)
                again["total"] = sum(again[name] for name in names)
                pictures.append(chain.target("previous").read())
                if not decoded or shown_again:
                    failures += 1
                    print("  frame %d: decoded %s, decoded again %s" % (index, decoded, shown_again))
                if round_index > 0:
                    (new_key if keyframes[index] else new_p).append(times)
                    idle.append(again)
            if first_pictures is None:
                first_pictures = pictures
            else:
                mismatches += sum(
                    1 for first_picture, picture in zip(first_pictures, pictures) if first_picture != picture
                )
        result = dict(
            width=width,
            height=height,
            frames=len(frames),
            mismatches=mismatches,
            new_keyframe=shader_timing_summarize(new_key, names),
            new_p=shader_timing_summarize(new_p, names),
            idle=shader_timing_summarize(idle, names),
        )
        report["streams"][Path(stream).name] = result
        failures += mismatches
        print(
            "%s (%dx%d, %d frames, %d rounds counted, %d picture mismatches between rounds)"
            % (Path(stream).name, width, height, len(frames), arguments.rounds - 1, mismatches)
        )
        print(
            "  %-24s %22s %22s %22s"
            % ("pass (ms)", "new P frame mean/p95", "new keyframe mean/p95", "no new video mean/p95")
        )
        for name in names + ("total",):
            cells = [result[kind][name] for kind in ("new_p", "new_keyframe", "idle")]
            print(
                "  %-24s %22s %22s %22s"
                % (name, *("%9.3f / %9.3f" % (cell["mean"], cell["p95"]) for cell in cells))
            )
    if arguments.json:
        arguments.json.write_text(json.dumps(report, indent=2))
    sys.exit(1 if failures else 0)


strip_check_PAGE_PIXELS = PAGE_SYMBOLS // 4
strip_check_HEADER_SYMBOLS = (PAGE_HEADER.size * 8 + SYMBOL_BITS - 1) // SYMBOL_BITS
strip_check_SQUARE = 20
strip_check_STEP = 24
strip_check_GREEN, strip_check_RED, strip_check_BLUE = ((0, 255, 0), (255, 0, 0), (0, 0, 255))
strip_check_DESCRIPTOR = [tuple(b"MCV"), (0xA1, 0, 0)]


def strip_check_page_symbols(screen, slot, rows):
    pixels = (
        screen[slot * rows : (slot + 1) * rows].reshape(-1, 3)[:strip_check_PAGE_PIXELS].astype(numpy.uint32)
    )
    bits = pixels[:, 0] | pixels[:, 1] << 8 | pixels[:, 2] << 16
    return numpy.stack([bits >> shift & 63 for shift in (0, 6, 12, 18)], axis=1).astype(numpy.uint8).ravel()


def strip_check_read_strip_page(symbols):
    if len(symbols) < strip_check_HEADER_SYMBOLS:
        raise ValueError("truncated strip page header")
    bits = (
        (symbols[:strip_check_HEADER_SYMBOLS, None] >> numpy.arange(SYMBOL_BITS) & 1)
        .astype(numpy.uint8)
        .ravel()
    )
    fields = PAGE_HEADER.unpack(numpy.packbits(bits[: PAGE_HEADER.size * 8], bitorder="little").tobytes())
    number, total = (fields[6], fields[9])
    size = min(page_capacity(), max(total - number * page_capacity(), 0))
    length = ((PAGE_HEADER.size + size) * 8 + SYMBOL_BITS - 1) // SYMBOL_BITS
    return read_page(symbols[:length].tobytes())


def strip_check_main():
    parser = argparse.ArgumentParser(
        description=(
            "Validate six-bit transport pages, anchor descriptors, decision squares and monotonic decoded-frame "
            "counters in debug-view PNG screenshots. Exit 1 if any check fails; 2 if no PNG screenshots exist or "
            "options are invalid."
        ),
    )
    parser.add_argument("captures", type=Path)
    parser.add_argument("--slots", type=int, required=True)
    parser.add_argument("--video-width", type=int, required=True)
    parser.add_argument(
        "--screens", type=int, default=1, help="number of screens sharing the strip (default: 1)"
    )
    parser.add_argument(
        "--screen", type=int, default=0,
        help="zero-based screen index for its anchor descriptor (default: 0)",
    )
    parser.add_argument(
        "--first-slot", type=int, default=0, help="first global page slot for this screen (default: 0)"
    )
    parser.add_argument(
        "--total-slots", type=int, help="total page slots across all screens (defaults to --slots)"
    )
    parser.add_argument(
        "--debug-top", type=int, default=0,
        help="rows between the anchor descriptors and debug view (default: 0)",
    )
    arguments = parser.parse_args()
    from PIL import Image

    total_slots = arguments.total_slots if arguments.total_slots is not None else arguments.slots
    captures = sorted(arguments.captures.glob("*.png"))
    if not captures:
        parser.error("%s holds no PNG screenshots" % arguments.captures)
    valid, invalid, frames = (Counter(), Counter(), set())
    descriptors, decisions, failures, counts = (0, Counter(), [], [])
    squared = 0
    for capture in captures:
        screen = numpy.asarray(Image.open(capture).convert("RGB"))
        width = screen.shape[1]
        rows = (strip_check_PAGE_PIXELS + width - 1) // width
        pages = {}
        for slot in range(arguments.slots):
            try:
                page = strip_check_read_strip_page(
                    strip_check_page_symbols(screen, arguments.first_slot + slot, rows)
                )
                pages[slot] = True
                valid[slot] += 1
                frames.add((page.frame_id, page.number))
            except ValueError as error:
                pages[slot] = False
                invalid[slot, str(error)] += 1
        descriptor_row = total_slots * rows + arguments.screen
        if [
            tuple(int(channel) for channel in screen[descriptor_row, column]) for column in range(2)
        ] == strip_check_DESCRIPTOR:
            descriptors += 1
        else:
            failures.append((capture.name, "descriptor"))
        top = total_slots * rows + arguments.screens + arguments.debug_top
        if arguments.video_width + 8 + (arguments.slots + 5) * strip_check_STEP > width:
            continue
        squared += 1
        colours = []
        for square in range(arguments.slots + 5):
            left = arguments.video_width + 8 + square * strip_check_STEP
            region = screen[top : top + strip_check_SQUARE, left : left + strip_check_SQUARE].reshape(-1, 3)
            if numpy.any(region != region[0]):
                failures.append((capture.name, "square %d is not one colour" % square))
            colours.append(tuple(int(channel) for channel in region[0]))
        for slot in range(arguments.slots):
            if colours[slot] != (strip_check_GREEN if pages[slot] else strip_check_RED):
                failures.append(
                    (capture.name, "slot %d square %s, page valid %s" % (slot, colours[slot], pages[slot]))
                )
        decision = {
            strip_check_GREEN: "decoded",
            strip_check_BLUE: "nothing new",
            strip_check_RED: "cannot decode",
        }.get(colours[arguments.slots], "other")
        decisions[decision] += 1
        if decision in ("cannot decode", "other"):
            failures.append((capture.name, "decision %s" % (colours[arguments.slots],)))
        count = 0
        for index, colour in enumerate(colours[arguments.slots + 1 :]):
            if len(set(colour)) != 1:
                failures.append((capture.name, "counter byte %d not grey: %s" % (index, colour)))
            count |= colour[0] << 8 * index
        if counts and count < counts[-1]:
            failures.append((capture.name, "counter went down from %d to %d" % (counts[-1], count)))
        counts.append(count)
    summary = {
        "captures": len(captures),
        "valid_pages_per_slot": {slot: valid[slot] for slot in sorted(valid)},
        "slots_without_a_page": {"%d: %s" % key: value for key, value in sorted(invalid.items())},
        "distinct_pages": len(frames),
        "descriptor_rows": descriptors,
        "captures_with_squares": squared,
        "decisions": dict(decisions),
        "counter": [counts[0], counts[-1]] if counts else None,
        "failures": len(failures),
    }
    for failure in failures[:20]:
        print("%s: %s" % failure)
    print(json.dumps(summary))
    sys.exit(1 if failures else 0)


strip_fit_check_VIDEO = (64, 64)
strip_fit_check_SLOTS = 8


def strip_fit_check_frame(context, screen, spirv=None):
    """Use the same random scene for every size, so strip coverage can be compared exactly."""
    global shader_check_SCREEN
    shader_check_SCREEN = screen
    chain = ShaderChain(context, strip_fit_check_VIDEO[0], strip_fit_check_VIDEO[1], strip_fit_check_SLOTS)
    if spirv:
        chain.compiled = shader_check_compile_via_spirv(chain.includes, spirv)
    scene = numpy.random.default_rng(7).integers(0, 256, (screen[1], screen[0], 4), dtype=numpy.uint8)
    scene[..., 3] = 255
    chain.main.write(scene.tobytes())
    decoded, _ = chain.frame()
    after = numpy.frombuffer(chain.main.read(), numpy.uint8).reshape(screen[1], screen[0], 4)
    return (scene, after, decoded)


def strip_fit_check_strip_rows(width):
    return strip_fit_check_SLOTS * ((4096 + width - 1) // width) + 1


def strip_fit_check_main():
    parser = argparse.ArgumentParser(
        description=(
            "Require the pack to preserve the scene when the transport strip cannot fit, and to cover a fitting "
            "strip with the scene row below it. Exit 1 if any check fails; 2 on invalid options."
        ),
    )
    parser.add_argument("--backend", choices=("egl", "glx"), default=None)
    parser.add_argument("--spirv", metavar="CLASSPATH", help=SPIRV_HELP)
    arguments = parser.parse_args()
    import moderngl

    context = moderngl.create_standalone_context(
        require=330, **{"backend": "egl"} if arguments.backend == "egl" else {}
    )
    print(context.info["GL_RENDERER"])
    failures = []
    for screen in ((160, 90), (64, 400)):
        assert strip_fit_check_strip_rows(screen[0]) >= screen[1], screen
        scene, after, decoded = strip_fit_check_frame(context, screen, arguments.spirv)
        changed = int(numpy.count_nonzero(numpy.any(after != scene, axis=2)))
        if changed:
            failures.append(
                "%dx%d, where the strip does not fit: %d pixels of the scene changed" % (screen + (changed,))
            )
        if decoded:
            failures.append("%dx%d, where the strip does not fit: a frame was decoded" % screen)
    screen = (854, 480)
    rows = strip_fit_check_strip_rows(screen[0])
    scene, after, _ = strip_fit_check_frame(context, screen, arguments.spirv)
    below = screen[1] - 1 - rows
    if not numpy.array_equal(after[: below + 1], scene[: below + 1]):
        failures.append("854x480: the scene below the strip changed")
    if not numpy.array_equal(
        after[below + 1 :], numpy.broadcast_to(scene[below], (rows,) + scene[below].shape)
    ):
        failures.append("854x480: the strip is not covered with the scene row below it")
    print(json.dumps({"checks": 6, "failures": failures}))
    return 1 if failures else 0


latency_SAMPLES = counter_video_BITS + len(counter_video_SYNC)


def latency_millis(value):
    if isinstance(value, (int, float)):
        return float(value)
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000.0


def latency_sends(path, jfr, span):
    output = subprocess.run(
        [jfr, "print", "--json", "--events", "me.brandonli.mcav.Mcv2Send", str(path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    found = []
    for event in json.loads(output)["recording"]["events"]:
        values = event["values"]
        sent = latency_millis(values["sent"]) if values["colors"] >= 0 else float("nan")
        found.append(
            dict(
                frame=values["frameId"],
                keyframe=values["keyframe"],
                bytes=values["bytes"],
                colors=values["colors"],
                arrived=sent,
                sent=sent,
                sent_to=values["sentTo"],
                behind=values["behind"],
                waiting=values["waiting"],
                backlog=values["backlog"],
                number=values["frameId"] % span,
            )
        )
    return sorted(found, key=lambda event: event["frame"])


def latency_events(path, jfr):
    output = subprocess.run(
        [jfr, "print", "--json", "--events", "me.brandonli.mcav.Mcv2Frame", str(path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    found = []
    for event in json.loads(output)["recording"]["events"]:
        values = event["values"]
        fingerprint = values.get("fingerprint") or ""
        luma = [int(fingerprint[offset : offset + 2], 16) for offset in range(0, len(fingerprint), 2)]
        found.append(
            dict(
                frame=values["frameId"],
                keyframe=values["keyframe"],
                bytes=values["bytes"],
                colors=values["colors"],
                arrived=latency_millis(values["arrived"]),
                sent=latency_millis(values["sent"]),
                sent_to=values["sentTo"],
                behind=values["behind"],
                waiting=values["waiting"],
                backlog=values["backlog"],
                number=counter_video_read(luma),
            )
        )
    return sorted(found, key=lambda event: event["frame"])


def latency_captures(path):
    """(wall-clock ms, number or None) for every captured frame, decoded one frame at a time: a 10-minute capture is
    2.6 GB of pixels."""
    probe = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height:frame=pts_time",
            "-of",
            "json",
            str(path),
        ],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    info = json.loads(probe)
    width, height = (info["streams"][0]["width"], info["streams"][0]["height"])
    times = [float(frame["pts_time"]) * 1000.0 for frame in info["frames"]]
    row = min(counter_video_BLOCK // 2, height - 1)
    columns = [
        counter_video_BLOCK // 2 + counter_video_BLOCK * block
        for block in range(latency_SAMPLES)
        if counter_video_BLOCK // 2 + counter_video_BLOCK * block < width
    ]
    size = width * height * 3
    result = []
    with subprocess.Popen(
        ["ffmpeg", "-nostdin", "-v", "error", "-i", str(path), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
        stdout=subprocess.PIPE,
    ) as decoder:
        # Drain every frame so ffmpeg cannot block on a full pipe when timestamps run out.
        for index, raw in enumerate(iter(lambda: decoder.stdout.read(size), b"")):
            if index >= len(times) or len(raw) < size:
                continue
            pixels = numpy.frombuffer(raw, numpy.uint8).reshape(height, width, 3)[row, columns].astype(int)
            luma = ((pixels[:, 0] + 2 * pixels[:, 1] + pixels[:, 2]) // 4).tolist()
            result.append((times[index], counter_video_read(luma)))
    if decoder.returncode != 0:
        raise subprocess.CalledProcessError(decoder.returncode, decoder.args)
    return result


def latency_percentile(values, percent):
    return float(numpy.percentile(values, percent)) if values else float("nan")


def latency_main():
    parser = argparse.ArgumentParser(
        description=(
            "Match server JFR events to timestamped counter-video captures on the same clock. --stream N uses "
            "send events and frame ids modulo the archived stream length instead of encoder events. Exit 1 on "
            "input/analysis failure; 2 on invalid options."
        ),
    )
    parser.add_argument("recording")
    parser.add_argument("capture")
    parser.add_argument("--json", type=Path)
    parser.add_argument("--jfr", default="jfr")
    parser.add_argument(
        "--stream", type=int, default=0, help="the frames of a pre-encoded stream played by /mcav mcv2 stream"
    )
    arguments = parser.parse_args()
    frames = (
        latency_sends(arguments.recording, arguments.jfr, arguments.stream)
        if arguments.stream
        else latency_events(arguments.recording, arguments.jfr)
    )
    shots = latency_captures(arguments.capture)
    numbered = [event for event in frames if event["number"] is not None and math.isfinite(event["arrived"])]
    by_number = {}
    for event in numbered:
        by_number.setdefault(event["number"], []).append(event)
    first_seen = {}
    ambiguous = set()
    previous = None
    for timestamp, number in shots:
        if number is not None and number != previous:
            candidates = [
                event
                for event in by_number.get(number, [])
                if event["sent_to"] > 0
                and math.isfinite(event["sent"])
                and (event["arrived"] <= event["sent"] <= timestamp)
            ]
            if candidates:
                latest = max(candidates, key=lambda event: event["sent"])
                occurrence = (number, latest["arrived"])
                first_seen[occurrence] = timestamp
                if len(candidates) > 1:
                    ambiguous.add(occurrence)
        previous = number if number is not None else previous
    latencies = [timestamp - arrival for (number, arrival), timestamp in first_seen.items()]
    arrived = {event["number"]: event for event in numbered}
    server = [event["sent"] - event["arrived"] for event in frames]
    duration = (shots[-1][0] - shots[0][0]) / 1000.0 if len(shots) > 1 else float("nan")
    source = max(arrived) - min(arrived) + 1 if arrived else 0
    report = dict(
        source_frames=source,
        encoded=len(frames),
        keyframes=sum(1 for event in frames if event["keyframe"]),
        sent_to_viewers=sum(event["sent_to"] for event in frames),
        held_back_for_backlog=sum(event["behind"] for event in frames),
        held_back_for_reference=sum(event["waiting"] for event in frames),
        not_sent_too_large=sum(1 for event in frames if event["colors"] < 0),
        backlog_max=max((event["backlog"] for event in frames), default=0),
        backlog_p95=latency_percentile([event["backlog"] for event in frames], 95),
        captured=len(shots),
        capture_seconds=duration,
        displayed=len(first_seen),
        ambiguous_displayed=len(ambiguous),
        displayed_fps=len(first_seen) / duration if duration == duration and duration > 0 else float("nan"),
        server_ms_mean=float(numpy.mean(server)) if server else float("nan"),
        server_ms_p95=latency_percentile(server, 95),
        glass_to_glass_ms_mean=float(numpy.mean(latencies)) if latencies else float("nan"),
        glass_to_glass_ms_p50=latency_percentile(latencies, 50),
        glass_to_glass_ms_p95=latency_percentile(latencies, 95),
        glass_to_glass_ms_max=max(latencies, default=float("nan")),
    )
    for key, value in report.items():
        print("%-26s %s" % (key, round(value, 3) if isinstance(value, float) else value))
    if arguments.json:
        arguments.json.write_text(json.dumps(report, indent=2))


charts_DATA = Path(__file__).resolve().parents[1] / "resources/mcv2/data"
IMAGES = Path(__file__).resolve().parents[4] / "mcav-docs" / "images" / "mcv2"
charts_SURFACE = "#ffffff"
charts_INK = "#0b0b0b"
charts_SECONDARY = "#52514e"
charts_GRID = "#e4e3df"
charts_FONT = ["Liberation Sans", "Arial", "Helvetica", "DejaVu Sans"]
charts_CODECS = [
    ("mcv2", "MCV2", "#2a78d6", "o"),
    ("x264", "H.264", "#eb6834", "s"),
    ("vp9", "VP9", "#1baf7a", "^"),
    ("av1", "AV1", "#eda100", "D"),
]
charts_SOURCES = [("proxy30", "Minecraft proxy, 1080p30"), ("gameplay30", "Minecraft gameplay, 1080p30")]
charts_SOURCE_COLOURS = {"proxy30": "#2a78d6", "gameplay30": "#eb6834"}
charts_LEVELS = (70, 75, 80, 85, 90)
charts_TICKS = (0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50)


def figure_pyplot():
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as pyplot

    return pyplot


def charts_style():
    pyplot = figure_pyplot()
    pyplot.rcParams.update(
        {
            "font.family": charts_FONT,
            "font.size": 10.5,
            "axes.edgecolor": charts_SECONDARY,
            "axes.labelcolor": charts_INK,
            "xtick.color": charts_SECONDARY,
            "ytick.color": charts_SECONDARY,
            "text.color": charts_INK,
            "axes.facecolor": charts_SURFACE,
            "figure.facecolor": charts_SURFACE,
        }
    )


def charts_curve(data, source, codec):
    if codec == "mcv2":
        points = [
            (point["zlib_mbps"], point["vmaf_mean"]) for point in data["mcv2"] if point["source"] == source
        ]
    else:
        points = [
            (point["container_mbps"], point["vmaf_mean"])
            for point in data["codecs"]
            if point["source"] == source and point["codec"] == codec
        ]
    return sorted(points)


def charts_rate_at(points, level):
    by_quality = sorted(points, key=lambda point: point[1])
    for (low_rate, low), (high_rate, high) in zip(by_quality, by_quality[1:]):
        if low <= level <= high and high > low:
            share = (level - low) / (high - low)
            return math.exp(math.log(low_rate) + share * (math.log(high_rate) - math.log(low_rate)))
    return None


def charts_draw_codecs(data):
    from matplotlib.ticker import FixedLocator, FuncFormatter, NullLocator

    pyplot = figure_pyplot()
    figure, axes = pyplot.subplots(2, 1, figsize=(8, 9.6), sharex=True)
    for axis, (source, title) in zip(axes, charts_SOURCES):
        for codec, name, colour, marker in charts_CODECS:
            rates, scores = zip(*charts_curve(data, source, codec))
            axis.plot(
                rates,
                scores,
                color=colour,
                marker=marker,
                markersize=5.5,
                linewidth=1.6,
                label=name,
                markeredgecolor=charts_SURFACE,
                markeredgewidth=0.8,
                zorder=3 if codec == "mcv2" else 2,
            )
            offset, align = {"vp9": ((7, -1), "left"), "mcv2": ((0, -13), "center")}.get(
                codec, ((-7, -1), "right")
            )
            axis.annotate(
                name,
                (rates[0], scores[0]),
                textcoords="offset points",
                xytext=offset,
                fontsize=9.5,
                color=charts_SECONDARY,
                ha=align,
                va="center",
            )
        axis.set_xscale("log")
        axis.xaxis.set_major_locator(FixedLocator(charts_TICKS))
        axis.xaxis.set_major_formatter(FuncFormatter(lambda value, _: f"{value:g}"))
        axis.xaxis.set_minor_locator(NullLocator())
        axis.set_title(title, fontsize=11.5, color=charts_INK, loc="left")
        axis.set_xlabel("Rate on the wire, Mbit/s (log scale)", color=charts_SECONDARY)
        axis.grid(True, color=charts_GRID, linewidth=0.6, zorder=0)
        axis.set_ylim(20, 102)
        axis.margins(x=0.18)
        for side in ("top", "right"):
            axis.spines[side].set_visible(False)
    for axis in axes:
        axis.set_ylabel("VMAF (mean)", color=charts_SECONDARY)
        axis.xaxis.set_tick_params(labelbottom=True)
    handles, labels = axes[0].get_legend_handles_labels()
    figure.legend(handles, labels, loc="lower center", ncol=4, frameon=False, fontsize=10)
    figure.tight_layout(rect=(0, 0.04, 1, 1))
    figure.savefig(IMAGES / "codecs.png", dpi=120, facecolor=charts_SURFACE)
    pyplot.close(figure)


def charts_ablation_rates(ablation):
    results = []
    for feature in ablation.get("features", []):
        row = {"id": feature["id"], "name": feature["name"]}
        for source, _ in charts_SOURCES:
            baseline = [(point["zlib_mbps"], point["vmaf_mean"]) for point in ablation["baseline"][source]]
            test = [(point["zlib_mbps"], point["vmaf_mean"]) for point in feature["points"][source]]
            row[source] = bd_rate(baseline, test)
        results.append(row)
    return results


def charts_draw_features(rows):
    pyplot = figure_pyplot()
    rows = sorted(rows, key=lambda row: row["gameplay30"][0] + row["proxy30"][0])
    values = [row[source][0] for row in rows for source, _ in charts_SOURCES]
    ordered = sorted(values, reverse=True)
    limit = max(ordered[1] * 1.35, 10) if len(ordered) > 1 and ordered[0] > 2.5 * ordered[1] else None
    height = 0.38
    figure, axis = pyplot.subplots(figsize=(9.5, 0.55 * len(rows) + 1.6))
    for index, (source, title) in enumerate(charts_SOURCES):
        positions = [row_index + (0.5 - index) * (height + 0.02) for row_index in range(len(rows))]
        shown = [min(row[source][0], limit) if limit else row[source][0] for row in rows]
        axis.barh(positions, shown, height=height, color=charts_SOURCE_COLOURS[source], label=title, zorder=3)
        for position, row, value in zip(positions, rows, shown):
            if limit and row[source][0] > limit:
                axis.annotate(
                    f"{row[source][0]:+.0f}% →",
                    (value, position),
                    textcoords="offset points",
                    xytext=(-4, 0),
                    ha="right",
                    va="center",
                    fontsize=8.5,
                    color=charts_SURFACE,
                    zorder=4,
                )
    axis.set_yticks(range(len(rows)))
    axis.set_yticklabels([row["name"] for row in rows], color=charts_INK)
    axis.axvline(0, color=charts_SECONDARY, linewidth=0.8, zorder=2)
    axis.set_xlabel("Extra rate on the wire with the feature turned off (BD-rate, %)", color=charts_SECONDARY)
    axis.grid(True, axis="x", color=charts_GRID, linewidth=0.6, zorder=0)
    if limit:
        axis.set_xlim(right=limit * 1.02)
    for side in ("top", "right"):
        axis.spines[side].set_visible(False)
    axis.legend(loc="lower right", frameon=False, fontsize=10)
    figure.tight_layout()
    figure.savefig(IMAGES / "features.png", dpi=120, facecolor=charts_SURFACE)
    pyplot.close(figure)


def charts_signed(value):
    text = f"{value:+.1f}%"
    return "0.0%" if text in ("+0.0%", "-0.0%") else text


def charts_print_tables(data, rows, removed):
    print("Rate on the wire (Mbit/s) for the same VMAF mean, log-linear between measured points:\n")
    print("| VMAF mean | " + " | ".join(name for _, name, _, _ in charts_CODECS) + " |")
    print("|---:|" + "---:|" * len(charts_CODECS))
    for source, title in charts_SOURCES:
        print(f"| **{title}** |" + " |" * len(charts_CODECS))
        for level in charts_LEVELS:
            cells = []
            for codec, _, _, _ in charts_CODECS:
                rate = charts_rate_at(charts_curve(data, source, codec), level)
                cells.append("-" if rate is None else f"{rate:.2f}")
            print(f"| {level} | " + " | ".join(cells) + " |")
    print(
        "\nBD-rate of MCV2 against each codec (positive: MCV2 needs more), over the VMAF range both cover:\n"
    )
    for source, title in charts_SOURCES:
        mcv2 = charts_curve(data, source, "mcv2")
        cells = []
        for codec, name, _, _ in charts_CODECS[1:]:
            percent, low, high = bd_rate(charts_curve(data, source, codec), mcv2)
            cells.append(f"{name} {percent:+.1f}% (VMAF {low:.1f}-{high:.1f})")
        print(f"- {title}: " + "; ".join(cells))
    if rows:
        print("\nExtra rate on the wire without each feature (BD-rate against the full encoder):\n")
        print("| Feature turned off | " + " | ".join(title for _, title in charts_SOURCES) + " |")
        print("|---|" + "---:|" * len(charts_SOURCES))
        for row in sorted(rows, key=lambda row: -(row["gameplay30"][0] + row["proxy30"][0])):
            cells = [
                f"{charts_signed(row[source][0])} ({row[source][1]:.0f}-{row[source][2]:.0f})"
                for source, _ in charts_SOURCES
            ]
            print(f"| {row['name']} | " + " | ".join(cells) + " |")
    if removed:
        print("\nWhat each feature MCV2 no longer has was worth (BD-rate when it was measured):\n")
        print("| Feature | " + " | ".join(title for _, title in charts_SOURCES) + " | Mean |")
        print("|---|" + "---:|" * (len(charts_SOURCES) + 1))
        for row in sorted(removed, key=lambda row: -row["bd_rate"]["mean"]):
            cells = [charts_signed(row["bd_rate"][source]) for source, _ in charts_SOURCES]
            print(f"| {row['name']} | " + " | ".join(cells) + f" | {charts_signed(row['bd_rate']['mean'])} |")


def charts_main():
    parser = argparse.ArgumentParser(
        description=(
            "Render the codec and ablation charts from committed measurements; --tables also prints the article "
            "tables. Exact pixels require the documented matplotlib/font versions. Exit 1 on data/render failure; "
            "2 on invalid options."
        ),
    )
    parser.add_argument("--tables", action="store_true", help="print the tables of mcav-docs/mcv2.md")
    arguments = parser.parse_args()
    logging.getLogger("matplotlib.font_manager").setLevel(logging.ERROR)
    charts_style()
    IMAGES.mkdir(parents=True, exist_ok=True)
    data = json.loads((charts_DATA / "codec_curves.json").read_text())
    charts_draw_codecs(data)
    ablation_file = charts_DATA / "ablation.json"
    ablation = json.loads(ablation_file.read_text()) if ablation_file.exists() else {}
    rows = charts_ablation_rates(ablation)
    if rows:
        charts_draw_features(rows)
    if arguments.tables:
        charts_print_tables(data, rows, ablation.get("removed", []))


samples_MODES = [
    (mcv2_reference.SKIP, "SKIP", "#e4e3df"),
    (mcv2_reference.MOTION, "MOTION", "#2a78d6"),
    (mcv2_reference.SOLID, "SOLID", "#1baf7a"),
    (mcv2_reference.PALETTE, "PALETTE", "#eb6834"),
    (mcv2_reference.PATTERN, "PATTERN", "#eda100"),
    (mcv2_reference.COMPACT, "COMPACT", "#8f5bd6"),
]


def samples_decoded(path, wanted):
    decoder, found = (Decoder(), {})
    for index, data in enumerate(read_archive(path)):
        picture = decoder.accept(data)
        if index in wanted:
            found[index] = (parse_frame(data), picture.copy())
        if len(found) == len(wanted):
            break
    return found


def samples_source_frame(path, index, width, height):
    size = width * height * 3
    return numpy.fromfile(path, numpy.uint8, size, offset=index * size).reshape(height, width, 3)


def samples_crop_of(text):
    pixel_x, pixel_y, width, height = (int(value) for value in text.split(","))
    return (pixel_x, pixel_y, width, height)


def samples_inside(leaf, crop):
    pixel_x, pixel_y, width, height = crop
    return (
        leaf.pixel_x < pixel_x + width
        and leaf.pixel_x + leaf.size > pixel_x
        and (leaf.pixel_y < pixel_y + height)
        and (leaf.pixel_y + leaf.size > pixel_y)
    )


def samples_finish(axis, title):
    axis.set_xticks([])
    axis.set_yticks([])
    for spine in axis.spines.values():
        spine.set_visible(False)
    axis.set_title(title, fontsize=10.5, color=charts_INK, loc="left")


def samples_draw_tree(arguments):
    from matplotlib.patches import Patch, Rectangle

    pyplot = figure_pyplot()
    numbers = [int(value) for value in arguments.frames.split(",")]
    frames = samples_decoded(arguments.archive, set(numbers))
    pixel_x, pixel_y, width, height = crop = samples_crop_of(arguments.crop)
    colours = {mode: colour for mode, _, colour in samples_MODES}
    figure, axes = pyplot.subplots(2, len(numbers), figsize=(5.4 * len(numbers), 6.6), squeeze=False)
    for column, number in enumerate(numbers):
        frame, picture = frames[number]
        kind = "keyframe" if frame.keyframe else "P frame"
        top, bottom = (axes[0][column], axes[1][column])
        top.imshow(picture[pixel_y : pixel_y + height, pixel_x : pixel_x + width], interpolation="nearest")
        bottom.imshow(numpy.full((height, width, 3), 255, numpy.uint8))
        for leaf in frame.leaves:
            if not samples_inside(leaf, crop):
                continue
            corner = (leaf.pixel_x - pixel_x - 0.5, leaf.pixel_y - pixel_y - 0.5)
            top.add_patch(
                Rectangle(corner, leaf.size, leaf.size, fill=False, edgecolor="#ffffff", linewidth=0.5)
            )
            bottom.add_patch(
                Rectangle(
                    corner,
                    leaf.size,
                    leaf.size,
                    facecolor=colours[leaf.mode],
                    edgecolor=charts_SURFACE,
                    linewidth=0.5,
                )
            )
        for axis in (top, bottom):
            axis.set_xlim(-0.5, width - 0.5)
            axis.set_ylim(height - 0.5, -0.5)
        samples_finish(top, f"Frame {number} ({kind}): {frame.total:,} bytes, {len(frame.leaves):,} leaves")
        samples_finish(bottom, f"The leaves of frame {number} by mode")
    handles = [
        Patch(facecolor=colour, edgecolor=charts_SECONDARY, linewidth=0.5, label=name)
        for _, name, colour in samples_MODES
    ]
    columns = min(len(samples_MODES), 3 * len(numbers))
    figure.legend(handles=handles, loc="lower center", ncol=columns, frameon=False, fontsize=9.5)
    figure.tight_layout(rect=(0, 0.05 * len(samples_MODES) / columns, 1, 1), h_pad=2)
    figure.savefig(IMAGES / arguments.out, dpi=120, facecolor=charts_SURFACE)
    pyplot.close(figure)
    for number in numbers:
        frame = frames[number][0]
        sizes = {
            size: sum(1 for leaf in frame.leaves if leaf.size == size) for size in mcv2_reference.LEAF_SIZES
        }
        print(
            f"frame {number}: keyframe={frame.keyframe} bytes={frame.total} leaves={len(frame.leaves)} by size {sizes} by mode "
            + str(
                {
                    name: sum(1 for leaf in frame.leaves if leaf.mode == mode)
                    for mode, name, _ in samples_MODES
                }
            )
        )


def samples_biggest(frame, mode, crop):
    candidates = [leaf for leaf in frame.leaves if leaf.mode == mode and samples_inside(leaf, crop)]
    candidates = candidates or [leaf for leaf in frame.leaves if leaf.mode == mode]
    return max(candidates, key=lambda leaf: (leaf.size, -leaf.pixel_y, -leaf.pixel_x)) if candidates else None


def samples_bits_of(leaf):
    record = leaf.record
    if leaf.mode == mcv2_reference.PALETTE:
        bits = numpy.unpackbits(numpy.frombuffer(record[6:], numpy.uint8), bitorder="little")
        return bits.reshape(leaf.size, leaf.size)
    axis = numpy.unpackbits(numpy.frombuffer(record[7:], numpy.uint8), bitorder="little")
    return numpy.broadcast_to(axis[None, :] if record[6] == 0 else axis[:, None], (leaf.size, leaf.size))


def samples_draw_leaves(arguments):
    pyplot = figure_pyplot()
    frame, picture = samples_decoded(arguments.archive, {arguments.frame})[arguments.frame]
    width, height = arguments.size
    source = samples_source_frame(arguments.source, arguments.frame, width, height)
    crop = samples_crop_of(arguments.crop)
    leaves = [
        samples_biggest(frame, mcv2_reference.PALETTE, crop),
        samples_biggest(frame, mcv2_reference.PATTERN, crop),
    ]
    figure, axes = pyplot.subplots(2, 4, figsize=(11, 6.2))
    for row, leaf in zip(axes, leaves):
        name = "PALETTE" if leaf.mode == mcv2_reference.PALETTE else "PATTERN"
        size = leaf.size
        block = source[leaf.pixel_y : leaf.pixel_y + size, leaf.pixel_x : leaf.pixel_x + size]
        colours = [tuple(leaf.record[0:3]), tuple(leaf.record[3:6])]
        bits = samples_bits_of(leaf)
        row[0].imshow(block, interpolation="nearest")
        samples_finish(row[0], f"{name} {size}x{size} at ({leaf.pixel_x}, {leaf.pixel_y}): source")
        swatch = numpy.array([[colours[0]], [colours[1]]], numpy.uint8)
        row[1].imshow(swatch, interpolation="nearest", aspect="auto")
        for index, colour in enumerate(colours):
            light = sum(colour) > 382
            row[1].text(
                0,
                index,
                f"colour {index}\n{colour[0]}, {colour[1]}, {colour[2]}",
                ha="center",
                va="center",
                fontsize=9.5,
                color=charts_INK if light else charts_SURFACE,
            )
        samples_finish(row[1], "its two colours")
        row[2].imshow(bits, cmap="gray_r", vmin=0, vmax=1.6, interpolation="nearest")
        for edge in range(size + 1):
            row[2].axhline(edge - 0.5, color=charts_GRID, linewidth=0.4)
            row[2].axvline(edge - 0.5, color=charts_GRID, linewidth=0.4)
        if leaf.mode == mcv2_reference.PALETTE:
            what = f"{size * size} bits, one per pixel"
        else:
            what = f"{size} bits, one per {('column' if leaf.record[6] == 0 else 'row')}"
        samples_finish(row[2], f"bits (grey is 1): {what}")
        row[3].imshow(
            picture[leaf.pixel_y : leaf.pixel_y + size, leaf.pixel_x : leaf.pixel_x + size],
            interpolation="nearest",
        )
        samples_finish(row[3], f"decoded: {len(leaf.record)} bytes")
        print(
            f"{name} {size}x{size} at ({leaf.pixel_x}, {leaf.pixel_y}) colours {colours} record {leaf.record.hex()}"
        )
    figure.tight_layout()
    figure.savefig(IMAGES / "leaves.png", dpi=120, facecolor=charts_SURFACE)
    pyplot.close(figure)


def samples_print_bytes(arguments):
    data = list(read_archive(arguments.archive))[arguments.frame]
    frame = parse_frame(data)
    groups = len(frame.masks)
    parts = [("header", 0, mcv2_reference.HEADER_BYTES)]
    at = mcv2_reference.HEADER_BYTES
    for name, length in (
        ("presence masks", 4 * groups),
        ("directory", 4 * len(frame.directory)),
        ("level counts", 12),
        ("descriptors", len(frame.descriptors)),
        ("walk checkpoints", 4 * len(frame.walk)),
    ):
        parts.append((name, at, at + length))
        at += length
    parts.append(("records", at, frame.total))
    for name, start, end in parts:
        print(f"{name} ({end - start} bytes, offsets {start}-{end - 1}):")
        for line in range(start, end, 16):
            print(f"  {line:5d}  " + " ".join(f"{value:02x}" for value in data[line : min(line + 16, end)]))
    print(
        f"width {frame.width} height {frame.height} keyframe {frame.keyframe} frame id {frame.frame_id} reference id {frame.reference_id} payload start {frame.payload_start} length {frame.total}"
    )
    print(
        f"masks {[hex(mask) for mask in frame.masks]} directory {list(frame.directory)} levels {frame.level_counts} walk {[(value & 131071, value >> 17) for value in frame.walk]}"
    )
    names = {mode: name for mode, name, _ in samples_MODES}
    names[mcv2_reference.SPLIT] = "SPLIT"
    for index, descriptor in enumerate(frame.descriptors):
        print(f"descriptor {index}: 0x{descriptor:02x} = {names[descriptor & 31]} q={descriptor >> 5}")
    for leaf in frame.leaves:
        if leaf.offset is not None:
            print(
                f"leaf {leaf.size}x{leaf.size} at ({leaf.pixel_x}, {leaf.pixel_y}) {names[leaf.mode]} q={leaf.quantizer} offset {leaf.offset}: {leaf.record.hex()}"
            )


def samples_main():
    parser = argparse.ArgumentParser(
        description=(
            "Decode archives independently to draw cropped leaf trees and source/decoded palette or pattern "
            "leaves, or print frame bytes. --size describes the raw source dimensions. Exit 1 on input/render "
            "failure; 2 on invalid options."
        ),
    )
    parser.add_argument(
        "--size", type=lambda text: tuple(int(value) for value in text.split("x")), default=(1920, 1080)
    )
    commands = parser.add_subparsers(dest="command", required=True)
    tree = commands.add_parser("tree")
    tree.add_argument("archive")
    tree.add_argument("--frames", required=True)
    tree.add_argument("--crop", required=True)
    tree.add_argument("--out", default="tree.png")
    leaves = commands.add_parser("leaves")
    leaves.add_argument("archive")
    leaves.add_argument("source")
    leaves.add_argument("--frame", type=int, default=0)
    leaves.add_argument("--crop", required=True)
    dump = commands.add_parser("bytes")
    dump.add_argument("archive")
    dump.add_argument("--frame", type=int, default=0)
    arguments = parser.parse_args()
    if arguments.command != "bytes":
        logging.getLogger("matplotlib.font_manager").setLevel(logging.ERROR)
        charts_style()
        IMAGES.mkdir(parents=True, exist_ok=True)
    {"tree": samples_draw_tree, "leaves": samples_draw_leaves, "bytes": samples_print_bytes}[
        arguments.command
    ](arguments)


def main():
    commands = {
        "bd_rate": bd_rate_main,
        "codec_curves": codec_curves_main,
        "counter_video": counter_video_main,
        "capture_check": capture_check_main,
        "edge_streams": edge_streams_main,
        "fixtures": fixtures_main,
        "differential": differential_main,
        "rate_quality": rate_quality_main,
        "shader_check": shader_check_main,
        "shader_timing": shader_timing_main,
        "strip_check": strip_check_main,
        "strip_fit_check": strip_fit_check_main,
        "latency": latency_main,
        "charts": charts_main,
        "samples": samples_main,
    }
    if len(sys.argv) < 2 or sys.argv[1] not in commands:
        raise SystemExit("Usage: mcv2_tools.py <" + "|".join(commands) + "> [arguments]")
    command = sys.argv.pop(1)
    sys.argv[0] = f"{sys.argv[0]} {command}"
    return commands[command]()


if __name__ == "__main__":
    sys.exit(main())
