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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.brandonli.mcav.bukkit.BukkitException;
import me.brandonli.mcav.bukkit.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
import me.brandonli.mcav.bukkit.resourcepack.SimpleResourcePack;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.world.level.material.MapColor;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Builds the resource pack that decodes MCV2 screens on vanilla clients: one pack for every screen of the server, so
 * any number of screens up to {@link #MAXIMUM_SCREENS} play at once.
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
 *
 * @since 1.0.0
 */
public final class Mcv2Pack {

  /**
   * The resource pack format of Minecraft 26.3.
   *
   * @since 1.0.0
   */
  public static final int PACK_FORMAT = 97;

  /**
   * The gpu-codec commit recorded in the pack manifest.
   *
   * @since 1.0.0
   */
  public static final String CODEC_COMMIT = "85445433aeb9f8a35a5ce528d47d8829976d1401";

  /**
   * The most screens one pack decodes: each costs the client its decode passes on every frame the chain runs, and its
   * page slots a band of the top of the window.
   *
   * @since 1.0.0
   */
  public static final int MAXIMUM_SCREENS = 8;

  private static final String ROOT = "/mcav/mcv2/pack/";

  private static final String CHAIN_TEMPLATE = "/mcav/mcv2/chain.json";

  private static final int DEBUG_SQUARES = 24;

  private static final int DEBUG_GAP = 8;

  private static final String POST_CHAIN = "assets/minecraft/post_effect/entity_outline.json";

  private static final String INCLUDE = "assets/mcav/shaders/include/";

  private static final String POST = "assets/mcav/shaders/post/";

  private static final String SCREEN_INCLUDE = "#include <mcav:mcv2_screen.glsl>";

  private static final String INCLUDE_DIRECTIVE_PREFIX = "#include <mcav:";
  private static final String INCLUDE_DIRECTIVE_SUFFIX = ">";

  private static final String SCREEN_INCLUDE_PREFIX = "mcv2_screen_";
  private static final String GLSL_EXTENSION = ".glsl";

  private static final String CONFIG_INCLUDE = "mcv2_config.glsl";

  private static final String ALPHABET_INCLUDE = "mcv2_alphabet.glsl";

  private static final String MANIFEST_FILE = "mcav_mcv2.json";

  private static final String SCREEN_DIRECTORY_PREFIX = "s";

  private static final String PATH_SEPARATOR = "/";

  private static final String CONFIG_GUARD = "MCAV_MCV2_CONFIG_GLSL";

  private static final String SCREEN_GUARD = "MCAV_MCV2_SCREEN_GLSL";

  private static final String ALPHABET_GUARD = "MCAV_MCV2_ALPHABET_GLSL";

  private static final String TARGETS_KEY = "targets";

  private static final String CODEC = "MCV2";

  private static final List<String> FILES = List.of(
    "assets/minecraft/shaders/core/text.vsh",
    "assets/minecraft/shaders/core/text.fsh",
    INCLUDE + "mcv2.glsl",
    POST + "mcv2_state.fsh",
    POST + "mcv2_copy.fsh",
    POST + "mcv2_outline.fsh"
  );

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

  private static final int CRC_CHUNKS = 64;

  private static final int PAGE_TEXELS = 4;

  private static final int TEXEL_BYTES = 4;

  private static final int CELL_PIXELS = Mcv2Decoder.SMALLEST_BLOCK;

  // Four times the alphabet, so a multiplier that gives its colours distinct entries is quick to find.
  private static final int SYMBOL_ENTRIES = 256;

  private static final int SYMBOL_SHIFT = Integer.SIZE - Integer.numberOfTrailingZeros(SYMBOL_ENTRIES);

  // A colour shifted past its symbol bits never matches it.
  private static final int NO_SYMBOL = -1;

  // The map palette needs a multiplier of about 83,000.
  private static final int MAXIMUM_SYMBOL_HASH = 1 << 24;

  private static final int ENTRIES_PER_LINE = 8;

  private static final String LINE_INDENT = "    ";

  private static final String ENTRY_GAP = " ";

  private static final String ENTRY_SEPARATOR = ",";

  private static final int MULTIPLIER_STEP = 2;

  private static final char UNSIGNED_SUFFIX = 'u';

  private static final String LINE_SEPARATOR = "\n";

  private static final String LIST_SEPARATOR = ", ";

  private static final String SIZE_SEPARATOR = "x";

  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int CHANNEL_MASK = 255;

  private static final int RGB_MASK = 0xFFFFFF;

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private Mcv2Pack() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Writes the pack of one screen.
   *
   * @param configuration the screen
   * @param debugView     whether the pack also draws the decoded picture one to one below the strip, for testing
   * @param archivePath   where the pack is written, atomically
   * @throws BukkitException      if the pack cannot be written
   * @throws UncheckedIOException if a pack resource cannot be read
   * @throws NullPointerException if {@code configuration}, {@code debugView} or {@code archivePath} is null
   * @since 1.0.0
   */
  public static void write(final Mcv2Configuration configuration, final DebugView debugView, final Path archivePath) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null!");

    write(List.of(configuration), debugView, archivePath);
  }

  /**
   * Writes the pack of every screen of the server.
   *
   * @param screens     the screens, in the order of their place in the strip; their stream ids must differ, and they
   *                    share the outline colour of the first
   * @param debugView   whether the pack also draws every screen's decoded picture one to one below the strip, for
   *                    testing
   * @param archivePath where the pack is written, atomically
   * @throws IllegalArgumentException if there are no screens or more than {@link #MAXIMUM_SCREENS}, two share a stream
   *                                  id, or their outline colours differ
   * @throws BukkitException          if the pack cannot be written
   * @throws UncheckedIOException     if a pack resource cannot be read
   * @throws NullPointerException     if {@code screens}, {@code debugView} or {@code archivePath} is null
   * @since 1.0.0
   */
  public static void write(final List<Mcv2Configuration> screens, final DebugView debugView, final Path archivePath) {
    Preconditions.checkNotNull(screens, "Screens must not be null!");
    Preconditions.checkNotNull(debugView, "Debug view must not be null!");
    Preconditions.checkNotNull(archivePath, "Archive path must not be null!");
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
        pack.data(
          POST + SCREEN_DIRECTORY_PREFIX + screen + PATH_SEPARATOR + file,
          screenCopy(resource(POST + file), screen).getBytes(StandardCharsets.UTF_8)
        );
      }
      final String include = screenConstants(configuration, screen, firstSlot, debugTop);
      pack.data(INCLUDE + screenInclude(screen), include.getBytes(StandardCharsets.UTF_8));
      firstSlot += configuration.getPageSlots();
      debugTop += Math.max(configuration.getVideoHeight(), DEBUG_SQUARES) + DEBUG_GAP;
    }

    pack.data(POST_CHAIN, postChain(screens).getBytes(StandardCharsets.UTF_8));
    pack.data(INCLUDE + CONFIG_INCLUDE, packConstants(screens, debugView).getBytes(StandardCharsets.UTF_8));
    pack.data(INCLUDE + ALPHABET_INCLUDE, alphabet(palette()).getBytes(StandardCharsets.UTF_8));
    pack.data(MANIFEST_FILE, manifest(screens).getBytes(StandardCharsets.UTF_8));
    pack.zip(archivePath);
  }

  private static void checkScreens(final List<Mcv2Configuration> screens) {
    Preconditions.checkArgument(!screens.isEmpty() && screens.size() <= MAXIMUM_SCREENS, "A pack has 1 to %s screens!", MAXIMUM_SCREENS);

    final Set<Long> streams = screens.stream().map(Mcv2Configuration::getStreamId).collect(Collectors.toSet());
    Preconditions.checkArgument(streams.size() == screens.size(), "Every screen of a pack needs its own stream id!");

    final NamedTextColor outline = screens.getFirst().getOutlineColor();
    final boolean oneOutline = screens.stream().allMatch(screen -> screen.getOutlineColor().equals(outline));
    Preconditions.checkArgument(oneOutline, "The screens of a pack share one outline color!");
  }

  static String describe(final List<Mcv2Configuration> screens) {
    final String sizes = screens
      .stream()
      .map(screen -> screen.getVideoWidth() + SIZE_SEPARATOR + screen.getVideoHeight())
      .collect(Collectors.joining(LIST_SEPARATOR));
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
      final String message = "MCV2 pack resource %s is missing!".formatted(file);
      throw new IllegalStateException(message);
    }

    try (stream) {
      return stream.readAllBytes();
    } catch (final IOException exception) {
      final String message = "MCV2 pack resource %s cannot be read!".formatted(file);
      throw new UncheckedIOException(message, exception);
    }
  }

  private static String screenInclude(final int screen) {
    return SCREEN_INCLUDE_PREFIX + screen + GLSL_EXTENSION;
  }

  static String screenCopy(final byte[] source, final int screen) {
    final String text = new String(source, StandardCharsets.UTF_8);
    Preconditions.checkState(text.contains(SCREEN_INCLUDE), "A screen's pass must include mcv2_screen.glsl!");

    return text.replace(SCREEN_INCLUDE, INCLUDE_DIRECTIVE_PREFIX + screenInclude(screen) + INCLUDE_DIRECTIVE_SUFFIX);
  }

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

    for (final Map.Entry<String, JsonElement> target : shared.getAsJsonObject(TARGETS_KEY).entrySet()) {
      targets.add(target.getKey(), target.getValue());
    }

    final JsonArray passes = new JsonArray();
    passes.addAll(decoding);
    passes.addAll(drawing);
    passes.addAll(shared.getAsJsonArray("tail"));
    final JsonObject chain = new JsonObject();
    chain.add(TARGETS_KEY, targets);
    chain.add("passes", passes);
    return GSON.toJson(chain);
  }

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

  private static int bytesHeight(final Mcv2Configuration configuration) {
    final int bytes = configuration.getPageSlots() * TransportPages.capacity();
    return (bytes / TEXEL_BYTES + BYTES_WIDTH - 1) / BYTES_WIDTH;
  }

  static int cellsWidth(final Mcv2Configuration configuration) {
    return (configuration.getVideoWidth() + CELL_PIXELS - 1) / CELL_PIXELS;
  }

  static int cellsHeight(final Mcv2Configuration configuration) {
    return (configuration.getVideoHeight() + CELL_PIXELS - 1) / CELL_PIXELS;
  }

  static String packConstants(final List<Mcv2Configuration> screens, final DebugView debugView) {
    final int count = screens.size();
    final int color = screens.getFirst().getOutlineColor().value();
    final StringBuilder streams = new StringBuilder();
    final StringBuilder slots = new StringBuilder();
    final StringBuilder firsts = new StringBuilder();
    int total = 0;
    for (int screen = 0; screen < count; screen++) {
      final Mcv2Configuration configuration = screens.get(screen);
      final String separator = screen == 0 ? "" : LIST_SEPARATOR;
      streams.append(separator).append(configuration.getStreamId()).append(UNSIGNED_SUFFIX);
      slots.append(separator).append(configuration.getPageSlots());
      firsts.append(separator).append(total);
      total += configuration.getPageSlots();
    }

    return guarded(
      CONFIG_GUARD,
      String.join(
        LINE_SEPARATOR,
        String.format(Locale.ROOT, "const int MCV2_SCREENS = %d;", count),
        String.format(Locale.ROOT, "const int MCV2_TOTAL_SLOTS = %d;", total),
        String.format(Locale.ROOT, "const uint MCV2_SCREEN_STREAMS[%d] = uint[%d](%s);", count, count, streams),
        String.format(Locale.ROOT, "const int MCV2_SCREEN_SLOTS[%d] = int[%d](%s);", count, count, slots),
        String.format(Locale.ROOT, "const int MCV2_SCREEN_FIRST_SLOTS[%d] = int[%d](%s);", count, count, firsts),
        String.format(Locale.ROOT, "const bool MCV2_DEBUG_VIEW = %s;", debugView == DebugView.SHOWN),
        String.format(
          Locale.ROOT,
          "const ivec3 MCV2_OUTLINE_COLOR = ivec3(%d, %d, %d);",
          (color >> RED_SHIFT) & CHANNEL_MASK,
          (color >> GREEN_SHIFT) & CHANNEL_MASK,
          color & CHANNEL_MASK
        ),
        ""
      )
    );
  }

  private static String screenConstants(final Mcv2Configuration configuration, final int screen, final int firstSlot, final int debugTop) {
    return guarded(
      SCREEN_GUARD,
      String.join(
        LINE_SEPARATOR,
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

  static int[] palette() {
    final int[] colors = new int[MapAlphabet.SIZE];
    for (int symbol = 0; symbol < colors.length; symbol++) {
      colors[symbol] = MapColor.getColorFromPackedId(symbol + MapAlphabet.FIRST_COLOR) & RGB_MASK;
    }

    return colors;
  }

  static String alphabet(final int[] colors) {
    return alphabet(colors, MAXIMUM_SYMBOL_HASH);
  }

  // A table read per symbol, not a search of the 64 colours: the text shaders read up to 55 symbols for each vertex of a
  // map and 4 for each pixel of the strip, and the search made a wall of page frames cost an integrated GPU 50 ms a frame.
  static String alphabet(final int[] colors, final int maximumMultiplier) {
    final Set<Integer> seen = new HashSet<>();
    for (int symbol = 0; symbol < colors.length; symbol++) {
      if (!seen.add(colors[symbol])) {
        final String message = "Map color %s and another symbol are the same RGB!".formatted(symbol + MapAlphabet.FIRST_COLOR);
        throw new IllegalStateException(message);
      }
    }

    final int multiplier = symbolHash(colors, maximumMultiplier);
    final int[] entries = new int[SYMBOL_ENTRIES];
    Arrays.fill(entries, NO_SYMBOL);
    for (int symbol = 0; symbol < colors.length; symbol++) {
      entries[symbolEntry(colors[symbol], multiplier)] = (colors[symbol] << MapAlphabet.SYMBOL_BITS) | symbol;
    }

    final StringBuilder table = new StringBuilder();
    for (int entry = 0; entry < entries.length; entry++) {
      table
        .append(entry % ENTRIES_PER_LINE == 0 ? LINE_INDENT : ENTRY_GAP)
        .append(Integer.toUnsignedString(entries[entry]))
        .append(UNSIGNED_SUFFIX);
      table
        .append(entry < entries.length - 1 ? ENTRY_SEPARATOR : "")
        .append(entry % ENTRIES_PER_LINE == ENTRIES_PER_LINE - 1 ? LINE_SEPARATOR : "");
    }

    return guarded(
      ALPHABET_GUARD,
      String.format(
        Locale.ROOT,
        "const uint MCV2_SYMBOL_HASH = %du;\nconst uint MCV2_SYMBOL_SHIFT = %du;\nconst uint MCV2_SYMBOLS[%d] = uint[%d](\n%s);\n",
        multiplier,
        SYMBOL_SHIFT,
        SYMBOL_ENTRIES,
        SYMBOL_ENTRIES,
        table
      )
    );
  }

  // The entry the shader reads: the top bits of the colour's 32-bit product with the multiplier.
  private static int symbolEntry(final int color, final int multiplier) {
    return (color * multiplier) >>> SYMBOL_SHIFT;
  }

  private static int symbolHash(final int[] colors, final int maximumMultiplier) {
    final boolean[] taken = new boolean[SYMBOL_ENTRIES];
    for (int multiplier = 1; multiplier <= maximumMultiplier; multiplier += MULTIPLIER_STEP) {
      Arrays.fill(taken, false);
      boolean distinct = true;
      for (int symbol = 0; symbol < colors.length && distinct; symbol++) {
        final int entry = symbolEntry(colors[symbol], multiplier);
        distinct = !taken[entry];
        taken[entry] = true;
      }
      if (distinct) {
        return multiplier;
      }
    }

    final String message = "No odd multiplier up to %s gives the map colors distinct symbol entries!".formatted(maximumMultiplier);
    throw new IllegalStateException(message);
  }

  // Minecraft's shaderc inserts an include every time it is named, including through another include.
  private static String guarded(final String guard, final String body) {
    return "#ifndef %s\n#define %s\n\n%s\n#endif\n".formatted(guard, guard, body);
  }

  private static String manifest(final List<Mcv2Configuration> screens) {
    final JsonObject manifest = new JsonObject();
    manifest.addProperty("codec", CODEC);
    manifest.addProperty("version", Mcv2Decoder.VERSION);
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

  /**
   * Whether a pack also draws every screen's decoded picture one to one below the strip, for testing.
   *
   * <p>This enum is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public enum DebugView {
    /**
     * The pack draws the decoded pictures below the strip.
     *
     * @since 1.0.0
     */
    SHOWN,

    /**
     * The pack draws only the screens.
     *
     * @since 1.0.0
     */
    HIDDEN,
  }
}
