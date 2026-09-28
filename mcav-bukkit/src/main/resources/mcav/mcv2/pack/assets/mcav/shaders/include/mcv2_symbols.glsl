#ifndef MCAV_MCV2_SYMBOLS_GLSL
#define MCAV_MCV2_SYMBOLS_GLSL

// Reading MCV2 symbols out of map textures. Symbol s is sent as map colour s + 4, and the client uploads each map
// colour as one exact RGB texel, listed in MCV2_ALPHABET (generated from the server's map palette, which is the
// client's). Requires mcv2_alphabet.glsl and mcv2_config.glsl.

// The symbol a map texel carries, or -1 for a colour outside the alphabet.
int mcv2Symbol(vec4 texel) {
    ivec3 c = ivec3(texel.rgb * 255.0 + 0.5);
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

// The screen of the pack whose stream id this is, or -1.
int mcv2ScreenOf(uint stream) {
    for (int screen = 0; screen < MCV2_SCREENS; ++screen) {
        if (MCV2_SCREEN_STREAMS[screen] == stream) {
            return screen;
        }
    }
    return -1;
}

// The screen whose transport page a map is, or -1: a page's first seven symbols spell the fixed six-bit page prefix
// ("MCP1", version 1, six bits), and its header names the stream, which names the screen.
int mcv2PageScreen(sampler2D map) {
    if (mcv2SymbolAt(map, 0) != 13 || mcv2SymbolAt(map, 1) != 13 || mcv2SymbolAt(map, 2) != 4
        || mcv2SymbolAt(map, 3) != 20 || mcv2SymbolAt(map, 4) != 49 || mcv2SymbolAt(map, 5) != 4
        || mcv2SymbolAt(map, 6) != 32) {
        return -1;
    }
    uint stream = uint(mcv2PageBits(map, 64, 16)) | (uint(mcv2PageBits(map, 80, 16)) << 16u);
    return mcv2ScreenOf(stream);
}

// An anchor map marks where a screen is: a signature, then its column and row in the screen, the screen's size in
// maps, the facing of its frame, the screen's stream id in two symbols, low first, and a checksum, one symbol each in
// the first row.
bool mcv2IsAnchor(sampler2D map) {
    return mcv2SymbolAt(map, 0) == 21 && mcv2SymbolAt(map, 1) == 3 && mcv2SymbolAt(map, 2) == 58
        && mcv2SymbolAt(map, 3) == 44 && mcv2SymbolAt(map, 4) == 9 && mcv2SymbolAt(map, 5) == 37
        && mcv2SymbolAt(map, 6) == 60 && mcv2SymbolAt(map, 7) == 17;
}

#endif
