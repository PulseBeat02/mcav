#version 330

// The transport strip: the text shaders move every MCV2 page map to a band at the top of the screen, where the post
// chain reads it back. Four six-bit symbols make three page bytes, so a page's 16,384 symbols fill 4,096 pixels,
// written row by row across the full width of the screen from the top of its slot; slot p starts at row
// p * mcv2RowsPerPage(width), counted from the top. The anchor descriptor row follows the last slot.
// Requires mcv2_config.glsl.

const int MCV2_PAGE_PIXELS = 4096;
const int MCV2_PAGE_BYTES = 12288;
const int MCV2_PAGE_HEADER = 32;
const int MCV2_PAGE_CAPACITY = 12256;
const int MCV2_DESCRIPTOR_PIXELS = 64;

int mcv2RowsPerPage(int width) {
    return (MCV2_PAGE_PIXELS + width - 1) / width;
}

int mcv2DescriptorRow(int width) {
    return MCV2_PAGE_SLOTS * mcv2RowsPerPage(width);
}

// Rows of the screen the strip covers, counted from the top.
int mcv2StripRows(int width) {
    return mcv2DescriptorRow(width) + 1;
}

// The texel of a screen-sized target at a row counted from the top.
ivec2 mcv2FromTop(ivec2 size, int x, int row) {
    return ivec2(x, size.y - 1 - row);
}

// Where byte b (0..12287) of the page in slot p lies: the texel of the screen and the colour channel.
ivec3 mcv2PageByteAt(ivec2 size, int p, int b) {
    int pixel = b / 3;
    int row = p * mcv2RowsPerPage(size.x) + pixel / size.x;
    return ivec3(mcv2FromTop(size, pixel % size.x, row), b % 3);
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
