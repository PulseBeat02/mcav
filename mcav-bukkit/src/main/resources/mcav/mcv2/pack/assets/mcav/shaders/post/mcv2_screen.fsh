#version 330

// Pass 10: the picture on the screen. Every pixel of the scene is cast onto the plane of the screen the anchors
// describe; where the ray meets the screen in front of whatever the scene has there, it takes the decoded picture's
// pixel. The transport strip at the top of the screen is covered with the scene row just below it. With
// MCV2_DEBUG_VIEW the picture is also drawn one to one below the strip, which is how the in-game conformance test
// captures it, and to its right one square per page slot (green: a valid page, red: none), one for this frame's
// decision (green: decoded, blue: nothing new, red: a frame that cannot be decoded) and one per byte of the count of
// decoded frames.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D PictureSampler;
uniform sampler2D StateSampler;
uniform sampler2D PagesSampler;
uniform sampler2D StatusSampler;
uniform sampler2D ViewSampler;

// what the vertex shader read once for all pixels: the view pass's flags and box, the screen and the projection
flat in uvec4 ScreenView;
flat in vec4 ScreenTopLeft;
flat in vec4 ScreenRight;
flat in vec4 ScreenDown;
flat in vec4 ScreenProjection0;
flat in vec4 ScreenProjection1;
flat in vec4 ScreenProjection2;
flat in vec4 ScreenProjection3;

out vec4 fragColor;

// The picture's pixel at a position counted from its top-left corner; row y of the picture is row y of the target.
vec4 mcv2Picture(ivec2 position) {
    return vec4(texelFetch(PictureSampler, position, 0).rgb, 1.0);
}

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int fromTop = size.y - 1 - pixel.y;
    int strip = mcv2StripRows(size.x);
    // the strip shows the scene row below it, with that row's depth; the debug view leaves the strip as it is
    ivec2 source = fromTop < strip && !MCV2_DEBUG_VIEW ? mcv2FromTop(size, pixel.x, strip) : pixel;
    vec4 scene = texelFetch(MainSampler, source, 0);
    fragColor = scene;
    if (MCV2_DEBUG_VIEW && fromTop >= strip && fromTop < strip + 24 && pixel.x >= MCV2_VIDEO_WIDTH + 8) {
        int square = (pixel.x - MCV2_VIDEO_WIDTH - 8) / 24;
        if ((pixel.x - MCV2_VIDEO_WIDTH - 8) % 24 < 20 && fromTop - strip < 20) {
            if (square < MCV2_PAGE_SLOTS) {
                bool valid = mcv2Unorm(texelFetch(PagesSampler, ivec2(square * 4, 0), 0).x) == 1u;
                fragColor = valid ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
                return;
            }
            if (square == MCV2_PAGE_SLOTS) {
                uvec4 status = uvec4(texelFetch(StatusSampler, ivec2(0, 0), 0) * 255.0 + 0.5);
                bool valid = mcv2Unorm(texelFetch(PagesSampler, ivec2(0, 0), 0).x) == 1u;
                fragColor = status.x == 1u ? vec4(0.0, 1.0, 0.0, 1.0) : valid ? vec4(0.0, 0.0, 1.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
                return;
            }
            if (square <= MCV2_PAGE_SLOTS + 4) {
                vec4 count = texelFetch(StateSampler, ivec2(3, 0), 0);
                fragColor = vec4(vec3(count[square - MCV2_PAGE_SLOTS - 1]), 1.0);
                return;
            }
        }
    }
    uint view = ScreenView.x;
    if ((view & 1u) == 0u) {
        return;
    }
    if (MCV2_DEBUG_VIEW && fromTop >= strip && fromTop < strip + MCV2_VIDEO_HEIGHT && pixel.x < MCV2_VIDEO_WIDTH) {
        fragColor = mcv2Picture(ivec2(pixel.x, fromTop - strip));
        return;
    }
    if ((view & 2u) == 0u) {
        return;
    }
    // outside the box of pixels the screen can cover, the scene stays; the box is exact up to a margin wider than
    // any rounding of the corners it was built from
    if ((view & 4u) != 0u) {
        uint first = ScreenView.y;
        uint last = ScreenView.z;
        if (pixel.x < int(first & 65535u) || pixel.y < int(first >> 16u) || pixel.x > int(last & 65535u) || pixel.y > int(last >> 16u)) {
            return;
        }
    }
    vec3 topLeft = ScreenTopLeft.xyz;
    vec3 right = ScreenRight.xyz;
    vec3 down = ScreenDown.xyz;
    vec2 cells = vec2(ScreenTopLeft.w, ScreenRight.w);
    mat4 projection = mat4(ScreenProjection0, ScreenProjection1, ScreenProjection2, ScreenProjection3);
    // the view ray through this pixel, and where it meets the screen's plane
    vec2 ndc = (vec2(pixel) + 0.5) / vec2(size) * 2.0 - 1.0;
    vec4 far = inverse(projection) * vec4(ndc, 0.5, 1.0);
    vec3 direction = far.xyz / far.w;
    vec3 normal = cross(right, down);
    float denominator = dot(direction, normal);
    if (abs(denominator) < 1e-12) {
        return;
    }
    float t = dot(topLeft, normal) / denominator;
    vec3 hit = direction * t;
    vec3 local = hit - topLeft;
    vec2 uv = vec2(dot(local, right) / dot(right, right), dot(local, down) / dot(down, down)) / cells;
    if (t <= 0.0 || any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) {
        return;
    }
    vec4 clip = projection * vec4(hit, 1.0);
    float depth = clip.z / clip.w;
    // depth is zero-to-one where the projection's depth row says so, otherwise it comes from -1..1
    float planeDepth = abs(projection[2][2]) < 0.5 ? depth : depth * 0.5 + 0.5;
    float sceneDepth = texelFetch(MainDepthSampler, source, 0).r;
    // reversed depth: larger is nearer, and the scene wins only where it is in front of the screen
    if (sceneDepth > planeDepth * 1.00001) {
        return;
    }
    ivec2 video = ivec2(MCV2_VIDEO_WIDTH, MCV2_VIDEO_HEIGHT);
    fragColor = mcv2Picture(min(ivec2(floor(uv * vec2(video))), video - 1));
}
