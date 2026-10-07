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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ForkJoinPool;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Records whether an admitted live search can actually encode its first keyframe. */
final class LiveSearchModesDefectTest {

  @Disabled("OPEN defect: DR-034 admitted mode masks cannot encode their first keyframe")
  @Test
  void anAdmittedSearchCanEncodeItsFirstKeyframe() {
    final LiveSearch search = new LiveSearch(8, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 1, false, 8, true, 0, 0, false);
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2Encoder encoder = new Mcv2Encoder(EncoderSettings.LIVE.withLive(search), pool, 1, true, JavaKernels.FACTORY);
      final byte[] frame = assertDoesNotThrow(() -> encoder.encode(new byte[] { 10, 20, 30 }, 1, 1, 0));
      assertTrue(frame.length > 0);
    }
  }
}
