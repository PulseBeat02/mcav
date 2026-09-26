"""Run the MCV2 resource pack's post passes outside Minecraft and compare every picture with the reference decoder.

    python tools/mcv2/shader_check.py <gpu-codec checkout> <stream.mcs> [<stream.mcs> ...] [--slots N] [--drop K]
        [--backend egl|glx] [--pack DIR]

Run with a Python that has numpy and moderngl. The OpenGL 3.3 context is the one moderngl finds: set DISPLAY to an
X server for GLX (Xvfb gives Mesa's llvmpipe, the renderer of a headless client) or leave it unset for EGL on a render
node. The shaders are read from mcav-bukkit's pack sources with their #moj_import directives resolved, and the
generated includes (configuration, residual books) are produced the way the pack builder produces them.

For every frame, the reference's make_pages splits the frame into pages, each page is written into a simulated main
target exactly as the core text shader writes it (four symbols to three bytes, slot by slot from the top of the
screen), and the pack's post chain runs pass for pass as its entity_outline.json lists them, with the target sizes
the pack builder fills in: mcav's passes with their own shaders, Minecraft's blit as a texel copy, and Minecraft's
own outline passes, which only touch the outline target, left out. The persistent targets carry over to the next
frame, as they do in the client. The picture the chain keeps must equal the reference
decoder's picture byte for byte. --drop K leaves out every K-th frame from the client's view, to check that the chain
waits for the next frame it can decode instead of decoding against the wrong reference.
"""

import argparse
import json
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


def cells_width(width):
    """The resolve pass's columns, as Mcv2Pack.cellsWidth: one per 8 pixels, and room for the frame's 6 facts."""
    return max((width + 7) // 8, 6)


def cells_height(height):
    """The resolve pass's rows of cells, as Mcv2Pack.cellsHeight; the frame row follows them."""
    return (height + 7) // 8


def placeholders(width, height, slots):
    """The target sizes Mcv2Pack.postChain fills into the post chain."""
    capacity = 12256
    return {
        "VIDEO_WIDTH": width,
        "VIDEO_HEIGHT": height,
        "BYTES_WIDTH": 128,
        "BYTES_HEIGHT": (slots * capacity // 4 + 127) // 128,
        "PAGES_WIDTH": 4 * slots,
        "CRC_WIDTH": 64 * slots,
        "CELLS_WIDTH": cells_width(width),
        "CELLS_HEIGHT": cells_height(height) + 1,
    }


def post_chain(width, height, slots):
    """The pack's post chain with its sizes filled in, as the client loads it."""
    text = (PACK / "assets/minecraft/post_effect/entity_outline.json").read_text()
    for name, value in placeholders(width, height, slots).items():
        text = text.replace("@%s@" % name, str(value))
    return json.loads(text)


BLIT = """#version 330
uniform sampler2D InSampler;
out vec4 fragColor;
void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
}
"""


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
            "const int MCV2_BYTES_HEIGHT = %d;" % placeholders(width, height, slots)["BYTES_HEIGHT"],
            "const int MCV2_CELLS_WIDTH = %d;" % cells_width(width),
            "const int MCV2_CELLS_HEIGHT = %d;" % cells_height(height),
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
    """The pack's post chain as the client runs it on one rendered frame, with its targets kept between frames."""

    def __init__(self, context, width, height, slots):
        self.context = context
        self.width, self.height, self.slots = width, height, slots
        self.includes = generated(width, height, slots)
        self.programs = {}
        self.framebuffers = {}
        self.arrays = {}
        chain = post_chain(width, height, slots)
        self.passes = chain["passes"]
        make = lambda w, h: context.texture((w, h), 4, dtype="f1")
        self.main = make(*SCREEN)
        self.depth = context.depth_texture(SCREEN)
        # the client's depth sampler reads depths, it does not compare them
        self.depth.compare_func = ""
        framebuffer = context.framebuffer(depth_attachment=self.depth)
        framebuffer.clear(depth=0.0)
        framebuffer.release()
        self.targets = {"minecraft:main": self.main, "minecraft:entity_outline": make(*SCREEN)}
        self.persistent = []
        for name, spec in chain["targets"].items():
            # a target without a size has the screen's
            self.targets[name] = make(spec.get("width", SCREEN[0]), spec.get("height", SCREEN[1]))
            if spec.get("persistent"):
                self.persistent.append(name)
        for texture in self.targets.values():
            texture.filter = (context.NEAREST, context.NEAREST)
            texture.write(bytes(texture.width * texture.height * 4))
        self.blit = context.program(vertex_shader=VERTEX, fragment_shader=BLIT)

    def target(self, name):
        """A target by the short name shader_check has always used: previous, key, status, ..."""
        return self.targets[name if ":" in name else "mcav:mcv2_" + name]

    def program(self, name, vertex="minecraft:core/screenquad"):
        """A pass's program: its fragment shader, and its vertex shader when the pass names one of the pack's instead
        of Minecraft's screen quad, which is VERTEX."""
        key = (name, vertex)
        if key not in self.programs:
            source = (PACK / "assets/mcav/shaders/post" / (name + ".fsh")).read_text()
            if vertex == "minecraft:core/screenquad":
                vertex_source = VERTEX
            else:
                namespace, path = vertex.split(":")
                vertex_source = resolve((PACK / "assets" / namespace / "shaders" / (path + ".vsh")).read_text(), self.includes)
            self.programs[key] = self.context.program(vertex_shader=vertex_source, fragment_shader=resolve(source, self.includes))
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
                name = "blit %s -> %s" % (step["inputs"][0]["target"].split(":")[-1], step["output"].split(":")[-1])
                steps.append((name, self.blit, inputs, step["output"]))
            elif shader.startswith("mcav:post/"):
                program = self.program(shader.split("/")[-1], step.get("vertex_shader", "minecraft:core/screenquad"))
                steps.append((shader.split("/")[-1], program, inputs, step["output"]))
        return steps

    def reset(self):
        """A client that has decoded nothing: every persistent target zero."""
        for name in self.persistent:
            texture = self.targets[name]
            texture.write(bytes(texture.width * texture.height * 4))

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
        for _, program, inputs, output in self.steps():
            self.draw(program, inputs, output)
        status = np.frombuffer(self.target("status").read(), np.uint8)
        picture = np.frombuffer(self.target("previous").read(), np.uint8).reshape(self.height, self.width, 4)[:, :, :3]
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
    parser.add_argument("--backend", choices=("egl", "glx"), default=None)
    parser.add_argument("--pack", type=Path, help="another pack source folder (default: mcav-bukkit's)")
    arguments = parser.parse_args()
    if arguments.pack:
        global PACK
        PACK = arguments.pack
    sys.path.insert(0, arguments.codec)
    import moderngl
    from mcvideo.decoder import decode
    from mcvideo.transport import make_pages

    context = moderngl.create_standalone_context(require=330, **({"backend": "egl"} if arguments.backend == "egl" else {}))
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
            pages = make_pages(frame, STREAM_ID, 6)
            # a frame with more pages than the screen has slots is never sent (Mcv2Channel.send refuses it), so the
            # client never has it to decode
            decodable = (keyframe or reference_id in (last_id, key_id)) and len(pages) <= arguments.slots
            chain.show(pages[: arguments.slots])
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
