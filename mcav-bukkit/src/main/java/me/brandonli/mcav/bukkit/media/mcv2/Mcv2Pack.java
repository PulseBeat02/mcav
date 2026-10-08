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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.brandonli.mcav.bukkit.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
import me.brandonli.mcav.bukkit.resourcepack.SimpleResourcePack;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.level.material.MapColor;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Builds the resource pack that decodes MCV2 screens on vanilla clients: one pack for every screen of the server, so
 * any number of screens up to {@link #MAX_SCREENS} play at once.
 *
 * <p>The pack overrides the core text shaders, which draw maps, so the maps that carry pages and anchors are moved into
 * a strip at the top of the screen: each screen owns a run of page slots there, found by the stream id its pages and
 * anchors carry, and an anchor descriptor row after the slots. It replaces the entity outline post chain with, for
 * every screen, passes that read its part of the strip, check every page's CRC, decode the v3 frame into one persistent
 * picture, and draw the picture onto the screen's wall. The pass sources are fixed; what depends on the screens is
 * generated: each screen's copy of its passes, with its video size, page slots and place in the strip, the table of the
 * screens' streams, the outline colour of the page frames, and the map colours of the transport alphabet (from this
 * server's map palette, which is the client's). All decoding logic is in one shader include; pass files only select its stage.
 *
 * <p>Writing performs synchronous resource reads and archive creation. Run it off the main thread with stable
 * configuration inputs, and keep the resulting file available for the chosen hosting strategy.
 */
public final class Mcv2Pack {

  /** The resource pack format of Minecraft 26.3. */
  public static final int PACK_FORMAT = 97;

  /** The gpu-codec revision from which the decoder originated. */
  public static final String CODEC_COMMIT = "85445433aeb9f8a35a5ce528d47d8829976d1401";

  /**
   * The most screens one pack decodes: each costs the client its decode passes on every frame the chain runs, and its
   * page slots a band of the top of the window.
   */
  public static final int MAX_SCREENS = 8;

  private static final String ROOT = "/mcav/mcv2/pack/";

  private static final String CHAIN_TEMPLATE = "/mcav/mcv2/chain.json";

  /** The rows of the debug view's status squares right of a screen's picture: a picture shorter still takes them. */
  private static final int DEBUG_SQUARES = 24;

  /** The rows the debug view leaves between the pictures of two screens. */
  private static final int DEBUG_GAP = 8;

  private static final String POST_CHAIN = "assets/minecraft/post_effect/entity_outline.json";

  private static final String INCLUDE = "assets/mcav/shaders/include/";

  private static final String POST = "assets/mcav/shaders/post/";

  /** The include a screen's passes name, which each screen's copy renames to its own. */
  private static final String SCREEN_INCLUDE = "#include <mcav:mcv2_screen.glsl>";

  private static final List<String> FILES = List.of(
    "assets/minecraft/shaders/core/text.vsh",
    "assets/minecraft/shaders/core/text.fsh",
    INCLUDE + "mcv2.glsl",
    POST + "mcv2_state.fsh",
    POST + "mcv2_copy.fsh",
    POST + "mcv2_outline.fsh"
  );

  /** The passes every screen has its own copy of, in {@code post/s<screen>/}. */
  private static final List<String> SCREEN_FILES = List.of(
    "mcv2_bytes.fsh",
    "mcv2_crc.fsh",
    "mcv2_pages.fsh",
    "mcv2_status.fsh",
    "mcv2_resolve.fsh",
    "mcv2_decode.vsh",
    "mcv2_decode.fsh",
    "mcv2_view.fsh",
    "mcv2_screen.vsh",
    "mcv2_screen.fsh"
  );

  private static final int BYTES_WIDTH = 128;

  /** The chunks of 192 bytes the CRC pass splits each page slot's 12,288 strip bytes into. */
  private static final int CRC_CHUNKS = 64;

  /** The pages target's texels per page slot. */
  private static final int PAGE_TEXELS = 4;

  /** The bytes target holds four bytes to a texel. */
  private static final int TEXEL_BYTES = 4;

  /** The resolve pass works on cells of 8x8 pixels, the smallest leaf. */
  private static final int CELL_PIXELS = Mcv2Decoder.SMALLEST_BLOCK;

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private Mcv2Pack() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Writes the pack of one screen.
   *
   * @param configuration  the screen
   * @param showsDebugView whether the pack also draws the decoded picture one to one below the strip, for testing
   * @param zip            where the pack is written, atomically
   * @throws UncheckedIOException if the pack cannot be written
   * @throws NullPointerException if the zip path is null
   */
  public static void write(final Mcv2Configuration configuration, final boolean showsDebugView, final Path zip) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    write(List.of(configuration), showsDebugView, zip);
  }

  /**
   * Writes the pack of every screen of the server.
   *
   * @param screens        the screens, in the order of their place in the strip; their stream ids must differ, and they
   *                       share the outline colour of the first
   * @param showsDebugView whether the pack also draws the first screen's decoded picture one to one below the strip, for
   *                       testing
   * @param zip            where the pack is written, atomically
   * @throws IllegalArgumentException if there are no screens or more than {@link #MAX_SCREENS}, two share a stream
   *                                  id, or their outline colours differ
   * @throws UncheckedIOException     if the pack cannot be written
   * @throws NullPointerException if {@code screens} or {@code zip} is null
   */
  public static void write(final List<Mcv2Configuration> screens, final boolean showsDebugView, final Path zip) {
    Preconditions.checkNotNull(screens, "Screens must not be null");
    Preconditions.checkNotNull(zip, "Zip must not be null");
    checkScreens(screens);
    final SimpleResourcePack pack = SimpleResourcePack.pack();
    pack.meta(PACK_FORMAT, describe(screens));
    for (final String file : FILES) {
      pack.data(file, resource(file));
    }
    int firstSlot = 0;
    int debugTop = 0;
    for (int screen = 0; screen < screens.size(); screen++) {
      final Mcv2Configuration configuration = screens.get(screen);
      for (final String file : SCREEN_FILES) {
        pack.data(POST + "s" + screen + "/" + file, screenCopy(resource(POST + file), screen).getBytes(StandardCharsets.UTF_8));
      }
      final String include = screenConfig(configuration, screen, firstSlot, debugTop);
      pack.data(INCLUDE + screenInclude(screen), include.getBytes(StandardCharsets.UTF_8));
      firstSlot += configuration.getPageSlots();
      debugTop += Math.max(configuration.getVideoHeight(), DEBUG_SQUARES) + DEBUG_GAP;
    }
    pack.data(POST_CHAIN, postChain(screens).getBytes(StandardCharsets.UTF_8));
    pack.data(INCLUDE + "mcv2_config.glsl", config(screens, showsDebugView).getBytes(StandardCharsets.UTF_8));
    pack.data(INCLUDE + "mcv2_alphabet.glsl", alphabet(palette()).getBytes(StandardCharsets.UTF_8));
    pack.data("mcav_mcv2.json", manifest(screens).getBytes(StandardCharsets.UTF_8));
    pack.zip(zip);
  }

  private static void checkScreens(final List<Mcv2Configuration> screens) {
    Preconditions.checkArgument(!screens.isEmpty() && screens.size() <= MAX_SCREENS, "A pack has 1 to %s screens", MAX_SCREENS);
    final Set<Long> streams = screens.stream().map(Mcv2Configuration::getStreamId).collect(Collectors.toSet());
    Preconditions.checkArgument(streams.size() == screens.size(), "Every screen of a pack needs its own stream id");
    final NamedTextColor outline = screens.getFirst().getOutlineColor();
    final boolean oneOutline = screens.stream().allMatch(screen -> screen.getOutlineColor().equals(outline));
    Preconditions.checkArgument(oneOutline, "The screens of a pack share one outline colour");
  }

  /** The description shown in the client's pack list. */
  static String describe(final List<Mcv2Configuration> screens) {
    final String sizes = screens
      .stream()
      .map(screen -> screen.getVideoWidth() + "x" + screen.getVideoHeight())
      .collect(Collectors.joining(", "));
    return String.format(
      Locale.getDefault(Locale.Category.FORMAT),
      "mcav MCV2 decoder, %d %s: %s",
      screens.size(),
      screens.size() == 1 ? "screen" : "screens",
      sizes
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

  /** The include of a screen's constants. */
  private static String screenInclude(final int screen) {
    return "mcv2_screen_" + screen + ".glsl";
  }

  /**
   * A screen's copy of a pass: the pass with the screen's constants in place of the include every pass names.
   *
   * @throws IllegalStateException if the pass names no screen include
   */
  static String screenCopy(final byte[] source, final int screen) {
    final String text = new String(source, StandardCharsets.UTF_8);
    Preconditions.checkState(text.contains(SCREEN_INCLUDE), "A screen's pass must include mcv2_screen.glsl");
    return text.replace(SCREEN_INCLUDE, "#include <mcav:" + screenInclude(screen) + ">");
  }

  /** The post chain: every screen's decoding passes, then every screen's drawing passes, then the outline's. */
  private static String postChain(final List<Mcv2Configuration> screens) {
    final String template = new String(read(Mcv2Pack.class.getResourceAsStream(CHAIN_TEMPLATE), CHAIN_TEMPLATE), StandardCharsets.UTF_8);
    final JsonObject targets = new JsonObject();
    final JsonArray decoding = new JsonArray();
    final JsonArray drawing = new JsonArray();
    JsonObject shared = new JsonObject();
    for (int screen = 0; screen < screens.size(); screen++) {
      final JsonObject chain = JsonParser.parseString(fill(template, screens.get(screen), screen)).getAsJsonObject();
      shared = chain;
      for (final Map.Entry<String, JsonElement> target : chain.getAsJsonObject("screen_targets").entrySet()) {
        targets.add(target.getKey(), target.getValue());
      }
      decoding.addAll(chain.getAsJsonArray("decode"));
      drawing.addAll(chain.getAsJsonArray("draw"));
    }
    for (final Map.Entry<String, JsonElement> target : shared.getAsJsonObject("targets").entrySet()) {
      targets.add(target.getKey(), target.getValue());
    }
    final JsonArray passes = new JsonArray();
    passes.addAll(decoding);
    passes.addAll(drawing);
    passes.addAll(shared.getAsJsonArray("tail"));
    final JsonObject chain = new JsonObject();
    chain.add("targets", targets);
    chain.add("passes", passes);
    return GSON.toJson(chain);
  }

  /** The chain template with one screen's index and target sizes filled in. */
  private static String fill(final String template, final Mcv2Configuration configuration, final int screen) {
    final int slots = configuration.getPageSlots();
    return template
      .replace("@S@", Integer.toString(screen))
      .replace("@VIDEO_WIDTH@", Integer.toString(configuration.getVideoWidth()))
      .replace("@VIDEO_HEIGHT@", Integer.toString(configuration.getVideoHeight()))
      .replace("@BYTES_WIDTH@", Integer.toString(BYTES_WIDTH))
      .replace("@BYTES_HEIGHT@", Integer.toString(bytesHeight(configuration)))
      .replace("@PAGES_WIDTH@", Integer.toString(PAGE_TEXELS * slots))
      .replace("@CRC_WIDTH@", Integer.toString(CRC_CHUNKS * slots))
      .replace("@CELLS_WIDTH@", Integer.toString(cellsWidth(configuration)))
      .replace("@CELLS_HEIGHT@", Integer.toString(cellsHeight(configuration) + 1));
  }

  /** The rows of the bytes target: four bytes to a texel, enough for every byte of the page slots. */
  private static int bytesHeight(final Mcv2Configuration configuration) {
    final int bytes = configuration.getPageSlots() * TransportPages.capacity();
    return (bytes / TEXEL_BYTES + BYTES_WIDTH - 1) / BYTES_WIDTH;
  }

  /** The resolve pass's columns: one per 8 pixels of the video; the frame row's one texel fits any width. */
  static int cellsWidth(final Mcv2Configuration configuration) {
    return (configuration.getVideoWidth() + CELL_PIXELS - 1) / CELL_PIXELS;
  }

  /** The resolve pass's rows of cells, one per 8 pixels of the video; its frame row follows them. */
  static int cellsHeight(final Mcv2Configuration configuration) {
    return (configuration.getVideoHeight() + CELL_PIXELS - 1) / CELL_PIXELS;
  }

  /** The constants every shader of the pack shares: the screens' streams and places in the strip. */
  static String config(final List<Mcv2Configuration> screens, final boolean showsDebugView) {
    final int count = screens.size();
    final int color = screens.getFirst().getOutlineColor().value();
    final StringBuilder streams = new StringBuilder();
    final StringBuilder slots = new StringBuilder();
    final StringBuilder firsts = new StringBuilder();
    int total = 0;
    for (int screen = 0; screen < count; screen++) {
      final Mcv2Configuration configuration = screens.get(screen);
      final String separator = screen == 0 ? "" : ", ";
      streams.append(separator).append(configuration.getStreamId()).append('u');
      slots.append(separator).append(configuration.getPageSlots());
      firsts.append(separator).append(total);
      total += configuration.getPageSlots();
    }
    return guarded(
      "MCAV_MCV2_CONFIG_GLSL",
      String.join(
        "\n",
        "// Generated by mcav for the MCV2 screens of one pack.",
        String.format(Locale.ROOT, "const int MCV2_SCREENS = %d;", count),
        String.format(Locale.ROOT, "const int MCV2_TOTAL_SLOTS = %d;", total),
        String.format(Locale.ROOT, "const uint MCV2_SCREEN_STREAMS[%d] = uint[%d](%s);", count, count, streams),
        String.format(Locale.ROOT, "const int MCV2_SCREEN_SLOTS[%d] = int[%d](%s);", count, count, slots),
        String.format(Locale.ROOT, "const int MCV2_SCREEN_FIRST_SLOTS[%d] = int[%d](%s);", count, count, firsts),
        String.format(Locale.ROOT, "const bool MCV2_DEBUG_VIEW = %s;", showsDebugView),
        String.format(
          Locale.ROOT,
          "const ivec3 MCV2_OUTLINE_COLOR = ivec3(%d, %d, %d);",
          (color >> 16) & 255,
          (color >> 8) & 255,
          color & 255
        ),
        ""
      )
    );
  }

  /**
   * One screen's constants, which its copy of the passes includes.
   *
   * @param debugTop the row below the strip where the debug view draws this screen's picture, under the pictures of
   *                 the screens before it
   */
  private static String screenConfig(final Mcv2Configuration configuration, final int screen, final int firstSlot, final int debugTop) {
    return guarded(
      "MCAV_MCV2_SCREEN_GLSL",
      String.join(
        "\n",
        String.format(Locale.ROOT, "// Generated by mcav for screen %d of the pack.", screen),
        String.format(Locale.ROOT, "const int MCV2_SCREEN_INDEX = %d;", screen),
        String.format(Locale.ROOT, "const int MCV2_PAGE_SLOTS = %d;", configuration.getPageSlots()),
        String.format(Locale.ROOT, "const int MCV2_FIRST_SLOT = %d;", firstSlot),
        String.format(Locale.ROOT, "const uint MCV2_STREAM_ID = %du;", configuration.getStreamId()),
        String.format(Locale.ROOT, "const int MCV2_VIDEO_WIDTH = %d;", configuration.getVideoWidth()),
        String.format(Locale.ROOT, "const int MCV2_VIDEO_HEIGHT = %d;", configuration.getVideoHeight()),
        String.format(Locale.ROOT, "const int MCV2_BYTES_WIDTH = %d;", BYTES_WIDTH),
        String.format(Locale.ROOT, "const int MCV2_BYTES_HEIGHT = %d;", bytesHeight(configuration)),
        String.format(Locale.ROOT, "const int MCV2_CELLS_WIDTH = %d;", cellsWidth(configuration)),
        String.format(Locale.ROOT, "const int MCV2_CELLS_HEIGHT = %d;", cellsHeight(configuration)),
        String.format(Locale.ROOT, "const int MCV2_DEBUG_TOP = %d;", debugTop),
        ""
      )
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
        throw new IllegalStateException(
          String.format(
            Locale.getDefault(Locale.Category.FORMAT),
            "Map colours %d and another symbol are the same RGB",
            symbol + MapAlphabet.FIRST_COLOR
          )
        );
      }
      table.append(
        String.format(
          Locale.ROOT,
          "    ivec3(%d, %d, %d)%s%n",
          (color >> 16) & 255,
          (color >> 8) & 255,
          color & 255,
          symbol < colors.length - 1 ? "," : ""
        )
      );
    }
    return guarded(
      "MCAV_MCV2_ALPHABET_GLSL",
      "// Generated by mcav from the map palette: the RGB of every transport symbol's map colour.\n" +
        "const ivec3 MCV2_ALPHABET[64] = ivec3[64](\n" +
        table +
        ");\n"
    );
  }

  /**
   * Wraps a generated include in its guard: Minecraft compiles the pack's shaders with shaderc, which inserts an
   * include each time a shader or another include names it.
   */
  private static String guarded(final String guard, final String body) {
    return "#ifndef %s\n#define %s\n\n%s\n#endif\n".formatted(guard, guard, body);
  }

  /**
   * What the pack decodes, for whoever inspects it: only what the pack depends on, so screens encoded with other
   * profiles share the same pack.
   */
  private static String manifest(final List<Mcv2Configuration> screens) {
    final JsonObject manifest = new JsonObject();
    manifest.addProperty("codec", "MCV2");
    manifest.addProperty("version", 3);
    manifest.addProperty("gpu_codec_commit", CODEC_COMMIT);
    final JsonArray list = new JsonArray();
    for (final Mcv2Configuration configuration : screens) {
      final JsonObject screen = new JsonObject();
      screen.addProperty("stream_id", configuration.getStreamId());
      screen.addProperty("video_width", configuration.getVideoWidth());
      screen.addProperty("video_height", configuration.getVideoHeight());
      screen.addProperty("page_slots", configuration.getPageSlots());
      list.add(screen);
    }
    manifest.add("screens", list);
    return GSON.toJson(manifest);
  }
}
