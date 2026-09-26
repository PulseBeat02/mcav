#version 330

// Pass 2: the CRC of every 192-byte chunk of every page slot's strip area, from a zero register, one chunk per
// fragment: the pages pass chains them into each page's CRC instead of one fragment walking all 12,288 bytes of a
// page. The chunk reads each strip pixel once for its three bytes. The CRC covers the header with its own field
// (bytes 28 to 31) zeroed.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>
#moj_import <mcav:mcv2_crc.glsl>

uniform sampler2D MainSampler;

out vec4 fragColor;

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    int x = int(gl_FragCoord.x);
    int page = x / MCV2_CRC_CHUNKS;
    int first = (x % MCV2_CRC_CHUNKS) * MCV2_CRC_CHUNK_BYTES;
    uint crc = 0u;
    for (int b = first; b < first + MCV2_CRC_CHUNK_BYTES; b += 3) {
        ivec3 at = mcv2PageByteAt(size, page, b);
        vec4 texel = texelFetch(MainSampler, at.xy, 0);
        for (int k = 0; k < 3; ++k) {
            int offset = b + k;
            crc = mcv2CrcUpdate(crc, offset >= 28 && offset < 32 ? 0u : mcv2Unorm(texel[k]));
        }
    }
    fragColor = mcv2WordTexel(crc);
}
