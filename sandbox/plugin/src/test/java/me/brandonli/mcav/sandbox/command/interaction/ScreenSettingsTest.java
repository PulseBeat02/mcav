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
package me.brandonli.mcav.sandbox.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ScreenSettings}.
 */
final class ScreenSettingsTest {

  @Test
  void keepsEverySetting() {
    final MultiplePlayerSelector viewers = mock(MultiplePlayerSelector.class);
    final Pair<Integer, Integer> blocks = Pair.pair(5, 3);
    final Pair<Integer, Integer> resolution = Pair.pair(640, 384);
    final ScreenSettings settings = new ScreenSettings(viewers, blocks, resolution, 12, DitheringArgument.ATKINSON);
    final MultiplePlayerSelector keptViewers = settings.getViewers();
    final Pair<Integer, Integer> keptBlocks = settings.getBlocks();
    final Pair<Integer, Integer> keptResolution = settings.getResolution();
    final int mapId = settings.getMapId();
    final DitheringArgument dithering = settings.getDithering();
    assertSame(viewers, keptViewers);
    assertSame(blocks, keptBlocks);
    assertSame(resolution, keptResolution);
    assertEquals(12, mapId);
    assertSame(DitheringArgument.ATKINSON, dithering);
  }
}
