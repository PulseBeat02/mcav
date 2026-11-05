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

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class MapPipelineFuzzTest {

  private static final int MAXIMUM_MAPS_PER_SIDE = 4;
  private static final int MAP_SIZE = MapLayout.MAP_SIZE;

  @FuzzTest(maxDuration = "30s")
  void shouldMakeEveryPatchBelongToTheWallAndCarryItsFrame(final FuzzedDataProvider data) {
    final int columns = data.consumeInt(1, MAXIMUM_MAPS_PER_SIDE);
    final int rows = data.consumeInt(1, MAXIMUM_MAPS_PER_SIDE);
    final int mapCount = columns * rows;
    final int startMapId = data.consumeInt(0, Integer.MAX_VALUE - mapCount + 1);
    final int width = data.consumeInt(1, columns * MAP_SIZE + 64);
    final int height = data.consumeInt(1, rows * MAP_SIZE + 64);
    final int budget = data.consumeInt(1, 1 << 20);
    final MapLayout layout = MapLayout.of(startMapId, columns, rows, width, height);
    final DeltaMapEncoder encoder = new DeltaMapEncoder(layout, budget);

    final byte[] frame = new byte[width * height];
    final int frames = data.consumeInt(1, 6);
    for (int index = 0; index < frames; index++) {
      this.paint(data, frame, width, height);
      final List<MapTilePatch> patches = encoder.encode(frame);
      this.assertPatches(layout, frame, patches, budget);
    }
  }

  private void paint(final FuzzedDataProvider data, final byte[] frame, final int width, final int height) {
    final int rectangles = data.consumeInt(0, 6);
    for (int rectangle = 0; rectangle < rectangles; rectangle++) {
      this.paintRectangle(data, frame, width, height);
    }
  }

  private void paintRectangle(final FuzzedDataProvider data, final byte[] frame, final int width, final int height) {
    final int left = data.consumeInt(0, width - 1);
    final int top = data.consumeInt(0, height - 1);
    final int right = data.consumeInt(left, width - 1);
    final int bottom = data.consumeInt(top, height - 1);
    final byte color = data.consumeByte();
    for (int row = top; row <= bottom; row++) {
      for (int column = left; column <= right; column++) {
        frame[row * width + column] = color;
      }
    }
  }

  private void assertPatches(final MapLayout layout, final byte[] frame, final List<MapTilePatch> patches, final int budget) {
    final int firstId = layout.getMapId(0);
    final int mapCount = layout.getMapCount();
    final int imageWidth = layout.getImageWidth();
    long bytes = 0;
    final Set<Integer> maps = new HashSet<>();
    for (final MapTilePatch patch : patches) {
      final int mapId = patch.getMapId();
      final long index = (long) mapId - firstId;
      final boolean inWall = index >= 0 && index < mapCount;
      assertTrue(inWall, () -> "a patch for map %s, outside of the wall".formatted(mapId));

      final MapRegion region = layout.getRegion((int) index);
      final int left = patch.getX();
      final int top = patch.getY();
      final int patchWidth = patch.getWidth();
      final int patchHeight = patch.getHeight();
      final int regionX = region.getLocalX();
      final int regionY = region.getLocalY();
      final boolean inside =
        left >= regionX &&
        top >= regionY &&
        left + patchWidth <= regionX + region.getWidth() &&
        top + patchHeight <= regionY + region.getHeight();
      assertTrue(inside, "a patch outside of the part of its map the picture covers");
      final int sourceX = region.getSourceX() + left - regionX;
      final int sourceY = region.getSourceY() + top - regionY;
      this.assertPatchPixels(frame, imageWidth, patch, sourceX, sourceY);
      bytes += patch.getEncodedSize();
      maps.add(mapId);
    }

    final long sent = bytes;
    final boolean withinBudget = sent <= budget || maps.size() == 1;
    assertTrue(withinBudget, () -> "%s bytes over %s maps, budget %s".formatted(sent, maps.size(), budget));
  }

  private void assertPatchPixels(final byte[] frame, final int imageWidth, final MapTilePatch patch, final int sourceX, final int sourceY) {
    final byte[] colors = patch.getColors();
    final int patchWidth = patch.getWidth();
    for (int row = 0; row < patch.getHeight(); row++) {
      for (int column = 0; column < patchWidth; column++) {
        final byte expected = frame[(sourceY + row) * imageWidth + sourceX + column];
        final byte color = colors[row * patchWidth + column];
        assertEquals(expected, color, "a patch carries the pixels of its frame");
      }
    }
  }
}
