#version 330

// Pass 8: the decoder state, kept in a persistent target, four texels: (1 when a picture was decoded, plus 2 when a
// keyframe was), the id of the last decoded frame, the id of the last decoded keyframe, and how many frames were
// decoded. A new target is all zero, which is the state of a client that has decoded nothing.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>

uniform sampler2D StateSampler;
uniform sampler2D StatusSampler;

out vec4 fragColor;

void main() {
    int x = int(gl_FragCoord.x);
    vec4 kept = texelFetch(StateSampler, ivec2(x, 0), 0);
    uvec4 status = uvec4(texelFetch(StatusSampler, ivec2(0, 0), 0) * 255.0 + 0.5);
    if (status.x != 1u) {
        fragColor = kept;
        return;
    }
    bool keyframe = status.y == 1u;
    uint frameId = mcv2TexelWord(texelFetch(StatusSampler, ivec2(1, 0), 0));
    if (x == 0) {
        uint flags = uint(kept.x * 255.0 + 0.5) | 1u | (keyframe ? 2u : 0u);
        fragColor = mcv2Texel(uvec4(flags, 0u, 0u, 255u));
    } else if (x == 1) {
        fragColor = mcv2WordTexel(frameId);
    } else if (x == 2) {
        fragColor = keyframe ? mcv2WordTexel(frameId) : kept;
    } else {
        fragColor = mcv2WordTexel(mcv2TexelWord(kept) + 1u);
    }
}
