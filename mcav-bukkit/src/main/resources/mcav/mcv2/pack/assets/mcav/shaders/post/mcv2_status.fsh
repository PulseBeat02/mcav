#version 330

// Pass 3: what to do with the strip this frame, four texels: (decode, keyframe, predict from the keyframe
// reference, page count), the frame id, the frame length and the reference id. A frame is decoded when every one of
// its pages is valid and agrees with the others, and it is either a keyframe other than the last decoded frame, or
// a P frame newer than the last decoded frame that predicts from a reference the client holds: the last decoded
// frame, or the last decoded keyframe.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>

uniform sampler2D PagesSampler;
uniform sampler2D StateSampler;

out vec4 fragColor;

uvec4 mcv2Bytes(sampler2D sampler, int x) {
    return uvec4(floor(texelFetch(sampler, ivec2(x, 0), 0) * 255.0 + 0.5));
}

uint mcv2Word(sampler2D sampler, int x) {
    return mcv2TexelWord(texelFetch(sampler, ivec2(x, 0), 0));
}

void main() {
    uvec4 first = mcv2Bytes(PagesSampler, 0);
    bool keyframe = first.y == 1u;
    uint frameId = mcv2Word(PagesSampler, 1);
    uint referenceId = mcv2Word(PagesSampler, 2);
    uint total = mcv2Word(PagesSampler, 3);
    int count = int(first.z);
    bool valid = first.x == 1u && count >= 1 && count <= MCV2_PAGE_SLOTS;
    for (int page = 1; page < MCV2_PAGE_SLOTS; ++page) {
        if (page < count) {
            uvec4 head = mcv2Bytes(PagesSampler, page * 4);
            valid = valid && head.x == 1u && head.y == first.y && head.z == first.z
                && mcv2Word(PagesSampler, page * 4 + 1) == frameId && mcv2Word(PagesSampler, page * 4 + 2) == referenceId
                && mcv2Word(PagesSampler, page * 4 + 3) == total;
        }
    }
    uint flags = mcv2Bytes(StateSampler, 0).x;
    bool previousValid = (flags & 1u) != 0u;
    bool keyValid = (flags & 2u) != 0u;
    uint lastId = mcv2Word(StateSampler, 1);
    uint keyId = mcv2Word(StateSampler, 2);
    // newer in the unsigned 32-bit sequence space, like the Java receiver; a keyframe depends on nothing, so any
    // keyframe other than the last decoded frame is taken, which lets a restarted stream or server start over
    bool newer = !previousValid || (frameId != lastId && (keyframe || frameId - lastId < 0x80000000u));
    bool fromPrevious = previousValid && referenceId == lastId;
    bool fromKey = keyValid && referenceId == keyId;
    bool decode = valid && newer && (keyframe || fromPrevious || fromKey);
    bool useKey = !keyframe && !fromPrevious && fromKey;
    int x = int(gl_FragCoord.x);
    if (x == 0) {
        fragColor = mcv2Texel(uvec4(decode ? 1u : 0u, keyframe ? 1u : 0u, useKey ? 1u : 0u, uint(count)));
    } else if (x == 1) {
        fragColor = mcv2WordTexel(frameId);
    } else if (x == 2) {
        fragColor = mcv2WordTexel(total);
    } else {
        fragColor = mcv2WordTexel(referenceId);
    }
}
