"""Run the MCV2 resource pack's post passes outside Minecraft and compare every picture with the reference decoder.

    python tools/mcv2/shader_check.py <gpu-codec checkout> <stream.mcs> [<stream.mcs> ...] [--slots N] [--drop K]

Run with a Python that has numpy and moderngl. The OpenGL 3.3 context is the one moderngl finds: set DISPLAY to an
X server for GLX (Xvfb gives Mesa's llvmpipe, the renderer of a headless client) or leave it unset for EGL on a render
node. The shaders are read from mcav-bukkit's pack sources with their #moj_import directives resolved, and the
generated includes (configuration, residual books) are produced the way the pack builder produces them.

For every frame, the reference's make_pages splits the frame into pages, each page is written into a simulated main
target exactly as the core text shader writes it (four symbols to three bytes, slot by slot from the top of the
screen), and the chain runs: bytes, pages, status, decode, the two reference commits and the state. The persistent
targets carry over to the next frame, as they do in the client. The picture the chain keeps must equal the reference
decoder's picture byte for byte. --drop K leaves out every K-th frame from the client's view, to check that the chain
waits for the next frame it can decode instead of decoding against the wrong reference.
"""

import argparse
import re
import struct
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "mcav-bukkit/src/main/resources/mcav/mcv2/pack"
BOOKS = ROOT / "mcav-common/src/main/resources/me/brandonli/mcav/media/mcv2/residual_books.bin"
SCREEN = (1920, 1080)
STREAM_ID = 7

VERTEX = """#version 330
out vec2 texCoord;
void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
    texCoord = uv;
}
"""


def frames(path):
    data = Path(path).read_bytes()
    offset = 0
    while offset < len(data):
        length = struct.unpack_from("<I", data, offset)[0]
        yield data[offset + 4 : offset + 4 + length]
        offset += 4 + length


def generated(width, height, slots):
    """The generated includes, as the pack builder writes them."""
    books = BOOKS.read_bytes()
    words = [struct.unpack_from("<I", books, i)[0] for i in range(0, len(books), 4)]
    rows = ",\n".join("    " + ", ".join("0x%08Xu" % w for w in words[i : i + 8]) for i in range(0, len(words), 8))
    return {
        "mcav:mcv2_config.glsl": "\n".join([
            "#version 330",
            "const int MCV2_PAGE_SLOTS = %d;" % slots,
            "const int MCV2_VIDEO_WIDTH = %d;" % width,
            "const int MCV2_VIDEO_HEIGHT = %d;" % height,
            "const uint MCV2_STREAM_ID = %du;" % STREAM_ID,
            "const int MCV2_BYTES_WIDTH = 128;",
            "const bool MCV2_DEBUG_VIEW = false;",
            "const ivec3 MCV2_OUTLINE_COLOR = ivec3(0, 0, 0);",
            "",
        ]),
        "mcav:mcv2_books.glsl": "#version 330\nconst uint MCV2_BOOKS[512] = uint[512](\n" + rows + "\n);\n",
    }


def resolve(source, includes, seen=None):
    """Inlines #moj_import <namespace:file> like the client's preprocessor, once per file."""
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
            text = (PACK / "assets" / namespace / "shaders/include" / path).read_text()
        text = re.sub(r"^#version.*$", "", text, flags=re.M)
        return resolve(text, includes, seen)

    return re.sub(r"^#moj_import <([^>]+)>", include, source, flags=re.M)


class Chain:
    def __init__(self, context, width, height, slots):
        self.context = context
        self.width, self.height, self.slots = width, height, slots
        self.includes = generated(width, height, slots)
        self.programs = {}
        capacity = 12256
        bytes_height = (slots * capacity // 4 + 127) // 128
        make = lambda w, h: context.texture((w, h), 4, dtype="f1")
        self.main = make(*SCREEN)
        self.targets = {
            "bytes": make(128, bytes_height),
            "pages": make(4 * slots, 1),
            "status": make(4, 1),
            "state": make(4, 1),
            "state_next": make(4, 1),
            "previous": make(width, height),
            "key": make(width, height),
            "next": make(width, height),
            "key_next": make(width, height),
        }
        for name in ("state", "previous", "key"):
            self.targets[name].write(bytes(self.targets[name].width * self.targets[name].height * 4))
        for texture in list(self.targets.values()) + [self.main]:
            texture.filter = (context.NEAREST, context.NEAREST)

    def program(self, name):
        if name not in self.programs:
            source = (PACK / "assets/mcav/shaders/post" / (name + ".fsh")).read_text()
            self.programs[name] = self.context.program(vertex_shader=VERTEX, fragment_shader=resolve(source, self.includes))
        return self.programs[name]

    def run(self, name, inputs, output):
        program = self.program(name)
        for unit, (sampler, texture) in enumerate(inputs.items()):
            texture.use(unit)
            if sampler + "Sampler" in program:
                program[sampler + "Sampler"].value = unit
        target = self.targets[output]
        framebuffer = self.context.framebuffer(color_attachments=[target])
        framebuffer.use()
        self.context.vertex_array(program, []).render(mode=self.context.TRIANGLES, vertices=3)
        framebuffer.release()

    def copy(self, source, target):
        self.targets[target].write(self.targets[source].read())

    def show(self, pages):
        """Writes the pages into the strip as the core text shader does: slot p from row p * rows from the top."""
        screen = np.zeros((SCREEN[1], SCREEN[0], 4), np.uint8)
        rows = (4096 + SCREEN[0] - 1) // SCREEN[0]
        for page in pages:
            symbols = np.zeros(16384, np.uint32)
            symbols[: len(page)] = np.frombuffer(page, np.uint8)
            bits = symbols[0::4] | symbols[1::4] << 6 | symbols[2::4] << 12 | symbols[3::4] << 18
            packed = np.stack([bits & 255, bits >> 8 & 255, bits >> 16, np.full_like(bits, 255)], axis=1).astype(np.uint8)
            # the shader discards the fragments after the page's 4,096 pixels, leaving the (here empty) scene
            strip = np.zeros((rows * SCREEN[0], 4), np.uint8)
            strip[:4096] = packed
            slot = page_number(page)
            for r in range(rows):
                screen[SCREEN[1] - 1 - (slot * rows + r)] = strip[r * SCREEN[0] : (r + 1) * SCREEN[0]]
        self.main.write(screen.tobytes())

    def frame(self):
        self.run("mcv2_bytes", {"Main": self.main}, "bytes")
        self.run("mcv2_pages", {"Main": self.main}, "pages")
        self.run("mcv2_status", {"Pages": self.targets["pages"], "State": self.targets["state"]}, "status")
        self.run("mcv2_decode", {"Bytes": self.targets["bytes"], "Status": self.targets["status"],
                                 "Previous": self.targets["previous"], "Key": self.targets["key"]}, "next")
        self.copy("next", "previous")
        self.run("mcv2_keyframe", {"Next": self.targets["next"], "Key": self.targets["key"], "Status": self.targets["status"]}, "key_next")
        self.copy("key_next", "key")
        self.run("mcv2_state", {"State": self.targets["state"], "Status": self.targets["status"]}, "state_next")
        self.copy("state_next", "state")
        status = np.frombuffer(self.targets["status"].read(), np.uint8)
        picture = np.frombuffer(self.targets["previous"].read(), np.uint8).reshape(self.height, self.width, 4)[:, :, :3]
        return bool(status[0]), picture


def page_number(page):
    """The page number from a page's six-bit symbols: header bytes 16 and 17, bits 128 to 143."""
    value = 0
    for i in range(16):
        b = 128 + i
        value |= (page[b // 6] >> (b % 6) & 1) << i
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("codec")
    parser.add_argument("streams", nargs="+")
    parser.add_argument("--slots", type=int, default=4)
    parser.add_argument("--drop", type=int, default=0)
    arguments = parser.parse_args()
    sys.path.insert(0, arguments.codec)
    import moderngl
    from mcvideo.decoder import decode
    from mcvideo.transport import make_pages

    context = moderngl.create_standalone_context(require=330)
    print(context.info["GL_RENDERER"])
    failures = 0
    for stream in arguments.streams:
        chain = None
        # the client model: the pictures the chain decoded, by id, and the two references it holds
        pictures = {}
        last_id = key_id = None
        shown = None
        decoded = skipped = wrong = 0
        for index, frame in enumerate(frames(stream)):
            width, height = struct.unpack_from("<HH", frame, 8)
            frame_id, reference_id = struct.unpack_from("<II", frame, 12)
            keyframe = struct.unpack_from("<I", frame, 4)[0] >> 16 & 1 == 1
            if chain is None:
                chain = Chain(context, width, height, arguments.slots)
            if arguments.drop and index % arguments.drop == arguments.drop - 1:
                continue
            decodable = keyframe or reference_id in (last_id, key_id)
            chain.show(make_pages(frame, STREAM_ID, 6))
            did, picture = chain.frame()
            if did != decodable:
                wrong += 1
                print("  frame %d: the chain %s it, the client model %s" % (index, "decoded" if did else "skipped", "can" if decodable else "cannot"))
                continue
            if did:
                decoded += 1
                expected = decode(frame, None if keyframe else pictures[reference_id], reference_id)
                pictures[frame_id] = shown = expected
                last_id = frame_id
                key_id = frame_id if keyframe else key_id
                if not np.array_equal(picture, expected):
                    wrong += 1
                    difference = np.abs(picture.astype(int) - expected.astype(int))
                    print("  frame %d differs: %d pixels, max %d" % (index, int((difference.max(axis=2) > 0).sum()), difference.max()))
            else:
                skipped += 1
                # a frame the chain cannot decode keeps the last picture it could
                if shown is not None and not np.array_equal(picture, shown):
                    wrong += 1
                    print("  frame %d was skipped but the picture changed" % index)
        print("%s: %d frames decoded, %d skipped, %d wrong" % (Path(stream).name, decoded, skipped, wrong))
        failures += wrong
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
