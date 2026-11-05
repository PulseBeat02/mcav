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
package me.brandonli.mcav.bukkit.media.result;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.config.MapConfigurationBuilder;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.ParallelDitherAlgorithm;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.apache.logging.log4j.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

final class CompressedMapResultTest {

  private static final int LARGE_BYTE_BUDGET = 1 << 20;

  private static final int SMALL_FRAME_LEFT = 96;

  private static final int THREE_MAP_SIZE = 384;

  private static final String CLEAR_EVENT = "clear";
  private static final String FRAME_EVENT = "frame";

  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static final int MAP_SIZE = 128;

  private static final int BLACK = 0xFF000000;
  private static final int UNOWNED_COLOR = 117;

  private FakeServer server;
  private List<UUID> viewers;
  private DitherAlgorithm algorithm;
  private byte[] nextFrame;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.addPlayer(FIRST);
    this.server.injectModule();

    final List<UUID> initialViewers = List.of(FIRST, SECOND);
    this.viewers = new CopyOnWriteArrayList<>(initialViewers);
    this.nextFrame = MapPackets.pattern(MAP_SIZE * MAP_SIZE, 4);
    this.algorithm = mock(DitherAlgorithm.class);
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(_ -> this.nextFrame.clone());
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private MapConfiguration createConfiguration(final int columns, final int rows, final Sizing sizing) {
    final MapConfigurationBuilder builder = MapConfiguration.builder();
    builder.viewers(this.viewers);
    builder.map(3);
    builder.mapBlockWidth(columns);
    builder.mapBlockHeight(rows);
    builder.mapWidthResolution(64);
    builder.mapHeightResolution(64);
    builder.resize(sizing == Sizing.RESIZED);
    return builder.build();
  }

  private List<ClientboundMapItemDataPacket> packetsOf(final UUID viewer, final int index) {
    final List<Packet<?>> packets = this.server.getSentPackets(viewer);
    final Packet<?> packet = packets.get(index);
    return MapPackets.unbundle(packet);
  }

  private int firstViewerPacketCount() {
    final List<Packet<?>> packets = this.server.getSentPackets(FIRST);
    return packets.size();
  }

  private byte[] replayFrames(final int firstPacket, final int columns) {
    final byte[] canvas = new byte[columns * MAP_SIZE * MAP_SIZE];
    for (int packetIndex = firstPacket; packetIndex < this.firstViewerPacketCount(); packetIndex++) {
      for (final ClientboundMapItemDataPacket packet : this.packetsOf(FIRST, packetIndex)) {
        this.replayPacket(canvas, packet, columns);
      }
    }

    return canvas;
  }

  private void replayPacket(final byte[] canvas, final ClientboundMapItemDataPacket packet, final int columns) {
    final int map = packet.mapId().id() - 3;
    final boolean addressesTheGrid = map >= 0 && map < columns;
    assertTrue(addressesTheGrid);

    final MapItemSavedData.MapPatch patch = packet.colorPatch().orElseThrow();
    final byte[] colors = patch.mapColors();
    for (int row = 0; row < patch.height(); row++) {
      final int target = (patch.startY() + row) * columns * MAP_SIZE + map * MAP_SIZE + patch.startX();
      System.arraycopy(colors, row * patch.width(), canvas, target, patch.width());
    }
  }

  private byte[] changedFrame() {
    final byte[] frame = this.nextFrame.clone();
    for (int row = 0; row < 16; row++) {
      for (int column = 0; column < 16; column++) {
        final int index = row * MAP_SIZE + column;
        frame[index] = (byte) (frame[index] + 1);
      }
    }

    return frame;
  }

  // a lock this thread still holds shows as a thread that never finishes
  private void runInAnotherThread(final Runnable work) throws InterruptedException {
    final AtomicReference<Throwable> recordedFailure = new AtomicReference<>();
    final Thread worker = new Thread(work, "map-result-lock-probe");
    worker.setDaemon(true);
    worker.setUncaughtExceptionHandler((_, failure) -> recordedFailure.set(failure));
    worker.start();
    worker.join();
    final Throwable thrown = recordedFailure.get();
    if (thrown != null) {
      throw new AssertionError("The work on the other thread failed!", thrown);
    }
  }

  private byte[] topLeftTile(final byte[] frame) {
    final byte[] tile = new byte[16 * 16];
    for (int row = 0; row < 16; row++) {
      System.arraycopy(frame, row * MAP_SIZE, tile, row * 16, 16);
    }

    return tile;
  }

  @Test
  void shouldSendTheCompletePictureFirstAndThenOnlyTheChanges() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    result.start();
    final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK);
    final byte[] firstFrame = this.nextFrame.clone();

    result.process(image, this.algorithm);
    result.process(image, this.algorithm);
    final byte[] changed = this.changedFrame();
    this.nextFrame = changed;
    result.process(image, this.algorithm);

    final int count = this.firstViewerPacketCount();
    final List<ClientboundMapItemDataPacket> first = this.packetsOf(FIRST, 0);
    final List<ClientboundMapItemDataPacket> delta = this.packetsOf(FIRST, 1);
    final ClientboundMapItemDataPacket full = first.getFirst();
    final ClientboundMapItemDataPacket tile = delta.getFirst();
    final byte[] tileColors = this.topLeftTile(changed);

    assertEquals(2, count, "an unchanged frame sends nothing");

    MapPackets.assertMapPacket(full, 3, 0, 0, MAP_SIZE, MAP_SIZE, firstFrame);
    MapPackets.assertMapPacket(tile, 3, 0, 0, 16, 16, tileColors);
  }

  @Test
  void shouldRefreshEveryViewerOnceThenResumeOrdinaryDeltas() {
    this.server.addPlayer(SECOND);
    PacketUtils.initialize();
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    final byte[] original = this.nextFrame.clone();
    try (final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK)) {
      result.process(image, this.algorithm);
      MapPacketFactory.clear(this.viewers, 3, 1);
      result.refresh();
      result.refresh();
      final int firstPacketCount = this.firstViewerPacketCount();
      assertEquals(2, firstPacketCount, "requests send no packets before the next frame");

      final int secondPacketCount = this.server.getSentPackets(SECOND).size();
      assertEquals(2, secondPacketCount);

      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
      final int firstPacketCountAfterFrames = this.firstViewerPacketCount();
      assertEquals(3, firstPacketCountAfterFrames, "requests coalesce and unchanged frames stay quiet");

      final int secondPacketCountAfterFrames = this.server.getSentPackets(SECOND).size();
      assertEquals(3, secondPacketCountAfterFrames);

      MapPackets.assertMapPacket(this.packetsOf(FIRST, 2).getFirst(), 3, 0, 0, MAP_SIZE, MAP_SIZE, original);
      MapPackets.assertMapPacket(this.packetsOf(SECOND, 2).getFirst(), 3, 0, 0, MAP_SIZE, MAP_SIZE, original);

      this.nextFrame = this.changedFrame();
      result.process(image, this.algorithm);
      final byte[] changed = this.topLeftTile(this.nextFrame);
      MapPackets.assertMapPacket(this.packetsOf(FIRST, 3).getFirst(), 3, 0, 0, 16, 16, changed);
      MapPackets.assertMapPacket(this.packetsOf(SECOND, 3).getFirst(), 3, 0, 0, 16, 16, changed);
      result.release();
      final int released = this.firstViewerPacketCount();
      result.refresh();
      result.process(image, this.algorithm);
      final int firstPacketCountAfterRefresh = this.firstViewerPacketCount();
      assertEquals(released, firstPacketCountAfterRefresh, "a refresh cannot revive released playback");
    } finally {
      result.release();
    }
  }

  @Test
  void shouldLetAnotherThreadAcquireTheProcessingLockDuringARefresh() throws ReflectiveOperationException, InterruptedException {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    final Field lockField = CompressedMapResult.class.getDeclaredField("lock");
    lockField.setAccessible(true);
    final Lock lock = (Lock) lockField.get(result);
    try {
      result.refresh();
      this.runInAnotherThread(() -> this.assertLockFree(lock));
    } finally {
      result.release();
    }
  }

  private void assertLockFree(final Lock lock) {
    final boolean acquired = lock.tryLock();
    if (acquired) {
      lock.unlock();
    }

    assertTrue(acquired, "refresh must release the processing lock for the next frame on another thread");
  }

  @Test
  void shouldRetainHistoricalCleanupOnRefreshWithoutTouchingUnownedPixels() {
    final MapConfiguration configuration = this.createConfiguration(2, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    this.nextFrame = MapPackets.pattern(MAP_SIZE * 64, 4);
    try (final ImageBuffer original = Images.solid(MAP_SIZE, 64, BLACK)) {
      result.process(original, this.algorithm);
    }

    final byte[] smallFrame = MapPackets.pattern(64 * 32, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer small = Images.solid(64, 32, BLACK)) {
      result.process(small, this.algorithm);
      result.refresh();
      result.process(small, this.algorithm);
      final byte[] canvas = new byte[256 * MAP_SIZE];
      Arrays.fill(canvas, (byte) UNOWNED_COLOR);
      this.applyPacketsToCanvas(canvas, 2, 1, this.packetsOf(FIRST, 0));
      this.applyPacketsToCanvas(canvas, 2, 1, this.packetsOf(FIRST, 2));
      final byte[] expected = new byte[256 * MAP_SIZE];
      Arrays.fill(expected, (byte) UNOWNED_COLOR);
      this.placeRectangle(expected, 256, 64, 32, MAP_SIZE, 64, new byte[MAP_SIZE * 64]);
      this.placeRectangle(expected, 256, SMALL_FRAME_LEFT, 48, 64, 32, smallFrame);
      assertArrayEquals(expected, canvas, "repair clears an old picture restored externally and preserves pixels never owned");
    } finally {
      result.release();
    }
  }

  @Test
  void shouldSendThePictureToViewersThatStartWatchingLater() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK);
    final byte[] firstFrame = this.nextFrame.clone();

    result.process(image, this.algorithm);
    this.server.addPlayer(SECOND);
    PacketUtils.initialize();
    result.process(image, this.algorithm);
    final int firstCount = this.firstViewerPacketCount();
    final List<ClientboundMapItemDataPacket> snapshot = this.packetsOf(SECOND, 0);
    final ClientboundMapItemDataPacket snapshotPacket = snapshot.getFirst();

    final byte[] changed = this.changedFrame();
    this.nextFrame = changed;
    result.process(image, this.algorithm);
    final List<ClientboundMapItemDataPacket> firstDelta = this.packetsOf(FIRST, 1);
    final List<ClientboundMapItemDataPacket> secondDelta = this.packetsOf(SECOND, 1);
    final ClientboundMapItemDataPacket firstDeltaPacket = firstDelta.getFirst();
    final ClientboundMapItemDataPacket secondDeltaPacket = secondDelta.getFirst();
    final byte[] tileColors = this.topLeftTile(changed);

    assertEquals(1, firstCount, "viewers that already watch receive nothing for an unchanged frame");

    MapPackets.assertMapPacket(snapshotPacket, 3, 0, 0, MAP_SIZE, MAP_SIZE, firstFrame);
    MapPackets.assertMapPacket(firstDeltaPacket, 3, 0, 0, 16, 16, tileColors);
    MapPackets.assertMapPacket(secondDeltaPacket, 3, 0, 0, 16, 16, tileColors);
  }

  @Test
  void shouldForgetViewersThatDisconnected() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK);
    final byte[] firstFrame = this.nextFrame.clone();

    result.process(image, this.algorithm);
    this.server.removePlayer(FIRST);
    PacketUtils.initialize();
    result.process(image, this.algorithm);
    this.server.addPlayer(FIRST);
    PacketUtils.initialize();
    result.process(image, this.algorithm);

    final int count = this.firstViewerPacketCount();
    final List<ClientboundMapItemDataPacket> snapshot = this.packetsOf(FIRST, 0);
    final ClientboundMapItemDataPacket snapshotPacket = snapshot.getFirst();
    assertEquals(1, count);

    MapPackets.assertMapPacket(snapshotPacket, 3, 0, 0, MAP_SIZE, MAP_SIZE, firstFrame);
  }

  @ParameterizedTest
  @EnumSource(OfflineFrame.class)
  void shouldSendAReconnectedViewerASnapshotWithoutAnInterveningFrame(final OfflineFrame offlineFrame) {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    try (final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK)) {
      result.process(image, this.algorithm);
      this.server.removePlayer(FIRST);
      PacketUtils.initialize();
      this.server.addPlayer(FIRST);
      PacketUtils.initialize();

      if (offlineFrame == OfflineFrame.CHANGED) {
        this.nextFrame = this.changedFrame();
      }

      result.process(image, this.algorithm);

      final int packetCount = this.firstViewerPacketCount();
      assertEquals(1, packetCount, "a replacement connection needs its own complete baseline");

      final List<ClientboundMapItemDataPacket> snapshot = this.packetsOf(FIRST, 0);
      final int snapshotSize = snapshot.size();
      assertEquals(1, snapshotSize);

      MapPackets.assertMapPacket(snapshot.getFirst(), 3, 0, 0, MAP_SIZE, MAP_SIZE, this.nextFrame);
      result.process(image, this.algorithm);
      final int packetCountAfterNextFrame = this.firstViewerPacketCount();
      assertEquals(1, packetCountAfterNextFrame, "the replacement connection retains its new baseline");
    } finally {
      result.release();
    }
  }

  private void applyPacketsToCanvas(
    final byte[] canvas,
    final int columns,
    final int rows,
    final List<ClientboundMapItemDataPacket> packets
  ) {
    final int canvasWidth = columns * MAP_SIZE;
    for (final ClientboundMapItemDataPacket packet : packets) {
      final MapId mapId = packet.mapId();
      final int index = mapId.id() - 3;
      final boolean addressesTheGrid = index >= 0 && index < columns * rows;
      assertTrue(addressesTheGrid, "packet must address the configured map grid");
      final Optional<MapItemSavedData.MapPatch> colorPatch = packet.colorPatch();
      final MapItemSavedData.MapPatch patch = colorPatch.orElseThrow();
      final int mapX = (index % columns) * MAP_SIZE;
      final int mapY = (index / columns) * MAP_SIZE;
      final int startX = mapX + patch.startX();
      final int startY = mapY + patch.startY();
      final int width = patch.width();
      final int height = patch.height();
      final byte[] colors = patch.mapColors();
      for (int row = 0; row < height; row++) {
        final int destination = (startY + row) * canvasWidth + startX;
        System.arraycopy(colors, row * width, canvas, destination, width);
      }
    }
  }

  private void placeRectangle(
    final byte[] canvas,
    final int canvasWidth,
    final int left,
    final int top,
    final int width,
    final int height,
    final byte[] colors
  ) {
    for (int row = 0; row < height; row++) {
      final int destination = (top + row) * canvasWidth + left;
      System.arraycopy(colors, row * width, canvas, destination, width);
    }
  }

  @Test
  void shouldStartOverWhenTheFrameSizeChanges() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    final ImageBuffer large = Images.solid(MAP_SIZE, MAP_SIZE, BLACK);
    result.process(large, this.algorithm);

    final byte[] smallFrame = MapPackets.pattern(64 * 64, 9);
    this.nextFrame = smallFrame;
    final ImageBuffer small = Images.solid(64, 64, BLACK);
    result.process(small, this.algorithm);

    final int count = this.firstViewerPacketCount();
    final List<ClientboundMapItemDataPacket> restart = this.packetsOf(FIRST, 1);
    final List<ClientboundMapItemDataPacket> original = this.packetsOf(FIRST, 0);
    final byte[] canvas = new byte[MAP_SIZE * MAP_SIZE];
    this.applyPacketsToCanvas(canvas, 1, 1, original);
    this.applyPacketsToCanvas(canvas, 1, 1, restart);
    final byte[] expected = new byte[MAP_SIZE * MAP_SIZE];
    this.placeRectangle(expected, MAP_SIZE, 32, 32, 64, 64, smallFrame);
    assertEquals(2, count, "clear and replacement must share one bundle");
    assertArrayEquals(expected, canvas, "the old border must be transparent after a shrink");

    final int patches = restart.size();
    assertEquals(5, patches, "four rectangular border strips and the new picture must stay compact");

    result.process(small, this.algorithm);
    final int afterUnchanged = this.firstViewerPacketCount();
    assertEquals(2, afterUnchanged, "the reset is not repeated for an unchanged frame");
  }

  @Test
  void shouldPreservePixelsOutsideBothCenteredPictures() {
    final MapConfiguration configuration = this.createConfiguration(2, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    final byte[] originalFrame = MapPackets.pattern(MAP_SIZE * 64, 4);
    this.nextFrame = originalFrame;
    try (final ImageBuffer image = Images.solid(MAP_SIZE, 64, BLACK)) {
      result.process(image, this.algorithm);
    }

    final byte[] smallFrame = MapPackets.pattern(64 * 32, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer image = Images.solid(64, 32, BLACK)) {
      result.process(image, this.algorithm);
    }

    final byte[] canvas = new byte[256 * MAP_SIZE];
    Arrays.fill(canvas, (byte) UNOWNED_COLOR);
    final List<ClientboundMapItemDataPacket> original = this.packetsOf(FIRST, 0);
    final List<ClientboundMapItemDataPacket> restart = this.packetsOf(FIRST, 1);
    this.applyPacketsToCanvas(canvas, 2, 1, original);
    this.applyPacketsToCanvas(canvas, 2, 1, restart);
    final byte[] expected = new byte[256 * MAP_SIZE];
    Arrays.fill(expected, (byte) UNOWNED_COLOR);
    final byte[] transparent = new byte[MAP_SIZE * 64];
    this.placeRectangle(expected, 256, 64, 32, MAP_SIZE, 64, transparent);
    this.placeRectangle(expected, 256, SMALL_FRAME_LEFT, 48, 64, 32, smallFrame);
    assertArrayEquals(expected, canvas, "only pixels occupied by the old or new picture may change");
  }

  @Test
  void shouldClearFormerlyCoveredMapsForOldAndNewViewersDuringAShrink() {
    final MapConfiguration configuration = this.createConfiguration(3, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    this.nextFrame = MapPackets.pattern(THREE_MAP_SIZE * MAP_SIZE, 4);
    try (final ImageBuffer image = Images.solid(THREE_MAP_SIZE, MAP_SIZE, BLACK)) {
      result.process(image, this.algorithm);
    }

    this.server.addPlayer(SECOND);
    PacketUtils.initialize();
    final byte[] smallFrame = MapPackets.pattern(64 * 64, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer image = Images.solid(64, 64, BLACK)) {
      result.process(image, this.algorithm);
    }

    final byte[] oldViewerCanvas = new byte[THREE_MAP_SIZE * MAP_SIZE];
    final byte[] newViewerCanvas = new byte[THREE_MAP_SIZE * MAP_SIZE];
    Arrays.fill(newViewerCanvas, (byte) 118);
    final List<ClientboundMapItemDataPacket> original = this.packetsOf(FIRST, 0);
    final List<ClientboundMapItemDataPacket> oldViewerReset = this.packetsOf(FIRST, 1);
    final List<ClientboundMapItemDataPacket> newViewerReset = this.packetsOf(SECOND, 0);
    this.applyPacketsToCanvas(oldViewerCanvas, 3, 1, original);
    this.applyPacketsToCanvas(oldViewerCanvas, 3, 1, oldViewerReset);
    this.applyPacketsToCanvas(newViewerCanvas, 3, 1, newViewerReset);
    final byte[] expected = new byte[THREE_MAP_SIZE * MAP_SIZE];
    this.placeRectangle(expected, THREE_MAP_SIZE, 160, 32, 64, 64, smallFrame);
    assertArrayEquals(expected, oldViewerCanvas);
    assertArrayEquals(expected, newViewerCanvas);

    final int firstCount = this.firstViewerPacketCount();
    final List<Packet<?>> secondPackets = this.server.getSentPackets(SECOND);
    final int secondCount = secondPackets.size();
    assertEquals(2, firstCount, "old viewers receive the clear and replacement together");
    assertEquals(1, secondCount, "joining viewers receive the same reset in one bundle");
  }

  @Test
  void shouldKeepTheNormalByteBudgetAfterOneTimeResizeCleanup() {
    final MapConfiguration configuration = this.createConfiguration(2, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1);
    this.nextFrame = MapPackets.pattern(256 * MAP_SIZE, 4);
    try (final ImageBuffer image = Images.solid(256, MAP_SIZE, BLACK)) {
      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
    }

    final byte[] smallFrame = MapPackets.pattern(MAP_SIZE * 64, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer image = Images.solid(MAP_SIZE, 64, BLACK)) {
      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
    }

    final List<ClientboundMapItemDataPacket> next = this.packetsOf(FIRST, 3);
    final int nextCount = next.size();
    final int totalCount = this.firstViewerPacketCount();
    assertEquals(1, nextCount, "ordinary frames retain the encoder's one-urgent-map exception");
    assertEquals(4, totalCount, "an unchanged completed frame sends nothing");

    final byte[] canvas = new byte[256 * MAP_SIZE];
    for (int index = 0; index < totalCount; index++) {
      final List<ClientboundMapItemDataPacket> packets = this.packetsOf(FIRST, index);
      this.applyPacketsToCanvas(canvas, 2, 1, packets);
    }

    final byte[] expected = new byte[256 * MAP_SIZE];
    this.placeRectangle(expected, 256, 64, 32, MAP_SIZE, 64, smallFrame);
    assertArrayEquals(expected, canvas);
  }

  @Test
  void shouldClearTheClippedOldPictureWhenAnOversizedFrameShrinks() {
    final MapConfiguration configuration = this.createConfiguration(2, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    this.nextFrame = MapPackets.pattern(512 * 256, 4);
    try (final ImageBuffer image = Images.solid(512, 256, BLACK)) {
      result.process(image, this.algorithm);
    }

    final byte[] smallFrame = MapPackets.pattern(64 * 64, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer image = Images.solid(64, 64, BLACK)) {
      result.process(image, this.algorithm);
    }

    final List<ClientboundMapItemDataPacket> original = this.packetsOf(FIRST, 0);
    final List<ClientboundMapItemDataPacket> reset = this.packetsOf(FIRST, 1);
    final byte[] canvas = new byte[256 * MAP_SIZE];
    this.applyPacketsToCanvas(canvas, 2, 1, original);
    this.applyPacketsToCanvas(canvas, 2, 1, reset);
    final byte[] expected = new byte[256 * MAP_SIZE];
    this.placeRectangle(expected, 256, SMALL_FRAME_LEFT, 32, 64, 64, smallFrame);
    assertArrayEquals(expected, canvas);
  }

  @Test
  void shouldClearHistoricalBordersWhenAViewerMissedTheResize() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    try (final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK)) {
      result.process(image, this.algorithm);
    }

    this.viewers.remove(FIRST);
    final byte[] smallFrame = MapPackets.pattern(64 * 64, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer image = Images.solid(64, 64, BLACK)) {
      result.process(image, this.algorithm);
      final int beforeRejoining = this.firstViewerPacketCount();
      assertEquals(1, beforeRejoining, "an absent viewer receives no resize packets");

      this.viewers.add(FIRST);
      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
    }

    final byte[] canvas = new byte[MAP_SIZE * MAP_SIZE];
    final List<ClientboundMapItemDataPacket> original = this.packetsOf(FIRST, 0);
    final List<ClientboundMapItemDataPacket> rejoined = this.packetsOf(FIRST, 1);
    this.applyPacketsToCanvas(canvas, 1, 1, original);
    this.applyPacketsToCanvas(canvas, 1, 1, rejoined);
    final byte[] expected = new byte[MAP_SIZE * MAP_SIZE];
    this.placeRectangle(expected, MAP_SIZE, 32, 32, 64, 64, smallFrame);
    assertArrayEquals(expected, canvas);

    final int count = this.firstViewerPacketCount();
    assertEquals(2, count, "cleanup is sent once when the viewer rejoins");
  }

  @Test
  void shouldRetainExactCoverageAcrossSeveralResizesWithoutClaimingUnviewedPixels() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    this.nextFrame = MapPackets.pattern(MAP_SIZE * 32, 4);
    try (final ImageBuffer image = Images.solid(MAP_SIZE, 32, BLACK)) {
      result.process(image, this.algorithm);
    }

    this.nextFrame = MapPackets.pattern(32 * MAP_SIZE, 7);
    try (final ImageBuffer image = Images.solid(32, MAP_SIZE, BLACK)) {
      result.process(image, this.algorithm);
    }

    this.viewers.remove(FIRST);
    this.nextFrame = MapPackets.pattern(MAP_SIZE * MAP_SIZE, 11);
    try (final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK)) {
      result.process(image, this.algorithm);
    }

    final byte[] finalFrame = MapPackets.pattern(64 * 16, 13);
    this.nextFrame = finalFrame;
    try (final ImageBuffer image = Images.solid(64, 16, BLACK)) {
      result.process(image, this.algorithm);
      this.viewers.add(FIRST);
      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
    }

    final byte[] canvas = new byte[MAP_SIZE * MAP_SIZE];
    Arrays.fill(canvas, (byte) UNOWNED_COLOR);
    final int count = this.firstViewerPacketCount();
    assertEquals(3, count, "wide, tall, and one rejoin snapshot; no unviewed or repeated reset");

    for (int index = 0; index < count; index++) {
      final List<ClientboundMapItemDataPacket> packets = this.packetsOf(FIRST, index);
      this.applyPacketsToCanvas(canvas, 1, 1, packets);
    }

    final byte[] expected = new byte[MAP_SIZE * MAP_SIZE];
    Arrays.fill(expected, (byte) UNOWNED_COLOR);
    final byte[] wideClear = new byte[MAP_SIZE * 32];
    final byte[] tallClear = new byte[32 * MAP_SIZE];
    this.placeRectangle(expected, MAP_SIZE, 0, 48, MAP_SIZE, 32, wideClear);
    this.placeRectangle(expected, MAP_SIZE, 48, 0, 32, MAP_SIZE, tallClear);
    this.placeRectangle(expected, MAP_SIZE, 32, 56, 64, 16, finalFrame);
    assertArrayEquals(expected, canvas, "the union is a cross, not its whole-map bounding rectangle");
  }

  @Test
  void shouldResizeFramesWhenConfigured() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.RESIZED);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    final byte[] frame = MapPackets.pattern(64 * 64, 7);
    this.nextFrame = frame;
    final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK);

    result.process(image, this.algorithm);

    final List<ClientboundMapItemDataPacket> packets = this.packetsOf(FIRST, 0);
    final ClientboundMapItemDataPacket packet = packets.getFirst();
    final int width = image.getWidth();
    assertEquals(64, width);

    MapPackets.assertMapPacket(packet, 3, 32, 32, 64, 64, frame);
  }

  @Test
  void shouldSendAtMostTheDefaultBudgetPerFrame() {
    final MapConfiguration configuration = this.createConfiguration(3, 3, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration);
    this.nextFrame = MapPackets.pattern(THREE_MAP_SIZE * THREE_MAP_SIZE, 4);
    final ImageBuffer image = Images.solid(THREE_MAP_SIZE, THREE_MAP_SIZE, BLACK);

    result.process(image, this.algorithm);

    final List<ClientboundMapItemDataPacket> packets = this.packetsOf(FIRST, 0);
    final int mapCount = packets.size();
    assertEquals(7, mapCount, "seven complete maps fit into 128 KiB, the eighth does not");
  }

  @Test
  void shouldSpreadLargeUpdatesOverSeveralFramesWithASmallBudget() {
    final MapConfiguration configuration = this.createConfiguration(2, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1);
    this.nextFrame = MapPackets.pattern(256 * MAP_SIZE, 4);
    final ImageBuffer image = Images.solid(256, MAP_SIZE, BLACK);

    result.process(image, this.algorithm);
    result.process(image, this.algorithm);

    final List<ClientboundMapItemDataPacket> first = this.packetsOf(FIRST, 0);
    final List<ClientboundMapItemDataPacket> second = this.packetsOf(FIRST, 1);
    final int firstCount = first.size();
    final int secondCount = second.size();
    final ClientboundMapItemDataPacket firstPacket = first.getFirst();
    final ClientboundMapItemDataPacket secondPacket = second.getFirst();
    final MapId firstMapId = firstPacket.mapId();
    final MapId secondMapId = secondPacket.mapId();
    final int firstId = firstMapId.id();
    final int secondId = secondMapId.id();
    assertEquals(1, firstCount);
    assertEquals(1, secondCount);
    assertEquals(3, firstId);
    assertEquals(4, secondId);
  }

  @Test
  void shouldDitherParallelAlgorithmsOnItsOwnDaemonThreads() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    final List<ForkJoinPool> pools = new CopyOnWriteArrayList<>();
    final List<Thread> workers = new CopyOnWriteArrayList<>();
    final ParallelDitherAlgorithm parallel = this.createRecordingAlgorithm(pools, workers);
    final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK);

    result.process(image, parallel);
    result.process(image, parallel);
    final ForkJoinPool firstPool = pools.get(0);
    final ForkJoinPool secondPool = pools.get(1);
    final Thread worker = workers.getFirst();
    final String workerName = worker.getName();
    final boolean isDaemon = worker.isDaemon();
    final boolean namedWorker = workerName.startsWith("mcav-map-dither-");
    final boolean shutDownBeforeRelease = firstPool.isShutdown();
    result.release();
    final boolean shutDownAfterRelease = firstPool.isShutdown();

    assertSame(firstPool, secondPool, "the pool is reused");

    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final int parallelism = firstPool.getParallelism();
    final boolean leavesHalfTheProcessors = parallelism <= Math.max(1, processors / 2);
    assertTrue(leavesHalfTheProcessors, "map dithering leaves at least half the CPUs for the server");
    assertTrue(namedWorker, workerName);
    assertTrue(isDaemon, "dither threads never keep the server alive");
    assertFalse(shutDownBeforeRelease);
    assertTrue(shutDownAfterRelease);
    verify(parallel, never()).ditherIntoBytes(any(ImageBuffer.class));
  }

  private ParallelDitherAlgorithm createRecordingAlgorithm(final List<ForkJoinPool> pools, final List<Thread> workers) {
    final ParallelDitherAlgorithm parallel = mock(ParallelDitherAlgorithm.class);
    when(parallel.ditherIntoBytes(any(ImageBuffer.class), any(ForkJoinPool.class))).thenAnswer(invocation ->
      this.recordDitherWorker(invocation, pools, workers)
    );
    return parallel;
  }

  private byte[] recordDitherWorker(final InvocationOnMock invocation, final List<ForkJoinPool> pools, final List<Thread> workers)
    throws InterruptedException, ExecutionException {
    final ForkJoinPool pool = invocation.getArgument(1);
    final ForkJoinTask<Thread> task = pool.submit(Thread::currentThread);
    final Thread worker = task.get();
    pools.add(pool);
    workers.add(worker);

    return MapPackets.pattern(MAP_SIZE * MAP_SIZE, 4);
  }

  @Test
  void shouldLogExceptionsThatEscapeTheDitherThreads() throws InterruptedException {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    final RuntimeException failure = new RuntimeException("Escaped the dither pool!");
    final ParallelDitherAlgorithm parallel = this.createFailingAlgorithm(failure);

    final LogCapture.RecordedEvent event;
    try (final LogCapture logs = LogCapture.capture(CompressedMapResult.class)) {
      final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK);
      result.process(image, parallel);
      event = logs.awaitEvent(TIMEOUT);
    } finally {
      result.release();
    }

    final Level level = event.getLevel();
    final String message = event.getMessage();
    final Throwable thrown = event.getThrown().orElseThrow();
    final boolean namesTheThread = message.startsWith("Map dithering thread mcav-map-dither-");
    assertEquals(Level.ERROR, level);
    assertTrue(namesTheThread, message);
    assertSame(failure, thrown);
  }

  private ParallelDitherAlgorithm createFailingAlgorithm(final RuntimeException failure) {
    final ParallelDitherAlgorithm parallel = mock(ParallelDitherAlgorithm.class);
    when(parallel.ditherIntoBytes(any(ImageBuffer.class), any(ForkJoinPool.class))).thenAnswer(invocation ->
      this.submitDitherFailure(invocation, failure)
    );
    return parallel;
  }

  private byte[] submitDitherFailure(final InvocationOnMock invocation, final RuntimeException failure) {
    final ForkJoinPool pool = invocation.getArgument(1);
    pool.execute(() -> this.throwDitherFailure(failure));

    return MapPackets.pattern(MAP_SIZE * MAP_SIZE, 4);
  }

  private void throwDitherFailure(final RuntimeException failure) {
    throw failure;
  }

  @Test
  void shouldClearTheMapsWhenReleasedAndIgnoreLaterFrames() throws ReflectiveOperationException {
    final MapConfiguration configuration = this.createConfiguration(2, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    this.nextFrame = MapPackets.pattern(256 * MAP_SIZE, 4);
    final ImageBuffer image = Images.solid(256, MAP_SIZE, BLACK);

    result.process(image, this.algorithm);
    result.release();
    result.release();
    result.process(image, this.algorithm);

    final int count = this.firstViewerPacketCount();
    final List<ClientboundMapItemDataPacket> cleared = this.packetsOf(FIRST, 1);
    final byte[] transparent = new byte[MAP_SIZE * MAP_SIZE];
    final int clearedCount = cleared.size();
    final ClientboundMapItemDataPacket firstCleared = cleared.get(0);
    final ClientboundMapItemDataPacket secondCleared = cleared.get(1);
    assertEquals(2, count, "the frame and one clear, nothing afterwards");
    assertEquals(2, clearedCount);

    MapPackets.assertMapPacket(firstCleared, 3, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
    MapPackets.assertMapPacket(secondCleared, 4, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
    final Field retainedField = CompressedMapResult.class.getDeclaredField("sentPixels");
    retainedField.setAccessible(true);
    final Map<?, ?> retained = (Map<?, ?>) retainedField.get(result);
    final boolean retainsNoState = retained.isEmpty();
    assertTrue(retainsNoState, "released displays must not retain obsolete rendering state");

    final Field viewersField = CompressedMapResult.class.getDeclaredField("activeViewers");
    viewersField.setAccessible(true);
    final Map<?, ?> retainedViewers = (Map<?, ?>) viewersField.get(result);
    final boolean retainsNoViewers = retainedViewers.isEmpty();
    assertTrue(retainsNoViewers, "release drops the old viewer snapshot");
  }

  @Test
  void shouldShowASecondVideoAfterItWasReleasedAndStartedAgain() {
    final MapConfiguration configuration = this.createConfiguration(2, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    this.nextFrame = MapPackets.pattern(256 * MAP_SIZE, 4);
    final ImageBuffer image = Images.solid(256, MAP_SIZE, BLACK);

    result.process(image, this.algorithm);
    result.release();
    final int afterRelease = this.firstViewerPacketCount();

    this.nextFrame = MapPackets.pattern(256 * MAP_SIZE, 13);
    result.start();
    result.process(image, this.algorithm);
    final int afterRestart = this.firstViewerPacketCount();

    final boolean sendsAgain = afterRestart > afterRelease;
    assertTrue(sendsAgain, "a started result sends frames again instead of dropping them");

    final byte[] replayed = this.replayFrames(afterRelease, 2);
    assertArrayEquals(this.nextFrame, replayed, "the entire second picture reaches both maps");

    result.release();
    image.close();
  }

  @Test
  void shouldClearEveryMapOfTheGridOnTheFirstRelease() {
    final MapConfiguration configuration = this.createConfiguration(2, 2, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);

    result.release();

    final int count = this.firstViewerPacketCount();
    final List<ClientboundMapItemDataPacket> cleared = this.packetsOf(FIRST, 0);
    final int clearedCount = cleared.size();
    final ClientboundMapItemDataPacket last = cleared.get(3);
    final byte[] transparent = new byte[MAP_SIZE * MAP_SIZE];

    assertEquals(1, count, "the first release clears the maps at once");
    assertEquals(4, clearedCount, "every map of the 2x2 grid is cleared");

    MapPackets.assertMapPacket(last, 6, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
  }

  @Test
  void shouldFinishTheOldClearBeforeAConcurrentRestartCanSendFrames() throws Exception {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration);
    final CountDownLatch clearing = new CountDownLatch(1);
    final CountDownLatch finishClear = new CountDownLatch(1);
    final CountDownLatch restarting = new CountDownLatch(1);
    final List<String> order = new CopyOnWriteArrayList<>();
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(_ -> this.recordFrame(order));
    try (
      final ExecutorService workers = Executors.newFixedThreadPool(2);
      final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK)
    ) {
      final CompletableFuture<Void> released = CompletableFuture.runAsync(
        () -> this.releaseWithSlowClear(result, clearing, finishClear, order),
        workers
      );
      final CompletableFuture<Void> restarted;
      try {
        final boolean clearStarted = clearing.await(5, TimeUnit.SECONDS);
        assertTrue(clearStarted);

        restarted = CompletableFuture.runAsync(() -> this.restart(result, image, restarting), workers);
        final boolean restartStarted = restarting.await(5, TimeUnit.SECONDS);
        assertTrue(restartStarted);
        assertThrows(
          TimeoutException.class,
          () -> restarted.get(200, TimeUnit.MILLISECONDS),
          "restart must wait until the old clear has been sent"
        );
      } finally {
        finishClear.countDown();
      }
      released.get(5, TimeUnit.SECONDS);
      restarted.get(5, TimeUnit.SECONDS);
      final List<String> expectedOrder = List.of(CLEAR_EVENT, FRAME_EVENT);
      assertEquals(expectedOrder, order);
    }
  }

  private byte[] recordFrame(final List<String> order) {
    order.add(FRAME_EVENT);
    return this.nextFrame.clone();
  }

  private void releaseWithSlowClear(
    final CompressedMapResult result,
    final CountDownLatch clearing,
    final CountDownLatch finishClear,
    final List<String> order
  ) {
    try (final MockedStatic<MapPacketFactory> packets = Mockito.mockStatic(MapPacketFactory.class)) {
      packets.when(() -> MapPacketFactory.clear(this.viewers, 3, 1)).thenAnswer(_ -> this.clearSlowly(clearing, finishClear, order));
      result.release();
    }
  }

  private @Nullable Object clearSlowly(final CountDownLatch clearing, final CountDownLatch finishClear, final List<String> order)
    throws InterruptedException {
    clearing.countDown();
    final boolean clearFinished = finishClear.await(10, TimeUnit.SECONDS);
    assertTrue(clearFinished);

    order.add(CLEAR_EVENT);
    return null;
  }

  private void restart(final CompressedMapResult result, final ImageBuffer image, final CountDownLatch restarting) {
    restarting.countDown();
    result.start();
    result.process(image, this.algorithm);
  }

  @Test
  void shouldLetAnotherThreadInAgainAfterEveryFrameAndAfterTheRelease() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration, LARGE_BYTE_BUDGET);
    result.start();
    final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK);

    result.process(image, this.algorithm);
    final byte[] replayed = this.replayFrames(0, 1);
    assertArrayEquals(this.nextFrame, replayed);

    this.nextFrame = MapPackets.pattern(MAP_SIZE * MAP_SIZE, 19);
    assertTimeoutPreemptively(
      TIMEOUT,
      () -> this.runInAnotherThread(() -> result.process(image, this.algorithm)),
      "a frame that was processed releases the lock again"
    );
    final byte[] replayedAfterOtherThread = this.replayFrames(0, 1);
    assertArrayEquals(this.nextFrame, replayedAfterOtherThread, "the other thread delivered the next picture");

    result.release();
    assertTimeoutPreemptively(TIMEOUT, () -> this.runInAnotherThread(result::release), "releasing releases the lock again");

    final int packets = this.firstViewerPacketCount();
    final boolean sent = packets > 0;
    assertTrue(sent, "both threads got through to the viewers");

    image.close();
  }

  @Test
  void shouldRejectInvalidArguments() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, Sizing.ORIGINAL);
    final CompressedMapResult result = new CompressedMapResult(configuration);
    assertThrows(NullPointerException.class, () -> new CompressedMapResult(null));
    assertThrows(NullPointerException.class, () -> new CompressedMapResult(null, 1));
    assertThrows(IllegalArgumentException.class, () -> new CompressedMapResult(configuration, 0));
    assertThrows(NullPointerException.class, () -> result.process(null, this.algorithm));

    try (final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, BLACK)) {
      assertThrows(NullPointerException.class, () -> result.process(image, null));
    }
  }

  private enum Sizing {
    RESIZED,
    ORIGINAL,
  }

  private enum OfflineFrame {
    SAME,
    CHANGED,
  }
}
