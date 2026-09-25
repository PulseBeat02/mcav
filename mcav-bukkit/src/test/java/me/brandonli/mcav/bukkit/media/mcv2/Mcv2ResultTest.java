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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.mcv2.encode.EncoderSettings;
import me.brandonli.mcav.media.mcv2.encode.Mcv2Encoder;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import net.minecraft.network.protocol.Packet;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class Mcv2ResultTest {

  private static final UUID WITH_PACK = UUID.fromString("00000000-0000-0000-0000-000000000041");
  private static final UUID WITHOUT = UUID.fromString("00000000-0000-0000-0000-000000000042");

  private FakeServer server;
  private Mcv2Viewers viewers;
  private Mcv2Screen screen;
  private DitherAlgorithm algorithm;
  private OriginalVideoMetadata metadata;
  private Mcv2Configuration configuration;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.addPlayer(WITH_PACK);
    this.server.addPlayer(WITHOUT);
    this.server.injectModule();
    this.viewers = mock(Mcv2Viewers.class);
    when(this.viewers.isLoaded(WITH_PACK)).thenReturn(true);
    this.screen = mock(Mcv2Screen.class);
    when(this.screen.anchors()).thenReturn(List.of());
    this.algorithm = mock(DitherAlgorithm.class);
    when(this.algorithm.ditherIntoBytes(any())).thenAnswer(invocation -> {
      final ImageBuffer image = invocation.getArgument(0);
      return new byte[image.getWidth() * image.getHeight()];
    });
    this.metadata = mock(OriginalVideoMetadata.class);
    this.configuration = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK, WITHOUT))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(500)
      .build();
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private Mcv2Result result(final Mcv2Configuration screenConfiguration, final DitherAlgorithm fallback) {
    return new Mcv2Result(screenConfiguration, new Mcv2Channel(screenConfiguration, this.viewers, this.screen), fallback);
  }

  private static void awaitFrames(final Mcv2Result result, final long frames) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (result.getStatistics().getFrames() < frames && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(frames, result.getStatistics().getFrames());
  }

  @Test
  void encodesForViewersWithThePackAndDithersForTheOthers() throws InterruptedException {
    final Mcv2Result result = this.result(this.configuration, this.algorithm);
    result.start();
    verify(this.screen).build();
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    // the first frame only schedules showing the screen, so both viewers are dithered for
    result.applyFilter(frame, this.metadata);
    verify(this.algorithm).ditherIntoBytes(frame);
    this.server.runTasks();
    assertTrue(result.applyFilter(frame, this.metadata));
    awaitFrames(result, 1);
    // the next frame, of the same size, is a P frame
    result.applyFilter(Images.solid(64, 32, 0xFF336698), this.metadata);
    awaitFrames(result, 2);
    final Mcv2Result.Statistics statistics = result.getStatistics();
    assertEquals(1, statistics.getKeyframes());
    assertEquals(0, statistics.getDropped());
    assertTrue(statistics.getBytes() > 48);
    assertTrue(statistics.getMapBytes() > 0 && statistics.getMapBytes() % 128 == 0);
    assertTrue(statistics.getNanoseconds() > 0);
    final List<Packet<?>> packets = this.server.getSentPackets(WITH_PACK);
    assertEquals(500, MapPackets.unbundle(packets.getLast()).getFirst().mapId().id());
    assertSame(result.getChannel(), result.getChannel());
    result.release();
    verify(this.screen).remove();
  }

  @Test
  void resizesFramesToTheVideoSizeAndCanLeaveOthersWithout() {
    final Mcv2Result result = this.result(this.configuration, null);
    final ImageBuffer wide = Images.solid(128, 64, 0xFF000000);
    result.applyFilter(wide, this.metadata);
    assertEquals(64, wide.getWidth());
    assertEquals(32, wide.getHeight());
    final ImageBuffer tall = Images.solid(64, 16, 0xFF000000);
    result.applyFilter(tall, this.metadata);
    assertEquals(32, tall.getHeight());
    verify(this.algorithm, never()).ditherIntoBytes(any());
    result.release();
  }

  @Test
  void dithersNothingWhenEveryViewerHasThePack() {
    final Mcv2Configuration onlyPack = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(500)
      .build();
    final Mcv2Result result = this.result(onlyPack, this.algorithm);
    final ImageBuffer frame = Images.solid(64, 32, 0xFF000000);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    verify(this.algorithm).ditherIntoBytes(frame);
    result.release();
  }

  @Test
  void keepsItsInterruptWhileReleasing() {
    final Mcv2Result result = this.result(this.configuration, null);
    result.start();
    Thread.currentThread().interrupt();
    result.release();
    assertTrue(Thread.interrupted());
    verify(this.screen).remove();
  }

  @Test
  void dropsAFrameWithMorePagesThanTheScreenHas() {
    final Mcv2Configuration narrow = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .pageMap(500)
      .pageSlots(1)
      .build();
    final Mcv2Result result = this.result(narrow, null);
    final Mcv2Encoder encoder = mock(Mcv2Encoder.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenReturn(Mcv2ChannelTest.large());
    when(encoder.getStats()).thenReturn(new Mcv2Encoder.Stats(1, true, 0, 0, 0, 1, 1));
    result.getChannel().requestKeyframe();
    result.send(encoder, new byte[128 * 128 * 3], 0);
    verify(encoder).requestKeyframe();
    assertEquals(1, result.getStatistics().getDropped());
    assertEquals(0, result.getStatistics().getFrames());
  }

  @Test
  void stopsWaitingOnceReleasedOrInterrupted() throws InterruptedException {
    final Mcv2Result result = this.result(this.configuration, null);
    // not started: nothing to wait for
    assertNull(result.take());
    result.start();
    final Thread waiter = new Thread(() -> result.encodeLoop(mock(Mcv2Encoder.class)));
    waiter.start();
    waiter.interrupt();
    waiter.join(TimeUnit.SECONDS.toMillis(10));
    assertTrue(!waiter.isAlive());
    result.release();
    // a frame handed over after the release is never encoded
    assertNull(result.take());
  }

  @Test
  void convertsArgbToRgb() {
    assertArrayEquals(
      new byte[] { 0x11, 0x22, 0x33, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC },
      Mcv2Result.rgb(new int[] { 0xFF112233, 0x00AABBCC, 7 }, 2)
    );
  }

  @Test
  void refusesNulls() {
    assertThrows(NullPointerException.class, () -> new Mcv2Result(null, this.viewers, null));
    assertThrows(NullPointerException.class, () -> new Mcv2Result(this.configuration, (Mcv2Channel) null, null));
    final Mcv2Result result = new Mcv2Result(this.configuration, this.viewers, this.algorithm);
    assertThrows(NullPointerException.class, () -> result.applyFilter(null, this.metadata));
    assertThrows(NullPointerException.class, () -> result.applyFilter(Images.solid(64, 32, 0), null));
    assertEquals(EncoderSettings.SHIP, this.configuration.getSettings());
  }
}
