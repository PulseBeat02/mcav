/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef MCAV_MCV2_GLSL
#define MCAV_MCV2_GLSL

const int MCV2_PAGE_PIXELS = 4096;
const int MCV2_PAGE_HEADER = 32;
const int MCV2_PAGE_CAPACITY = 12256;
const int MCV2_DESCRIPTOR_PIXELS = 64;

const int MCV2_HEADER_BYTES = 20;
const int MCV2_MAX_FRAME_BYTES = 131071;
const uint MCV2_KEYFRAME = 1u;
const uint MCV2_SKIP = 0u;
const uint MCV2_MOTION = 1u;
const uint MCV2_SOLID = 2u;
const uint MCV2_PALETTE = 3u;
const uint MCV2_PATTERN = 4u;
const uint MCV2_COMPACT = 5u;
const uint MCV2_SPLIT = 6u;
const uint MCV2_CELL_INVALID = 0xffffffffu;
const uint MCV2_CELL_OFFSET = 0x1ffffu;

int mcv2RowsPerPage(int width) {
    return (MCV2_PAGE_PIXELS + width - 1) / width;
}

int mcv2SlotRow(int width, int slot) {
    return slot * mcv2RowsPerPage(width);
}

int mcv2DescriptorRowOf(int width, int screen) {
    return mcv2SlotRow(width, MCV2_TOTAL_SLOTS) + screen;
}

int mcv2StripRows(int width) {
    return mcv2DescriptorRowOf(width, MCV2_SCREENS);
}

bool mcv2StripFits(ivec2 size) {
    return mcv2StripRows(size.x) < size.y;
}

ivec2 mcv2FromTop(ivec2 size, int columnIndex, int row) {
    return ivec2(columnIndex, size.y - 1 - row);
}

uint mcv2ByteFromChannel(float value) {
    return uint(value * 255.0 + 0.5);
}

vec4 mcv2Texel(uvec4 bytes) {
    return vec4(bytes) / 255.0;
}

vec4 mcv2WordTexel(uint value) {
    return mcv2Texel(uvec4(value & 255u, (value >> 8u) & 255u, (value >> 16u) & 255u, value >> 24u));
}

uint mcv2TexelWord(vec4 texel) {
    uvec4 channels = uvec4(texel * 255.0 + 0.5);
    return channels.x | (channels.y << 8u) | (channels.z << 16u) | (channels.w << 24u);
}

#if defined(MCV2_PASS_BYTES) || defined(MCV2_PASS_CRC) || defined(MCV2_PASS_PAGES) || defined(MCV2_PASS_VIEW)

ivec3 mcv2PageByteAt(ivec2 size, int pageIndex, int byteIndex) {
    int pixel = byteIndex / 3;
    int row = mcv2SlotRow(size.x, MCV2_FIRST_SLOT + pageIndex) + pixel / size.x;
    return ivec3(mcv2FromTop(size, pixel % size.x, row), byteIndex % 3);
}

int mcv2DescriptorRow(int width) {
    return mcv2DescriptorRowOf(width, MCV2_SCREEN_INDEX);
}
#endif

#if defined(MCV2_PASS_STATUS) || defined(MCV2_PASS_RESOLVE) || defined(MCV2_PASS_DECODE)
uniform sampler2D BytesSampler;
int DataBytes = 0;

bool mcv2Range(int offset, int count) {
    return offset >= 0 && count >= 0 && offset <= DataBytes && count <= DataBytes - offset;
}

uvec4 mcv2ReadTexel(int offset) {
    if (!mcv2Range(offset, 1)) return uvec4(0u);
    int index = offset >> 2;
    if (index / MCV2_BYTES_WIDTH >= MCV2_BYTES_HEIGHT) return uvec4(0u);
    return uvec4(texelFetch(BytesSampler, ivec2(index % MCV2_BYTES_WIDTH, index / MCV2_BYTES_WIDTH), 0) * 255.0 + 0.5);
}

uint mcv2ReadByte(int offset) {
    return mcv2ReadTexel(offset)[offset & 3];
}

uint mcv2ReadWord(int offset) {
    if (!mcv2Range(offset, 4)) return 0u;
    uvec4 firstChannels = mcv2ReadTexel(offset);
    uint word = firstChannels.x | (firstChannels.y << 8u) | (firstChannels.z << 16u) | (firstChannels.w << 24u);
    uint shift = uint(offset & 3) * 8u;
    if (shift == 0u) return word;
    uvec4 nextChannels = mcv2ReadTexel((offset & ~3) + 4);
    uint next = nextChannels.x | (nextChannels.y << 8u) | (nextChannels.z << 16u) | (nextChannels.w << 24u);
    return (word >> shift) | (next << (32u - shift));
}

vec3 mcv2ReadColor(int offset) {
    if (!mcv2Range(offset, 3)) return vec3(0.0);
    uvec4 first = mcv2ReadTexel(offset);
    int lane = offset & 3;
    if (lane == 0) return vec3(first.rgb);
    if (lane == 1) return vec3(first.gba);
    uvec4 second = mcv2ReadTexel((offset & ~3) + 4);
    return lane == 2 ? vec3(first.ba, second.r) : vec3(first.a, second.rg);
}

int mcv2Signed(uint value, uint width) {
    uint sign = 1u << (width - 1u);
    return int((value & ((1u << width) - 1u)) ^ sign) - int(sign);
}
#endif

#ifdef MCV2_PASS_BYTES
uniform sampler2D MainSampler;

layout(location = 0) out vec4 fragColor;

uint mcv2FrameByte(ivec2 size, int offset) {
    int page = offset / MCV2_PAGE_CAPACITY;
    ivec3 position = mcv2PageByteAt(size, page, MCV2_PAGE_HEADER + offset % MCV2_PAGE_CAPACITY);
    return mcv2ByteFromChannel(texelFetch(MainSampler, position.xy, 0)[position.z]);
}

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int offset = (texel.y * MCV2_BYTES_WIDTH + texel.x) * 4;
    if (offset + 3 >= MCV2_PAGE_SLOTS * MCV2_PAGE_CAPACITY || !mcv2StripFits(size)) {
        fragColor = vec4(0.0);
        return;
    }
    fragColor = mcv2Texel(uvec4(mcv2FrameByte(size, offset), mcv2FrameByte(size, offset + 1),
        mcv2FrameByte(size, offset + 2), mcv2FrameByte(size, offset + 3)));
}
#endif

#if defined(MCV2_PASS_CRC) || defined(MCV2_PASS_PAGES)

const uint MCV2_CRC_TABLE[256] = uint[256](
    0x00000000u, 0x77073096u, 0xEE0E612Cu, 0x990951BAu, 0x076DC419u, 0x706AF48Fu,
    0xE963A535u, 0x9E6495A3u, 0x0EDB8832u, 0x79DCB8A4u, 0xE0D5E91Eu, 0x97D2D988u,
    0x09B64C2Bu, 0x7EB17CBDu, 0xE7B82D07u, 0x90BF1D91u, 0x1DB71064u, 0x6AB020F2u,
    0xF3B97148u, 0x84BE41DEu, 0x1ADAD47Du, 0x6DDDE4EBu, 0xF4D4B551u, 0x83D385C7u,
    0x136C9856u, 0x646BA8C0u, 0xFD62F97Au, 0x8A65C9ECu, 0x14015C4Fu, 0x63066CD9u,
    0xFA0F3D63u, 0x8D080DF5u, 0x3B6E20C8u, 0x4C69105Eu, 0xD56041E4u, 0xA2677172u,
    0x3C03E4D1u, 0x4B04D447u, 0xD20D85FDu, 0xA50AB56Bu, 0x35B5A8FAu, 0x42B2986Cu,
    0xDBBBC9D6u, 0xACBCF940u, 0x32D86CE3u, 0x45DF5C75u, 0xDCD60DCFu, 0xABD13D59u,
    0x26D930ACu, 0x51DE003Au, 0xC8D75180u, 0xBFD06116u, 0x21B4F4B5u, 0x56B3C423u,
    0xCFBA9599u, 0xB8BDA50Fu, 0x2802B89Eu, 0x5F058808u, 0xC60CD9B2u, 0xB10BE924u,
    0x2F6F7C87u, 0x58684C11u, 0xC1611DABu, 0xB6662D3Du, 0x76DC4190u, 0x01DB7106u,
    0x98D220BCu, 0xEFD5102Au, 0x71B18589u, 0x06B6B51Fu, 0x9FBFE4A5u, 0xE8B8D433u,
    0x7807C9A2u, 0x0F00F934u, 0x9609A88Eu, 0xE10E9818u, 0x7F6A0DBBu, 0x086D3D2Du,
    0x91646C97u, 0xE6635C01u, 0x6B6B51F4u, 0x1C6C6162u, 0x856530D8u, 0xF262004Eu,
    0x6C0695EDu, 0x1B01A57Bu, 0x8208F4C1u, 0xF50FC457u, 0x65B0D9C6u, 0x12B7E950u,
    0x8BBEB8EAu, 0xFCB9887Cu, 0x62DD1DDFu, 0x15DA2D49u, 0x8CD37CF3u, 0xFBD44C65u,
    0x4DB26158u, 0x3AB551CEu, 0xA3BC0074u, 0xD4BB30E2u, 0x4ADFA541u, 0x3DD895D7u,
    0xA4D1C46Du, 0xD3D6F4FBu, 0x4369E96Au, 0x346ED9FCu, 0xAD678846u, 0xDA60B8D0u,
    0x44042D73u, 0x33031DE5u, 0xAA0A4C5Fu, 0xDD0D7CC9u, 0x5005713Cu, 0x270241AAu,
    0xBE0B1010u, 0xC90C2086u, 0x5768B525u, 0x206F85B3u, 0xB966D409u, 0xCE61E49Fu,
    0x5EDEF90Eu, 0x29D9C998u, 0xB0D09822u, 0xC7D7A8B4u, 0x59B33D17u, 0x2EB40D81u,
    0xB7BD5C3Bu, 0xC0BA6CADu, 0xEDB88320u, 0x9ABFB3B6u, 0x03B6E20Cu, 0x74B1D29Au,
    0xEAD54739u, 0x9DD277AFu, 0x04DB2615u, 0x73DC1683u, 0xE3630B12u, 0x94643B84u,
    0x0D6D6A3Eu, 0x7A6A5AA8u, 0xE40ECF0Bu, 0x9309FF9Du, 0x0A00AE27u, 0x7D079EB1u,
    0xF00F9344u, 0x8708A3D2u, 0x1E01F268u, 0x6906C2FEu, 0xF762575Du, 0x806567CBu,
    0x196C3671u, 0x6E6B06E7u, 0xFED41B76u, 0x89D32BE0u, 0x10DA7A5Au, 0x67DD4ACCu,
    0xF9B9DF6Fu, 0x8EBEEFF9u, 0x17B7BE43u, 0x60B08ED5u, 0xD6D6A3E8u, 0xA1D1937Eu,
    0x38D8C2C4u, 0x4FDFF252u, 0xD1BB67F1u, 0xA6BC5767u, 0x3FB506DDu, 0x48B2364Bu,
    0xD80D2BDAu, 0xAF0A1B4Cu, 0x36034AF6u, 0x41047A60u, 0xDF60EFC3u, 0xA867DF55u,
    0x316E8EEFu, 0x4669BE79u, 0xCB61B38Cu, 0xBC66831Au, 0x256FD2A0u, 0x5268E236u,
    0xCC0C7795u, 0xBB0B4703u, 0x220216B9u, 0x5505262Fu, 0xC5BA3BBEu, 0xB2BD0B28u,
    0x2BB45A92u, 0x5CB36A04u, 0xC2D7FFA7u, 0xB5D0CF31u, 0x2CD99E8Bu, 0x5BDEAE1Du,
    0x9B64C2B0u, 0xEC63F226u, 0x756AA39Cu, 0x026D930Au, 0x9C0906A9u, 0xEB0E363Fu,
    0x72076785u, 0x05005713u, 0x95BF4A82u, 0xE2B87A14u, 0x7BB12BAEu, 0x0CB61B38u,
    0x92D28E9Bu, 0xE5D5BE0Du, 0x7CDCEFB7u, 0x0BDBDF21u, 0x86D3D2D4u, 0xF1D4E242u,
    0x68DDB3F8u, 0x1FDA836Eu, 0x81BE16CDu, 0xF6B9265Bu, 0x6FB077E1u, 0x18B74777u,
    0x88085AE6u, 0xFF0F6A70u, 0x66063BCAu, 0x11010B5Cu, 0x8F659EFFu, 0xF862AE69u,
    0x616BFFD3u, 0x166CCF45u, 0xA00AE278u, 0xD70DD2EEu, 0x4E048354u, 0x3903B3C2u,
    0xA7672661u, 0xD06016F7u, 0x4969474Du, 0x3E6E77DBu, 0xAED16A4Au, 0xD9D65ADCu,
    0x40DF0B66u, 0x37D83BF0u, 0xA9BCAE53u, 0xDEBB9EC5u, 0x47B2CF7Fu, 0x30B5FFE9u,
    0xBDBDF21Cu, 0xCABAC28Au, 0x53B39330u, 0x24B4A3A6u, 0xBAD03605u, 0xCDD70693u,
    0x54DE5729u, 0x23D967BFu, 0xB3667A2Eu, 0xC4614AB8u, 0x5D681B02u, 0x2A6F2B94u,
    0xB40BBE37u, 0xC30C8EA1u, 0x5A05DF1Bu, 0x2D02EF8Du
);

uint mcv2UpdateChecksum(uint checksum, uint value) {
    return MCV2_CRC_TABLE[(checksum ^ value) & 255u] ^ (checksum >> 8u);
}

// Linear CRC advancement makes zero-initialized chunk checksums compose exactly.
const int MCV2_CRC_CHUNK_BYTES = 192;
const int MCV2_CRC_CHUNKS = 64;
const uint MCV2_CRC_SHIFT[32] = uint[32](
    0x596C8D81u, 0xB2D91B02u, 0xBEC33045u, 0xA6F766CBu, 0x969FCBD7u, 0xF64E91EFu,
    0x37EC259Fu, 0x6FD84B3Eu, 0xDFB0967Cu, 0x64102AB9u, 0xC8205572u, 0x4B31ACA5u,
    0x9663594Au, 0xF7B7B4D5u, 0x341E6FEBu, 0x683CDFD6u, 0xD079BFACu, 0x7B827919u,
    0xF704F232u, 0x3578E225u, 0x6AF1C44Au, 0xD5E38894u, 0x70B61769u, 0xE16C2ED2u,
    0x19A95BE5u, 0x3352B7CAu, 0x66A56F94u, 0xCD4ADF28u, 0x41E4B811u, 0x83C97022u,
    0xDCE3E605u, 0x62B6CA4Bu
);

uint mcv2AdvanceChecksum(uint checksum) {
    uint shifted = 0u;
    for (int bitIndex = 0; bitIndex < 32; ++bitIndex) {
        shifted ^= MCV2_CRC_SHIFT[bitIndex] & (0u - ((checksum >> uint(bitIndex)) & 1u));
    }
    return shifted;
}
#endif

#ifdef MCV2_PASS_CRC
uniform sampler2D MainSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    if (!mcv2StripFits(size)) {
        fragColor = vec4(0.0);
        return;
    }
    int columnIndex = int(gl_FragCoord.x);
    int page = columnIndex / MCV2_CRC_CHUNKS;
    int first = (columnIndex % MCV2_CRC_CHUNKS) * MCV2_CRC_CHUNK_BYTES;
    uint checksum = 0u;
    for (int byteIndex = first; byteIndex < first + MCV2_CRC_CHUNK_BYTES; byteIndex += 3) {
        ivec3 position = mcv2PageByteAt(size, page, byteIndex);
        vec4 texel = texelFetch(MainSampler, position.xy, 0);
        for (int channelIndex = 0; channelIndex < 3; ++channelIndex) {
            int offset = byteIndex + channelIndex;
            checksum = mcv2UpdateChecksum(checksum, offset >= 28 && offset < 32 ? 0u : mcv2ByteFromChannel(texel[channelIndex]));
        }
    }
    fragColor = mcv2WordTexel(checksum);
}
#endif

#ifdef MCV2_PASS_PAGES
uniform sampler2D MainSampler;
uniform sampler2D CrcSampler;

layout(location = 0) out vec4 fragColor;

uint mcv2PageByte(ivec2 size, int page, int byteIndex) {
    ivec3 position = mcv2PageByteAt(size, page, byteIndex);
    return mcv2ByteFromChannel(texelFetch(MainSampler, position.xy, 0)[position.z]);
}

uint mcv2PageWord(ivec2 size, int page, int byteIndex) {
    return mcv2PageByte(size, page, byteIndex) | (mcv2PageByte(size, page, byteIndex + 1) << 8u)
        | (mcv2PageByte(size, page, byteIndex + 2) << 16u) | (mcv2PageByte(size, page, byteIndex + 3) << 24u);
}

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    if (!mcv2StripFits(size)) {

        fragColor = vec4(0.0);
        return;
    }
    int columnIndex = int(gl_FragCoord.x);
    int page = columnIndex / 4;
    int field = columnIndex % 4;
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
        && number == uint(page) && number < count && total >= uint(MCV2_HEADER_BYTES) && total <= uint(MCV2_MAX_FRAME_BYTES)
        && total <= uint(MCV2_PAGE_SLOTS) * capacity
        && count == (total + capacity - 1u) / capacity;
    if (valid) {
        int length = MCV2_PAGE_HEADER + int(min(capacity, total - number * capacity));

        int chunks = length / MCV2_CRC_CHUNK_BYTES;
        uint checksum = 0xFFFFFFFFu;
        for (int chunk = 0; chunk < MCV2_CRC_CHUNKS; ++chunk) {
            if (chunk >= chunks) break;
            checksum = mcv2AdvanceChecksum(checksum) ^ mcv2TexelWord(texelFetch(CrcSampler, ivec2(page * MCV2_CRC_CHUNKS + chunk, 0), 0));
        }
        for (int byteIndex = chunks * MCV2_CRC_CHUNK_BYTES; byteIndex < length; ++byteIndex) {

            checksum = mcv2UpdateChecksum(checksum, byteIndex >= 28 && byteIndex < 32 ? 0u : mcv2PageByte(size, page, byteIndex));
        }
        valid = ~checksum == mcv2PageWord(size, page, 28);
    }
    fragColor = mcv2Texel(uvec4(valid ? 1u : 0u, type, count & 255u, 255u));
}
#endif

#ifdef MCV2_PASS_STATUS
bool mcv2HeaderValid(uint frameId, uint referenceId, uint type);

uniform sampler2D PagesSampler;
uniform sampler2D StateSampler;

layout(location = 0) out vec4 fragColor;

uvec4 mcv2Bytes(sampler2D source, int columnIndex) {
    return uvec4(texelFetch(source, ivec2(columnIndex, 0), 0) * 255.0 + 0.5);
}

uint mcv2Word(sampler2D source, int columnIndex) {
    return mcv2TexelWord(texelFetch(source, ivec2(columnIndex, 0), 0));
}

void main() {
    uvec4 first = mcv2Bytes(PagesSampler, 0);
    bool keyframe = first.y == 1u;
    uint frameId = mcv2Word(PagesSampler, 1);
    uint referenceId = mcv2Word(PagesSampler, 2);
    uint total = mcv2Word(PagesSampler, 3);
    int count = int(first.z);
    bool valid = first.x == 1u && count >= 1 && count <= MCV2_PAGE_SLOTS;
    for (int page = 1; page < MCV2_PAGE_SLOTS; ++page) {
        if (page < count) {
            uvec4 head = mcv2Bytes(PagesSampler, page * 4);
            valid = valid && head.x == 1u && head.y == first.y && head.z == first.z
                && mcv2Word(PagesSampler, page * 4 + 1) == frameId && mcv2Word(PagesSampler, page * 4 + 2) == referenceId
                && mcv2Word(PagesSampler, page * 4 + 3) == total;
        }
    }
    bool previousValid = (mcv2Bytes(StateSampler, 0).x & 1u) != 0u;
    uint lastId = mcv2Word(StateSampler, 1);
    uint delta = frameId - lastId;
    // A keyframe can restart the sender without a resource reload.
    bool newer = !previousValid || (delta > 0u && (keyframe || delta < 0x80000000u));
    bool fromPrevious = previousValid && referenceId == lastId;
    DataBytes = int(total);
    bool decode = valid && newer && (keyframe || fromPrevious)
        && mcv2HeaderValid(frameId, referenceId, first.y);
    int columnIndex = int(gl_FragCoord.x);
    if (columnIndex == 0) {
        fragColor = mcv2Texel(uvec4(decode ? 1u : 0u, keyframe ? 1u : 0u, 0u, uint(count)));
    } else if (columnIndex == 1) {
        fragColor = mcv2WordTexel(frameId);
    } else if (columnIndex == 2) {
        fragColor = mcv2WordTexel(total);
    } else {
        fragColor = mcv2WordTexel(referenceId);
    }
}
#endif

#if defined(MCV2_PASS_STATUS) || defined(MCV2_PASS_RESOLVE)
struct Mcv2Frame {
    bool keyframe;
    int payloadStart;
    int groups;
    int descriptorBase;
    int walkBase;
    int rootDescriptors;
    int childDescriptors;
    int descriptors;
};

bool mcv2FrameIndex(out Mcv2Frame frame) {
    frame.keyframe = mcv2ReadWord(12) == mcv2ReadWord(16);
    int roots = ((MCV2_VIDEO_WIDTH + 31) / 32) * ((MCV2_VIDEO_HEIGHT + 31) / 32);
    frame.groups = (roots + 31) / 32;
    int directory = MCV2_HEADER_BYTES + 4 * frame.groups;
    int levels = directory + 4 * ((frame.groups + 7) / 8);
    if (!mcv2Range(levels, 12)) return false;
    uint rootDescriptors = mcv2ReadWord(levels);
    uint childDescriptors = mcv2ReadWord(levels + 4);
    uint grandchildDescriptors = mcv2ReadWord(levels + 8);
    if (rootDescriptors > uint(roots) || childDescriptors > 4u * rootDescriptors || grandchildDescriptors > 4u * childDescriptors || ((childDescriptors | grandchildDescriptors) & 3u) != 0u) return false;
    frame.rootDescriptors = int(rootDescriptors);
    frame.childDescriptors = int(childDescriptors);
    frame.descriptors = int(rootDescriptors + childDescriptors + grandchildDescriptors);
    frame.descriptorBase = levels + 12;
    frame.walkBase = frame.descriptorBase + frame.descriptors;
    int indexEnd = frame.walkBase + 4 * ((frame.descriptors + 7) / 8);
    if (!mcv2Range(indexEnd, 0)) return false;
    frame.payloadStart = indexEnd;
    if (mcv2ReadWord(directory) != 0u) return false;
    if (frame.descriptors > 0 && mcv2ReadWord(frame.walkBase) != 0u) return false;
    uint last = mcv2ReadWord(MCV2_HEADER_BYTES + 4 * (frame.groups - 1));
    if ((roots & 31) != 0 && (last >> uint(roots & 31)) != 0u) return false;
    return frame.descriptors != 0 || frame.payloadStart == DataBytes;
}

bool mcv2HeaderValid(uint frameId, uint referenceId, uint type) {
    if (DataBytes < MCV2_HEADER_BYTES || DataBytes > MCV2_MAX_FRAME_BYTES || type > 1u
        || MCV2_VIDEO_WIDTH < 1 || MCV2_VIDEO_WIDTH > 4096 || MCV2_VIDEO_HEIGHT < 1 || MCV2_VIDEO_HEIGHT > 4096)
        return false;
    if (mcv2ReadWord(0) != 0x3256434du || mcv2ReadWord(4) != 3u
        || mcv2ReadWord(8) != (uint(MCV2_VIDEO_WIDTH) | (uint(MCV2_VIDEO_HEIGHT) << 16u))
        || mcv2ReadWord(12) != frameId || mcv2ReadWord(16) != referenceId || (type == 1u) != (referenceId == frameId))
        return false;
    Mcv2Frame frame;
    return mcv2FrameIndex(frame);
}
#endif

#ifdef MCV2_PASS_RESOLVE
uniform sampler2D StatusSampler;
layout(location = 0) out vec4 fragColor;

// GLSL 330 lacks core bitCount; SWAR also has a fixed cost for bit 31.
uint mcv2Popcount(uint value) {
    value -= (value >> 1u) & 0x55555555u;
    value = (value & 0x33333333u) + ((value >> 2u) & 0x33333333u);
    value = (value + (value >> 4u)) & 0x0f0f0f0fu;
    return (value * 0x01010101u) >> 24u;
}

int mcv2RecordBytes(Mcv2Frame frame, uint descriptor, int size) {
    uint mode = descriptor & 31u;
    if (mode > MCV2_SPLIT || descriptor >> 5u > (mode == MCV2_COMPACT ? 2u : 0u)) return -1;
    if (frame.keyframe && (mode == MCV2_MOTION || mode == MCV2_COMPACT)) return -1;
    if (mode == MCV2_SPLIT) return size > 8 ? 0 : -1;
    if (mode == MCV2_SKIP) return 0;
    if (mode == MCV2_MOTION) return 2;
    if (mode == MCV2_SOLID) return 3;
    if (mode == MCV2_PALETTE) return 6 + size * size / 8;
    if (mode == MCV2_PATTERN) return 7 + size / 8;
    return 10;
}

bool mcv2Walk(Mcv2Frame frame, int descriptor, out int cursor, out int splits) {
    uint checkpoint = mcv2ReadWord(frame.walkBase + (descriptor >> 3) * 4);
    cursor = int(checkpoint & 0x1ffffu);
    splits = int(checkpoint >> 17u);
    int anchor = descriptor & ~7;
    for (int step = 0; step < 7; ++step) {
        int index = anchor + step;
        if (index >= descriptor) break;
        uint value = mcv2ReadByte(frame.descriptorBase + index);
        int size = index < frame.rootDescriptors ? 32 : index < frame.rootDescriptors + frame.childDescriptors ? 16 : 8;
        int bytes = mcv2RecordBytes(frame, value, size);
        if (bytes < 0) return false;
        cursor += bytes;
        splits += (value & 31u) == MCV2_SPLIT ? 1 : 0;
    }
    return cursor <= DataBytes - frame.payloadStart && splits <= descriptor;
}

uint mcv2Resolve(ivec2 pixel, Mcv2Frame frame) {
    int root = (pixel.y >> 5) * ((MCV2_VIDEO_WIDTH + 31) / 32) + (pixel.x >> 5);
    int group = root >> 5;
    uint bit = uint(root & 31);
    uint mask = mcv2ReadWord(MCV2_HEADER_BYTES + group * 4);
    if ((mask & (1u << bit)) == 0u) return 0u;
    uint descriptor = mcv2ReadWord(MCV2_HEADER_BYTES + frame.groups * 4 + (group >> 3) * 4);
    int anchor = group & ~7;
    for (int step = 0; step < 7; ++step) {
        if (anchor + step >= group) break;
        descriptor += mcv2Popcount(mcv2ReadWord(MCV2_HEADER_BYTES + (anchor + step) * 4));
    }
    descriptor += mcv2Popcount(mask & ((1u << bit) - 1u));
    int size = 32;
    for (int level = 0; level < 3; ++level) {
        int first = level == 0 ? 0 : level == 1 ? frame.rootDescriptors : frame.rootDescriptors + frame.childDescriptors;
        int end = level == 0 ? frame.rootDescriptors : level == 1 ? frame.rootDescriptors + frame.childDescriptors : frame.descriptors;
        if (descriptor < uint(first) || descriptor >= uint(end)) return MCV2_CELL_INVALID;
        int cursor, splits;
        if (!mcv2Walk(frame, int(descriptor), cursor, splits)) return MCV2_CELL_INVALID;
        uint value = mcv2ReadByte(frame.descriptorBase + int(descriptor));
        uint mode = value & 31u;
        int offset = frame.payloadStart + cursor;
        int bytes = mcv2RecordBytes(frame, value, size);
        if (bytes < 0 || bytes > DataBytes - offset) return MCV2_CELL_INVALID;
        if (mode == MCV2_SPLIT) {
            size /= 2;
            ivec2 quadrant = (pixel / size) & ivec2(1);
            descriptor = uint(frame.rootDescriptors + 4 * splits + 2 * quadrant.y + quadrant.x);
            continue;
        }

        uint record = mode == MCV2_SKIP ? 0u : mode == MCV2_MOTION
            ? mcv2ReadByte(offset) | (mcv2ReadByte(offset + 1) << 8u) : uint(offset);
        return record | (uint(level) << 22u) | (value << 24u);
    }
    return MCV2_CELL_INVALID;
}

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    uvec4 status = uvec4(texelFetch(StatusSampler, ivec2(0, 0), 0) * 255.0 + 0.5);
    if (status.x != 1u) {
        fragColor = vec4(0.0);
        return;
    }
    DataBytes = int(mcv2TexelWord(texelFetch(StatusSampler, ivec2(2, 0), 0)));
    Mcv2Frame frame;
    if (!mcv2FrameIndex(frame)) {
        fragColor = mcv2WordTexel(MCV2_CELL_INVALID);
        return;
    }
    uint word = 0u;
    if (cell.y == MCV2_CELLS_HEIGHT) {
        if (cell.x == 0) word = 0x80000000u | (frame.keyframe ? MCV2_KEYFRAME : 0u);
    } else if (cell.x * 8 < MCV2_VIDEO_WIDTH) {
        word = mcv2Resolve(cell * 8, frame);
    }
    fragColor = mcv2WordTexel(word);
}
#endif

#ifdef MCV2_PASS_DECODE_VERTEX
uniform sampler2D StatusSampler;
uniform sampler2D CellsSampler;
layout(location = 1) flat out uvec4 DecodeStatus;
layout(location = 2) flat out uvec2 DecodeFrame;

uint mcv2FrameFlags() {
    return mcv2TexelWord(texelFetch(CellsSampler, ivec2(0, MCV2_CELLS_HEIGHT), 0));
}

void main() {
    vec2 textureCoordinates = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(textureCoordinates * 2.0 - 1.0, 0.0, 1.0);
    DecodeStatus = uvec4(texelFetch(StatusSampler, ivec2(0, 0), 0) * 255.0 + 0.5);
    DecodeFrame = uvec2(mcv2FrameFlags(), mcv2TexelWord(texelFetch(StatusSampler, ivec2(2, 0), 0)));
}
#endif

#ifdef MCV2_PASS_DECODE
uniform sampler2D CellsSampler;
uniform sampler2D PreviousSampler;
layout(location = 1) flat in uvec4 DecodeStatus;
layout(location = 2) flat in uvec2 DecodeFrame;
layout(location = 0) out vec4 fragColor;

vec3 mcv2Predict(ivec2 pixel, ivec2 motion) {
    ivec2 position = clamp(pixel + motion, ivec2(0), ivec2(MCV2_VIDEO_WIDTH, MCV2_VIDEO_HEIGHT) - 1);
    return floor(texelFetch(PreviousSampler, position, 0).rgb * 255.0 + 0.5);
}

float mcv2CompactNode(int offset, ivec2 node) {
    int index = node.y * 4 + node.x;
    uint value = mcv2ReadByte(offset + index / 2);
    return float(mcv2Signed(value >> uint((index & 1) * 4), 4u));
}

float mcv2CompactGrid(int offset, ivec2 local, int size) {
    vec2 position = clamp((vec2(local) + 0.5) * 4.0 / float(size) - 0.5, vec2(0.0), vec2(3.0));
    ivec2 lower = ivec2(floor(position)), upper = min(lower + 1, ivec2(3));
    vec2 fraction = fract(position);
    float top = mix(mcv2CompactNode(offset, lower), mcv2CompactNode(offset, ivec2(upper.x, lower.y)), fraction.x);
    float bottom = mix(mcv2CompactNode(offset, ivec2(lower.x, upper.y)), mcv2CompactNode(offset, upper), fraction.x);
    return mix(top, bottom, fraction.y);
}

vec3 mcv2Compact(int offset, uint quantizer, ivec2 pixel, int size) {
    ivec2 motion = ivec2(mcv2Signed(mcv2ReadByte(offset), 8u), mcv2Signed(mcv2ReadByte(offset + 1), 8u));
    return mcv2Predict(pixel, motion) + float(1u << quantizer) * mcv2CompactGrid(offset + 2, pixel % size, size);
}

vec3 mcv2Pattern(int offset, ivec2 pixel, int size) {
    uint orientation = mcv2ReadByte(offset + 6);
    if (orientation > 1u) return mcv2Predict(pixel, ivec2(0));
    int axis = (orientation == 0u ? pixel.x : pixel.y) % size;
    int selector = int((mcv2ReadByte(offset + 7 + axis / 8) >> uint(axis & 7)) & 1u);
    return mcv2ReadColor(offset + selector * 3);
}

vec3 mcv2Decode(ivec2 pixel) {
    uint cell = mcv2TexelWord(texelFetch(CellsSampler, pixel / 8, 0));
    if ((DecodeFrame.x & 0x80000000u) == 0u || cell == MCV2_CELL_INVALID) return mcv2Predict(pixel, ivec2(0));
    uint mode = (cell >> 24u) & 31u;
    if (mode == MCV2_SKIP) return (DecodeFrame.x & MCV2_KEYFRAME) == 0u ? mcv2Predict(pixel, ivec2(0)) : vec3(0.0);
    if (mode == MCV2_MOTION)
        return mcv2Predict(pixel, ivec2(mcv2Signed(cell, 8u), mcv2Signed(cell >> 8u, 8u)));
    DataBytes = int(DecodeFrame.y);
    int offset = int(cell & MCV2_CELL_OFFSET);
    int size = 32 >> int((cell >> 22u) & 3u);
    if (mode == MCV2_SOLID) return mcv2ReadColor(offset);
    if (mode == MCV2_PALETTE) {
        ivec2 local = pixel % size;
        // Exact float products avoid Intel's GLSL 330 dynamic-shift/multiply miscompile.
        int index = int(float(local.y) * float(size)) + local.x;
        uint selector = (mcv2ReadByte(offset + 6 + index / 8) >> uint(index & 7)) & 1u;
        return mcv2ReadColor(offset + int(selector) * 3);
    }
    if (mode == MCV2_PATTERN) return mcv2Pattern(offset, pixel, size);
    if (mode == MCV2_COMPACT) return mcv2Compact(offset, cell >> 29u, pixel, size);
    return mcv2Predict(pixel, ivec2(0));
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (DecodeStatus.x != 1u) {
        fragColor = texelFetch(PreviousSampler, pixel, 0);
        return;
    }
    vec3 color = mcv2Decode(pixel);
    fragColor = vec4(clamp(floor(color + 0.5), 0.0, 255.0) / 255.0, 1.0);
}
#endif

#ifdef MCV2_PASS_STATE
uniform sampler2D StateSampler;
uniform sampler2D StatusSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    int columnIndex = int(gl_FragCoord.x);
    vec4 kept = texelFetch(StateSampler, ivec2(columnIndex, 0), 0);
    uvec4 status = uvec4(texelFetch(StatusSampler, ivec2(0, 0), 0) * 255.0 + 0.5);
    if (status.x != 1u) {
        fragColor = kept;
        return;
    }
    uint frameId = mcv2TexelWord(texelFetch(StatusSampler, ivec2(1, 0), 0));
    if (columnIndex == 0) {
        fragColor = mcv2Texel(uvec4(1u, 0u, 0u, 255u));
    } else if (columnIndex == 1) {
        fragColor = mcv2WordTexel(frameId);
    } else if (columnIndex == 2) {
        fragColor = vec4(0.0);
    } else {
        fragColor = mcv2WordTexel(mcv2TexelWord(kept) + 1u);
    }
}
#endif

#ifdef MCV2_PASS_COPY
uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
}
#endif

#ifdef MCV2_PASS_VIEW
uniform sampler2D MainSampler;
uniform sampler2D StateSampler;

layout(location = 0) out vec4 fragColor;

const int MCV2_VIEW_FLOATS = 3;
// Projected-corner rounding requires a margin around the screen's convex hull.
const int MCV2_VIEW_MARGIN = 2;

ivec3 mcv2DescriptorBytes(ivec2 size, int row, int columnIndex) {
    return ivec3(texelFetch(MainSampler, mcv2FromTop(size, columnIndex, row), 0).rgb * 255.0 + 0.5);
}

float mcv2DescriptorFloat(ivec2 size, int row, int index) {
    ivec3 low = mcv2DescriptorBytes(size, row, 2 + index * 2);
    ivec3 high = mcv2DescriptorBytes(size, row, 3 + index * 2);
    return uintBitsToFloat(uint(low.r) | (uint(low.g) << 8u) | (uint(low.b) << 16u) | (uint(high.r) << 24u));
}

mat4 mcv2Projection(ivec2 size, int row) {
    mat4 projection;
    for (int index = 0; index < 16; ++index) {
        projection[index / 4][index % 4] = mcv2DescriptorFloat(size, row, 12 + index);
    }
    return projection;
}

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    if (!mcv2StripFits(size)) {

        fragColor = vec4(0.0);
        return;
    }
    int columnIndex = int(gl_FragCoord.x);
    int row = mcv2DescriptorRow(size.x);
    if (columnIndex >= MCV2_VIEW_FLOATS) {
        fragColor = mcv2WordTexel(floatBitsToUint(mcv2DescriptorFloat(size, row, columnIndex - MCV2_VIEW_FLOATS)));
        return;
    }
    bool shown = (uint(texelFetch(StateSampler, ivec2(0, 0), 0).x * 255.0 + 0.5) & 1u) != 0u;
    bool present = mcv2DescriptorBytes(size, row, 0) == ivec3(0x4D, 0x43, 0x56) && mcv2DescriptorBytes(size, row, 1).r == 0xA1;

    vec3 topLeft = vec3(mcv2DescriptorFloat(size, row, 0), mcv2DescriptorFloat(size, row, 1), mcv2DescriptorFloat(size, row, 2));
    vec3 right = vec3(mcv2DescriptorFloat(size, row, 4), mcv2DescriptorFloat(size, row, 5), mcv2DescriptorFloat(size, row, 6));
    vec3 down = vec3(mcv2DescriptorFloat(size, row, 8), mcv2DescriptorFloat(size, row, 9), mcv2DescriptorFloat(size, row, 10));
    vec2 cells = vec2(mcv2DescriptorFloat(size, row, 3), mcv2DescriptorFloat(size, row, 7));
    mat4 projection = mcv2Projection(size, row);
    bool front = true;
    vec2 low = vec2(1e9);
    vec2 high = vec2(-1e9);
    for (int corner = 0; corner < 4; ++corner) {
        vec2 position = vec2(corner & 1, corner >> 1) * cells;
        vec4 clip = projection * vec4(topLeft + right * position.x + down * position.y, 1.0);
        front = front && clip.w > 0.0;
        vec2 pixel = (clip.xy / clip.w + 1.0) * 0.5 * vec2(size) - 0.5;
        low = min(low, pixel);
        high = max(high, pixel);
    }

    front = front && all(greaterThan(low, vec2(-65536.0))) && all(lessThan(high, vec2(65536.0)));
    ivec2 first = clamp(ivec2(floor(low)) - MCV2_VIEW_MARGIN, ivec2(0), size - 1);
    ivec2 last = clamp(ivec2(ceil(high)) + MCV2_VIEW_MARGIN, ivec2(0), size - 1);
    if (columnIndex == 0) {
        fragColor = mcv2WordTexel((shown ? 1u : 0u) | (present ? 2u : 0u) | (front ? 4u : 0u));
    } else if (columnIndex == 1) {
        fragColor = mcv2WordTexel(uint(first.x) | (uint(first.y) << 16u));
    } else {
        fragColor = mcv2WordTexel(uint(last.x) | (uint(last.y) << 16u));
    }
}
#endif

#ifdef MCV2_PASS_SCREEN_VERTEX
uniform sampler2D ViewSampler;

layout(location = 1) flat out uvec4 ScreenView;
layout(location = 2) flat out vec4 ScreenTopLeft;
layout(location = 3) flat out vec4 ScreenRight;
layout(location = 4) flat out vec4 ScreenDown;
layout(location = 5) flat out vec4 ScreenProjection0;
layout(location = 6) flat out vec4 ScreenProjection1;
layout(location = 7) flat out vec4 ScreenProjection2;
layout(location = 8) flat out vec4 ScreenProjection3;

uint mcv2View(int columnIndex) {
    return mcv2TexelWord(texelFetch(ViewSampler, ivec2(columnIndex, 0), 0));
}

float mcv2DescriptorFloat(int index) {
    return uintBitsToFloat(mcv2View(3 + index));
}

void main() {
    vec2 textureCoordinates = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(textureCoordinates * vec2(2, 2) + vec2(-1, -1), 0, 1);
    ScreenView = uvec4(mcv2View(0), mcv2View(1), mcv2View(2), 0u);

    ScreenTopLeft = vec4(mcv2DescriptorFloat(0), mcv2DescriptorFloat(1), mcv2DescriptorFloat(2), mcv2DescriptorFloat(3));
    ScreenRight = vec4(mcv2DescriptorFloat(4), mcv2DescriptorFloat(5), mcv2DescriptorFloat(6), mcv2DescriptorFloat(7));
    ScreenDown = vec4(mcv2DescriptorFloat(8), mcv2DescriptorFloat(9), mcv2DescriptorFloat(10), 0.0);
    mat4 projection;
    for (int index = 0; index < 16; ++index) {
        projection[index / 4][index % 4] = mcv2DescriptorFloat(12 + index);
    }
    ScreenProjection0 = projection[0];
    ScreenProjection1 = projection[1];
    ScreenProjection2 = projection[2];
    ScreenProjection3 = projection[3];
}
#endif

#ifdef MCV2_PASS_SCREEN
uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D PictureSampler;
uniform sampler2D StateSampler;
uniform sampler2D PagesSampler;
uniform sampler2D StatusSampler;
uniform sampler2D ViewSampler;

layout(location = 1) flat in uvec4 ScreenView;
layout(location = 2) flat in vec4 ScreenTopLeft;
layout(location = 3) flat in vec4 ScreenRight;
layout(location = 4) flat in vec4 ScreenDown;
layout(location = 5) flat in vec4 ScreenProjection0;
layout(location = 6) flat in vec4 ScreenProjection1;
layout(location = 7) flat in vec4 ScreenProjection2;
layout(location = 8) flat in vec4 ScreenProjection3;

layout(location = 0) out vec4 fragColor;

vec4 mcv2Picture(ivec2 position) {
    return vec4(texelFetch(PictureSampler, position, 0).rgb, 1.0);
}

void main() {
    ivec2 size = textureSize(MainSampler, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int fromTop = size.y - 1 - pixel.y;
    int strip = mcv2StripRows(size.x);

    ivec2 source = fromTop < strip && mcv2StripFits(size) && !MCV2_DEBUG_VIEW ? mcv2FromTop(size, pixel.x, strip) : pixel;
    // Only the first screen's pass covers the strip: covering it again would smear the earlier screens' pictures.
    vec4 scene = texelFetch(MainSampler, MCV2_SCREEN_INDEX == 0 ? source : pixel, 0);
    fragColor = scene;
    int debugRow = fromTop - strip - MCV2_DEBUG_TOP;
    if (MCV2_DEBUG_VIEW && debugRow >= 0 && debugRow < 24 && pixel.x >= MCV2_VIDEO_WIDTH + 8) {
        int square = (pixel.x - MCV2_VIDEO_WIDTH - 8) / 24;
        if ((pixel.x - MCV2_VIDEO_WIDTH - 8) % 24 < 20 && debugRow < 20) {
            if (square < MCV2_PAGE_SLOTS) {
                bool valid = mcv2ByteFromChannel(texelFetch(PagesSampler, ivec2(square * 4, 0), 0).x) == 1u;
                fragColor = valid ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
                return;
            }
            if (square == MCV2_PAGE_SLOTS) {
                uvec4 status = uvec4(texelFetch(StatusSampler, ivec2(0, 0), 0) * 255.0 + 0.5);
                bool valid = mcv2ByteFromChannel(texelFetch(PagesSampler, ivec2(0, 0), 0).x) == 1u;
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
    if (MCV2_DEBUG_VIEW && debugRow >= 0 && debugRow < MCV2_VIDEO_HEIGHT && pixel.x < MCV2_VIDEO_WIDTH) {
        fragColor = mcv2Picture(ivec2(pixel.x, debugRow));
        return;
    }
    if ((view & 2u) == 0u) {
        return;
    }

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

    vec2 normalizedCoordinates = (vec2(pixel) + 0.5) / vec2(size) * 2.0 - 1.0;
    vec4 far = inverse(projection) * vec4(normalizedCoordinates, 0.5, 1.0);
    vec3 direction = far.xyz / far.w;
    vec3 normal = cross(right, down);
    float denominator = dot(direction, normal);
    if (abs(denominator) < 1e-12) {
        return;
    }
    float rayDistance = dot(topLeft, normal) / denominator;
    vec3 hit = direction * rayDistance;
    vec3 local = hit - topLeft;
    vec2 textureCoordinates = vec2(dot(local, right) / dot(right, right), dot(local, down) / dot(down, down)) / cells;
    if (rayDistance <= 0.0 || any(lessThan(textureCoordinates, vec2(0.0))) || any(greaterThanEqual(textureCoordinates, vec2(1.0)))) {
        return;
    }
    vec4 clip = projection * vec4(hit, 1.0);
    float depth = clip.z / clip.w;
    // Projection depth may use zero-to-one or negative-one-to-one clip coordinates.
    float planeDepth = abs(projection[2][2]) < 0.5 ? depth : depth * 0.5 + 0.5;
    float sceneDepth = texelFetch(MainDepthSampler, source, 0).r;
    // Minecraft uses reversed depth: larger values are nearer.
    if (sceneDepth > planeDepth * 1.00001) {
        return;
    }
    ivec2 video = ivec2(MCV2_VIDEO_WIDTH, MCV2_VIDEO_HEIGHT);
    fragColor = mcv2Picture(min(ivec2(floor(textureCoordinates * vec2(video))), video - 1));
}
#endif

#ifdef MCV2_PASS_OUTLINE
uniform sampler2D OutlineSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 outline = texelFetch(OutlineSampler, ivec2(gl_FragCoord.xy), 0);
    ivec3 color = ivec3(outline.rgb * 255.0 + 0.5);
    fragColor = outline.a > 0.0 && color == MCV2_OUTLINE_COLOR ? vec4(0.0) : outline;
}
#endif

#if defined(MCV2_PASS_TEXT_VERTEX) || defined(MCV2_PASS_TEXT_FRAGMENT)

int mcv2Symbol(vec4 texel) {
    ivec3 texelColor = ivec3(texel.rgb * 255.0 + 0.5);
    for (int symbolIndex = 0; symbolIndex < 64; ++symbolIndex) {
        if (MCV2_ALPHABET[symbolIndex] == texelColor) {
            return symbolIndex;
        }
    }
    return -1;
}

int mcv2SymbolAt(sampler2D map, int index) {
    return mcv2Symbol(texelFetch(map, ivec2(index % 128, index / 128), 0));
}

int mcv2PageBits(sampler2D map, int bit, int width) {
    int value = 0;
    for (int index = 0; index < width; ++index) {
        int bitOffset = bit + index;
        int symbol = max(mcv2SymbolAt(map, bitOffset / 6), 0);
        value |= ((symbol >> (bitOffset % 6)) & 1) << index;
    }
    return value;
}

int mcv2ScreenOf(uint stream) {
    for (int screen = 0; screen < MCV2_SCREENS; ++screen) {
        if (MCV2_SCREEN_STREAMS[screen] == stream) {
            return screen;
        }
    }
    return -1;
}

int mcv2PageScreen(sampler2D map) {
    if (mcv2SymbolAt(map, 0) != 13 || mcv2SymbolAt(map, 1) != 13 || mcv2SymbolAt(map, 2) != 4
        || mcv2SymbolAt(map, 3) != 20 || mcv2SymbolAt(map, 4) != 49 || mcv2SymbolAt(map, 5) != 4
        || mcv2SymbolAt(map, 6) != 32) {
        return -1;
    }
    uint stream = uint(mcv2PageBits(map, 64, 16)) | (uint(mcv2PageBits(map, 80, 16)) << 16u);
    return mcv2ScreenOf(stream);
}

bool mcv2IsAnchor(sampler2D map) {
    return mcv2SymbolAt(map, 0) == 21 && mcv2SymbolAt(map, 1) == 3 && mcv2SymbolAt(map, 2) == 58
        && mcv2SymbolAt(map, 3) == 44 && mcv2SymbolAt(map, 4) == 9 && mcv2SymbolAt(map, 5) == 37
        && mcv2SymbolAt(map, 6) == 60 && mcv2SymbolAt(map, 7) == 17;
}
#endif

#ifdef MCV2_PASS_TEXT_VERTEX

layout(location = 4) flat out int mcv2Kind;
layout(location = 5) flat out int mcv2Slot;
layout(location = 6) flat out vec4 mcv2TopLeft;
layout(location = 7) flat out vec4 mcv2Right;
layout(location = 8) flat out vec4 mcv2Down;
// Mesa llvmpipe crashes with a flat mat4 varying; pass the columns separately.
layout(location = 9) flat out vec4 mcv2ProjectionColumn0;
layout(location = 10) flat out vec4 mcv2ProjectionColumn1;
layout(location = 11) flat out vec4 mcv2ProjectionColumn2;
layout(location = 12) flat out vec4 mcv2ProjectionColumn3;

vec4 mcv2Place(vec2 textureCoordinates, float leftPixel, float topPixel, float rightPixel, float bottomPixel) {
    vec2 size = ScreenSize;
    float left = leftPixel / size.x * 2.0 - 1.0;
    float right = rightPixel / size.x * 2.0 - 1.0;
    float top = 1.0 - topPixel / size.y * 2.0;
    float bottom = 1.0 - bottomPixel / size.y * 2.0;
    return vec4(mix(left, right, textureCoordinates.x), mix(top, bottom, textureCoordinates.y), 0.999, 1.0);
}

void mcv2TextVertex() {
    mcv2Kind = 0;
    mcv2Slot = 0;
    mcv2TopLeft = vec4(0.0);
    mcv2Right = vec4(0.0);
    mcv2Down = vec4(0.0);
    mcv2ProjectionColumn0 = vec4(0.0);
    mcv2ProjectionColumn1 = vec4(0.0);
    mcv2ProjectionColumn2 = vec4(0.0);
    mcv2ProjectionColumn3 = vec4(0.0);
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH) && !defined(IS_GRAYSCALE)
    if (textureSize(Sampler0, 0) != ivec2(128, 128)) {
        return;
    }
    int width = int(ScreenSize.x);
    if (!mcv2StripFits(ivec2(ScreenSize))) {
        return;
    }
    int pageScreen = mcv2PageScreen(Sampler0);
    if (pageScreen >= 0) {

        int page = mcv2PageBits(Sampler0, 128, 16);
        if (page < MCV2_SCREEN_SLOTS[pageScreen]) {
            int slot = MCV2_SCREEN_FIRST_SLOTS[pageScreen] + page;
            gl_Position = mcv2Place(UV0, 0.0, float(mcv2SlotRow(width, slot)), float(width), float(mcv2SlotRow(width, slot + 1)));
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
    int streamLow = mcv2SymbolAt(Sampler0, 13);
    int streamHigh = mcv2SymbolAt(Sampler0, 14);
    int check = mcv2SymbolAt(Sampler0, 15);
    if (((column + row + columns + screenRows + facing + streamLow + streamHigh) & 63) != check || facing > 3) {
        return;
    }
    int anchorScreen = mcv2ScreenOf(uint(streamLow | (streamHigh << 6)));
    if (anchorScreen < 0) {
        return;
    }

    vec3 right = facing == 0 ? vec3(1.0, 0.0, 0.0) : facing == 1 ? vec3(0.0, 0.0, 1.0)
        : facing == 2 ? vec3(-1.0, 0.0, 0.0) : vec3(0.0, 0.0, -1.0);
    vec3 down = vec3(0.0, -1.0, 0.0);
    vec3 corner = (ModelViewMat * vec4(Position, 1.0)).xyz;
    vec3 projectedRight = (ModelViewMat * vec4(right, 0.0)).xyz;
    vec3 projectedDown = (ModelViewMat * vec4(down, 0.0)).xyz;
    vec3 tileTopLeft = corner - UV0.x * projectedRight - UV0.y * projectedDown;
    vec3 screenTopLeft = tileTopLeft - float(column) * projectedRight - float(row) * projectedDown;
    mcv2TopLeft = vec4(screenTopLeft, float(columns));
    mcv2Right = vec4(projectedRight, float(screenRows));
    mcv2Down = vec4(projectedDown, float(column * 64 + row));
    mcv2ProjectionColumn0 = ProjMat[0];
    mcv2ProjectionColumn1 = ProjMat[1];
    mcv2ProjectionColumn2 = ProjMat[2];
    mcv2ProjectionColumn3 = ProjMat[3];
    float descriptor = float(mcv2DescriptorRowOf(width, anchorScreen));
    gl_Position = mcv2Place(UV0, 0.0, descriptor, float(MCV2_DESCRIPTOR_PIXELS), descriptor + 1.0);
    mcv2Kind = 2;
#endif
}
#endif

#ifdef MCV2_PASS_TEXT_FRAGMENT
layout(location = 4) flat in int mcv2Kind;
layout(location = 5) flat in int mcv2Slot;
layout(location = 6) flat in vec4 mcv2TopLeft;
layout(location = 7) flat in vec4 mcv2Right;
layout(location = 8) flat in vec4 mcv2Down;
layout(location = 9) flat in vec4 mcv2ProjectionColumn0;
layout(location = 10) flat in vec4 mcv2ProjectionColumn1;
layout(location = 11) flat in vec4 mcv2ProjectionColumn2;
layout(location = 12) flat in vec4 mcv2ProjectionColumn3;

#if !defined(OIT_ALPHA_ONLY) && !defined(OIT_ACCUMULATE)

float mcv2DescriptorFloat(int index) {
    if (index < 12) {
        vec4 descriptorVector = index < 4 ? mcv2TopLeft : index < 8 ? mcv2Right : mcv2Down;
        return descriptorVector[index % 4];
    }
    int projectionIndex = index - 12;
    vec4 column = projectionIndex < 4 ? mcv2ProjectionColumn0 : projectionIndex < 8 ? mcv2ProjectionColumn1 : projectionIndex < 12 ? mcv2ProjectionColumn2 : mcv2ProjectionColumn3;
    return column[projectionIndex % 4];
}

void mcv2WritePage() {
    // Alpha 1 prevents translucent blending from changing the page bytes.
    int width = int(ScreenSize.x);
    int row = int(ScreenSize.y) - 1 - int(gl_FragCoord.y) - mcv2SlotRow(width, mcv2Slot);
    int pixel = row * width + int(gl_FragCoord.x);
    if (row < 0 || pixel >= MCV2_PAGE_PIXELS) {
        discard;
    }
    uint bits = 0u;
    for (int index = 0; index < 4; ++index) {
        bits |= uint(max(mcv2SymbolAt(Sampler0, pixel * 4 + index), 0)) << uint(6 * index);
    }
    fragColor = mcv2Texel(uvec4(bits & 255u, (bits >> 8u) & 255u, bits >> 16u, 255u));
}

void mcv2WriteDescriptor() {
    int columnIndex = int(gl_FragCoord.x);
    if (columnIndex == 0) {
        fragColor = mcv2Texel(uvec4(0x4Du, 0x43u, 0x56u, 255u));
    } else if (columnIndex == 1) {
        fragColor = mcv2Texel(uvec4(0xA1u, 0u, 0u, 255u));
    } else if ((columnIndex - 2) / 2 < 28) {
        uint bits = floatBitsToUint(mcv2DescriptorFloat((columnIndex - 2) / 2));
        fragColor = columnIndex % 2 == 0 ? mcv2Texel(uvec4(bits & 255u, (bits >> 8u) & 255u, (bits >> 16u) & 255u, 255u))
            : mcv2Texel(uvec4(bits >> 24u, 0u, 0u, 255u));
    } else {
        fragColor = mcv2Texel(uvec4(0u, 0u, 0u, 255u));
    }
}
#endif

bool mcv2TextFragment() {
    if (mcv2Kind != 0) {
        #if defined(OIT_ALPHA_ONLY) || defined(OIT_ACCUMULATE)
        // Improved transparency redirects text to targets that cannot carry exact page bytes.
        discard;
        #else
        if (mcv2Kind == 1) {
            mcv2WritePage();
        } else {
            mcv2WriteDescriptor();
        }
        return true;
        #endif
    }

    return false;
}
#endif

#endif
