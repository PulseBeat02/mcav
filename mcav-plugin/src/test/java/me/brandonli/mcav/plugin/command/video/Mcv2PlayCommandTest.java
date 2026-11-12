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
package me.brandonli.mcav.plugin.command.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Uninterruptibles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2FileEncoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.TestServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

final class Mcv2PlayCommandTest {

  private static final String SCREEN_SIZE = "5x3";
  private static final int MAP_ID = 20;

  private static final int TICKS = 2;
  private static final int LATER_TICKS = 3;
  private static final long PERIOD_TICKS = 2L;
  private static final long LATER_PERIOD_TICKS = 3L;
  private static final int FRAMES_PER_SECOND = 60;

  private static final int HAND_OVER_TIMEOUT_SECONDS = 10;
  private static final int JOIN_TIMEOUT_SECONDS = 60;
  private static final int WAIT_TIMEOUT_SECONDS = 10;

  private static final long LAST_REPORT_NANOSECONDS = 1_000L;
  private static final long PROGRESS_INTERVAL_NANOSECONDS = 30L;

  private static final int FRAME_COUNT = 3;
  private static final int SHORT_FRAME_COUNT = 2;
  private static final int LONG_FRAME_COUNT = 1000;
  private static final int COLOR_STEP = 40;

  private static final String ENCODE_DIMENSIONS = "16x16";
  private static final int ENCODE_SIDE = 16;

  private static final String VIDEO_FILE_NAME = "clip.mp4";
  private static final String STREAM_FILE_NAME = "clip.mcs";
  private static final String LIMIT_STREAM_FILE_NAME = "limit.mcs";
  private static final String STREAM_SUFFIX = ".mcs";
  private static final String INSIDE_FOLDER_NAME = "inside";
  private static final String LONG_VIDEO_FILE_NAME = "long.mp4";
  private static final String LONG_STREAM_FILE_NAME = "long.mcs";
  private static final String LONG_PART_FILE_NAME = "long.mcs.part";
  private static final String MISSING_STREAM_FILE_NAME = "missing.mcs";
  private static final String STREAM_PREFIX = "stream";
  private static final String HIGH_STREAM_PREFIX = "h";
  private static final String CLIP_PART_FILE_NAME = "clip.mcs.part";
  private static final String NEXT_VIDEO_FILE_NAME = "next.mp4";
  private static final String NEXT_STREAM_FILE_NAME = "next.mcs";
  private static final String PROGRESS_STREAM_FILE_NAME = "progress.mcs";
  private static final String VIDEO_A_FILE_NAME = "a.mp4";
  private static final String VIDEO_B_FILE_NAME = "b.mp4";
  private static final String VIDEO_C_FILE_NAME = "c.mp4";
  private static final String VIDEO_D_FILE_NAME = "d.mp4";
  private static final String VIDEO_E_FILE_NAME = "e.mp4";
  private static final String VIDEO_F_FILE_NAME = "f.mp4";
  private static final String MISSING_FOLDER_NAME = "missing";
  private static final String OUTPUT_STREAM_FILE_NAME = "out.mcs";
  private static final String BLOCKED_STREAM_FILE_NAME = "blocked.mcs";
  private static final String BLOCKED_PART_FILE_NAME = "blocked.mcs.part";
  private static final String LIMIT_PART_FILE_NAME = "limit.mcs.part";
  private static final String OLD_STREAM_FILE_NAME = "old.mcs";
  private static final String OLD_PART_FILE_NAME = "old.mcs.part";
  private static final String OTHER_VIDEO_FILE_NAME = "other.mp4";
  private static final String OTHER_STREAM_FILE_NAME = "other.mcs";
  private static final String AGAIN_VIDEO_FILE_NAME = "again.mp4";
  private static final String TAKEN_STREAM_FILE_NAME = "taken.mcs";
  private static final String TAKEN_PART_FILE_NAME = "taken.mcs.part";
  private static final String TIMED_STREAM_FILE_NAME = "timed.mcs";
  private static final String ESCAPE_STREAM_FILE_NAME = "escape.mcs";
  private static final String STREAM_A_FILE_NAME = "a.mcs";
  private static final String STREAM_B_FILE_NAME = "b.mcs";
  private static final String STREAM_C_FILE_NAME = "c.mcs";
  private static final String STREAM_D_FILE_NAME = "d.mcs";
  private static final String STREAM_E_FILE_NAME = "e.mcs";
  private static final String STREAM_F_FILE_NAME = "f.mcs";
  private static final String STREAM_G_FILE_NAME = "g.mcs";
  private static final String STREAM_I_FILE_NAME = "i.mcs";
  private static final String STREAM_J_FILE_NAME = "j.mcs";
  private static final String STREAM_K_FILE_NAME = "k.mcs";
  private static final String OVERSIZED_STREAM_FILE_NAME = "oversized.mcs";

  private static final String NOT_A_STREAM_FILE_REASON = "File missing.mcs is not a stream file!";
  private static final String ENCODE_FAILURE_PREFIX = "The MCV2 encode failed: ";

  private static final String REDUNDANT_STREAM_PATH = "sub/../clip.mcs";
  private static final String SUBFOLDER_STREAM_PATH = "sub/clip.mcs";
  private static final String PARENT_STREAM_PATH = "../clip.mcs";
  private static final String ESCAPING_STREAM_PATH = "sub/../../clip.mcs";
  private static final String ABSOLUTE_STREAM_PATH = "/etc/passwd";
  private static final String PARENT_PLAY_PATH = "../stream.mcs";
  private static final String ESCAPING_ENCODE_PATH = "../../escape.mcs";

  private static final String PROGRESS_PATTERN = "MCV2 encode: (\\d+) frames, (\\d+) ms per frame";
  private static final String DURATION_PATTERN = " in (\\d+) s, ";
  private static final String DONE_FIGURES_PATTERN = " s, (\\d+) ms per frame, (\\d+) ms of it encoding";

  private static final String MISSING_VIDEO_MESSAGE = "no such video!";

  private static final String REJECTED_SOURCE_MESSAGE = "reader rejected the source!";

  private static final String SENT_EVENT = "sent";
  private static final String CLOSED_EVENT = "closed";

  @TempDir
  private Path folder;

  private Path streams;

  private MCAVSandbox plugin;

  private Mcv2PackServer packs;

  private Mcv2PackServer.Lease lease;

  private Mcv2PlayCommand command;

  private MultiplePlayerSelector selector;

  private CommandSender sender;

  private BukkitTask task;

  private Player player;

  private final UUID viewer = UUID.randomUUID();

  private final BlockingQueue<Runnable> handedOver = new LinkedBlockingQueue<>();

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    this.plugin = mock(MCAVSandbox.class);
    when(this.plugin.getDataFolder()).thenReturn(this.folder.toFile());
    this.streams = this.folder.resolve(Mcv2PlayCommand.STREAM_FOLDER);
    this.packs = mock(Mcv2PackServer.class);
    when(this.packs.getViewers()).thenReturn(mock(Mcv2Viewers.class));
    this.lease = mock(Mcv2PackServer.Lease.class);
    final AtomicReference<Mcv2Configuration> opened = new AtomicReference<>();
    when(this.packs.open(any())).thenAnswer(invocation -> this.openLease(invocation, opened));
    when(this.lease.getConfiguration()).thenAnswer(_ -> opened.get());
    when(this.plugin.getMcv2Support()).thenReturn(new Mcv2Support(this.packs));
    when(this.plugin.getOnlinePlayers()).thenReturn(new OnlinePlayers());
    this.command = new Mcv2PlayCommand(this.plugin);
    this.player = mock(Player.class);
    when(this.player.getUniqueId()).thenReturn(this.viewer);
    this.selector = mock(MultiplePlayerSelector.class);
    when(this.selector.values()).thenReturn(List.of(this.player));
    this.sender = mock(CommandSender.class);
    final World world = mock(World.class);
    final ItemFrame frame = Mcv2SupportTest.frame(Mcv2SupportTest.map(Material.FILLED_MAP, true, MAP_ID));
    when(frame.getLocation()).thenReturn(new Location(world, 5, 64, 7));
    when(frame.getFacing()).thenReturn(BlockFace.SOUTH);
    when(world.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(frame));
    when(TestServer.getServer().getWorlds()).thenReturn(List.of(world));
    this.task = mock(BukkitTask.class);
    when(TestServer.getScheduler().runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenReturn(
      this.task
    );
    when(TestServer.getScheduler().runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(this::recordHandOver);
  }

  private Mcv2PackServer.Lease openLease(final InvocationOnMock invocation, final AtomicReference<Mcv2Configuration> opened) {
    opened.set(invocation.getArgument(0));

    return this.lease;
  }

  private @Nullable Object recordHandOver(final InvocationOnMock invocation) {
    this.handedOver.add(invocation.getArgument(1));

    return null;
  }

  @Test
  void shouldPlayAStreamOnTheWallUntilStopped() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, file.toString());
      this.runHandedOver();
      final Mcv2Channel channel = channels.constructed().getFirst();
      verify(channel).open();

      final ArgumentCaptor<Mcv2Configuration> configurations = ArgumentCaptor.forClass(Mcv2Configuration.class);
      verify(this.packs).open(configurations.capture());

      final int videoWidth = configurations.getValue().getVideoWidth();
      assertEquals(32, videoWidth);

      final int videoHeight = configurations.getValue().getVideoHeight();
      assertEquals(32, videoHeight);

      final List<UUID> copy = List.copyOf(configurations.getValue().getViewers());
      final List<UUID> expectedViewers = List.of(this.viewer);
      assertEquals(expectedViewers, copy);

      final ArgumentCaptor<Runnable> playbacks = ArgumentCaptor.forClass(Runnable.class);
      verify(TestServer.getScheduler()).runTaskTimerAsynchronously(eq(this.plugin), playbacks.capture(), eq(0L), eq(PERIOD_TICKS));

      final Runnable value = playbacks.getValue();
      assertInstanceOf(Mcv2Playback.class, value);

      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());

      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, file.toString());
      this.runHandedOver();
      verify(this.task).cancel();
      verify(channel).close();
      verify(this.lease).close();

      this.command.stop(this.sender);
      verify(channels.constructed().getLast()).close();
      verify(this.sender).sendMessage(Message.MCV2_STOP.build());
      verify(this.lease, Mockito.times(2)).close();
      verify(this.packs, never()).shutdown();
    }
  }

  // the test thread is the main thread
  private void runHandedOver() throws InterruptedException {
    final Runnable handed = this.handedOver.poll(HAND_OVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertNotNull(handed, "the command handed nothing over to the main thread");

    handed.run();
  }

  private Path archive(final List<byte[]> frames) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (final byte[] frame : frames) {
      output.write(new byte[] {
        (byte) frame.length,
        (byte) (frame.length >> 8),
        (byte) (frame.length >> 16),
        (byte) (frame.length >> 24),
      });
      output.write(frame);
    }
    Files.createDirectories(this.streams);
    final Path file = Files.createTempFile(this.streams, STREAM_PREFIX, STREAM_SUFFIX);
    Files.write(file, output.toByteArray());
    return file;
  }

  @Test
  void shouldReadTheStreamFileOffTheMainThreadAndOpenTheScreenOnIt() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    final List<Boolean> fromMainThread = new CopyOnWriteArrayList<>();
    when(TestServer.getScheduler().runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation ->
      this.recordThreadAndHandOver(invocation, fromMainThread)
    );
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, file.toString());
      verify(this.packs, never()).open(any());
      verify(this.sender, never()).sendMessage(any(Component.class));

      this.runHandedOver();
      final List<Boolean> expectedThreads = List.of(false);
      assertEquals(expectedThreads, fromMainThread, "the file is read on a thread of its own, which hands the screen over");
      verify(channels.constructed().getFirst()).open();
      verify(this.packs).open(any());
      verify(TestServer.getScheduler()).runTaskTimerAsynchronously(eq(this.plugin), any(Runnable.class), eq(0L), eq(PERIOD_TICKS));
      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());
    }
  }

  private @Nullable Object recordThreadAndHandOver(final InvocationOnMock invocation, final List<Boolean> fromMainThread) {
    fromMainThread.add(Bukkit.isPrimaryThread());
    this.handedOver.add(invocation.getArgument(1));

    return null;
  }

  @Test
  void shouldReplaceAStreamWhoseFileIsStillBeingReadWithOneAskedForLater() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, file.toString());
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, LATER_TICKS, file.toString());
      this.runHandedOver();
      this.runHandedOver();

      final int size = channels.constructed().size();
      assertEquals(1, size);

      verify(this.packs).open(any());
      verify(TestServer.getScheduler()).runTaskTimerAsynchronously(eq(this.plugin), any(Runnable.class), eq(0L), eq(LATER_PERIOD_TICKS));
      verify(TestServer.getScheduler(), never()).runTaskTimerAsynchronously(
        any(Plugin.class),
        any(Runnable.class),
        anyLong(),
        eq(PERIOD_TICKS)
      );
      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());
    }
  }

  @Test
  void shouldReplaceAStreamWhoseFileIsStillBeingReadOnAStop() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, this.streams.resolve(MISSING_STREAM_FILE_NAME).toString());
      this.command.stop(this.sender);
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, LATER_TICKS, file.toString());
      this.runHandedOver();
      this.runHandedOver();
      verify(this.sender, Mockito.times(2)).sendMessage(any(Component.class));
      verify(this.sender).sendMessage(Message.MCV2_STOP.build());
      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());

      final int size = channels.constructed().size();
      assertEquals(1, size);

      verify(TestServer.getScheduler()).runTaskTimerAsynchronously(eq(this.plugin), any(Runnable.class), eq(0L), eq(LATER_PERIOD_TICKS));
    }
  }

  @Test
  void shouldNotOpenAStreamReadAsThePluginStops() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, file.toString());
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, LATER_TICKS, file.toString());
      // both files are read, and the main thread has not yet opened either, when the plugin stops
      final Runnable first = this.handedOver.poll(HAND_OVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      final Runnable second = this.handedOver.poll(HAND_OVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertNotNull(first);
      assertNotNull(second);

      this.command.shutdown();
      first.run();
      second.run();

      final List<Mcv2Channel> constructed = channels.constructed();
      assertEquals(List.of(), constructed);

      verify(this.packs, never()).open(any());
      verify(this.sender, never()).sendMessage(any(Component.class));
    }
  }

  @Test
  void shouldStreamAtAFrameRateOffTheServerTick() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (
      final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class, (channel, context) ->
        this.addRecipient(channel)
      )
    ) {
      this.command.stream(this.sender, this.selector, SCREEN_SIZE, MAP_ID, 100, file.toString());
      this.runHandedOver();
      final Mcv2Channel channel = channels.constructed().getFirst();
      verify(channel).open();
      // 100 frames a second from the stream's own thread, not from the server's scheduler
      verify(channel, Mockito.timeout(5000).atLeast(10)).send(any());
      verify(TestServer.getScheduler(), never()).runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong());
      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());

      this.command.stop(this.sender);
      verify(channel).close();

      Mockito.clearInvocations(channel);
      Thread.sleep(100);
      verify(channel, never()).send(any());

      this.command.stream(
        this.sender,
        this.selector,
        SCREEN_SIZE,
        MAP_ID,
        FRAMES_PER_SECOND,
        this.streams.resolve(MISSING_STREAM_FILE_NAME).toString()
      );
      this.runHandedOver();
      verify(this.sender, Mockito.times(3)).sendMessage(any(Component.class));
      verify(this.sender).sendMessage(Message.MCV2_FILE_ERROR.build(NOT_A_STREAM_FILE_REASON));
    }
  }

  private void addRecipient(final Mcv2Channel channel) {
    when(channel.getRecipients()).thenReturn(Set.of(UUID.randomUUID()));
  }

  @Test
  void shouldSendNoFrameFromARunThatStartsAfterAStop() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (
      final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class, (channel, context) ->
        this.addRecipient(channel)
      )
    ) {
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, file.toString());
      this.runHandedOver();
      final Runnable playback = this.capturePlayback();
      this.command.stop(this.sender);
      // the scheduler handed this run to its thread before the cancel
      playback.run();

      final Mcv2Channel channel = channels.constructed().getFirst();
      verify(channel, never()).update();
      verify(channel, never()).send(any());
    }
  }

  private Runnable capturePlayback() {
    final ArgumentCaptor<Runnable> playbacks = ArgumentCaptor.forClass(Runnable.class);
    verify(TestServer.getScheduler()).runTaskTimerAsynchronously(eq(this.plugin), playbacks.capture(), eq(0L), eq(PERIOD_TICKS));

    return playbacks.getValue();
  }

  @Test
  void shouldFinishTheFrameBeingSentBeforeAStopClosesTheScreen() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    final CountDownLatch sending = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final List<String> events = new CopyOnWriteArrayList<>();
    try (
      final MockedConstruction<Mcv2Channel> _ = Mockito.mockConstruction(Mcv2Channel.class, (channel, _) ->
        this.holdFrames(channel, sending, release, events)
      )
    ) {
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, file.toString());
      this.runHandedOver();
      final Runnable playback = this.capturePlayback();
      final Thread task = Thread.ofPlatform().daemon().start(playback);
      final boolean started = sending.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(started, "the playback sent no frame");

      // the stop waits for the frame, so it runs beside the test thread
      final Thread stopping = Thread.ofPlatform()
        .daemon()
        .start(() -> this.command.stop(this.sender));
      this.awaitBlocked(stopping);
      release.countDown();
      stopping.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));
      task.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));

      final List<String> order = List.copyOf(events);
      final List<String> expected = List.of(SENT_EVENT, CLOSED_EVENT);
      assertEquals(expected, order);
    }
  }

  private void holdFrames(
    final Mcv2Channel channel,
    final CountDownLatch sending,
    final CountDownLatch release,
    final List<String> events
  ) {
    this.addRecipient(channel);
    when(channel.send(any())).thenAnswer(_ -> this.sendOnceReleased(sending, release, events));
    doAnswer(_ -> events.add(CLOSED_EVENT))
      .when(channel)
      .close();
  }

  private OptionalInt sendOnceReleased(final CountDownLatch sending, final CountDownLatch release, final List<String> events) {
    sending.countDown();
    Uninterruptibles.awaitUninterruptibly(release);
    events.add(SENT_EVENT);

    return OptionalInt.empty();
  }

  private void awaitBlocked(final Thread thread) {
    final long start = System.nanoTime();
    final long deadline = start + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS);
    Thread.State state = thread.getState();
    while (state != Thread.State.BLOCKED) {
      final long now = System.nanoTime();
      assertTrue(now < deadline, "the stop never waited for the frame being sent");

      Thread.onSpinWait();
      state = thread.getState();
    }
  }

  @Test
  void shouldPlayNothingWhenEverySlotOfThePackPlays() throws IOException, InterruptedException {
    when(this.packs.open(any())).thenThrow(new IllegalStateException("full!"));
    this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, this.archive(Mcv2PlaybackTest.stream()).toString());
    this.runHandedOver();
    verify(this.sender).sendMessage(Message.MCV2_FULL.build());
    verify(TestServer.getScheduler(), never()).runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong());
  }

  @Test
  void shouldRefuseWhatItCannotPlay() throws IOException, InterruptedException {
    this.command.play(this.sender, this.selector, "65x1", MAP_ID, TICKS, "anything");

    final boolean empty = this.handedOver.isEmpty();
    assertTrue(empty);

    this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, this.streams.resolve(MISSING_STREAM_FILE_NAME).toString());
    this.runHandedOver();
    this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, this.archive(List.of(new byte[48])).toString());
    this.runHandedOver();
    // the wall size, the missing file and the frame that is not MCV2 are each reported
    verify(this.sender, Mockito.times(3)).sendMessage(any(Component.class));

    final ArgumentCaptor<Component> messages = ArgumentCaptor.captor();
    verify(this.sender, Mockito.times(3)).sendMessage(messages.capture());

    final List<Component> expected = List.of(
      Message.UNSUPPORTED_DIMENSION.build(),
      Message.MCV2_FILE_ERROR.build(NOT_A_STREAM_FILE_REASON),
      Message.MCV2_FILE_ERROR.build("Not an MCV2 frame!")
    );
    final List<Component> allValues = messages.getAllValues();
    assertEquals(expected, allValues);

    this.command.play(this.sender, this.selector, SCREEN_SIZE, 21, TICKS, this.archive(Mcv2PlaybackTest.stream()).toString());
    this.runHandedOver();
    verify(this.sender).sendMessage(Message.MCV2_SCREEN_ERROR.build(21));
    verify(this.packs, never()).open(any());
  }

  @Test
  void shouldEncodeAVideoFileAheadOfTimeOnItsOwnThread() throws Exception {
    final Path output = this.streams.resolve(STREAM_FILE_NAME);
    final SolidFrames frames = new SolidFrames(FRAME_COUNT, new CountDownLatch(0));
    final List<String> opened = new ArrayList<>();
    this.command.setOpener((video, width, height) -> this.recordOpening(video, width, height, opened, frames));
    this.command.encode(this.sender, VIDEO_FILE_NAME, STREAM_FILE_NAME, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);
    final List<String> told = this.finish();
    final List<String> expectedOpened = List.of("clip.mp4 16x16");
    assertEquals(expectedOpened, opened);

    final int size = told.size();
    assertEquals(2, size);

    final int threads = Pool.shared().getThreads();

    final String expected = this.text(
      Message.MCV2_ENCODE_START.build("clip.mp4 into %s at 16x16 with the default preset, on %s encoder threads".formatted(output, threads))
    );
    final String toldTold = told.getFirst();
    assertEquals(expected, toldTold);

    final boolean prefixed = told.get(1).startsWith("MCV2 encode finished: 3 frames (1 keyframes) into %s".formatted(output));
    assertTrue(prefixed, told.get(1));
    assertTrue(frames.closed);

    final int mcv2PlayCommandSize = this.command.read(output).size();
    assertEquals(FRAME_COUNT, mcv2PlayCommandSize);

    final boolean exists = Files.exists(this.streams.resolve(CLIP_PART_FILE_NAME));
    assertFalse(exists);

    this.command.setOpener((_, _, _) -> new SolidFrames(1, new CountDownLatch(0)));
    this.command.encode(this.sender, NEXT_VIDEO_FILE_NAME, NEXT_STREAM_FILE_NAME, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);
    this.finish();
    this.command.cancel(this.sender);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_NONE.build());

    final int secondSize = this.command.read(this.streams.resolve(NEXT_STREAM_FILE_NAME)).size();
    assertEquals(1, secondSize);
  }

  private Mcv2FileEncoder.FrameReader recordOpening(
    final Path video,
    final int width,
    final int height,
    final List<String> opened,
    final SolidFrames frames
  ) {
    opened.add("%s %sx%s".formatted(video, width, height));

    return frames;
  }

  private String text(final Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  private List<String> finish() throws InterruptedException {
    final Thread thread = this.command.getEncodingThread();
    if (thread != null) {
      thread.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));

      final boolean alive = thread.isAlive();
      assertFalse(alive);
    }
    final ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
    verify(this.sender, Mockito.atLeast(0)).sendMessage(messages.capture());

    return messages.getAllValues().stream().map(this::text).toList();
  }

  @Test
  void shouldTellTheProgressAndTheFailures() throws Exception {
    final Path output = this.folder.resolve(PROGRESS_STREAM_FILE_NAME);
    this.command.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(SHORT_FRAME_COUNT, new CountDownLatch(0)),
      Path.of(VIDEO_A_FILE_NAME),
      output,
      ENCODE_SIDE,
      ENCODE_SIDE,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    final List<String> told = this.finish();

    final int size = told.size();
    assertEquals(3, size);

    final boolean prefixed = told.getFirst().startsWith("MCV2 encode: 1 frames, ");
    assertTrue(prefixed, told.getFirst());

    final boolean toldPrefixed = told.get(1).startsWith("MCV2 encode: 2 frames, ");
    assertTrue(toldPrefixed, told.get(1));

    Mockito.clearInvocations(this.sender);
    this.command.encodeFile(
      this.sender,
      (_, _, _) -> this.throwMissingVideo(),
      Path.of(VIDEO_B_FILE_NAME),
      output,
      ENCODE_SIDE,
      ENCODE_SIDE,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    this.command.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(1, new CountDownLatch(0)),
      Path.of(VIDEO_C_FILE_NAME),
      this.folder.resolve(MISSING_FOLDER_NAME).resolve(OUTPUT_STREAM_FILE_NAME),
      ENCODE_SIDE,
      ENCODE_SIDE,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    final Path blocked = this.folder.resolve(BLOCKED_STREAM_FILE_NAME);
    Files.createDirectories(this.folder.resolve(BLOCKED_PART_FILE_NAME).resolve(INSIDE_FOLDER_NAME));
    this.command.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(1, new CountDownLatch(0)),
      Path.of(VIDEO_D_FILE_NAME),
      blocked,
      ENCODE_SIDE,
      ENCODE_SIDE,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    final List<String> failures = this.finish();

    final String expected = this.text(Message.MCV2_ENCODE_ERROR.build(MISSING_VIDEO_MESSAGE));
    final String failure = failures.getFirst();
    assertEquals(expected, failure);

    final boolean failuresPrefixed = failures.get(1).startsWith(ENCODE_FAILURE_PREFIX);
    assertTrue(failuresPrefixed, failures.get(1));

    final boolean secondPrefixed = failures.get(2).startsWith(ENCODE_FAILURE_PREFIX);
    assertTrue(secondPrefixed, failures.get(2));

    final int failuresSize = failures.size();
    assertEquals(3, failuresSize);

    final boolean directory = Files.isDirectory(this.folder.resolve(BLOCKED_PART_FILE_NAME));
    assertTrue(directory);
  }

  private Mcv2FileEncoder.FrameReader throwMissingVideo() throws IOException {
    final String message = MISSING_VIDEO_MESSAGE;
    throw new IOException(message);
  }

  @Test
  void shouldTellTheProgressOnceAWholeIntervalHasPassed() {
    final long now = LAST_REPORT_NANOSECONDS + PROGRESS_INTERVAL_NANOSECONDS;
    final boolean due = this.command.isProgressDue(now, LAST_REPORT_NANOSECONDS, PROGRESS_INTERVAL_NANOSECONDS);
    assertTrue(due);
  }

  @Test
  void shouldNotTellTheProgressBeforeAWholeIntervalHasPassed() {
    final long now = LAST_REPORT_NANOSECONDS + PROGRESS_INTERVAL_NANOSECONDS - 1;
    final boolean due = this.command.isProgressDue(now, LAST_REPORT_NANOSECONDS, PROGRESS_INTERVAL_NANOSECONDS);
    assertFalse(due);
  }

  @ParameterizedTest
  @ValueSource(strings = { "4097x1", "1x4097" })
  void shouldRefuseOfflineDimensionsBeyondTheCodecLimit(final String dimensions) throws Exception {
    final Mcv2PlayCommand.Opener opener = mock(Mcv2PlayCommand.Opener.class);
    when(opener.open(any(), anyInt(), anyInt())).thenThrow(new IOException("unexpected open!"));
    this.command.setOpener(opener);
    try {
      this.command.encode(this.sender, VIDEO_FILE_NAME, LIMIT_STREAM_FILE_NAME, dimensions, Mcv2Profile.DEFAULT);

      final Thread encoding = this.command.getEncodingThread();
      assertNull(encoding, "invalid codec dimensions cannot start a worker");

      Mockito.verifyNoInteractions(opener);
      verify(this.sender).sendMessage(Message.UNSUPPORTED_DIMENSION.build());

      final boolean exists = Files.exists(this.streams.resolve(LIMIT_PART_FILE_NAME));
      assertFalse(exists);
    } finally {
      this.finish();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "4096x1", "1x4096" })
  void shouldAcceptOfflineDimensionsAtTheCodecLimit(final String dimensions) throws Exception {
    final Mcv2FileEncoder.FrameReader frames = mock(Mcv2FileEncoder.FrameReader.class);
    when(frames.read(any(byte[].class))).thenReturn(true, false);
    this.command.setOpener((_, _, _) -> frames);
    this.command.encode(this.sender, VIDEO_FILE_NAME, LIMIT_STREAM_FILE_NAME, dimensions, Mcv2Profile.DEFAULT);
    this.finish();

    final int size = this.command.read(this.streams.resolve(LIMIT_STREAM_FILE_NAME)).size();
    assertEquals(1, size);

    verify(frames).close();
  }

  @Test
  void shouldReportRecoverableEncodeFailuresAndRemoveOnlyItsPartialOutput() throws Exception {
    final Path target = this.folder.resolve(OLD_STREAM_FILE_NAME);
    final byte[] previous = { 1, 2, 3 };
    Files.write(target, previous);
    assertDoesNotThrow(() -> this.encodeRejectedSource(target));

    final boolean exists = Files.exists(this.folder.resolve(OLD_PART_FILE_NAME));
    assertFalse(exists);

    final byte[] allBytes = Files.readAllBytes(target);
    assertArrayEquals(previous, allBytes);

    verify(this.sender).sendMessage(Message.MCV2_ENCODE_ERROR.build(REJECTED_SOURCE_MESSAGE));
  }

  private void encodeRejectedSource(final Path target) {
    this.command.encodeFile(
      this.sender,
      (_, _, _) -> this.throwRejectedSource(),
      Path.of(VIDEO_FILE_NAME),
      target,
      ENCODE_SIDE,
      ENCODE_SIDE,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
  }

  private Mcv2FileEncoder.FrameReader throwRejectedSource() {
    final String message = REJECTED_SOURCE_MESSAGE;
    throw new IllegalStateException(message);
  }

  @Test
  void shouldRunOneEncodeAtATimeAndStopItWhenAsked() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final SolidFrames frames = new SolidFrames(LONG_FRAME_COUNT, release);
    this.command.setOpener((_, _, _) -> frames);
    final Path output = this.streams.resolve(LONG_STREAM_FILE_NAME);
    this.command.encode(this.sender, LONG_VIDEO_FILE_NAME, LONG_STREAM_FILE_NAME, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);
    final Thread thread = this.command.getEncodingThread();
    this.command.encode(this.sender, OTHER_VIDEO_FILE_NAME, OTHER_STREAM_FILE_NAME, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_BUSY.build());

    this.command.cancel(this.sender);

    final Thread encoding = this.command.getEncodingThread();
    assertSame(thread, encoding, "the cancelled encode is the running one until its thread ends");

    Objects.requireNonNull(thread).join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));

    final boolean alive = thread.isAlive();
    assertFalse(alive);

    verify(this.sender).sendMessage(Message.MCV2_ENCODE_CANCELLED.build());
    assertTrue(frames.closed);

    final boolean exists = Files.exists(output);
    assertFalse(exists);

    final boolean filesExists = Files.exists(this.streams.resolve(LONG_PART_FILE_NAME));
    assertFalse(filesExists);

    this.command.cancel(this.sender);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_NONE.build());

    this.command.encode(this.sender, VIDEO_FILE_NAME, LONG_STREAM_FILE_NAME, "big", Mcv2Profile.DEFAULT);

    final Thread commandEncoding = this.command.getEncodingThread();
    assertSame(thread, commandEncoding);
  }

  @Test
  void shouldStopTheEncodeOnACancelEvenWhenItsVideoDropsTheInterrupt() throws Exception {
    final CountDownLatch cancelled = new CountDownLatch(1);
    final DropsInterrupts frames = new DropsInterrupts(cancelled);
    this.command.setOpener((_, _, _) -> frames);
    this.command.encode(this.sender, LONG_VIDEO_FILE_NAME, LONG_STREAM_FILE_NAME, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);
    final Thread thread = Objects.requireNonNull(this.command.getEncodingThread());
    this.command.cancel(this.sender);
    cancelled.countDown();
    thread.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));

    final boolean alive = thread.isAlive();
    assertFalse(alive);
    assertTrue(frames.dropped, "the video dropped the cancel's interrupt");
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_CANCELLED.build());

    final boolean exists = Files.exists(this.streams.resolve(LONG_STREAM_FILE_NAME));
    assertFalse(exists);

    final boolean filesExists = Files.exists(this.streams.resolve(LONG_PART_FILE_NAME));
    assertFalse(filesExists);
  }

  @Test
  void shouldStopTheEncodeOnACancelEvenWhenOpeningTheVideoDropsTheInterrupt() throws Exception {
    final CountDownLatch cancelled = new CountDownLatch(1);
    final AtomicBoolean dropped = new AtomicBoolean();
    this.command.setOpener((_, _, _) -> this.openDroppingTheInterrupt(cancelled, dropped));
    this.command.encode(this.sender, LONG_VIDEO_FILE_NAME, LONG_STREAM_FILE_NAME, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);
    final Thread thread = Objects.requireNonNull(this.command.getEncodingThread());
    this.command.cancel(this.sender);
    cancelled.countDown();
    thread.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));

    final boolean alive = thread.isAlive();
    assertFalse(alive, "the encode waits for a frame though it was cancelled");

    final boolean droppedDropped = dropped.get();
    assertTrue(droppedDropped, "opening the video dropped the cancel's interrupt");

    verify(this.sender).sendMessage(Message.MCV2_ENCODE_CANCELLED.build());

    final boolean exists = Files.exists(this.streams.resolve(LONG_PART_FILE_NAME));
    assertFalse(exists);
  }

  private Mcv2FileEncoder.FrameReader openDroppingTheInterrupt(final CountDownLatch cancelled, final AtomicBoolean dropped) {
    Uninterruptibles.awaitUninterruptibly(cancelled);
    dropped.set(Thread.interrupted());

    return new WaitsForInterrupt();
  }

  @Test
  void shouldSayThereIsNoEncodeOnACancelBeforeAnyEncode() {
    this.command.cancel(this.sender);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_NONE.build());

    final Thread encoding = this.command.getEncodingThread();
    assertNull(encoding);
  }

  @Test
  void shouldLetANewEncodeWaitUntilACancelledOneHasEnded() throws Exception {
    final CountDownLatch mayClose = new CountDownLatch(1);
    this.command.setOpener((_, _, _) -> new SlowToClose(mayClose));
    this.command.encode(this.sender, LONG_VIDEO_FILE_NAME, LONG_STREAM_FILE_NAME, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);
    final Thread cancelled = Objects.requireNonNull(this.command.getEncodingThread());
    this.command.cancel(this.sender);
    final CountDownLatch released = new CountDownLatch(0);
    this.command.setOpener((_, _, _) -> new SolidFrames(SHORT_FRAME_COUNT, released));
    this.command.encode(this.sender, AGAIN_VIDEO_FILE_NAME, LONG_STREAM_FILE_NAME, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_BUSY.build());

    final Thread encoding = this.command.getEncodingThread();
    assertSame(cancelled, encoding);

    mayClose.countDown();
    cancelled.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));

    final boolean alive = cancelled.isAlive();
    assertFalse(alive);

    this.command.encode(this.sender, AGAIN_VIDEO_FILE_NAME, LONG_STREAM_FILE_NAME, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);
    final Thread again = Objects.requireNonNull(this.command.getEncodingThread());
    again.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));

    final boolean exists = Files.exists(this.streams.resolve(LONG_STREAM_FILE_NAME));
    assertTrue(exists);

    final boolean filesExists = Files.exists(this.streams.resolve(LONG_PART_FILE_NAME));
    assertFalse(filesExists);
  }

  @Test
  void shouldStopTheStreamWhenThePluginStops() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, file.toString());
      this.runHandedOver();
      this.command.shutdown();
      verify(this.task).cancel();
      verify(channels.constructed().getFirst()).close();
    }
  }

  @Test
  void shouldRemoveTheUnfinishedFileOfAFailedEncode() throws Exception {
    final Path target = this.folder.resolve(TAKEN_STREAM_FILE_NAME);
    Files.createDirectories(target.resolve(INSIDE_FOLDER_NAME));
    this.command.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(1, new CountDownLatch(0)),
      Path.of(VIDEO_E_FILE_NAME),
      target,
      ENCODE_SIDE,
      ENCODE_SIDE,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );

    final boolean prefixed = this.finish().getLast().startsWith(ENCODE_FAILURE_PREFIX);
    assertTrue(prefixed);

    final boolean exists = Files.exists(this.folder.resolve(TAKEN_PART_FILE_NAME));
    assertFalse(exists);
  }

  @Test
  void shouldReplaceAnExistingStreamFileWhenTheEncodeEnds() throws Exception {
    final Path target = this.folder.resolve(OLD_STREAM_FILE_NAME);
    Files.write(target, new byte[] { 1, 2, 3 });
    this.command.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(FRAME_COUNT, new CountDownLatch(0)),
      Path.of(VIDEO_FILE_NAME),
      target,
      ENCODE_SIDE,
      ENCODE_SIDE,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );

    final int size = this.command.read(target).size();
    assertEquals(FRAME_COUNT, size);

    final boolean exists = Files.exists(this.folder.resolve(OLD_PART_FILE_NAME));
    assertFalse(exists);
  }

  @Test
  void shouldTellTimesThatTheEncodeCouldHaveTaken() throws Exception {
    final Path output = this.folder.resolve(TIMED_STREAM_FILE_NAME);
    final AtomicLong openingNanoseconds = new AtomicLong();
    final long started = System.nanoTime();
    this.command.encodeFile(
      this.sender,
      (_, _, _) -> this.openSlowly(openingNanoseconds),
      Path.of(VIDEO_F_FILE_NAME),
      output,
      320,
      180,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    final double took = (System.nanoTime() - started) / 1e6;
    final List<String> told = this.finish();
    final double openingMilliseconds = openingNanoseconds.get() / 1e6;
    assertTrue(openingMilliseconds >= 600, "opening consumed independently measured time");

    final Matcher progress = Pattern.compile(PROGRESS_PATTERN).matcher(told.get(2));

    final boolean found = progress.find();
    assertTrue(found, told.get(2));

    final int frames = Integer.parseInt(progress.group(1));
    assertEquals(FRAME_COUNT, frames);

    final boolean matches = Integer.parseInt(progress.group(2)) <= took / FRAME_COUNT + 0.5;
    assertTrue(matches, "%s in %s ms".formatted(told.get(2), took));

    final boolean secondMatches = Integer.parseInt(progress.group(2)) >= openingMilliseconds / FRAME_COUNT - 0.5;
    assertTrue(secondMatches, "progress includes source opening");

    final Matcher done = Pattern.compile(DURATION_PATTERN).matcher(told.getLast());

    final boolean doneFound = done.find();
    assertTrue(doneFound, told.getLast());

    final boolean thirdMatches = Integer.parseInt(done.group(1)) <= took / 1000 + 0.5;
    assertTrue(thirdMatches, "%s in %s ms".formatted(told.getLast(), took));

    final boolean fourthMatches = Integer.parseInt(done.group(1)) >= openingMilliseconds / 1000 - 0.5;
    assertTrue(fourthMatches, "completion includes source opening");
  }

  @Test
  void shouldReportTheWallTimePerFrameWhenTheEncodeIsDone() throws Exception {
    final Path output = this.folder.resolve(TIMED_STREAM_FILE_NAME);
    final AtomicLong openingNanoseconds = new AtomicLong();
    this.command.encodeFile(
      this.sender,
      (_, _, _) -> this.openSlowly(openingNanoseconds),
      Path.of(VIDEO_F_FILE_NAME),
      output,
      320,
      180,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    final List<String> told = this.finish();
    final String done = told.getLast();
    final Matcher figures = Pattern.compile(DONE_FIGURES_PATTERN).matcher(done);

    final boolean found = figures.find();
    assertTrue(found, done);

    final double openingMilliseconds = openingNanoseconds.get() / 1e6;
    final int millisecondsPerFrame = Integer.parseInt(figures.group(1));
    final boolean includesOpening = millisecondsPerFrame >= openingMilliseconds / FRAME_COUNT - 0.5;
    assertTrue(includesOpening, done);

    final int encodingMillisecondsPerFrame = Integer.parseInt(figures.group(2));
    final boolean partOfTheWallTime = encodingMillisecondsPerFrame <= millisecondsPerFrame;
    assertTrue(partOfTheWallTime, done);
  }

  @Test
  void shouldReportNoTimePerFrameWhenTheVideoHasNoFrames() throws Exception {
    final Path output = this.folder.resolve(STREAM_FILE_NAME);
    this.command.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(0, new CountDownLatch(0)),
      Path.of(VIDEO_FILE_NAME),
      output,
      ENCODE_SIDE,
      ENCODE_SIDE,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    final List<String> told = this.finish();
    final String done = told.getLast();
    final Matcher figures = Pattern.compile(DONE_FIGURES_PATTERN).matcher(done);

    final boolean found = figures.find();
    assertTrue(found, done);

    final String millisecondsPerFrame = figures.group(1);
    assertEquals("0", millisecondsPerFrame, done);

    final String encodingMillisecondsPerFrame = figures.group(2);
    assertEquals("0", encodingMillisecondsPerFrame, done);
  }

  private Mcv2FileEncoder.FrameReader openSlowly(final AtomicLong openingNanoseconds) throws IOException {
    final long openingStarted = System.nanoTime();
    try {
      Thread.sleep(700);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException(exception);
    }
    openingNanoseconds.set(System.nanoTime() - openingStarted);

    return new SolidFrames(FRAME_COUNT, new CountDownLatch(0));
  }

  @Test
  void shouldStopTheEncodeWhenThePluginStops() throws Exception {
    final SolidFrames frames = new SolidFrames(LONG_FRAME_COUNT, new CountDownLatch(1));
    this.command.setOpener((_, _, _) -> frames);
    this.command.encode(this.sender, LONG_VIDEO_FILE_NAME, LONG_STREAM_FILE_NAME, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);
    final Thread thread = Objects.requireNonNull(this.command.getEncodingThread());
    this.command.shutdown();
    thread.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));

    final boolean alive = thread.isAlive();
    assertFalse(alive);
    assertTrue(frames.closed);

    this.command.shutdown();
  }

  @Test
  void shouldKeepStreamFilesInThePluginsFolder() throws IOException, InterruptedException {
    final Path folder = this.streams.toAbsolutePath().normalize();

    final boolean exists = Files.exists(folder);
    assertFalse(exists);

    final Path expected = folder.resolve(STREAM_FILE_NAME);
    final Path streamFile = this.command.prepareStreamFile(STREAM_FILE_NAME);
    assertEquals(expected, streamFile);

    final boolean directory = Files.isDirectory(folder);
    assertTrue(directory);

    final Path folderExpected = folder.resolve(STREAM_FILE_NAME);
    final Path commandStreamFile = this.command.prepareStreamFile(REDUNDANT_STREAM_PATH);
    assertEquals(folderExpected, commandStreamFile);

    final Path secondExpected = folder.resolve(SUBFOLDER_STREAM_PATH);
    final Path secondStreamFile = this.command.prepareStreamFile(folder.resolve(SUBFOLDER_STREAM_PATH).toString());
    assertEquals(secondExpected, secondStreamFile);

    for (final String name : List.of(
      PARENT_STREAM_PATH,
      ESCAPING_STREAM_PATH,
      ABSOLUTE_STREAM_PATH,
      this.folder.resolve(STREAM_FILE_NAME).toString(),
      "",
      "."
    )) {
      assertThrows(IOException.class, () -> this.command.prepareStreamFile(name), name);
    }
    this.command.play(this.sender, this.selector, SCREEN_SIZE, MAP_ID, TICKS, ABSOLUTE_STREAM_PATH);
    this.runHandedOver();
    this.command.stream(this.sender, this.selector, SCREEN_SIZE, MAP_ID, FRAMES_PER_SECOND, PARENT_PLAY_PATH);
    this.runHandedOver();
    verify(this.packs, never()).open(any());

    this.command.encode(this.sender, VIDEO_FILE_NAME, ESCAPING_ENCODE_PATH, ENCODE_DIMENSIONS, Mcv2Profile.DEFAULT);

    final Thread encoding = this.command.getEncodingThread();
    assertNull(encoding);

    final List<String> told = this.finish();

    final int size = told.size();
    assertEquals(3, size);

    final String refusal = "Stream file ../../escape.mcs is not a file in %s, where stream files are kept!".formatted(folder);

    final String thirdExpected = this.text(Message.MCV2_ENCODE_ERROR.build(refusal));
    final String toldTold = told.get(2);
    assertEquals(thirdExpected, toldTold);

    final boolean filesExists = Files.exists(this.folder.getParent().resolve(ESCAPE_STREAM_FILE_NAME));
    assertFalse(filesExists);
  }

  @Test
  void shouldReadLengthPrefixedFrames() throws IOException {
    final List<byte[]> stream = Mcv2PlaybackTest.stream();
    final List<byte[]> read = this.command.read(this.archive(stream));

    final int size = read.size();
    assertEquals(3, size);

    final byte[] expected = stream.get(2);
    final byte[] readRead = read.get(2);
    assertArrayEquals(expected, readRead);

    final Path truncatedLength = Files.write(this.folder.resolve(STREAM_A_FILE_NAME), new byte[] { 1, 0, 0 });

    final String message = assertThrows(IOException.class, () -> this.command.read(truncatedLength)).getMessage();
    assertEquals("Truncated frame length!", message);

    final Path truncatedFrame = Files.write(this.folder.resolve(STREAM_B_FILE_NAME), new byte[] { 9, 0, 0, 0, 1 });

    final String secondMessage = assertThrows(IOException.class, () -> this.command.read(truncatedFrame)).getMessage();
    assertEquals("Truncated frame!", secondMessage);

    final Path empty = Files.write(this.folder.resolve(STREAM_C_FILE_NAME), new byte[0]);

    final String thirdMessage = assertThrows(IOException.class, () -> this.command.read(empty)).getMessage();
    assertEquals("Empty stream!", thirdMessage);

    final String secondExpected = "File %s is not a stream file!".formatted(this.folder.getFileName());
    final String fourthMessage = assertThrows(IOException.class, () -> this.command.read(this.folder)).getMessage();
    assertEquals(secondExpected, fourthMessage);

    final String fifthMessage = assertThrows(IOException.class, () -> this.command.read(truncatedFrame, 4)).getMessage();
    assertEquals("The stream file is larger than 4 bytes!", fifthMessage);

    final byte[] shortest = this.record(Mcv2Decoder.HEADER_BYTES);

    final int mcv2PlayCommandSize = this.command
      .read(Files.write(this.folder.resolve(STREAM_D_FILE_NAME), shortest), shortest.length)
      .size();
    assertEquals(1, mcv2PlayCommandSize);

    // after a first frame: three bytes are no length, a length past the end no frame
    final Path shortTail = Files.write(this.folder.resolve(STREAM_E_FILE_NAME), this.concatenate(shortest, new byte[] { 1, 0, 0 }));

    final String sixthMessage = assertThrows(IOException.class, () -> this.command.read(shortTail)).getMessage();
    assertEquals("Truncated frame length!", sixthMessage);

    final Path longTail = Files.write(this.folder.resolve(STREAM_F_FILE_NAME), this.concatenate(shortest, new byte[] { 9, 0, 0, 0, 1 }));

    final String seventhMessage = assertThrows(IOException.class, () -> this.command.read(longTail)).getMessage();
    assertEquals("Truncated frame!", seventhMessage);

    // a record shorter than a frame's header is no frame, an empty one neither
    final Path tiny = Files.write(this.folder.resolve(STREAM_G_FILE_NAME), new byte[] { 1, 0, 0, 0, 7 });

    final String eighthMessage = assertThrows(IOException.class, () -> this.command.read(tiny)).getMessage();
    assertEquals("MCV2 frame length 1 is invalid!", eighthMessage);

    final Path empties = Files.write(this.folder.resolve(STREAM_I_FILE_NAME), this.concatenate(shortest, new byte[] { 0, 0, 0, 0 }));

    final String ninthMessage = assertThrows(IOException.class, () -> this.command.read(empties)).getMessage();
    assertEquals("MCV2 frame length 0 is invalid!", ninthMessage);

    final Path huge = Files.write(this.folder.resolve(STREAM_J_FILE_NAME), this.record(Mcv2Decoder.MAXIMUM_FRAME_BYTES + 1));

    final String tenthMessage = assertThrows(IOException.class, () -> this.command.read(huge)).getMessage();
    assertEquals("MCV2 frame length %s is invalid!".formatted(Mcv2Decoder.MAXIMUM_FRAME_BYTES + 1), tenthMessage);

    final byte[] longest = this.record(Mcv2Decoder.MAXIMUM_FRAME_BYTES);

    final int secondSize = this.command.read(Files.write(this.folder.resolve(STREAM_K_FILE_NAME), longest)).size();
    assertEquals(1, secondSize);

    // every byte of the length counts, little-endian: 16 bytes follow, fewer than any of these lengths
    for (int high = 1; high < 4; high++) {
      final byte[] data = new byte[4 + 16];
      data[0] = 16;
      data[high] = 1;
      final Path file = Files.write(this.folder.resolve(HIGH_STREAM_PREFIX + high + STREAM_SUFFIX), data);

      final String eleventhMessage = assertThrows(IOException.class, () -> this.command.read(file)).getMessage();
      assertEquals("Truncated frame!", eleventhMessage);
    }
  }

  private byte[] record(final int length) {
    final byte[] record = new byte[Integer.BYTES + length];
    ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN).putInt(0, length);

    return record;
  }

  private byte[] concatenate(final byte[] first, final byte[] second) {
    final byte[] both = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, both, first.length, second.length);
    return both;
  }

  @Test
  void shouldRefuseANullPlugin() {
    final String message = assertThrows(NullPointerException.class, () -> new Mcv2PlayCommand(null)).getMessage();
    assertEquals("Plugin must not be null!", message);
  }

  @Test
  void shouldRefuseAStreamFileLargerThanAGibibyte() throws IOException {
    final Path file = this.folder.resolve(OVERSIZED_STREAM_FILE_NAME);
    try (
      final FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)
    ) {
      final ByteBuffer last = ByteBuffer.wrap(new byte[] { 1 });
      channel.write(last, 1L << 30);
    }

    final String message = assertThrows(IOException.class, () -> this.command.read(file)).getMessage();
    assertEquals("The stream file is larger than 1073741824 bytes!", message);
  }

  // frames after the first wait for a latch, like a slow decoder
  private static final class SolidFrames implements Mcv2FileEncoder.FrameReader {

    private final int count;

    private final CountDownLatch release;

    private int read;

    private volatile boolean closed;

    SolidFrames(final int count, final CountDownLatch release) {
      this.count = count;
      this.release = release;
    }

    @Override
    public void close() {
      this.closed = true;
    }

    @Override
    public boolean read(final byte[] rgb) {
      if (this.read > 0) {
        try {
          this.release.await();
        } catch (final InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }
      Arrays.fill(rgb, (byte) (this.read * COLOR_STEP));

      return this.read++ < this.count;
    }
  }

  private static final class SlowToClose implements Mcv2FileEncoder.FrameReader {

    private final CountDownLatch mayClose;

    private int read;

    SlowToClose(final CountDownLatch mayClose) {
      this.mayClose = mayClose;
    }

    @Override
    public void close() {
      // the cancel's interrupt is kept for after, so the wait is not cut short by it
      final boolean interrupted = Thread.interrupted();
      Uninterruptibles.awaitUninterruptibly(this.mayClose);
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public boolean read(final byte[] rgb) {
      if (this.read++ > 0) {
        try {
          // until the cancel interrupts the encode
          new CountDownLatch(1).await();
        } catch (final InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }
      return true;
    }
  }

  // it has a few frames, so an encode that misses the cancel ends as done
  private static final class DropsInterrupts implements Mcv2FileEncoder.FrameReader {

    private final CountDownLatch cancelled;

    private int read;

    private volatile boolean dropped;

    DropsInterrupts(final CountDownLatch cancelled) {
      this.cancelled = cancelled;
    }

    @Override
    public void close() {}

    @Override
    public boolean read(final byte[] rgb) {
      if (this.read == 0) {
        Uninterruptibles.awaitUninterruptibly(this.cancelled);
      }
      if (Thread.interrupted()) {
        this.dropped = true;
      }
      Arrays.fill(rgb, (byte) (this.read * COLOR_STEP));

      return this.read++ < 3;
    }
  }

  private static final class WaitsForInterrupt implements Mcv2FileEncoder.FrameReader {

    @Override
    public void close() {}

    @Override
    public boolean read(final byte[] rgb) {
      try {
        new CountDownLatch(1).await();
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
      return true;
    }
  }
}
