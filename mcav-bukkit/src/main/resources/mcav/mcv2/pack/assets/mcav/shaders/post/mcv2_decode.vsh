#version 330

// Pass 6's vertex shader: Minecraft's screen quad, and the facts every pixel of the decode pass shares, read once per
// vertex instead of once per pixel: the status texel, and from the resolve pass's frame row the flags, the global
// motion, the payload start and the table bases, with the frame's length from the status.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>

uniform sampler2D StatusSampler;
uniform sampler2D CellsSampler;

out vec2 texCoord;
flat out uvec4 DecodeStatus;
flat out uvec4 DecodeFrame;
flat out uvec4 DecodeTables;

uint mcv2FrameFact(int x) {
    return mcv2TexelWord(texelFetch(CellsSampler, ivec2(x, MCV2_CELLS_HEIGHT), 0));
}

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
    texCoord = uv;
    DecodeStatus = uvec4(texelFetch(StatusSampler, ivec2(0, 0), 0) * 255.0 + 0.5);
    DecodeFrame = uvec4(mcv2FrameFact(0), mcv2FrameFact(5), mcv2TexelWord(texelFetch(StatusSampler, ivec2(2, 0), 0)),
        mcv2FrameFact(1));
    DecodeTables = uvec4(mcv2FrameFact(2), mcv2FrameFact(3), mcv2FrameFact(4), 0u);
}
