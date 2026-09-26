#version 330

// Pass 10's vertex shader: Minecraft's screen quad, and what every pixel of the screen pass shares, read once per
// vertex instead of once per pixel: the view pass's flags and box, the screen's corner, its cell vectors and its size
// in cells, and the camera's projection as four flat columns (a flat mat4 varying crashes Mesa's llvmpipe). The
// fragment shader still inverts the projection itself: an inverse computed here rounds differently on the Intel UHD
// 630 and would move the screen's edges by a pixel where a ray grazes them.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>

uniform sampler2D ViewSampler;

out vec2 texCoord;
flat out uvec4 ScreenView;
flat out vec4 ScreenTopLeft;
flat out vec4 ScreenRight;
flat out vec4 ScreenDown;
flat out vec4 ScreenProjection0;
flat out vec4 ScreenProjection1;
flat out vec4 ScreenProjection2;
flat out vec4 ScreenProjection3;

uint mcv2View(int x) {
    return mcv2TexelWord(texelFetch(ViewSampler, ivec2(x, 0), 0));
}

float mcv2DescriptorFloat(int index) {
    return uintBitsToFloat(mcv2View(3 + index));
}

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);
    texCoord = uv;
    ScreenView = uvec4(mcv2View(0), mcv2View(1), mcv2View(2), 0u);
    // the corner with the width in cells, the cell to the right with the height in cells, the cell down
    ScreenTopLeft = vec4(mcv2DescriptorFloat(0), mcv2DescriptorFloat(1), mcv2DescriptorFloat(2), mcv2DescriptorFloat(3));
    ScreenRight = vec4(mcv2DescriptorFloat(4), mcv2DescriptorFloat(5), mcv2DescriptorFloat(6), mcv2DescriptorFloat(7));
    ScreenDown = vec4(mcv2DescriptorFloat(8), mcv2DescriptorFloat(9), mcv2DescriptorFloat(10), 0.0);
    mat4 projection;
    for (int i = 0; i < 16; ++i) {
        projection[i / 4][i % 4] = mcv2DescriptorFloat(12 + i);
    }
    ScreenProjection0 = projection[0];
    ScreenProjection1 = projection[1];
    ScreenProjection2 = projection[2];
    ScreenProjection3 = projection[3];
}
