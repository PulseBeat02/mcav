#version 330

// Pass 1: the frame bytes, four to a texel, gathered from the pages in the transport strip. Frame byte o is byte
// 32 + o % 12256 of the page in slot o / 12256. Bytes past the frame's end are whatever the strip holds there; the
// decoder never reads past the length the page headers give.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>

uniform sampler2D MainSampler;

out vec4 fragColor;

uint mcv2FrameByte(ivec2 size, int offset) {
    int page = offset / MCV2_PAGE_CAPACITY;
    ivec3 at = mcv2PageByteAt(size, page, MCV2_PAGE_HEADER + offset % MCV2_PAGE_CAPACITY);
    return mcv2Unorm(texelFetch(MainSampler, at.xy, 0)[at.z]);
}

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int offset = (texel.y * MCV2_BYTES_WIDTH + texel.x) * 4;
    if (offset + 3 >= MCV2_PAGE_SLOTS * MCV2_PAGE_CAPACITY) {
        fragColor = vec4(0.0);
        return;
    }
    fragColor = mcv2Texel(uvec4(mcv2FrameByte(size, offset), mcv2FrameByte(size, offset + 1),
        mcv2FrameByte(size, offset + 2), mcv2FrameByte(size, offset + 3)));
}
