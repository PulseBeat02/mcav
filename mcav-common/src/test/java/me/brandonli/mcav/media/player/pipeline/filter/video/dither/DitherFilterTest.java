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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.AbstractDitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link DitherFilter} and the defaults of {@link AbstractDitherAlgorithm}.
 */
final class DitherFilterTest {

  @Test
  void handsEveryFrameToTheResultStep() {
    final DitherAlgorithm algorithm = mock(DitherAlgorithm.class);
    final DitherResultStep result = mock(DitherResultStep.class);
    final ImageBuffer image = mock(ImageBuffer.class);
    final FunctionalVideoFilter filter = DitherFilter.dither(algorithm, result);
    filter.start();
    final boolean modified = filter.applyFilter(image, OriginalVideoMetadata.EMPTY);
    filter.release();
    final DitherFilter ditherFilter = (DitherFilter) filter;
    final DitherAlgorithm storedAlgorithm = ditherFilter.getAlgorithm();
    final DitherResultStep storedResult = ditherFilter.getResult();
    assertTrue(modified);
    assertSame(algorithm, storedAlgorithm);
    assertSame(result, storedResult);
    verify(result).start();
    verify(result).process(image, algorithm);
    verify(result).release();
  }

  @Test
  void rejectsMissingArguments() {
    final DitherAlgorithm algorithm = mock(DitherAlgorithm.class);
    final DitherResultStep result = mock(DitherResultStep.class);
    final FunctionalVideoFilter filter = DitherFilter.dither(algorithm, result);
    assertThrows(NullPointerException.class, () -> DitherFilter.dither(null, result));
    assertThrows(NullPointerException.class, () -> DitherFilter.dither(algorithm, null));
    assertThrows(NullPointerException.class, () -> filter.applyFilter(null, OriginalVideoMetadata.EMPTY));
  }

  @Test
  void algorithmsDefaultToTheMapPalette() {
    final DefaultPaletteAlgorithm algorithm = new DefaultPaletteAlgorithm();
    final DitherPalette palette = algorithm.getPalette();
    assertSame(DitherPalette.DEFAULT_MAP_PALETTE, palette);
  }

  /**
   * An algorithm that relies on the default palette of its base class.
   */
  private static final class DefaultPaletteAlgorithm extends AbstractDitherAlgorithm {

    @Override
    public byte[] ditherIntoBytes(final ImageBuffer buffer) {
      return new byte[0];
    }

    @Override
    public void dither(final int[] buffer, final int width) {
      checkBuffer(buffer, width);
    }
  }
}
