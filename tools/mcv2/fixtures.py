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

"""Regenerate v3 reference fixtures; read but never rewrite Java conformance/golden streams.

Archives contain repeated little-endian u32 lengths followed by that many frame bytes.
Version-2 conformance and encoder streams are left untouched until replaced by Java output.
"""

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY / 'tools/mcv2-reference'))

from mcvideo.decoder import Decoder
from mcvideo.transport import make_pages, wire_bytes
from mcvideo.v3 import parse_frame

PREFIX_LIMIT = 1_000_000


def frames(data):
    offset = 0
    while offset < len(data):
        if offset + 4 > len(data):
            raise ValueError('truncated archive length')
        length = struct.unpack_from('<I', data, offset)[0]
        offset += 4
        if offset + length > len(data):
            raise ValueError('truncated archive frame')
        yield data[offset:offset + length]
        offset += length


def archive(chunks):
    return b''.join(struct.pack('<I', len(chunk)) + chunk for chunk in chunks)


def digests(data):
    decoder = Decoder()
    return [hashlib.sha256(decoder.accept(frame).tobytes()).hexdigest() for frame in frames(data)]


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=1) + '\n')


def v3_stream(path):
    data = path.read_bytes()
    kept = list(frames(data))
    if not kept:
        raise ValueError(f'{path}: empty archive')
    if kept[0][:5] != b'MCV2\x03':
        print(f'skip non-v3 stream: {path}', file=sys.stderr)
        return None
    return data, kept


def conformance(root):
    output = root / 'conformance'
    path = output / 'digests.json'
    table = json.loads(path.read_text()) if path.exists() else {}
    updated = False
    for stream in sorted(output.glob('*.mcs')):
        result = v3_stream(stream)
        if result is None:
            continue
        data, kept = result
        if len(data) > PREFIX_LIMIT or not parse_frame(kept[0]).keyframe:
            raise ValueError(f'{stream}: conformance must start with a keyframe and fit within {PREFIX_LIMIT} bytes')
        table[stream.name] = dict(frames=len(kept), bytes=len(data), sha256_per_frame=digests(data))
        updated = True
        print(f'{stream.name}: {len(kept)} v3 frames checked', file=sys.stderr)
    if updated:
        write_json(path, table)


def edge(root):
    from edge_streams import generate
    generate(root / 'edge')


def pages(root):
    cases = []
    for stream in sorted((root / 'conformance').glob('*.mcs')):
        result = v3_stream(stream)
        if result is not None:
            cases.extend((str(stream.relative_to(root)), index, frame) for index, frame in enumerate(result[1]))
    if cases:
        source = 'committed v3 conformance streams'
        cases = [cases[index % len(cases)] for index in range(4)]
    else:
        source = 'edge streams (no committed v3 conformance streams)'
        cases = [(f'edge/{name}.mcs', index, list(frames((root / 'edge' / f'{name}.mcs').read_bytes()))[index])
                 for name, index in [('edge-modes', 0), ('edge-modes', 1), ('edge-tiny', 0), ('edge-long-walk', 1)]]
    entries = []
    for stream, index, frame in cases:
        symbols = make_pages(frame, 7)
        entries.append(dict(stream=stream, frame=index, pages=[hashlib.sha256(page).hexdigest() for page in symbols],
                            lengths=[len(page) for page in symbols], wire=wire_bytes(symbols),
                            wire_full=wire_bytes(symbols, full_maps=True)))
    write_json(root / 'conformance/pages.json', dict(source=source, stream_id=7, symbol_bits=6, frames=entries))


def encoder(root):
    checked = 0
    for stream in sorted((root / 'encoder').glob('*.mcs')):
        result = v3_stream(stream)
        if result is None:
            continue
        decoded = digests(result[0])
        checked += 1
        print(f'{stream.name}: {len(decoded)} golden v3 frames decoded', file=sys.stderr)
    print(f'encoder: {checked} committed v3 golden streams checked; files unchanged', file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('what', nargs='?', default='all', choices=('conformance', 'edge', 'pages', 'encoder', 'all'))
    arguments = parser.parse_args()
    steps = dict(conformance=conformance, edge=edge, pages=pages, encoder=encoder)
    for step in steps if arguments.what == 'all' else [arguments.what]:
        steps[step](arguments.root)


if __name__ == '__main__':
    main()
