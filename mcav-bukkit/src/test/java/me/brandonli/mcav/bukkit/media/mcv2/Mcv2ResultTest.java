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

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
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
import org.junit.jupiter.api.io.TempDir;

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
  void recordsEveryFrameForTheFlightRecorder(@TempDir final Path directory) throws Exception {
    final Mcv2Result result = this.result(this.configuration, this.algorithm);
    try (Recording recording = new Recording()) {
      recording.enable("me.brandonli.mcav.Mcv2Frame");
      recording.start();
      final long before = System.currentTimeMillis();
      result.start();
      result.applyFilter(Images.solid(64, 32, 0xFF336699), this.metadata);
      this.server.runTasks();
      result.applyFilter(Images.solid(64, 32, 0xFF336699), this.metadata);
      awaitFrames(result, 1);
      result.release();
      recording.stop();
      final Path file = directory.resolve("frames.jfr");
      recording.dump(file);
      final List<RecordedEvent> frames = RecordingFile.readAllEvents(file)
        .stream()
        .filter(event -> event.getEventType().getName().equals("me.brandonli.mcav.Mcv2Frame"))
        .toList();
      assertEquals(1, frames.size());
      final RecordedEvent frame = frames.getFirst();
      assertEquals(0, frame.getLong("frameId"));
      assertTrue(frame.getBoolean("keyframe"));
      assertEquals(1, frame.getInt("sentTo"));
      assertEquals(0, frame.getInt("behind") + frame.getInt("waiting"));
      assertTrue(frame.getInt("colors") > 0 && frame.getInt("bytes") > 48);
      assertTrue(frame.getLong("arrived") >= before && frame.getLong("sent") >= frame.getLong("arrived"));
      // two block centres fit a 64-pixel-wide video: the luma of 0x336699 is (0x33 + 2 * 0x66 + 0x99) / 4 = 0x66
      assertEquals("6666", frame.getString("fingerprint"));
    }
  }

  @Test
  void fingerprintsTheCentresOfTheFirstBlocks() {
    final byte[] rgb = new byte[1024 * 32 * 3];
    // block 1's centre white, the others black
    final int at = (16 * 1024 + 48) * 3;
    rgb[at] = rgb[at + 1] = rgb[at + 2] = (byte) 255;
    final String fingerprint = Mcv2FrameEvent.fingerprint(rgb, 1024, 32);
    assertEquals(2 * Mcv2FrameEvent.FINGERPRINT_PIXELS, fingerprint.length());
    assertEquals("00ff00", fingerprint.substring(0, 6));
    // a video narrower than the blocks, or too short for row 16, has fewer samples
    assertEquals(2, Mcv2FrameEvent.fingerprint(new byte[20 * 20 * 3], 20, 20).length());
    assertEquals("", Mcv2FrameEvent.fingerprint(new byte[64 * 16 * 3], 64, 16));
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
  void dropsAFrameWithMorePagesThanTheScreenHas() throws InterruptedException {
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
    result.send(encoder, new Mcv2Result.Arrival(new byte[128 * 128 * 3], 0), 0);
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
