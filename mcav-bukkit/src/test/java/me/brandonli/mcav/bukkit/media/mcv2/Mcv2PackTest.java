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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import me.brandonli.mcav.media.mcv2.ResidualBooks;
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
    try (InputStream input = Files.newInputStream(zip); ZipInputStream stream = new ZipInputStream(input)) {
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
      "assets/mcav/shaders/include/mcv2_codec.glsl",
      "assets/mcav/shaders/include/mcv2_config.glsl",
      "assets/mcav/shaders/include/mcv2_alphabet.glsl",
      "assets/mcav/shaders/include/mcv2_books.glsl",
      "assets/mcav/shaders/post/mcv2_crc.fsh",
      "assets/mcav/shaders/post/mcv2_resolve.fsh",
      "assets/mcav/shaders/post/mcv2_decode.vsh",
      "assets/mcav/shaders/post/mcv2_decode.fsh",
      "assets/mcav/shaders/post/mcv2_view.fsh",
      "assets/mcav/shaders/post/mcv2_screen.vsh",
      "assets/mcav/shaders/post/mcv2_screen.fsh",
    }) {
      assertTrue(entries.containsKey(name), name);
    }
    assertEquals(25, entries.size());
    final JsonObject meta = JsonParser.parseString(entries.get("pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack");
    assertEquals(Mcv2Pack.PACK_FORMAT, meta.get("pack_format").getAsInt());
    assertEquals("mcav MCV2 decoder, 320x180 video, stream 9", meta.get("description").getAsString());
    final String config = entries.get("assets/mcav/shaders/include/mcv2_config.glsl");
    assertTrue(config.contains("const int MCV2_PAGE_SLOTS = 2;"), config);
    assertTrue(config.contains("const int MCV2_VIDEO_WIDTH = 320;"), config);
    assertTrue(config.contains("const int MCV2_VIDEO_HEIGHT = 180;"), config);
    assertTrue(config.contains("const uint MCV2_STREAM_ID = 9u;"), config);
    assertTrue(config.contains("const bool MCV2_DEBUG_VIEW = true;"), config);
    assertTrue(config.contains("const ivec3 MCV2_OUTLINE_COLOR = ivec3(255, 170, 0);"), config);
    assertTrue(config.contains("const int MCV2_BYTES_HEIGHT = 48;"), config);
    // one cell per 8x8 pixels: 40 columns, 23 rows of cells (180 / 8 rounded up)
    assertTrue(config.contains("const int MCV2_CELLS_WIDTH = 40;"), config);
    assertTrue(config.contains("const int MCV2_CELLS_HEIGHT = 23;"), config);
    final String chain = entries.get("assets/minecraft/post_effect/entity_outline.json");
    assertFalse(chain.contains("@"), "every token is filled in");
    final JsonObject targets = JsonParser.parseString(chain).getAsJsonObject().getAsJsonObject("targets");
    assertEquals(320, targets.getAsJsonObject("mcav:mcv2_previous").get("width").getAsInt());
    assertEquals(180, targets.getAsJsonObject("mcav:mcv2_key").get("height").getAsInt());
    assertEquals(8, targets.getAsJsonObject("mcav:mcv2_pages").get("width").getAsInt());
    // two pages of 12,256 bytes, four to a texel, 128 texels to a row
    assertEquals(48, targets.getAsJsonObject("mcav:mcv2_bytes").get("height").getAsInt());
    // 64 CRC chunks per page slot
    assertEquals(128, targets.getAsJsonObject("mcav:mcv2_crc").get("width").getAsInt());
    // the cells and, after them, the frame row
    assertEquals(40, targets.getAsJsonObject("mcav:mcv2_cells").get("width").getAsInt());
    assertEquals(24, targets.getAsJsonObject("mcav:mcv2_cells").get("height").getAsInt());
    final JsonObject manifest = JsonParser.parseString(entries.get("mcav_mcv2.json")).getAsJsonObject();
    assertEquals(Mcv2Pack.CODEC_COMMIT, manifest.get("gpu_codec_commit").getAsString());
    assertEquals("previous_frame", manifest.get("reference").getAsString());
    assertEquals(2, manifest.get("page_slots").getAsInt());
  }

  @Test
  void leavesRoomForTheFrameFactsInANarrowVideo() {
    final Mcv2Configuration narrow = Mcv2ConfigurationTest.complete().video(16, 9).build();
    // two columns of cells would not hold the frame row's six facts
    assertEquals(6, Mcv2Pack.cellsWidth(narrow));
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
    assertThrows(IllegalStateException.class, () -> Mcv2Pack.alphabet(clash));
  }

  @Test
  void writesTheBooksAsLittleEndianWords() {
    final byte[] books = ResidualBooks.bytes();
    final Matcher matcher = Pattern.compile("0x([0-9A-F]{8})u").matcher(Mcv2Pack.books(books));
    final byte[] parsed = new byte[books.length];
    for (int word = 0; word < 512; word++) {
      assertTrue(matcher.find());
      final long value = Long.parseLong(matcher.group(1), 16);
      for (int i = 0; i < 4; i++) {
        parsed[word * 4 + i] = (byte) (value >> (8 * i));
      }
    }
    assertFalse(matcher.find());
    assertArrayEquals(books, parsed);
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
