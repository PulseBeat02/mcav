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

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
import java.util.function.LongSupplier;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

final class Mcv2ResultTest {

  private static final int ADAPTATION_FRAMES = 90;

  private static final int PROFILE_CHANGE_FRAMES = 120;

  private static final int PREDICTED_MAP_BYTES = 256;

  private static final UUID WITH_PACK = UUID.fromString("00000000-0000-0000-0000-000000000041");

  private static final UUID WITHOUT = UUID.fromString("00000000-0000-0000-0000-000000000042");

  private static final int WALL_MAP = 100;
  private static final int PAGE_MAP = 500;
  private static final int LAMBDA = 72;

  private static final long FRAME_INTERVAL_NANOSECONDS = 16_666_667L;
  private static final int WAIT_TIMEOUT_MILLISECONDS = 5000;
  private static final int CHECKS_UNTIL_A_NEW_VIEWER_IS_SHOWN = 3;
  private static final int SLOW_ENCODE_SECONDS = 2;
  private static final int SEARCH_DURATION_MILLISECONDS = 300;

  private static final int PIPELINE_TIMEOUT_SECONDS = 10;

  private static final long HANDOFF_DURATION_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(25);
  private static final long HANDOFF_INTERVAL_NANOSECONDS = TimeUnit.SECONDS.toNanos(1);

  private static final long CLOCK_START_NANOSECONDS = TimeUnit.SECONDS.toNanos(1);
  private static final long KEYFRAME_PERIOD_NANOSECONDS = TimeUnit.SECONDS.toNanos(4);
  private static final long SLOW_FRAME_INTERVAL_NANOSECONDS = TimeUnit.SECONDS.toNanos(1);

  private static final int FRAME_COLOR = 0xFF336699;
  private static final int BLACK = 0xFF000000;

  private static final String OTHER_VIEWER_ID = "00000000-0000-0000-0000-000000000043";

  private static final String FRAME_EVENT_NAME = "me.brandonli.mcav.Mcv2Frame";
  private static final String SENT_TO_FIELD = "sentTo";
  private static final String BEHIND_FIELD = "behind";
  private static final String WAITING_FIELD = "waiting";

  private static final String SCREEN_THREAD_NAME = "mcav-mcv2-screen";
  private static final String SENDER_THREAD_NAME = "mcav-mcv2-sender";

  private static final String LADDER_METHOD = "ladder";
  private static final String DEFAULT_PRESET_NAME = "DEFAULT";

  private static final String DISAGREEMENT_MESSAGE = "MCV2 live picture and decoded picture disagree!";
  private static final String FAILURE_LOGGED_MESSAGE = "the failure is logged";
  private static final String WAITER_STOPPED_MESSAGE = "the fixture probe must stop its own waiter";

  // a location holds its world weakly, so a static field keeps it reachable
  private static final World WORLD = mock(World.class);

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
    when(this.screen.getAnchors()).thenReturn(List.of());
    this.algorithm = mock(DitherAlgorithm.class);
    when(this.algorithm.ditherIntoBytes(any())).thenAnswer(this::createEmptyPixels);
    this.metadata = mock(OriginalVideoMetadata.class);
    this.configuration = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK, WITHOUT))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      // one preset, so the pacer steps only frame rates and sizes; the tests of the preset ladder give their own
      .settings(Settings.FAST)
      // every frame the tests give is the screen's; the rate is tested on its own
      .maximumFrameRate(0)
      .build();
  }

  private byte[] createEmptyPixels(final InvocationOnMock invocation) {
    final ImageBuffer image = invocation.getArgument(0);
    return new byte[image.getWidth() * image.getHeight()];
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private Mcv2Result result(final Mcv2Configuration screenConfiguration, final DitherAlgorithm fallback) {
    return new Mcv2Result(screenConfiguration, new Mcv2Channel(screenConfiguration, this.viewers, this.screen), fallback);
  }

  private void awaitFrames(final Mcv2Result result, final long frames) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (result.getStatistics().getFrames() < frames && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }

    final long publishedFrames = result.getStatistics().getFrames();
    assertEquals(frames, publishedFrames);
  }

  @Test
  void shouldEncodeForViewersWithThePackAndDitherForTheOthers() throws InterruptedException {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    final Mcv2Result result = new Mcv2Result(this.configuration, channel, this.algorithm);
    try {
      final List<byte[]> heard = new CopyOnWriteArrayList<>();
      result.setFrameListener(heard::add);
      result.start();
      verify(this.screen).build();

      final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
      // the first frame only schedules showing the screen, so both viewers are dithered for, on the dithering thread
      result.applyFilter(frame, this.metadata);
      verify(this.algorithm, timeout(WAIT_TIMEOUT_MILLISECONDS)).ditherIntoBytes(any());

      this.server.runTasks();
      final boolean applied = result.applyFilter(frame, this.metadata);
      assertTrue(applied);

      this.awaitFrames(result, 1);
      result.applyFilter(Images.solid(64, 32, 0xFF336698), this.metadata);
      this.awaitFrames(result, 2);
      final Mcv2Result.Statistics statistics = result.getStatistics();
      final long keyframes = statistics.getKeyframes();
      assertEquals(1, keyframes);

      final long dropped = statistics.getDropped();
      assertEquals(0, dropped);

      final boolean largerThanTheHeader = statistics.getBytes() > 48;
      assertTrue(largerThanTheHeader);

      final boolean wholeMaps = statistics.getMapBytes() > 0 && statistics.getMapBytes() % 128 == 0;
      assertTrue(wholeMaps);

      final boolean timed = statistics.getNanoseconds() > 0;
      assertTrue(timed);

      final List<Packet<?>> packets = this.server.getSentPackets(WITH_PACK);
      final int lastMapId = MapPackets.unbundle(packets.getLast()).getFirst().mapId().id();
      assertEquals(PAGE_MAP, lastMapId);

      final Mcv2Channel resultChannel = result.getChannel();
      assertSame(channel, resultChannel);

      final int heardCount = heard.size();
      assertEquals(2, heardCount);

      final long expectedBytes = statistics.getBytes();
      final int heardBytes = heard.getFirst().length + heard.getLast().length;
      assertEquals(expectedBytes, heardBytes);
    } finally {
      result.release();
    }

    verify(this.screen).remove();
  }

  @Test
  void shouldShowTheScreenToAViewerWhoLoadsThePackWhileNoFrameArrives() throws InterruptedException {
    final Mcv2Result result = this.result(this.configuration, this.algorithm);
    try {
      result.start();
      final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
      result.applyFilter(frame, this.metadata);
      verify(this.algorithm, timeout(WAIT_TIMEOUT_MILLISECONDS)).ditherIntoBytes(any());

      this.server.runTasks();
      result.applyFilter(frame, this.metadata);
      this.awaitFrames(result, 1);

      when(this.viewers.isLoaded(WITHOUT)).thenReturn(true);
      for (int check = 0; check < CHECKS_UNTIL_A_NEW_VIEWER_IS_SHOWN; check++) {
        this.server.runTasks();
      }

      final Player without = requireNonNull(Bukkit.getPlayer(WITHOUT));
      verify(this.screen).show(without);
    } finally {
      result.release();
    }
  }

  @Test
  void shouldSendTheLastPictureAgainAsAKeyframeOnceTheFramesStop() throws InterruptedException {
    final Mcv2Result result = this.result(this.configuration, this.algorithm);
    try {
      result.start();
      final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
      result.applyFilter(frame, this.metadata);
      verify(this.algorithm, timeout(WAIT_TIMEOUT_MILLISECONDS)).ditherIntoBytes(any());

      this.server.runTasks();
      result.applyFilter(frame, this.metadata);
      this.awaitFrames(result, 1);
      result.applyFilter(Images.solid(64, 32, 0xFF336698), this.metadata);
      this.awaitFrames(result, 2);

      this.server.runTasks();
      this.server.runTasks();
      this.server.runTasks();
      this.server.runTasks();

      this.awaitFrames(result, 3);
      final Mcv2Result.Statistics statistics = result.getStatistics();
      final long keyframes = statistics.getKeyframes();
      assertEquals(2, keyframes);
    } finally {
      result.release();
    }
  }

  @Test
  void shouldRepeatNothingWhileFramesKeepComing() {
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    final Mcv2Result result = this.showAFrame(frame);

    result.repeatWhileIdle();
    result.applyFilter(frame, this.metadata);
    final Mcv2Result.@Nullable Arrival handed = result.poll();
    assertNotNull(handed);

    result.repeatWhileIdle();

    final Mcv2Result.@Nullable Arrival repeated = result.poll();
    assertNull(repeated);
  }

  // the encoder takes the second frame and the keyframe request of the viewer it was the first frame for
  private Mcv2Result showAFrame(final ImageBuffer frame) {
    final Mcv2Result result = this.createSynchronousResult();
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    final Mcv2Result.@Nullable Arrival handed = result.poll();
    assertNotNull(handed);

    final Mcv2Channel channel = result.getChannel();
    final boolean requested = channel.takeKeyframeRequest();
    assertTrue(requested);

    return result;
  }

  private Mcv2Result createSynchronousResult() {
    return this.createSynchronousResult(System::nanoTime);
  }

  private Mcv2Result createSynchronousResult(final LongSupplier clock) {
    return new Mcv2Result(
      this.configuration,
      new Mcv2Channel(this.configuration, this.viewers, this.screen),
      this.algorithm,
      clock,
      Runnable::run
    );
  }

  @Test
  void shouldRepeatTheLastPictureOnceEachTimeTheFramesStop() {
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    final Mcv2Result result = this.showAFrame(frame);
    final Mcv2Channel channel = result.getChannel();

    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    final Mcv2Result.Arrival repeated = requireNonNull(result.poll());

    final Settings settings = result.settingsFor(repeated);
    assertEquals(Settings.FAST, settings);

    final boolean requested = channel.takeKeyframeRequest();
    assertTrue(requested);

    result.repeatWhileIdle();
    final Mcv2Result.@Nullable Arrival repeatedAgain = result.poll();
    assertNull(repeatedAgain);

    result.applyFilter(frame, this.metadata);
    final Mcv2Result.@Nullable Arrival handed = result.poll();
    assertNotNull(handed);

    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    final Mcv2Result.@Nullable Arrival repeatedAfterTheNextStop = result.poll();
    assertNotNull(repeatedAfterTheNextStop);
  }

  @Test
  void shouldRepeatNothingWhileFramesComeEveryOtherCheck() {
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    final Mcv2Result result = this.showAFrame(frame);

    int repeats = 0;
    for (int round = 0; round < 5; round++) {
      result.applyFilter(frame, this.metadata);
      final Mcv2Result.@Nullable Arrival handed = result.poll();
      assertNotNull(handed);

      result.repeatWhileIdle();
      result.repeatWhileIdle();
      final Mcv2Result.@Nullable Arrival repeated = result.poll();
      if (repeated != null) {
        repeats++;
      }
    }

    assertEquals(0, repeats);
  }

  @Test
  void shouldRepeatTheLastPictureOnceTheFramesStopForThreeChecks() {
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    final Mcv2Result result = this.showAFrame(frame);
    final Mcv2Channel channel = result.getChannel();

    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    final Mcv2Result.@Nullable Arrival repeatedTooSoon = result.poll();
    assertNull(repeatedTooSoon);

    result.repeatWhileIdle();
    final Mcv2Result.@Nullable Arrival repeated = result.poll();
    assertNotNull(repeated);

    final boolean requested = channel.takeKeyframeRequest();
    assertTrue(requested);

    result.repeatWhileIdle();
    result.repeatWhileIdle();
    final Mcv2Result.@Nullable Arrival repeatedAgain = result.poll();
    assertNull(repeatedAgain);
  }

  @Test
  void shouldRepeatTheLastPictureForAViewerShownTheScreenWhileNoFrameArrives() {
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    final Mcv2Result result = this.showAFrame(frame);
    final Mcv2Channel channel = result.getChannel();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    final Mcv2Result.@Nullable Arrival repeated = result.poll();
    assertNotNull(repeated);

    final boolean requested = channel.takeKeyframeRequest();
    assertTrue(requested);

    when(this.viewers.isLoaded(WITHOUT)).thenReturn(true);
    result.repeatWhileIdle();
    final Mcv2Result.@Nullable Arrival repeatedBeforeTheShow = result.poll();
    assertNull(repeatedBeforeTheShow);

    this.server.runTasks();
    result.repeatWhileIdle();

    final Player without = requireNonNull(Bukkit.getPlayer(WITHOUT));
    verify(this.screen).show(without);

    final Mcv2Result.@Nullable Arrival repeatedForTheViewer = result.poll();
    assertNotNull(repeatedForTheViewer);

    final boolean stillRequested = channel.takeKeyframeRequest();
    assertTrue(stillRequested);
  }

  @Test
  void shouldRepeatTheLastPictureForAViewerShownTheScreenBeforeTheFramesStopForThreeChecks() {
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    final Mcv2Result result = this.showAFrame(frame);
    result.repeatWhileIdle();

    when(this.viewers.isLoaded(WITHOUT)).thenReturn(true);
    result.repeatWhileIdle();
    final Mcv2Result.@Nullable Arrival repeatedBeforeTheShow = result.poll();
    assertNull(repeatedBeforeTheShow);

    this.server.runTasks();
    result.repeatWhileIdle();

    final Mcv2Result.@Nullable Arrival repeatedForTheViewer = result.poll();
    assertNotNull(repeatedForTheViewer);
  }

  @Test
  void shouldRepeatNothingWithoutRecipients() {
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    final Mcv2Result result = this.showAFrame(frame);
    when(this.viewers.isLoaded(WITH_PACK)).thenReturn(false);

    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();

    final Mcv2Result.@Nullable Arrival repeated = result.poll();
    assertNull(repeated);
  }

  @Test
  void shouldRepeatNothingBeforeAnyFrameWasEncoded() {
    final Mcv2Result result = this.createSynchronousResult();
    result.applyFilter(Images.solid(64, 32, FRAME_COLOR), this.metadata);
    this.server.runTasks();

    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();

    final Mcv2Result.@Nullable Arrival repeated = result.poll();
    assertNull(repeated);
  }

  @Test
  void shouldRepeatNothingWhileEveryViewerIsDitheredFor() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration onlyPack = this.packScreen(Settings.FAST);
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    result.resetPacer();
    final AtomicLong encodeNanoseconds = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> this.advanceEncodeClock(clock, encodeNanoseconds));
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 300);
    encodeNanoseconds.set(TimeUnit.SECONDS.toNanos(2));
    this.play(result, clock, encoder, frame, ADAPTATION_FRAMES);
    this.server.runTasks();

    final Mcv2Pacer.Rung rung = result.getRung().orElseThrow();
    assertEquals(Mcv2Pacer.Rung.DITHERED, rung);

    final int tasks = this.server.getScheduledTaskCount();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    final Mcv2Result.@Nullable Arrival repeated = result.poll();
    assertNull(repeated);

    final int tasksAfterTheChecks = this.server.getScheduledTaskCount();
    assertEquals(tasks, tasksAfterTheChecks);
  }

  @ParameterizedTest(name = "{0}x{1}")
  @CsvSource({ "16, 32", "64, 16" })
  void shouldRepeatNoPictureOfTheLargerSizeButShowTheSmallerScreen(final int width, final int height) throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration onlyPack = this.packScreen(Settings.FAST);
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    final Mcv2Screen smallScreen = mock(Mcv2Screen.class);
    when(smallScreen.getAnchors()).thenReturn(List.of());
    result.setSmallerSizes(List.of(new int[] { width, height }), configuration ->
      new Mcv2Channel(configuration, this.viewers, smallScreen)
    );
    result.resetPacer();
    final AtomicLong fullNanoseconds = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final List<Integer> widths = new ArrayList<>();
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(invocation ->
      this.recordWidthAndAdvanceClock(invocation, widths, clock, fullNanoseconds)
    );
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 300);
    fullNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(100));
    this.play(result, clock, encoder, frame, PROFILE_CHANGE_FRAMES);
    this.server.runTasks();

    final Mcv2Configuration small = result.getConfiguration();
    final int smallWidth = small.getVideoWidth();
    assertEquals(width, smallWidth);

    final int smallHeight = small.getVideoHeight();
    assertEquals(height, smallHeight);

    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    this.server.runTasks();
    result.repeatWhileIdle();

    final Player withPack = requireNonNull(Bukkit.getPlayer(WITH_PACK));
    verify(smallScreen).show(withPack);

    final Mcv2Result.@Nullable Arrival repeated = result.poll();
    assertNull(repeated);
  }

  @Test
  void shouldSendTheNextFrameAfterAStepToAViewerWhoLoadsTheNewPackWhileNoFrameComes() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration onlyPack = this.packScreen(Settings.FAST);
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    final Mcv2Screen smallScreen = mock(Mcv2Screen.class);
    when(smallScreen.getAnchors()).thenReturn(List.of());
    final List<Mcv2Configuration> resized = new ArrayList<>();
    result.setSmallerSizes(List.of(new int[] { 16, 32 }), configuration -> this.recordResizedChannel(configuration, resized, smallScreen));
    result.resetPacer();
    final AtomicLong fullNanoseconds = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final List<Integer> widths = new ArrayList<>();
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(invocation ->
      this.recordWidthAndAdvanceClock(invocation, widths, clock, fullNanoseconds)
    );
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 300);
    fullNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(100));
    this.play(result, clock, encoder, frame, PROFILE_CHANGE_FRAMES);
    when(this.viewers.isLoaded(WITH_PACK)).thenReturn(false);
    this.server.runTasks();

    final int smallWidth = result.getConfiguration().getVideoWidth();
    assertEquals(16, smallWidth);

    when(this.viewers.isLoaded(WITH_PACK)).thenReturn(true);
    for (int check = 0; check < CHECKS_UNTIL_A_NEW_VIEWER_IS_SHOWN; check++) {
      result.repeatWhileIdle();
      this.server.runTasks();
    }

    final Player withPack = requireNonNull(Bukkit.getPlayer(WITH_PACK));
    verify(smallScreen).show(withPack);

    widths.clear();
    final Mcv2Pacer.Rung rung = result.getRung().orElseThrow();
    final int encoded = this.play(result, clock, encoder, frame, rung.getDivisor());
    assertEquals(1, encoded);

    final List<Integer> expectedWidths = List.of(16);
    assertEquals(expectedWidths, widths);

    final Set<UUID> expectedRecipients = Set.of(WITH_PACK);
    final Set<UUID> recipients = result.getChannel().getRecipients();
    assertEquals(expectedRecipients, recipients);
  }

  @Test
  void shouldRepeatTheLastPictureWithThePresetOfTheCurrentRung() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration live = this.packScreen(Settings.DEFAULT);
    final AtomicLong encodeNanoseconds = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final List<Settings> switched = new ArrayList<>();
    final Mcv2Result result = new Mcv2Result(
      live,
      new Mcv2Channel(live, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    result.resetPacer();
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    final Mcv2 first = this.timedEncoder(Settings.DEFAULT, clock, encodeNanoseconds, switched);
    final Mcv2 encoder = this.playSwitching(result, clock, first, frame, 300);
    encodeNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(18));
    this.playSwitching(result, clock, encoder, frame, PROFILE_CHANGE_FRAMES);

    final List<Settings> expectedSwitches = List.of(Settings.FAST);
    assertEquals(expectedSwitches, switched);

    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    result.repeatWhileIdle();
    final Mcv2Result.Arrival repeated = requireNonNull(result.poll());

    final Settings settings = result.settingsFor(repeated);
    assertEquals(Settings.FAST, settings);
  }

  @Test
  void shouldCancelTheIdleCheckOnRelease() {
    final Mcv2Result result = this.result(this.configuration, this.algorithm);
    result.start();
    final int tasksWhileStarted = this.server.getScheduledTaskCount();
    assertEquals(2, tasksWhileStarted);

    result.release();

    final int tasksAfterRelease = this.server.getScheduledTaskCount();
    assertEquals(0, tasksAfterRelease);
  }

  @Test
  void shouldRecordEveryFrameForTheFlightRecorder(@TempDir final Path directory) throws Exception {
    // a screen that took over a pack slot numbers its frames from where the pack server said
    final Mcv2Configuration slotted = this.configuration.withSlot(
      this.configuration.getStreamId(),
      this.configuration.getPageMap(),
      4_000_000_000L
    );
    final Mcv2Result result = this.result(slotted, this.algorithm);
    try (final Recording recording = new Recording()) {
      recording.enable(FRAME_EVENT_NAME);
      recording.start();
      final long before = System.currentTimeMillis();
      result.start();
      result.applyFilter(Images.solid(64, 32, FRAME_COLOR), this.metadata);
      this.server.runTasks();
      result.applyFilter(Images.solid(64, 32, FRAME_COLOR), this.metadata);
      this.awaitFrames(result, 1);
      result.release();
      recording.stop();
      final Path file = directory.resolve("frames.jfr");
      recording.dump(file);
      final List<RecordedEvent> frames = RecordingFile.readAllEvents(file)
        .stream()
        .filter(event -> event.getEventType().getName().equals(FRAME_EVENT_NAME))
        .toList();
      final int frameCount = frames.size();
      assertEquals(1, frameCount);

      final RecordedEvent frame = frames.getFirst();
      final long frameId = frame.getLong("frameId");
      assertEquals(4_000_000_000L, frameId);

      final boolean keyframe = frame.getBoolean("keyframe");
      assertTrue(keyframe);

      final int sentTo = frame.getInt(SENT_TO_FIELD);
      assertEquals(1, sentTo);

      final int queued = frame.getInt(BEHIND_FIELD) + frame.getInt(WAITING_FIELD);
      assertEquals(0, queued);

      final boolean hasContent = frame.getInt("colors") > 0 && frame.getInt("bytes") > 48;
      assertTrue(hasContent);

      final boolean timedInOrder = frame.getLong("arrived") >= before && frame.getLong("sent") >= frame.getLong("arrived");
      assertTrue(timedInOrder);
      // two block centers fit a 64-pixel-wide video: the luma of 0x336699 is (0x33 + 2 * 0x66 + 0x99) / 4 = 0x66
      final String fingerprint = frame.getString("fingerprint");
      assertEquals("6666", fingerprint);
    }
  }

  @Test
  void shouldFingerprintTheCentersOfTheFirstBlocks() {
    final byte[] rgb = new byte[1024 * 32 * 3];
    // block 1's center white, the others black
    final int offset = (16 * 1024 + 48) * 3;
    rgb[offset] = rgb[offset + 1] = rgb[offset + 2] = (byte) 255;
    final String fingerprint = Mcv2FrameEvent.fingerprint(rgb, 1024, 32);

    final int expectedLength = 2 * Mcv2FrameEvent.FINGERPRINT_PIXELS;
    final int length = fingerprint.length();
    assertEquals(expectedLength, length);

    final String firstSample = fingerprint.substring(0, 6);
    assertEquals("00ff00", firstSample);
    // a video narrower than the blocks, or too short for row 16, has fewer samples
    final int narrowLength = Mcv2FrameEvent.fingerprint(new byte[20 * 20 * 3], 20, 20).length();
    assertEquals(2, narrowLength);

    final String shortFingerprint = Mcv2FrameEvent.fingerprint(new byte[64 * 16 * 3], 64, 16);
    assertEquals("", shortFingerprint);
  }

  @Test
  void shouldResizeFramesToTheVideoSizeAndLeaveOthersWithoutDithering() {
    final Mcv2Result result = this.result(this.configuration, null);
    final ImageBuffer wide = Images.solid(128, 64, BLACK);
    result.applyFilter(wide, this.metadata);

    final int wideWidth = wide.getWidth();
    assertEquals(64, wideWidth);

    final int wideHeight = wide.getHeight();
    assertEquals(32, wideHeight);

    final ImageBuffer tall = Images.solid(64, 16, BLACK);
    result.applyFilter(tall, this.metadata);

    final int tallHeight = tall.getHeight();
    assertEquals(32, tallHeight);

    final ImageBuffer wider = Images.solid(128, 32, BLACK);
    result.applyFilter(wider, this.metadata);

    final int widerWidth = wider.getWidth();
    assertEquals(64, widerWidth);

    verify(this.algorithm, never()).ditherIntoBytes(any());

    result.release();
  }

  @Test
  void shouldDitherNothingWhenEveryViewerHasThePack() {
    final Mcv2Configuration onlyPack = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      .maximumFrameRate(0)
      .build();
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run
    );
    final ImageBuffer frame = Images.solid(64, 32, BLACK);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    verify(this.algorithm).ditherIntoBytes(any());

    result.release();
  }

  @Test
  void shouldDitherOffTheVideoThreadAndLeaveOutTheFramesMeanwhile() throws InterruptedException {
    final CountDownLatch dithering = new CountDownLatch(1);
    final CountDownLatch finish = new CountDownLatch(1);
    Mockito.doAnswer(invocation -> this.awaitDitherCompletion(invocation, dithering, finish))
      .when(this.algorithm)
      .ditherIntoBytes(any());
    final Mcv2Result result = this.result(this.configuration, this.algorithm);
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    final boolean firstApplied = result.applyFilter(frame, this.metadata);
    assertTrue(firstApplied);

    final boolean ditheringStarted = dithering.await(5, TimeUnit.SECONDS);
    assertTrue(ditheringStarted);

    final boolean secondApplied = result.applyFilter(frame, this.metadata);
    assertTrue(secondApplied);

    final boolean thirdApplied = result.applyFilter(frame, this.metadata);
    assertTrue(thirdApplied);

    finish.countDown();
    verify(this.algorithm, timeout(WAIT_TIMEOUT_MILLISECONDS).times(1)).ditherIntoBytes(any());

    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (Mockito.mockingDetails(this.algorithm).getInvocations().size() < 2 && System.nanoTime() < deadline) {
      result.applyFilter(frame, this.metadata);
      Thread.sleep(10);
    }

    verify(this.algorithm, Mockito.atLeast(2)).ditherIntoBytes(any());

    result.release();
    clearInvocations(this.algorithm);
    result.applyFilter(frame, this.metadata);
    Thread.sleep(100);
    verify(this.algorithm, never()).ditherIntoBytes(any());
  }

  private byte[] awaitDitherCompletion(final InvocationOnMock invocation, final CountDownLatch dithering, final CountDownLatch finish)
    throws InterruptedException {
    dithering.countDown();
    finish.await();

    final ImageBuffer image = invocation.getArgument(0);
    return new byte[image.getWidth() * image.getHeight()];
  }

  @Test
  void shouldKeepItsInterruptWhileReleasing() throws InterruptedException {
    final CountDownLatch taking = new CountDownLatch(1);
    final CountDownLatch takeMayReturn = new CountDownLatch(1);
    final Mcv2Result original = this.result(this.configuration, null);
    final Mcv2Result result = spy(original);
    // The screen thread stays alive through the release, so its join always meets the interrupt.
    doAnswer(_ -> this.holdTake(taking, takeMayReturn))
      .when(result)
      .take();
    result.start();
    final boolean takeStarted = taking.await(PIPELINE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(takeStarted);

    final Thread current = Thread.currentThread();
    current.interrupt();
    try {
      result.release();
    } finally {
      takeMayReturn.countDown();
    }

    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted);
    verify(this.screen).remove();
  }

  private @Nullable Object holdTake(final CountDownLatch taking, final CountDownLatch takeMayReturn) {
    taking.countDown();
    this.awaitUninterruptibly(takeMayReturn);

    return null;
  }

  @Test
  void shouldDropAFrameWithMorePagesThanTheScreenHas() throws InterruptedException {
    final Mcv2Configuration narrow = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .pageMap(PAGE_MAP)
      .pageSlots(1)
      .maximumFrameRate(0)
      .build();
    final Mcv2Result result = this.result(narrow, null);
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenReturn(Mcv2ChannelTest.large());
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, true, 1, 1, LAMBDA)));
    result.getChannel().requestKeyframe();
    final List<byte[]> heard = new ArrayList<>();
    result.setFrameListener(heard::add);
    result.send(encoder, new Mcv2Result.Arrival(new byte[128 * 128 * 3], 128, 128, 0, Mcv2Pacer.Preset.ONLY), 0);
    verify(encoder).requestKeyframe();
    verify(encoder).setFrameLimit(TransportPages.capacity());
    assertEquals(List.of(), heard, "a frame that is not sent is not heard");

    final long dropped = result.getStatistics().getDropped();
    assertEquals(1, dropped);

    final long frames = result.getStatistics().getFrames();
    assertEquals(0, frames);
  }

  @Test
  void shouldRequestAKeyframeOnceTheLastIsFourSecondsOld() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(CLOCK_START_NANOSECONDS);
    final Mcv2Result result = this.createSynchronousResult(clock::get);
    final Mcv2 encoder = this.createFrameEncoder();
    this.sendFrame(result, encoder, Mcv2FrameKind.KEYFRAME);
    clearInvocations(encoder);
    while (clock.get() - CLOCK_START_NANOSECONDS < KEYFRAME_PERIOD_NANOSECONDS) {
      clock.addAndGet(SLOW_FRAME_INTERVAL_NANOSECONDS);
      this.sendFrame(result, encoder, Mcv2FrameKind.PREDICTED);
    }

    verify(encoder).requestKeyframe();
  }

  @Test
  void shouldRequestNoKeyframeBeforeTheLastIsFourSecondsOld() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(CLOCK_START_NANOSECONDS);
    final Mcv2Result result = this.createSynchronousResult(clock::get);
    final Mcv2 encoder = this.createFrameEncoder();
    this.sendFrame(result, encoder, Mcv2FrameKind.KEYFRAME);
    clearInvocations(encoder);
    clock.addAndGet(KEYFRAME_PERIOD_NANOSECONDS - 1);
    this.sendFrame(result, encoder, Mcv2FrameKind.PREDICTED);

    verify(encoder, never()).requestKeyframe();
  }

  @Test
  void shouldCountTheFourSecondsFromTheLatestKeyframe() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(CLOCK_START_NANOSECONDS);
    final Mcv2Result result = this.createSynchronousResult(clock::get);
    final Mcv2 encoder = this.createFrameEncoder();
    this.sendFrame(result, encoder, Mcv2FrameKind.KEYFRAME);
    clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
    this.sendFrame(result, encoder, Mcv2FrameKind.KEYFRAME);
    clearInvocations(encoder);
    clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
    this.sendFrame(result, encoder, Mcv2FrameKind.PREDICTED);

    verify(encoder, never()).requestKeyframe();

    clock.addAndGet(SLOW_FRAME_INTERVAL_NANOSECONDS);
    this.sendFrame(result, encoder, Mcv2FrameKind.PREDICTED);

    verify(encoder).requestKeyframe();
  }

  private Mcv2 createFrameEncoder() {
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenReturn(Mcv2ChannelTest.keyframe());

    return encoder;
  }

  private void sendFrame(final Mcv2Result result, final Mcv2 encoder, final Mcv2FrameKind kind) throws InterruptedException {
    final boolean keyframe = kind == Mcv2FrameKind.KEYFRAME;
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, keyframe, 1, 1, LAMBDA)));
    result.send(encoder, new Mcv2Result.Arrival(new byte[32 * 32 * 3], 32, 32, 0, Mcv2Pacer.Preset.ONLY), 0);
  }

  @Test
  void shouldEndTheEncodeLoopAtOnceWhenItFindsNoPipeline() throws InterruptedException {
    final Mcv2Result result = this.result(this.configuration, null);
    // the screen's thread of a start that a release overtook finds no pipeline to stop
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> result.encodeLoop(mock(Mcv2.class)));

    final Mcv2Result.@Nullable Arrival taken = result.take();
    assertNull(taken);
  }

  @Test
  void shouldStopWaitingOnceReleasedOrInterrupted() throws InterruptedException {
    final Mcv2Result result = this.result(this.configuration, null);
    final Mcv2Result.@Nullable Arrival takenBeforeStart = result.take();
    assertNull(takenBeforeStart);

    result.start();
    final Thread waiter = new Thread(() -> result.encodeLoop(mock(Mcv2.class)));
    waiter.start();
    waiter.interrupt();
    waiter.join(TimeUnit.SECONDS.toMillis(10));

    final boolean waiterStopped = !waiter.isAlive();
    assertTrue(waiterStopped);

    result.release();

    final Mcv2Result.@Nullable Arrival takenAfterRelease = result.take();
    assertNull(takenAfterRelease);
  }

  private int play(final Mcv2Result result, final AtomicLong clock, final Mcv2 encoder, final ImageBuffer frame, final int frames)
    throws InterruptedException {
    int encoded = 0;
    for (int frameNumber = 0; frameNumber < frames; frameNumber++) {
      final long arrival = clock.addAndGet(FRAME_INTERVAL_NANOSECONDS);
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
  void shouldPaceTheFramesToWhatTheBudgetSustains() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration onlyPack = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      // one preset, so the pacer steps the frame rate and the size, which this is about
      .settings(Settings.FAST)
      .maximumFrameRate(0)
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
    final Optional<Mcv2Pacer.Rung> rungBeforePacing = result.getRung();
    final boolean rungAbsentBeforePacing = rungBeforePacing.isEmpty();
    assertTrue(rungAbsentBeforePacing);

    result.resetPacer();

    final Mcv2Pacer.Rung expectedFirstRung = Mcv2Pacer.Rung.of(64, 32, 1);
    final Mcv2Pacer.Rung firstRung = result.getRung().orElseThrow();
    assertEquals(expectedFirstRung, firstRung);

    final AtomicLong encodeNanoseconds = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> this.advanceEncodeClock(clock, encodeNanoseconds));
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    // the viewer with the pack, dithered for until then, is shown the screen; every frame is encoded while 5 ms fit a frame's 16.7 ms
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    // five seconds while a new encoder warms up: the pacer judges nothing yet
    final int encodedWhileWarming = this.play(result, clock, encoder, frame, 300);
    assertEquals(300, encodedWhileWarming);
    verify(this.algorithm, times(1)).ditherIntoBytes(any());

    // at 25 ms a frame the screen steps down to every other frame, and says why
    encodeNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(25));
    this.play(result, clock, encoder, frame, ADAPTATION_FRAMES);

    final int changesAfterSteppingDown = changes.size();
    assertEquals(1, changesAfterSteppingDown);

    final boolean steppedDown = changes.getFirst().isDown();
    assertTrue(steppedDown);

    final Mcv2Pacer.Rung expectedHalvedRung = Mcv2Pacer.Rung.of(64, 32, 2);
    final Mcv2Pacer.Rung halvedRung = result.getRung().orElseThrow();
    assertEquals(expectedHalvedRung, halvedRung);

    final int encodedAtHalfRate = this.play(result, clock, encoder, frame, 60);
    assertEquals(30, encodedAtHalfRate);

    // at two seconds a frame nothing fits: every viewer is dithered for, and the page frames go
    encodeNanoseconds.set(TimeUnit.SECONDS.toNanos(2));
    this.play(result, clock, encoder, frame, ADAPTATION_FRAMES);

    final int changesAfterDithering = changes.size();
    assertEquals(2, changesAfterDithering);

    final Mcv2Pacer.Rung ditheredRung = result.getRung().orElseThrow();
    assertEquals(Mcv2Pacer.Rung.DITHERED, ditheredRung);

    this.server.runTasks();
    verify(this.screen).remove();

    clearInvocations(this.algorithm);

    final int encodedWhileDithered = this.play(result, clock, encoder, frame, 60);
    assertEquals(0, encodedWhileDithered);
    verify(this.algorithm, times(60)).ditherIntoBytes(any());

    // thirty seconds later the screen tries encoding again: the page frames return
    encodeNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(5));
    this.play(result, clock, encoder, frame, 35 * 60);

    final Mcv2Pacer.Rung retriedFrom = changes.get(2).getFrom();
    assertEquals(Mcv2Pacer.Rung.DITHERED, retriedFrom);

    this.server.runTasks();
    verify(this.screen).build();

    final boolean stillDithered = result.getRung().orElseThrow().isDithered();
    assertFalse(stillDithered);

    result.release();

    final Optional<Mcv2Pacer.Rung> rungAfterRelease = result.getRung();
    final boolean rungAbsentAfterRelease = rungAfterRelease.isEmpty();
    assertTrue(rungAbsentAfterRelease);
  }

  private byte[] advanceEncodeClock(final AtomicLong clock, final AtomicLong encodeNanoseconds) {
    clock.addAndGet(encodeNanoseconds.get());

    return Mcv2ChannelTest.keyframe();
  }

  @Test
  void shouldStepDownToASmallerVideoAndBackUp() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration onlyPack = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(WORLD, 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      // one preset, so the pacer steps the frame rate and the size, which this is about
      .settings(Settings.FAST)
      .maximumFrameRate(0)
      .build();
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    final Mcv2Screen smallScreen = mock(Mcv2Screen.class);
    when(smallScreen.getAnchors()).thenReturn(List.of());
    final List<Mcv2Configuration> resized = new ArrayList<>();
    // the same height, so only the width tells the sizes apart
    result.setSmallerSizes(List.of(new int[] { 16, 32 }), configuration -> this.recordResizedChannel(configuration, resized, smallScreen));
    result.resetPacer();
    final AtomicLong fullNanoseconds = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final List<Integer> widths = new ArrayList<>();
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(invocation ->
      this.recordWidthAndAdvanceClock(invocation, widths, clock, fullNanoseconds)
    );
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 300);
    fullNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(100));
    this.play(result, clock, encoder, frame, PROFILE_CHANGE_FRAMES);

    // no frame rate of 64x32 fits 100 ms with room; 16x32 at 30 fps does
    final Mcv2Pacer.Rung expectedSmallRung = Mcv2Pacer.Rung.of(16, 32, 2);
    final Mcv2Pacer.Rung smallRung = result.getRung().orElseThrow();
    assertEquals(expectedSmallRung, smallRung);

    this.server.runTasks();
    verify(this.screen).remove();
    verify(smallScreen).build();

    final int resizeCount = resized.size();
    assertEquals(1, resizeCount);

    final int resizedWidth = resized.getFirst().getVideoWidth();
    assertEquals(16, resizedWidth);

    final int smallWidth = result.getConfiguration().getVideoWidth();
    assertEquals(16, smallWidth);

    result.applyFilter(Images.solid(64, 32, FRAME_COLOR), this.metadata);
    this.server.runTasks();
    widths.clear();
    this.play(result, clock, encoder, Images.solid(64, 32, FRAME_COLOR), 60);

    final boolean encodedSmall = !widths.isEmpty() && widths.stream().allMatch(width -> width == 16);
    assertTrue(encodedSmall, widths.toString());

    // the load drops: back to the size it was asked for, once the top rung may be tried again
    fullNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(4));
    this.play(result, clock, encoder, Images.solid(64, 32, FRAME_COLOR), 60 * 30);

    final Mcv2Pacer.Rung expectedFullRung = Mcv2Pacer.Rung.of(64, 32, 1);
    final Mcv2Pacer.Rung fullRung = result.getRung().orElseThrow();
    assertEquals(expectedFullRung, fullRung);

    this.server.runTasks();
    verify(smallScreen).remove();

    final int videoWidth = result.getConfiguration().getVideoWidth();
    assertEquals(64, videoWidth);

    result.sync();
    result.release();
    result.sync();
    assertThrows(NullPointerException.class, () -> result.setSmallerSizes(null, configuration -> null));
    assertThrows(NullPointerException.class, () -> result.setSmallerSizes(List.of(), null));
  }

  private Mcv2Channel recordResizedChannel(
    final Mcv2Configuration configuration,
    final List<Mcv2Configuration> resized,
    final Mcv2Screen smallScreen
  ) {
    resized.add(configuration);

    return new Mcv2Channel(configuration, this.viewers, configuration.getVideoWidth() == 16 ? smallScreen : this.screen);
  }

  private byte[] recordWidthAndAdvanceClock(
    final InvocationOnMock invocation,
    final List<Integer> widths,
    final AtomicLong clock,
    final AtomicLong fullNanoseconds
  ) {
    final int width = invocation.getArgument(1);
    widths.add(width);
    clock.addAndGet((fullNanoseconds.get() * width * (int) invocation.getArgument(2)) / (64 * 32));

    return Mcv2ChannelTest.keyframe();
  }

  @Test
  void shouldStepDownToAShorterVideoOfTheSameWidth() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration onlyPack = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(WORLD, 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      .settings(Settings.FAST)
      .maximumFrameRate(0)
      .build();
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    final Mcv2Screen shortScreen = mock(Mcv2Screen.class);
    when(shortScreen.getAnchors()).thenReturn(List.of());
    result.setSmallerSizes(List.of(new int[] { 64, 16 }), configuration -> new Mcv2Channel(configuration, this.viewers, shortScreen));
    result.resetPacer();
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(invocation -> this.encodeInPixelTime(clock, invocation));
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    // past the five seconds of warm-up, no frame rate of 64x32 fits 100 ms with room, but one of 64x16 does
    this.play(result, clock, encoder, frame, 420);
    this.server.runTasks();
    verify(this.screen).remove();
    verify(shortScreen).build();

    final int shortHeight = result.getConfiguration().getVideoHeight();
    assertEquals(16, shortHeight);

    result.release();
  }

  private byte[] encodeInPixelTime(final AtomicLong clock, final InvocationOnMock invocation) {
    final int width = invocation.getArgument(1);
    final int height = invocation.getArgument(2);
    // 100 ms for the full 64x32 picture, less for fewer pixels
    final long encodeNanoseconds = (TimeUnit.MILLISECONDS.toNanos(100) * width * height) / (64 * 32);
    clock.addAndGet(encodeNanoseconds);
    return Mcv2ChannelTest.keyframe();
  }

  @Test
  void shouldStayAtItsLowestFrameRateWithoutDitheredMaps() throws InterruptedException {
    final AtomicLong clock = new AtomicLong();
    final Mcv2Result result = new Mcv2Result(
      this.configuration,
      new Mcv2Channel(this.configuration, this.viewers, this.screen),
      null,
      clock::get,
      Runnable::run
    );
    result.resetPacer();
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> this.advanceSlowEncodeClock(clock));
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 600);

    final Mcv2Pacer.Rung expectedRung = Mcv2Pacer.Rung.of(64, 32, 6);
    final Mcv2Pacer.Rung rung = result.getRung().orElseThrow();
    assertEquals(expectedRung, rung);

    result.release();
  }

  private byte[] advanceSlowEncodeClock(final AtomicLong clock) {
    clock.addAndGet(TimeUnit.SECONDS.toNanos(SLOW_ENCODE_SECONDS));

    return Mcv2ChannelTest.keyframe();
  }

  @Test
  void shouldEncodeInTheBudgetItIsGiven() throws InterruptedException {
    try (final Pool budget = new Pool(1)) {
      final Mcv2Configuration own = Mcv2Configuration.builder()
        .viewers(List.of(WITH_PACK))
        .origin(new Location(mock(World.class), 0, 64, 0))
        .facing(BlockFace.SOUTH)
        .map(WALL_MAP)
        .columns(1)
        .rows(1)
        .video(64, 32)
        .pageMap(PAGE_MAP)
        .encoderPool(budget)
        .maximumFrameRate(0)
        .build();
      final Pool ownPool = own.getEncoderPool();
      assertSame(budget, ownPool);

      final Pool sharedPool = Pool.shared();
      final Pool defaultPool = this.configuration.getEncoderPool();
      assertSame(sharedPool, defaultPool);

      final Mcv2Result result = this.result(own, null);
      result.start();
      final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
      result.applyFilter(frame, this.metadata);
      this.server.runTasks();
      result.applyFilter(frame, this.metadata);
      this.awaitFrames(result, 1);
      result.release();
    }

    assertThrows(NullPointerException.class, () -> Mcv2Configuration.builder().encoderPool(null));
    assertThrows(NullPointerException.class, () -> this.result(this.configuration, null).setPacingListener(null));
    assertThrows(NullPointerException.class, () -> this.result(this.configuration, null).setFrameListener(null));
  }

  @Test
  void shouldThinASourceFasterThanTheScreensRate() {
    final Mcv2Configuration thinnedConfiguration = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      .build();
    // frames handed to applyFilter read this clock, so no real time passes between two of them
    final AtomicLong clock = new AtomicLong();
    final Mcv2Result result = new Mcv2Result(
      thinnedConfiguration,
      new Mcv2Channel(thinnedConfiguration, this.viewers, this.screen),
      null,
      clock::get,
      null
    );
    final long millisecond = TimeUnit.MILLISECONDS.toNanos(1);
    // a browser painting every 5 ms: 30 frames of a second's 200
    int taken = 0;
    for (long now = 0; now < 1000 * millisecond; now += 5 * millisecond) {
      taken += result.takeFrame(now) ? 1 : 0;
    }

    final boolean aboutThirtyTaken = taken >= 30 && taken <= 32;
    assertTrue(aboutThirtyTaken, "frames taken: %s".formatted(taken));

    // a 30 fps source with jitter keeps every frame
    long now = 5000 * millisecond;
    result.takeFrame(now);
    for (int frame = 0; frame < 60; frame++) {
      now += (frame % 2 == 0 ? 30 : 36) * millisecond + millisecond / 2;
      final boolean frameTaken = result.takeFrame(now);
      assertTrue(frameTaken, "frame %s".formatted(frame));
    }

    // frames saved up during a pause do not come out as a burst
    now += 2000 * millisecond;
    final boolean takenAfterPause = result.takeFrame(now);
    assertTrue(takenAfterPause);

    final boolean burstTaken = result.takeFrame(now + millisecond);
    assertFalse(burstTaken);

    // and a frame that is not the screen's goes nowhere: of two frames at once, the second
    clock.set(now + 1000 * millisecond);
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    final boolean firstApplied = result.applyFilter(frame, this.metadata);
    assertTrue(firstApplied);

    final boolean secondApplied = result.applyFilter(frame, this.metadata);
    assertFalse(secondApplied);
  }

  @Test
  void shouldTakeAFrameThatBringsTheCreditToExactlyOne() {
    // 2^-25 frames a nanosecond, so 2^24 nanoseconds add exactly half a frame
    final double framesPerSecond = TimeUnit.SECONDS.toNanos(1) / (double) (1L << 25);
    final Mcv2Result result = this.result(
      Mcv2Configuration.builder()
        .viewers(List.of(WITH_PACK))
        .origin(new Location(mock(World.class), 0, 64, 0))
        .facing(BlockFace.SOUTH)
        .map(WALL_MAP)
        .columns(1)
        .rows(1)
        .video(64, 32)
        .pageMap(PAGE_MAP)
        .maximumFrameRate(framesPerSecond)
        .build(),
      null
    );
    // the credit starts at one and a half frames, so the first frame leaves half of one
    result.takeFrame(0);

    final boolean taken = result.takeFrame(1L << 24);
    assertTrue(taken);
  }

  @Test
  void shouldConvertBgrToRgbFromThePositionOfTheBuffer() {
    final ByteBuffer bgr = ByteBuffer.wrap(new byte[] { 9, 0x33, 0x22, 0x11, (byte) 0xCC, (byte) 0xBB, (byte) 0xAA, 7 });
    bgr.position(1);
    final byte[] rgb = Mcv2Result.rgbFromBgr(bgr, 2);

    final byte[] expectedRgb = new byte[] { 0x11, 0x22, 0x33, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC };
    assertArrayEquals(expectedRgb, rgb);

    final int position = bgr.position();
    assertEquals(1, position, "the buffer is left as it was");
  }

  @Test
  void shouldRefuseNulls() {
    final NullPointerException noConfiguration = assertThrows(NullPointerException.class, () -> new Mcv2Result(null, this.viewers, null));
    final String noConfigurationMessage = noConfiguration.getMessage();
    assertEquals("Configuration must not be null!", noConfigurationMessage);

    final NullPointerException noChannel = assertThrows(NullPointerException.class, () ->
      new Mcv2Result(this.configuration, (Mcv2Channel) null, null)
    );
    final String noChannelMessage = noChannel.getMessage();
    assertEquals("Channel must not be null!", noChannelMessage);

    final Mcv2Result result = new Mcv2Result(this.configuration, this.viewers, this.algorithm);
    try (final ImageBuffer image = Images.solid(64, 32, 0)) {
      final NullPointerException noFrame = assertThrows(NullPointerException.class, () -> result.applyFilter(null, this.metadata));
      final String noFrameMessage = noFrame.getMessage();
      assertEquals("Frame must not be null!", noFrameMessage);

      final NullPointerException noMetadata = assertThrows(NullPointerException.class, () -> result.applyFilter(image, null));
      final String noMetadataMessage = noMetadata.getMessage();
      assertEquals("Metadata must not be null!", noMetadataMessage);

      final long frames = result.getStatistics().getFrames();
      assertEquals(0, frames, "invalid input cannot publish a frame");
    } finally {
      result.release();
    }
  }

  @Test
  void shouldCountTheKeyframesItSent() {
    final Mcv2Result.Statistics statistics = new Mcv2Result.Statistics();
    statistics.add(Mcv2.Statistics.of(10, true, 1, 5, LAMBDA), 128);
    statistics.add(Mcv2.Statistics.of(20, false, 1, 7, LAMBDA), PREDICTED_MAP_BYTES);

    final long frames = statistics.getFrames();
    assertEquals(2, frames);

    final long keyframes = statistics.getKeyframes();
    assertEquals(1, keyframes);
  }

  @Test
  void shouldCountNoKeyframeForAPredictedFrame() {
    final Mcv2Result.Statistics statistics = new Mcv2Result.Statistics();
    statistics.add(Mcv2.Statistics.of(20, false, 1, 7, LAMBDA), PREDICTED_MAP_BYTES);

    final long frames = statistics.getFrames();
    assertEquals(1, frames);

    final long keyframes = statistics.getKeyframes();
    assertEquals(0, keyframes);
  }

  @Test
  void shouldPaceFromTheTopOfItsLadderOnceStarted() {
    final Mcv2Result result = this.result(this.configuration, null);
    final Optional<Mcv2Pacer.Rung> rungBeforeStart = result.getRung();
    final boolean rungAbsentBeforeStart = rungBeforeStart.isEmpty();
    assertTrue(rungAbsentBeforeStart);

    result.start();
    final Mcv2Pacer.Rung expectedRung = Mcv2Pacer.Rung.of(64, 32, 1);
    final Mcv2Pacer.Rung rung = result.getRung().orElseThrow();
    assertEquals(expectedRung, rung);

    result.release();
  }

  @Test
  void shouldRepairAnUnchangedFallbackAfterVanillaOverwritesItsFirstSnapshot() {
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
    try (final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR)) {
      result.applyFilter(frame, this.metadata);
      final byte[] expected = MapPackets.pattern(128 * 128, 4);
      MapPackets.assertMapPacket(
        MapPackets.unbundle(this.server.getSentPackets(WITHOUT).getLast()).getFirst(),
        WALL_MAP,
        0,
        0,
        128,
        128,
        expected
      );
      // Vanilla can initialize its map after the first snapshot, leaving an unchanged delta stream transparent.
      MapPacketFactory.clear(List.of(WITHOUT), WALL_MAP, 1);
      clock.addAndGet(TimeUnit.SECONDS.toNanos(4) - 1);
      result.applyFilter(frame, this.metadata);
      final int sentAfterIdenticalFrame = this.server.getSentPackets(WITHOUT).size();
      assertEquals(2, sentAfterIdenticalFrame, "ordinary identical frames keep delta savings");

      clock.incrementAndGet();
      result.applyFilter(frame, this.metadata);
      MapPackets.assertMapPacket(
        MapPackets.unbundle(this.server.getSentPackets(WITHOUT).getLast()).getFirst(),
        WALL_MAP,
        0,
        0,
        128,
        128,
        expected
      );
      final int sentAfterRepair = this.server.getSentPackets(WITHOUT).size();
      assertEquals(3, sentAfterRepair, "the fallback repairs an external map reset");

      clock.incrementAndGet();
      result.applyFilter(frame, this.metadata);
      final int sentAfterFreshInterval = this.server.getSentPackets(WITHOUT).size();
      assertEquals(3, sentAfterFreshInterval, "a repair starts a fresh interval");

      MapPacketFactory.clear(List.of(WITHOUT), WALL_MAP, 1);
      clock.addAndGet(TimeUnit.SECONDS.toNanos(4));
      result.applyFilter(frame, this.metadata);
      MapPackets.assertMapPacket(
        MapPackets.unbundle(this.server.getSentPackets(WITHOUT).getLast()).getFirst(),
        WALL_MAP,
        0,
        0,
        128,
        128,
        expected
      );
      final int sentAfterPeriodicRepair = this.server.getSentPackets(WITHOUT).size();
      assertEquals(5, sentAfterPeriodicRepair, "recovery is periodic, not a one-time join delay");
    } finally {
      result.release();
    }
  }

  @Test
  void shouldDitherAgainOnceStartedAgainAndClearTheMapsWhenReleased() {
    final Mcv2Result result = new Mcv2Result(
      this.configuration,
      new Mcv2Channel(this.configuration, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run
    );
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.start();
    result.applyFilter(frame, this.metadata);
    final int sent = this.server.getSentPackets(WITHOUT).size();
    result.release();
    final List<Packet<?>> cleared = this.server.getSentPackets(WITHOUT);
    final int expectedClearedSize = sent + 1;
    final int clearedSize = cleared.size();
    assertEquals(expectedClearedSize, clearedSize);

    MapPackets.assertMapPacket(MapPackets.unbundle(cleared.getLast()).getFirst(), WALL_MAP, 0, 0, 128, 128, new byte[128 * 128]);

    result.start();
    clearInvocations(this.algorithm);
    result.applyFilter(frame, this.metadata);
    verify(this.algorithm).ditherIntoBytes(any());

    result.release();
  }

  @Test
  void shouldClearTheWallOfTheViewersWithThePackWhenReleased() throws InterruptedException {
    // a client with the pack keeps drawing its last picture on maps with the screen's anchors, so a release clears those maps too
    final Mcv2Result result = new Mcv2Result(
      this.configuration,
      new Mcv2Channel(this.configuration, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run
    );
    result.start();
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    this.awaitFrames(result, 1);
    final Set<UUID> expectedRecipients = Set.of(WITH_PACK);
    final Set<UUID> recipients = result.getChannel().getRecipients();
    assertEquals(expectedRecipients, recipients);

    final int withPack = this.server.getSentPackets(WITH_PACK).size();
    final int without = this.server.getSentPackets(WITHOUT).size();

    result.release();

    final List<Packet<?>> cleared = this.server.getSentPackets(WITH_PACK);
    final int expectedClearedSize = withPack + 1;
    final int clearedSize = cleared.size();
    assertEquals(expectedClearedSize, clearedSize, "one bundle clears the wall of the viewer with the pack");

    MapPackets.assertMapPacket(MapPackets.unbundle(cleared.getLast()).getFirst(), WALL_MAP, 0, 0, 128, 128, new byte[128 * 128]);
    final int expectedDitheredSize = without + 1;
    final int ditheredSize = this.server.getSentPackets(WITHOUT).size();
    assertEquals(expectedDitheredSize, ditheredSize, "the dithered maps are cleared once");
  }

  @Test
  void shouldDitherAgainAfterARefusedHandover() {
    final AtomicInteger handed = new AtomicInteger();
    final Executor refusesOnce = task -> this.rejectFirstHandover(task, handed);
    final Mcv2Result result = new Mcv2Result(
      this.configuration,
      new Mcv2Channel(this.configuration, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      refusesOnce
    );
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    result.applyFilter(frame, this.metadata);
    final int handovers = handed.get();
    assertEquals(2, handovers);
    verify(this.algorithm).ditherIntoBytes(any());

    result.release();
  }

  private void rejectFirstHandover(final Runnable task, final AtomicInteger handed) {
    if (handed.getAndIncrement() == 0) {
      final String message = "released!";
      throw new RejectedExecutionException(message);
    }

    task.run();
  }

  private boolean hasPipelineThreads(final Set<Thread> before) {
    return !threads(SCREEN_THREAD_NAME, before).isEmpty() || !threads(SENDER_THREAD_NAME, before).isEmpty();
  }

  private void awaitPipelineEnd(final Set<Thread> before) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (this.hasPipelineThreads(before) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
  }

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
  void shouldKeepTheEncodingThreadInterruptedAfterAnInterruptedTake() {
    final Mcv2Result result = this.result(this.configuration, null);
    final Thread current = Thread.currentThread();
    try {
      result.start();
      current.interrupt();
      result.encodeLoop(mock(Mcv2.class));

      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted);
    } finally {
      final boolean _ = Thread.interrupted();
      result.release();
    }
  }

  @Test
  void shouldLeaveTheSenderInterruptedAfterRelease() {
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    final Set<Thread> knownThreads = stackTraces.keySet();
    final Set<Thread> before = Set.copyOf(knownThreads);
    final Mcv2Result result = this.result(this.configuration, null);
    try {
      result.start();
      final Set<Thread> senders = threads(SENDER_THREAD_NAME, before);
      final Iterator<Thread> iterator = senders.iterator();
      final Thread sender = iterator.next();
      result.release();

      final boolean senderAlive = sender.isAlive();
      final boolean senderInterrupted = sender.isInterrupted();
      assertFalse(senderAlive);
      assertTrue(senderInterrupted);
    } finally {
      result.release();
    }
  }

  @Test
  void shouldExcludeLiveKeyframeHandoffsFromPacing() throws InterruptedException {
    final Mcv2Pacer.Rung rung = this.rungAfterSlowHandoffs(Mcv2FrameKind.KEYFRAME);
    final int divisor = rung.getDivisor();
    assertEquals(1, divisor);
  }

  @Test
  void shouldPaceLivePredictedFrameHandoffs() throws InterruptedException {
    final Mcv2Pacer.Rung rung = this.rungAfterSlowHandoffs(Mcv2FrameKind.PREDICTED);
    final int divisor = rung.getDivisor();
    assertEquals(2, divisor);
  }

  private Mcv2Pacer.Rung rungAfterSlowHandoffs(final Mcv2FrameKind kind) throws InterruptedException {
    final AtomicLong clock = new AtomicLong();
    final Pool budget = this.interruptibleBudget();
    final Mcv2Configuration configuration = this.pooledScreen(budget);
    final Mcv2Channel channel = mock(Mcv2Channel.class);
    when(channel.send(any())).thenReturn(OptionalInt.of(0));
    final Mcv2 encoder = mock(Mcv2.class);
    final Mcv2.Pending pending = mock(Mcv2.Pending.class);
    when(pending.isKeyframe()).thenReturn(kind == Mcv2FrameKind.KEYFRAME);
    when(encoder.begin(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> this.advanceHandoffClock(clock, pending));
    final Mcv2.Encoded encoded = this.completedFrame(kind);
    when(encoder.finish(pending)).thenReturn(encoded);
    final Mcv2Result result = new Mcv2Result(configuration, channel, null, clock::get, Runnable::run, _ -> encoder);
    try (final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR)) {
      result.start();
      result.applyFilter(frame, this.metadata);
      clock.set(FRAME_INTERVAL_NANOSECONDS);
      result.applyFilter(frame, this.metadata);
      this.sendSlowHandoffs(result, encoder, clock);

      final Optional<Mcv2Pacer.Rung> rung = result.getRung();
      return rung.orElseThrow();
    } finally {
      result.release();
    }
  }

  private Mcv2.Pending advanceHandoffClock(final AtomicLong clock, final Mcv2.Pending pending) {
    clock.addAndGet(HANDOFF_DURATION_NANOSECONDS);

    return pending;
  }

  private void sendSlowHandoffs(final Mcv2Result result, final Mcv2 encoder, final AtomicLong clock) throws InterruptedException {
    final Mcv2Result.Arrival arrival = new Mcv2Result.Arrival(new byte[64 * 32 * 3], 64, 32, 0, Mcv2Pacer.Preset.ONLY);
    for (int frameNumber = 0; frameNumber < 16; frameNumber++) {
      clock.addAndGet(HANDOFF_INTERVAL_NANOSECONDS);
      result.send(encoder, arrival, frameNumber);
    }
  }

  private Pool interruptibleBudget() throws InterruptedException {
    final Pool budget = mock(Pool.class);
    when(budget.runInterruptibly(any())).thenAnswer(invocation -> invocation.<Callable<?>>getArgument(0).call());

    return budget;
  }

  private Mcv2Configuration pooledScreen(final Pool budget) {
    return Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(WORLD, 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      .settings(Settings.FAST)
      .encoderPool(budget)
      .maximumFrameRate(0)
      .build();
  }

  private Mcv2.Encoded completedFrame(final Mcv2FrameKind kind) {
    final Mcv2.Encoded encoded = mock(Mcv2.Encoded.class);
    when(encoded.getData()).thenReturn(Mcv2ChannelTest.keyframe());
    when(encoded.getStatistics()).thenReturn(Mcv2.Statistics.of(1, kind == Mcv2FrameKind.KEYFRAME, 1, 1, LAMBDA));

    return encoded;
  }

  @Test
  void shouldInterruptTheScreenWhenAnotherEncoderStopsThePipeline() throws InterruptedException {
    final Pool budget = this.interruptibleBudget();
    final Mcv2Configuration configuration = this.pooledScreen(budget);
    final CountDownLatch sending = new CountDownLatch(1);
    final CountDownLatch sendMayReturn = new CountDownLatch(1);
    final Mcv2Channel channel = mock(Mcv2Channel.class);
    when(channel.getRecipients()).thenReturn(Set.of(WITH_PACK));
    when(channel.send(any())).thenAnswer(_ -> this.holdDelivery(sending, sendMayReturn));
    final CountDownLatch secondSearch = new CountDownLatch(1);
    final CountDownLatch searchMayReturn = new CountDownLatch(1);
    final AtomicReference<Thread> searching = new AtomicReference<>();
    final AtomicInteger searches = new AtomicInteger();
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.getSettings()).thenReturn(Settings.FAST);
    final Mcv2.Pending pending = mock(Mcv2.Pending.class);
    when(encoder.begin(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ ->
      this.blockSecondSearch(searches, searching, secondSearch, searchMayReturn, pending)
    );
    final Mcv2.Encoded encoded = this.completedFrame(Mcv2FrameKind.KEYFRAME);
    when(encoder.finish(pending)).thenReturn(encoded);
    final Mcv2Result result = new Mcv2Result(configuration, channel, null, System::nanoTime, Runnable::run, _ -> encoder);
    try (final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR)) {
      result.start();
      result.applyFilter(frame, this.metadata);
      final boolean deliveryStarted = sending.await(PIPELINE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(deliveryStarted);

      result.applyFilter(frame, this.metadata);
      final boolean secondSearchStarted = secondSearch.await(PIPELINE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(secondSearchStarted);

      final Thread current = Thread.currentThread();
      current.interrupt();
      result.encodeLoop(mock(Mcv2.class));
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted);

      final Thread worker = requireNonNull(searching.get());
      worker.join(TimeUnit.SECONDS.toMillis(PIPELINE_TIMEOUT_SECONDS));
      final boolean workerAlive = worker.isAlive();
      assertFalse(workerAlive);
    } finally {
      final boolean _ = Thread.interrupted();
      sendMayReturn.countDown();
      searchMayReturn.countDown();
      result.release();
    }
  }

  private Mcv2.Pending blockSecondSearch(
    final AtomicInteger searches,
    final AtomicReference<Thread> searching,
    final CountDownLatch secondSearch,
    final CountDownLatch searchMayReturn,
    final Mcv2.Pending pending
  ) throws InterruptedException {
    if (searches.getAndIncrement() == 1) {
      searching.set(Thread.currentThread());
      secondSearch.countDown();
      searchMayReturn.await();
    }

    return pending;
  }

  private OptionalInt holdDelivery(final CountDownLatch sending, final CountDownLatch sendMayReturn) {
    sending.countDown();
    this.awaitUninterruptibly(sendMayReturn);

    return OptionalInt.of(0);
  }

  @Test
  void shouldEndItsThreadsBeforeReleaseReturns() throws Exception {
    final Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());
    final CountDownLatch encoding = new CountDownLatch(1);
    final Mcv2 slow = mock(Mcv2.class);
    when(slow.getSettings()).thenReturn(Settings.FAST);
    final Mcv2.Pending pending = mock(Mcv2.Pending.class);
    when(slow.begin(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> this.awaitSearchCompletion(encoding, pending));
    final Mcv2.Encoded encoded = mock(Mcv2.Encoded.class);
    when(encoded.getData()).thenReturn(Mcv2ChannelTest.keyframe());
    when(encoded.getStatistics()).thenReturn(Mcv2.Statistics.of(1, true, 1, 1, LAMBDA));
    when(slow.finish(pending)).thenReturn(encoded);
    final Pool budget = mock(Pool.class);
    when(budget.encoder(any(), any())).thenReturn(slow);
    when(budget.runInterruptibly(any())).thenAnswer(invocation -> invocation.<Callable<?>>getArgument(0).call());
    final Mcv2Configuration own = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK, WITHOUT))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      .encoderPool(budget)
      .maximumFrameRate(0)
      .build();
    // no executor given: the result dithers on a thread of its own
    final Mcv2Result result = new Mcv2Result(own, new Mcv2Channel(own, this.viewers, this.screen), this.algorithm, System::nanoTime, null);
    result.start();
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    final boolean encodingBegan = encoding.await(5, TimeUnit.SECONDS);
    assertTrue(encodingBegan);

    result.release();
    final Set<Thread> screenThreads = threads(SCREEN_THREAD_NAME, before);
    assertEquals(Set.of(), screenThreads);

    final Set<Thread> senderThreads = threads(SENDER_THREAD_NAME, before);
    assertEquals(Set.of(), senderThreads);

    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!threads("mcav-mcv2-dither", before).isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }

    final Set<Thread> ditherThreads = threads("mcav-mcv2-dither", before);
    assertEquals(Set.of(), ditherThreads);
  }

  private Mcv2.Pending awaitSearchCompletion(final CountDownLatch encoding, final Mcv2.Pending pending) {
    encoding.countDown();
    // a search does not stop at an interrupt: it runs to its end
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEARCH_DURATION_MILLISECONDS);
    while (System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }

    return pending;
  }

  @Test
  void shouldDropTheWaitingFrameWhenItStepsDownToTheDitheredMaps() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration onlyPack = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      .maximumFrameRate(0)
      .build();
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    result.resetPacer();
    final AtomicLong encodeNanoseconds = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> this.advanceEncodeClock(clock, encodeNanoseconds));
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 300);
    // two seconds a frame: nothing fits, and the frame that arrived while the last was encoded is not encoded any more
    encodeNanoseconds.set(TimeUnit.SECONDS.toNanos(2));
    for (int frameNumber = 0; frameNumber < 20 && !result.getRung().orElseThrow().isDithered(); frameNumber++) {
      clock.addAndGet(FRAME_INTERVAL_NANOSECONDS);
      result.applyFilter(frame, this.metadata);
      final Mcv2Result.Arrival encoding = result.poll();
      assertNotNull(encoding);
      result.applyFilter(frame, this.metadata);
      result.send(encoder, encoding, 300 + frameNumber);
    }

    final boolean dithered = result.getRung().orElseThrow().isDithered();
    assertTrue(dithered);

    final Mcv2Result.@Nullable Arrival remaining = result.poll();
    assertNull(remaining);

    result.release();
  }

  private void seesSixChunksFrom(final Player player, final AtomicReference<Location> position) {
    when(player.getViewDistance()).thenReturn(6);
    when(player.getWorld()).thenAnswer(_ -> position.get().getWorld());
    when(player.getLocation()).thenAnswer(_ -> position.get());
  }

  @Test
  void shouldGiveNoDitheredMapsToAViewerTooFarFromTheWallWhenTheyStandInForTheFrames() throws InterruptedException {
    when(WORLD.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000a0a0"));
    when(ELSEWHERE.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000b0b0"));
    final UUID far = UUID.fromString(OTHER_VIEWER_ID);
    final UUID otherWorld = UUID.fromString("00000000-0000-0000-0000-000000000044");
    final AtomicReference<Location> farPosition = new AtomicReference<>(new Location(WORLD, 400, 64, 0));
    this.seesSixChunksFrom(requireNonNull(Bukkit.getPlayer(WITHOUT)), new AtomicReference<>(new Location(WORLD, 0, 64, 5)));
    this.seesSixChunksFrom(this.server.addPlayer(far), farPosition);
    this.seesSixChunksFrom(this.server.addPlayer(otherWorld), new AtomicReference<>(new Location(ELSEWHERE, 0, 64, 0)));
    PacketUtils.initialize();
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration wall = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK, WITHOUT, far, otherWorld))
      .origin(new Location(WORLD, 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      .settings(Settings.FAST)
      .maximumFrameRate(0)
      .build();
    final Mcv2Result result = new Mcv2Result(
      wall,
      new Mcv2Channel(wall, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    result.resetPacer();
    // opened as start opens it, without the threads: the test hands the frames to the encoder itself
    result.getChannel().open();
    final AtomicLong encodeNanoseconds = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> this.advanceEncodeClock(clock, encodeNanoseconds));
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 300);
    // two seconds a frame: the pacer falls back to the dithered maps, which every viewer near the wall is shown
    encodeNanoseconds.set(TimeUnit.SECONDS.toNanos(2));
    for (int frameNumber = 0; frameNumber < 600 && !result.getRung().orElseThrow().isDithered(); frameNumber++) {
      this.play(result, clock, encoder, frame, 1);
    }

    final boolean dithered = result.getRung().orElseThrow().isDithered();
    assertTrue(dithered);

    this.server.runTasks();
    verify(this.screen).remove();

    final int withPack = this.server.getSentPackets(WITH_PACK).size();
    this.play(result, clock, encoder, frame, 30);
    final boolean shownToThePackViewer = this.server.getSentPackets(WITH_PACK).size() > withPack;
    assertTrue(shownToThePackViewer, "the viewer with the pack is shown the dithered maps");

    final List<Packet<?>> farPackets = this.server.getSentPackets(far);
    assertEquals(List.of(), farPackets, "a viewer 400 blocks from the wall cannot see it");

    final List<Packet<?>> otherWorldPackets = this.server.getSentPackets(otherWorld);
    assertEquals(List.of(), otherWorldPackets, "nor one in another world");

    farPosition.set(new Location(WORLD, 0, 64, 5));
    this.server.runTasks();
    this.play(result, clock, encoder, frame, 1);
    final boolean nothingSentNearTheWall = this.server.getSentPackets(far).isEmpty();
    assertFalse(nothingSentNearTheWall, "a viewer who came near the wall sees the dithered maps");

    // the load drops: the pacer tries the frames again, and the first frame encoded after its return is a keyframe
    encodeNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(1));
    for (int frameNumber = 0; frameNumber < 60 * 60 && result.getRung().orElseThrow().isDithered(); frameNumber++) {
      this.play(result, clock, encoder, frame, 1);
    }

    final boolean stillDithered = result.getRung().orElseThrow().isDithered();
    assertFalse(stillDithered);

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
  void shouldRecordWhatTheViewersLinksDidWithEachFrame(@TempDir final Path directory) throws Exception {
    // two viewers with the pack; the first's connection writes nothing, so it falls behind, misses a reference and waits for a keyframe
    final UUID second = UUID.fromString(OTHER_VIEWER_ID);
    this.server.addPlayer(second);
    PacketUtils.initialize();
    when(this.viewers.isLoaded(second)).thenReturn(true);
    final Mcv2Configuration tight = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK, second))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .pageMap(PAGE_MAP)
      .backlogLimit(200)
      .unsentLimit(0)
      .maximumFrameRate(0)
      .build();
    final Mcv2Result result = this.result(tight, null);
    result.getChannel().update();
    this.server.runTasks();
    result.getChannel().update();
    final List<byte[]> frames = List.of(
      Mcv2ChannelTest.frame(0, 0, Mcv2FrameKind.KEYFRAME),
      Mcv2ChannelTest.frame(1, 0, Mcv2FrameKind.PREDICTED),
      Mcv2ChannelTest.frame(2, 1, Mcv2FrameKind.PREDICTED),
      Mcv2ChannelTest.frame(3, 2, Mcv2FrameKind.PREDICTED),
      Mcv2ChannelTest.frame(4, 4, Mcv2FrameKind.KEYFRAME)
    );
    final Mcv2 encoder = mock(Mcv2.class);
    try (final Recording recording = new Recording()) {
      recording.enable(FRAME_EVENT_NAME);
      recording.start();
      for (int id = 0; id < frames.size(); id++) {
        this.sendRecordedFrame(result, encoder, frames.get(id), id, second);
      }

      recording.stop();
      final Path file = directory.resolve("links.jfr");
      recording.dump(file);
      final List<RecordedEvent> recorded = RecordingFile.readAllEvents(file)
        .stream()
        .filter(event -> event.getEventType().getName().equals(FRAME_EVENT_NAME))
        .toList();
      final List<Integer> expectedSentTo = List.of(2, 2, 1, 1, 2);
      final List<Integer> sentToCounts = recorded
        .stream()
        .map(event -> event.getInt(SENT_TO_FIELD))
        .toList();
      assertEquals(expectedSentTo, sentToCounts);

      final List<Integer> expectedBehind = List.of(0, 0, 1, 0, 0);
      final List<Integer> behindCounts = recorded
        .stream()
        .map(event -> event.getInt(BEHIND_FIELD))
        .toList();
      assertEquals(expectedBehind, behindCounts);

      final List<Integer> expectedWaiting = List.of(0, 0, 0, 1, 0);
      final List<Integer> waitingCounts = recorded
        .stream()
        .map(event -> event.getInt(WAITING_FIELD))
        .toList();
      assertEquals(expectedWaiting, waitingCounts);
    }

    result.release();
  }

  private void sendRecordedFrame(final Mcv2Result result, final Mcv2 encoder, final byte[] frame, final int id, final UUID second)
    throws Exception {
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenReturn(frame);
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(frame.length, id == 0 || id == 4, 1, 1, LAMBDA)));
    if (id == 3) {
      this.server.completeWrites(WITH_PACK);
    }

    result.send(encoder, new Mcv2Result.Arrival(new byte[32 * 32 * 3], 32, 32, 0, Mcv2Pacer.Preset.ONLY), id);
    this.server.completeWrites(second);
  }

  private Mcv2Configuration packScreen(final Settings settings) {
    return Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(WORLD, 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      .settings(settings)
      .maximumFrameRate(0)
      .build();
  }

  private Function<Settings, Mcv2> mockEncoders(final List<Mcv2> made, final FinishOutcome outcome, final CountDownLatch gate) {
    return settings -> this.mockEncoder(settings, made, outcome, gate);
  }

  private Mcv2 mockEncoder(final Settings settings, final List<Mcv2> made, final FinishOutcome outcome, final CountDownLatch gate) {
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.getSettings()).thenReturn(settings);
    final Mcv2.Pending pending = mock(Mcv2.Pending.class);
    when(encoder.begin(any(), anyInt(), anyInt(), anyLong())).thenReturn(pending);

    if (outcome == FinishOutcome.FAILS) {
      when(encoder.finish(pending)).thenThrow(new IllegalStateException(DISAGREEMENT_MESSAGE));
    } else {
      final Mcv2.Encoded encoded = mock(Mcv2.Encoded.class);
      when(encoded.getData()).thenReturn(Mcv2ChannelTest.keyframe());
      when(encoded.getStatistics()).thenReturn(Mcv2.Statistics.of(1, true, 1, 1, 55));
      when(encoder.finish(pending)).thenAnswer(_ -> this.awaitGate(gate, encoded));
    }

    made.add(encoder);
    return encoder;
  }

  private Mcv2.Encoded awaitGate(final CountDownLatch gate, final Mcv2.Encoded encoded) throws InterruptedException {
    gate.await();
    return encoded;
  }

  private Mcv2Channel holdingChannel(final Mcv2Configuration configuration, final CountDownLatch gate) {
    final Mcv2Channel original = new Mcv2Channel(configuration, this.viewers, this.screen);
    final Mcv2Channel channel = spy(original);
    doAnswer(invocation -> this.sendAfter(gate, invocation))
      .when(channel)
      .send(any());
    return channel;
  }

  private @Nullable Object sendAfter(final CountDownLatch gate, final InvocationOnMock invocation) throws Throwable {
    gate.await();
    return invocation.callRealMethod();
  }

  private void awaitEncoders(final List<Mcv2> made, final int count) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (made.size() < count && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }

    final int madeCount = made.size();
    assertEquals(count, madeCount);
  }

  @Test
  void shouldVerifyAFrameBeforeSearchingTheNext() throws InterruptedException {
    final Mcv2Configuration fast = this.packScreen(Settings.FAST);
    final List<Mcv2> made = new CopyOnWriteArrayList<>();
    final CountDownLatch gate = new CountDownLatch(1);
    final Mcv2Result result = new Mcv2Result(
      fast,
      new Mcv2Channel(fast, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run,
      this.mockEncoders(made, FinishOutcome.ENCODES, gate)
    );
    result.start();
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    this.awaitEncoders(made, 1);
    final Mcv2 encoder = made.getFirst();
    verify(encoder, timeout(TimeUnit.SECONDS.toMillis(10))).finish(any());

    result.applyFilter(frame, this.metadata);
    verify(encoder, Mockito.after(500).times(1)).begin(any(), anyInt(), anyInt(), anyLong());

    gate.countDown();
    this.awaitFrames(result, 2);
    verify(encoder, times(2)).begin(any(), anyInt(), anyInt(), anyLong());
    verify(encoder, times(2)).finish(any());

    result.drain();
    result.release();
  }

  @Test
  void shouldLetTheSenderFinishItsFrameBeforeSwitchingTheEncoder() throws InterruptedException {
    final Mcv2Configuration live = this.packScreen(Settings.DEFAULT);
    final List<Mcv2> made = new CopyOnWriteArrayList<>();
    final CountDownLatch gate = new CountDownLatch(1);
    final AtomicReference<Thread> screenThread = new AtomicReference<>();
    final Mcv2Channel channel = this.holdingChannel(live, gate);
    final Mcv2Result result = new Mcv2Result(
      live,
      channel,
      this.algorithm,
      System::nanoTime,
      Runnable::run,
      // it keeps reporting FAST, so every DEFAULT frame switches it
      _ -> this.switchingEncoder(made, new CountDownLatch(0), screenThread)
    );
    result.start();
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    this.awaitEncoders(made, 1);
    final Mcv2 encoder = made.getFirst();
    verify(channel, timeout(TimeUnit.SECONDS.toMillis(10))).send(any());

    result.applyFilter(frame, this.metadata);
    verify(encoder, timeout(TimeUnit.SECONDS.toMillis(10)).times(2)).getSettings();
    this.awaitWaiting(screenThread.get());
    verify(encoder).setSettings(Settings.DEFAULT);

    gate.countDown();
    verify(encoder, timeout(TimeUnit.SECONDS.toMillis(10)).times(2)).setSettings(Settings.DEFAULT);

    result.release();
  }

  private Mcv2 switchingEncoder(final List<Mcv2> made, final CountDownLatch gate, final AtomicReference<Thread> screenThread) {
    final Mcv2 encoder = this.mockEncoder(Settings.FAST, made, FinishOutcome.ENCODES, gate);
    doAnswer(_ -> this.recordThread(screenThread))
      .when(encoder)
      .setSettings(any());
    return encoder;
  }

  private @Nullable Object recordThread(final AtomicReference<Thread> thread) {
    thread.set(Thread.currentThread());
    return null;
  }

  @Test
  void shouldEndADrainWhenTheScreenIsReleased() throws InterruptedException {
    final Mcv2Configuration fast = this.packScreen(Settings.FAST);
    final List<Mcv2> made = new CopyOnWriteArrayList<>();
    final CountDownLatch gate = new CountDownLatch(1);
    final Mcv2Channel channel = this.holdingChannel(fast, gate);
    final Mcv2Result result = new Mcv2Result(
      fast,
      channel,
      this.algorithm,
      System::nanoTime,
      Runnable::run,
      this.mockEncoders(made, FinishOutcome.ENCODES, new CountDownLatch(0))
    );
    final Thread drainer = this.startDrainerWhileTheSenderHoldsAFrame(result, made, channel);

    result.release();
    drainer.join(TimeUnit.SECONDS.toMillis(10));
    final boolean drainerWaits = drainer.isAlive();
    assertFalse(drainerWaits, "a release ends a drain");

    gate.countDown();
  }

  @Test
  void shouldEndADrainWhenTheScreenStopsOnAFailedSearch() throws InterruptedException {
    final Mcv2Configuration fast = this.packScreen(Settings.FAST);
    final List<Mcv2> made = new CopyOnWriteArrayList<>();
    final CountDownLatch gate = new CountDownLatch(1);
    final Mcv2Channel channel = this.holdingChannel(fast, gate);
    final Mcv2Result result = new Mcv2Result(
      fast,
      channel,
      this.algorithm,
      System::nanoTime,
      Runnable::run,
      this.mockEncoders(made, FinishOutcome.ENCODES, new CountDownLatch(0))
    );
    final Thread drainer = this.startDrainerWhileTheSenderHoldsAFrame(result, made, channel);
    final Mcv2 encoder = made.getFirst();
    when(encoder.begin(any(), anyInt(), anyInt(), anyLong())).thenThrow(new IllegalStateException("The search failed!"));

    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    drainer.join(TimeUnit.SECONDS.toMillis(10));
    final boolean drainerWaits = drainer.isAlive();
    assertFalse(drainerWaits, "a screen that stops ends a drain");

    gate.countDown();
    result.release();
  }

  // the sender holds the first frame until the gate opens, so the drainer waits for it
  private Thread startDrainerWhileTheSenderHoldsAFrame(final Mcv2Result result, final List<Mcv2> made, final Mcv2Channel channel)
    throws InterruptedException {
    result.start();
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    this.awaitEncoders(made, 1);
    verify(channel, timeout(TimeUnit.SECONDS.toMillis(10))).send(any());

    final Thread drainer = new Thread(() -> this.drainQuietly(result));
    drainer.setDaemon(true);
    drainer.start();
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (drainer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }

    final Thread.State state = drainer.getState();
    assertEquals(Thread.State.WAITING, state, "the drainer waits for the held frame");

    return drainer;
  }

  private void drainQuietly(final Mcv2Result result) {
    try {
      result.drain();
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  @Test
  void shouldStopTheScreenWhenAFrameFailsItsVerification() throws InterruptedException {
    final Mcv2Configuration fast = this.packScreen(Settings.FAST);
    final List<Mcv2> made = new CopyOnWriteArrayList<>();
    final Mcv2Result result = new Mcv2Result(
      fast,
      new Mcv2Channel(fast, this.viewers, this.screen),
      this.algorithm,
      System::nanoTime,
      Runnable::run,
      this.mockEncoders(made, FinishOutcome.FAILS, new CountDownLatch(0))
    );
    result.start();
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    result.applyFilter(frame, this.metadata);
    this.awaitEncoders(made, 1);
    verify(made.getFirst(), timeout(TimeUnit.SECONDS.toMillis(10))).finish(any());

    result.applyFilter(frame, this.metadata);
    result.applyFilter(frame, this.metadata);
    verify(made.getFirst(), Mockito.after(500).times(1)).begin(any(), anyInt(), anyInt(), anyLong());

    final long frames = result.getStatistics().getFrames();
    assertEquals(0, frames);

    result.release();
  }

  @Test
  void shouldScaleANonPresetLambdaAndStopAtFast() {
    final Settings settings = Settings.of(60, false);
    final Settings fast = Settings.of(45.833333333333336, true);
    final List<Settings> expectedLadder = List.of(settings, fast);
    final @Nullable Object ladder = Mcv2Internals.call(Mcv2Result.class, null, LADDER_METHOD, new Class<?>[] { Settings.class }, settings);
    assertEquals(expectedLadder, ladder);

    final List<Settings> expectedFastLadder = List.of(fast);
    final @Nullable Object fastLadder = Mcv2Internals.call(Mcv2Result.class, null, LADDER_METHOD, new Class<?>[] { Settings.class }, fast);
    assertEquals(expectedFastLadder, fastLadder);
  }

  @Test
  void shouldBuildAScreenWhoseLambdaTimesTheFastLambdaOverflows() {
    final Settings largest = Settings.DEFAULT.withLambda(Double.MAX_VALUE);
    final Mcv2Result result = assertDoesNotThrow(() -> this.result(this.packScreen(largest), null));
    result.release();

    final List<Settings> expectedLadder = List.of(
      largest,
      Settings.of(Double.MAX_VALUE * (Settings.FAST.getLambda() / Settings.DEFAULT.getLambda()), true)
    );
    final @Nullable Object ladder = Mcv2Internals.call(Mcv2Result.class, null, LADDER_METHOD, new Class<?>[] { Settings.class }, largest);
    assertEquals(expectedLadder, ladder);
  }

  @Test
  void shouldNameThePresetsOfItsLadder() {
    final Mcv2Result live = this.result(this.packScreen(Settings.DEFAULT), null);
    live.resetPacer();
    final Mcv2Pacer.Preset expectedPreset = Mcv2Pacer.Preset.of(DEFAULT_PRESET_NAME, 1);
    final Mcv2Pacer.Preset preset = live.getRung().orElseThrow().getPreset();
    assertEquals(expectedPreset, preset);

    // The fastest preset has no faster search to step through.
    final Mcv2Result fastest = this.result(this.packScreen(Settings.FAST), null);
    fastest.resetPacer();
    final Mcv2Pacer.Rung expectedRung = Mcv2Pacer.Rung.of(64, 32, 1);
    final Mcv2Pacer.Rung fastestRung = fastest.getRung().orElseThrow();
    assertEquals(expectedRung, fastestRung);
  }

  private Mcv2 timedEncoder(
    final Settings settings,
    final AtomicLong clock,
    final AtomicLong encodeNanoseconds,
    final List<Settings> switched
  ) {
    final Mcv2 encoder = mock(Mcv2.class);
    final AtomicReference<Settings> current = new AtomicReference<>(settings);
    when(encoder.getSettings()).thenAnswer(_ -> current.get());
    doAnswer(invocation -> this.recordEncoderSettings(invocation, current, switched))
      .when(encoder)
      .setSettings(any());
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ ->
      this.advancePresetEncodeClock(current, clock, encodeNanoseconds)
    );
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    return encoder;
  }

  private @Nullable Object recordEncoderSettings(
    final InvocationOnMock invocation,
    final AtomicReference<Settings> current,
    final List<Settings> switched
  ) {
    current.set(invocation.getArgument(0));
    switched.add(invocation.getArgument(0));

    return null;
  }

  private byte[] advancePresetEncodeClock(
    final AtomicReference<Settings> current,
    final AtomicLong clock,
    final AtomicLong encodeNanoseconds
  ) {
    final Settings settings = current.get();
    final long share = settings.isFast() ? 3 : 4;
    clock.addAndGet((encodeNanoseconds.get() * share) / 4);

    return Mcv2ChannelTest.keyframe();
  }

  @Test
  void shouldStepDownTheLivePresetsWithOneEncoder() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration live = this.packScreen(Settings.DEFAULT);
    final AtomicLong encodeNanoseconds = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final List<Mcv2> made = new ArrayList<>();
    final List<Settings> switched = new ArrayList<>();
    final Mcv2Result result = new Mcv2Result(
      live,
      new Mcv2Channel(live, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run,
      settings -> this.recordTimedEncoder(settings, clock, encodeNanoseconds, switched, made)
    );
    final List<Mcv2Pacer.Change> changes = new ArrayList<>();
    result.setPacingListener(changes::add);
    result.resetPacer();
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    final Mcv2 first = this.timedEncoder(Settings.DEFAULT, clock, encodeNanoseconds, switched);
    made.add(first);
    Mcv2 encoder = this.playSwitching(result, clock, first, frame, 300);
    final int encoderCount = made.size();
    assertEquals(1, encoderCount);

    final Settings firstSettings = made.getFirst().getSettings();
    assertEquals(Settings.DEFAULT, firstSettings);

    // FAST is estimated at 16.2 ms, inside the 16.7 ms frame interval.
    encodeNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(18));
    encoder = this.playSwitching(result, clock, encoder, frame, PROFILE_CHANGE_FRAMES);
    final int changeCount = changes.size();
    assertEquals(1, changeCount);

    final String expectedDescription =
      "MCV2 screen steps down to 64x32 at 60 fps with the FAST search: encoding 64x32 with the DEFAULT search takes 18.0 ms per frame, more than the 16.7 ms a frame has at 60 fps with the encoder threads it has";
    final String description = changes.getFirst().describe();
    assertEquals(expectedDescription, description);

    final int encoderCountAfterStepDown = made.size();
    assertEquals(1, encoderCountAfterStepDown);

    final List<Settings> expectedSwitches = List.of(Settings.FAST);
    assertEquals(expectedSwitches, switched);
    verify(made.getFirst(), Mockito.atLeast(150)).encode(any(), anyInt(), anyInt(), anyLong());

    encodeNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(5));
    this.playSwitching(result, clock, encoder, frame, 30 * 60);
    final int changeCountAfterClimb = changes.size();
    assertEquals(2, changeCountAfterClimb);

    final boolean climbStepsDown = changes.get(1).isDown();
    assertFalse(climbStepsDown);

    final int encoderCountAfterClimb = made.size();
    assertEquals(1, encoderCountAfterClimb);

    final List<Settings> expectedSwitchesAfterClimb = List.of(Settings.FAST, Settings.DEFAULT);
    assertEquals(expectedSwitchesAfterClimb, switched);

    final Mcv2Pacer.Preset expectedPreset = Mcv2Pacer.Preset.of(DEFAULT_PRESET_NAME, 1);
    final Mcv2Pacer.Preset preset = result.getRung().orElseThrow().getPreset();
    assertEquals(expectedPreset, preset);
  }

  private Mcv2 recordTimedEncoder(
    final Settings settings,
    final AtomicLong clock,
    final AtomicLong encodeNanoseconds,
    final List<Settings> switched,
    final List<Mcv2> made
  ) {
    final Mcv2 encoder = this.timedEncoder(settings, clock, encodeNanoseconds, switched);
    made.add(encoder);

    return encoder;
  }

  private Mcv2 playSwitching(final Mcv2Result result, final AtomicLong clock, final Mcv2 first, final ImageBuffer frame, final int frames)
    throws InterruptedException {
    Mcv2 encoder = first;
    for (int frameNumber = 0; frameNumber < frames; frameNumber++) {
      final long arrival = clock.addAndGet(FRAME_INTERVAL_NANOSECONDS);
      result.applyFilter(frame, this.metadata);
      final Mcv2Result.Arrival handed = result.poll();
      if (handed != null) {
        encoder = this.encoderOfPreset(result, handed, encoder);
        result.send(encoder, handed, frameNumber);
      }

      clock.set(arrival);
    }

    return encoder;
  }

  private Mcv2 encoderOfPreset(final Mcv2Result result, final Mcv2Result.Arrival handed, final Mcv2 encoder) {
    final Settings settings = result.settingsFor(handed);
    if (settings.equals(encoder.getSettings())) {
      return encoder;
    }

    return result.switchEncoder(settings, encoder);
  }

  @Test
  void shouldWarnOfAStepDownAndTellOfAStepUp() throws InterruptedException {
    final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    final Mcv2Configuration onlyPack = Mcv2Configuration.builder()
      .viewers(List.of(WITH_PACK))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .video(64, 32)
      .pageMap(PAGE_MAP)
      // one preset, so the pacer steps the frame rate and the size, which this is about
      .settings(Settings.FAST)
      .maximumFrameRate(0)
      .build();
    final Mcv2Result result = new Mcv2Result(
      onlyPack,
      new Mcv2Channel(onlyPack, this.viewers, this.screen),
      this.algorithm,
      clock::get,
      Runnable::run
    );
    result.resetPacer();
    final AtomicLong encodeNanoseconds = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(5));
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.encode(any(), anyInt(), anyInt(), anyLong())).thenAnswer(_ -> this.advanceEncodeClock(clock, encodeNanoseconds));
    when(encoder.getStatistics()).thenReturn(Optional.of(Mcv2.Statistics.of(1, false, 1, 1, LAMBDA)));
    final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
    result.applyFilter(frame, this.metadata);
    this.server.runTasks();
    try (final LogCapture logs = LogCapture.capture(Mcv2Result.class)) {
      this.play(result, clock, encoder, frame, 300);
      // 25 ms a frame steps down to every other frame; 5 ms a frame climbs back once the top is free and has room
      encodeNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(25));
      this.play(result, clock, encoder, frame, ADAPTATION_FRAMES);
      encodeNanoseconds.set(TimeUnit.MILLISECONDS.toNanos(5));
      this.play(result, clock, encoder, frame, 20 * 60);
      final List<Level> expectedLevels = List.of(Level.WARN, Level.DEBUG);
      final List<Level> levels = logs.getEvents().stream().map(LogCapture.RecordedEvent::getLevel).toList();
      assertEquals(expectedLevels, levels);
    }

    result.release();
  }

  @Test
  void shouldShowTheWholeVideoScaledToTheWallOnTheDitheredMaps() {
    final List<String> dithered = new CopyOnWriteArrayList<>();
    final DitherAlgorithm recording = mock(DitherAlgorithm.class);
    when(recording.ditherIntoBytes(any())).thenAnswer(invocation -> this.recordDitheredDimensions(invocation, dithered));
    // a video larger than the wall's maps, and one smaller, as a pacer rung makes it
    for (final int[] video : new int[][] { { 512, 128 }, { 128, 64 } }) {
      final Mcv2Configuration wide = Mcv2Configuration.builder()
        .viewers(List.of(WITHOUT))
        .origin(new Location(WORLD, 0, 64, 0))
        .facing(BlockFace.SOUTH)
        .map(WALL_MAP)
        .columns(2)
        .rows(1)
        .video(video[0], video[1])
        .pageMap(PAGE_MAP)
        .maximumFrameRate(0)
        .build();
      final Mcv2Result result = new Mcv2Result(
        wide,
        new Mcv2Channel(wide, this.viewers, this.screen),
        recording,
        System::nanoTime,
        Runnable::run
      );
      result.applyFilter(Images.solid(video[0], video[1], FRAME_COLOR), this.metadata);
      result.release();
    }

    final List<String> expected = List.of("256x128", "256x128");
    assertEquals(expected, dithered, "a wall of 2x1 maps is 256x128 pixels");
  }

  private byte[] recordDitheredDimensions(final InvocationOnMock invocation, final List<String> dithered) {
    final ImageBuffer image = invocation.getArgument(0);
    dithered.add("%sx%s".formatted(image.getWidth(), image.getHeight()));

    return new byte[image.getWidth() * image.getHeight()];
  }

  @Test
  void shouldStopTheScreenInsteadOfLeavingItsEncoderWaitingForeverWhenAFrameListenerFails() throws InterruptedException {
    final Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());
    final Mcv2Result result = this.result(this.configuration, this.algorithm);
    result.setFrameListener(this::failFrameListener);
    try (final LogCapture logs = LogCapture.capture(Mcv2Result.class)) {
      result.start();
      result.applyFilter(Images.solid(64, 32, FRAME_COLOR), this.metadata);
      this.server.runTasks();
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      for (int shade = 0; this.hasPipelineThreads(before) && System.nanoTime() < deadline; shade++) {
        result.applyFilter(Images.solid(64, 32, FRAME_COLOR + shade), this.metadata);
        Thread.sleep(20);
      }

      final Set<Thread> senderThreads = threads(SENDER_THREAD_NAME, before);
      assertEquals(Set.of(), senderThreads, "the listener's failure ends the sender");

      final Set<Thread> screenThreads = threads(SCREEN_THREAD_NAME, before);
      assertEquals(Set.of(), screenThreads, "and the screen's thread, instead of waiting for it");

      assertTimeoutPreemptively(Duration.ofSeconds(5), result::drain, "a stopped screen has nothing to drain");

      final boolean errorLogged = logs
        .getEvents()
        .stream()
        .anyMatch(event -> Level.ERROR.equals(event.getLevel()));
      assertTrue(errorLogged, FAILURE_LOGGED_MESSAGE);
    }

    result.release();
  }

  private void failFrameListener(final byte[] frame) {
    final String message = "a listener that fails!";
    throw new IllegalStateException(message);
  }

  @Test
  void shouldStopTheScreenAndItsSenderWhenAnEncoderFails() throws InterruptedException {
    final Set<Thread> before = Set.copyOf(Thread.getAllStackTraces().keySet());
    final Mcv2 failing = mock(Mcv2.class);
    when(failing.getSettings()).thenReturn(Settings.FAST);
    when(failing.begin(any(), anyInt(), anyInt(), anyLong())).thenThrow(new IllegalArgumentException("a size the codec cannot carry!"));
    final Mcv2Configuration fast = this.packScreen(Settings.FAST);
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
      final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
      result.applyFilter(frame, this.metadata);
      this.server.runTasks();
      result.applyFilter(frame, this.metadata);
      verify(failing, timeout(TimeUnit.SECONDS.toMillis(10))).begin(any(), anyInt(), anyInt(), anyLong());

      this.awaitPipelineEnd(before);
      final Set<Thread> screenThreads = threads(SCREEN_THREAD_NAME, before);
      assertEquals(Set.of(), screenThreads, "the encoder's failure ends the screen's thread");

      final Set<Thread> senderThreads = threads(SENDER_THREAD_NAME, before);
      assertEquals(Set.of(), senderThreads, "and the sender, which waits for its frames");

      final boolean errorLogged = logs
        .getEvents()
        .stream()
        .anyMatch(event -> Level.ERROR.equals(event.getLevel()));
      assertTrue(errorLogged, FAILURE_LOGGED_MESSAGE);
    }

    result.release();
  }

  private void awaitWaiting(final Thread thread) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }

    final Thread.State state = thread.getState();
    assertEquals(Thread.State.WAITING, state);
  }

  private void awaitUninterruptibly(final CountDownLatch latch) {
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
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  private Thread taker(final Mcv2Result result, final AtomicReference<Mcv2Result.Arrival> taken) {
    return new Thread(() -> this.takeFrame(result, taken));
  }

  private void takeFrame(final Mcv2Result result, final AtomicReference<Mcv2Result.Arrival> taken) {
    try {
      taken.set(result.take());
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  @Test
  void shouldWakeAThreadThatWaitsForAFrameOnRelease() throws InterruptedException {
    final Mcv2Result result = this.result(this.configuration, null);
    result.start();
    final AtomicReference<Mcv2Result.Arrival> taken = new AtomicReference<>(
      new Mcv2Result.Arrival(new byte[0], 0, 0, 0, Mcv2Pacer.Preset.ONLY)
    );
    final Thread waiter = this.taker(result, taken);
    try {
      waiter.start();
      this.awaitWaiting(waiter);
      result.release();
      waiter.join(TimeUnit.SECONDS.toMillis(5));
      final boolean waiterAlive = waiter.isAlive();
      assertFalse(waiterAlive, "the release wakes a thread that waits for a frame");

      final Mcv2Result.@Nullable Arrival handed = taken.get();
      assertNull(handed, "and hands it none");
    } finally {
      this.releaseAndStopWaiter(result, waiter);
    }
  }

  @Test
  void shouldStillStopTheWaiterWhenTheReleaseNotificationAssertionFails() throws InterruptedException {
    final CountDownLatch neverNotified = new CountDownLatch(1);
    final AtomicReference<Thread> waiting = new AtomicReference<>();
    try (
      final MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class, (result, _) ->
        this.blockTake(result, waiting, neverNotified)
      )
    ) {
      final AssertionError failure = assertThrows(AssertionError.class, this::shouldWakeAThreadThatWaitsForAFrameOnRelease);
      final boolean namesTheRelease = failure.getMessage().contains("the release wakes a thread");
      assertTrue(namesTheRelease);
      final int resultCount = results.constructed().size();
      assertEquals(1, resultCount);
      final boolean waiterLeaked = waiting.get().isAlive();
      assertFalse(waiterLeaked, "a failed release-notification test leaked its waiter");
    } finally {
      final Thread waiter = waiting.get();
      if (waiter != null) {
        waiter.interrupt();
        waiter.join(WAIT_TIMEOUT_MILLISECONDS);
        final boolean waiterAlive = waiter.isAlive();
        assertFalse(waiterAlive, WAITER_STOPPED_MESSAGE);
      }
    }
  }

  private void blockTake(final Mcv2Result result, final AtomicReference<Thread> waiting, final CountDownLatch neverNotified)
    throws InterruptedException {
    when(result.take()).thenAnswer(_ -> this.waitUnnotified(waiting, neverNotified));
  }

  private @Nullable Object waitUnnotified(final AtomicReference<Thread> waiting, final CountDownLatch neverNotified)
    throws InterruptedException {
    waiting.set(Thread.currentThread());
    neverNotified.await();
    return null;
  }

  @Test
  void shouldStillStopTheFixtureWaiterWhenTheReleaseFails() throws InterruptedException {
    final CountDownLatch neverNotified = new CountDownLatch(1);
    final AtomicReference<Thread> waiting = new AtomicReference<>();
    final AssertionError forcedFailure = new AssertionError("forced release assertion!");
    try (
      final MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class, (result, _) ->
        this.blockTakeAndFailRelease(result, waiting, neverNotified, forcedFailure)
      )
    ) {
      final AssertionError failure = assertThrows(AssertionError.class, this::shouldWakeAThreadThatWaitsForAFrameOnRelease);
      assertSame(forcedFailure, failure);
      final int resultCount = results.constructed().size();
      assertEquals(1, resultCount);
      final boolean waiterLeaked = waiting.get().isAlive();
      assertFalse(waiterLeaked, "a release failure leaked the fixture waiter");
    } finally {
      final Thread waiter = waiting.get();
      if (waiter != null) {
        waiter.interrupt();
        waiter.join(WAIT_TIMEOUT_MILLISECONDS);
        final boolean waiterAlive = waiter.isAlive();
        assertFalse(waiterAlive, WAITER_STOPPED_MESSAGE);
      }
    }
  }

  private void blockTakeAndFailRelease(
    final Mcv2Result result,
    final AtomicReference<Thread> waiting,
    final CountDownLatch neverNotified,
    final AssertionError forcedFailure
  ) throws InterruptedException {
    this.blockTake(result, waiting, neverNotified);
    Mockito.doThrow(forcedFailure).doNothing().when(result).release();
  }

  @Test
  void shouldStopTheScreenWhenASenderFailsWhileItsThreadCannotBeInterrupted() throws InterruptedException {
    final Mcv2Configuration fast = this.packScreen(Settings.FAST);
    final CountDownLatch inSecondFrame = new CountDownLatch(1);
    final CountDownLatch secondFrameMayGoOn = new CountDownLatch(1);
    final CountDownLatch firstFrameMayFail = new CountDownLatch(1);
    final Mcv2 encoder = mock(Mcv2.class);
    when(encoder.getSettings()).thenReturn(Settings.FAST);
    final Mcv2.Pending first = mock(Mcv2.Pending.class);
    when(encoder.begin(any(), anyInt(), anyInt(), anyLong())).thenReturn(first);
    final AtomicInteger frames = new AtomicInteger();
    doAnswer(_ -> this.awaitSecondFrame(frames, inSecondFrame, secondFrameMayGoOn))
      .when(encoder)
      .setFrameLimit(anyInt());
    final Mcv2.Encoded encoded = this.completedFrame(Mcv2FrameKind.KEYFRAME);
    when(encoder.finish(first)).thenReturn(encoded);
    final Mcv2Channel original = new Mcv2Channel(fast, this.viewers, this.screen);
    final Mcv2Channel channel = spy(original);
    doAnswer(_ -> this.failFirstFrame(firstFrameMayFail))
      .when(channel)
      .send(any());
    final Mcv2Result result = new Mcv2Result(fast, channel, this.algorithm, System::nanoTime, Runnable::run, _ -> encoder);
    final AtomicReference<Mcv2Result.Arrival> taken = new AtomicReference<>(
      new Mcv2Result.Arrival(new byte[0], 0, 0, 0, Mcv2Pacer.Preset.ONLY)
    );
    final Thread waiter = this.taker(result, taken);
    try (final LogCapture logs = LogCapture.capture(Mcv2Result.class)) {
      result.start();
      final ImageBuffer frame = Images.solid(64, 32, FRAME_COLOR);
      result.applyFilter(frame, this.metadata);
      this.server.runTasks();
      // the first frame goes to the sender, which holds it; the screen's thread is stuck in the second frame
      result.applyFilter(frame, this.metadata);
      verify(channel, timeout(TimeUnit.SECONDS.toMillis(10))).send(any());

      result.applyFilter(frame, this.metadata);
      final boolean reachedSecondFrame = inSecondFrame.await(10, TimeUnit.SECONDS);
      assertTrue(reachedSecondFrame);

      waiter.start();
      this.awaitWaiting(waiter);
      firstFrameMayFail.countDown();
      waiter.join(TimeUnit.SECONDS.toMillis(5));
      final boolean waiterAlive = waiter.isAlive();
      assertFalse(waiterAlive, "the sender's failure stops the screen at once, not when its thread is free again");

      final Mcv2Result.@Nullable Arrival handed = taken.get();
      assertNull(handed);

      final boolean errorLogged = logs
        .getEvents()
        .stream()
        .anyMatch(event -> Level.ERROR.equals(event.getLevel()));
      assertTrue(errorLogged, FAILURE_LOGGED_MESSAGE);
      final boolean sendFailureLogged = logs
        .getEvents()
        .stream()
        .filter(event -> Level.ERROR.equals(event.getLevel()))
        .map(LogCapture.RecordedEvent::getMessage)
        .anyMatch("The MCV2 screen stops: sending a frame failed."::equals);
      assertTrue(sendFailureLogged, "the failure is logged as a failed send");
    } finally {
      firstFrameMayFail.countDown();
      secondFrameMayGoOn.countDown();
      this.releaseAndStopWaiter(result, waiter);
    }
  }

  private @Nullable Object awaitSecondFrame(
    final AtomicInteger frames,
    final CountDownLatch inSecondFrame,
    final CountDownLatch secondFrameMayGoOn
  ) {
    if (frames.getAndIncrement() == 1) {
      // the second frame's work ignores the interrupt that stops the screen, as native code does
      inSecondFrame.countDown();
      this.awaitUninterruptibly(secondFrameMayGoOn);
    }

    return null;
  }

  private OptionalInt failFirstFrame(final CountDownLatch firstFrameMayFail) throws InterruptedException {
    firstFrameMayFail.await();
    final String message = DISAGREEMENT_MESSAGE;
    throw new IllegalStateException(message);
  }

  private void releaseAndStopWaiter(final Mcv2Result result, final Thread waiter) throws InterruptedException {
    try {
      result.release();
    } finally {
      // Cleanup must not depend on the production notification that this fixture tests.
      waiter.interrupt();
      waiter.join(TimeUnit.SECONDS.toMillis(5));
      final boolean waiterAlive = waiter.isAlive();
      assertFalse(waiterAlive, "the fixture must stop its waiter even after an assertion fails");
    }
  }

  private enum FinishOutcome {
    ENCODES,
    FAILS,
  }
}
