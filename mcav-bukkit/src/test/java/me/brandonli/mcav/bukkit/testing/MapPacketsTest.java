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
package me.brandonli.mcav.bukkit.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData.MapPatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class MapPacketsTest {

  private static final int MAP_ID = 7;
  private static final int OTHER_MAP_ID = 8;

  private static final int LEFT = 3;
  private static final int TOP = 5;
  private static final int WIDTH = 2;
  private static final int HEIGHT = 4;

  private static final int COLOR_OFFSET = 1;
  private static final int OTHER_COLOR_OFFSET = 2;

  @Test
  void shouldAcceptMatchingMapPackets() {
    final ClientboundMapItemDataPacket first = this.createMatchingPacket();
    final ClientboundMapItemDataPacket second = this.createMatchingPacket();
    final ClientboundBundlePacket bundle = new ClientboundBundlePacket(List.of(first, second));
    final List<ClientboundMapItemDataPacket> packets = MapPackets.unbundle(bundle);
    final List<ClientboundMapItemDataPacket> expected = List.of(first, second);
    assertEquals(expected, packets);

    final byte[] colors = MapPackets.pattern(WIDTH * HEIGHT, COLOR_OFFSET);
    final MapTilePatch patch = MapTilePatch.of(MAP_ID, LEFT, TOP, WIDTH, HEIGHT, colors);
    assertDoesNotThrow(() -> MapPackets.assertMapPacket(first, MAP_ID, LEFT, TOP, WIDTH, HEIGHT, colors));
    assertDoesNotThrow(() -> MapPackets.assertMapPacket(first, patch));
  }

  private ClientboundMapItemDataPacket createMatchingPacket() {
    final MapId id = new MapId(MAP_ID);
    final byte[] colors = MapPackets.pattern(WIDTH * HEIGHT, COLOR_OFFSET);
    final MapPatch patch = new MapPatch(LEFT, TOP, WIDTH, HEIGHT, colors);
    return new ClientboundMapItemDataPacket(id, (byte) 0, false, Optional.empty(), Optional.of(patch));
  }

  @ParameterizedTest
  @MethodSource("mismatchedPackets")
  void shouldFailForAMapPacketWithOneWrongField(final ClientboundMapItemDataPacket packet) {
    final byte[] colors = MapPackets.pattern(WIDTH * HEIGHT, COLOR_OFFSET);
    assertThrows(AssertionError.class, () -> MapPackets.assertMapPacket(packet, MAP_ID, LEFT, TOP, WIDTH, HEIGHT, colors));
  }

  static Stream<Arguments> mismatchedPackets() {
    final MapId id = new MapId(MAP_ID);
    final MapId otherId = new MapId(OTHER_MAP_ID);
    final byte[] colors = MapPackets.pattern(WIDTH * HEIGHT, COLOR_OFFSET);
    final byte[] otherColors = MapPackets.pattern(WIDTH * HEIGHT, OTHER_COLOR_OFFSET);
    final MapPatch patch = new MapPatch(LEFT, TOP, WIDTH, HEIGHT, colors);
    final Optional<MapPatch> colorPatch = Optional.of(patch);
    final Optional<List<MapDecoration>> noDecorations = Optional.empty();
    final Optional<List<MapDecoration>> decorations = Optional.of(List.of());
    final MapPatch otherLeft = new MapPatch(LEFT + 1, TOP, WIDTH, HEIGHT, colors);
    final MapPatch otherTop = new MapPatch(LEFT, TOP + 1, WIDTH, HEIGHT, colors);
    final MapPatch otherWidth = new MapPatch(LEFT, TOP, WIDTH + 1, HEIGHT, colors);
    final MapPatch otherHeight = new MapPatch(LEFT, TOP, WIDTH, HEIGHT + 1, colors);
    final MapPatch otherColorPatch = new MapPatch(LEFT, TOP, WIDTH, HEIGHT, otherColors);
    return Stream.of(
      Arguments.argumentSet("a different map id", new ClientboundMapItemDataPacket(otherId, (byte) 0, false, noDecorations, colorPatch)),
      Arguments.argumentSet("a nonzero scale", new ClientboundMapItemDataPacket(id, (byte) 1, false, noDecorations, colorPatch)),
      Arguments.argumentSet("a locked map", new ClientboundMapItemDataPacket(id, (byte) 0, true, noDecorations, colorPatch)),
      Arguments.argumentSet("present decorations", new ClientboundMapItemDataPacket(id, (byte) 0, false, decorations, colorPatch)),
      Arguments.argumentSet("a different x", new ClientboundMapItemDataPacket(id, (byte) 0, false, noDecorations, Optional.of(otherLeft))),
      Arguments.argumentSet("a different y", new ClientboundMapItemDataPacket(id, (byte) 0, false, noDecorations, Optional.of(otherTop))),
      Arguments.argumentSet(
        "a different width",
        new ClientboundMapItemDataPacket(id, (byte) 0, false, noDecorations, Optional.of(otherWidth))
      ),
      Arguments.argumentSet(
        "a different height",
        new ClientboundMapItemDataPacket(id, (byte) 0, false, noDecorations, Optional.of(otherHeight))
      ),
      Arguments.argumentSet(
        "different colors",
        new ClientboundMapItemDataPacket(id, (byte) 0, false, noDecorations, Optional.of(otherColorPatch))
      )
    );
  }

  @Test
  void shouldFailForAMapPacketWithoutAColorPatch() {
    final MapId id = new MapId(MAP_ID);
    final ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(id, (byte) 0, false, Optional.empty(), Optional.empty());
    final byte[] colors = MapPackets.pattern(WIDTH * HEIGHT, COLOR_OFFSET);
    assertThrows(NoSuchElementException.class, () -> MapPackets.assertMapPacket(packet, MAP_ID, LEFT, TOP, WIDTH, HEIGHT, colors));
  }

  @Test
  void shouldFailToUnbundleAPacketThatIsNotABundle() {
    final ClientboundMapItemDataPacket packet = this.createMatchingPacket();
    assertThrows(AssertionError.class, () -> MapPackets.unbundle(packet));
  }

  @Test
  void shouldFailToUnbundleABundleWithAnotherPacket() {
    final ClientboundBundlePacket child = new ClientboundBundlePacket(List.of());
    final ClientboundBundlePacket bundle = new ClientboundBundlePacket(List.of(child));
    assertThrows(AssertionError.class, () -> MapPackets.unbundle(bundle));
  }
}
