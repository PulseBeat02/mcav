#version 330

// The MCV2 fragment decoder of the gpu-codec research repository (mcvideo_codec.glsl at commit
// 85445433aeb9f8a35a5ce528d47d8829976d1401, last changed by its round 18), ported into mcav's resource pack.
// Every output pixel is reconstructed independently from the frame bytes and the reference picture, with the same
// float arithmetic, in the same order, as the reference: on Mesa Intel and on llvmpipe it reproduces the CPU
// decoder byte for byte. mcav's changes are limited to where the inputs come from (the post chain's targets
// instead of uniforms and a codebook texture) and to refusing the syntax mcav does not implement.

// 1. Configuration/constants. Byte offsets agree with format.HEADER (<12I).
// The decode pass provides these before it calls mcvideoDecode: the frame bytes in BytesSampler, and
// DataBytes, OutputSize, FrameReady, ReferenceValid and ReferenceId as globals, not uniforms, because they come
// from the page status the chain computed this frame. mcv2ReferenceFetch reads whichever persistent reference
// the frame predicts from.

const uint MCVIDEO_MAGIC = 0x3156434du;
const uint MCVIDEO_OFFSET_MASK = 0x00ffffffu;
const int MCVIDEO_HEADER_BYTES = 48;
const uint MCVIDEO_KEYFRAME = 1u;
const uint MCVIDEO_SPARSE = 2u;
const uint MCVIDEO_DEFAULT_SOLID = 4u;
const uint MCVIDEO_DERIVED_DIRECTORY = 16u;
const uint MCVIDEO_DERIVED_OFFSETS = 32u;
const uint MCVIDEO_PACKED_SYMBOLS = 64u;
const uint MCVIDEO_TWO_LEVEL_WALK = 128u;
const uint MCVIDEO_MOTION_TABLE = 256u;
const uint MCVIDEO_ENDPOINT_TABLE = 512u;
// One flag per block size: a record's length must follow from its mode and size alone,
// and the walk reads flags from a register where a count byte would cost it a fetch.
const uint MCVIDEO_SELECTOR_TABLE_8 = 1024u;
const uint MCVIDEO_SELECTOR_TABLE_16 = 2048u;
const uint MCVIDEO_SELECTOR_TABLE_32 = 4096u;
const uint MCVIDEO_SELECTOR_TABLE = 7168u;
// Endpoint pairs are two little-endian RGB565 colours, four bytes rather than six.
const uint MCVIDEO_ENDPOINT_565 = 8192u;
const int MCVIDEO_WALK_SPAN = 8;
const int MCVIDEO_COARSE_SPAN = 32;
const int MCVIDEO_CHECKPOINT_GROUPS = 8;
const uint MCVIDEO_MODE_SKIP = 0u;
const uint MCVIDEO_MODE_MOTION = 1u;
const uint MCVIDEO_MODE_SOLID = 2u;
const uint MCVIDEO_MODE_PALETTE = 3u;
const uint MCVIDEO_MODE_INTRA = 4u;
const uint MCVIDEO_MODE_RESIDUAL = 8u;

// 2. Packed helpers. Bad accesses return zero without issuing an invalid fetch.
// Bound checking uses subtraction, avoiding overflow in offset+length.
bool mcvideoRange(int offset, int count) {
    return offset >= 0 && count >= 0 && offset <= DataBytes && count <= DataBytes - offset;
}

uvec4 mcvideoTexel(int offset) {
    if (!mcvideoRange(offset, 1)) return uvec4(0u);
    ivec2 dimensions = textureSize(BytesSampler, 0);
    int texelIndex = offset / 4;
    if (dimensions.x <= 0 || texelIndex / dimensions.x >= dimensions.y) return uvec4(0u);
    return uvec4(floor(texelFetch(BytesSampler,
                         ivec2(texelIndex % dimensions.x, texelIndex / dimensions.x), 0) * 255.0 + 0.5));
}

// Whether this block size names its selector words in the frame's table.
bool mcvideoListed(uint flags, int size) {
    uint bit = size == 8 ? MCVIDEO_SELECTOR_TABLE_8
             : (size == 16 ? MCVIDEO_SELECTOR_TABLE_16 : MCVIDEO_SELECTOR_TABLE_32);
    return (flags & bit) != 0u;
}

uint mcvideoByte(int offset) {
    return mcvideoTexel(offset)[offset & 3];
}

// Every table word is aligned; fetch it once instead of four dependent samples.
uint mcvideoWord(int offset) {
    if (!mcvideoRange(offset, 4) || (offset & 3) != 0) return 0u;
    uvec4 bytes = mcvideoTexel(offset);
    return bytes.r | (bytes.g << 8u) | (bytes.b << 16u) | (bytes.a << 24u);
}

// Three consecutive count bytes in at most two texel fetches. Reading them with three
// mcvideoByte calls is three fetches, which is what put round 17 over the draw ceiling.
uint mcvideoCounts(int at) {
    uvec4 lo = mcvideoTexel(at);
    int lane = at & 3;
    uint a = lo[lane], b, c;
    if (lane == 0) { b = lo[1]; c = lo[2]; }
    else if (lane == 1) { b = lo[2]; c = lo[3]; }
    else if (lane == 2) { b = lo[3]; c = mcvideoTexel(at + 2)[0]; }
    else { uvec4 hi = mcvideoTexel(at + 1); b = hi[0]; c = hi[1]; }
    return a | (b << 8u) | (c << 16u);
}

int mcvideoSigned(uint value, uint width) {
    // Two's-complement extension, width 1..31; production callers use 8 or 16.
    // Subtract after conversion to avoid relying on unsigned-to-signed wrap.
    uint signBit = 1u << (width - 1u);
    return int((value & ((1u << width) - 1u)) ^ signBit) - int(signBit);
}

// GLSL 330 has no core bitCount. SWAR count is constant work, even for bit 31.
uint mcvideoPopcount(uint value) {
    value -= (value >> 1u) & 0x55555555u;
    value = (value & 0x33333333u) + ((value >> 2u) & 0x33333333u);
    value = (value + (value >> 4u)) & 0x0f0f0f0fu;
    return (value * 0x01010101u) >> 24u;
}

// 3. Transport symbols. Logical symbols must first be recovered by an exact
// palette inverse (never approximate RGB distance). This bounded helper accepts
// three consecutive 6/7/8-bit symbols and extracts an 8-bit logical byte. The
// assembly pass supplies symbols beginning at floor(byteOffset*8/symbolBits).
// It is compiled only for an integration stage that actually uses it.
#ifdef MCVIDEO_TRANSPORT_HELPERS
uint mcvideoSymbolByte(uvec3 symbols, uint bitInFirstSymbol, uint symbolBits) {
    if (symbolBits < 6u || symbolBits > 8u || bitInFirstSymbol >= symbolBits) return 0u;
    uint mask = (1u << symbolBits) - 1u;
    if (any(greaterThan(symbols, uvec3(mask)))) return 0u;
    uint symbolWord = symbols.x | (symbols.y << symbolBits) | (symbols.z << (2u * symbolBits));
    return (symbolWord >> bitInFirstSymbol) & 255u;
}
#endif

// 4. Color conversion, gamma-coded 0..255 units. No chroma bias.
vec3 mcvideoYCoCgToRGB(vec3 color) {
    return vec3(color.x + color.y - color.z,
                color.x + color.z, color.x - color.y - color.z);
}

vec3 mcvideoRGB565(int offset) {
    // Two adjacent bytes, little-endian, R5 in bits 15..11. The expansion replicates the
    // high bits into the low ones, which is precisely what encoder.quantize565 does, so
    // the encoder sees what this reconstructs.
    if (!mcvideoRange(offset, 2)) return vec3(0.0);
    uvec4 first = mcvideoTexel(offset);
    int lane = offset & 3;
    uint lo, hi;
    if (lane == 0) { lo = first.r; hi = first.g; }
    else if (lane == 1) { lo = first.g; hi = first.b; }
    else if (lane == 2) { lo = first.b; hi = first.a; }
    else { lo = first.a; hi = mcvideoTexel(offset + 1).r; }
    uint value = lo | (hi << 8u);
    uint r = (value >> 11u) & 31u, g = (value >> 5u) & 63u, b = value & 31u;
    return vec3(float((r << 3u) | (r >> 2u)),
                float((g << 2u) | (g >> 4u)),
                float((b << 3u) | (b >> 2u)));
}

vec3 mcvideoRGB(int offset) {
    // Three adjacent bytes occupy at most two RGBA texels. Explicit component
    // selection reduces grid loads from 12 to 4..8 fetches per output pixel.
    if (!mcvideoRange(offset, 3)) return vec3(0.0);
    uvec4 first = mcvideoTexel(offset);
    int lane = offset & 3;
    if (lane == 0) return vec3(first.rgb);
    if (lane == 1) return vec3(first.gba);
    uvec4 second = mcvideoTexel((offset & ~3) + 4);
    if (lane == 2) return vec3(first.ba, second.r);
    return vec3(first.a, second.rg);
}

// Normalize a bounded u24 descriptor to the existing internal u32 layout.
// Three bytes occupy at most two RGBA texels; no prefix parsing is involved.
uint mcvideoIndexWord(int offset, int stride) {
    if (stride == 4) return mcvideoWord(offset);
    if (!mcvideoRange(offset, 3)) return 0u;
    uvec3 bytes = uvec3(mcvideoRGB(offset));
    return bytes.x | (bytes.y << 8u) | (bytes.z << 24u);
}

// 5. Block-header loading. Sparse groups have (mask, absolute table offset).
// There is no recursive neighbor parsing: one group, popcount, one descriptor.
bool mcvideoDescriptor(int blockIndex, int blockCount, uint flags, int payloadStart, int stride, out uint word) {
    int descriptorOffset = MCVIDEO_HEADER_BYTES + blockIndex * stride;
    if ((flags & MCVIDEO_SPARSE) != 0u) {
        int groups = (blockCount + 31) / 32;
        int group = blockIndex / 32;
        uint bit = uint(blockIndex & 31);
        bool derived = (flags & MCVIDEO_DERIVED_DIRECTORY) != 0u;
        int directoryEnd = MCVIDEO_HEADER_BYTES + (derived
            ? groups * 4 + ((groups + MCVIDEO_CHECKPOINT_GROUPS - 1) / MCVIDEO_CHECKPOINT_GROUPS) * 4
            : groups * 8);
        if (directoryEnd > payloadStart) return false;
        uint mask;
        uint tableOffset;
        if (derived) {
            // Every group pointer equals the running cursor, so only one in
            // MCVIDEO_CHECKPOINT_GROUPS is stored. Recovering this group's
            // pointer costs at most that many popcounts: bounded, no loop over
            // the frame, and no dependence on any later group.
            int anchor = (group / MCVIDEO_CHECKPOINT_GROUPS) * MCVIDEO_CHECKPOINT_GROUPS;
            mask = mcvideoWord(MCVIDEO_HEADER_BYTES + group * 4);
            if ((mask & (1u << bit)) == 0u) { word = 0u; return true; }
            tableOffset = mcvideoWord(
                MCVIDEO_HEADER_BYTES + groups * 4 + (group / MCVIDEO_CHECKPOINT_GROUPS) * 4);
            for (int i = 0; i < MCVIDEO_CHECKPOINT_GROUPS; ++i) {
                if (anchor + i >= group) break;
                tableOffset += mcvideoPopcount(
                    mcvideoWord(MCVIDEO_HEADER_BYTES + (anchor + i) * 4)) * uint(stride);
            }
        } else {
            int groupOffset = MCVIDEO_HEADER_BYTES + group * 8;
            mask = mcvideoWord(groupOffset);
            if ((mask & (1u << bit)) == 0u) { word = 0u; return true; }
            tableOffset = mcvideoWord(groupOffset + 4);
        }
        if (tableOffset > uint(payloadStart)) return false;
        descriptorOffset = int(tableOffset) + int(mcvideoPopcount(mask & ((1u << bit) - 1u))) * stride;
        if (descriptorOffset < directoryEnd) return false;
    }
    if (descriptorOffset + stride > payloadStart || (stride == 4 && (descriptorOffset & 3) != 0)) return false;
    word = mcvideoIndexWord(descriptorOffset, stride);
    return true;
}


// 5b. Derived descriptors: a one-byte plane carrying no addresses at all.
//
// Every offset the other form stores is provably redundant - the encoder is refused a
// frame unless each one equals the running cursor - so it exists only so a fragment can
// reach its record without walking the frame. This form removes it and recovers it
// instead, from three facts: a root's descriptor index is the popcount of the presence
// masks, a split's four children sit at four times the number of splits before it, and a
// record's address is the sum of the record lengths before it.
//
// One checkpoint every MCVIDEO_WALK_SPAN descriptors carries the payload cursor and the
// split prefix together, so a single bounded walk over the same descriptor bytes serves
// both recoveries instead of each needing its own plane. The walk is at most
// MCVIDEO_WALK_SPAN - 1 bytes at directly computable addresses, three times over for the
// deepest leaf, so it is bounded at compile time and nothing here depends on the frame.
uint mcvideoHalf(int offset) {
    return mcvideoByte(offset) | (mcvideoByte(offset + 1) << 8u);
}

int mcvideoCompactBytes(uint control) {
    int kind = int(control & 15u), form = int(control >> 4u);
    if (kind > 8 || form > 2) return -1;
    int bodyBytes = kind == 0   ? 1
                    : kind == 1 ? 6
                    : kind == 2 ? 10
                    : kind == 3 ? 8
                    : kind == 4 ? 18
                    : kind == 5 ? 4
                    : kind == 6 ? 5
                    : kind == 7 ? 4
                                : 5;
    return 1 + form + bodyBytes;
}

// The length a descriptor implies, which is exactly what makes the next address
// derivable. `at` is only read for a compact record, whose length lives in its own
// control byte; that single dependent fetch is the one the walk cannot avoid.
int mcvideoRecordBytes(uint mode, int size, int at, uint flags) {
    if (mode == MCVIDEO_MODE_SKIP || mode == 16u) return 0;
    if (mode == MCVIDEO_MODE_MOTION) return 2;
    // An indexed motion leaf spends one byte naming a table entry; the vector itself
    // lives in the index, so the cursor advances by the index and not by the vector.
    if (mode == 23u) return 1;
    if (mode == MCVIDEO_MODE_SOLID) return 3;
    if (mode == MCVIDEO_MODE_PALETTE) return 6 + size * size / 8;
    if (mode == 17u) return mcvideoCompactBytes(mcvideoByte(at));
    // Six of a pattern record's bytes are its endpoints; when the frame names them in
    // a table the record carries a one-byte index instead and is five shorter.
    if (mode == 18u) {
        int ends = (flags & MCVIDEO_ENDPOINT_TABLE) != 0u ? 1 : 6;
        int rest = mcvideoListed(flags, size) ? 1 : 1 + size / 8;
        return ends + rest;
    }
    if (mode == 21u || mode == 22u) {
        int factor = mode == 21u ? 2 : 4;
        int cells = size / factor;
        if (cells < 1 || size % factor != 0 || (cells * cells) % 8 != 0) return -1;
        return 6 + cells * cells / 8;
    }
    if (mode >= 4u && mode < 8u) {
        int grid = 1 << (mode - 4u);
        return grid <= size ? 3 * grid * grid : -1;
    }
    if (mode >= 8u && mode < 12u) {
        int grid = 1 << (mode - 8u);
        return grid <= size ? 2 + 3 * grid * grid : -1;
    }
    if (mode >= 12u && mode < 16u) {
        int luma = mode < 14u ? 4 : 8;
        int chroma = mode < 14u ? 1 : 2;
        bool residual = mode == 13u || mode == 15u;
        return luma <= size ? luma * luma + 2 * chroma * chroma + (residual ? 2 : 0) : -1;
    }
    return -1;
}

// Round 9: a descriptor is an index into the frame's own table of (mode, quantizer) pairs,
// at a width the table's size decides. Width 8 with no table is the unpacked form, so both
// read through here and the walk is unchanged apart from how many bytes it touches - fewer,
// since a window of MCVIDEO_WALK_SPAN descriptors is exactly `width` bytes.
uint mcvideoSymbol(int descriptorBase, int tableBase, int width, int index) {
    if (width == 8) return mcvideoByte(descriptorBase + index);
    int position = index * width;
    int at = descriptorBase + (position >> 3);
    // Two bytes always cover a symbol of at most eight bits; the mask discards any bits
    // the read picks up from the checkpoints that follow the plane.
    uint word = mcvideoByte(at) | (mcvideoByte(at + 1) << 8u);
    uint symbol = (word >> uint(position & 7)) & ((1u << uint(width)) - 1u);
    return mcvideoByte(tableBase + int(symbol));
}

int mcvideoSymbolWidth(int count) {
    int width = 1;
    for (int i = 0; i < 5; ++i) {
        if ((1 << width) >= count) break;
        width += 1;
    }
    return width;
}

int mcvideoWalkStride() { return MCVIDEO_COARSE_SPAN / MCVIDEO_WALK_SPAN; }
int mcvideoWalkDelta(int walkBase, int walkpoints, int index) {
    // Where checkpoint `index` keeps its delta. Anchors have none, so every checkpoint
    // before this one that was an anchor is skipped.
    int stride = mcvideoWalkStride();
    int coarse = (walkpoints + stride - 1) / stride;
    return walkBase + 2 + coarse * 4 + (index - 1 - (index - 1) / stride) * 2;
}
uint mcvideoWalkCursor(int walkBase, int walkpoints, uint flags, uint bits, int index) {
    // The delta is measured from the coarse anchor, not from the previous checkpoint, so
    // this is two reads whatever MCVIDEO_COARSE_SPAN is rather than a chain of them.
    if ((flags & MCVIDEO_TWO_LEVEL_WALK) == 0u)
        return mcvideoHalf(walkBase + index * 4);
    int stride = mcvideoWalkStride();
    uint base = mcvideoHalf(walkBase + 2 + (index / stride) * 4);
    if (index % stride == 0) return base;
    return base + (mcvideoHalf(mcvideoWalkDelta(walkBase, walkpoints, index)) &
                   ((1u << bits) - 1u));
}
uint mcvideoWalkSplits(int walkBase, int walkpoints, uint flags, uint bits, int index) {
    if ((flags & MCVIDEO_TWO_LEVEL_WALK) == 0u)
        return mcvideoHalf(walkBase + index * 4 + 2);
    int stride = mcvideoWalkStride();
    uint base = mcvideoHalf(walkBase + 2 + (index / stride) * 4 + 2);
    if (index % stride == 0) return base;
    // The bits above the two widths are zero, which the encoder guarantees and the CPU
    // parser refuses a stream without, so the split delta is the rest of the entry.
    return base + (mcvideoHalf(mcvideoWalkDelta(walkBase, walkpoints, index)) >> bits);
}
int mcvideoSplitPrefix(int descriptorBase, int tableBase, int width, int walkBase,
                       int walkpoints, uint flags, uint bits, int at) {
    int anchor = (at / MCVIDEO_WALK_SPAN) * MCVIDEO_WALK_SPAN;
    int prefix = int(mcvideoWalkSplits(walkBase, walkpoints, flags, bits,
                                       at / MCVIDEO_WALK_SPAN));
    for (int i = 0; i < MCVIDEO_WALK_SPAN; ++i) {
        if (anchor + i >= at) break;
        if ((mcvideoSymbol(descriptorBase, tableBase, width, anchor + i) & 31u) == 16u)
            prefix += 1;
    }
    return prefix;
}

int mcvideoPayloadCursor(int descriptorBase, int tableBase, int width, int walkBase,
                         int walkpoints, uint flags, uint bits, int payloadStart,
                         int at, int n0, int n1) {
    int anchor = (at / MCVIDEO_WALK_SPAN) * MCVIDEO_WALK_SPAN;
    int cursor = int(mcvideoWalkCursor(walkBase, walkpoints, flags, bits,
                                       at / MCVIDEO_WALK_SPAN));
    for (int i = 0; i < MCVIDEO_WALK_SPAN; ++i) {
        int index = anchor + i;
        if (index >= at) break;
        int size = index < n0 ? 32 : (index < n0 + n1 ? 16 : 8);
        int bytes = mcvideoRecordBytes(
            mcvideoSymbol(descriptorBase, tableBase, width, index) & 31u, size,
            payloadStart + cursor, flags);
        if (bytes < 0) return -1;
        cursor += bytes;
    }
    return cursor;
}

bool mcvideoDerivedDescriptor(ivec2 pixel, int blockCount, ivec2 blockDimensions,
                              uint flags, int payloadStart, out uint word,
                              out int blockSize, out int endpointBase,
                              out int selectorHead, out int selectorTail) {
    endpointBase = -1;
    selectorHead = -1;
    selectorTail = -1;
    int groups = (blockCount + 31) / 32;
    int checkpoints =
        (groups + MCVIDEO_CHECKPOINT_GROUPS - 1) / MCVIDEO_CHECKPOINT_GROUPS;
    int countsBase = MCVIDEO_HEADER_BYTES + groups * 4 + checkpoints * 4;
    int descriptorBase = countsBase + 6;
    int n0 = int(mcvideoHalf(countsBase));
    int n1 = int(mcvideoHalf(countsBase + 2));
    int n2 = int(mcvideoHalf(countsBase + 4));
    int total = n0 + n1 + n2;
    int tableBase = descriptorBase;
    int width = 8;
    if ((flags & MCVIDEO_PACKED_SYMBOLS) != 0u) {
        int size = int(mcvideoByte(descriptorBase));
        if (size < 1 || size > 32) return false;
        tableBase = descriptorBase + 1;
        width = mcvideoSymbolWidth(size);
        descriptorBase = tableBase + size;
    }
    int plane = (total * width + 7) / 8;
    int walkBase = descriptorBase + plane;
    int walkpoints = (total + MCVIDEO_WALK_SPAN - 1) / MCVIDEO_WALK_SPAN;
    int region = walkpoints * 4;
    uint bits = 0u;
    if ((flags & MCVIDEO_TWO_LEVEL_WALK) != 0u) {
        int stride = mcvideoWalkStride();
        int coarse = (walkpoints + stride - 1) / stride;
        region = 2 + coarse * 4 + (walkpoints - coarse) * 2;
        // The cursor width is read once here and handed to every checkpoint read, so
        // the walk does not pay for it again at each one.
        bits = mcvideoByte(walkBase);
        if (bits < 1u || bits > 15u) return false;
    }
    // The table counts sit in the index after the walk region, in one fixed order -
    // motion, endpoints, then three selector counts - so every one of them is addressed
    // from this base rather than from a region that later reads have already advanced.
    int headBase = walkBase + region;
    bool hasMotion = (flags & MCVIDEO_MOTION_TABLE) != 0u;
    bool hasPairs = (flags & MCVIDEO_ENDPOINT_TABLE) != 0u;
    int pairStride = (flags & MCVIDEO_ENDPOINT_565) != 0u ? 4 : 6;
    int pairCount = 0;
    if (hasPairs) {
        pairCount = int(mcvideoByte(headBase + (hasMotion ? 1 : 0)));
        if (pairCount < 1) return false;
        region += 1;
        // Pairs sit at the very end of the frame, so their base needs the pair count and
        // nothing else - no fragment pays for a table it does not read.
        endpointBase = DataBytes - pairCount * pairStride;
        if (endpointBase < payloadStart) return false;
    }
    int vectorCount = 0;
    int vectorBase = 0;
    if (hasMotion) {
        // The count sits in the index so the length check can size the frame; the vectors
        // sit at the end of the payload so a leaf pointing at one still points at or
        // after payloadStart, which is what the record bounds guard requires.
        vectorCount = int(mcvideoByte(headBase));
        if (vectorCount < 1) return false;
        region += 1;
        vectorBase = DataBytes - pairCount * pairStride - vectorCount * 2;
        if (vectorBase < payloadStart) return false;
    }
    if ((flags & MCVIDEO_SELECTOR_TABLE) != 0u) {
        // Only where the counts live and where the region ends; the counts themselves are
        // read in the pattern path, which is the only place that wants them.
        selectorHead = headBase + (hasMotion ? 1 : 0) + (hasPairs ? 1 : 0);
        selectorTail = DataBytes - pairCount * pairStride - vectorCount * 2;
        region += 3;
    }
    if (walkBase + region != payloadStart || n1 % 4 != 0 || n2 % 4 != 0)
        return false;
    blockSize = 32;
    word = 0u;
    int blockIndex = (pixel.y / 32) * blockDimensions.x + pixel.x / 32;
    int group = blockIndex / 32;
    uint bit = uint(blockIndex & 31);
    uint mask = mcvideoWord(MCVIDEO_HEADER_BYTES + group * 4);
    // A root the masks leave out is an implicit skip, exactly as in the sparse form.
    if ((mask & (1u << bit)) == 0u) return true;
    int anchor = (group / MCVIDEO_CHECKPOINT_GROUPS) * MCVIDEO_CHECKPOINT_GROUPS;
    uint index = mcvideoWord(MCVIDEO_HEADER_BYTES + groups * 4 +
                             (group / MCVIDEO_CHECKPOINT_GROUPS) * 4);
    for (int i = 0; i < MCVIDEO_CHECKPOINT_GROUPS; ++i) {
        if (anchor + i >= group) break;
        index += mcvideoPopcount(mcvideoWord(MCVIDEO_HEADER_BYTES + (anchor + i) * 4));
    }
    index += mcvideoPopcount(mask & ((1u << bit) - 1u));
    if (int(index) >= n0) return false;
    int at = int(index);
    for (int depth = 0; depth < 2; ++depth) {
        uint value = mcvideoSymbol(descriptorBase, tableBase, width, at);
        if ((value & 31u) != 16u) break;
        if ((value >> 5u) != 0u) return false;
        // A split's children are the only thing the prefix has to locate, because the
        // four sit together: no child pointer exists to be range-checked.
        int within = mcvideoSplitPrefix(descriptorBase, tableBase, width, walkBase,
                                        walkpoints, flags, bits, at) -
                     (depth == 0 ? 0 : n1 / 4);
        if (within < 0) return false;
        blockSize /= 2;
        ivec2 quadrant = (pixel / blockSize) & ivec2(1);
        at = (depth == 0 ? n0 : n0 + n1) + within * 4 + quadrant.y * 2 + quadrant.x;
        if (at >= (depth == 0 ? n0 + n1 : total)) return false;
    }
    uint value = mcvideoSymbol(descriptorBase, tableBase, width, at);
    uint mode = value & 31u;
    // Splits below the bounded depth, sparse children and immediate motion all need a
    // field this form does not have, so none of them can appear in it.
    if (mode == 16u || mode == 19u || mode == 20u) return false;
    if (mode > 23u && mode != 21u && mode != 22u) return false;
    if (mode == MCVIDEO_MODE_SKIP) return (value >> 5u) == 0u;
    int cursor = mcvideoPayloadCursor(descriptorBase, tableBase, width, walkBase,
                                      walkpoints, flags, bits, payloadStart, at, n0,
                                      n1);
    if (cursor < 0 || payloadStart + cursor > DataBytes) return false;
    if (mode == 23u) {
        // Resolve the index here so every later stage sees an ordinary motion leaf whose
        // two bytes sit at a computable address. No new decode path, one extra fetch.
        if ((flags & MCVIDEO_MOTION_TABLE) == 0u) return false;
        int which = int(mcvideoByte(payloadStart + cursor));
        if (which >= vectorCount) return false;
        word = uint(vectorBase + which * 2) | (MCVIDEO_MODE_MOTION << 24u) |
               ((value >> 5u) << 29u);
        return true;
    }
    word = uint(payloadStart + cursor) | (mode << 24u) | ((value >> 5u) << 29u);
    return true;
}

// 6. Prediction/fallback. Same-position fallback never uses untrusted motion.
vec3 mcvideoFallback(ivec2 pixel) {
    if (!ReferenceValid || any(notEqual(mcv2ReferenceSize(), OutputSize))) return vec3(0.0);
    return floor(mcv2ReferenceFetch(clamp(pixel, ivec2(0), OutputSize - 1)).rgb * 255.0 + 0.5);
}

// Recover exact reference bytes before interpolation. Multiplying a normalized
// sample by 255 without rounding can fall just below an exact half-integer tie.
vec3 mcvideoReferencePixel(ivec2 pixel) {
    return floor(mcv2ReferenceFetch(pixel).rgb * 255.0 + 0.5);
}

// 7. Motion compensation. Four explicit point samples give portable half-pel
// arithmetic. Integral motion takes one sample; no dependency on sampler state.
vec3 mcvideoPredict(ivec2 pixel, ivec2 halfMotion) {
    vec2 position = clamp(vec2(pixel) + vec2(halfMotion) * 0.5, vec2(0.0), vec2(OutputSize - 1));
    ivec2 lower = ivec2(floor(position));
    if (all(equal(halfMotion & ivec2(1), ivec2(0)))) return mcvideoReferencePixel(lower);
    ivec2 upper = min(lower + 1, OutputSize - 1);
    vec2 fraction = fract(position);
    vec3 top = mix(mcvideoReferencePixel(lower),
                   mcvideoReferencePixel(ivec2(upper.x, lower.y)), fraction.x);
    vec3 bottom = mix(mcvideoReferencePixel(ivec2(lower.x, upper.y)),
                      mcvideoReferencePixel(upper), fraction.x);
    return mix(top, bottom, fraction.y);
}

// 8. Residual grids. Signed bytes use explicit sign extension, never uint->int8.
vec3 mcvideoGridNode(int offset, ivec2 position, int gridSize, bool residual) {
    int nodeOffset = offset + (position.y * gridSize + position.x) * 3;
    vec3 values = mcvideoRGB(nodeOffset);
    return residual ? values - step(vec3(128.0), values) * 256.0 : values;
}

vec3 mcvideoGrid(int offset, ivec2 pixelInBlock, int blockSize, int gridSize, bool residual) {
    if (gridSize == 1) return mcvideoGridNode(offset, ivec2(0), gridSize, residual);
    vec2 position = clamp((vec2(pixelInBlock) + 0.5) * float(gridSize) / float(blockSize) - 0.5,
                          vec2(0.0), vec2(gridSize - 1));
    ivec2 lower = ivec2(floor(position));
    ivec2 upper = min(lower + 1, gridSize - 1);
    vec2 fraction = fract(position);
    vec3 top = mix(mcvideoGridNode(offset, lower, gridSize, residual),
                   mcvideoGridNode(offset, ivec2(upper.x, lower.y), gridSize, residual), fraction.x);
    vec3 bottom = mix(mcvideoGridNode(offset, ivec2(lower.x, upper.y), gridSize, residual),
                      mcvideoGridNode(offset, upper, gridSize, residual), fraction.x);
    return mix(top, bottom, fraction.y);
}

// Reduced-chroma modes keep a scalar luma plane followed by interleaved Co/Cg.
// All grid widths divide B, so coordinates and interpolation weights are dyadic.
// Component loads are bounded by the record validation in mcvideoDecode.
float mcvideoPlaneNode(int offset, ivec2 position, int gridSize, int stride, bool signedValue) {
    int index = int(float(position.y) * float(gridSize)) + position.x;
    uint value = mcvideoByte(offset + index * stride);
    return signedValue ? float(mcvideoSigned(value, 8u)) : float(value);
}

float mcvideoPlane(int offset, ivec2 localPixel, int blockSize, int gridSize, int stride, bool signedValue) {
    if (gridSize == 1) return mcvideoPlaneNode(offset, ivec2(0), gridSize, stride, signedValue);
    vec2 position = clamp((vec2(localPixel) + 0.5) * float(gridSize) / float(blockSize) - 0.5,
                          vec2(0.0), vec2(gridSize - 1));
    ivec2 lower = ivec2(floor(position));
    ivec2 upper = min(lower + 1, gridSize - 1);
    vec2 fraction = fract(position);
    return mix(mix(mcvideoPlaneNode(offset, lower, gridSize, stride, signedValue),
                   mcvideoPlaneNode(offset, ivec2(upper.x, lower.y), gridSize, stride, signedValue), fraction.x),
               mix(mcvideoPlaneNode(offset, ivec2(lower.x, upper.y), gridSize, stride, signedValue),
                   mcvideoPlaneNode(offset, upper, gridSize, stride, signedValue), fraction.x), fraction.y);
}

#ifndef MCVIDEO_NO_COMPACT
// MCV2 compact temporal classes. The books are immutable pack content, generated from the same bytes as mcav's
// ResidualBooks, four bytes to a word, little-endian.
float mcvideoBook(int index) {
  uint value = (MCV2_BOOKS[index >> 2] >> uint((index & 3) * 8)) & 255u;
  return float(mcvideoSigned(value, 8u));
}
float mcvideoCompactNode(int offset, ivec2 p, int kind, int indexA,
                         int indexB) {
  int index = p.y * 4 + p.x;
  if (kind == 2 || kind == 3) {
    uint byteValue = mcvideoByte(offset + index / 2);
    return float(mcvideoSigned(byteValue >> uint((index & 1) * 4), 4u));
  }
  if (kind == 5)
    return mcvideoBook(indexA * 16 + index);
  int side = p.x / 2;
  return mcvideoBook(1024 + side * 512 + (side == 0 ? indexA : indexB) * 8 +
                     p.y * 2 + p.x % 2);
}
float mcvideoCompactGrid(int offset, ivec2 localPixel, int blockSize, int kind,
                         int indexA, int indexB) {
  vec2 p = clamp((vec2(localPixel) + 0.5) * 4.0 / float(blockSize) - 0.5,
                 vec2(0), vec2(3));
  ivec2 a = ivec2(floor(p)), b = min(a + 1, ivec2(3));
  vec2 f = fract(p);
  return mix(
      mix(mcvideoCompactNode(offset, a, kind, indexA, indexB),
          mcvideoCompactNode(offset, ivec2(b.x, a.y), kind, indexA, indexB),
          f.x),
      mix(mcvideoCompactNode(offset, ivec2(a.x, b.y), kind, indexA, indexB),
          mcvideoCompactNode(offset, b, kind, indexA, indexB), f.x),
      f.y);
}
vec3 mcvideoCompact(int offset, uint q, ivec2 pixel, int blockSize,
                    ivec2 motion, vec3 fallback) {
  if (!mcvideoRange(offset, 1))
    return fallback;
  uint control = mcvideoByte(offset);
  int kind = int(control & 15u), form = int(control >> 4u);
  if (kind > 8 || form > 2 || (kind == 7 && q != 0u))
    return fallback;
  int bodyBytes = kind == 0   ? 1
                  : kind == 1 ? 6
                  : kind == 2 ? 10
                  : kind == 3 ? 8
                  : kind == 4 ? 18
                  : kind == 5 ? 4
                  : kind == 6 ? 5
                  : kind == 7 ? 4
                              : 5;
  if (!mcvideoRange(offset, 1 + form + bodyBytes))
    return fallback;
  if (form == 1) {
    uint v = mcvideoByte(offset + 1);
    motion += ivec2(mcvideoSigned(v, 4u), mcvideoSigned(v >> 4u, 4u));
  } else if (form == 2)
    motion += ivec2(mcvideoSigned(mcvideoByte(offset + 1), 8u),
                    mcvideoSigned(mcvideoByte(offset + 2), 8u));
  offset += 1 + form;
  vec3 predicted = mcvideoPredict(pixel, motion);
  ivec2 localPixel = pixel % blockSize;
  float scale = float(1u << q);
  if (kind == 0)
    return predicted +
           vec3(float(mcvideoSigned(mcvideoByte(offset), 8u)) * scale);
  if (kind == 7) {
    float gain = 1.0 + float(mcvideoSigned(mcvideoByte(offset), 8u)) / 64.0;
    vec3 bias = mcvideoRGB(offset + 1);
    bias -= step(vec3(128), bias) * 256.0;
    return predicted * gain + mcvideoYCoCgToRGB(bias);
  }
  if (kind == 8) {
    vec3 coeff = mcvideoRGB(offset);
    coeff -= step(vec3(128), coeff) * 256.0;
    vec2 axis = (vec2(localPixel) + 0.5) / float(blockSize) * 2.0 - 1.0;
    float y = coeff.x + coeff.y * axis.x + coeff.z * axis.y;
    vec3 color = vec3(y, float(mcvideoSigned(mcvideoByte(offset + 3), 8u)),
                      float(mcvideoSigned(mcvideoByte(offset + 4), 8u)));
    return predicted + mcvideoYCoCgToRGB(color * scale);
  }
  float y = 0.0;
  vec2 chroma = vec2(0);
  if (kind == 1 || kind == 4) {
    int gridSize = kind == 1 ? 2 : 4;
    y = mcvideoPlane(offset, localPixel, blockSize, gridSize, 1, true);
    chroma = vec2(
        float(mcvideoSigned(mcvideoByte(offset + gridSize * gridSize), 8u)),
        float(
            mcvideoSigned(mcvideoByte(offset + gridSize * gridSize + 1), 8u)));
  } else if (kind == 2 || kind == 3) {
    y = mcvideoCompactGrid(offset, localPixel, blockSize, kind, 0, 0);
    if (kind == 2)
      chroma = vec2(float(mcvideoSigned(mcvideoByte(offset + 8), 8u)),
                    float(mcvideoSigned(mcvideoByte(offset + 9), 8u)));
  } else {
    int indexA = int(mcvideoByte(offset + 3)), indexB = 0;
    if (kind == 5 && indexA >= 64)
      return fallback;
    if (kind == 6) {
      uint hi = mcvideoByte(offset + 4);
      if (hi > 15u)
        return fallback;
      indexB = (indexA >> 6) | (int(hi) << 2);
      indexA &= 63;
    }
    y = float(mcvideoSigned(mcvideoByte(offset), 8u)) +
        mcvideoCompactGrid(offset, localPixel, blockSize, kind, indexA, indexB);
    chroma = vec2(float(mcvideoSigned(mcvideoByte(offset + 1), 8u)),
                  float(mcvideoSigned(mcvideoByte(offset + 2), 8u)));
  }
  return predicted + mcvideoYCoCgToRGB(vec3(y, chroma) * scale);
}

#endif

// 9/10. Intra selection and final reconstruction. All loop bounds are constant
// or absent; malformed mode values cannot create arbitrary work or coordinates.
vec3 mcvideoDecode(ivec2 pixel) {
  vec3 fallback = mcvideoFallback(pixel);
  if (!FrameReady || DataBytes < MCVIDEO_HEADER_BYTES ||
      DataBytes > int(MCVIDEO_OFFSET_MASK) ||
      any(lessThan(OutputSize, ivec2(1))) ||
      any(greaterThan(OutputSize, ivec2(4096))))
    return fallback;
  // mcav decodes MCV2 only: MCV1 frames, the motion table of round 15 and the coarse palettes of round 3 are
  // refused here as they are by the Java decoder
  bool tree = mcvideoWord(0) == 0x3256434du;
  if (!tree ||
      mcvideoWord(32) != uint(DataBytes) || mcvideoWord(40) != 0u ||
      mcvideoWord(44) != 0u)
    return fallback;
  uint config = mcvideoWord(4);
  uint blockLog = (config >> 8u) & 255u;
  uint flags = config >> 16u;
  if ((config & 255u) != (tree ? 2u : 1u) || blockLog < 2u || blockLog > 5u ||
      (tree && blockLog != 5u) || flags > (tree ? 16383u : 7u) ||
      (flags & MCVIDEO_MOTION_TABLE) != 0u)
    return fallback;
  int stride = (flags & 8u) != 0u ? 3 : 4;
  if (stride == 3 && DataBytes > 65535) return fallback;
  uint dimensions = mcvideoWord(8);
  if (ivec2(int(dimensions & 65535u), int(dimensions >> 16u)) != OutputSize)
    return fallback;
  bool keyframe = (flags & MCVIDEO_KEYFRAME) != 0u;
  bool defaultSolid = (flags & MCVIDEO_DEFAULT_SOLID) != 0u;
  uint defaultColor = mcvideoWord(36);
  if ((defaultSolid && !keyframe) || defaultColor > 0x00ffffffu ||
      (!defaultSolid && defaultColor != 0u))
    return fallback;
  if (keyframe) {
    if (mcvideoWord(12) != mcvideoWord(16) || mcvideoWord(20) != 0u)
      return fallback;
  } else if (!ReferenceValid || ReferenceId != mcvideoWord(16) ||
             mcvideoWord(12) == ReferenceId ||
             any(notEqual(mcv2ReferenceSize(), OutputSize)))
    return fallback;
  int blockSize = 1 << blockLog;
  ivec2 blockDimensions = (OutputSize + blockSize - 1) / blockSize;
  int blockCount = blockDimensions.x * blockDimensions.y;
  if (mcvideoWord(24) != uint(blockCount))
    return fallback;
  uint payloadValue = mcvideoWord(28);
  if (payloadValue < uint(MCVIDEO_HEADER_BYTES) ||
      payloadValue > uint(DataBytes))
    return fallback;
  int payloadStart = int(payloadValue);
  if (!tree && (flags & MCVIDEO_SPARSE) == 0u &&
      payloadStart != MCVIDEO_HEADER_BYTES + blockCount * 4)
    return fallback;
  int blockIndex =
      (pixel.y / blockSize) * blockDimensions.x + pixel.x / blockSize;
  uint word;
  int endpointBase = -1;
  int selectorHead = -1;
  int selectorTail = -1;
  bool derivedOffsets = tree && (flags & MCVIDEO_DERIVED_OFFSETS) != 0u;
  if (derivedOffsets) {
    if ((flags & MCVIDEO_SPARSE) == 0u ||
        (flags & MCVIDEO_DERIVED_DIRECTORY) == 0u || stride != 4)
      return fallback;
    if (!mcvideoDerivedDescriptor(pixel, blockCount, blockDimensions, flags, payloadStart,
                                  word, blockSize, endpointBase, selectorHead,
                                  selectorTail))
      return fallback;
  } else if (!mcvideoDescriptor(blockIndex, blockCount, flags, payloadStart, stride,
                                word))
    return fallback;
  // Descriptor, one little-endian 32-bit word (bit 0 is the byte's LSB):
  // 31       28 27       24 23                                   0
  // +----------+-----------+-------------------------------------+
  // | Q log2   | mode      | absolute payload BYTE offset        |
  // +----------+-----------+-------------------------------------+
  // Q=0..7 on residuals, zero otherwise; SKIP is the entire word zero.
  if (tree && !derivedOffsets) {
    // Exactly two dependent child groups; no unbounded tree traversal.
    for (int depth = 0; depth < 2; ++depth) {
      uint splitMode = (word >> 24u) & 31u;
      if (splitMode != 16u && splitMode != 19u) break;
      int childOffset = int(word & MCVIDEO_OFFSET_MASK);
      if ((word >> 29u) != 0u || childOffset < MCVIDEO_HEADER_BYTES) return fallback;
      blockSize /= 2;
      ivec2 quadrant = (pixel / blockSize) & ivec2(1);
      int child = quadrant.y * 2 + quadrant.x;
      if (splitMode == 19u) {
        if (stride != 3 || childOffset >= payloadStart) return fallback;
        uint mask = mcvideoByte(childOffset);
        if (mask >= 15u || childOffset + 1 + int(mcvideoPopcount(mask)) * 3 > payloadStart) return fallback;
        if ((mask & (1u << uint(child))) == 0u) word = 0u;
        else word = mcvideoIndexWord(childOffset + 1 + int(mcvideoPopcount(mask & ((1u << uint(child)) - 1u))) * 3, 3);
      } else {
        if ((stride == 4 && (childOffset & 3) != 0) || childOffset > payloadStart - 4 * stride) return fallback;
        word = mcvideoIndexWord(childOffset + child * stride, stride);
      }
    }
  }
  uint mode = (word >> 24u) & (tree ? 31u : 15u);
  uint quantizer = word >> (tree ? 29u : 28u);
  if (mode == 17u && tree) {
#ifdef MCVIDEO_NO_COMPACT
    return fallback;
#else
    int compactOffset = int(word & MCVIDEO_OFFSET_MASK);
    if (keyframe || compactOffset < payloadStart)
      return fallback;
    uint global = mcvideoWord(20);
    ivec2 motion =
        ivec2(mcvideoSigned(global, 16u), mcvideoSigned(global >> 16u, 16u));
    return mcvideoCompact(compactOffset, quantizer, pixel, blockSize, motion,
                          fallback);
#endif
  }
  if (mode == 18u && tree) {
    int patternOffset = int(word & MCVIDEO_OFFSET_MASK);
    // With an endpoint table the record is one index byte, the orientation, then the
    // axis bits; the two colours come from the table instead of from the record head.
    bool named = (flags & MCVIDEO_ENDPOINT_TABLE) != 0u && endpointBase >= 0;
    bool listed = mcvideoListed(flags, blockSize) && selectorHead >= 0;
    int head = named ? 1 : 6;
    int patternBytes = head + (listed ? 1 : 1 + blockSize / 8);
    if (quantizer != 0u || patternOffset < payloadStart || !mcvideoRange(patternOffset, patternBytes)) return fallback;
    // The orientation and axis bits either follow the endpoints in the record, or are one
    // entry of the table for this block size - the tables sit smallest size first.
    int wordAt = patternOffset + head;
    if (listed) {
        int entryBytes = 1 + blockSize / 8;
        uint counts = mcvideoCounts(selectorHead);
        int c8 = int(counts & 255u);
        int c16 = int((counts >> 8u) & 255u);
        int c32 = int((counts >> 16u) & 255u);
        if (c8 + c16 + c32 < 1) return fallback;
        int skip = blockSize == 8 ? 0 : (blockSize == 16 ? c8 * 2 : c8 * 2 + c16 * 3);
        int slots = blockSize == 8 ? c8 : (blockSize == 16 ? c16 : c32);
        int slot = int(mcvideoByte(patternOffset + head));
        if (slot >= slots) return fallback;
        // The region ends where the motion vectors begin, so its base is the tail less
        // every word it holds.
        wordAt = selectorTail - (c8 * 2 + c16 * 3 + c32 * 5) + skip + slot * entryBytes;
        if (wordAt < payloadStart) return fallback;
        if (!mcvideoRange(wordAt, entryBytes)) return fallback;
    }
    uint orientation = mcvideoByte(wordAt);
    if (orientation > 1u) return fallback;
    int axis = (orientation == 0u ? pixel.x : pixel.y) % blockSize;
    uint selector = (mcvideoByte(wordAt + 1 + axis / 8) >> uint(axis & 7)) & 1u;
    if (!named) return mcvideoRGB(patternOffset + int(selector) * 3);
    int which = int(mcvideoByte(patternOffset));
    if ((flags & MCVIDEO_ENDPOINT_565) != 0u) {
        int pairAt = endpointBase + which * 4;
        if (!mcvideoRange(pairAt, 4)) return fallback;
        return mcvideoRGB565(pairAt + int(selector) * 2);
    }
    int entry = endpointBase + which * 6;
    if (!mcvideoRange(entry, 6)) return fallback;
    return mcvideoRGB(entry + int(selector) * 3);
  }
  // the coarse palettes of round 3 were not kept by the frontier, and mcav does not decode them
  if (mode == 21u || mode == 22u)
    return fallback;
  if (mode == 20u && tree) {
    // Immediate motion: the two record bytes ride in the descriptor's address
    // field, so this leaf costs no payload byte and no second memory fetch.
    if (keyframe || quantizer != 0u || (word & MCVIDEO_OFFSET_MASK) > 0xffffu)
      return fallback;
    uint immediateGlobal = mcvideoWord(20);
    ivec2 immediateMotion = ivec2(mcvideoSigned(immediateGlobal, 16u),
                                  mcvideoSigned(immediateGlobal >> 16u, 16u));
    immediateMotion += ivec2(mcvideoSigned(word & 255u, 8u),
                             mcvideoSigned((word >> 8u) & 255u, 8u));
    return mcvideoPredict(pixel, immediateMotion);
  }
  if (mode >= 16u)
    return fallback;
  int offset = int(word & MCVIDEO_OFFSET_MASK);
  bool reducedChroma = mode >= 12u;
  bool residual = (mode >= MCVIDEO_MODE_RESIDUAL && mode < 12u) ||
                  mode == 13u || mode == 15u;
  if (quantizer > 7u || (!residual && quantizer != 0u) ||
      (mode == MCVIDEO_MODE_SKIP && word != 0u))
    return fallback;
  if (keyframe && (mode == MCVIDEO_MODE_MOTION || residual ||
                   (mode == MCVIDEO_MODE_SKIP && !defaultSolid)))
    return fallback;
  if (mode == MCVIDEO_MODE_SKIP && defaultSolid)
    return mcvideoRGB(36);
  int gridSize = 1;
  int chromaSize = 1;
  int recordBytes = 0;
  if (mode == MCVIDEO_MODE_MOTION)
    recordBytes = 2;
  else if (mode == MCVIDEO_MODE_SOLID)
    recordBytes = 3;
  else if (mode == MCVIDEO_MODE_PALETTE)
    recordBytes = 6 + blockSize * blockSize / 8;
  else if (reducedChroma) {
    gridSize = mode < 14u ? 4 : 8;
    chromaSize = mode < 14u ? 1 : 2;
    if (gridSize > blockSize)
      return fallback;
    recordBytes =
        gridSize * gridSize + 2 * chromaSize * chromaSize + (residual ? 2 : 0);
  } else if (mode >= MCVIDEO_MODE_INTRA) {
    gridSize =
        1 << (mode - (residual ? MCVIDEO_MODE_RESIDUAL : MCVIDEO_MODE_INTRA));
    if (gridSize > blockSize)
      return fallback;
    recordBytes = 3 * gridSize * gridSize + (residual ? 2 : 0);
  }
  if (mode != MCVIDEO_MODE_SKIP &&
      (offset < payloadStart || !mcvideoRange(offset, recordBytes)))
    return fallback;
  uint global = mcvideoWord(20);
  ivec2 motion =
      ivec2(mcvideoSigned(global, 16u), mcvideoSigned(global >> 16u, 16u));
  if (mode == MCVIDEO_MODE_MOTION || residual) {
    motion += ivec2(mcvideoSigned(mcvideoByte(offset), 8u),
                    mcvideoSigned(mcvideoByte(offset + 1), 8u));
    offset += 2;
  }
  if (mode <= MCVIDEO_MODE_MOTION)
    return mcvideoPredict(pixel, motion);
  if (mode == MCVIDEO_MODE_SOLID)
    return mcvideoRGB(offset);
  ivec2 localPixel = pixel % blockSize;
  if (mode == MCVIDEO_MODE_PALETTE) {
    // Integer products here are <=1024 and exactly representable in float.
    // This avoids an observed Intel 3.3 dynamic-shift/multiply miscompile.
    int selectorIndex =
        int(float(localPixel.y) * float(blockSize)) + localPixel.x;
    uint selector = (mcvideoByte(offset + 6 + selectorIndex / 8) >>
                     uint(selectorIndex & 7)) &
                    1u;
    return mcvideoRGB(offset + int(selector) * 3);
  }
  if (reducedChroma) {
    int chromaOffset = offset + gridSize * gridSize;
    vec3 color = vec3(
        mcvideoPlane(offset, localPixel, blockSize, gridSize, 1, residual),
        mcvideoPlane(chromaOffset, localPixel, blockSize, chromaSize, 2, true),
        mcvideoPlane(chromaOffset + 1, localPixel, blockSize, chromaSize, 2,
                     true));
    if (!residual)
      return mcvideoYCoCgToRGB(color);
    return mcvideoPredict(pixel, motion) +
           mcvideoYCoCgToRGB(color * float(1u << quantizer));
  }
  vec3 grid = mcvideoGrid(offset, localPixel, blockSize, gridSize, residual);
  if (!residual)
    return grid;
  return mcvideoPredict(pixel, motion) +
         mcvideoYCoCgToRGB(grid * float(1u << quantizer));
}

// 11. The decode pass quantizes the result to RGB8 explicitly; that quantization is normative for references.
