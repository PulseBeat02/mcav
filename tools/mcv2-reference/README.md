# MCV2 version 3 reference

This is the normative Python reference for MCV2 v3, together with the specification in
[docs/mcv2.md](../../docs/mcv2.md). It was written from the specification, independently of
mcav's Java code. It validates and decodes frames, serializes supplied block trees, and
implements six-bit transport pages. It contains no encoder or search code.

Use Python 3.12 or newer and `pip install -r tools/mcv2-reference/requirements.txt`.
The reference needs only numpy (pinned to 2.5.3); the adjacent shader and screenshot tools
also use moderngl 5.12.0 and Pillow 12.3.0. From the repository root:

```sh
python -m unittest discover -s tools/mcv2-reference/tests
python tools/mcv2/fixtures.py mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2 all
```

Add `tools/mcv2-reference` to `PYTHONPATH` to import the package:

```python
from mcvideo.format import SOLID
from mcvideo.v3 import Node, pack_frame, parse_frame
from mcvideo.decoder import decode, Decoder
from mcvideo.transport import make_pages, Assembler

wire = pack_frame(1, 1, 9, 9, True, (0, 0, 0), {0: Node(SOLID, record=bytes([10, 20, 30]))})
assert decode(wire).tolist() == [[[10, 20, 30]]]
```

## Public API

- `format.py` defines header offsets, mode and compact class numbers, bounds and transport constants.
- `v3.py`: `Node(mode, q=0, record=b"", children=())`, `parse_frame(data: bytes) -> Frame`,
  `pack_frame(width, height, frame_id, reference_id, keyframe, default_color, roots,
  endpoint_table=None, selector_tables=None) -> bytes`, and `expand_endpoints(pair: bytes) -> bytes`.
  `roots` maps superblock index to `Node`; missing entries mean SKIP. SPLIT nodes have four
  children in top-left, top-right, bottom-left, bottom-right order. Records are whole bytes;
  PATTERN records always contain six RGB bytes followed by the whole selector word.
  Endpoint tables are sequences of four-byte RGB565 pairs. Selector tables map 8, 16 or 32 to
  sequences of whole selector words. Each PATTERN value must match a supplied table entry
  exactly (endpoints after RGB565 expansion). The writer replaces those values with indexes.
  Table order and unused entries are preserved; there is no automatic table selection or quantization.
- `Frame` exposes header fields, `leaves`, `masks`, `directory`, `level_counts`, `descriptors`,
  `walk`, `table_counts` (P, c8, c16, c32), raw `endpoint_table`, `selector_tables`, and `roots`.
  Parsed roots contain whole PATTERN records and can be passed back to `pack_frame` with the
  parsed tables for a byte-identical round trip. Leaves have `x`, `y`, `size`, `mode`, `q`,
  `offset`, `record` and `descriptor_index`. Present leaves are in level order, followed by
  absent superblocks in raster order; absent leaves have `offset = descriptor_index = None`.
- `decoder.py`: `decode(data: bytes, reference: np.ndarray | None = None,
  reference_id: int | None = None) -> np.ndarray` returns height × width × 3 uint8 RGB.
  A P frame requires both the matching id and a uint8 reference of the same dimensions.
  `Decoder().accept(data) -> np.ndarray` retains one previous picture and applies the u32
  half-range rule. Invalid, old or missing-reference frames raise `ValueError` and preserve
  state; a newer keyframe restores playback. A newer P frame can reference the held picture
  across an id gap. Returned pictures can be edited without changing the held reference.
- `transport.py`: `PAGE_HEADER` is a `struct.Struct`;
  `page_capacity(symbol_bits=6) -> int`, `make_pages(frame_data, stream_id=1, symbol_bits=6)
  -> list[bytes]`, `read_page(symbols, symbol_bits=6) -> Page`,
  `wire_bytes(pages, full_maps=False, packet_overhead=18) -> int`, and
  `Assembler(stream_id=1, symbol_bits=6).push(symbols) -> bytes | None`.
  Only six-bit symbols are accepted. Page symbols are values 0–63 with exact symbol length;
  add four for map colours and pad map updates to whole 128-colour rows with colour four.
  `read_page` takes the unpadded symbol sequence. `to_symbols(data, symbol_bits=6)` and
  `from_symbols(symbols, symbol_bits, byte_count)` expose the LSB-first packing.
  Assembly validates CRCs, metadata agreement and complete frame syntax, retaining at most
  four pending frames; it does not advance decoder state.

## Tests and file hashes

The tests include literal wire bytes, hand-computed expected pixels, every validation rule,
serializer/tree round trips, frame and id bounds, and page corruption/reassembly. The malformed
vectors in `tests/rejection_cases.py` also supply the public rejected-frame fixture catalog.
All source and test files and the pinned requirements are listed below; this README is excluded
from its own manifest.

| file | bytes | SHA-256 |
|---|---:|---|
| `mcvideo/__init__.py` | 822 | `f25bfd3cdfbb172eaf31e0fd90ba062ef0f30f9aef5e04807734565d4c68e03c` |
| `mcvideo/decoder.py` | 5,804 | `5f2e7f5c0877cc0c7d7a165eaad663caed4888bcfdae2fa0a36294e607df4bf6` |
| `mcvideo/format.py` | 1,843 | `f28ec26c06e18668bf113cb35ccb3a5543e7ccbed9cecaa2901d690b3844b9c3` |
| `mcvideo/transport.py` | 6,385 | `36d445e28a90564e17c893ec2991f55db16497c97560711facf3412ba740949b` |
| `mcvideo/v3.py` | 16,123 | `e80f6e12a433d11c87c21ab957137e0fdefb350edfe56a4a85b03aae81878027` |
| `requirements.txt` | 883 | `c4f288cc61d58c292c943c07dbe3b4e233aac528db5a9d91866704469ed4ea24` |
| `tests/rejection_cases.py` | 8,092 | `ee18c42e14b25f395aad4a1f08ed78c7544b4d94b767d2b139e3122b642db8e7` |
| `tests/test_decoder.py` | 10,701 | `e796e118e21ad4ab9db2ba96e27b19ffe7e295d058bdc0d01f1eee2c578c8a11` |
| `tests/test_format.py` | 7,980 | `fc7aaf4271d99df742663f7aa7476c36e7e5fb26cbb6f0d6ebc18623de04e808` |
| `tests/test_transport.py` | 7,246 | `bc31f8e5606baf78cb09708369c963c5ca362fcd3f9df1e69677fd697e23810f` |
