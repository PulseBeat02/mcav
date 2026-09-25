#version 330

// Pass 2: the header of the page in every slot, four texels per slot: (valid, frame type, page count), the frame id,
// the reference id and the frame length. A page is valid when its header is the reference's read_page header for
// this stream and slot and its CRC-32 matches; only the first texel of a slot pays for the CRC.

#moj_import <mcav:mcv2_config.glsl>
#moj_import <mcav:mcv2_strip.glsl>
#moj_import <mcav:mcv2_crc.glsl>

uniform sampler2D MainSampler;

out vec4 fragColor;

uint mcv2PageByte(ivec2 size, int page, int b) {
    ivec3 at = mcv2PageByteAt(size, page, b);
    return mcv2Unorm(texelFetch(MainSampler, at.xy, 0)[at.z]);
}

uint mcv2PageWord(ivec2 size, int page, int b) {
    return mcv2PageByte(size, page, b) | (mcv2PageByte(size, page, b + 1) << 8u)
        | (mcv2PageByte(size, page, b + 2) << 16u) | (mcv2PageByte(size, page, b + 3) << 24u);
}

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    int x = int(gl_FragCoord.x);
    int page = x / 4;
    int field = x % 4;
    if (field == 1) {
        fragColor = mcv2WordTexel(mcv2PageWord(size, page, 12));
        return;
    }
    if (field == 2) {
        fragColor = mcv2WordTexel(mcv2PageWord(size, page, 20));
        return;
    }
    if (field == 3) {
        fragColor = mcv2WordTexel(mcv2PageWord(size, page, 24));
        return;
    }
    uint type = mcv2PageByte(size, page, 6) | (mcv2PageByte(size, page, 7) << 8u);
    uint number = mcv2PageByte(size, page, 16) | (mcv2PageByte(size, page, 17) << 8u);
    uint count = mcv2PageByte(size, page, 18) | (mcv2PageByte(size, page, 19) << 8u);
    uint total = mcv2PageWord(size, page, 24);
    uint capacity = uint(MCV2_PAGE_CAPACITY);
    bool valid = mcv2PageWord(size, page, 0) == 0x3150434Du && mcv2PageByte(size, page, 4) == 1u
        && mcv2PageByte(size, page, 5) == 6u && type <= 1u && mcv2PageWord(size, page, 8) == MCV2_STREAM_ID
        && number == uint(page) && total >= 48u && total <= uint(MCV2_PAGE_SLOTS) * capacity
        && count == (total + capacity - 1u) / capacity;
    if (valid) {
        int length = MCV2_PAGE_HEADER + int(min(capacity, total - number * capacity));
        uint crc = 0xFFFFFFFFu;
        for (int b = 0; b < length; ++b) {
            // the CRC covers the header with its own field zeroed
            crc = mcv2CrcUpdate(crc, b >= 28 && b < 32 ? 0u : mcv2PageByte(size, page, b));
        }
        valid = ~crc == mcv2PageWord(size, page, 28);
    }
    fragColor = mcv2Texel(uvec4(valid ? 1u : 0u, type, count & 255u, 255u));
}
