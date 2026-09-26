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

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import me.brandonli.mcav.bukkit.resourcepack.SimpleResourcePack;
import me.brandonli.mcav.media.mcv2.ResidualBooks;
import me.brandonli.mcav.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.media.mcv2.transport.TransportPages;
import net.minecraft.world.level.material.MapColor;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Builds the resource pack that decodes an MCV2 screen on vanilla clients.
 *
 * <p>The pack overrides the core text shaders, which draw maps, so the maps that carry pages and anchors are moved into
 * a strip at the top of the screen, and replaces the entity outline post chain with passes that read the strip,
 * check every page's CRC, decode the frame with the gpu-codec fragment decoder into a persistent picture, keep the
 * last keyframe as a second reference, and draw the picture onto the screen's wall. The pass sources are fixed; what
 * depends on the screen is generated: the video size and page slots, the stream id, the outline colour of the page
 * frames, the map colours of the transport alphabet (from this server's map palette, which is the client's), and the
 * residual books (from the same bytes the Java decoder uses).
 */
public final class Mcv2Pack {

  /** The resource pack format of Minecraft 26.2. */
  public static final int PACK_FORMAT = 88;

  /** The gpu-codec commit whose decoder the pack carries. */
  public static final String CODEC_COMMIT = "85445433aeb9f8a35a5ce528d47d8829976d1401";

  private static final String ROOT = "/mcav/mcv2/pack/";
  private static final String POST_CHAIN = "assets/minecraft/post_effect/entity_outline.json";
  private static final String INCLUDE = "assets/mcav/shaders/include/";
  private static final List<String> FILES = List.of(
    "assets/minecraft/shaders/core/text.vsh",
    "assets/minecraft/shaders/core/text.fsh",
    INCLUDE + "mcv2_codec.glsl",
    INCLUDE + "mcv2_crc.glsl",
    INCLUDE + "mcv2_strip.glsl",
    INCLUDE + "mcv2_symbols.glsl",
    "assets/mcav/shaders/post/mcv2_bytes.fsh",
    "assets/mcav/shaders/post/mcv2_crc.fsh",
    "assets/mcav/shaders/post/mcv2_pages.fsh",
    "assets/mcav/shaders/post/mcv2_status.fsh",
    "assets/mcav/shaders/post/mcv2_resolve.fsh",
    "assets/mcav/shaders/post/mcv2_decode.vsh",
    "assets/mcav/shaders/post/mcv2_decode.fsh",
    "assets/mcav/shaders/post/mcv2_keyframe.fsh",
    "assets/mcav/shaders/post/mcv2_state.fsh",
    "assets/mcav/shaders/post/mcv2_view.fsh",
    "assets/mcav/shaders/post/mcv2_screen.vsh",
    "assets/mcav/shaders/post/mcv2_screen.fsh",
    "assets/mcav/shaders/post/mcv2_outline.fsh"
  );
  private static final int BYTES_WIDTH = 128;
  /** The chunks of 192 bytes the CRC pass splits each page slot's 12,288 strip bytes into. */
  private static final int CRC_CHUNKS = 64;
  /** The facts of a frame the resolve pass keeps in the row after its cells, one texel each. */
  private static final int FRAME_FACTS = 6;
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private Mcv2Pack() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Writes the pack of a screen.
   *
   * @param configuration the screen
   * @param debugView     whether the pack also draws the decoded picture one to one below the strip, for testing
   * @param zip           where the pack is written, atomically
   * @throws UncheckedIOException if the pack cannot be written
   */
  public static void write(final Mcv2Configuration configuration, final boolean debugView, final Path zip) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    Preconditions.checkNotNull(zip, "Zip must not be null");
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.meta(PACK_FORMAT, describe(configuration));
    for (final String file : FILES) {
      pack.data(file, resource(file));
    }
    pack.data(POST_CHAIN, postChain(configuration).getBytes(StandardCharsets.UTF_8));
    pack.data(INCLUDE + "mcv2_config.glsl", config(configuration, debugView).getBytes(StandardCharsets.UTF_8));
    pack.data(INCLUDE + "mcv2_alphabet.glsl", alphabet(palette()).getBytes(StandardCharsets.UTF_8));
    pack.data(INCLUDE + "mcv2_books.glsl", books(ResidualBooks.bytes()).getBytes(StandardCharsets.UTF_8));
    pack.data("mcav_mcv2.json", manifest(configuration).getBytes(StandardCharsets.UTF_8));
    pack.zip(zip);
  }

  /** The description shown in the client's pack list. */
  static String describe(final Mcv2Configuration configuration) {
    return "mcav MCV2 decoder, %dx%d video, stream %d".formatted(
        configuration.getVideoWidth(),
        configuration.getVideoHeight(),
        configuration.getStreamId()
      );
  }

  static byte[] resource(final String file) {
    return read(Mcv2Pack.class.getResourceAsStream(ROOT + file), file);
  }

  static byte[] read(final @Nullable InputStream stream, final String file) {
    if (stream == null) {
      throw new IllegalStateException("Missing MCV2 pack resource " + file);
    }
    try (stream) {
      return stream.readAllBytes();
    } catch (final IOException exception) {
      throw new UncheckedIOException("Cannot read MCV2 pack resource " + file, exception);
    }
  }

  /** The post chain with the screen's target sizes filled in. */
  static String postChain(final Mcv2Configuration configuration) {
    final int slots = configuration.getPageSlots();
    return new String(resource(POST_CHAIN), StandardCharsets.UTF_8)
      .replace("@VIDEO_WIDTH@", Integer.toString(configuration.getVideoWidth()))
      .replace("@VIDEO_HEIGHT@", Integer.toString(configuration.getVideoHeight()))
      .replace("@BYTES_WIDTH@", Integer.toString(BYTES_WIDTH))
      .replace("@BYTES_HEIGHT@", Integer.toString(bytesHeight(configuration)))
      .replace("@PAGES_WIDTH@", Integer.toString(4 * slots))
      .replace("@CRC_WIDTH@", Integer.toString(CRC_CHUNKS * slots))
      .replace("@CELLS_WIDTH@", Integer.toString(cellsWidth(configuration)))
      .replace("@CELLS_HEIGHT@", Integer.toString(cellsHeight(configuration) + 1));
  }

  /** The rows of the bytes target: four bytes to a texel, enough for every byte of the page slots. */
  static int bytesHeight(final Mcv2Configuration configuration) {
    return ((configuration.getPageSlots() * TransportPages.capacity(MapAlphabet.SYMBOL_BITS)) / 4 + BYTES_WIDTH - 1) / BYTES_WIDTH;
  }

  /** The resolve pass's columns: one per 8 pixels of the video, and room for the frame's facts. */
  static int cellsWidth(final Mcv2Configuration configuration) {
    return Math.max((configuration.getVideoWidth() + 7) / 8, FRAME_FACTS);
  }

  /** The resolve pass's rows of cells, one per 8 pixels of the video; its frame row follows them. */
  static int cellsHeight(final Mcv2Configuration configuration) {
    return (configuration.getVideoHeight() + 7) / 8;
  }

  /** The screen's constants for the shaders. */
  static String config(final Mcv2Configuration configuration, final boolean debugView) {
    final int color = configuration.getOutlineColor().value();
    return String.join(
      "\n",
      "#version 330",
      "",
      "// Generated by mcav for one MCV2 screen.",
      "const int MCV2_PAGE_SLOTS = %d;".formatted(configuration.getPageSlots()),
      "const int MCV2_VIDEO_WIDTH = %d;".formatted(configuration.getVideoWidth()),
      "const int MCV2_VIDEO_HEIGHT = %d;".formatted(configuration.getVideoHeight()),
      "const uint MCV2_STREAM_ID = %du;".formatted(configuration.getStreamId()),
      "const int MCV2_BYTES_WIDTH = %d;".formatted(BYTES_WIDTH),
      "const int MCV2_BYTES_HEIGHT = %d;".formatted(bytesHeight(configuration)),
      "const int MCV2_CELLS_WIDTH = %d;".formatted(cellsWidth(configuration)),
      "const int MCV2_CELLS_HEIGHT = %d;".formatted(cellsHeight(configuration)),
      "const bool MCV2_DEBUG_VIEW = %s;".formatted(debugView),
      "const ivec3 MCV2_OUTLINE_COLOR = ivec3(%d, %d, %d);".formatted((color >> 16) & 255, (color >> 8) & 255, color & 255),
      ""
    );
  }

  /**
   * The RGB the client uploads for each symbol's map colour, from the map palette: symbol s is packed map colour
   * s + 4.
   *
   * @return 64 RGB values
   */
  static int[] palette() {
    final int[] colors = new int[MapAlphabet.SIZE];
    for (int symbol = 0; symbol < colors.length; symbol++) {
      colors[symbol] = MapColor.getColorFromPackedId(symbol + MapAlphabet.FIRST_COLOR) & 0xFFFFFF;
    }
    return colors;
  }

  /**
   * The alphabet table of the shaders.
   *
   * @param colors the RGB of every symbol
   * @return the include
   * @throws IllegalStateException if two symbols share a colour, so the shader could not tell them apart
   */
  static String alphabet(final int[] colors) {
    final Set<Integer> seen = new HashSet<>();
    final StringBuilder table = new StringBuilder();
    for (int symbol = 0; symbol < colors.length; symbol++) {
      final int color = colors[symbol];
      if (!seen.add(color)) {
        throw new IllegalStateException("Map colours %d and another symbol are the same RGB".formatted(symbol + MapAlphabet.FIRST_COLOR));
      }
      table.append(
        "    ivec3(%d, %d, %d)%s%n".formatted((color >> 16) & 255, (color >> 8) & 255, color & 255, symbol < colors.length - 1 ? "," : "")
      );
    }
    return (
      "#version 330\n\n// Generated by mcav from the map palette: the RGB of every transport symbol's map colour.\n" +
      "const ivec3 MCV2_ALPHABET[64] = ivec3[64](\n" +
      table +
      ");\n"
    );
  }

  /**
   * The residual books as the shader reads them: 512 little-endian words.
   *
   * @param books the 2,048 bytes of the books
   * @return the include
   */
  static String books(final byte[] books) {
    final StringBuilder table = new StringBuilder();
    for (int word = 0; word < books.length / 4; word++) {
      final int at = word * 4;
      final long value =
        (books[at] & 0xFFL) | ((books[at + 1] & 0xFFL) << 8) | ((books[at + 2] & 0xFFL) << 16) | ((books[at + 3] & 0xFFL) << 24);
      table.append(word % 8 == 0 ? "    " : " ").append("0x%08Xu".formatted(value)).append(word < books.length / 4 - 1 ? "," : "");
      if (word % 8 == 7) {
        table.append('\n');
      }
    }
    return (
      "#version 330\n\n// Generated by mcav: the MCV2 residual books, four bytes to a word.\n" +
      "const uint MCV2_BOOKS[512] = uint[512](\n" +
      table +
      ");\n"
    );
  }

  /** What the pack decodes, for whoever inspects it. */
  static String manifest(final Mcv2Configuration configuration) {
    final JsonObject manifest = new JsonObject();
    manifest.addProperty("codec", "MCV2");
    manifest.addProperty("gpu_codec_commit", CODEC_COMMIT);
    manifest.addProperty("lambda", configuration.getSettings().lambda());
    manifest.addProperty("reference", configuration.getSettings().reference().name().toLowerCase(Locale.ROOT));
    manifest.addProperty("video_width", configuration.getVideoWidth());
    manifest.addProperty("video_height", configuration.getVideoHeight());
    manifest.addProperty("page_slots", configuration.getPageSlots());
    manifest.addProperty("stream_id", configuration.getStreamId());
    return GSON.toJson(manifest);
  }
}
