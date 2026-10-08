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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.level.material.MapColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Mcv2PackTest {

  @TempDir
  private Path directory;

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2Pack.class);
  }

  private static Map<String, String> read(final Path zip) throws IOException {
    final Map<String, String> entries = new HashMap<>();
    try (final InputStream input = Files.newInputStream(zip); final ZipInputStream stream = new ZipInputStream(input)) {
      for (ZipEntry entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
        entries.put(entry.getName(), new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }

  @Test
  void writesThePackOfAScreen() throws IOException {
    final Mcv2Configuration configuration = Mcv2ConfigurationTest.complete()
      .video(320, 180)
      .pageSlots(2)
      .streamId(9)
      .outlineColor(NamedTextColor.GOLD)
      .build();
    final Path zip = this.directory.resolve("pack.zip");
    Mcv2Pack.write(configuration, true, zip);
    final Map<String, String> entries = read(zip);
    for (final String name : new String[] {
      "pack.mcmeta",
      "mcav_mcv2.json",
      "assets/minecraft/post_effect/entity_outline.json",
      "assets/minecraft/shaders/core/text.vsh",
      "assets/minecraft/shaders/core/text.fsh",
      "assets/mcav/shaders/include/mcv2.glsl",
      "assets/mcav/shaders/include/mcv2_config.glsl",
      "assets/mcav/shaders/include/mcv2_screen_0.glsl",
      "assets/mcav/shaders/include/mcv2_alphabet.glsl",
      "assets/mcav/shaders/post/s0/mcv2_crc.fsh",
      "assets/mcav/shaders/post/s0/mcv2_resolve.fsh",
      "assets/mcav/shaders/post/s0/mcv2_decode.vsh",
      "assets/mcav/shaders/post/s0/mcv2_decode.fsh",
      "assets/mcav/shaders/post/s0/mcv2_view.fsh",
      "assets/mcav/shaders/post/s0/mcv2_screen.vsh",
      "assets/mcav/shaders/post/s0/mcv2_screen.fsh",
      "assets/mcav/shaders/post/mcv2_state.fsh",
      "assets/mcav/shaders/post/mcv2_copy.fsh",
    }) {
      assertTrue(entries.containsKey(name), name);
    }
    // six shared files, the screen's ten passes and its include, the chain, the shared includes, the manifest, the
    // metadata
    assertEquals(22, entries.size());
    final JsonObject meta = JsonParser.parseString(entries.get("pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack");
    assertEquals(Mcv2Pack.PACK_FORMAT, meta.get("pack_format").getAsInt());
    assertEquals("mcav MCV2 decoder, 1 screen: 320x180", meta.get("description").getAsString());
    final String config = entries.get("assets/mcav/shaders/include/mcv2_config.glsl");
    assertTrue(config.contains("const int MCV2_SCREENS = 1;"), config);
    assertTrue(config.contains("const int MCV2_TOTAL_SLOTS = 2;"), config);
    assertTrue(config.contains("const uint MCV2_SCREEN_STREAMS[1] = uint[1](9u);"), config);
    assertTrue(config.contains("const int MCV2_SCREEN_SLOTS[1] = int[1](2);"), config);
    assertTrue(config.contains("const int MCV2_SCREEN_FIRST_SLOTS[1] = int[1](0);"), config);
    assertTrue(config.contains("const bool MCV2_DEBUG_VIEW = true;"), config);
    assertTrue(config.contains("const ivec3 MCV2_OUTLINE_COLOR = ivec3(255, 170, 0);"), config);
    final String screen = entries.get("assets/mcav/shaders/include/mcv2_screen_0.glsl");
    assertTrue(screen.contains("const int MCV2_SCREEN_INDEX = 0;"), screen);
    assertTrue(screen.contains("const int MCV2_PAGE_SLOTS = 2;"), screen);
    assertTrue(screen.contains("const int MCV2_FIRST_SLOT = 0;"), screen);
    assertTrue(screen.contains("const uint MCV2_STREAM_ID = 9u;"), screen);
    assertTrue(screen.contains("const int MCV2_VIDEO_WIDTH = 320;"), screen);
    assertTrue(screen.contains("const int MCV2_VIDEO_HEIGHT = 180;"), screen);
    assertTrue(screen.contains("const int MCV2_DEBUG_TOP = 0;"), screen);
    assertTrue(screen.contains("const int MCV2_BYTES_HEIGHT = 48;"), screen);
    // one cell per 8x8 pixels: 40 columns, 23 rows of cells (180 / 8 rounded up)
    assertTrue(screen.contains("const int MCV2_CELLS_WIDTH = 40;"), screen);
    assertTrue(screen.contains("const int MCV2_CELLS_HEIGHT = 23;"), screen);
    final String pass = entries.get("assets/mcav/shaders/post/s0/mcv2_crc.fsh");
    assertTrue(pass.contains("#include <mcav:mcv2_screen_0.glsl>"), pass);
    assertFalse(pass.contains("mcv2_screen.glsl"), pass);
    final String chain = entries.get("assets/minecraft/post_effect/entity_outline.json");
    assertFalse(chain.contains("@"), "every token is filled in");
    final JsonObject parsed = JsonParser.parseString(chain).getAsJsonObject();
    final JsonObject targets = parsed.getAsJsonObject("targets");
    assertEquals(320, targets.getAsJsonObject("mcav:mcv2_previous_0").get("width").getAsInt());
    assertEquals(180, targets.getAsJsonObject("mcav:mcv2_previous_0").get("height").getAsInt());
    assertTrue(targets.getAsJsonObject("mcav:mcv2_previous_0").get("persistent").getAsBoolean());
    assertFalse(targets.has("mcav:mcv2_key_0"));
    assertFalse(targets.has("mcav:mcv2_key_next_0"));
    assertFalse(entries.containsKey("assets/mcav/shaders/include/mcv2_books.glsl"));
    assertFalse(entries.containsKey("assets/mcav/shaders/post/mcv2_keyframe.fsh"));
    assertEquals(8, targets.getAsJsonObject("mcav:mcv2_pages_0").get("width").getAsInt());
    // two pages of 12,256 bytes, four to a texel, 128 texels to a row
    assertEquals(48, targets.getAsJsonObject("mcav:mcv2_bytes_0").get("height").getAsInt());
    // 64 CRC chunks per page slot
    assertEquals(128, targets.getAsJsonObject("mcav:mcv2_crc_0").get("width").getAsInt());
    // the cells and, after them, the frame row
    assertEquals(40, targets.getAsJsonObject("mcav:mcv2_cells_0").get("width").getAsInt());
    assertEquals(24, targets.getAsJsonObject("mcav:mcv2_cells_0").get("height").getAsInt());
    assertTrue(targets.has("mcav:mcv2_screen") && targets.has("swap"));
    // ten passes decode, two draw, six are the outline's
    final JsonArray passes = parsed.getAsJsonArray("passes");
    assertEquals(18, passes.size());
    assertEquals("mcav:post/s0/mcv2_bytes", passes.get(0).getAsJsonObject().get("fragment_shader").getAsString());
    assertEquals("mcav:post/s0/mcv2_screen", passes.get(10).getAsJsonObject().get("fragment_shader").getAsString());
    assertEquals("mcav:post/mcv2_outline", passes.get(12).getAsJsonObject().get("fragment_shader").getAsString());
    for (final String name : entries.keySet()) {
      if (name.startsWith("assets/mcav/shaders/post/")) {
        final String stub = entries.get(name);
        assertTrue(stub.startsWith("#version 330\n#extension GL_ARB_separate_shader_objects : require\n#define MCV2_PASS_"), name);
        assertTrue(stub.endsWith("#include <mcav:mcv2.glsl>\n"), name);
        assertEquals(name.contains("/s0/") ? 6L : 5L, stub.lines().count(), name);
        assertFalse(stub.contains("void "), name);
      }
    }
    final JsonObject manifest = JsonParser.parseString(entries.get("mcav_mcv2.json")).getAsJsonObject();
    assertEquals("MCV2", manifest.get("codec").getAsString());
    assertEquals(Mcv2Pack.CODEC_COMMIT, manifest.get("gpu_codec_commit").getAsString());
    assertEquals(3, manifest.get("version").getAsInt());
    assertEquals(4, manifest.size());
    final JsonObject described = manifest.getAsJsonArray("screens").get(0).getAsJsonObject();
    assertEquals(4, described.size(), "the encoder's profile is not the pack's business");
    assertEquals(320, described.get("video_width").getAsInt());
    assertEquals(180, described.get("video_height").getAsInt());
    assertEquals(2, described.get("page_slots").getAsInt());
    assertEquals(9, described.get("stream_id").getAsLong());
    // every channel of the outline colour, the red one too
    final Mcv2Configuration purple = Mcv2ConfigurationTest.complete().outlineColor(NamedTextColor.DARK_PURPLE).build();
    assertTrue(Mcv2Pack.config(List.of(purple), false).contains("const ivec3 MCV2_OUTLINE_COLOR = ivec3(170, 0, 170);"));
  }

  @Test
  void writesOnePackForSeveralScreens() throws IOException {
    final Mcv2Configuration small = Mcv2ConfigurationTest.complete().video(320, 180).pageSlots(2).streamId(9).build();
    final Mcv2Configuration large = Mcv2ConfigurationTest.complete().video(640, 360).pageSlots(8).streamId(3).build();
    final Path zip = this.directory.resolve("screens.zip");
    Mcv2Pack.write(List.of(small, large), false, zip);
    final Map<String, String> entries = read(zip);
    assertEquals(33, entries.size());
    final String config = entries.get("assets/mcav/shaders/include/mcv2_config.glsl");
    assertTrue(config.contains("const int MCV2_SCREENS = 2;"), config);
    assertTrue(config.contains("const int MCV2_TOTAL_SLOTS = 10;"), config);
    assertTrue(config.contains("const uint MCV2_SCREEN_STREAMS[2] = uint[2](9u, 3u);"), config);
    assertTrue(config.contains("const int MCV2_SCREEN_SLOTS[2] = int[2](2, 8);"), config);
    assertTrue(config.contains("const int MCV2_SCREEN_FIRST_SLOTS[2] = int[2](0, 2);"), config);
    final String second = entries.get("assets/mcav/shaders/include/mcv2_screen_1.glsl");
    assertTrue(second.contains("const int MCV2_SCREEN_INDEX = 1;"), second);
    assertTrue(second.contains("const int MCV2_FIRST_SLOT = 2;"), second);
    assertTrue(second.contains("const int MCV2_VIDEO_WIDTH = 640;"), second);
    // the debug view draws the second screen's picture under the first one's
    assertTrue(second.contains("const int MCV2_DEBUG_TOP = 188;"), second);
    assertTrue(entries.get("assets/mcav/shaders/post/s1/mcv2_decode.fsh").contains("#include <mcav:mcv2_screen_1.glsl>"));
    final JsonObject chain = JsonParser.parseString(entries.get("assets/minecraft/post_effect/entity_outline.json")).getAsJsonObject();
    assertEquals(640, chain.getAsJsonObject("targets").getAsJsonObject("mcav:mcv2_previous_1").get("width").getAsInt());
    // each screen decodes before any draws, so no strip is covered before its pages were read
    final JsonArray passes = chain.getAsJsonArray("passes");
    assertEquals(30, passes.size());
    assertEquals("mcav:post/s1/mcv2_bytes", passes.get(10).getAsJsonObject().get("fragment_shader").getAsString());
    assertEquals("mcav:post/s0/mcv2_screen", passes.get(20).getAsJsonObject().get("fragment_shader").getAsString());
    assertEquals("mcav:post/s1/mcv2_screen", passes.get(22).getAsJsonObject().get("fragment_shader").getAsString());
    assertEquals("mcav MCV2 decoder, 2 screens: 320x180, 640x360", describe(entries));
  }

  private static String describe(final Map<String, String> entries) {
    return JsonParser.parseString(entries.get("pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack").get("description").getAsString();
  }

  @Test
  void refusesScreensThatCannotShareAPack() {
    final Path zip = this.directory.resolve("refused.zip");
    final Mcv2Configuration first = Mcv2ConfigurationTest.complete().streamId(1).build();
    final Mcv2Configuration sameStream = Mcv2ConfigurationTest.complete().streamId(1).build();
    final Mcv2Configuration otherColour = Mcv2ConfigurationTest.complete().streamId(2).outlineColor(NamedTextColor.GOLD).build();
    final List<Mcv2Configuration> tooMany = new ArrayList<>();
    for (int stream = 0; stream <= Mcv2Pack.MAX_SCREENS; stream++) {
      tooMany.add(Mcv2ConfigurationTest.complete().streamId(stream).build());
    }
    assertThrows(IllegalArgumentException.class, () -> Mcv2Pack.write(List.of(), false, zip));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Pack.write(tooMany, false, zip));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Pack.write(List.of(first, sameStream), false, zip));
    assertThrows(IllegalArgumentException.class, () -> Mcv2Pack.write(List.of(first, otherColour), false, zip));
    assertFalse(Files.exists(zip));
  }

  @Test
  void refusesAScreenPassWithoutTheScreenInclude() {
    final byte[] pass = "void main() {}".getBytes(StandardCharsets.UTF_8);
    assertThrows(IllegalStateException.class, () -> Mcv2Pack.screenCopy(pass, 0));
  }

  @Test
  void leavesRoomForTheFrameFactsInANarrowVideo() {
    final Mcv2Configuration narrow = Mcv2ConfigurationTest.complete().video(16, 9).build();
    // two columns of cells would not hold the frame row's three facts
    assertEquals(3, Mcv2Pack.cellsWidth(narrow));
    assertEquals(2, Mcv2Pack.cellsHeight(narrow));
    final Mcv2Configuration wide = Mcv2ConfigurationTest.complete().video(1920, 1080).build();
    assertEquals(240, Mcv2Pack.cellsWidth(wide));
    assertEquals(135, Mcv2Pack.cellsHeight(wide));
  }

  @Test
  void generatesTheAlphabetFromTheMapPalette() {
    final int[] palette = Mcv2Pack.palette();
    assertEquals(64, palette.length);
    for (int symbol = 0; symbol < 64; symbol++) {
      assertEquals(MapColor.getColorFromPackedId(symbol + 4) & 0xFFFFFF, palette[symbol]);
    }
    final Matcher matcher = Pattern.compile("ivec3\\((\\d+), (\\d+), (\\d+)\\)").matcher(Mcv2Pack.alphabet(palette));
    for (int symbol = 0; symbol < 64; symbol++) {
      assertTrue(matcher.find());
      final int rgb =
        (Integer.parseInt(matcher.group(1)) << 16) | (Integer.parseInt(matcher.group(2)) << 8) | Integer.parseInt(matcher.group(3));
      assertEquals(palette[symbol], rgb);
    }
    assertFalse(matcher.find());
    final int[] clash = palette.clone();
    clash[5] = clash[4];
    assertEquals(
      "Map colours 9 and another symbol are the same RGB",
      assertThrows(IllegalStateException.class, () -> Mcv2Pack.alphabet(clash)).getMessage()
    );
    // the whole include of a short table: a comma after every entry but the last
    final String line = System.lineSeparator();
    assertEquals(
      "#ifndef MCAV_MCV2_ALPHABET_GLSL\n#define MCAV_MCV2_ALPHABET_GLSL\n\n" +
        "// Generated by mcav from the map palette: the RGB of every transport symbol's map colour.\n" +
        "const ivec3 MCV2_ALPHABET[64] = ivec3[64](\n" +
        "    ivec3(1, 2, 3)," +
        line +
        "    ivec3(4, 5, 6)" +
        line +
        ");\n\n#endif\n",
      Mcv2Pack.alphabet(new int[] { 0x010203, 0x040506 })
    );
  }

  @Test
  void reportsAMissingOrUnreadableResource() {
    assertEquals(
      "Missing MCV2 pack resource none",
      assertThrows(IllegalStateException.class, () -> Mcv2Pack.read(null, "none")).getMessage()
    );
    final InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken");
      }

      @Override
      public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        throw new IOException("broken");
      }
    };
    assertThrows(UncheckedIOException.class, () -> Mcv2Pack.read(broken, "broken"));
    assertArrayEquals(new byte[] { 1 }, Mcv2Pack.read(new ByteArrayInputStream(new byte[] { 1 }), "one"));
  }
}
