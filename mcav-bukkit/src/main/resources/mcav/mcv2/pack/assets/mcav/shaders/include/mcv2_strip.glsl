#ifndef MCAV_MCV2_STRIP_GLSL
#define MCAV_MCV2_STRIP_GLSL

// The transport strip: the text shaders move every MCV2 page map to a band at the top of the screen, where the post
// chain reads it back. Four six-bit symbols make three page bytes, so a page's 16,384 symbols fill 4,096 pixels,
// written row by row across the full width of the screen from the top of its slot; slot s starts at row
// s * mcv2RowsPerPage(width), counted from the top. The screens of the pack own consecutive runs of slots, in their
// order, and after the last slot comes one anchor descriptor row per screen. Requires mcv2_config.glsl.

const int MCV2_PAGE_PIXELS = 4096;
const int MCV2_PAGE_BYTES = 12288;
const int MCV2_PAGE_HEADER = 32;
const int MCV2_PAGE_CAPACITY = 12256;
const int MCV2_DESCRIPTOR_PIXELS = 64;

int mcv2RowsPerPage(int width) {
    return (MCV2_PAGE_PIXELS + width - 1) / width;
}

// The first row of slot s, counted from the top.
int mcv2SlotRow(int width, int slot) {
    return slot * mcv2RowsPerPage(width);
}

// The anchor descriptor row of a screen of the pack.
int mcv2DescriptorRowOf(int width, int screen) {
    return mcv2SlotRow(width, MCV2_TOTAL_SLOTS) + screen;
}

// Rows of the screen the strip covers, counted from the top.
int mcv2StripRows(int width) {
    return mcv2DescriptorRowOf(width, MCV2_SCREENS);
}

// The texel of a screen-sized target at a row counted from the top.
ivec2 mcv2FromTop(ivec2 size, int x, int row) {
    return ivec2(x, size.y - 1 - row);
}


// A UNORM8 channel back to its byte: value * 255 + 0.5 lies in [k + 0.5 - 2^-16, k + 0.5 + 2^-16] for byte k, and the
// conversion to an integer drops the fraction, which is floor for a positive value, one instruction fewer.
uint mcv2Unorm(float value) {
    return uint(value * 255.0 + 0.5);
}

vec4 mcv2Texel(uvec4 bytes) {
    return vec4(bytes) / 255.0;
}

vec4 mcv2WordTexel(uint value) {
    return mcv2Texel(uvec4(value & 255u, (value >> 8u) & 255u, (value >> 16u) & 255u, value >> 24u));
}

uint mcv2TexelWord(vec4 texel) {
    uvec4 b = uvec4(texel * 255.0 + 0.5);
    return b.x | (b.y << 8u) | (b.z << 16u) | (b.w << 24u);
}

#endif
