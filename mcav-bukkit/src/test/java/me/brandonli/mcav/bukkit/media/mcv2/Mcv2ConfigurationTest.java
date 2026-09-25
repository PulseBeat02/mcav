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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import me.brandonli.mcav.media.mcv2.encode.EncoderSettings;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class Mcv2ConfigurationTest {

  private static final World WORLD = mock(World.class);
  private static final List<UUID> VIEWERS = List.of(UUID.randomUUID());

  static Mcv2Configuration.Builder complete() {
    return Mcv2Configuration.builder()
      .viewers(VIEWERS)
      .origin(new Location(WORLD, 10.7, 64.2, -3.5))
      .facing(BlockFace.SOUTH)
      .map(7)
      .columns(5)
      .rows(3);
  }

  @Test
  void fillsInTheDefaults() {
    final Mcv2Configuration configuration = complete().build();
    assertSame(VIEWERS, configuration.getViewers());
    assertEquals(new Location(WORLD, 10, 64, -4), configuration.getOrigin());
    assertNotSame(configuration.getOrigin(), configuration.getOrigin());
    assertEquals(BlockFace.SOUTH, configuration.getFacing());
    assertEquals(7, configuration.getMap());
    assertEquals(5, configuration.getColumns());
    assertEquals(3, configuration.getRows());
    assertEquals(640, configuration.getVideoWidth());
    assertEquals(384, configuration.getVideoHeight());
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP, configuration.getPageMap());
    assertEquals(4, configuration.getPageSlots());
    assertEquals(1, configuration.getStreamId());
    assertEquals(EncoderSettings.SHIP, configuration.getSettings());
    assertEquals(NamedTextColor.DARK_PURPLE, configuration.getOutlineColor());
  }

  @Test
  void keepsWhatWasSet() {
    final Mcv2Configuration configuration = complete()
      .video(320, 180)
      .pageMap(50)
      .pageSlots(2)
      .streamId(0xFFFFFFFFL)
      .settings(EncoderSettings.LOW_BANDWIDTH)
      .outlineColor(NamedTextColor.AQUA)
      .build();
    assertEquals(320, configuration.getVideoWidth());
    assertEquals(180, configuration.getVideoHeight());
    assertEquals(50, configuration.getPageMap());
    assertEquals(2, configuration.getPageSlots());
    assertEquals(0xFFFFFFFFL, configuration.getStreamId());
    assertEquals(EncoderSettings.LOW_BANDWIDTH, configuration.getSettings());
    assertEquals(NamedTextColor.AQUA, configuration.getOutlineColor());
  }

  @Test
  void usesOnePageSlotPerMapOfASmallWall() {
    assertEquals(1, complete().columns(1).rows(1).build().getPageSlots());
    assertEquals(3, complete().columns(3).rows(1).build().getPageSlots());
  }

  @ParameterizedTest
  @CsvSource({ "SOUTH, EAST, 0", "WEST, SOUTH, 1", "NORTH, WEST, 2", "EAST, NORTH, 3" })
  void knowsWhereTheMapsRightEdgePoints(final BlockFace facing, final BlockFace right, final int code) {
    final Mcv2Configuration configuration = complete().facing(facing).build();
    assertEquals(right, configuration.getRight());
    assertEquals(code, configuration.getFacingCode());
  }

  private static void refuses(final Consumer<Mcv2Configuration.Builder> change) {
    final Mcv2Configuration.Builder builder = complete();
    assertThrows(RuntimeException.class, () -> {
      change.accept(builder);
      builder.build();
    });
  }

  @Test
  void refusesMissingOrInvalidValues() {
    assertThrows(NullPointerException.class, () ->
      Mcv2Configuration.builder().origin(new Location(WORLD, 0, 0, 0)).facing(BlockFace.SOUTH).map(0).columns(1).rows(1).build()
    );
    assertThrows(NullPointerException.class, () ->
      Mcv2Configuration.builder().viewers(VIEWERS).facing(BlockFace.SOUTH).map(0).columns(1).rows(1).build()
    );
    assertThrows(NullPointerException.class, () ->
      Mcv2Configuration.builder().viewers(VIEWERS).origin(new Location(WORLD, 0, 0, 0)).map(0).columns(1).rows(1).build()
    );
    refuses(builder -> builder.origin(new Location(null, 0, 0, 0)));
    refuses(builder -> builder.facing(BlockFace.UP));
    refuses(builder -> builder.facing(BlockFace.NORTH_EAST));
    refuses(builder -> builder.map(-1));
    refuses(builder -> builder.columns(0));
    refuses(builder -> builder.columns(Mcv2Configuration.MAX_SIDE + 1));
    refuses(builder -> builder.rows(0));
    refuses(builder -> builder.rows(Mcv2Configuration.MAX_SIDE + 1));
    refuses(builder -> builder.video(-1, 10));
    refuses(builder -> builder.video(4097, 10));
    refuses(builder -> builder.video(10, -1));
    refuses(builder -> builder.video(10, 4097));
    refuses(builder -> builder.pageSlots(-1));
    refuses(builder -> builder.pageSlots(Mcv2Configuration.MAX_PAGE_SLOTS + 1));
    refuses(builder -> builder.streamId(-1));
    refuses(builder -> builder.streamId(1L << 32));
    refuses(builder -> builder.pageMap(-1));
    refuses(builder -> builder.pageMap(Integer.MAX_VALUE));
    refuses(builder -> builder.map(Integer.MAX_VALUE));
    refuses(builder -> builder.outlineColor(NamedTextColor.BLACK));
  }

  @Test
  void keepsThePageMapsOffTheWall() {
    // the wall is maps 7 to 21
    refuses(builder -> builder.pageMap(21));
    refuses(builder -> builder.pageMap(4));
    assertEquals(3, complete().pageMap(3).build().getPageMap());
    assertEquals(22, complete().pageMap(22).build().getPageMap());
  }
}
