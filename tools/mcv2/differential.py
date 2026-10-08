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

"""Require identical accept/reject decisions and pixels from the Python and Java v3 decoders.

Random block trees use the independent Python serializer. Committed conformance archives,
byte mutations and truncations exercise both parsers and their stream state machines.
There are no unsupported-syntax exemptions. Only ValueError means Python rejected a frame;
any other Python exception or a Java launcher failure fails the run.
"""

import argparse
import hashlib
import json
import random
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcv2-reference'))

from mcvideo.decoder import Decoder
from edge_streams import random_stream
from fixtures import archive, frames

DEFAULT_CORPUS = Path(__file__).resolve().parents[2] / 'mcav-bukkit/src/test/resources/mcv2/conformance'


def mutant(original, randomizer):
    mutated = [bytearray(frame) for frame in original]
    nonempty = [index for index, frame in enumerate(mutated) if frame]
    if not nonempty:
        raise ValueError('mutation requires a nonempty frame')
    if randomizer.random() < 0.15:
        victim = randomizer.choice(nonempty)
        mutated[victim] = mutated[victim][:randomizer.randrange(len(mutated[victim]))]
    else:
        for _ in range(randomizer.randint(1, 4)):
            victim = mutated[randomizer.choice(nonempty)]
            limit = min(len(victim), 256) if randomizer.random() < 0.7 else len(victim)
            victim[randomizer.randrange(limit)] ^= randomizer.randint(1, 255)
    if mutated == original:
        mutated[nonempty[0]][0] ^= 1
    return [bytes(frame) for frame in mutated]


def reference_tokens(chunks):
    decoder, tokens = Decoder(), []
    for frame in chunks:
        try:
            tokens.append(hashlib.sha256(decoder.accept(frame).tobytes()).hexdigest())
        except ValueError:
            tokens.append('reject')
    return tokens


def build_archives(arguments):
    randomizer = random.Random(arguments.seed)
    archives = {f'tree-{index:04d}': random_stream(randomizer) for index in range(arguments.streams)}
    corpus = sorted(arguments.corpus.glob('*.mcs'))
    if arguments.conformance and not corpus:
        raise ValueError(f'no committed conformance archives in {arguments.corpus}')
    for index in range(arguments.conformance):
        path = corpus[index % len(corpus)]
        chunks = list(frames(path.read_bytes()))
        if not chunks:
            raise ValueError(f'empty conformance archive: {path}')
        archives[f'conformance-{index:04d}-{path.stem}'] = chunks
    for name, chunks in list(archives.items()):
        for index in range(arguments.mutants):
            archives[f'{name}-mutant-{index}'] = mutant(chunks, randomizer)
    return archives


def compare(expected, actual):
    counts = dict(archives=len(expected), frames=0, decoded=0, refused=0, disagreements=0)
    disagreements = []
    for name, reference in expected.items():
        java = actual.get(name, [])
        if len(java) != len(reference):
            disagreements.append(dict(archive=name, reason='frame count', mcav=len(java), reference=len(reference)))
        for index, token in enumerate(reference):
            counts['frames'] += 1
            other = java[index] if index < len(java) else None
            if other != token:
                disagreements.append(dict(archive=name, frame=index, mcav=other, reference=token))
            else:
                counts['refused' if token == 'reject' else 'decoded'] += 1
    for name in actual.keys() - expected.keys():
        disagreements.append(dict(archive=name, reason='unexpected archive from Java'))
    counts['disagreements'] = len(disagreements)
    return counts, disagreements


def run(arguments):
    archives = build_archives(arguments)
    output = arguments.out
    (output / 'archives').mkdir(parents=True, exist_ok=True)
    paths = {}
    for name, chunks in archives.items():
        paths[name] = output / 'archives' / f'{name}.mcs'
        paths[name].write_bytes(archive(chunks))
    expected = {name: reference_tokens(chunks) for name, chunks in archives.items()}
    java = subprocess.run(
        [arguments.java, '-cp', arguments.classpath, str(Path(__file__).with_name('Mcv2Digests.java'))]
        + [str(path) for path in paths.values()], capture_output=True, text=True,
    )
    if java.returncode:
        print(java.stderr, file=sys.stderr)
        return 2
    actual = {}
    for line in java.stdout.splitlines():
        path, *tokens = line.split(' ')
        name = Path(path).stem
        if name in actual:
            raise ValueError(f'duplicate Java output for {name}')
        actual[name] = tokens
    counts, disagreements = compare(expected, actual)
    summary = dict(seed=arguments.seed, counts=counts, disagreements=disagreements)
    (output / 'summary.json').write_text(json.dumps(summary, indent=1) + '\n')
    print(json.dumps(counts))
    return 1 if disagreements else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('classpath')
    parser.add_argument('--streams', type=int, default=200, help='random-tree archives')
    parser.add_argument('--conformance', type=int, default=40, help='committed archives, cycling when necessary')
    parser.add_argument('--corpus', type=Path, default=DEFAULT_CORPUS)
    parser.add_argument('--mutants', type=int, default=1, help='mutated copies of every archive')
    parser.add_argument('--seed', type=int, default=20260926)
    parser.add_argument('--out', type=Path, default=Path('build/mcv2-differential'))
    parser.add_argument('--java', default='java')
    arguments = parser.parse_args()
    if min(arguments.streams, arguments.conformance, arguments.mutants) < 0 or arguments.streams + arguments.conformance == 0:
        parser.error('archive counts must be nonnegative and at least one archive is required')
    sys.exit(run(arguments))


if __name__ == '__main__':
    main()
