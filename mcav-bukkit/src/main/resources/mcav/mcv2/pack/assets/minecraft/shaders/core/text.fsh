#version 330

// Vanilla 26.2 core/text.fsh, plus MCV2: the fragments of a relocated transport page carry the page's bytes, three
// to a pixel, and those of a relocated anchor the screen descriptor. Every other text is drawn exactly as by
// vanilla.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_alphabet.glsl>
#moj_import <mcav:mcv2_symbols.glsl>
#moj_import <mcav:mcv2_strip.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif

in vec4 vertexColor;
in vec2 texCoord0;
flat in int mcv2Kind;
flat in int mcv2Slot;
flat in vec4 mcv2A;
flat in vec4 mcv2B;
flat in vec4 mcv2C;
flat in vec4 mcv2P0;
flat in vec4 mcv2P1;
flat in vec4 mcv2P2;
flat in vec4 mcv2P3;

out vec4 fragColor;

// The 28 floats of the descriptor: the screen's top-left corner, right and down vectors with its size and the
// anchor's cell, then the projection matrix.
float mcv2DescriptorFloat(int index) {
    if (index < 12) {
        vec4 v = index < 4 ? mcv2A : index < 8 ? mcv2B : mcv2C;
        return v[index % 4];
    }
    int m = index - 12;
    vec4 column = m < 4 ? mcv2P0 : m < 8 ? mcv2P1 : m < 12 ? mcv2P2 : mcv2P3;
    return column[m % 4];
}

void main() {
    if (mcv2Kind == 1) {
        // alpha 1, so the translucent blend stores the bytes exactly
        int width = int(ScreenSize.x);
        int row = int(ScreenSize.y) - 1 - int(gl_FragCoord.y) - mcv2Slot * mcv2RowsPerPage(width);
        int pixel = row * width + int(gl_FragCoord.x);
        if (row < 0 || pixel >= MCV2_PAGE_PIXELS) {
            discard;
        }
        uint bits = 0u;
        for (int i = 0; i < 4; ++i) {
            bits |= uint(max(mcv2SymbolAt(Sampler0, pixel * 4 + i), 0)) << uint(6 * i);
        }
        fragColor = mcv2Texel(uvec4(bits & 255u, (bits >> 8u) & 255u, bits >> 16u, 255u));
        return;
    }
    if (mcv2Kind == 2) {
        int x = int(gl_FragCoord.x);
        if (x == 0) {
            fragColor = mcv2Texel(uvec4(0x4Du, 0x43u, 0x56u, 255u));
        } else if (x == 1) {
            fragColor = mcv2Texel(uvec4(0xA1u, 0u, 0u, 255u));
        } else if ((x - 2) / 2 < 28) {
            uint bits = floatBitsToUint(mcv2DescriptorFloat((x - 2) / 2));
            fragColor = x % 2 == 0 ? mcv2Texel(uvec4(bits & 255u, (bits >> 8u) & 255u, (bits >> 16u) & 255u, 255u))
                : mcv2Texel(uvec4(bits >> 24u, 0u, 0u, 255u));
        } else {
            fragColor = mcv2Texel(uvec4(0u, 0u, 0u, 255u));
        }
        return;
    }
#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif

#ifdef IS_SEE_THROUGH
    vec4 color = texColor * vertexColor;
#else
    vec4 color = texColor * vertexColor * ColorModulator;
#endif
    if (color.a < 0.1) {
        discard;
    }

#ifdef IS_SEE_THROUGH
    fragColor = color * ColorModulator;
#elif defined(IS_GUI)
    fragColor = color;
#else
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif
}
