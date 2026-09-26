#version 330

// Pass 11: the outline target without the data frames. The frames that carry the pages glow, which is what makes the
// client run this chain at all; their outline has the team colour MCV2_OUTLINE_COLOR, and removing it here keeps
// them from glowing on screen. Every other outline is kept.

#moj_import <mcav:mcv2_config.glsl>

uniform sampler2D OutlineSampler;

out vec4 fragColor;

void main() {
    vec4 outline = texelFetch(OutlineSampler, ivec2(gl_FragCoord.xy), 0);
    ivec3 color = ivec3(outline.rgb * 255.0 + 0.5);
    fragColor = outline.a > 0.0 && color == MCV2_OUTLINE_COLOR ? vec4(0.0) : outline;
}
