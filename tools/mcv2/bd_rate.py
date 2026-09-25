"""Bjontegaard delta rate between two rate-quality curves.

    python tools/mcv2/bd_rate.py <reference.json> <test.json> [--metric vmaf_mean] [--rate map_mbps]

Each file is a JSON list of points, one per encode of the same source at one lambda, each an object with at least the
rate and the quality named by --rate and --metric (for example the lines tools/mcv2/rate_quality.py writes). The BD
rate is the average difference of log rate between the two curves over the quality range both cover, from a cubic
fit of log rate against quality on each (Bjontegaard, VCEG-M33): positive means the test curve needs more rate for
the same quality. Needs at least four points per curve, and prints the overlap it integrated over.
"""

import argparse
import json
import math
import sys

import numpy as np


def bd_rate(reference, test):
    """The BD rate of test against reference, each a list of (rate, quality); returns (percent, low, high)."""
    r1 = np.log([p[0] for p in reference])
    q1 = np.array([p[1] for p in reference])
    r2 = np.log([p[0] for p in test])
    q2 = np.array([p[1] for p in test])
    low = max(q1.min(), q2.min())
    high = min(q1.max(), q2.max())
    if high <= low:
        raise ValueError("the curves share no quality range")
    p1 = np.polyfit(q1, r1, 3)
    p2 = np.polyfit(q2, r2, 3)
    i1 = np.polyval(np.polyint(p1), high) - np.polyval(np.polyint(p1), low)
    i2 = np.polyval(np.polyint(p2), high) - np.polyval(np.polyint(p2), low)
    return (math.exp((i2 - i1) / (high - low)) - 1) * 100, low, high


def points(path, rate, metric):
    data = json.load(open(path))
    return sorted((float(p[rate]), float(p[metric])) for p in data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reference")
    parser.add_argument("test")
    parser.add_argument("--metric", default="vmaf_mean")
    parser.add_argument("--rate", default="map_mbps")
    arguments = parser.parse_args()
    reference = points(arguments.reference, arguments.rate, arguments.metric)
    test = points(arguments.test, arguments.rate, arguments.metric)
    if len(reference) < 4 or len(test) < 4:
        sys.exit("each curve needs at least four points")
    delta, low, high = bd_rate(reference, test)
    print(json.dumps({"bd_rate_percent": round(delta, 3), "metric": arguments.metric, "rate": arguments.rate,
                      "quality_low": round(low, 4), "quality_high": round(high, 4)}))


if __name__ == "__main__":
    main()
