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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.mcv2.encode.EncoderPool;
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
import org.mockito.Mockito;

final class Mcv2ResultTest {

  private static final UUID WITH_PACK = UUID.fromString("00000000-0000-0000-0000-000000000041");
  private static final UUID WITHOUT = UUID.fromString("00000000-0000-0000-0000-000000000042");
  /** A location holds its world weakly; a world a test builds screens in again must stay reachable. */
  private static final World WORLD = mock(World.class);

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
    // the first frame only schedules showing the screen, so both viewers are dithered for, on the dithering thread
    result.applyFilter(frame, this.metadata);
    verify(this.algorithm, timeout(5000)).ditherIntoBytes(any());
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
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run
    );
    final ImageBuffer frame = Images.solid(64, 32, 0xFF000000);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    verify(this.algorithm).ditherIntoBytes(any());
    result.release();
  }

  @Test
  void dithersOffTheVideoThreadAndLeavesOutTheFramesMeanwhile() throws InterruptedException {
    final CountDownLatch dithering = new CountDownLatch(1);
    final CountDownLatch finish = new CountDownLatch(1);
    Mockito.doAnswer(invocation -> {
      dithering.countDown();
      finish.await();
      final ImageBuffer image = invocation.getArgument(0);
      return new byte[image.getWidth() * image.getHeight()];
    })
      .when(this.algorithm)
      .ditherIntoBytes(any());
    final Mcv2Result result = this.result(this.configuration, this.algorithm);
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    // the frame is handed over and the video goes on while it is dithered; the frames meanwhile are left out
    assertTrue(result.applyFilter(frame, this.metadata));
    assertTrue(dithering.await(5, TimeUnit.SECONDS));
    assertTrue(result.applyFilter(frame, this.metadata));
    assertTrue(result.applyFilter(frame, this.metadata));
    finish.countDown();
    verify(this.algorithm, timeout(5000).times(1)).ditherIntoBytes(any());
    // once the dithering is done, the next frame is dithered again
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (Mockito.mockingDetails(this.algorithm).getInvocations().size() < 2 && System.nanoTime() < deadline) {
      result.applyFilter(frame, this.metadata);
      Thread.sleep(10);
    }
    verify(this.algorithm, Mockito.atLeast(2)).ditherIntoBytes(any());
    // a released result dithers nothing more
    result.release();
    clearInvocations(this.algorithm);
    result.applyFilter(frame, this.metadata);
    Thread.sleep(100);
    verify(this.algorithm, never()).ditherIntoBytes(any());
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
    result.send(encoder, new Mcv2Result.Arrival(new byte[128 * 128 * 3], 128, 128, 0), 0);
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

  /**
   * Plays frames at 60 frames a second on the test's clock and encodes every frame the result hands over.
   *
   * @return how many frames were encoded
   */
  private int play(final Mcv2Result result, final AtomicLong clock, final Mcv2Encoder encoder, final ImageBuffer frame, final int frames)
    throws InterruptedException {
    int encoded = 0;
    for (int i = 0; i < frames; i++) {
      final long arrival = clock.addAndGet(16_666_667L);
      result.applyFilter(frame, this.metadata);
      final Mcv2Result.Arrival handed = result.poll();
      if (handed != null) {
        result.send(encoder, handed, i);
        encoded++;
      }
      // the encode ran on the clock; the video's next frame comes a frame after this one, whatever it took
      clock.set(arrival);
    }
    return encoded;
  }

  @Test
  void pacesTheFramesToWhatTheBudgetSustains() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
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
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    final List<Mcv2Pacer.Change> changes = new ArrayList<>();
    result.setPacingListener(changes::add);
    assertNull(result.getRung());
    result.pace();
    assertEquals(new Mcv2Pacer.Rung(64, 32, 1), result.getRung());
    final AtomicLong encodeNanos = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final Mcv2Encoder encoder = mock(Mcv2Encoder.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> {
      clock.addAndGet(encodeNanos.get());
      return Mcv2ChannelTest.keyframe();
    });
    when(encoder.getStats()).thenReturn(new Mcv2Encoder.Stats(1, false, 0, 0, 0, 1, 1));
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    // the viewer with the pack is shown the screen, dithered for until then, then every frame is encoded while 5 ms
    // fit a frame's 16.7 ms
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    // five seconds while a new encoder warms up: the pacer judges nothing yet
    assertEquals(300, this.play(result, clock, encoder, frame, 300));
    verify(this.algorithm, times(1)).ditherIntoBytes(any());
    // at 25 ms a frame the screen steps down to every other frame, and says why
    encodeNanos.set(TimeUnit.MILLISECONDS.toNanos(25));
    this.play(result, clock, encoder, frame, 90);
    assertEquals(1, changes.size());
    assertTrue(changes.getFirst().down());
    assertEquals(new Mcv2Pacer.Rung(64, 32, 2), result.getRung());
    assertEquals(30, this.play(result, clock, encoder, frame, 60));
    // at two seconds a frame nothing fits: every viewer is dithered for, and the page frames go
    encodeNanos.set(TimeUnit.SECONDS.toNanos(2));
    this.play(result, clock, encoder, frame, 90);
    assertEquals(2, changes.size());
    assertEquals(Mcv2Pacer.Rung.DITHERED, result.getRung());
    this.server.runTasks();
    verify(this.screen).remove();
    clearInvocations(this.algorithm);
    assertEquals(0, this.play(result, clock, encoder, frame, 60));
    verify(this.algorithm, times(60)).ditherIntoBytes(any());
    // thirty seconds later the screen tries encoding again: the page frames return
    encodeNanos.set(TimeUnit.MILLISECONDS.toNanos(5));
    this.play(result, clock, encoder, frame, 35 * 60);
    assertEquals(Mcv2Pacer.Rung.DITHERED, changes.get(2).from());
    this.server.runTasks();
    verify(this.screen).build();
    assertFalse(result.getRung().isDithered());
    result.release();
    assertNull(result.getRung());
  }

  @Test
  void stepsDownToASmallerVideoAndBackUp() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration onlyPack = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(WORLD, 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(500)
      .build();
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    // the owner offers a 32x16 screen: its own channel and page frames
    final Mcv2Screen smallScreen = mock(Mcv2Screen.class);
    when(smallScreen.anchors()).thenReturn(List.of());
    final List<Mcv2Configuration> resized = new ArrayList<>();
    result.setSmallerSizes(List.of(new int[] { 32, 16 }), configuration -> {
      resized.add(configuration);
      return new Mcv2Channel(configuration, this.viewers, configuration.getVideoWidth() == 32 ? smallScreen : this.screen);
    });
    result.pace();
    // an encode takes 100 ms at 64x32 and a quarter of that at 32x16
    final AtomicLong fullNanos = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final List<Integer> widths = new ArrayList<>();
    final Mcv2Encoder encoder = mock(Mcv2Encoder.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(invocation -> {
      final int width = invocation.getArgument(1);
      widths.add(width);
      clock.addAndGet((fullNanos.get() * width * (int) invocation.getArgument(2)) / (64 * 32));
      return Mcv2ChannelTest.keyframe();
    });
    when(encoder.getStats()).thenReturn(new Mcv2Encoder.Stats(1, false, 0, 0, 0, 1, 1));
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 300);
    fullNanos.set(TimeUnit.MILLISECONDS.toNanos(100));
    this.play(result, clock, encoder, frame, 120);
    // no frame rate of 64x32 fits 100 ms with room; 32x16 at 30 fps does
    assertEquals(new Mcv2Pacer.Rung(32, 16, 2), result.getRung());
    this.server.runTasks();
    verify(this.screen).remove();
    verify(smallScreen).build();
    assertEquals(1, resized.size());
    assertEquals(32, resized.getFirst().getVideoWidth());
    assertEquals(16, result.getConfiguration().getVideoHeight());
    // the viewer is shown the smaller screen, and the frames are encoded at its size
    result.applyFilter(Images.solid(64, 32, 0xFF336699), this.metadata);
    this.server.runTasks();
    widths.clear();
    this.play(result, clock, encoder, Images.solid(64, 32, 0xFF336699), 60);
    assertTrue(!widths.isEmpty() && widths.stream().allMatch(width -> width == 32), widths.toString());
    // the load drops: back to the size it was asked for, once the top rung may be tried again
    fullNanos.set(TimeUnit.MILLISECONDS.toNanos(4));
    this.play(result, clock, encoder, Images.solid(64, 32, 0xFF336699), 60 * 30);
    assertEquals(new Mcv2Pacer.Rung(64, 32, 1), result.getRung());
    this.server.runTasks();
    verify(smallScreen).remove();
    assertEquals(64, result.getConfiguration().getVideoWidth());
    // bringing the screen in line again changes nothing, nor does it once the result is released
    result.sync();
    result.release();
    result.sync();
    assertThrows(NullPointerException.class, () -> result.setSmallerSizes(null, configuration -> null));
    assertThrows(NullPointerException.class, () -> result.setSmallerSizes(List.of(), null));
  }

  @Test
  void staysAtItsLowestFrameRateWithoutDitheredMaps() throws InterruptedException {
    final AtomicLong clock = new AtomicLong();
    final Mcv2Result result = new Mcv2Result(
      this.configuration,
      new Mcv2Channel(this.configuration, this.viewers, this.screen),
      null,
      clock::get,
      Runnable::run
    );
    result.pace();
    final Mcv2Encoder encoder = mock(Mcv2Encoder.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> {
      clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
      return Mcv2ChannelTest.keyframe();
    });
    when(encoder.getStats()).thenReturn(new Mcv2Encoder.Stats(1, false, 0, 0, 0, 1, 1));
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 600);
    assertEquals(new Mcv2Pacer.Rung(64, 32, 6), result.getRung());
    result.release();
  }

  @Test
  void encodesInTheBudgetItIsGiven() throws InterruptedException {
    try (EncoderPool budget = new EncoderPool(1)) {
      final Mcv2Configuration own = Mcv2Configuration.builder()
        .viewers(List.of(WITH_PACK))
        .origin(new Location(mock(World.class), 0, 64, 0))
        .facing(BlockFace.SOUTH)
        .map(100)
        .columns(1)
        .rows(1)
        .video(64, 32)
        .pageMap(500)
        .encoderPool(budget)
        .build();
      assertSame(budget, own.getEncoderPool());
      assertSame(EncoderPool.shared(), this.configuration.getEncoderPool());
      final Mcv2Result result = this.result(own, null);
      result.start();
      final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
      result.applyFilter(frame, this.metadata);
      this.server.runTasks();
      result.applyFilter(frame, this.metadata);
      awaitFrames(result, 1);
      result.release();
    }
    assertThrows(NullPointerException.class, () -> Mcv2Configuration.builder().encoderPool(null));
    assertThrows(NullPointerException.class, () -> this.result(this.configuration, null).setPacingListener(null));
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
