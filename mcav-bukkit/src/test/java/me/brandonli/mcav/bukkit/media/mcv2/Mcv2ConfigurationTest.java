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
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
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
    assertEquals(Mcv2Configuration.MAX_PAGE_SLOTS, configuration.getPageSlots());
    assertEquals(1, configuration.getStreamId());
    assertEquals(Settings.DEFAULT, configuration.getSettings());
    assertEquals(NamedTextColor.DARK_PURPLE, configuration.getOutlineColor());
    assertEquals(Mcv2Configuration.DEFAULT_BACKLOG_LIMIT, configuration.getBacklogLimit());
    assertEquals(Mcv2Configuration.DEFAULT_UNSENT_LIMIT, configuration.getUnsentLimit());
    assertEquals(32 * 1024, Mcv2Configuration.DEFAULT_UNSENT_LIMIT);
  }

  @Test
  void copiesItselfAtAnotherVideoSize() {
    try (final Pool budget = new Pool(1)) {
      final Mcv2Configuration original = complete()
        .video(320, 180)
        .pageSlots(2)
        .streamId(7)
        .firstFrameId(99)
        .settings(Settings.DEFAULT)
        .outlineColor(NamedTextColor.AQUA)
        .backlogLimit(1000)
        .unsentLimit(2000)
        .encoderPool(budget)
        .build();
      final Mcv2Configuration smaller = original.withVideo(160, 90);
      assertEquals(160, smaller.getVideoWidth());
      assertEquals(90, smaller.getVideoHeight());
      assertEquals(original.getViewers(), smaller.getViewers());
      assertEquals(original.getOrigin(), smaller.getOrigin());
      assertEquals(original.getFacing(), smaller.getFacing());
      assertEquals(original.getMap(), smaller.getMap());
      assertEquals(original.getColumns(), smaller.getColumns());
      assertEquals(original.getRows(), smaller.getRows());
      assertEquals(original.getPageMap(), smaller.getPageMap());
      assertEquals(2, smaller.getPageSlots());
      assertEquals(7, smaller.getStreamId());
      assertEquals(99, smaller.getFirstFrameId());
      assertEquals(Settings.DEFAULT, smaller.getSettings());
      assertEquals(NamedTextColor.AQUA, smaller.getOutlineColor());
      assertEquals(1000, smaller.getBacklogLimit());
      assertEquals(2000, smaller.getUnsentLimit());
      assertSame(budget, smaller.getEncoderPool());
      // without a budget of its own, the copy uses the server's too
      assertSame(Pool.shared(), complete().build().withVideo(64, 64).getEncoderPool());
      assertThrows(IllegalArgumentException.class, () -> original.withVideo(0, 90));
      assertThrows(IllegalArgumentException.class, () -> original.withVideo(160, 0));
      assertThrows(IllegalArgumentException.class, () -> original.withVideo(5000, 90));
    }
  }

  @Test
  void showsThirtyFramesASecondUnlessToldOtherwise() {
    assertEquals(Mcv2Configuration.DEFAULT_MAX_FRAME_RATE, complete().build().getMaxFrameRate());
    assertEquals(30, Mcv2Configuration.DEFAULT_MAX_FRAME_RATE);
    final Mcv2Configuration sixty = complete().maxFrameRate(60).build();
    assertEquals(60, sixty.getMaxFrameRate());
    assertEquals(60, sixty.withVideo(64, 64).getMaxFrameRate());
    assertEquals(60, sixty.withSlot(2, Mcv2Configuration.DEFAULT_PAGE_MAP, 0).getMaxFrameRate());
    assertEquals(0, complete().maxFrameRate(0).build().getMaxFrameRate());
    assertEquals(Mcv2Configuration.MAX_FRAME_RATE, complete().maxFrameRate(Mcv2Configuration.MAX_FRAME_RATE).build().getMaxFrameRate());
    assertThrows(IllegalArgumentException.class, () -> complete().maxFrameRate(-1).build());
    assertThrows(IllegalArgumentException.class, () ->
      complete()
        .maxFrameRate(Mcv2Configuration.MAX_FRAME_RATE + 0.5)
        .build()
    );
  }

  @Test
  void copiesItselfWithTheStreamASlotGaveIt() {
    final Mcv2Configuration original = complete().video(320, 180).streamId(7).build();
    final Mcv2Configuration slotted = original.withSlot(3, Mcv2Configuration.DEFAULT_PAGE_MAP + 16, Mcv2Decoder.MAX_U32);
    assertEquals(3, slotted.getStreamId());
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP + 16, slotted.getPageMap());
    assertEquals(Mcv2Decoder.MAX_U32, slotted.getFirstFrameId());
    assertEquals(320, slotted.getVideoWidth());
    assertSame(original.getViewers(), slotted.getViewers());
    assertEquals(0, original.getFirstFrameId());
    assertThrows(IllegalArgumentException.class, () ->
      original.withSlot(Mcv2Configuration.MAX_STREAM_ID + 1, Mcv2Configuration.DEFAULT_PAGE_MAP, 0)
    );
    assertThrows(IllegalArgumentException.class, () -> original.withSlot(1, Mcv2Configuration.DEFAULT_PAGE_MAP, -1));
    assertThrows(IllegalArgumentException.class, () -> original.withSlot(1, Mcv2Configuration.DEFAULT_PAGE_MAP, Mcv2Decoder.MAX_U32 + 1));
  }

  @Test
  void keepsWhatWasSet() {
    final Mcv2Configuration configuration = complete()
      .video(320, 180)
      .pageMap(50)
      .pageSlots(2)
      .streamId(Mcv2Configuration.MAX_STREAM_ID)
      .settings(Settings.DEFAULT)
      .outlineColor(NamedTextColor.AQUA)
      .backlogLimit(0)
      .unsentLimit(0)
      .build();
    assertEquals(320, configuration.getVideoWidth());
    assertEquals(180, configuration.getVideoHeight());
    assertEquals(50, configuration.getPageMap());
    assertEquals(2, configuration.getPageSlots());
    assertEquals(Mcv2Configuration.MAX_STREAM_ID, configuration.getStreamId());
    assertEquals(Settings.DEFAULT, configuration.getSettings());
    assertEquals(NamedTextColor.AQUA, configuration.getOutlineColor());
    assertEquals(0, configuration.getBacklogLimit());
    assertEquals(0, configuration.getUnsentLimit());
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

  private static void refuses(final String message, final Consumer<Mcv2Configuration.Builder> change) {
    final Mcv2Configuration.Builder builder = complete();
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
      change.accept(builder);
      builder.build();
    });
    assertEquals(message, failure.getMessage());
  }

  @Test
  void refusesMissingOrInvalidValues() {
    assertThrows(NullPointerException.class, () ->
      Mcv2Configuration.builder()
        .origin(new Location(WORLD, 0, 0, 0))
        .facing(BlockFace.SOUTH)
        .map(0)
        .columns(1)
        .rows(1)
        .build()
    );
    assertThrows(NullPointerException.class, () ->
      Mcv2Configuration.builder().viewers(VIEWERS).facing(BlockFace.SOUTH).map(0).columns(1).rows(1).build()
    );
    assertThrows(NullPointerException.class, () ->
      Mcv2Configuration.builder()
        .viewers(VIEWERS)
        .origin(new Location(WORLD, 0, 0, 0))
        .map(0)
        .columns(1)
        .rows(1)
        .build()
    );
    refuses("Origin must be in a world", builder -> builder.origin(new Location(null, 0, 0, 0)));
    refuses("Frames must face a horizontal direction: UP", builder -> builder.facing(BlockFace.UP));
    refuses("Frames must face a horizontal direction: NORTH_EAST", builder -> builder.facing(BlockFace.NORTH_EAST));
    refuses("Map id must be set and non-negative", builder -> builder.map(-1));
    refuses("Columns must be 1 to 63", builder -> builder.columns(0));
    refuses("Columns must be 1 to 63", builder -> builder.columns(Mcv2Configuration.MAX_SIDE + 1));
    refuses("Rows must be 1 to 63", builder -> builder.rows(0));
    refuses("Rows must be 1 to 63", builder -> builder.rows(Mcv2Configuration.MAX_SIDE + 1));
    refuses("Video width must be 0 to 4096", builder -> builder.video(-1, 10));
    refuses("Video width must be 0 to 4096", builder -> builder.video(4097, 10));
    refuses("Video height must be 0 to 4096", builder -> builder.video(10, -1));
    refuses("Video height must be 0 to 4096", builder -> builder.video(10, 4097));
    refuses("Page slots must be 0 to 8", builder -> builder.pageSlots(-1));
    refuses("Page slots must be 0 to 8", builder -> builder.pageSlots(Mcv2Configuration.MAX_PAGE_SLOTS + 1));
    refuses("Stream id must be 0 to 4095", builder -> builder.streamId(-1));
    refuses("Stream id must be 0 to 4095", builder -> builder.streamId(Mcv2Configuration.MAX_STREAM_ID + 1));
    refuses("Backlog limit must not be negative", builder -> builder.backlogLimit(-1));
    refuses("Unsent limit must not be negative", builder -> builder.unsentLimit(-1));
    refuses("Page map ids must be non-negative ints", builder -> builder.pageMap(-1));
    refuses("Page map ids must be non-negative ints", builder -> builder.pageMap(Integer.MAX_VALUE));
    refuses("Map ids exceed the integer range", builder -> builder.map(Integer.MAX_VALUE));
    refuses("The outline colour must not be black", builder -> builder.outlineColor(NamedTextColor.BLACK));
  }

  @Test
  void acceptsEveryValueAtTheEdgesOfItsRanges() {
    // the smallest and the largest value of every range, each one step inside the values refused above
    assertEquals(0, complete().map(0).build().getMap());
    assertEquals(1, complete().columns(1).build().getColumns());
    // a side of 63 maps is 8064 pixels at its native size, so it plays a video the codec can take
    assertEquals(Mcv2Configuration.MAX_SIDE, complete().columns(Mcv2Configuration.MAX_SIDE).video(4096, 0).build().getColumns());
    assertEquals(1, complete().rows(1).build().getRows());
    assertEquals(Mcv2Configuration.MAX_SIDE, complete().rows(Mcv2Configuration.MAX_SIDE).video(0, 4096).build().getRows());
    // 0 is the wall's own size, 128 pixels per map
    assertEquals(5 * 128, complete().video(0, 0).build().getVideoWidth());
    assertEquals(3 * 128, complete().video(0, 0).build().getVideoHeight());
    assertEquals(4096, complete().video(4096, 4096).build().getVideoWidth());
    assertEquals(4096, complete().video(4096, 4096).build().getVideoHeight());
    assertEquals(Mcv2Configuration.MAX_PAGE_SLOTS, complete().pageSlots(0).build().getPageSlots());
    assertEquals(Mcv2Configuration.MAX_PAGE_SLOTS, complete().pageSlots(Mcv2Configuration.MAX_PAGE_SLOTS).build().getPageSlots());
    assertEquals(0, complete().streamId(0).build().getStreamId());
    assertEquals(Mcv2Configuration.MAX_STREAM_ID, complete().streamId(Mcv2Configuration.MAX_STREAM_ID).build().getStreamId());
    assertEquals(0, complete().backlogLimit(0).build().getBacklogLimit());
    assertEquals(0, complete().unsentLimit(0).build().getUnsentLimit());
    // the page maps from 0, and up to the last int; the wall's maps up to the last int
    assertEquals(0, complete().pageSlots(4).pageMap(0).build().getPageMap());
    assertEquals(
      Integer.MAX_VALUE - 3,
      complete()
        .pageSlots(4)
        .pageMap(Integer.MAX_VALUE - 3)
        .build()
        .getPageMap()
    );
    assertEquals(
      Integer.MAX_VALUE - (Mcv2Configuration.MAX_PAGE_SLOTS - 1),
      complete()
        .pageMap(Integer.MAX_VALUE - (Mcv2Configuration.MAX_PAGE_SLOTS - 1))
        .build()
        .getPageMap()
    );
    assertEquals(
      Integer.MAX_VALUE - 14,
      complete()
        .map(Integer.MAX_VALUE - 14)
        .pageMap(0)
        .build()
        .getMap()
    );
    // a copy at the smallest video size, and none below it
    final Mcv2Configuration configuration = complete().build();
    assertEquals(1, configuration.withVideo(1, 1).getVideoWidth());
    assertEquals(1, configuration.withVideo(1, 1).getVideoHeight());
    assertThrows(IllegalArgumentException.class, () -> configuration.withVideo(0, 1));
    assertThrows(IllegalArgumentException.class, () -> configuration.withVideo(1, 0));
  }

  @Test
  void keepsThePageMapsOffTheWall() {
    // the wall is maps 7 to 21; four page slots end below it at 3, the default eight at none
    refuses("Page maps must not be maps of the wall", builder -> builder.pageMap(21));
    refuses("Page maps must not be maps of the wall", builder -> builder.pageSlots(4).pageMap(4));
    refuses("Page maps must not be maps of the wall", builder -> builder.pageMap(0));
    assertEquals(3, complete().pageSlots(4).pageMap(3).build().getPageMap());
    assertEquals(22, complete().pageMap(22).build().getPageMap());
  }

  @Test
  void refusesAWallWhoseNativeSizeIsLargerThanTheCodecTakes() {
    // 33 maps are 4224 pixels at 128 a map
    final IllegalArgumentException wide = assertThrows(IllegalArgumentException.class, () -> complete().columns(33).build());
    assertEquals("A wall of 33 by 3 maps is 4224 by 384 pixels, more than the codec's 4096: set a smaller video size", wide.getMessage());
    assertThrows(IllegalArgumentException.class, () -> complete().rows(33).build());
    // the same walls with a video size the codec takes, and the largest wall at its native size
    assertEquals(4096, complete().columns(33).video(4096, 0).build().getVideoWidth());
    assertEquals(4096, complete().rows(33).video(0, 4096).build().getVideoHeight());
    assertEquals(4096, complete().columns(32).rows(32).build().getVideoWidth());
  }

  @Test
  void refusesMorePageSlotsThanTheWallHasMaps() {
    final Mcv2Configuration.Builder square = complete().columns(2).rows(2);
    assertEquals(4, square.pageSlots(0).build().getPageSlots(), "the default of a wall of four maps");
    assertEquals(4, square.pageSlots(4).build().getPageSlots());
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> square.pageSlots(5).build());
    assertEquals("A wall of 4 maps cannot carry 5 page slots: one page frame hangs behind each map", refused.getMessage());
  }
}
