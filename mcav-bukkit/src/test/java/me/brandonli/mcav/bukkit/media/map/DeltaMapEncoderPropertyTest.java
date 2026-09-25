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
package me.brandonli.mcav.bukkit.media.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.arbitraries.ListArbitrary;

/**
 * Properties of {@link DeltaMapEncoder}, checked against a model of the clients: an array per map that receives every
 * patch the encoder returns, in order, exactly as a client applies map data packets.
 *
 * <p>The encoder documents two ways in which a frame may reach the clients incompletely: maps that do not fit the byte
 * budget of a frame wait for later frames, and tiles with only a few changed pixels are held back for a few frames.
 * Both only delay: once the picture stops changing, the clients must end up showing exactly the last frame. The number
 * of frames that takes is bounded, because every frame that sends anything brings at least one tile up to date and at
 * most {@value #MAX_QUIET_FRAMES} frames in a row can send nothing while a tile is still out of date.
 */
final class DeltaMapEncoderPropertyTest {

  private static final String SEED = "20260925";
  private static final int MAP_SIZE = MapLayout.MAP_SIZE;
  private static final int TILES_PER_MAP = 64;
  private static final int MAX_QUIET_FRAMES = 7;
  private static final short NEVER_SENT = -1;

  /**
   * Creates integers in a range from a fresh arbitrary: jqwik 1.9 shares the range between an arbitrary and the ones
   * configured from it, so one base configured twice would hand every user the last range.
   */
  private static Arbitrary<Integer> between(final int min, final int max) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(min, max);
  }

  @Provide
  Arbitrary<EncodingScenario> scenarios() {
    final Arbitrary<Integer> columns = between(1, 3);
    final Arbitrary<Integer> rows = between(1, 3);
    final Arbitrary<Integer> widths = between(1, 3 * MAP_SIZE + 40);
    final Arbitrary<Integer> heights = between(1, 3 * MAP_SIZE + 40);
    final Arbitrary<Integer> tinyBudgets = between(1, 600);
    final Arbitrary<Integer> smallBudgets = between(600, 40_000);
    final Arbitrary<Integer> largeBudgets = between(40_000, 1 << 20);
    final Arbitrary<Integer> budgets = Arbitraries.oneOf(tinyBudgets, smallBudgets, largeBudgets);
    final Arbitrary<List<FrameEdit>> editsOfFrame = edits();
    final ListArbitrary<List<FrameEdit>> frames = editsOfFrame.list();
    final ListArbitrary<List<FrameEdit>> someFrames = frames.ofMinSize(1);
    final ListArbitrary<List<FrameEdit>> boundedFrames = someFrames.ofMaxSize(6);
    final Combinators.Combinator6<Integer, Integer, Integer, Integer, Integer, List<List<FrameEdit>>> scenarios = Combinators.combine(
      columns,
      rows,
      widths,
      heights,
      budgets,
      boundedFrames
    );
    return scenarios.as(EncodingScenario::new);
  }

  /**
   * Changes of one frame against the previous one: large rectangles, which look like motion, and single pixels, which
   * look like dithering noise and are held back by the encoder.
   */
  private static Arbitrary<List<FrameEdit>> edits() {
    final Arbitrary<Integer> positions = between(0, 999);
    final Arbitrary<Integer> sizes = between(1, 1000);
    final Arbitrary<Integer> pixels = Arbitraries.just(1);
    final Arbitrary<Integer> extents = Arbitraries.oneOf(sizes, pixels);
    final Arbitrary<Integer> colors = between(0, 60);
    final Combinators.Combinator5<Integer, Integer, Integer, Integer, Integer> combined = Combinators.combine(
      positions,
      positions,
      extents,
      extents,
      colors
    );
    final Arbitrary<FrameEdit> edit = combined.as(FrameEdit::new);
    final ListArbitrary<FrameEdit> list = edit.list();
    return list.ofMaxSize(5);
  }

  @Property(seed = SEED)
  void everyFrameRespectsTheBudgetAndCarriesOnlyPixelsOfThatFrame(@ForAll("scenarios") final EncodingScenario scenario) {
    final MapLayout layout = scenario.createLayout();
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, scenario.getBudget());
    final ClientModel clients = new ClientModel(layout);

    for (final byte[] frame : scenario.createFrames()) {
      final List<MapTilePatch> patches = encoder.encode(frame);
      assertWithinBudget(patches, scenario.getBudget());
      for (final MapTilePatch patch : patches) {
        clients.assertCarriesFramePixels(patch, frame);
        clients.apply(patch);
      }
      final List<MapTilePatch> snapshot = encoder.snapshot();
      clients.assertMatchesSnapshot(snapshot);
    }
  }

  @Property(seed = SEED)
  void theClientsShowExactlyTheLastFrameOnceThePictureStopsChanging(@ForAll("scenarios") final EncodingScenario scenario) {
    final MapLayout layout = scenario.createLayout();
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, scenario.getBudget());
    final ClientModel clients = new ClientModel(layout);
    final List<byte[]> frames = scenario.createFrames();
    for (final byte[] frame : frames) {
      final List<MapTilePatch> patches = encoder.encode(frame);
      clients.applyAll(patches);
    }

    final byte[] lastFrame = frames.getLast();
    final int coveredMaps = clients.countCoveredMaps();
    final int frameLimit = MAX_QUIET_FRAMES * (coveredMaps * (TILES_PER_MAP + 1)) + MAX_QUIET_FRAMES;
    int quietFrames = 0;
    int extraFrames = 0;
    while (quietFrames < MAX_QUIET_FRAMES) {
      assertTrue(extraFrames < frameLimit, () -> "the clients did not catch up within " + frameLimit + " frames");
      final List<MapTilePatch> patches = encoder.encode(lastFrame);
      clients.applyAll(patches);
      quietFrames = patches.isEmpty() ? quietFrames + 1 : 0;
      extraFrames++;
    }
    clients.assertShows(lastFrame);
  }

  @Property(seed = SEED)
  void aResetSendsEveryCoveredMapCompletelyAgain(@ForAll("scenarios") final EncodingScenario scenario) {
    final MapLayout layout = scenario.createLayout();
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, 1 << 30);
    final List<byte[]> frames = scenario.createFrames();
    for (final byte[] frame : frames) {
      encoder.encode(frame);
    }

    encoder.reset();
    final byte[] lastFrame = frames.getLast();
    final List<MapTilePatch> patches = encoder.encode(lastFrame);
    final ClientModel clients = new ClientModel(layout);
    clients.applyAll(patches);
    clients.assertShows(lastFrame);
  }

  private static void assertWithinBudget(final List<MapTilePatch> patches, final int budget) {
    long bytes = 0;
    final Set<Integer> maps = new HashSet<>();
    for (final MapTilePatch patch : patches) {
      bytes += patch.getEncodedSize();
      final int mapId = patch.getMapId();
      maps.add(mapId);
    }
    final long sent = bytes;
    final int mapCount = maps.size();
    // the most urgent map is sent even when it alone exceeds the budget, and then it is the only one
    final boolean withinBudget = sent <= budget || mapCount == 1;
    assertTrue(withinBudget, () -> sent + " bytes over " + mapCount + " maps in one frame, budget " + budget);
  }

  /**
   * One frame of a scenario, a pure function of the scenario so that shrinking the scenario shrinks the frames.
   */
  static final class EncodingScenario {

    private final int columns;
    private final int rows;
    private final int imageWidth;
    private final int imageHeight;
    private final int budget;
    private final List<List<FrameEdit>> frameEdits;

    EncodingScenario(
      final int columns,
      final int rows,
      final int imageWidth,
      final int imageHeight,
      final int budget,
      final List<List<FrameEdit>> frameEdits
    ) {
      this.columns = columns;
      this.rows = rows;
      this.imageWidth = imageWidth;
      this.imageHeight = imageHeight;
      this.budget = budget;
      this.frameEdits = frameEdits;
    }

    MapLayout createLayout() {
      return new MapLayout(7, this.columns, this.rows, this.imageWidth, this.imageHeight);
    }

    int getBudget() {
      return this.budget;
    }

    /**
     * Builds the frames: the first starts from a picture with a different color in every row, and every frame applies
     * its edits to the previous one.
     */
    List<byte[]> createFrames() {
      final List<byte[]> frames = new ArrayList<>();
      byte[] frame = new byte[this.imageWidth * this.imageHeight];
      for (int index = 0; index < frame.length; index++) {
        final int row = index / this.imageWidth;
        frame[index] = (byte) (row % 50);
      }
      for (final List<FrameEdit> edits : this.frameEdits) {
        final byte[] next = frame.clone();
        for (final FrameEdit edit : edits) {
          edit.applyTo(next, this.imageWidth, this.imageHeight);
        }
        frames.add(next);
        frame = next;
      }
      return frames;
    }

    @Override
    public String toString() {
      return (
        "maps " +
        this.columns +
        "x" +
        this.rows +
        ", image " +
        this.imageWidth +
        "x" +
        this.imageHeight +
        ", budget " +
        this.budget +
        ", frames " +
        this.frameEdits
      );
    }
  }

  /**
   * A rectangle painted in one color, given in thousandths of the image so it fits every image size.
   */
  static final class FrameEdit {

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int color;

    FrameEdit(final int x, final int y, final int width, final int height, final int color) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
      this.color = color;
    }

    void applyTo(final byte[] frame, final int imageWidth, final int imageHeight) {
      final int left = (this.x * imageWidth) / 1000;
      final int top = (this.y * imageHeight) / 1000;
      final int right = Math.min(imageWidth, left + scale(this.width, imageWidth));
      final int bottom = Math.min(imageHeight, top + scale(this.height, imageHeight));
      for (int row = top; row < bottom; row++) {
        for (int column = left; column < right; column++) {
          frame[row * imageWidth + column] = (byte) this.color;
        }
      }
    }

    // a size of 1 means one pixel, anything else a share of the image
    private static int scale(final int size, final int imageSize) {
      final int scaled = (size * imageSize) / 1000;
      return size == 1 ? 1 : Math.max(1, scaled);
    }

    @Override
    public String toString() {
      return "(" + this.x + "," + this.y + " " + this.width + "x" + this.height + " color " + this.color + ")";
    }
  }

  /**
   * What the clients display: every map starts with pixels that were never sent, and every patch overwrites its
   * rectangle, as the Minecraft client does with a map data packet.
   */
  private static final class ClientModel {

    private final MapLayout layout;
    private final short[][] maps;

    ClientModel(final MapLayout layout) {
      this.layout = layout;
      final int mapCount = layout.getMapCount();
      this.maps = new short[mapCount][MAP_SIZE * MAP_SIZE];
      for (final short[] map : this.maps) {
        Arrays.fill(map, NEVER_SENT);
      }
    }

    void applyAll(final List<MapTilePatch> patches) {
      for (final MapTilePatch patch : patches) {
        this.apply(patch);
      }
    }

    void apply(final MapTilePatch patch) {
      final int index = this.indexOf(patch);
      final short[] map = this.maps[index];
      final byte[] colors = patch.getColors();
      final int width = patch.getWidth();
      final int height = patch.getHeight();
      final int x = patch.getX();
      final int y = patch.getY();
      for (int row = 0; row < height; row++) {
        for (int column = 0; column < width; column++) {
          map[(y + row) * MAP_SIZE + x + column] = colors[row * width + column];
        }
      }
    }

    private int indexOf(final MapTilePatch patch) {
      final int mapId = patch.getMapId();
      final int firstId = this.layout.getMapId(0);
      final int index = mapId - firstId;
      final int mapCount = this.layout.getMapCount();
      final boolean inLayout = index >= 0 && index < mapCount;
      assertTrue(inLayout, () -> "patch for map " + mapId + ", which is not part of the layout");
      return index;
    }

    /**
     * Asserts that a patch lies inside the covered region of its map and shows the pixels the frame has there.
     */
    void assertCarriesFramePixels(final MapTilePatch patch, final byte[] frame) {
      final int index = this.indexOf(patch);
      final MapRegion region = this.layout.getRegion(index);
      final int x = patch.getX();
      final int y = patch.getY();
      final int width = patch.getWidth();
      final int height = patch.getHeight();
      final int regionX = region.getLocalX();
      final int regionY = region.getLocalY();
      final boolean inside =
        x >= regionX && y >= regionY && x + width <= regionX + region.getWidth() && y + height <= regionY + region.getHeight();
      assertTrue(inside, "patch inside the covered region of its map");

      final byte[] colors = patch.getColors();
      final int imageWidth = this.layout.getImageWidth();
      final int sourceX = region.getSourceX() + x - regionX;
      final int sourceY = region.getSourceY() + y - regionY;
      for (int row = 0; row < height; row++) {
        for (int column = 0; column < width; column++) {
          final byte expected = frame[(sourceY + row) * imageWidth + sourceX + column];
          final byte actual = colors[row * width + column];
          assertEquals(expected, actual, "a patch carries the pixels of the frame it was encoded from");
        }
      }
    }

    /**
     * Asserts that the snapshot is exactly what the clients display: every map that received anything, and nothing else.
     */
    void assertMatchesSnapshot(final List<MapTilePatch> snapshot) {
      final Set<Integer> snapshotMaps = new HashSet<>();
      for (final MapTilePatch patch : snapshot) {
        final int index = this.indexOf(patch);
        snapshotMaps.add(index);
        final short[] map = this.maps[index];
        final byte[] colors = patch.getColors();
        final int width = patch.getWidth();
        for (int row = 0; row < patch.getHeight(); row++) {
          for (int column = 0; column < width; column++) {
            final short shown = map[(patch.getY() + row) * MAP_SIZE + patch.getX() + column];
            final short expected = colors[row * width + column];
            assertEquals(expected, shown, "the snapshot shows what the clients display");
          }
        }
      }
      for (int index = 0; index < this.maps.length; index++) {
        final boolean received = this.hasReceived(index);
        final boolean inSnapshot = snapshotMaps.contains(index);
        assertEquals(received, inSnapshot, "the snapshot holds exactly the maps that were sent");
      }
    }

    private boolean hasReceived(final int index) {
      for (final short pixel : this.maps[index]) {
        if (pixel != NEVER_SENT) {
          return true;
        }
      }
      return false;
    }

    int countCoveredMaps() {
      int covered = 0;
      for (int index = 0; index < this.maps.length; index++) {
        final MapRegion region = this.layout.getRegion(index);
        if (!region.isEmpty()) {
          covered++;
        }
      }
      return covered;
    }

    /**
     * Asserts that every map shows the frame where the image covers it and was never sent anything anywhere else.
     */
    void assertShows(final byte[] frame) {
      final int imageWidth = this.layout.getImageWidth();
      for (int index = 0; index < this.maps.length; index++) {
        final MapRegion region = this.layout.getRegion(index);
        final short[] map = this.maps[index];
        for (int y = 0; y < MAP_SIZE; y++) {
          for (int x = 0; x < MAP_SIZE; x++) {
            final short shown = map[y * MAP_SIZE + x];
            final int regionX = x - region.getLocalX();
            final int regionY = y - region.getLocalY();
            final boolean covered = regionX >= 0 && regionY >= 0 && regionX < region.getWidth() && regionY < region.getHeight();
            if (covered) {
              final int sourceIndex = (region.getSourceY() + regionY) * imageWidth + region.getSourceX() + regionX;
              final short expected = frame[sourceIndex];
              assertEquals(expected, shown, "map " + index + " shows the last frame at " + x + "," + y);
            } else {
              assertEquals(NEVER_SENT, shown, "map " + index + " was sent nothing outside the image at " + x + "," + y);
            }
          }
        }
      }
    }
  }
}
