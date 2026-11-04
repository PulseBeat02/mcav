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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

final class DeltaMapEncoderPropertyTest {

  private static final String SEED = "20260925";
  private static final int MAP_SIZE = MapLayout.MAP_SIZE;
  private static final int TILES_PER_MAP = 64;
  private static final int MAXIMUM_QUIET_FRAMES = 7;
  private static final short NEVER_SENT = -1;

  private static final int PER_MILLE = 1000;

  private static final String SCENARIOS = "scenarios";

  // jqwik 1.10 shares a range between derived arbitraries, so each range starts from a fresh one
  private Arbitrary<Integer> between(final int minimum, final int maximum) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(minimum, maximum);
  }

  @Provide
  Arbitrary<EncodingScenario> scenarios() {
    final Arbitrary<Integer> columns = this.between(1, 3);
    final Arbitrary<Integer> rows = this.between(1, 3);
    final Arbitrary<Integer> widths = this.between(1, 3 * MAP_SIZE + 40);
    final Arbitrary<Integer> heights = this.between(1, 3 * MAP_SIZE + 40);
    final Arbitrary<Integer> tinyBudgets = this.between(1, 600);
    final Arbitrary<Integer> smallBudgets = this.between(600, 40_000);
    final Arbitrary<Integer> largeBudgets = this.between(40_000, 1 << 20);
    final Arbitrary<Integer> budgets = Arbitraries.oneOf(tinyBudgets, smallBudgets, largeBudgets);
    final Arbitrary<List<FrameEdit>> editsOfFrame = this.edits();
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

  private Arbitrary<List<FrameEdit>> edits() {
    final Arbitrary<Integer> positions = this.between(0, 999);
    final Arbitrary<Integer> sizes = this.between(1, PER_MILLE);
    final Arbitrary<Integer> pixels = Arbitraries.just(1);
    final Arbitrary<Integer> extents = Arbitraries.oneOf(sizes, pixels);
    final Arbitrary<Integer> colors = this.between(0, 60);
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
  void shouldRespectTheBudgetAndCarryOnlyPixelsOfThatFrameInEveryFrame(@ForAll(SCENARIOS) final EncodingScenario scenario) {
    final MapLayout layout = scenario.createLayout();
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, scenario.getBudget());
    final ClientModel clients = new ClientModel(layout);

    for (final byte[] frame : scenario.createFrames()) {
      final List<MapTilePatch> patches = encoder.encode(frame);
      this.assertWithinBudget(patches, scenario.getBudget());
      for (final MapTilePatch patch : patches) {
        clients.assertCarriesFramePixels(patch, frame);
        clients.apply(patch);
      }
      final List<MapTilePatch> snapshot = encoder.snapshot();
      clients.assertMatchesSnapshot(snapshot);
    }
  }

  @Property(seed = SEED)
  void shouldShowExactlyTheLastFrameOnTheClientsOnceThePictureStopsChanging(@ForAll(SCENARIOS) final EncodingScenario scenario) {
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
    final int frameLimit = MAXIMUM_QUIET_FRAMES * (coveredMaps * (TILES_PER_MAP + 1)) + MAXIMUM_QUIET_FRAMES;
    int quietFrames = 0;
    int extraFrames = 0;
    while (quietFrames < MAXIMUM_QUIET_FRAMES) {
      final boolean withinLimit = extraFrames < frameLimit;
      assertTrue(withinLimit, () -> "the clients did not catch up within " + frameLimit + " frames");
      final List<MapTilePatch> patches = encoder.encode(lastFrame);
      clients.applyAll(patches);
      quietFrames = patches.isEmpty() ? quietFrames + 1 : 0;
      extraFrames++;
    }

    clients.assertShows(lastFrame);
  }

  @Property(seed = SEED)
  void shouldSendEveryCoveredMapCompletelyAgainOnAReset(@ForAll(SCENARIOS) final EncodingScenario scenario) {
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

  private void assertWithinBudget(final List<MapTilePatch> patches, final int budget) {
    long bytes = 0;
    final Set<Integer> maps = new HashSet<>();
    for (final MapTilePatch patch : patches) {
      bytes += patch.getEncodedSize();
      final int mapId = patch.getMapId();
      maps.add(mapId);
    }

    final long sent = bytes;
    final int mapCount = maps.size();
    final boolean withinBudget = sent <= budget || mapCount == 1;
    assertTrue(withinBudget, () -> sent + " bytes over " + mapCount + " maps in one frame, budget " + budget);
  }

  // a pure function of the scenario, so shrinking the scenario shrinks the frames
  private static final class EncodingScenario {

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

    private MapLayout createLayout() {
      return MapLayout.of(7, this.columns, this.rows, this.imageWidth, this.imageHeight);
    }

    private List<byte[]> createFrames() {
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

    private int getBudget() {
      return this.budget;
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

  private static final class FrameEdit {

    private final int left;
    private final int top;
    private final int width;
    private final int height;
    private final int color;

    FrameEdit(final int left, final int top, final int width, final int height, final int color) {
      this.left = left;
      this.top = top;
      this.width = width;
      this.height = height;
      this.color = color;
    }

    private void applyTo(final byte[] frame, final int imageWidth, final int imageHeight) {
      final int left = (this.left * imageWidth) / PER_MILLE;
      final int top = (this.top * imageHeight) / PER_MILLE;
      final int right = Math.min(imageWidth, left + scale(this.width, imageWidth));
      final int bottom = Math.min(imageHeight, top + scale(this.height, imageHeight));
      for (int row = top; row < bottom; row++) {
        for (int column = left; column < right; column++) {
          frame[row * imageWidth + column] = (byte) this.color;
        }
      }
    }

    private static int scale(final int size, final int imageSize) {
      final int scaled = (size * imageSize) / PER_MILLE;
      return size == 1 ? 1 : Math.max(1, scaled);
    }

    @Override
    public String toString() {
      return "(" + this.left + "," + this.top + " " + this.width + "x" + this.height + " color " + this.color + ")";
    }
  }

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

    private void applyAll(final List<MapTilePatch> patches) {
      for (final MapTilePatch patch : patches) {
        this.apply(patch);
      }
    }

    private void apply(final MapTilePatch patch) {
      final int index = this.indexOf(patch);
      final short[] map = this.maps[index];
      final byte[] colors = patch.getColors();
      final int width = patch.getWidth();
      final int height = patch.getHeight();
      final int left = patch.getX();
      final int top = patch.getY();
      for (int row = 0; row < height; row++) {
        for (int column = 0; column < width; column++) {
          map[(top + row) * MAP_SIZE + left + column] = colors[row * width + column];
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

    private void assertCarriesFramePixels(final MapTilePatch patch, final byte[] frame) {
      final int index = this.indexOf(patch);
      final MapRegion region = this.layout.getRegion(index);
      final int left = patch.getX();
      final int top = patch.getY();
      final int width = patch.getWidth();
      final int height = patch.getHeight();
      final int regionX = region.getLocalX();
      final int regionY = region.getLocalY();
      final boolean inside =
        left >= regionX && top >= regionY && left + width <= regionX + region.getWidth() && top + height <= regionY + region.getHeight();
      assertTrue(inside, "patch inside the covered region of its map");

      final byte[] colors = patch.getColors();
      final int imageWidth = this.layout.getImageWidth();
      final int sourceX = region.getSourceX() + left - regionX;
      final int sourceY = region.getSourceY() + top - regionY;
      for (int row = 0; row < height; row++) {
        for (int column = 0; column < width; column++) {
          final byte expected = frame[(sourceY + row) * imageWidth + sourceX + column];
          final byte actual = colors[row * width + column];
          assertEquals(expected, actual, "a patch carries the pixels of the frame it was encoded from");
        }
      }
    }

    private void assertMatchesSnapshot(final List<MapTilePatch> snapshot) {
      final ClientModel restored = new ClientModel(this.layout);
      restored.applyAll(snapshot);
      for (int index = 0; index < this.maps.length; index++) {
        final short[] expectedMap = this.maps[index];
        final short[] restoredMap = restored.maps[index];
        assertArrayEquals(expectedMap, restoredMap, "the snapshot reconstructs every sent pixel of map " + index);
      }

      final Set<Integer> snapshotMaps = new HashSet<>();
      for (final MapTilePatch patch : snapshot) {
        final int index = this.indexOf(patch);
        snapshotMaps.add(index);
        this.assertPatchShown(patch, this.maps[index]);
      }

      for (int index = 0; index < this.maps.length; index++) {
        final boolean received = this.hasReceived(index);
        final boolean inSnapshot = snapshotMaps.contains(index);
        assertEquals(received, inSnapshot, "the snapshot holds exactly the maps that were sent");
      }
    }

    private void assertPatchShown(final MapTilePatch patch, final short[] map) {
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

    private boolean hasReceived(final int index) {
      for (final short pixel : this.maps[index]) {
        if (pixel != NEVER_SENT) {
          return true;
        }
      }

      return false;
    }

    private int countCoveredMaps() {
      int covered = 0;
      for (int index = 0; index < this.maps.length; index++) {
        final MapRegion region = this.layout.getRegion(index);
        if (!region.isEmpty()) {
          covered++;
        }
      }

      return covered;
    }

    private void assertShows(final byte[] frame) {
      final int imageWidth = this.layout.getImageWidth();
      for (int index = 0; index < this.maps.length; index++) {
        for (int row = 0; row < MAP_SIZE; row++) {
          this.assertRowShows(frame, imageWidth, index, row);
        }
      }
    }

    private void assertRowShows(final byte[] frame, final int imageWidth, final int index, final int row) {
      final MapRegion region = this.layout.getRegion(index);
      final short[] map = this.maps[index];
      for (int column = 0; column < MAP_SIZE; column++) {
        final short shown = map[row * MAP_SIZE + column];
        final int regionX = column - region.getLocalX();
        final int regionY = row - region.getLocalY();
        final boolean covered = regionX >= 0 && regionY >= 0 && regionX < region.getWidth() && regionY < region.getHeight();
        if (covered) {
          final int sourceIndex = (region.getSourceY() + regionY) * imageWidth + region.getSourceX() + regionX;
          final short expected = frame[sourceIndex];
          assertEquals(expected, shown, "map " + index + " shows the last frame at " + column + "," + row);
        } else {
          assertEquals(NEVER_SENT, shown, "map " + index + " was sent nothing outside the image at " + column + "," + row);
        }
      }
    }
  }
}
