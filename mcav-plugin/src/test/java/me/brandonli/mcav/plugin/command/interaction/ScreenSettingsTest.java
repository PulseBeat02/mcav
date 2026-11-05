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
package me.brandonli.mcav.plugin.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.google.common.base.MoreObjects;
import java.util.Objects;
import me.brandonli.mcav.plugin.testing.EqualityAssertions;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ScreenSettingsTest {

  private static final Pair<Integer, Integer> BLOCKS = Pair.pair(5, 3);
  private static final Pair<Integer, Integer> RESOLUTION = Pair.pair(640, 384);

  private static final int MAP_ID = 12;

  private CommandSender sender;
  private MultiplePlayerSelector viewers;
  private ScreenSettings settings;

  @BeforeEach
  void setUp() {
    this.sender = mock(CommandSender.class);
    this.viewers = mock(MultiplePlayerSelector.class);
    this.settings = new ScreenSettings(this.sender, this.viewers, BLOCKS, RESOLUTION, MAP_ID, DitheringArgument.ATKINSON, MapCodec.MCV2);
  }

  @Test
  void shouldKeepEverySetting() {
    final MultiplePlayerSelector keptViewers = this.settings.getViewers();
    final Pair<Integer, Integer> keptBlocks = this.settings.getBlocks();
    final Pair<Integer, Integer> keptResolution = this.settings.getResolution();
    final int mapId = this.settings.getMapId();
    final DitheringArgument dithering = this.settings.getDithering();
    assertSame(this.viewers, keptViewers);
    assertSame(BLOCKS, keptBlocks);
    assertSame(RESOLUTION, keptResolution);
    assertEquals(MAP_ID, mapId);
    assertSame(DitheringArgument.ATKINSON, dithering);

    final CommandSender settingsSender = this.settings.getSender();
    assertSame(this.sender, settingsSender);

    final MapCodec codec = this.settings.getCodec();
    assertSame(MapCodec.MCV2, codec);
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final ScreenSettings equalSettings = this.createSettings(this.sender, this.viewers);
    final CommandSender otherSender = mock(CommandSender.class);
    final ScreenSettings otherSenderSettings = this.createSettings(otherSender, this.viewers);
    final MultiplePlayerSelector otherViewers = mock(MultiplePlayerSelector.class);
    final ScreenSettings otherViewersSettings = this.createSettings(this.sender, otherViewers);
    final ScreenSettings otherBlocks = new ScreenSettings(
      this.sender,
      this.viewers,
      RESOLUTION,
      RESOLUTION,
      MAP_ID,
      DitheringArgument.ATKINSON,
      MapCodec.MCV2
    );
    final ScreenSettings otherResolution = new ScreenSettings(
      this.sender,
      this.viewers,
      BLOCKS,
      BLOCKS,
      MAP_ID,
      DitheringArgument.ATKINSON,
      MapCodec.MCV2
    );
    final ScreenSettings otherMapId = new ScreenSettings(
      this.sender,
      this.viewers,
      BLOCKS,
      RESOLUTION,
      MAP_ID + 1,
      DitheringArgument.ATKINSON,
      MapCodec.MCV2
    );
    final ScreenSettings otherDithering = new ScreenSettings(
      this.sender,
      this.viewers,
      BLOCKS,
      RESOLUTION,
      MAP_ID,
      DitheringArgument.NEAREST_COLOR,
      MapCodec.MCV2
    );
    final ScreenSettings otherCodec = new ScreenSettings(
      this.sender,
      this.viewers,
      BLOCKS,
      RESOLUTION,
      MAP_ID,
      DitheringArgument.ATKINSON,
      MapCodec.DITHER
    );
    EqualityAssertions.assertEqualityContract(
      this.settings,
      equalSettings,
      otherSenderSettings,
      otherViewersSettings,
      otherBlocks,
      otherResolution,
      otherMapId,
      otherDithering,
      otherCodec
    );
  }

  @Test
  void shouldHashEverySetting() {
    final int hash = this.settings.hashCode();
    final int expected = Objects.hash(this.sender, this.viewers, BLOCKS, RESOLUTION, MAP_ID, DitheringArgument.ATKINSON, MapCodec.MCV2);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRenderEverySetting() {
    final String text = this.settings.toString();
    final String expected = MoreObjects.toStringHelper("ScreenSettings")
      .add("sender", this.sender)
      .add("viewers", this.viewers)
      .add("blocks", BLOCKS)
      .add("resolution", RESOLUTION)
      .add("mapId", MAP_ID)
      .add("dithering", DitheringArgument.ATKINSON)
      .add("codec", MapCodec.MCV2)
      .toString();
    assertEquals(expected, text);
  }

  private ScreenSettings createSettings(final CommandSender sender, final MultiplePlayerSelector viewers) {
    return new ScreenSettings(sender, viewers, BLOCKS, RESOLUTION, MAP_ID, DitheringArgument.ATKINSON, MapCodec.MCV2);
  }
}
