#version 330

// Pass 7: the keyframe reference: the picture just decoded when it was a keyframe, otherwise the one kept so far.

uniform sampler2D NextSampler;
uniform sampler2D KeySampler;
uniform sampler2D StatusSampler;

out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    uvec4 status = uvec4(texelFetch(StatusSampler, ivec2(0, 0), 0) * 255.0 + 0.5);
    fragColor = status.x == 1u && status.y == 1u ? texelFetch(NextSampler, pixel, 0) : texelFetch(KeySampler, pixel, 0);
}
