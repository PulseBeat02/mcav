#version 330

// Pass 5: the leaf of every 8x8 cell of the next picture, once per cell instead of once per pixel. Every pixel of a
// cell lies in the same leaf, so the frame's header checks and the walk that finds the leaf - the descriptor, the
// split prefix and the payload cursor - give the same answer for all 64 of them; this pass finds it once and the
// decode pass reads it. The row after the cells holds the frame's own facts. Nothing is resolved when the status
// does not decode a frame, and the decode pass then reads nothing here.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>
#moj_import <mcav:mcv2_books.glsl>

uniform sampler2D BytesSampler;
uniform sampler2D StatusSampler;
uniform sampler2D PreviousSampler;

out vec4 fragColor;

int DataBytes = 0;
ivec2 OutputSize = ivec2(0);
bool FrameReady = false;
bool ReferenceValid = false;
uint ReferenceId = 0u;

vec4 mcv2ReferenceFetch(ivec2 pixel) {
    return texelFetch(PreviousSampler, pixel, 0);
}

ivec2 mcv2ReferenceSize() {
    return textureSize(PreviousSampler, 0);
}

#moj_import <mcav:mcv2_codec.glsl>

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    uvec4 status = uvec4(texelFetch(StatusSampler, ivec2(0, 0), 0) * 255.0 + 0.5);
    if (status.x != 1u) {
        fragColor = vec4(0.0);
        return;
    }
    DataBytes = int(mcv2TexelWord(texelFetch(StatusSampler, ivec2(2, 0), 0)));
    OutputSize = ivec2(MCV2_VIDEO_WIDTH, MCV2_VIDEO_HEIGHT);
    FrameReady = true;
    ReferenceValid = status.y == 0u;
    ReferenceId = mcv2TexelWord(texelFetch(StatusSampler, ivec2(3, 0), 0));
    McvideoFrame frame;
    bool valid = mcvideoFrame(frame);
    if (texel.y == MCV2_CELLS_HEIGHT) {
        fragColor = mcv2WordTexel(mcvideoFrameWord(valid, frame, texel.x));
        return;
    }
    uint word = 0u;
    int blockSize = 32;
    bool resolved = valid && mcvideoResolve(texel * 8, frame, word, blockSize);
    fragColor = mcv2WordTexel(mcvideoCellWord(resolved, frame, word, blockSize));
}
