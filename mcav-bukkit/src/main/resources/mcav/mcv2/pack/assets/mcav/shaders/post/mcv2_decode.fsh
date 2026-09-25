#version 330

// Pass 4: the next picture. When the status says so, every pixel is decoded by the reference decoder from the frame
// bytes and the reference the frame predicts from; otherwise the last picture is kept. The result is quantized to
// RGB8, which the reference defines as the decoded picture.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>
#moj_import <mcav:mcv2_books.glsl>

uniform sampler2D BytesSampler;
uniform sampler2D StatusSampler;
uniform sampler2D PreviousSampler;
uniform sampler2D KeySampler;

out vec4 fragColor;

int DataBytes = 0;
ivec2 OutputSize = ivec2(0);
bool FrameReady = false;
bool ReferenceValid = false;
uint ReferenceId = 0u;
bool UseKey = false;

vec4 mcv2ReferenceFetch(ivec2 pixel) {
    return UseKey ? texelFetch(KeySampler, pixel, 0) : texelFetch(PreviousSampler, pixel, 0);
}

ivec2 mcv2ReferenceSize() {
    return textureSize(PreviousSampler, 0);
}

#moj_import <mcav:mcv2_codec.glsl>

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    uvec4 status = uvec4(floor(texelFetch(StatusSampler, ivec2(0, 0), 0) * 255.0 + 0.5));
    if (status.x != 1u) {
        fragColor = texelFetch(PreviousSampler, pixel, 0);
        return;
    }
    DataBytes = int(mcv2TexelWord(texelFetch(StatusSampler, ivec2(2, 0), 0)));
    OutputSize = ivec2(MCV2_VIDEO_WIDTH, MCV2_VIDEO_HEIGHT);
    FrameReady = true;
    ReferenceValid = status.y == 0u;
    ReferenceId = mcv2TexelWord(texelFetch(StatusSampler, ivec2(3, 0), 0));
    UseKey = status.z == 1u;
    vec3 color = mcvideoDecode(pixel);
    fragColor = vec4(floor(clamp(color, 0.0, 255.0) + 0.5) / 255.0, 1.0);
}
