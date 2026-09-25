#version 330

// Vanilla 26.2 core/text.vsh, plus MCV2: a map that is a transport page of this pack's stream is moved to its slot
// of the transport strip at the top of the screen, and an anchor map is moved to the descriptor row after it,
// carrying the screen's position and orientation in view space for the post chain. Every other text is drawn
// exactly as by vanilla.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_alphabet.glsl>
#moj_import <mcav:mcv2_symbols.glsl>
#moj_import <mcav:mcv2_strip.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in ivec2 UV2;
#endif

uniform sampler2D Sampler0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif

out vec4 vertexColor;
out vec2 texCoord0;
// 0 for ordinary text, 1 for a transport page, 2 for an anchor
flat out int mcv2Kind;
flat out int mcv2Slot;
flat out vec4 mcv2A;
flat out vec4 mcv2B;
flat out vec4 mcv2C;
// the projection matrix by columns: a flat mat4 varying crashes Mesa's llvmpipe here
flat out vec4 mcv2P0;
flat out vec4 mcv2P1;
flat out vec4 mcv2P2;
flat out vec4 mcv2P3;

// Places a quad's corner, given by its texture coordinate, on the screen rectangle from the top-left pixel (x0, y0)
// counted from the top to (x1, y1), in front of everything drawn so far.
vec4 mcv2Place(vec2 uv, float x0, float y0, float x1, float y1) {
    vec2 size = ScreenSize;
    float left = x0 / size.x * 2.0 - 1.0;
    float right = x1 / size.x * 2.0 - 1.0;
    float top = 1.0 - y0 / size.y * 2.0;
    float bottom = 1.0 - y1 / size.y * 2.0;
    return vec4(mix(left, right, uv.x), mix(top, bottom, uv.y), 0.999, 1.0);
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;

    mcv2Kind = 0;
    mcv2Slot = 0;
    mcv2A = vec4(0.0);
    mcv2B = vec4(0.0);
    mcv2C = vec4(0.0);
    mcv2P0 = vec4(0.0);
    mcv2P1 = vec4(0.0);
    mcv2P2 = vec4(0.0);
    mcv2P3 = vec4(0.0);
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH) && !defined(IS_GRAYSCALE)
    if (textureSize(Sampler0, 0) != ivec2(128, 128)) {
        return;
    }
    int width = int(ScreenSize.x);
    int rows = mcv2RowsPerPage(width);
    if (mcv2IsPage(Sampler0)) {
        // the page number, header bytes 16 and 17
        int slot = mcv2PageBits(Sampler0, 128, 16);
        if (slot < MCV2_PAGE_SLOTS) {
            gl_Position = mcv2Place(UV0, 0.0, float(slot * rows), float(width), float((slot + 1) * rows));
            mcv2Kind = 1;
            mcv2Slot = slot;
        }
        return;
    }
    if (!mcv2IsAnchor(Sampler0)) {
        return;
    }
    int column = mcv2SymbolAt(Sampler0, 8);
    int row = mcv2SymbolAt(Sampler0, 9);
    int columns = mcv2SymbolAt(Sampler0, 10);
    int screenRows = mcv2SymbolAt(Sampler0, 11);
    int facing = mcv2SymbolAt(Sampler0, 12);
    int check = mcv2SymbolAt(Sampler0, 13);
    if (((column + row + columns + screenRows + facing) & 63) != check || facing > 3) {
        return;
    }
    // one block along the map's right and down edges in world space; frames hang on vertical walls, and facing
    // counts the directions the map's right edge can point to: east, south, west, north
    vec3 right = facing == 0 ? vec3(1.0, 0.0, 0.0) : facing == 1 ? vec3(0.0, 0.0, 1.0)
        : facing == 2 ? vec3(-1.0, 0.0, 0.0) : vec3(0.0, 0.0, -1.0);
    vec3 down = vec3(0.0, -1.0, 0.0);
    vec3 corner = (ModelViewMat * vec4(Position, 1.0)).xyz;
    vec3 r = (ModelViewMat * vec4(right, 0.0)).xyz;
    vec3 d = (ModelViewMat * vec4(down, 0.0)).xyz;
    vec3 tileTopLeft = corner - UV0.x * r - UV0.y * d;
    vec3 screenTopLeft = tileTopLeft - float(column) * r - float(row) * d;
    mcv2A = vec4(screenTopLeft, float(columns));
    mcv2B = vec4(r, float(screenRows));
    mcv2C = vec4(d, float(column * 64 + row));
    mcv2P0 = ProjMat[0];
    mcv2P1 = ProjMat[1];
    mcv2P2 = ProjMat[2];
    mcv2P3 = ProjMat[3];
    float descriptor = float(mcv2DescriptorRow(width));
    gl_Position = mcv2Place(UV0, 0.0, descriptor, float(MCV2_DESCRIPTOR_PIXELS), descriptor + 1.0);
    mcv2Kind = 2;
#endif
}
