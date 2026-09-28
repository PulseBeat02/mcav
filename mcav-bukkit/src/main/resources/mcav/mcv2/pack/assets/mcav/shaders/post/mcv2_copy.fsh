#version 330
#extension GL_ARB_separate_shader_objects : require

// Copies a target into another of the same size, texel for texel. The pack's reference pictures, keyframe and state
// must not change between two decodes, and Minecraft 26.3's post/blit, which samples with texture() and scales by a
// colour factor, loses a level here and there on every rendered frame when it copies the video-sized targets.

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
}
