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

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link CompressedMapResult}.
 */
final class CompressedMapResultTest {

  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

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
    this.nextFrame = MapPackets.pattern(128 * 128, 4);
    this.algorithm = mock(DitherAlgorithm.class);
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(_ -> this.nextFrame.clone());
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private MapConfiguration createConfiguration(final int columns, final int rows, final boolean resize) {
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
    builder.viewers(this.viewers);
    builder.map(3);
    builder.mapBlockWidth(columns);
    builder.mapBlockHeight(rows);
    builder.mapWidthResolution(64);
    builder.mapHeightResolution(64);
    configureResize(builder, resize);
    return builder.build();
  }

  private static void configureResize(final MapConfiguration.Builder<?> builder, final boolean resize) {
    builder.resize(resize);
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

  private byte[] changedFrame() {
    final byte[] frame = this.nextFrame.clone();
    for (int row = 0; row < 16; row++) {
      for (int column = 0; column < 16; column++) {
        final int index = row * 128 + column;
        frame[index] = (byte) (frame[index] + 1);
      }
    }
    return frame;
  }

  /**
   * Runs work on another thread and waits for it to finish. A lock that the calling thread still holds shows up as a
   * thread that never finishes, because the lock is reentrant only for its owner. Work that fails instead of hanging
   * is reported as well, so a failure on the other thread can never pass unnoticed.
   *
   * @param work the work to run
   * @throws InterruptedException if the test is interrupted while waiting for the thread
   */
  private static void runInAnotherThread(final Runnable work) throws InterruptedException {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread worker = new Thread(work, "map-result-lock-probe");
    worker.setDaemon(true);
    worker.setUncaughtExceptionHandler((_, throwable) -> failure.set(throwable));
    worker.start();
    worker.join();
    final Throwable thrown = failure.get();
    if (thrown != null) {
      throw new AssertionError("the work on the other thread failed", thrown);
    }
  }

  private static byte[] topLeftTile(final byte[] frame) {
    final byte[] tile = new byte[16 * 16];
    for (int row = 0; row < 16; row++) {
      System.arraycopy(frame, row * 128, tile, row * 16, 16);
    }
    return tile;
  }

  @Test
  void sendsTheCompletePictureFirstAndThenOnlyTheChanges() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    result.start();
    final ImageBuffer image = Images.solid(128, 128, 0xFF000000);
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
    final byte[] tileColors = topLeftTile(changed);

    assertEquals(2, count, "an unchanged frame sends nothing");
    MapPackets.assertMapPacket(full, 3, 0, 0, 128, 128, firstFrame);
    MapPackets.assertMapPacket(tile, 3, 0, 0, 16, 16, tileColors);
  }

  @Test
  void sendsThePictureToViewersThatStartWatchingLater() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    final ImageBuffer image = Images.solid(128, 128, 0xFF000000);
    final byte[] firstFrame = this.nextFrame.clone();

    result.process(image, this.algorithm);
    this.server.addPlayer(SECOND);
    PacketUtils.init();
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
    final byte[] tileColors = topLeftTile(changed);

    assertEquals(1, firstCount, "viewers that already watch receive nothing for an unchanged frame");
    MapPackets.assertMapPacket(snapshotPacket, 3, 0, 0, 128, 128, firstFrame);
    MapPackets.assertMapPacket(firstDeltaPacket, 3, 0, 0, 16, 16, tileColors);
    MapPackets.assertMapPacket(secondDeltaPacket, 3, 0, 0, 16, 16, tileColors);
  }

  @Test
  void forgetsViewersThatDisconnected() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    final ImageBuffer image = Images.solid(128, 128, 0xFF000000);
    final byte[] firstFrame = this.nextFrame.clone();

    result.process(image, this.algorithm);
    this.server.removePlayer(FIRST);
    PacketUtils.init();
    result.process(image, this.algorithm);
    this.server.addPlayer(FIRST);
    PacketUtils.init();
    result.process(image, this.algorithm);

    final int count = this.firstViewerPacketCount();
    final List<ClientboundMapItemDataPacket> snapshot = this.packetsOf(FIRST, 0);
    final ClientboundMapItemDataPacket snapshotPacket = snapshot.getFirst();
    assertEquals(1, count);
    MapPackets.assertMapPacket(snapshotPacket, 3, 0, 0, 128, 128, firstFrame);
  }

  private static void applyPacketsToCanvas(
    final byte[] canvas,
    final int columns,
    final int rows,
    final List<ClientboundMapItemDataPacket> packets
  ) {
    final int canvasWidth = columns * 128;
    for (final ClientboundMapItemDataPacket packet : packets) {
      final MapId mapId = packet.mapId();
      final int index = mapId.id() - 3;
      assertTrue(index >= 0 && index < columns * rows, "packet must address the configured map grid");
      final Optional<MapItemSavedData.MapPatch> colorPatch = packet.colorPatch();
      final MapItemSavedData.MapPatch patch = colorPatch.orElseThrow();
      final int mapX = (index % columns) * 128;
      final int mapY = (index / columns) * 128;
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

  private static void placeRectangle(
    final byte[] canvas,
    final int canvasWidth,
    final int x,
    final int y,
    final int width,
    final int height,
    final byte[] colors
  ) {
    for (int row = 0; row < height; row++) {
      final int destination = (y + row) * canvasWidth + x;
      System.arraycopy(colors, row * width, canvas, destination, width);
    }
  }

  @Test
  void startsOverWhenTheFrameSizeChanges() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    final ImageBuffer large = Images.solid(128, 128, 0xFF000000);
    result.process(large, this.algorithm);

    final byte[] smallFrame = MapPackets.pattern(64 * 64, 9);
    this.nextFrame = smallFrame;
    final ImageBuffer small = Images.solid(64, 64, 0xFF000000);
    result.process(small, this.algorithm);

    final int count = this.firstViewerPacketCount();
    final List<ClientboundMapItemDataPacket> restart = this.packetsOf(FIRST, 1);
    final List<ClientboundMapItemDataPacket> original = this.packetsOf(FIRST, 0);
    final byte[] canvas = new byte[128 * 128];
    applyPacketsToCanvas(canvas, 1, 1, original);
    applyPacketsToCanvas(canvas, 1, 1, restart);
    final byte[] expected = new byte[128 * 128];
    placeRectangle(expected, 128, 32, 32, 64, 64, smallFrame);
    assertEquals(2, count, "clear and replacement must share one bundle");
    assertArrayEquals(expected, canvas, "the old border must be transparent after a shrink");
    final int patches = restart.size();
    assertEquals(5, patches, "four rectangular border strips and the new picture must stay compact");

    result.process(small, this.algorithm);
    final int afterUnchanged = this.firstViewerPacketCount();
    assertEquals(2, afterUnchanged, "the reset is not repeated for an unchanged frame");
  }

  @Test
  void preservesPixelsOutsideBothCenteredPictures() {
    final MapConfiguration configuration = this.createConfiguration(2, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    final byte[] originalFrame = MapPackets.pattern(128 * 64, 4);
    this.nextFrame = originalFrame;
    try (final ImageBuffer image = Images.solid(128, 64, 0xFF000000)) {
      result.process(image, this.algorithm);
    }
    final byte[] smallFrame = MapPackets.pattern(64 * 32, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer image = Images.solid(64, 32, 0xFF000000)) {
      result.process(image, this.algorithm);
    }

    final byte[] canvas = new byte[256 * 128];
    Arrays.fill(canvas, (byte) 117);
    final List<ClientboundMapItemDataPacket> original = this.packetsOf(FIRST, 0);
    final List<ClientboundMapItemDataPacket> restart = this.packetsOf(FIRST, 1);
    applyPacketsToCanvas(canvas, 2, 1, original);
    applyPacketsToCanvas(canvas, 2, 1, restart);
    final byte[] expected = new byte[256 * 128];
    Arrays.fill(expected, (byte) 117);
    final byte[] transparent = new byte[128 * 64];
    placeRectangle(expected, 256, 64, 32, 128, 64, transparent);
    placeRectangle(expected, 256, 96, 48, 64, 32, smallFrame);
    assertArrayEquals(expected, canvas, "only pixels occupied by the old or new picture may change");
  }

  @Test
  void clearsFormerlyCoveredMapsForOldAndNewViewersDuringAShrink() {
    final MapConfiguration configuration = this.createConfiguration(3, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    this.nextFrame = MapPackets.pattern(384 * 128, 4);
    try (final ImageBuffer image = Images.solid(384, 128, 0xFF000000)) {
      result.process(image, this.algorithm);
    }
    this.server.addPlayer(SECOND);
    PacketUtils.init();
    final byte[] smallFrame = MapPackets.pattern(64 * 64, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer image = Images.solid(64, 64, 0xFF000000)) {
      result.process(image, this.algorithm);
    }

    final byte[] oldViewerCanvas = new byte[384 * 128];
    final byte[] newViewerCanvas = new byte[384 * 128];
    Arrays.fill(newViewerCanvas, (byte) 118);
    final List<ClientboundMapItemDataPacket> original = this.packetsOf(FIRST, 0);
    final List<ClientboundMapItemDataPacket> oldViewerReset = this.packetsOf(FIRST, 1);
    final List<ClientboundMapItemDataPacket> newViewerReset = this.packetsOf(SECOND, 0);
    applyPacketsToCanvas(oldViewerCanvas, 3, 1, original);
    applyPacketsToCanvas(oldViewerCanvas, 3, 1, oldViewerReset);
    applyPacketsToCanvas(newViewerCanvas, 3, 1, newViewerReset);
    final byte[] expected = new byte[384 * 128];
    placeRectangle(expected, 384, 160, 32, 64, 64, smallFrame);
    assertArrayEquals(expected, oldViewerCanvas);
    assertArrayEquals(expected, newViewerCanvas);
    final int firstCount = this.firstViewerPacketCount();
    final List<Packet<?>> secondPackets = this.server.getSentPackets(SECOND);
    final int secondCount = secondPackets.size();
    assertEquals(2, firstCount, "old viewers receive the clear and replacement together");
    assertEquals(1, secondCount, "joining viewers receive the same reset in one bundle");
  }

  @Test
  void keepsTheNormalByteBudgetAfterOneTimeResizeCleanup() {
    final MapConfiguration configuration = this.createConfiguration(2, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1);
    this.nextFrame = MapPackets.pattern(256 * 128, 4);
    try (final ImageBuffer image = Images.solid(256, 128, 0xFF000000)) {
      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
    }
    final byte[] smallFrame = MapPackets.pattern(128 * 64, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer image = Images.solid(128, 64, 0xFF000000)) {
      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
    }

    final List<ClientboundMapItemDataPacket> next = this.packetsOf(FIRST, 3);
    final int nextCount = next.size();
    final int totalCount = this.firstViewerPacketCount();
    assertEquals(1, nextCount, "ordinary frames retain the encoder's one-urgent-map exception");
    assertEquals(4, totalCount, "an unchanged completed frame sends nothing");
    final byte[] canvas = new byte[256 * 128];
    for (int index = 0; index < totalCount; index++) {
      final List<ClientboundMapItemDataPacket> packets = this.packetsOf(FIRST, index);
      applyPacketsToCanvas(canvas, 2, 1, packets);
    }
    final byte[] expected = new byte[256 * 128];
    placeRectangle(expected, 256, 64, 32, 128, 64, smallFrame);
    assertArrayEquals(expected, canvas);
  }

  @Test
  void clearsTheClippedOldPictureWhenAnOversizedFrameShrinks() {
    final MapConfiguration configuration = this.createConfiguration(2, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    this.nextFrame = MapPackets.pattern(512 * 256, 4);
    try (final ImageBuffer image = Images.solid(512, 256, 0xFF000000)) {
      result.process(image, this.algorithm);
    }
    final byte[] smallFrame = MapPackets.pattern(64 * 64, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer image = Images.solid(64, 64, 0xFF000000)) {
      result.process(image, this.algorithm);
    }

    final List<ClientboundMapItemDataPacket> original = this.packetsOf(FIRST, 0);
    final List<ClientboundMapItemDataPacket> reset = this.packetsOf(FIRST, 1);
    final byte[] canvas = new byte[256 * 128];
    applyPacketsToCanvas(canvas, 2, 1, original);
    applyPacketsToCanvas(canvas, 2, 1, reset);
    final byte[] expected = new byte[256 * 128];
    placeRectangle(expected, 256, 96, 32, 64, 64, smallFrame);
    assertArrayEquals(expected, canvas);
  }

  @Test
  void clearsHistoricalBordersWhenAViewerMissedTheResize() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    try (final ImageBuffer image = Images.solid(128, 128, 0xFF000000)) {
      result.process(image, this.algorithm);
    }
    this.viewers.remove(FIRST);
    final byte[] smallFrame = MapPackets.pattern(64 * 64, 9);
    this.nextFrame = smallFrame;
    try (final ImageBuffer image = Images.solid(64, 64, 0xFF000000)) {
      result.process(image, this.algorithm);
      final int beforeRejoining = this.firstViewerPacketCount();
      assertEquals(1, beforeRejoining, "an absent viewer receives no resize packets");
      this.viewers.add(FIRST);
      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
    }

    final byte[] canvas = new byte[128 * 128];
    final List<ClientboundMapItemDataPacket> original = this.packetsOf(FIRST, 0);
    final List<ClientboundMapItemDataPacket> rejoined = this.packetsOf(FIRST, 1);
    applyPacketsToCanvas(canvas, 1, 1, original);
    applyPacketsToCanvas(canvas, 1, 1, rejoined);
    final byte[] expected = new byte[128 * 128];
    placeRectangle(expected, 128, 32, 32, 64, 64, smallFrame);
    assertArrayEquals(expected, canvas);
    final int count = this.firstViewerPacketCount();
    assertEquals(2, count, "cleanup is sent once when the viewer rejoins");
  }

  @Test
  void retainsExactCoverageAcrossSeveralResizesWithoutClaimingUnviewedPixels() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    this.nextFrame = MapPackets.pattern(128 * 32, 4);
    try (final ImageBuffer image = Images.solid(128, 32, 0xFF000000)) {
      result.process(image, this.algorithm);
    }
    this.nextFrame = MapPackets.pattern(32 * 128, 7);
    try (final ImageBuffer image = Images.solid(32, 128, 0xFF000000)) {
      result.process(image, this.algorithm);
    }
    this.viewers.remove(FIRST);
    // This whole-map frame has no connected configured viewer; its untouched corners must not become owned.
    this.nextFrame = MapPackets.pattern(128 * 128, 11);
    try (final ImageBuffer image = Images.solid(128, 128, 0xFF000000)) {
      result.process(image, this.algorithm);
    }
    final byte[] finalFrame = MapPackets.pattern(64 * 16, 13);
    this.nextFrame = finalFrame;
    try (final ImageBuffer image = Images.solid(64, 16, 0xFF000000)) {
      result.process(image, this.algorithm);
      this.viewers.add(FIRST);
      result.process(image, this.algorithm);
      result.process(image, this.algorithm);
    }

    final byte[] canvas = new byte[128 * 128];
    Arrays.fill(canvas, (byte) 117);
    final int count = this.firstViewerPacketCount();
    assertEquals(3, count, "wide, tall, and one rejoin snapshot; no unviewed or repeated reset");
    for (int index = 0; index < count; index++) {
      final List<ClientboundMapItemDataPacket> packets = this.packetsOf(FIRST, index);
      applyPacketsToCanvas(canvas, 1, 1, packets);
    }
    final byte[] expected = new byte[128 * 128];
    Arrays.fill(expected, (byte) 117);
    final byte[] wideClear = new byte[128 * 32];
    final byte[] tallClear = new byte[32 * 128];
    placeRectangle(expected, 128, 0, 48, 128, 32, wideClear);
    placeRectangle(expected, 128, 48, 0, 32, 128, tallClear);
    placeRectangle(expected, 128, 32, 56, 64, 16, finalFrame);
    assertArrayEquals(expected, canvas, "the union is a cross, not its whole-map bounding rectangle");
  }

  @Test
  void resizesFramesWhenConfigured() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, true);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    final byte[] frame = MapPackets.pattern(64 * 64, 7);
    this.nextFrame = frame;
    final ImageBuffer image = Images.solid(128, 128, 0xFF000000);

    result.process(image, this.algorithm);

    final List<ClientboundMapItemDataPacket> packets = this.packetsOf(FIRST, 0);
    final ClientboundMapItemDataPacket packet = packets.getFirst();
    final int width = image.getWidth();
    assertEquals(64, width);
    MapPackets.assertMapPacket(packet, 3, 32, 32, 64, 64, frame);
  }

  @Test
  void sendsAtMostTheDefaultBudgetPerFrame() {
    final MapConfiguration configuration = this.createConfiguration(3, 3, false);
    final CompressedMapResult result = new CompressedMapResult(configuration);
    this.nextFrame = MapPackets.pattern(384 * 384, 4);
    final ImageBuffer image = Images.solid(384, 384, 0xFF000000);

    result.process(image, this.algorithm);

    final List<ClientboundMapItemDataPacket> packets = this.packetsOf(FIRST, 0);
    final int mapCount = packets.size();
    assertEquals(7, mapCount, "seven complete maps fit into 128 KiB, the eighth does not");
  }

  @Test
  void spreadsLargeUpdatesOverSeveralFramesWithASmallBudget() {
    final MapConfiguration configuration = this.createConfiguration(2, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1);
    this.nextFrame = MapPackets.pattern(256 * 128, 4);
    final ImageBuffer image = Images.solid(256, 128, 0xFF000000);

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
  void dithersParallelAlgorithmsOnItsOwnDaemonThreads() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    final List<ForkJoinPool> pools = new CopyOnWriteArrayList<>();
    final List<Thread> workers = new CopyOnWriteArrayList<>();
    final ParallelDitherAlgorithm parallel = createRecordingAlgorithm(pools, workers);
    final ImageBuffer image = Images.solid(128, 128, 0xFF000000);

    result.process(image, parallel);
    result.process(image, parallel);
    final ForkJoinPool firstPool = pools.get(0);
    final ForkJoinPool secondPool = pools.get(1);
    final Thread worker = workers.getFirst();
    final String workerName = worker.getName();
    final boolean daemon = worker.isDaemon();
    final boolean namedWorker = workerName.startsWith("mcav-map-dither-");
    final boolean shutDownBeforeRelease = firstPool.isShutdown();
    result.release();
    final boolean shutDownAfterRelease = firstPool.isShutdown();

    assertSame(firstPool, secondPool, "the pool is reused");
    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final int parallelism = firstPool.getParallelism();
    assertTrue(parallelism <= Math.max(1, processors / 2), "map dithering leaves at least half the CPUs for the server");
    assertTrue(namedWorker, workerName);
    assertTrue(daemon, "dither threads never keep the server alive");
    assertFalse(shutDownBeforeRelease);
    assertTrue(shutDownAfterRelease);
    verify(parallel, never()).ditherIntoBytes(any(ImageBuffer.class));
  }

  /**
   * Creates a parallel algorithm that records the pool it receives and the worker thread that runs a task in it.
   */
  private static ParallelDitherAlgorithm createRecordingAlgorithm(final List<ForkJoinPool> pools, final List<Thread> workers) {
    final ParallelDitherAlgorithm parallel = mock(ParallelDitherAlgorithm.class);
    when(parallel.ditherIntoBytes(any(ImageBuffer.class), any(ForkJoinPool.class))).thenAnswer(invocation -> {
      final ForkJoinPool pool = invocation.getArgument(1);
      final ForkJoinTask<Thread> task = pool.submit(Thread::currentThread);
      final Thread worker = task.get();
      pools.add(pool);
      workers.add(worker);
      return MapPackets.pattern(128 * 128, 4);
    });
    return parallel;
  }

  @Test
  void logsExceptionsThatEscapeTheDitherThreads() throws InterruptedException {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    final RuntimeException failure = new RuntimeException("escaped the dither pool");
    final ParallelDitherAlgorithm parallel = createFailingAlgorithm(failure);

    final LogCapture.RecordedEvent event;
    try (final LogCapture logs = LogCapture.capture(CompressedMapResult.class)) {
      final ImageBuffer image = Images.solid(128, 128, 0xFF000000);
      result.process(image, parallel);
      event = logs.awaitEvent(TIMEOUT);
    } finally {
      result.release();
    }

    final Level level = event.getLevel();
    final String message = event.getMessage();
    final Throwable thrown = event.getThrown();
    final boolean namesTheThread = message.startsWith("Uncaught exception in map dithering thread mcav-map-dither-");
    assertEquals(Level.ERROR, level);
    assertTrue(namesTheThread, message);
    assertSame(failure, thrown);
  }

  /**
   * Creates a parallel algorithm that runs a task in the pool which throws the failure out of its worker thread, so
   * the pool hands it to the uncaught exception handler of that thread.
   */
  private static ParallelDitherAlgorithm createFailingAlgorithm(final RuntimeException failure) {
    final ParallelDitherAlgorithm parallel = mock(ParallelDitherAlgorithm.class);
    when(parallel.ditherIntoBytes(any(ImageBuffer.class), any(ForkJoinPool.class))).thenAnswer(invocation -> {
      final ForkJoinPool pool = invocation.getArgument(1);
      pool.execute(() -> {
        throw failure;
      });
      return MapPackets.pattern(128 * 128, 4);
    });
    return parallel;
  }

  @Test
  void clearsTheMapsWhenReleasedAndIgnoresLaterFrames() throws ReflectiveOperationException {
    final MapConfiguration configuration = this.createConfiguration(2, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    this.nextFrame = MapPackets.pattern(256 * 128, 4);
    final ImageBuffer image = Images.solid(256, 128, 0xFF000000);

    result.process(image, this.algorithm);
    result.release();
    result.release();
    result.process(image, this.algorithm);

    final int count = this.firstViewerPacketCount();
    final List<ClientboundMapItemDataPacket> cleared = this.packetsOf(FIRST, 1);
    final byte[] transparent = new byte[128 * 128];
    final int clearedCount = cleared.size();
    final ClientboundMapItemDataPacket firstCleared = cleared.get(0);
    final ClientboundMapItemDataPacket secondCleared = cleared.get(1);
    assertEquals(2, count, "the frame and one clear, nothing afterwards");
    assertEquals(2, clearedCount);
    MapPackets.assertMapPacket(firstCleared, 3, 0, 0, 128, 128, transparent);
    MapPackets.assertMapPacket(secondCleared, 4, 0, 0, 128, 128, transparent);
    final java.lang.reflect.Field retainedField = CompressedMapResult.class.getDeclaredField("sentPixels");
    retainedField.setAccessible(true);
    final java.util.Map<?, ?> retained = (java.util.Map<?, ?>) retainedField.get(result);
    assertTrue(retained.isEmpty(), "released displays must not retain obsolete rendering state");
    final java.lang.reflect.Field viewersField = CompressedMapResult.class.getDeclaredField("activeViewers");
    viewersField.setAccessible(true);
    final java.util.Set<?> retainedViewers = (java.util.Set<?>) viewersField.get(result);
    assertTrue(retainedViewers.isEmpty(), "release drops the old viewer snapshot");
  }

  @Test
  void showsASecondVideoAfterItWasReleasedAndStartedAgain() {
    // every other result of this module can be started again; this one latched `released` for good, so a reused
    // result silently rendered nothing at all for the second video
    final MapConfiguration configuration = this.createConfiguration(2, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    this.nextFrame = MapPackets.pattern(256 * 128, 4);
    final ImageBuffer image = Images.solid(256, 128, 0xFF000000);

    result.process(image, this.algorithm);
    result.release();
    final int afterRelease = this.firstViewerPacketCount();

    result.start();
    result.process(image, this.algorithm);
    final int afterRestart = this.firstViewerPacketCount();

    assertTrue(afterRestart > afterRelease, "a started result sends frames again instead of dropping them");
  }

  @Test
  void clearsEveryMapOfTheGridOnTheFirstRelease() {
    final MapConfiguration configuration = this.createConfiguration(2, 2, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);

    result.release();

    final int count = this.firstViewerPacketCount();
    final List<ClientboundMapItemDataPacket> cleared = this.packetsOf(FIRST, 0);
    final int clearedCount = cleared.size();
    final ClientboundMapItemDataPacket last = cleared.get(3);
    final byte[] transparent = new byte[128 * 128];

    assertEquals(1, count, "the first release clears the maps at once");
    assertEquals(4, clearedCount, "every map of the 2x2 grid is cleared");
    MapPackets.assertMapPacket(last, 6, 0, 0, 128, 128, transparent);
  }

  @Test
  void finishesTheOldClearBeforeAConcurrentRestartCanSendFrames() throws Exception {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration);
    final CountDownLatch clearing = new CountDownLatch(1);
    final CountDownLatch finishClear = new CountDownLatch(1);
    final CountDownLatch restarting = new CountDownLatch(1);
    final List<String> order = new CopyOnWriteArrayList<>();
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(_ -> {
      order.add("frame");
      return this.nextFrame.clone();
    });
    try (final ExecutorService workers = Executors.newFixedThreadPool(2); final ImageBuffer image = Images.solid(128, 128, 0xFF000000)) {
      final CompletableFuture<Void> released = CompletableFuture.runAsync(
        () -> {
          try (final MockedStatic<MapPacketFactory> packets = Mockito.mockStatic(MapPacketFactory.class)) {
            packets
              .when(() -> MapPacketFactory.clear(this.viewers, 3, 1))
              .thenAnswer(_ -> {
                clearing.countDown();
                assertTrue(finishClear.await(10, TimeUnit.SECONDS));
                order.add("clear");
                return null;
              });
            result.release();
          }
        },
        workers
      );
      final CompletableFuture<Void> restarted;
      try {
        assertTrue(clearing.await(5, TimeUnit.SECONDS));
        restarted = CompletableFuture.runAsync(
          () -> {
            restarting.countDown();
            result.start();
            result.process(image, this.algorithm);
          },
          workers
        );
        assertTrue(restarting.await(5, TimeUnit.SECONDS));
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
      assertEquals(List.of("clear", "frame"), order);
    }
  }

  @Test
  void letsAnotherThreadInAgainAfterEveryFrameAndAfterTheRelease() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration, 1 << 20);
    result.start();
    final ImageBuffer image = Images.solid(128, 128, 0xFF000000);

    result.process(image, this.algorithm);
    assertTimeoutPreemptively(
      TIMEOUT,
      () -> runInAnotherThread(() -> result.process(image, this.algorithm)),
      "a frame that was processed releases the lock again"
    );

    result.release();
    assertTimeoutPreemptively(TIMEOUT, () -> runInAnotherThread(result::release), "releasing releases the lock again");

    final int packets = this.firstViewerPacketCount();
    final boolean sent = packets > 0;
    assertTrue(sent, "both threads got through to the viewers");
  }

  @Test
  void rejectsInvalidArguments() {
    final MapConfiguration configuration = this.createConfiguration(1, 1, false);
    final CompressedMapResult result = new CompressedMapResult(configuration);
    assertThrows(NullPointerException.class, () -> new CompressedMapResult(null));
    assertThrows(NullPointerException.class, () -> new CompressedMapResult(null, 1));
    assertThrows(IllegalArgumentException.class, () -> new CompressedMapResult(configuration, 0));
    assertThrows(NullPointerException.class, () -> result.process(null, this.algorithm));

    try (final ImageBuffer image = Images.solid(128, 128, 0xFF000000)) {
      assertThrows(NullPointerException.class, () -> result.process(image, null));
    }
  }
}
