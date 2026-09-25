#version 330

// Reading MCV2 symbols out of map textures. Symbol s is sent as map colour s + 4, and the client uploads each map
// colour as one exact RGB texel, listed in MCV2_ALPHABET (generated from the server's map palette, which is the
// client's). Requires mcv2_alphabet.glsl and mcv2_config.glsl.

// The symbol a map texel carries, or -1 for a colour outside the alphabet.
int mcv2Symbol(vec4 texel) {
    ivec3 c = ivec3(floor(texel.rgb * 255.0 + 0.5));
    for (int s = 0; s < 64; ++s) {
        if (MCV2_ALPHABET[s] == c) {
            return s;
        }
    }
    return -1;
}

int mcv2SymbolAt(sampler2D map, int index) {
    return mcv2Symbol(texelFetch(map, ivec2(index % 128, index / 128), 0));
}

// Bits [bit, bit + width) of a page's symbol stream, least significant first, width at most 16.
int mcv2PageBits(sampler2D map, int bit, int width) {
    int value = 0;
    for (int i = 0; i < width; ++i) {
        int b = bit + i;
        int symbol = max(mcv2SymbolAt(map, b / 6), 0);
        value |= ((symbol >> (b % 6)) & 1) << i;
    }
    return value;
}

// A map is a transport page of this pack's stream when its first seven symbols spell the fixed six-bit page prefix
// ("MCP1", version 1, six bits) and its header names the stream.
bool mcv2IsPage(sampler2D map) {
    if (mcv2SymbolAt(map, 0) != 13 || mcv2SymbolAt(map, 1) != 13 || mcv2SymbolAt(map, 2) != 4
        || mcv2SymbolAt(map, 3) != 20 || mcv2SymbolAt(map, 4) != 49 || mcv2SymbolAt(map, 5) != 4
        || mcv2SymbolAt(map, 6) != 32) {
        return false;
    }
    uint stream = uint(mcv2PageBits(map, 64, 16)) | (uint(mcv2PageBits(map, 80, 16)) << 16u);
    return stream == MCV2_STREAM_ID;
}

// An anchor map marks where the screen is: a signature, then its column and row in the screen, the screen's size
// in maps, the facing of its frame and a checksum, one symbol each in the first row.
bool mcv2IsAnchor(sampler2D map) {
    return mcv2SymbolAt(map, 0) == 21 && mcv2SymbolAt(map, 1) == 3 && mcv2SymbolAt(map, 2) == 58
        && mcv2SymbolAt(map, 3) == 44 && mcv2SymbolAt(map, 4) == 9 && mcv2SymbolAt(map, 5) == 37
        && mcv2SymbolAt(map, 6) == 60 && mcv2SymbolAt(map, 7) == 17;
}
