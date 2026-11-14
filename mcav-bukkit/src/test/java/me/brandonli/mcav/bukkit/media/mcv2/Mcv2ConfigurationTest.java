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

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
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

  private static final int WALL_MAP = 7;
  private static final int COLUMNS = 5;
  private static final int ROWS = 3;

  private static final int STREAM_ID = 7;

  private static final int VIDEO_WIDTH = 320;
  private static final int VIDEO_HEIGHT = 180;

  private static final int MAXIMUM_VIDEO_SIDE = 4096;

  static Mcv2ConfigurationBuilder complete() {
    return Mcv2Configuration.builder()
      .viewers(VIEWERS)
      .origin(new Location(WORLD, 10.7, 64.2, -3.5))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(COLUMNS)
      .rows(ROWS);
  }

  @Test
  void shouldFillInTheDefaults() {
    final Mcv2Configuration configuration = complete().build();

    final Collection<UUID> viewers = configuration.getViewers();
    assertSame(VIEWERS, viewers);

    final Location expectedOrigin = new Location(WORLD, 10, 64, -4);
    final Location origin = configuration.getOrigin();
    assertEquals(expectedOrigin, origin);

    final Location firstOrigin = configuration.getOrigin();
    final Location secondOrigin = configuration.getOrigin();
    assertNotSame(firstOrigin, secondOrigin);

    final BlockFace facing = configuration.getFacing();
    assertEquals(BlockFace.SOUTH, facing);

    final int map = configuration.getMap();
    assertEquals(WALL_MAP, map);

    final int columns = configuration.getColumns();
    assertEquals(COLUMNS, columns);

    final int rows = configuration.getRows();
    assertEquals(ROWS, rows);

    final int videoWidth = configuration.getVideoWidth();
    assertEquals(640, videoWidth);

    final int videoHeight = configuration.getVideoHeight();
    assertEquals(384, videoHeight);

    final int pageMap = configuration.getPageMap();
    assertEquals(Mcv2Configuration.DEFAULT_PAGE_MAP, pageMap);

    final int pageSlots = configuration.getPageSlots();
    assertEquals(Mcv2Configuration.MAXIMUM_PAGE_SLOTS, pageSlots);

    final long streamId = configuration.getStreamId();
    assertEquals(1, streamId);

    final Settings settings = configuration.getSettings();
    assertEquals(Settings.DEFAULT, settings);

    final NamedTextColor outlineColor = configuration.getOutlineColor();
    assertEquals(NamedTextColor.DARK_PURPLE, outlineColor);

    final long backlogLimit = configuration.getBacklogLimit();
    assertEquals(Mcv2Configuration.DEFAULT_BACKLOG_LIMIT, backlogLimit);

    final int unsentLimit = configuration.getUnsentLimit();
    assertEquals(Mcv2Configuration.DEFAULT_UNSENT_LIMIT, unsentLimit);

    final int expectedDefaultUnsentLimit = 32 * 1024;
    assertEquals(expectedDefaultUnsentLimit, Mcv2Configuration.DEFAULT_UNSENT_LIMIT);
  }

  @Test
  void shouldCopyItselfAtAnotherVideoSize() {
    try (final Pool budget = new Pool(1)) {
      final Mcv2Configuration original = complete()
        .video(VIDEO_WIDTH, VIDEO_HEIGHT)
        .pageSlots(2)
        .streamId(STREAM_ID)
        .firstFrameId(99)
        .settings(Settings.DEFAULT)
        .outlineColor(NamedTextColor.AQUA)
        .backlogLimit(1000)
        .unsentLimit(2000)
        .encoderPool(budget)
        .build();
      final Mcv2Configuration smaller = original.withVideo(160, 90);

      final int videoWidth = smaller.getVideoWidth();
      assertEquals(160, videoWidth);

      final int videoHeight = smaller.getVideoHeight();
      assertEquals(90, videoHeight);

      final Collection<UUID> expectedViewers = original.getViewers();
      final Collection<UUID> viewers = smaller.getViewers();
      assertEquals(expectedViewers, viewers);

      final Location expectedOrigin = original.getOrigin();
      final Location origin = smaller.getOrigin();
      assertEquals(expectedOrigin, origin);

      final BlockFace expectedFacing = original.getFacing();
      final BlockFace facing = smaller.getFacing();
      assertEquals(expectedFacing, facing);

      final int expectedMap = original.getMap();
      final int map = smaller.getMap();
      assertEquals(expectedMap, map);

      final int expectedColumns = original.getColumns();
      final int columns = smaller.getColumns();
      assertEquals(expectedColumns, columns);

      final int expectedRows = original.getRows();
      final int rows = smaller.getRows();
      assertEquals(expectedRows, rows);

      final int expectedPageMap = original.getPageMap();
      final int pageMap = smaller.getPageMap();
      assertEquals(expectedPageMap, pageMap);

      final int pageSlots = smaller.getPageSlots();
      assertEquals(2, pageSlots);

      final long streamId = smaller.getStreamId();
      assertEquals(STREAM_ID, streamId);

      final long firstFrameId = smaller.getFirstFrameId();
      assertEquals(99, firstFrameId);

      final Settings settings = smaller.getSettings();
      assertEquals(Settings.DEFAULT, settings);

      final NamedTextColor outlineColor = smaller.getOutlineColor();
      assertEquals(NamedTextColor.AQUA, outlineColor);

      final long backlogLimit = smaller.getBacklogLimit();
      assertEquals(1000, backlogLimit);

      final int unsentLimit = smaller.getUnsentLimit();
      assertEquals(2000, unsentLimit);

      final Pool encoderPool = smaller.getEncoderPool();
      assertSame(budget, encoderPool);

      final Pool expectedEncoderPool = Pool.shared();
      final Pool defaultEncoderPool = complete().build().withVideo(64, 64).getEncoderPool();
      assertSame(expectedEncoderPool, defaultEncoderPool);

      final IllegalArgumentException noWidthException = assertThrows(IllegalArgumentException.class, () -> original.withVideo(0, 90));
      final IllegalArgumentException noHeightException = assertThrows(IllegalArgumentException.class, () -> original.withVideo(160, 0));
      final IllegalArgumentException tooWideException = assertThrows(IllegalArgumentException.class, () -> original.withVideo(5000, 90));
      final String noWidthMessage = noWidthException.getMessage();
      final String noHeightMessage = noHeightException.getMessage();
      final String tooWideMessage = tooWideException.getMessage();
      assertEquals("Video size must be positive!", noWidthMessage);
      assertEquals("Video size must be positive!", noHeightMessage);
      assertEquals("Video width must be between 0 and 4096 inclusive!", tooWideMessage);
    }
  }

  @Test
  void shouldShowThirtyFramesASecondUnlessToldOtherwise() {
    final double defaultMaximumFrameRate = complete().build().getMaximumFrameRate();
    assertEquals(Mcv2Configuration.DEFAULT_MAXIMUM_FRAME_RATE, defaultMaximumFrameRate);
    assertEquals(30, Mcv2Configuration.DEFAULT_MAXIMUM_FRAME_RATE);

    final Mcv2Configuration sixty = complete().maximumFrameRate(60).build();

    final double maximumFrameRate = sixty.getMaximumFrameRate();
    assertEquals(60, maximumFrameRate);

    final double resizedMaximumFrameRate = sixty.withVideo(64, 64).getMaximumFrameRate();
    assertEquals(60, resizedMaximumFrameRate);

    final double slottedMaximumFrameRate = sixty.withSlot(2, Mcv2Configuration.DEFAULT_PAGE_MAP, 0).getMaximumFrameRate();
    assertEquals(60, slottedMaximumFrameRate);

    final double zeroMaximumFrameRate = complete().maximumFrameRate(0).build().getMaximumFrameRate();
    assertEquals(0, zeroMaximumFrameRate);

    final double highestMaximumFrameRate = complete().maximumFrameRate(Mcv2Configuration.MAXIMUM_FRAME_RATE).build().getMaximumFrameRate();
    assertEquals(Mcv2Configuration.MAXIMUM_FRAME_RATE, highestMaximumFrameRate);

    final IllegalArgumentException negativeRateException = assertThrows(IllegalArgumentException.class, () ->
      complete().maximumFrameRate(-1).build()
    );
    final IllegalArgumentException tooFastRateException = assertThrows(IllegalArgumentException.class, () ->
      complete()
        .maximumFrameRate(Mcv2Configuration.MAXIMUM_FRAME_RATE + 0.5)
        .build()
    );
    final IllegalArgumentException nanRateException = assertThrows(IllegalArgumentException.class, () ->
      complete().maximumFrameRate(Double.NaN).build()
    );
    final String negativeRateMessage = negativeRateException.getMessage();
    final String tooFastRateMessage = tooFastRateException.getMessage();
    final String nanRateMessage = nanRateException.getMessage();
    final String rateExpected = "Frame rate must be between 0 and %s inclusive!".formatted(Mcv2Configuration.MAXIMUM_FRAME_RATE);
    assertEquals(rateExpected, negativeRateMessage);
    assertEquals(rateExpected, tooFastRateMessage);
    assertEquals(rateExpected, nanRateMessage);
  }

  @Test
  void shouldCopyItselfWithTheStreamASlotGaveIt() {
    final Mcv2Configuration original = complete().video(VIDEO_WIDTH, VIDEO_HEIGHT).streamId(STREAM_ID).build();
    final Mcv2Configuration slotted = original.withSlot(3, Mcv2Configuration.DEFAULT_PAGE_MAP + 16, Mcv2Decoder.MAXIMUM_UNSIGNED_INT);

    final long streamId = slotted.getStreamId();
    assertEquals(3, streamId);

    final int expectedPageMap = Mcv2Configuration.DEFAULT_PAGE_MAP + 16;
    final int pageMap = slotted.getPageMap();
    assertEquals(expectedPageMap, pageMap);

    final long firstFrameId = slotted.getFirstFrameId();
    assertEquals(Mcv2Decoder.MAXIMUM_UNSIGNED_INT, firstFrameId);

    final int videoWidth = slotted.getVideoWidth();
    assertEquals(VIDEO_WIDTH, videoWidth);

    final Collection<UUID> expectedViewers = original.getViewers();
    final Collection<UUID> viewers = slotted.getViewers();
    assertSame(expectedViewers, viewers);

    final long originalFirstFrameId = original.getFirstFrameId();
    assertEquals(0, originalFirstFrameId);

    final IllegalArgumentException tooLargeStreamException = assertThrows(IllegalArgumentException.class, () ->
      original.withSlot(Mcv2Configuration.MAXIMUM_STREAM_ID + 1, Mcv2Configuration.DEFAULT_PAGE_MAP, 0)
    );
    final IllegalArgumentException negativeFrameException = assertThrows(IllegalArgumentException.class, () ->
      original.withSlot(1, Mcv2Configuration.DEFAULT_PAGE_MAP, -1)
    );
    final IllegalArgumentException tooLargeFrameException = assertThrows(IllegalArgumentException.class, () ->
      original.withSlot(1, Mcv2Configuration.DEFAULT_PAGE_MAP, Mcv2Decoder.MAXIMUM_UNSIGNED_INT + 1)
    );
    final String tooLargeStreamMessage = tooLargeStreamException.getMessage();
    final String negativeFrameMessage = negativeFrameException.getMessage();
    final String tooLargeFrameMessage = tooLargeFrameException.getMessage();
    assertEquals("Stream id must be between 0 and 4095 inclusive!", tooLargeStreamMessage);
    assertEquals("First frame id must be an unsigned 32-bit value!", negativeFrameMessage);
    assertEquals("First frame id must be an unsigned 32-bit value!", tooLargeFrameMessage);
  }

  @Test
  void shouldKeepWhatWasSet() {
    final Mcv2Configuration configuration = complete()
      .video(VIDEO_WIDTH, VIDEO_HEIGHT)
      .pageMap(50)
      .pageSlots(2)
      .streamId(Mcv2Configuration.MAXIMUM_STREAM_ID)
      .settings(Settings.DEFAULT)
      .outlineColor(NamedTextColor.AQUA)
      .backlogLimit(0)
      .unsentLimit(0)
      .build();

    final int videoWidth = configuration.getVideoWidth();
    assertEquals(VIDEO_WIDTH, videoWidth);

    final int videoHeight = configuration.getVideoHeight();
    assertEquals(VIDEO_HEIGHT, videoHeight);

    final int pageMap = configuration.getPageMap();
    assertEquals(50, pageMap);

    final int pageSlots = configuration.getPageSlots();
    assertEquals(2, pageSlots);

    final long streamId = configuration.getStreamId();
    assertEquals(Mcv2Configuration.MAXIMUM_STREAM_ID, streamId);

    final Settings settings = configuration.getSettings();
    assertEquals(Settings.DEFAULT, settings);

    final NamedTextColor outlineColor = configuration.getOutlineColor();
    assertEquals(NamedTextColor.AQUA, outlineColor);

    final long backlogLimit = configuration.getBacklogLimit();
    assertEquals(0, backlogLimit);

    final int unsentLimit = configuration.getUnsentLimit();
    assertEquals(0, unsentLimit);
  }

  @Test
  void shouldUseOnePageSlotPerMapOfASmallWall() {
    final int singleMapPageSlots = complete().columns(1).rows(1).build().getPageSlots();
    assertEquals(1, singleMapPageSlots);

    final int threeMapPageSlots = complete().columns(3).rows(1).build().getPageSlots();
    assertEquals(3, threeMapPageSlots);
  }

  @ParameterizedTest
  @CsvSource({ "SOUTH, EAST, 0", "WEST, SOUTH, 1", "NORTH, WEST, 2", "EAST, NORTH, 3" })
  void shouldKnowWhereTheMapsRightEdgePoints(final BlockFace facing, final BlockFace right, final int code) {
    final Mcv2Configuration configuration = complete().facing(facing).build();

    final BlockFace rightEdge = configuration.getRight();
    assertEquals(right, rightEdge);

    final int facingCode = configuration.getFacingCode();
    assertEquals(code, facingCode);
  }

  @Test
  void shouldFailWhenTheFacingIsNotAWallDirection() {
    final DefaultMcv2ConfigurationBuilder builder = new DefaultMcv2ConfigurationBuilder();
    final Location origin = new Location(WORLD, 0, 0, 0);
    final Mcv2Configuration configuration = new Mcv2Configuration(builder, VIEWERS, origin, BlockFace.UP, 0);

    final IllegalStateException exception = assertThrows(IllegalStateException.class, configuration::getRight);
    final String message = exception.getMessage();
    assertEquals("Facing UP is not a wall direction!", message);
  }

  private void refuses(final String message, final Consumer<Mcv2ConfigurationBuilder> change) {
    final Mcv2ConfigurationBuilder builder = complete();
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
      this.buildChangedConfiguration(change, builder)
    );
    final String failureMessage = failure.getMessage();
    assertEquals(message, failureMessage);
  }

  private void buildChangedConfiguration(final Consumer<Mcv2ConfigurationBuilder> change, final Mcv2ConfigurationBuilder builder) {
    change.accept(builder);
    builder.build();
  }

  @Test
  void shouldRefuseMissingOrInvalidValues() {
    final IllegalStateException noViewersException = assertThrows(IllegalStateException.class, () ->
      Mcv2Configuration.builder()
        .origin(new Location(WORLD, 0, 0, 0))
        .facing(BlockFace.SOUTH)
        .map(0)
        .columns(1)
        .rows(1)
        .build()
    );
    final IllegalStateException noOriginException = assertThrows(IllegalStateException.class, () ->
      Mcv2Configuration.builder().viewers(VIEWERS).facing(BlockFace.SOUTH).map(0).columns(1).rows(1).build()
    );
    final IllegalStateException noFacingException = assertThrows(IllegalStateException.class, () ->
      Mcv2Configuration.builder()
        .viewers(VIEWERS)
        .origin(new Location(WORLD, 0, 0, 0))
        .map(0)
        .columns(1)
        .rows(1)
        .build()
    );
    final String noViewersMessage = noViewersException.getMessage();
    final String noOriginMessage = noOriginException.getMessage();
    final String noFacingMessage = noFacingException.getMessage();
    assertEquals("Viewers must be set!", noViewersMessage);
    assertEquals("Origin must be set!", noOriginMessage);
    assertEquals("Facing must be set!", noFacingMessage);

    final Mcv2ConfigurationBuilder noMap = Mcv2Configuration.builder()
      .viewers(VIEWERS)
      .origin(new Location(WORLD, 0, 0, 0))
      .facing(BlockFace.SOUTH)
      .columns(1)
      .rows(1);
    final IllegalArgumentException noMapException = assertThrows(IllegalArgumentException.class, noMap::build);
    final String noMapMessage = noMapException.getMessage();
    assertEquals("Map id -1 must be set and must not be negative!", noMapMessage);
    this.refuses("Origin must be in a world!", builder -> builder.origin(new Location(null, 0, 0, 0)));
    this.refuses("Facing UP is not horizontal, but frames must face a horizontal direction!", builder -> builder.facing(BlockFace.UP));
    this.refuses("Facing NORTH_EAST is not horizontal, but frames must face a horizontal direction!", builder ->
      builder.facing(BlockFace.NORTH_EAST)
    );
    this.refuses("Map id -1 must be set and must not be negative!", builder -> builder.map(-1));
    this.refuses("Columns must be between 1 and 63 inclusive!", builder -> builder.columns(0));
    this.refuses("Columns must be between 1 and 63 inclusive!", builder -> builder.columns(Mcv2Configuration.MAXIMUM_SIDE + 1));
    this.refuses("Rows must be between 1 and 63 inclusive!", builder -> builder.rows(0));
    this.refuses("Rows must be between 1 and 63 inclusive!", builder -> builder.rows(Mcv2Configuration.MAXIMUM_SIDE + 1));
    this.refuses("Video width must be between 0 and 4096 inclusive!", builder -> builder.video(-1, 10));
    this.refuses("Video width must be between 0 and 4096 inclusive!", builder -> builder.video(4097, 10));
    this.refuses("Video height must be between 0 and 4096 inclusive!", builder -> builder.video(10, -1));
    this.refuses("Video height must be between 0 and 4096 inclusive!", builder -> builder.video(10, 4097));
    this.refuses("Page slots must be between 0 and 8 inclusive!", builder -> builder.pageSlots(-1));
    this.refuses("Page slots must be between 0 and 8 inclusive!", builder -> builder.pageSlots(Mcv2Configuration.MAXIMUM_PAGE_SLOTS + 1));
    this.refuses("Stream id must be between 0 and 4095 inclusive!", builder -> builder.streamId(-1));
    this.refuses("Stream id must be between 0 and 4095 inclusive!", builder -> builder.streamId(Mcv2Configuration.MAXIMUM_STREAM_ID + 1));
    this.refuses("Backlog limit must not be negative!", builder -> builder.backlogLimit(-1));
    this.refuses("Unsent limit must not be negative!", builder -> builder.unsentLimit(-1));
    this.refuses("Page map id -1 must not be negative!", builder -> builder.pageMap(-1));
    this.refuses("Page map ids exceed the integer range!", builder -> builder.pageMap(Integer.MAX_VALUE));
    this.refuses("Map ids exceed the integer range!", builder -> builder.map(Integer.MAX_VALUE));
    this.refuses("Outline color must not be black!", builder -> builder.outlineColor(NamedTextColor.BLACK));
  }

  @Test
  void shouldAcceptEveryValueAtTheEdgesOfItsRanges() {
    final int lowestMap = complete().map(0).build().getMap();
    assertEquals(0, lowestMap);

    final int fewestColumns = complete().columns(1).build().getColumns();
    assertEquals(1, fewestColumns);
    // a side of 63 maps is 8064 pixels at its native size, so it plays a video the codec can take
    final int mostColumns = complete().columns(Mcv2Configuration.MAXIMUM_SIDE).video(MAXIMUM_VIDEO_SIDE, 0).build().getColumns();
    assertEquals(Mcv2Configuration.MAXIMUM_SIDE, mostColumns);

    final int fewestRows = complete().rows(1).build().getRows();
    assertEquals(1, fewestRows);

    final int mostRows = complete().rows(Mcv2Configuration.MAXIMUM_SIDE).video(0, MAXIMUM_VIDEO_SIDE).build().getRows();
    assertEquals(Mcv2Configuration.MAXIMUM_SIDE, mostRows);
    // 0 is the wall's own size, 128 pixels per map
    final int expectedNativeVideoWidth = 5 * 128;
    final int nativeVideoWidth = complete().video(0, 0).build().getVideoWidth();
    assertEquals(expectedNativeVideoWidth, nativeVideoWidth);

    final int expectedNativeVideoHeight = 3 * 128;
    final int nativeVideoHeight = complete().video(0, 0).build().getVideoHeight();
    assertEquals(expectedNativeVideoHeight, nativeVideoHeight);

    final int largestVideoWidth = complete().video(MAXIMUM_VIDEO_SIDE, MAXIMUM_VIDEO_SIDE).build().getVideoWidth();
    assertEquals(MAXIMUM_VIDEO_SIDE, largestVideoWidth);

    final int largestVideoHeight = complete().video(MAXIMUM_VIDEO_SIDE, MAXIMUM_VIDEO_SIDE).build().getVideoHeight();
    assertEquals(MAXIMUM_VIDEO_SIDE, largestVideoHeight);

    final int defaultPageSlots = complete().pageSlots(0).build().getPageSlots();
    assertEquals(Mcv2Configuration.MAXIMUM_PAGE_SLOTS, defaultPageSlots);

    final int mostPageSlots = complete().pageSlots(Mcv2Configuration.MAXIMUM_PAGE_SLOTS).build().getPageSlots();
    assertEquals(Mcv2Configuration.MAXIMUM_PAGE_SLOTS, mostPageSlots);

    final long lowestStreamId = complete().streamId(0).build().getStreamId();
    assertEquals(0, lowestStreamId);

    final long highestStreamId = complete().streamId(Mcv2Configuration.MAXIMUM_STREAM_ID).build().getStreamId();
    assertEquals(Mcv2Configuration.MAXIMUM_STREAM_ID, highestStreamId);

    final long lowestBacklogLimit = complete().backlogLimit(0).build().getBacklogLimit();
    assertEquals(0, lowestBacklogLimit);

    final int lowestUnsentLimit = complete().unsentLimit(0).build().getUnsentLimit();
    assertEquals(0, lowestUnsentLimit);

    final int lowestPageMap = complete().pageSlots(4).pageMap(0).build().getPageMap();
    assertEquals(0, lowestPageMap);

    final int expectedHighestPageMapOfFourSlots = Integer.MAX_VALUE - 3;
    final int highestPageMapOfFourSlots = complete()
      .pageSlots(4)
      .pageMap(Integer.MAX_VALUE - 3)
      .build()
      .getPageMap();
    assertEquals(expectedHighestPageMapOfFourSlots, highestPageMapOfFourSlots);

    final int expectedHighestPageMap = Integer.MAX_VALUE - (Mcv2Configuration.MAXIMUM_PAGE_SLOTS - 1);
    final int highestPageMap = complete()
      .pageMap(Integer.MAX_VALUE - (Mcv2Configuration.MAXIMUM_PAGE_SLOTS - 1))
      .build()
      .getPageMap();
    assertEquals(expectedHighestPageMap, highestPageMap);

    final int expectedHighestMap = Integer.MAX_VALUE - 14;
    final int highestMap = complete()
      .map(Integer.MAX_VALUE - 14)
      .pageMap(0)
      .build()
      .getMap();
    assertEquals(expectedHighestMap, highestMap);

    final Mcv2Configuration configuration = complete().build();

    final int smallestVideoWidth = configuration.withVideo(1, 1).getVideoWidth();
    assertEquals(1, smallestVideoWidth);

    final int smallestVideoHeight = configuration.withVideo(1, 1).getVideoHeight();
    assertEquals(1, smallestVideoHeight);

    final IllegalArgumentException noWidthException = assertThrows(IllegalArgumentException.class, () -> configuration.withVideo(0, 1));
    final IllegalArgumentException noHeightException = assertThrows(IllegalArgumentException.class, () -> configuration.withVideo(1, 0));
    final String noWidthMessage = noWidthException.getMessage();
    final String noHeightMessage = noHeightException.getMessage();
    assertEquals("Video size must be positive!", noWidthMessage);
    assertEquals("Video size must be positive!", noHeightMessage);
  }

  @Test
  void shouldKeepThePageMapsOffTheWall() {
    // the wall is maps 7 to 21; four page slots end below it at 3, the default eight at none
    this.refuses("Page maps must not be maps of the wall!", builder -> builder.pageMap(21));
    this.refuses("Page maps must not be maps of the wall!", builder -> builder.pageSlots(4).pageMap(4));
    this.refuses("Page maps must not be maps of the wall!", builder -> builder.pageMap(0));

    final int pageMapBelowTheWall = complete().pageSlots(4).pageMap(3).build().getPageMap();
    assertEquals(3, pageMapBelowTheWall);

    final int pageMapAboveTheWall = complete().pageMap(22).build().getPageMap();
    assertEquals(22, pageMapAboveTheWall);
  }

  @Test
  void shouldRefuseAWallWhoseNativeSizeIsLargerThanTheCodecTakes() {
    final IllegalArgumentException wide = assertThrows(IllegalArgumentException.class, () -> complete().columns(33).build());
    final String message = wide.getMessage();
    assertEquals("A wall of 33 by 3 maps is 4224 by 384 pixels, more than the codec's 4096: set a smaller video size!", message);
    final IllegalArgumentException tall = assertThrows(IllegalArgumentException.class, () -> complete().rows(33).build());
    final String tallMessage = tall.getMessage();
    assertEquals("A wall of 5 by 33 maps is 640 by 4224 pixels, more than the codec's 4096: set a smaller video size!", tallMessage);

    final int wideVideoWidth = complete().columns(33).video(MAXIMUM_VIDEO_SIDE, 0).build().getVideoWidth();
    assertEquals(MAXIMUM_VIDEO_SIDE, wideVideoWidth);

    final int tallVideoHeight = complete().rows(33).video(0, MAXIMUM_VIDEO_SIDE).build().getVideoHeight();
    assertEquals(MAXIMUM_VIDEO_SIDE, tallVideoHeight);

    final int largestNativeVideoWidth = complete().columns(32).rows(32).build().getVideoWidth();
    assertEquals(MAXIMUM_VIDEO_SIDE, largestNativeVideoWidth);
  }

  @Test
  void shouldRefuseMorePageSlotsThanTheWallHasMaps() {
    final Mcv2ConfigurationBuilder square = complete().columns(2).rows(2);

    final int defaultPageSlots = square.pageSlots(0).build().getPageSlots();
    assertEquals(4, defaultPageSlots, "the default of a wall of four maps");

    final int fullPageSlots = square.pageSlots(4).build().getPageSlots();
    assertEquals(4, fullPageSlots);

    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> square.pageSlots(5).build());
    final String message = refused.getMessage();
    assertEquals("A wall of 4 maps cannot carry 5 page slots: one page frame hangs behind each map!", message);
  }
}
