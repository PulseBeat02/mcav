#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla 26.3 core/text.fsh, plus MCV2: the fragments of a relocated transport page carry the page's bytes, three
// to a pixel, and those of a relocated anchor the screen descriptor. Every other text is drawn exactly as by
// vanilla.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:globals.glsl>
#include <mcav:mcv2_config.glsl>
#include <mcav:mcv2_alphabet.glsl>
#include <mcav:mcv2_symbols.glsl>
#include <mcav:mcv2_strip.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif

layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) flat in int mcv2Kind;
layout(location = 5) flat in int mcv2Slot;
layout(location = 6) flat in vec4 mcv2A;
layout(location = 7) flat in vec4 mcv2B;
layout(location = 8) flat in vec4 mcv2C;
layout(location = 9) flat in vec4 mcv2P0;
layout(location = 10) flat in vec4 mcv2P1;
layout(location = 11) flat in vec4 mcv2P2;
layout(location = 12) flat in vec4 mcv2P3;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif

    #if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)

    #ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
    #endif

    return color;
}

#if !defined(OIT_ALPHA_ONLY) && !defined(OIT_ACCUMULATE)
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

void mcv2WritePage() {
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
}

void mcv2WriteDescriptor() {
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
}
#endif

void main() {
    if (mcv2Kind != 0) {
        #if defined(OIT_ALPHA_ONLY) || defined(OIT_ACCUMULATE)
        // with improved transparency, text is drawn into the transparency targets, where a page's bytes cannot reach
        // the screen unchanged
        discard;
        #else
        if (mcv2Kind == 1) {
            mcv2WritePage();
        } else {
            mcv2WriteDescriptor();
        }
        return;
        #endif
    }

    #ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
    #else
    vec4 texColor = texture(Sampler0, texCoord0);
    #endif

    vec4 color = texColor * vertexColor * ColorModulator;

    if (color.a < 0.1) {
        discard;
    }

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
