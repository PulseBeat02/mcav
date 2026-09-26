#version 330

// Pass 9: the screen's geometry for this frame, once instead of at every pixel of the screen pass: the 28 floats of
// the anchor descriptor the text shader writes after the strip, from 56 pixels of the main target, and the box of
// screen pixels the screen can cover. Texel 0 says what the screen pass does: bit 0 when a picture is shown, bit 1
// when the descriptor is there, bit 2 when every corner of the screen lies in front of the camera, so the box holds;
// texels 1 and 2 are the box's lowest and highest pixel, x in the low half and y in the high half; texels 3 to 30 are the
// descriptor's floats as their IEEE bits. The screen pass still inverts the projection at every pixel: a stored inverse
// is 16 more fetches, which measured slower on the Intel UHD 630, and rounds differently from the inline one.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>

uniform sampler2D MainSampler;
uniform sampler2D StateSampler;

out vec4 fragColor;

const int MCV2_VIEW_FLOATS = 3;
// how far past the corners' box a pixel may still meet the screen: the box is built from float projections of the
// corners, and a pixel whose ray meets the screen lies within rounding of the corners' convex hull
const int MCV2_VIEW_MARGIN = 2;

ivec3 mcv2DescriptorBytes(ivec2 size, int row, int x) {
    return ivec3(texelFetch(MainSampler, mcv2FromTop(size, x, row), 0).rgb * 255.0 + 0.5);
}

float mcv2DescriptorFloat(ivec2 size, int row, int index) {
    ivec3 low = mcv2DescriptorBytes(size, row, 2 + index * 2);
    ivec3 high = mcv2DescriptorBytes(size, row, 3 + index * 2);
    return uintBitsToFloat(uint(low.r) | (uint(low.g) << 8u) | (uint(low.b) << 16u) | (uint(high.r) << 24u));
}

mat4 mcv2Projection(ivec2 size, int row) {
    mat4 projection;
    for (int i = 0; i < 16; ++i) {
        projection[i / 4][i % 4] = mcv2DescriptorFloat(size, row, 12 + i);
    }
    return projection;
}

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    int x = int(gl_FragCoord.x);
    int row = mcv2DescriptorRow(size.x);
    if (x >= MCV2_VIEW_FLOATS) {
        fragColor = mcv2WordTexel(floatBitsToUint(mcv2DescriptorFloat(size, row, x - MCV2_VIEW_FLOATS)));
        return;
    }
    bool shown = (uint(texelFetch(StateSampler, ivec2(0, 0), 0).x * 255.0 + 0.5) & 1u) != 0u;
    bool present = mcv2DescriptorBytes(size, row, 0) == ivec3(0x4D, 0x43, 0x56) && mcv2DescriptorBytes(size, row, 1).r == 0xA1;
    // the corners of the screen, projected to pixels
    vec3 topLeft = vec3(mcv2DescriptorFloat(size, row, 0), mcv2DescriptorFloat(size, row, 1), mcv2DescriptorFloat(size, row, 2));
    vec3 right = vec3(mcv2DescriptorFloat(size, row, 4), mcv2DescriptorFloat(size, row, 5), mcv2DescriptorFloat(size, row, 6));
    vec3 down = vec3(mcv2DescriptorFloat(size, row, 8), mcv2DescriptorFloat(size, row, 9), mcv2DescriptorFloat(size, row, 10));
    vec2 cells = vec2(mcv2DescriptorFloat(size, row, 3), mcv2DescriptorFloat(size, row, 7));
    mat4 projection = mcv2Projection(size, row);
    bool front = true;
    vec2 low = vec2(1e9);
    vec2 high = vec2(-1e9);
    for (int corner = 0; corner < 4; ++corner) {
        vec2 at = vec2(corner & 1, corner >> 1) * cells;
        vec4 clip = projection * vec4(topLeft + right * at.x + down * at.y, 1.0);
        front = front && clip.w > 0.0;
        vec2 pixel = (clip.xy / clip.w + 1.0) * 0.5 * vec2(size) - 0.5;
        low = min(low, pixel);
        high = max(high, pixel);
    }
    // a box too far out to be one is not trusted either
    front = front && all(greaterThan(low, vec2(-65536.0))) && all(lessThan(high, vec2(65536.0)));
    ivec2 first = clamp(ivec2(floor(low)) - MCV2_VIEW_MARGIN, ivec2(0), size - 1);
    ivec2 last = clamp(ivec2(ceil(high)) + MCV2_VIEW_MARGIN, ivec2(0), size - 1);
    if (x == 0) {
        fragColor = mcv2WordTexel((shown ? 1u : 0u) | (present ? 2u : 0u) | (front ? 4u : 0u));
    } else if (x == 1) {
        fragColor = mcv2WordTexel(uint(first.x) | (uint(first.y) << 16u));
    } else {
        fragColor = mcv2WordTexel(uint(last.x) | (uint(last.y) << 16u));
    }
}
