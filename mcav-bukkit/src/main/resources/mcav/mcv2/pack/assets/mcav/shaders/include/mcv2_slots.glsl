#ifndef MCAV_MCV2_SLOTS_GLSL
#define MCAV_MCV2_SLOTS_GLSL

// Where one screen's pages and descriptor are in the transport strip. Requires mcv2_config.glsl, mcv2_screen.glsl and
// mcv2_strip.glsl.

// Where byte b (0..12287) of the screen's page p lies: the texel of the screen and the colour channel.
ivec3 mcv2PageByteAt(ivec2 size, int p, int b) {
    int pixel = b / 3;
    int row = mcv2SlotRow(size.x, MCV2_FIRST_SLOT + p) + pixel / size.x;
    return ivec3(mcv2FromTop(size, pixel % size.x, row), b % 3);
}

// The screen's anchor descriptor row.
int mcv2DescriptorRow(int width) {
    return mcv2DescriptorRowOf(width, MCV2_SCREEN_INDEX);
}

#endif
