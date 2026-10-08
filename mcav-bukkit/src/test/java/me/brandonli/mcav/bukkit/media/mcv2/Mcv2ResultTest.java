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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import net.minecraft.network.protocol.Packet;
import org.apache.logging.log4j.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

final class Mcv2ResultTest {

  private static final UUID WITH_PACK = UUID.fromString("00000000-0000-0000-0000-000000000041");

  private static final UUID WITHOUT = UUID.fromString("00000000-0000-0000-0000-000000000042");

  /** A location holds its world weakly; a world a test builds screens in again must stay reachable. */
  private static final World WORLD = mock(World.class);

  /** Another world, which a viewer can be in. */
  private static final World ELSEWHERE = mock(World.class);

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
      // one preset, so the pacer steps only frame rates and sizes; the tests of the preset ladder give their own
      .settings(Settings.FAST)
      // every frame the tests give is the screen's; the rate is tested on its own
      .maxFrameRate(0)
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
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    final Mcv2Result result = new Mcv2Result(this.configuration, channel, this.algorithm);
    try {
      final List<byte[]> heard = new CopyOnWriteArrayList<>();
      result.setFrameListener(heard::add);
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
      assertSame(channel, result.getChannel());
      // the listener heard both frames sent, a keyframe first
      assertEquals(2, heard.size());
      assertEquals(statistics.getBytes(), heard.getFirst().length + heard.getLast().length);
    } finally {
      result.release();
    }
    verify(this.screen).remove();
  }

  @Test
  void recordsEveryFrameForTheFlightRecorder(@TempDir final Path directory) throws Exception {
    // a screen that took over a pack slot numbers its frames from where the pack server said
    final Mcv2Configuration slotted = this.configuration.withSlot(
      this.configuration.getStreamId(),
      this.configuration.getPageMap(),
      4_000_000_000L
    );
    final Mcv2Result result = this.result(slotted, this.algorithm);
    try (final Recording recording = new Recording()) {
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
      assertEquals(4_000_000_000L, frame.getLong("frameId"));
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
    // another width alone is another size too
    final ImageBuffer wider = Images.solid(128, 32, 0xFF000000);
    result.applyFilter(wider, this.metadata);
    assertEquals(64, wider.getWidth());
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
      .maxFrameRate(0)
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
      .maxFrameRate(0)
      .build();
    final Mcv2Result result = this.result(narrow, null);
    final MCV2 encoder = mock(MCV2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenReturn(Mcv2ChannelTest.large());
    when(encoder.getStats()).thenReturn(new MCV2.Stats(1, true, 1, 1, 72));
    result.getChannel().requestKeyframe();
    final List<byte[]> heard = new ArrayList<>();
    result.setFrameListener(heard::add);
    result.send(encoder, new Mcv2Result.Arrival(new byte[128 * 128 * 3], 128, 128, 0, Mcv2Pacer.Preset.ONLY), 0);
    verify(encoder).requestKeyframe();
    assertEquals(List.of(), heard, "a frame that is not sent is not heard");
    assertEquals(1, result.getStatistics().getDropped());
    assertEquals(0, result.getStatistics().getFrames());
  }

  @Test
  void anEncodeLoopThatFindsNoPipelineEndsAtOnce() throws InterruptedException {
    final Mcv2Result result = this.result(this.configuration, null);
    // the screen's thread of a start that a release overtook finds no pipeline to stop
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> result.encodeLoop(mock(MCV2.class)));
    assertNull(result.take());
  }

  @Test
  void stopsWaitingOnceReleasedOrInterrupted() throws InterruptedException {
    final Mcv2Result result = this.result(this.configuration, null);
    // not started: nothing to wait for
    assertNull(result.take());
    result.start();
    final Thread waiter = new Thread(() -> result.encodeLoop(mock(MCV2.class)));
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
  private int play(final Mcv2Result result, final AtomicLong clock, final MCV2 encoder, final ImageBuffer frame, final int frames)
    throws InterruptedException {
    int encoded = 0;
    for (int frameNumber = 0; frameNumber < frames; frameNumber++) {
      final long arrival = clock.addAndGet(16_666_667L);
      result.applyFilter(frame, this.metadata);
      final Mcv2Result.Arrival handed = result.poll();
      if (handed != null) {
        result.send(encoder, handed, frameNumber);
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
      // one preset, so the pacer steps the frame rate and the size, which this is about
      .settings(Settings.FAST)
      .maxFrameRate(0)
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
    final MCV2 encoder = mock(MCV2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> {
      clock.addAndGet(encodeNanos.get());
      return Mcv2ChannelTest.keyframe();
    });
    when(encoder.getStats()).thenReturn(new MCV2.Stats(1, false, 1, 1, 72));
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
      // one preset, so the pacer steps the frame rate and the size, which this is about
      .settings(Settings.FAST)
      .maxFrameRate(0)
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
    final MCV2 encoder = mock(MCV2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(invocation -> {
      final int width = invocation.getArgument(1);
      widths.add(width);
      clock.addAndGet((fullNanos.get() * width * (int) invocation.getArgument(2)) / (64 * 32));
      return Mcv2ChannelTest.keyframe();
    });
    when(encoder.getStats()).thenReturn(new MCV2.Stats(1, false, 1, 1, 72));
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
    final MCV2 encoder = mock(MCV2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> {
      clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
      return Mcv2ChannelTest.keyframe();
    });
    when(encoder.getStats()).thenReturn(new MCV2.Stats(1, false, 1, 1, 72));
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 600);
    assertEquals(new Mcv2Pacer.Rung(64, 32, 6), result.getRung());
    result.release();
  }

  @Test
  void encodesInTheBudgetItIsGiven() throws InterruptedException {
    try (final Pool budget = new Pool(1)) {
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
        .maxFrameRate(0)
        .build();
      assertSame(budget, own.getEncoderPool());
      assertSame(Pool.shared(), this.configuration.getEncoderPool());
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
    assertThrows(NullPointerException.class, () -> this.result(this.configuration, null).setFrameListener(null));
  }

  @Test
  void thinsASourceFasterThanTheScreensRate() {
    final Mcv2Result result = this.result(
      Mcv2Configuration.builder()
        .viewers(List.of(WITH_PACK))
        .origin(new Location(mock(World.class), 0, 64, 0))
        .facing(BlockFace.SOUTH)
        .map(100)
        .columns(1)
        .rows(1)
        .video(64, 32)
        .pageMap(500)
        .build(),
      null
    );
    final long millisecond = TimeUnit.MILLISECONDS.toNanos(1);
    // a browser painting every 5 ms: 30 frames of a second's 200
    int taken = 0;
    for (long now = 0; now < 1000 * millisecond; now += 5 * millisecond) {
      taken += result.takeFrame(now) ? 1 : 0;
    }
    assertTrue(taken >= 30 && taken <= 32, "frames taken: " + taken);
    // a 30 fps source with jitter keeps every frame
    long now = 5000 * millisecond;
    result.takeFrame(now);
    for (int frame = 0; frame < 60; frame++) {
      now += (frame % 2 == 0 ? 30 : 36) * millisecond + millisecond / 2;
      assertTrue(result.takeFrame(now), "frame " + frame);
    }
    // frames saved up during a pause do not come out as a burst
    now += 2000 * millisecond;
    assertTrue(result.takeFrame(now));
    assertFalse(result.takeFrame(now + millisecond));
    // and a frame that is not the screen's goes nowhere: of two frames at once, the second
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    assertFalse(result.applyFilter(frame, this.metadata));
  }

  @Test
  void convertsBgrToRgbFromThePositionOfTheBuffer() {
    final ByteBuffer bgr = ByteBuffer.wrap(new byte[] { 9, 0x33, 0x22, 0x11, (byte) 0xCC, (byte) 0xBB, (byte) 0xAA, 7 });
    bgr.position(1);
    final byte[] rgb = Mcv2Result.rgb(bgr, 2);

    assertArrayEquals(new byte[] { 0x11, 0x22, 0x33, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC }, rgb);
    assertEquals(1, bgr.position(), "the buffer is left as it was");
  }

  @Test
  void refusesNulls() {
    assertEquals(
      "Configuration must not be null",
      assertThrows(NullPointerException.class, () -> new Mcv2Result(null, this.viewers, null)).getMessage()
    );
    assertEquals(
      "Channel must not be null",
      assertThrows(NullPointerException.class, () -> new Mcv2Result(this.configuration, (Mcv2Channel) null, null)).getMessage()
    );
    final Mcv2Result result = new Mcv2Result(this.configuration, this.viewers, this.algorithm);
    try (final ImageBuffer image = Images.solid(64, 32, 0)) {
      assertEquals(
        "Frame must not be null",
        assertThrows(NullPointerException.class, () -> result.applyFilter(null, this.metadata)).getMessage()
      );
      assertEquals(
        "Metadata must not be null",
        assertThrows(NullPointerException.class, () -> result.applyFilter(image, null)).getMessage()
      );
      assertEquals(0, result.getStatistics().getFrames(), "invalid input cannot publish a frame");
    } finally {
      result.release();
    }
  }

  @Test
  void countsTheKeyframesItSent() {
    final Mcv2Result.Statistics statistics = new Mcv2Result.Statistics();
    statistics.add(new MCV2.Stats(10, true, 1, 5, 72), 128);
    statistics.add(new MCV2.Stats(20, false, 1, 7, 72), 256);
    assertEquals(2, statistics.getFrames());
    assertEquals(1, statistics.getKeyframes());
  }

  @Test
  void pacesFromTheTopOfItsLadderOnceStarted() {
    final Mcv2Result result = this.result(this.configuration, null);
    assertNull(result.getRung());
    result.start();
    assertEquals(new Mcv2Pacer.Rung(64, 32, 1), result.getRung());
    result.release();
  }

  @Test
  void repairsAnUnchangedFallbackAfterVanillaOverwritesItsFirstSnapshot() {
    when(this.viewers.isLoaded(WITH_PACK)).thenReturn(false);
    doAnswer(invocation -> MapPackets.pattern(invocation.getArgument(0)))
      .when(this.algorithm)
      .ditherIntoBytes(any());
    final AtomicLong clock = new AtomicLong(-TimeUnit.SECONDS.toNanos(60));
    final Mcv2Result result = new Mcv2Result(
      this.configuration,
      new Mcv2Channel(this.configuration, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    result.start();
    try (final ImageBuffer frame = Images.solid(64, 32, 0xFF336699)) {
      result.applyFilter(frame, this.metadata);
      final byte[] expected = MapPackets.pattern(128 * 128, 4);
      MapPackets.assertMapPacket(
        MapPackets.unbundle(this.server.getSentPackets(WITHOUT).getLast()).getFirst(),
        100,
        0,
        0,
        128,
        128,
        expected
      );
      // Vanilla can initialize its map after the first snapshot, leaving an unchanged delta stream transparent.
      MapPacketFactory.clear(List.of(WITHOUT), 100, 1);
      clock.addAndGet(TimeUnit.SECONDS.toNanos(4) - 1);
      result.applyFilter(frame, this.metadata);
      assertEquals(2, this.server.getSentPackets(WITHOUT).size(), "ordinary identical frames keep delta savings");
      clock.incrementAndGet();
      result.applyFilter(frame, this.metadata);
      MapPackets.assertMapPacket(
        MapPackets.unbundle(this.server.getSentPackets(WITHOUT).getLast()).getFirst(),
        100,
        0,
        0,
        128,
        128,
        expected
      );
      assertEquals(3, this.server.getSentPackets(WITHOUT).size(), "the fallback repairs an external map reset");
      clock.incrementAndGet();
      result.applyFilter(frame, this.metadata);
      assertEquals(3, this.server.getSentPackets(WITHOUT).size(), "a repair starts a fresh interval");
      MapPacketFactory.clear(List.of(WITHOUT), 100, 1);
      clock.addAndGet(TimeUnit.SECONDS.toNanos(4));
      result.applyFilter(frame, this.metadata);
      MapPackets.assertMapPacket(
        MapPackets.unbundle(this.server.getSentPackets(WITHOUT).getLast()).getFirst(),
        100,
        0,
        0,
        128,
        128,
        expected
      );
      assertEquals(5, this.server.getSentPackets(WITHOUT).size(), "recovery is periodic, not a one-time join delay");
    } finally {
      result.release();
    }
  }

  @Test
  void dithersAgainOnceStartedAgainAndClearsTheMapsWhenReleased() {
    final Mcv2Result result = new Mcv2Result(
      this.configuration,
      new Mcv2Channel(this.configuration, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run
    );
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.start();
    // the first frame is dithered for both viewers, so both have the wall's map
    result.applyFilter(frame, this.metadata);
    final int sent = this.server.getSentPackets(WITHOUT).size();
    result.release();
    // releasing clears it: one more bundle, of map 100's transparent colours
    final List<Packet<?>> cleared = this.server.getSentPackets(WITHOUT);
    assertEquals(sent + 1, cleared.size());
    MapPackets.assertMapPacket(MapPackets.unbundle(cleared.getLast()).getFirst(), 100, 0, 0, 128, 128, new byte[128 * 128]);
    // a result started again dithers again
    result.start();
    clearInvocations(this.algorithm);
    result.applyFilter(frame, this.metadata);
    verify(this.algorithm).ditherIntoBytes(any());
    result.release();
  }

  @Test
  void clearsTheWallOfTheViewersWithThePackWhenReleased() {
    // a client with the pack keeps drawing its last decoded picture over a wall whose maps still carry the screen's
    // anchors, so a release clears their maps too, not only the dithered ones
    final Mcv2Result result = new Mcv2Result(
      this.configuration,
      new Mcv2Channel(this.configuration, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run
    );
    result.start();
    // the first frame dithers for both and shows the screen to the viewer with the pack, who then decodes the second
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    assertEquals(Set.of(WITH_PACK), result.getChannel().getRecipients());
    final int withPack = this.server.getSentPackets(WITH_PACK).size();
    final int without = this.server.getSentPackets(WITHOUT).size();

    result.release();

    final List<Packet<?>> cleared = this.server.getSentPackets(WITH_PACK);
    assertEquals(withPack + 1, cleared.size(), "one bundle clears the wall of the viewer with the pack");
    MapPackets.assertMapPacket(MapPackets.unbundle(cleared.getLast()).getFirst(), 100, 0, 0, 128, 128, new byte[128 * 128]);
    assertEquals(without + 1, this.server.getSentPackets(WITHOUT).size(), "the dithered maps are cleared once");
  }

  @Test
  void dithersAgainAfterARefusedHandover() {
    final AtomicInteger handed = new AtomicInteger();
    final Executor refusesOnce = task -> {
      if (handed.getAndIncrement() == 0) {
        throw new RejectedExecutionException("released");
      }
      task.run();
    };
    final Mcv2Result result = new Mcv2Result(
      this.configuration,
      new Mcv2Channel(this.configuration, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      refusesOnce
    );
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    result.applyFilter(frame, this.metadata);
    assertEquals(2, handed.get());
    verify(this.algorithm).ditherIntoBytes(any());
    result.release();
  }

  /** Whether a screen's or a sender's thread that did not exist before is alive. */
  private static boolean hasPipelineThreads(final Set<Thread> before) {
    return !threads("mcav-mcv2-screen", before).isEmpty() || !threads("mcav-mcv2-sender", before).isEmpty();
  }

  /** Waits up to five seconds for the screen's and the sender's threads that did not exist before to end. */
  private static void awaitPipelineEnd(final Set<Thread> before) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (hasPipelineThreads(before) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
  }

  /** The live threads of a name that did not exist before. */
  private static Set<Thread> threads(final String name, final Set<Thread> before) {
    final Set<Thread> found = new HashSet<>();
    for (final Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.getName().equals(name) && thread.isAlive() && !before.contains(thread)) {
        found.add(thread);
      }
    }
    return found;
  }

  @Test
  void endsItsThreadsBeforeReleaseReturns() throws Exception {
    final Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());
    final CountDownLatch encoding = new CountDownLatch(1);
    final MCV2 slow = mock(MCV2.class);
    when(slow.getSettings()).thenReturn(Settings.FAST);
    final MCV2.Pending pending = mock(MCV2.Pending.class);
    when(slow.begin(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> {
      encoding.countDown();
      // a search does not stop at an interrupt: it runs to its end
      final long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
      while (System.nanoTime() < end) {
        Thread.onSpinWait();
      }
      return pending;
    });
    final MCV2.Encoded encoded = mock(MCV2.Encoded.class);
    when(encoded.getData()).thenReturn(Mcv2ChannelTest.keyframe());
    when(encoded.getStats()).thenReturn(new MCV2.Stats(1, true, 1, 1, 72));
    when(slow.finish(pending)).thenReturn(encoded);
    final Pool budget = mock(Pool.class);
    when(budget.encoder(any(), anyBoolean())).thenReturn(slow);
    when(budget.run(any())).thenAnswer(invocation -> invocation.<Callable<?>>getArgument(0).call());
    final Mcv2Configuration own = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK, WITHOUT))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(500)
      .encoderPool(budget)
      .maxFrameRate(0)
      .build();
    // no executor given: the result dithers on a thread of its own
    final Mcv2Result result = new Mcv2Result(own, new Mcv2Channel(own, this.viewers, this.screen), this.algorithm, System::nanoTime, null);
    result.start();
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    assertTrue(encoding.await(5, TimeUnit.SECONDS));
    result.release();
    // the encode ran to its end before release returned, and the sender stopped too
    assertEquals(Set.of(), threads("mcav-mcv2-screen", before));
    assertEquals(Set.of(), threads("mcav-mcv2-sender", before));
    // the dithering thread ends once its executor is shut down
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!threads("mcav-mcv2-dither", before).isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(Set.of(), threads("mcav-mcv2-dither", before));
  }

  @Test
  void dropsTheWaitingFrameWhenItStepsDownToTheDitheredMaps() throws InterruptedException {
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
      .maxFrameRate(0)
      .build();
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    result.pace();
    final AtomicLong encodeNanos = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final MCV2 encoder = mock(MCV2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> {
      clock.addAndGet(encodeNanos.get());
      return Mcv2ChannelTest.keyframe();
    });
    when(encoder.getStats()).thenReturn(new MCV2.Stats(1, false, 1, 1, 72));
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 300);
    // two seconds a frame: nothing fits, and the frame that arrived while the last was encoded is not encoded any more
    encodeNanos.set(TimeUnit.SECONDS.toNanos(2));
    for (int frameNumber = 0; frameNumber < 20 && !result.getRung().isDithered(); frameNumber++) {
      clock.addAndGet(16_666_667L);
      result.applyFilter(frame, this.metadata);
      final Mcv2Result.Arrival encoding = result.poll();
      assertNotNull(encoding);
      result.applyFilter(frame, this.metadata);
      result.send(encoder, encoding, 300 + frameNumber);
    }
    assertTrue(result.getRung().isDithered());
    assertNull(result.poll());
    result.release();
  }

  /** A viewer whose client sees six chunks far, from wherever the position is. */
  private static void seesSixChunksFrom(final Player player, final AtomicReference<Location> position) {
    when(player.getViewDistance()).thenReturn(6);
    when(player.getWorld()).thenAnswer(_ -> position.get().getWorld());
    when(player.getLocation()).thenAnswer(_ -> position.get());
  }

  @Test
  void aViewerTooFarFromTheWallGetsNoDitheredMapsWhenTheyStandInForTheFrames() throws InterruptedException {
    when(WORLD.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000a0a0"));
    when(ELSEWHERE.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000b0b0"));
    final UUID far = UUID.fromString("00000000-0000-0000-0000-000000000043");
    final UUID otherWorld = UUID.fromString("00000000-0000-0000-0000-000000000044");
    // the viewer without the pack stands at the wall, another 400 blocks from it, a third in another world
    final AtomicReference<Location> farPosition = new AtomicReference<>(new Location(WORLD, 400, 64, 0));
    seesSixChunksFrom(Objects.requireNonNull(Bukkit.getPlayer(WITHOUT)), new AtomicReference<>(new Location(WORLD, 0, 64, 5)));
    seesSixChunksFrom(this.server.addPlayer(far), farPosition);
    seesSixChunksFrom(this.server.addPlayer(otherWorld), new AtomicReference<>(new Location(ELSEWHERE, 0, 64, 0)));
    PacketUtils.init();
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration wall = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK, WITHOUT, far, otherWorld))
      .origin(new Location(WORLD, 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(500)
      .settings(Settings.FAST)
      .maxFrameRate(0)
      .build();
    final Mcv2Result result = new Mcv2Result(
      wall,
      new Mcv2Channel(wall, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    result.pace();
    // opened as start opens it, without the threads: the test hands the frames to the encoder itself
    result.getChannel().open();
    final AtomicLong encodeNanos = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final MCV2 encoder = mock(MCV2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> {
      clock.addAndGet(encodeNanos.get());
      return Mcv2ChannelTest.keyframe();
    });
    when(encoder.getStats()).thenReturn(new MCV2.Stats(1, false, 1, 1, 72));
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 300);
    // two seconds a frame: the pacer falls back to the dithered maps, which every viewer near the wall is shown
    encodeNanos.set(TimeUnit.SECONDS.toNanos(2));
    for (int frameNumber = 0; frameNumber < 600 && !result.getRung().isDithered(); frameNumber++) {
      this.play(result, clock, encoder, frame, 1);
    }
    assertTrue(result.getRung().isDithered());
    this.server.runTasks();
    verify(this.screen).remove();
    final int withPack = this.server.getSentPackets(WITH_PACK).size();
    this.play(result, clock, encoder, frame, 30);
    assertTrue(this.server.getSentPackets(WITH_PACK).size() > withPack, "the viewer with the pack is shown the dithered maps");
    assertEquals(List.of(), this.server.getSentPackets(far), "a viewer 400 blocks from the wall cannot see it");
    assertEquals(List.of(), this.server.getSentPackets(otherWorld), "nor one in another world");
    // one who comes near meanwhile is shown the maps as soon as the distances are measured again
    farPosition.set(new Location(WORLD, 0, 64, 5));
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 1);
    assertFalse(this.server.getSentPackets(far).isEmpty(), "a viewer who came near the wall sees the dithered maps");
    // the load drops: the pacer tries the frames again, and the first frame encoded after its return is a keyframe
    encodeNanos.set(TimeUnit.MILLISECONDS.toNanos(1));
    for (int frameNumber = 0; frameNumber < 60 * 60 && result.getRung().isDithered(); frameNumber++) {
      this.play(result, clock, encoder, frame, 1);
    }
    assertFalse(result.getRung().isDithered());
    clearInvocations(encoder);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 1);
    verify(encoder, never()).encode(any(), anyInt(), anyInt(), anyLong());
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 60);
    final InOrder order = inOrder(encoder);
    order.verify(encoder).requestKeyframe();
    order.verify(encoder).encode(any(), anyInt(), anyInt(), anyLong());
    result.release();
  }

  @Test
  void recordsWhatTheViewersLinksDidWithEachFrame(@TempDir final Path directory) throws Exception {
    // two viewers with the pack; the first one's connection writes nothing, so it falls behind, then misses the frame
    // the next one predicts from and waits for a keyframe
    final UUID second = UUID.fromString("00000000-0000-0000-0000-000000000043");
    this.server.addPlayer(second);
    PacketUtils.init();
    when(this.viewers.isLoaded(second)).thenReturn(true);
    final Mcv2Configuration tight = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK, second))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .pageMap(500)
      .backlogLimit(200)
      .unsentLimit(0)
      .maxFrameRate(0)
      .build();
    final Mcv2Result result = this.result(tight, null);
    result.getChannel().update();
    this.server.runTasks();
    result.getChannel().update();
    final List<byte[]> frames = List.of(
      Mcv2ChannelTest.frame(0, 0, true),
      Mcv2ChannelTest.frame(1, 0, false),
      Mcv2ChannelTest.frame(2, 1, false),
      Mcv2ChannelTest.frame(3, 2, false),
      Mcv2ChannelTest.frame(4, 4, true)
    );
    final MCV2 encoder = mock(MCV2.class);
    try (final Recording recording = new Recording()) {
      recording.enable("me.brandonli.mcav.Mcv2Frame");
      recording.start();
      for (int id = 0; id < frames.size(); id++) {
        final byte[] frame = frames.get(id);
        when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenReturn(frame);
        when(encoder.getStats()).thenReturn(new MCV2.Stats(frame.length, id == 0 || id == 4, 1, 1, 72));
        if (id == 3) {
          this.server.completeWrites(WITH_PACK);
        }
        result.send(encoder, new Mcv2Result.Arrival(new byte[32 * 32 * 3], 32, 32, 0, Mcv2Pacer.Preset.ONLY), id);
        this.server.completeWrites(second);
      }
      recording.stop();
      final Path file = directory.resolve("links.jfr");
      recording.dump(file);
      final List<RecordedEvent> recorded = RecordingFile.readAllEvents(file)
        .stream()
        .filter(event -> event.getEventType().getName().equals("me.brandonli.mcav.Mcv2Frame"))
        .toList();
      assertEquals(
        List.of(2, 2, 1, 1, 2),
        recorded
          .stream()
          .map(event -> event.getInt("sentTo"))
          .toList()
      );
      assertEquals(
        List.of(0, 0, 1, 0, 0),
        recorded
          .stream()
          .map(event -> event.getInt("behind"))
          .toList()
      );
      assertEquals(
        List.of(0, 0, 0, 1, 0),
        recorded
          .stream()
          .map(event -> event.getInt("waiting"))
          .toList()
      );
    }
    result.release();
  }

  /** A screen of one map for the viewer with the pack, at 64x32, with the given encoder settings. */
  private static Mcv2Configuration packScreen(final Settings settings) {
    return Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(500)
      .settings(settings)
      .maxFrameRate(0)
      .build();
  }

  /**
   * Makes mock encoders of a screen: each frame begins, and finishes - once the gate is open - with a keyframe's bytes,
   * or fails its verification.
   */
  private static Function<Settings, MCV2> mockEncoders(final List<MCV2> made, final boolean failing, final CountDownLatch gate) {
    return settings -> {
      final MCV2 encoder = mock(MCV2.class);
      when(encoder.getSettings()).thenReturn(settings);
      final MCV2.Pending pending = mock(MCV2.Pending.class);
      when(encoder.begin(any(), anyInt(), anyInt(), anyLong())).thenReturn(pending);
      if (failing) {
        when(encoder.finish(pending)).thenThrow(new IllegalStateException("MCV2 live picture and decoded picture disagree"));
      } else {
        final MCV2.Encoded encoded = mock(MCV2.Encoded.class);
        when(encoded.getData()).thenReturn(Mcv2ChannelTest.keyframe());
        when(encoded.getStats()).thenReturn(new MCV2.Stats(1, true, 1, 1, 55));
        when(encoder.finish(pending)).thenAnswer(_ -> {
          gate.await();
          return encoded;
        });
      }
      made.add(encoder);
      return encoder;
    };
  }

  /** Waits until the screen's thread has made the given number of encoders. */
  private static void awaitEncoders(final List<MCV2> made, final int count) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (made.size() < count && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(count, made.size());
  }

  @Test
  void searchesTheNextFrameWhileTheSenderVerifiesTheLast() throws InterruptedException {
    final Mcv2Configuration fast = packScreen(Settings.FAST);
    final List<MCV2> made = new CopyOnWriteArrayList<>();
    final CountDownLatch gate = new CountDownLatch(1);
    final Mcv2Result result = new Mcv2Result(
      fast,
      new Mcv2Channel(fast, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run,
      mockEncoders(made, false, gate)
    );
    result.start();
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    awaitEncoders(made, 1);
    // the sender holds the first frame: a drain waits for it
    verify(made.getFirst(), timeout(TimeUnit.SECONDS.toMillis(10))).finish(any());
    final Thread drainer = new Thread(() -> {
      try {
        result.drain();
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    });
    drainer.start();
    drainer.join(200);
    assertTrue(drainer.isAlive());
    gate.countDown();
    drainer.join(TimeUnit.SECONDS.toMillis(10));
    assertFalse(drainer.isAlive());
    awaitFrames(result, 1);
    for (int frameNumber = 2; frameNumber <= 3; frameNumber++) {
      result.applyFilter(frame, this.metadata);
      awaitFrames(result, frameNumber);
    }
    assertEquals(1, made.size());
    verify(made.getFirst(), times(3)).begin(any(), anyInt(), anyInt(), anyLong());
    verify(made.getFirst(), times(3)).finish(any());
    result.drain();
    result.release();
  }

  @Test
  void stopsTheScreenWhenAFrameFailsItsVerification() throws InterruptedException {
    final Mcv2Configuration fast = packScreen(Settings.FAST);
    final List<MCV2> made = new CopyOnWriteArrayList<>();
    final Mcv2Result result = new Mcv2Result(
      fast,
      new Mcv2Channel(fast, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run,
      mockEncoders(made, true, new CountDownLatch(0))
    );
    result.start();
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    awaitEncoders(made, 1);
    verify(made.getFirst(), timeout(TimeUnit.SECONDS.toMillis(10))).finish(any());
    // the screen searches the next frame while the sender verifies the last, so frames are handed over only once the
    // failed verification has stopped the screen, which also ends a drain: right after finish was called, a frame could
    // still be searched on a loaded machine
    final Thread drainer = new Thread(() -> {
      try {
        result.drain();
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    });
    drainer.start();
    drainer.join(TimeUnit.SECONDS.toMillis(10));
    assertFalse(drainer.isAlive(), "the failed verification stops the screen");
    // the failure stops the screen's thread too: no later frame begins, and nothing was sent
    result.applyFilter(frame, this.metadata);
    result.applyFilter(frame, this.metadata);
    verify(made.getFirst(), Mockito.after(500).times(1)).begin(any(), anyInt(), anyInt(), anyLong());
    assertEquals(0, result.getStatistics().getFrames());
    result.release();
  }

  @Test
  void namesThePresetsOfItsLadder() {
    final Mcv2Result live = this.result(packScreen(Settings.DEFAULT), null);
    live.pace();
    assertEquals(new Mcv2Pacer.Preset("DEFAULT", 1), live.getRung().preset());
    // The fastest preset has no faster search to step through.
    final Mcv2Result fastest = this.result(packScreen(Settings.FAST), null);
    fastest.pace();
    assertEquals(new Mcv2Pacer.Rung(64, 32, 1), fastest.getRung());
  }

  /**
   * An encoder that codes with the settings it was made with or last switched to, its frames taking the clock's time:
   * the normal thresholds take the given nanoseconds and the fast thresholds take three quarters of it.
   */
  private static MCV2 timedEncoder(
    final Settings settings,
    final AtomicLong clock,
    final AtomicLong encodeNanos,
    final List<Settings> switched
  ) {
    final MCV2 encoder = mock(MCV2.class);
    final AtomicReference<Settings> current = new AtomicReference<>(settings);
    when(encoder.getSettings()).thenAnswer(_ -> current.get());
    doAnswer(invocation -> {
      current.set(invocation.getArgument(0));
      switched.add(invocation.getArgument(0));
      return null;
    })
      .when(encoder)
      .switchTo(any());
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> {
      final Settings now = current.get();
      final long share = now.fast() ? 3 : 4;
      clock.addAndGet((encodeNanos.get() * share) / 4);
      return Mcv2ChannelTest.keyframe();
    });
    when(encoder.getStats()).thenReturn(new MCV2.Stats(1, false, 1, 1, 72));
    return encoder;
  }

  @Test
  void stepsDownTheLivePresetsWithOneEncoder() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration live = packScreen(Settings.DEFAULT);
    final AtomicLong encodeNanos = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final List<MCV2> made = new ArrayList<>();
    final List<Settings> switched = new ArrayList<>();
    final Mcv2Result result = new Mcv2Result(
      live,
      new Mcv2Channel(live, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run,
      settings -> {
        final MCV2 encoder = timedEncoder(settings, clock, encodeNanos, switched);
        made.add(encoder);
        return encoder;
      }
    );
    final List<Mcv2Pacer.Change> changes = new ArrayList<>();
    result.setPacingListener(changes::add);
    result.pace();
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    final MCV2 first = timedEncoder(Settings.DEFAULT, clock, encodeNanos, switched);
    made.add(first);
    MCV2 encoder = this.playSwitching(result, clock, first, frame, 300);
    assertEquals(1, made.size());
    assertEquals(Settings.DEFAULT, made.getFirst().getSettings());
    // FAST is estimated at 16.2 ms, inside the 16.7 ms frame interval.
    encodeNanos.set(TimeUnit.MILLISECONDS.toNanos(18));
    encoder = this.playSwitching(result, clock, encoder, frame, 120);
    assertEquals(1, changes.size());
    assertEquals(
      "MCV2 screen steps down to 64x32 at 60 fps with the FAST search: encoding 64x32 with the DEFAULT search takes 18.0 ms" +
        " per frame, more than the 16.7 ms a frame has at 60 fps with the encoder threads it has",
      changes.getFirst().describe()
    );
    assertEquals(1, made.size());
    assertEquals(List.of(Settings.FAST), switched);
    verify(made.getFirst(), Mockito.atLeast(150)).encode(any(), anyInt(), anyInt(), anyLong());
    // Once the budget frees, the same encoder returns to DEFAULT.
    encodeNanos.set(TimeUnit.MILLISECONDS.toNanos(5));
    this.playSwitching(result, clock, encoder, frame, 30 * 60);
    assertEquals(2, changes.size());
    assertFalse(changes.get(1).down());
    assertEquals(1, made.size());
    assertEquals(List.of(Settings.FAST, Settings.DEFAULT), switched);
    assertEquals(new Mcv2Pacer.Preset("DEFAULT", 1), result.getRung().preset());
  }

  /**
   * Plays frames at 60 frames a second on the test's clock, as {@link #play} does, and encodes each with the encoder of
   * the preset it arrived on, as the screen's thread does.
   *
   * @return the encoder of the last frame
   */
  private MCV2 playSwitching(final Mcv2Result result, final AtomicLong clock, final MCV2 first, final ImageBuffer frame, final int frames)
    throws InterruptedException {
    MCV2 encoder = first;
    for (int frameNumber = 0; frameNumber < frames; frameNumber++) {
      final long arrival = clock.addAndGet(16_666_667L);
      result.applyFilter(frame, this.metadata);
      final Mcv2Result.Arrival handed = result.poll();
      if (handed != null) {
        final Settings settings = result.settingsFor(handed);
        if (!settings.equals(encoder.getSettings())) {
          encoder = result.encoderFor(settings, encoder);
        }
        result.send(encoder, handed, frameNumber);
      }
      clock.set(arrival);
    }
    return encoder;
  }

  @Test
  void warnsOfAStepDownAndTellsOfAStepUp() throws InterruptedException {
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
      // one preset, so the pacer steps the frame rate and the size, which this is about
      .settings(Settings.FAST)
      .maxFrameRate(0)
      .build();
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    result.pace();
    final AtomicLong encodeNanos = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final MCV2 encoder = mock(MCV2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> {
      clock.addAndGet(encodeNanos.get());
      return Mcv2ChannelTest.keyframe();
    });
    when(encoder.getStats()).thenReturn(new MCV2.Stats(1, false, 1, 1, 72));
    final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    try (final LogCapture logs = LogCapture.capture(Mcv2Result.class)) {
      this.play(result, clock, encoder, frame, 300);
      // 25 ms a frame steps down to every other frame; 5 ms a frame climbs back once the top is free and has room
      encodeNanos.set(TimeUnit.MILLISECONDS.toNanos(25));
      this.play(result, clock, encoder, frame, 90);
      encodeNanos.set(TimeUnit.MILLISECONDS.toNanos(5));
      this.play(result, clock, encoder, frame, 20 * 60);
      assertEquals(List.of(Level.WARN, Level.INFO), logs.getEvents().stream().map(LogCapture.RecordedEvent::getLevel).toList());
    }
    result.release();
  }

  @Test
  void theDitheredMapsShowTheWholeVideoScaledToTheWall() {
    final List<String> dithered = new CopyOnWriteArrayList<>();
    final DitherAlgorithm recording = mock(DitherAlgorithm.class);
    when(recording.ditherIntoBytes(any())).thenAnswer(invocation -> {
      final ImageBuffer image = invocation.getArgument(0);
      dithered.add(image.getWidth() + "x" + image.getHeight());
      return new byte[image.getWidth() * image.getHeight()];
    });
    // a video larger than the wall's maps, and one smaller, as a pacer rung makes it
    for (final int[] video : new int[][] { { 512, 128 }, { 128, 64 } }) {
      final Mcv2Configuration wide = Mcv2Configuration.builder()
        .viewers(List.of(WITHOUT))
        .origin(new Location(WORLD, 0, 64, 0))
        .facing(BlockFace.SOUTH)
        .map(100)
        .columns(2)
        .rows(1)
        .video(video[0], video[1])
        .pageMap(500)
        .maxFrameRate(0)
        .build();
      final Mcv2Result result = new Mcv2Result(
        wide,
        new Mcv2Channel(wide, this.viewers, this.screen),
        recording,
        System::nanoTime,
        Runnable::run
      );
      result.applyFilter(Images.solid(video[0], video[1], 0xFF336699), this.metadata);
      result.release();
    }
    assertEquals(List.of("256x128", "256x128"), dithered, "a wall of 2x1 maps is 256x128 pixels");
  }

  @Test
  void aFrameListenerThatFailsStopsTheScreenInsteadOfLeavingItsEncoderWaitingForever() throws InterruptedException {
    final Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());
    final Mcv2Result result = this.result(this.configuration, this.algorithm);
    result.setFrameListener(_ -> {
      throw new IllegalStateException("a listener that fails");
    });
    try (final LogCapture logs = LogCapture.capture(Mcv2Result.class)) {
      result.start();
      result.applyFilter(Images.solid(64, 32, 0xFF336699), this.metadata);
      // shows the screen to the viewer with the pack: the next frame is sent, and the listener hears it
      this.server.runTasks();
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      for (int shade = 0; hasPipelineThreads(before) && System.nanoTime() < deadline; shade++) {
        result.applyFilter(Images.solid(64, 32, 0xFF336699 + shade), this.metadata);
        Thread.sleep(20);
      }
      assertEquals(Set.of(), threads("mcav-mcv2-sender", before), "the listener's failure ends the sender");
      assertEquals(Set.of(), threads("mcav-mcv2-screen", before), "and the screen's thread, instead of waiting for it");
      assertTimeoutPreemptively(Duration.ofSeconds(5), result::drain, "a stopped screen has nothing to drain");
      assertTrue(
        logs
          .getEvents()
          .stream()
          .anyMatch(event -> Level.ERROR.equals(event.getLevel())),
        "the failure is logged"
      );
    }
    result.release();
  }

  @Test
  void anEncoderThatFailsStopsTheScreenAndItsSender() throws InterruptedException {
    final Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());
    final MCV2 failing = mock(MCV2.class);
    when(failing.getSettings()).thenReturn(Settings.FAST);
    when(failing.begin(any(), anyInt(), anyInt(), anyLong())).thenThrow(new IllegalArgumentException("a size the codec cannot carry"));
    final Mcv2Configuration fast = packScreen(Settings.FAST);
    final Mcv2Result result = new Mcv2Result(
      fast,
      new Mcv2Channel(fast, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run,
      _ -> failing
    );
    try (final LogCapture logs = LogCapture.capture(Mcv2Result.class)) {
      result.start();
      final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
      result.applyFilter(frame, this.metadata);
      this.server.runTasks();
      result.applyFilter(frame, this.metadata);
      verify(failing, timeout(TimeUnit.SECONDS.toMillis(10))).begin(any(), anyInt(), anyInt(), anyLong());
      awaitPipelineEnd(before);
      assertEquals(Set.of(), threads("mcav-mcv2-screen", before), "the encoder's failure ends the screen's thread");
      assertEquals(Set.of(), threads("mcav-mcv2-sender", before), "and the sender, which waits for its frames");
      assertTrue(
        logs
          .getEvents()
          .stream()
          .anyMatch(event -> Level.ERROR.equals(event.getLevel())),
        "the failure is logged"
      );
    }
    result.release();
  }

  /** Waits up to ten seconds until a thread waits, such as on the result's lock. */
  private static void awaitWaiting(final Thread thread) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertEquals(Thread.State.WAITING, thread.getState());
  }

  /** Waits for a latch the way code that does not answer interrupts does, and keeps the interrupt for later. */
  private static void awaitUninterruptibly(final CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (final InterruptedException exception) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** A thread that waits in {@link Mcv2Result#take()} and keeps what it took. */
  private static Thread taker(final Mcv2Result result, final AtomicReference<Mcv2Result.Arrival> taken) {
    return new Thread(() -> {
      try {
        taken.set(result.take());
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    });
  }

  @Test
  void aReleaseWakesAThreadThatWaitsForAFrame() throws InterruptedException {
    final Mcv2Result result = this.result(this.configuration, null);
    result.start();
    final AtomicReference<Mcv2Result.Arrival> taken = new AtomicReference<>(
      new Mcv2Result.Arrival(new byte[0], 0, 0, 0, Mcv2Pacer.Preset.ONLY)
    );
    final Thread waiter = taker(result, taken);
    try {
      waiter.start();
      awaitWaiting(waiter);
      result.release();
      waiter.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(waiter.isAlive(), "the release wakes a thread that waits for a frame");
      assertNull(taken.get(), "and hands it none");
    } finally {
      releaseAndStopWaiter(result, waiter);
    }
  }

  @Test
  void aFailedReleaseNotificationAssertionStillStopsItsWaiter() throws InterruptedException {
    final CountDownLatch neverNotified = new CountDownLatch(1);
    final AtomicReference<Thread> waiting = new AtomicReference<>();
    try (
      final MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class, (result, _) -> {
        when(result.take()).thenAnswer(_ -> {
          waiting.set(Thread.currentThread());
          neverNotified.await();
          return null;
        });
      })
    ) {
      final AssertionError failure = assertThrows(AssertionError.class, this::aReleaseWakesAThreadThatWaitsForAFrame);
      assertTrue(failure.getMessage().contains("the release wakes a thread"));
      assertEquals(1, results.constructed().size());
      assertFalse(waiting.get().isAlive(), "a failed release-notification test leaked its waiter");
    } finally {
      final Thread waiter = waiting.get();
      if (waiter != null) {
        waiter.interrupt();
        waiter.join(5000);
        assertFalse(waiter.isAlive(), "the fixture probe must stop its own waiter");
      }
    }
  }

  @Test
  void aReleaseFailureStillStopsTheFixtureWaiter() throws InterruptedException {
    final CountDownLatch neverNotified = new CountDownLatch(1);
    final AtomicReference<Thread> waiting = new AtomicReference<>();
    final AssertionError forcedFailure = new AssertionError("forced release assertion");
    try (
      final MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class, (result, _) -> {
        when(result.take()).thenAnswer(_ -> {
          waiting.set(Thread.currentThread());
          neverNotified.await();
          return null;
        });
        Mockito.doThrow(forcedFailure).doNothing().when(result).release();
      })
    ) {
      assertSame(forcedFailure, assertThrows(AssertionError.class, this::aReleaseWakesAThreadThatWaitsForAFrame));
      assertEquals(1, results.constructed().size());
      assertFalse(waiting.get().isAlive(), "a release failure leaked the fixture waiter");
    } finally {
      final Thread waiter = waiting.get();
      if (waiter != null) {
        waiter.interrupt();
        waiter.join(5000);
        assertFalse(waiter.isAlive(), "the fixture probe must stop its own waiter");
      }
    }
  }

  @Test
  void aSenderThatFailsStopsTheScreenWhileItsThreadCannotBeInterrupted() throws InterruptedException {
    final Mcv2Configuration fast = packScreen(Settings.FAST);
    final CountDownLatch inSecondFrame = new CountDownLatch(1);
    final CountDownLatch secondFrameMayGoOn = new CountDownLatch(1);
    final CountDownLatch firstFrameMayFail = new CountDownLatch(1);
    final MCV2 encoder = mock(MCV2.class);
    when(encoder.getSettings()).thenReturn(Settings.FAST);
    final MCV2.Pending first = mock(MCV2.Pending.class);
    when(encoder.begin(any(), anyInt(), anyInt(), anyLong())).thenReturn(first);
    final AtomicInteger frames = new AtomicInteger();
    doAnswer(_ -> {
      if (frames.getAndIncrement() == 1) {
        // the second frame's work on the screen's own thread does not answer the interrupt that stops the screen, as
        // native code does not
        inSecondFrame.countDown();
        awaitUninterruptibly(secondFrameMayGoOn);
      }
      return null;
    })
      .when(encoder)
      .setFrameLimit(anyInt());
    when(encoder.finish(first)).thenAnswer(_ -> {
      firstFrameMayFail.await();
      throw new IllegalStateException("MCV2 live picture and decoded picture disagree");
    });
    final Mcv2Result result = new Mcv2Result(
      fast,
      new Mcv2Channel(fast, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run,
      _ -> encoder
    );
    final AtomicReference<Mcv2Result.Arrival> taken = new AtomicReference<>(
      new Mcv2Result.Arrival(new byte[0], 0, 0, 0, Mcv2Pacer.Preset.ONLY)
    );
    final Thread waiter = taker(result, taken);
    try (final LogCapture logs = LogCapture.capture(Mcv2Result.class)) {
      result.start();
      final ImageBuffer frame = Images.solid(64, 32, 0xFF336699);
      result.applyFilter(frame, this.metadata);
      this.server.runTasks();
      // the first frame goes to the sender, which holds it; the screen's thread is stuck in the second frame
      result.applyFilter(frame, this.metadata);
      verify(encoder, timeout(TimeUnit.SECONDS.toMillis(10))).finish(first);
      result.applyFilter(frame, this.metadata);
      assertTrue(inSecondFrame.await(10, TimeUnit.SECONDS));
      waiter.start();
      awaitWaiting(waiter);
      firstFrameMayFail.countDown();
      waiter.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(waiter.isAlive(), "the sender's failure stops the screen at once, not when its thread is free again");
      assertNull(taken.get());
      assertTrue(
        logs
          .getEvents()
          .stream()
          .anyMatch(event -> Level.ERROR.equals(event.getLevel())),
        "the failure is logged"
      );
    } finally {
      firstFrameMayFail.countDown();
      secondFrameMayGoOn.countDown();
      releaseAndStopWaiter(result, waiter);
    }
  }

  private static void releaseAndStopWaiter(final Mcv2Result result, final Thread waiter) throws InterruptedException {
    try {
      result.release();
    } finally {
      // Cleanup must not depend on the production notification that this fixture tests.
      waiter.interrupt();
      waiter.join(TimeUnit.SECONDS.toMillis(5));
      assertFalse(waiter.isAlive(), "the fixture must stop its waiter even after an assertion fails");
    }
  }
}
