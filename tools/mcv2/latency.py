"""End-to-end numbers of an MCV2 run from the server's flight recording and a capture of the client's screen.

    python tools/mcv2/latency.py <server.jfr> <capture.nut> [--top ROWS] [--json OUT] [--jfr JFR]

The server runs with -XX:StartFlightRecording and records one me.brandonli.mcav.Mcv2Frame event per encoded frame: when
the frame reached the result (arrived), when its pages left (sent), and a fingerprint that holds the frame's number when
the video is one of counter_video.py's. The client runs the pack's debug view, which draws the decoded picture one to one
below the transport strip, and the capture is ffmpeg's x11grab of that picture's top rows with wall-clock timestamps:

    ffmpeg -f x11grab -framerate 60 -video_size 768x32 -use_wallclock_as_timestamps 1 -i :103.0+0,<top> \\
        -copyts -c:v rawvideo -pix_fmt rgb24 -f nut capture.nut

--top is where the picture starts on the screen (the strip's rows: slots * ceil(4096 / screen width) + 1). A run of a
pre-encoded stream (the sandbox's /mcav mcv2 stream, no encoder) has no Mcv2Frame events: --stream SPAN reads the
channel's me.brandonli.mcav.Mcv2Send events instead, takes a frame's number as its id modulo the stream's length in
frames (the playback shifts ids by whole lengths), and measures from when the frame was sent. The report:
frames the source had, encoded and sent to the viewer, the frames the viewer displayed and at what rate, the
glass-to-glass latency of each displayed frame (first capture showing it, less its arrival at the server), and the
frames held back by the backlog limit or a missing reference. Both clocks are the same machine's.
"""

import argparse
import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from counter_video import BLOCK, BITS, SYNC, read  # noqa: E402

SAMPLES = BITS + len(SYNC)


def millis(value):
    """A flight recorder timestamp as milliseconds since the epoch: a number, or an ISO string."""
    if isinstance(value, (int, float)):
        return float(value)
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000.0


def sends(path, jfr, span):
    """The Mcv2Send events of a recording, in frame order, numbered by id modulo the stream length."""
    output = subprocess.run([jfr, "print", "--json", "--events", "me.brandonli.mcav.Mcv2Send", str(path)],
                            check=True, capture_output=True, text=True).stdout
    found = []
    for event in json.loads(output)["recording"]["events"]:
        values = event["values"]
        sent = millis(values["sent"]) if values["colors"] >= 0 else float("nan")
        found.append(dict(frame=values["frameId"], keyframe=values["keyframe"], bytes=values["bytes"],
                          colors=values["colors"], arrived=sent, sent=sent, sent_to=values["sentTo"],
                          behind=values["behind"], waiting=values["waiting"], backlog=values["backlog"],
                          number=values["frameId"] % span))
    return sorted(found, key=lambda e: e["frame"])


def events(path, jfr):
    """The Mcv2Frame events of a recording, in frame order."""
    output = subprocess.run([jfr, "print", "--json", "--events", "me.brandonli.mcav.Mcv2Frame", str(path)],
                            check=True, capture_output=True, text=True).stdout
    found = []
    for event in json.loads(output)["recording"]["events"]:
        values = event["values"]
        fingerprint = values.get("fingerprint") or ""
        luma = [int(fingerprint[i:i + 2], 16) for i in range(0, len(fingerprint), 2)]
        found.append(dict(frame=values["frameId"], keyframe=values["keyframe"], bytes=values["bytes"],
                          colors=values["colors"], arrived=millis(values["arrived"]), sent=millis(values["sent"]),
                          sent_to=values["sentTo"], behind=values["behind"], waiting=values["waiting"],
                          backlog=values["backlog"], number=read(luma)))
    return sorted(found, key=lambda e: e["frame"])


def captures(path):
    """(wall-clock ms, number or None) for every captured frame."""
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                            "stream=width,height:frame=pts_time", "-of", "json", str(path)],
                           check=True, capture_output=True, text=True).stdout
    info = json.loads(probe)
    width, height = info["streams"][0]["width"], info["streams"][0]["height"]
    times = [float(frame["pts_time"]) * 1000.0 for frame in info["frames"]]
    raw = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", str(path), "-f", "rawvideo", "-pix_fmt", "rgb24",
                          "-"], check=True, capture_output=True).stdout
    frames = np.frombuffer(raw, np.uint8).reshape(-1, height, width, 3)
    row = min(BLOCK // 2, height - 1)
    xs = [BLOCK // 2 + BLOCK * i for i in range(SAMPLES) if BLOCK // 2 + BLOCK * i < width]
    result = []
    for time, frame in zip(times, frames):
        pixels = frame[row, xs].astype(int)
        luma = ((pixels[:, 0] + 2 * pixels[:, 1] + pixels[:, 2]) // 4).tolist()
        result.append((time, read(luma)))
    return result


def percentile(values, p):
    return float(np.percentile(values, p)) if values else float("nan")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("recording")
    parser.add_argument("capture")
    parser.add_argument("--json", type=Path)
    parser.add_argument("--jfr", default="jfr")
    parser.add_argument("--stream", type=int, default=0, help="the frames of a pre-encoded stream played by /mcav mcv2 stream")
    arguments = parser.parse_args()
    frames = sends(arguments.recording, arguments.jfr, arguments.stream) if arguments.stream else events(arguments.recording, arguments.jfr)
    shots = captures(arguments.capture)
    numbered = [e for e in frames if e["number"] is not None and e["arrived"] == e["arrived"]]
    # a displayed picture is matched with the latest frame of its number that left before it was seen, since a
    # stream's numbers repeat every loop; its first capture is its display time
    by_number = {}
    for e in numbered:
        by_number.setdefault(e["number"], []).append(e["arrived"])
    first_seen = {}
    previous = None
    for time, number in shots:
        if number is not None and number != previous:
            candidates = [t for t in by_number.get(number, []) if t <= time]
            if candidates:
                first_seen[(number, max(candidates))] = time
        previous = number if number is not None else previous
    latencies = [time - arrival for (number, arrival), time in first_seen.items()]
    arrived = {e["number"]: e for e in numbered}
    server = [e["sent"] - e["arrived"] for e in frames]
    duration = (shots[-1][0] - shots[0][0]) / 1000.0 if len(shots) > 1 else float("nan")
    source = (max(arrived) - min(arrived) + 1) if arrived else 0
    report = dict(
        source_frames=source,
        encoded=len(frames),
        keyframes=sum(1 for e in frames if e["keyframe"]),
        sent_to_viewers=sum(e["sent_to"] for e in frames),
        held_back_for_backlog=sum(e["behind"] for e in frames),
        held_back_for_reference=sum(e["waiting"] for e in frames),
        not_sent_too_large=sum(1 for e in frames if e["colors"] < 0),
        backlog_max=max((e["backlog"] for e in frames), default=0),
        backlog_p95=percentile([e["backlog"] for e in frames], 95),
        captured=len(shots),
        capture_seconds=duration,
        displayed=len(first_seen),
        displayed_fps=len(first_seen) / duration if duration == duration and duration > 0 else float("nan"),
        server_ms_mean=float(np.mean(server)) if server else float("nan"),
        server_ms_p95=percentile(server, 95),
        glass_to_glass_ms_mean=float(np.mean(latencies)) if latencies else float("nan"),
        glass_to_glass_ms_p50=percentile(latencies, 50),
        glass_to_glass_ms_p95=percentile(latencies, 95),
        glass_to_glass_ms_max=max(latencies, default=float("nan")),
    )
    for key, value in report.items():
        print("%-26s %s" % (key, round(value, 3) if isinstance(value, float) else value))
    if arguments.json:
        arguments.json.write_text(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
