#version 330

// Pass 6: the next picture. When the status says so, every pixel is reconstructed by the reference decoder from the
// frame bytes, the reference the frame predicts from and the leaf the resolve pass found for its 8x8 cell; otherwise
// the last picture is kept. The result is quantized to RGB8, which the reference defines as the decoded picture.
// SKIP and local motion, most of a P frame, take a short path: the reference at the global motion, or at the global
// motion plus the two motion bytes the resolve pass unpacked into the cell, the prediction the reference makes for
// them; everything else reads what it needs of the frame. The facts all pixels share come from the vertex shader.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>
#moj_import <mcav:mcv2_books.glsl>

uniform sampler2D BytesSampler;
uniform sampler2D StatusSampler;
uniform sampler2D CellsSampler;
uniform sampler2D PreviousSampler;
uniform sampler2D KeySampler;

flat in uvec4 DecodeStatus;
flat in uvec4 DecodeFrame;
flat in uvec4 DecodeTables;

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

uint mcv2CellWord(int x, int y) {
    return mcv2TexelWord(texelFetch(CellsSampler, ivec2(x, y), 0));
}

vec3 mcv2Decode(ivec2 pixel) {
    uint flags = DecodeFrame.x;
    uint cell = mcv2CellWord(pixel.x / 8, pixel.y / 8);
    if ((flags & 0x80000000u) == 0u || cell == MCVIDEO_CELL_INVALID) {
        return mcvideoFallback(pixel);
    }
    uint word;
    int blockSize;
    mcvideoCellLeaf(cell, word, blockSize);
    uint mode = (word >> 24u) & 31u;
    if ((flags & MCVIDEO_KEYFRAME) == 0u && (word == 0u || mode == MCVIDEO_MODE_MOTION)) {
        ivec2 motion = mcvideoMotion(DecodeFrame.y);
        if (mode == MCVIDEO_MODE_MOTION) {
            motion += ivec2(mcvideoSigned(word & 255u, 8u), mcvideoSigned((word >> 8u) & 255u, 8u));
        }
        return mcvideoPredict(pixel, motion);
    }
    DataBytes = int(DecodeFrame.z);
    McvideoFrame frame;
    mcvideoFrameFromWords(flags, DecodeFrame.w, DecodeTables.x, DecodeTables.y, DecodeTables.z, frame);
    return mcvideoLeaf(pixel, frame, word, blockSize);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    uvec4 status = DecodeStatus;
    if (status.x != 1u) {
        fragColor = texelFetch(PreviousSampler, pixel, 0);
        return;
    }
    OutputSize = ivec2(MCV2_VIDEO_WIDTH, MCV2_VIDEO_HEIGHT);
    FrameReady = true;
    ReferenceValid = status.y == 0u;
    UseKey = status.z == 1u;
    vec3 color = mcv2Decode(pixel);
    fragColor = vec4(floor(clamp(color, 0.0, 255.0) + 0.5) / 255.0, 1.0);
}
