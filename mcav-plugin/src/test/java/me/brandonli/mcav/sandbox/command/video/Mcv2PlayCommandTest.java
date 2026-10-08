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
package me.brandonli.mcav.sandbox.command.video;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Uninterruptibles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2FileEncoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.listener.OnlinePlayers;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.TestServer;
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
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

final class Mcv2PlayCommandTest {

  @TempDir
  private Path folder;

  /** The plugin's folder of stream files. */
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

  /** The tasks the command handed over to the main thread, from the thread that reads stream files. */
  private final BlockingQueue<Runnable> handedOver = new LinkedBlockingQueue<>();

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    this.plugin = mock(MCAVSandbox.class);
    when(this.plugin.getDataFolder()).thenReturn(this.folder.toFile());
    this.streams = this.folder.resolve(Mcv2PlayCommand.STREAMS);
    this.packs = mock(Mcv2PackServer.class);
    when(this.packs.getViewers()).thenReturn(mock(Mcv2Viewers.class));
    this.lease = mock(Mcv2PackServer.Lease.class);
    final AtomicReference<Mcv2Configuration> opened = new AtomicReference<>();
    when(this.packs.open(any())).thenAnswer(invocation -> {
      opened.set(invocation.getArgument(0));
      return this.lease;
    });
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
    final ItemFrame frame = Mcv2SupportTest.frame(Mcv2SupportTest.map(Material.FILLED_MAP, true, 20));
    when(frame.getLocation()).thenReturn(new Location(world, 5, 64, 7));
    when(frame.getFacing()).thenReturn(BlockFace.SOUTH);
    when(world.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(frame));
    when(TestServer.server().getWorlds()).thenReturn(List.of(world));
    this.task = mock(BukkitTask.class);
    when(TestServer.scheduler().runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong())).thenReturn(
      this.task
    );
    when(TestServer.scheduler().runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
      this.handedOver.add(invocation.getArgument(1));
      return null;
    });
  }

  /**
   * Runs on the main thread, which the test thread is, the next task the command handed over to it from the thread that
   * reads stream files.
   */
  private void runHandedOver() throws InterruptedException {
    final Runnable handed = this.handedOver.poll(10, TimeUnit.SECONDS);
    assertNotNull(handed, "the command handed nothing over to the main thread");
    handed.run();
  }

  private Path archive(final List<byte[]> frames) throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final byte[] frame : frames) {
      out.write(new byte[] { (byte) frame.length, (byte) (frame.length >> 8), (byte) (frame.length >> 16), (byte) (frame.length >> 24) });
      out.write(frame);
    }
    Files.createDirectories(this.streams);
    final Path file = Files.createTempFile(this.streams, "stream", ".mcs");
    Files.write(file, out.toByteArray());
    return file;
  }

  @Test
  void playsAStreamOnTheWallUntilStopped() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, "5x3", 20, 2, file.toString());
      this.runHandedOver();
      final Mcv2Channel channel = channels.constructed().getFirst();
      verify(channel).open();
      final ArgumentCaptor<Mcv2Configuration> configurations = ArgumentCaptor.forClass(Mcv2Configuration.class);
      verify(this.packs).open(configurations.capture());
      assertEquals(32, configurations.getValue().getVideoWidth());
      assertEquals(32, configurations.getValue().getVideoHeight());
      assertEquals(List.of(this.viewer), List.copyOf(configurations.getValue().getViewers()));
      final ArgumentCaptor<Runnable> playbacks = ArgumentCaptor.forClass(Runnable.class);
      verify(TestServer.scheduler()).runTaskTimerAsynchronously(eq(this.plugin), playbacks.capture(), eq(0L), eq(2L));
      assertInstanceOf(Mcv2Playback.class, playbacks.getValue());
      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());
      // a new stream replaces the one before
      this.command.play(this.sender, this.selector, "5x3", 20, 2, file.toString());
      this.runHandedOver();
      verify(this.task).cancel();
      verify(channel).close();
      verify(this.lease).close();
      this.command.stop(this.sender);
      verify(channels.constructed().getLast()).close();
      verify(this.sender).sendMessage(Message.MCV2_STOP.build());
      // the slot goes back, and the pack stays served for the next stream
      verify(this.lease, Mockito.times(2)).close();
      verify(this.packs, never()).shutdown();
    }
  }

  @Test
  void readsTheStreamFileOffTheMainThreadAndOpensTheScreenOnIt() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    final List<Boolean> fromMainThread = new CopyOnWriteArrayList<>();
    when(TestServer.scheduler().runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(invocation -> {
      fromMainThread.add(Bukkit.isPrimaryThread());
      this.handedOver.add(invocation.getArgument(1));
      return null;
    });
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, "5x3", 20, 2, file.toString());
      // the command returns before the file is read: a stream file holds up to a gibibyte, and a busy disk held the
      // server's main thread up to 51 s in soak72
      verify(this.packs, never()).open(any());
      verify(this.sender, never()).sendMessage(any(Component.class));
      this.runHandedOver();
      assertEquals(List.of(false), fromMainThread, "the file is read on a thread of its own, which hands the screen over");
      verify(channels.constructed().getFirst()).open();
      verify(this.packs).open(any());
      verify(TestServer.scheduler()).runTaskTimerAsynchronously(eq(this.plugin), any(Runnable.class), eq(0L), eq(2L));
      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());
    }
  }

  @Test
  void aStreamAskedForLaterReplacesOneWhoseFileIsStillBeingRead() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, "5x3", 20, 2, file.toString());
      this.command.play(this.sender, this.selector, "5x3", 20, 3, file.toString());
      this.runHandedOver();
      this.runHandedOver();
      // only the stream asked for last plays, however the reads end
      assertEquals(1, channels.constructed().size());
      verify(this.packs).open(any());
      verify(TestServer.scheduler()).runTaskTimerAsynchronously(eq(this.plugin), any(Runnable.class), eq(0L), eq(3L));
      verify(TestServer.scheduler(), never()).runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), eq(2L));
      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());
    }
  }

  @Test
  void aStopReplacesAStreamWhoseFileIsStillBeingRead() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, "5x3", 20, 2, this.streams.resolve("missing.mcs").toString());
      this.command.stop(this.sender);
      this.command.play(this.sender, this.selector, "5x3", 20, 3, file.toString());
      this.runHandedOver();
      this.runHandedOver();
      // the file asked for before the stop is not reported, nor played; the one asked for after it plays
      verify(this.sender, Mockito.times(2)).sendMessage(any(Component.class));
      verify(this.sender).sendMessage(Message.MCV2_STOP.build());
      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());
      assertEquals(1, channels.constructed().size());
      verify(TestServer.scheduler()).runTaskTimerAsynchronously(eq(this.plugin), any(Runnable.class), eq(0L), eq(3L));
    }
  }

  @Test
  void aStreamReadAsThePluginStopsIsNotOpened() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, "5x3", 20, 2, file.toString());
      this.command.play(this.sender, this.selector, "5x3", 20, 3, file.toString());
      // both files are read, and the main thread has not yet opened either, when the plugin stops
      final Runnable first = this.handedOver.poll(10, TimeUnit.SECONDS);
      final Runnable second = this.handedOver.poll(10, TimeUnit.SECONDS);
      assertNotNull(first);
      assertNotNull(second);
      this.command.shutdown();
      first.run();
      second.run();
      assertEquals(List.of(), channels.constructed());
      verify(this.packs, never()).open(any());
      verify(this.sender, never()).sendMessage(any(Component.class));
    }
  }

  @Test
  void streamsAtAFrameRateOffTheServerTick() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (
      final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class, (channel, context) ->
        when(channel.getRecipients()).thenReturn(Set.of(UUID.randomUUID()))
      )
    ) {
      this.command.stream(this.sender, this.selector, "5x3", 20, 100, file.toString());
      this.runHandedOver();
      final Mcv2Channel channel = channels.constructed().getFirst();
      verify(channel).open();
      // 100 frames a second from the stream's own thread, not from the server's scheduler
      verify(channel, Mockito.timeout(5000).atLeast(10)).send(any());
      verify(TestServer.scheduler(), never()).runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong());
      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());
      this.command.stop(this.sender);
      verify(channel).close();
      // no frame follows the stop
      Mockito.clearInvocations(channel);
      Thread.sleep(100);
      verify(channel, never()).send(any());
      // a stream that cannot be read is reported like one to play: the play and stop messages, then the error
      this.command.stream(this.sender, this.selector, "5x3", 20, 60, this.streams.resolve("missing.mcs").toString());
      this.runHandedOver();
      verify(this.sender, Mockito.times(3)).sendMessage(any(Component.class));
      verify(this.sender).sendMessage(Message.MCV2_FILE_ERROR.build("Not a stream file: missing.mcs"));
    }
  }

  @Test
  void playsNothingWhenEverySlotOfThePackPlays() throws IOException, InterruptedException {
    when(this.packs.open(any())).thenThrow(new IllegalStateException("full"));
    this.command.play(this.sender, this.selector, "5x3", 20, 2, this.archive(Mcv2PlaybackTest.stream()).toString());
    this.runHandedOver();
    verify(this.sender).sendMessage(Message.MCV2_FULL.build());
    verify(TestServer.scheduler(), never()).runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong());
  }

  @Test
  void refusesWhatItCannotPlay() throws IOException, InterruptedException {
    // a wall of the wrong size is refused before any file is read
    this.command.play(this.sender, this.selector, "65x1", 20, 2, "anything");
    assertTrue(this.handedOver.isEmpty());
    this.command.play(this.sender, this.selector, "5x3", 20, 2, this.streams.resolve("missing.mcs").toString());
    this.runHandedOver();
    this.command.play(this.sender, this.selector, "5x3", 20, 2, this.archive(List.of(new byte[48])).toString());
    this.runHandedOver();
    // the wall size, the missing file and the frame that is not MCV2 are each reported
    verify(this.sender, Mockito.times(3)).sendMessage(any(Component.class));
    final ArgumentCaptor<Component> messages = ArgumentCaptor.captor();
    verify(this.sender, Mockito.times(3)).sendMessage(messages.capture());
    assertEquals(
      List.of(
        Message.UNSUPPORTED_DIMENSION.build(),
        Message.MCV2_FILE_ERROR.build("Not a stream file: missing.mcs"),
        Message.MCV2_FILE_ERROR.build("Not an MCV2 frame")
      ),
      messages.getAllValues()
    );
    this.command.play(this.sender, this.selector, "5x3", 21, 2, this.archive(Mcv2PlaybackTest.stream()).toString());
    this.runHandedOver();
    verify(this.sender).sendMessage(Message.MCV2_SCREEN_ERROR.build(21));
    verify(this.packs, never()).open(any());
  }

  /** A video of solid frames; every frame after the first waits for a latch, as a slow decoder would. */
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
    public boolean read(final byte[] rgb) {
      if (this.read > 0) {
        try {
          this.release.await();
        } catch (final InterruptedException exception) {
          Thread.currentThread().interrupt();
        }
      }
      Arrays.fill(rgb, (byte) (this.read * 40));
      return this.read++ < this.count;
    }

    @Override
    public void close() {
      this.closed = true;
    }
  }

  /** A video that reads one frame, then waits for the encode to be cancelled, and lets go of itself slowly. */
  private static final class SlowToClose implements Mcv2FileEncoder.FrameReader {

    private final CountDownLatch mayClose;

    private int read;

    SlowToClose(final CountDownLatch mayClose) {
      this.mayClose = mayClose;
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

    @Override
    public void close() {
      // the cancel's interrupt is kept for after, so the wait is not cut short by it
      final boolean interrupted = Thread.interrupted();
      Uninterruptibles.awaitUninterruptibly(this.mayClose);
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * A video whose reads drop the thread's interrupt, as code that swallows one does: its first read waits until the
   * encode was cancelled, and it has a few frames, so an encode that misses the cancel ends as done.
   */
  private static final class DropsInterrupts implements Mcv2FileEncoder.FrameReader {

    private final CountDownLatch cancelled;

    private int read;

    private volatile boolean dropped;

    DropsInterrupts(final CountDownLatch cancelled) {
      this.cancelled = cancelled;
    }

    @Override
    public boolean read(final byte[] rgb) {
      if (this.read == 0) {
        Uninterruptibles.awaitUninterruptibly(this.cancelled);
      }
      if (Thread.interrupted()) {
        this.dropped = true;
      }
      Arrays.fill(rgb, (byte) (this.read * 40));
      return this.read++ < 3;
    }

    @Override
    public void close() {
      // nothing to release
    }
  }

  /** A video whose every read waits until the encode's thread is interrupted. */
  private static final class WaitsForInterrupt implements Mcv2FileEncoder.FrameReader {

    @Override
    public boolean read(final byte[] rgb) {
      try {
        new CountDownLatch(1).await();
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
      return true;
    }

    @Override
    public void close() {
      // nothing to release
    }
  }

  private static String text(final Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  /** Waits for the encode the command started last and returns everything the sender was told. */
  private List<String> finish() throws InterruptedException {
    final Thread thread = this.command.getEncoding();
    if (thread != null) {
      thread.join(TimeUnit.SECONDS.toMillis(60));
      assertFalse(thread.isAlive());
    }
    final ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
    verify(this.sender, Mockito.atLeast(0)).sendMessage(messages.capture());
    return messages.getAllValues().stream().map(Mcv2PlayCommandTest::text).toList();
  }

  @Test
  void encodesAVideoFileAheadOfTimeOnItsOwnThread() throws Exception {
    final Path output = this.streams.resolve("clip.mcs");
    final SolidFrames frames = new SolidFrames(3, new CountDownLatch(0));
    final List<String> opened = new ArrayList<>();
    this.command.setOpener((video, width, height) -> {
      opened.add(video + " " + width + "x" + height);
      return frames;
    });
    this.command.encode(this.sender, "clip.mp4", "clip.mcs", "16x16", Mcv2Profile.DEFAULT);
    final List<String> told = this.finish();
    assertEquals(List.of("clip.mp4 16x16"), opened);
    assertEquals(2, told.size());
    final int threads = Pool.shared().getThreads();
    assertEquals(
      text(
        Message.MCV2_ENCODE_START.build(
          "clip.mp4 into " + output + " at 16x16 with the default profile, on " + threads + " encoder threads"
        )
      ),
      told.getFirst()
    );
    assertTrue(told.get(1).startsWith("MCV2 encode finished: 3 frames (1 keyframes) into " + output), told.get(1));
    assertTrue(frames.closed);
    assertEquals(3, Mcv2PlayCommand.read(output).size());
    assertFalse(Files.exists(this.streams.resolve("clip.mcs.part")));
    // the encode is over: the next one may start, and cancelling after it finished finds nothing to stop
    this.command.setOpener((_, _, _) -> new SolidFrames(1, new CountDownLatch(0)));
    this.command.encode(this.sender, "next.mp4", "next.mcs", "16x16", Mcv2Profile.DEFAULT);
    this.finish();
    this.command.cancel(this.sender);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_NONE.build());
    assertEquals(1, Mcv2PlayCommand.read(this.streams.resolve("next.mcs")).size());
  }

  @Test
  void tellsTheProgressAndTheFailures() throws Exception {
    final Path output = this.folder.resolve("progress.mcs");
    Mcv2PlayCommand.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(2, new CountDownLatch(0)),
      Path.of("a.mp4"),
      output,
      16,
      16,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    final List<String> told = this.finish();
    assertEquals(3, told.size());
    assertTrue(told.getFirst().startsWith("MCV2 encode: 1 frames, "), told.getFirst());
    assertTrue(told.get(1).startsWith("MCV2 encode: 2 frames, "), told.get(1));
    // a video that cannot be opened, and a stream that cannot be written
    Mockito.clearInvocations(this.sender);
    Mcv2PlayCommand.encodeFile(
      this.sender,
      (_, _, _) -> {
        throw new IOException("no such video");
      },
      Path.of("b.mp4"),
      output,
      16,
      16,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    Mcv2PlayCommand.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(1, new CountDownLatch(0)),
      Path.of("c.mp4"),
      this.folder.resolve("missing").resolve("out.mcs"),
      16,
      16,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    // a stream whose unfinished file is a folder that cannot be written, nor removed afterwards
    final Path blocked = this.folder.resolve("blocked.mcs");
    Files.createDirectories(this.folder.resolve("blocked.mcs.part").resolve("inside"));
    Mcv2PlayCommand.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(1, new CountDownLatch(0)),
      Path.of("d.mp4"),
      blocked,
      16,
      16,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    final List<String> failures = this.finish();
    assertEquals(text(Message.MCV2_ENCODE_ERROR.build("no such video")), failures.getFirst());
    assertTrue(failures.get(1).startsWith("The MCV2 encode failed: "), failures.get(1));
    assertTrue(failures.get(2).startsWith("The MCV2 encode failed: "), failures.get(2));
    assertEquals(3, failures.size());
    assertTrue(Files.isDirectory(this.folder.resolve("blocked.mcs.part")));
  }

  @ParameterizedTest
  @ValueSource(strings = { "4097x1", "1x4097" })
  void refusesOfflineDimensionsBeyondTheCodecLimit(final String dimensions) throws Exception {
    final Mcv2PlayCommand.Opener opener = mock(Mcv2PlayCommand.Opener.class);
    when(opener.open(any(), anyInt(), anyInt())).thenThrow(new IOException("unexpected open"));
    this.command.setOpener(opener);
    try {
      this.command.encode(this.sender, "clip.mp4", "limit.mcs", dimensions, Mcv2Profile.DEFAULT);
      assertNull(this.command.getEncoding(), "invalid codec dimensions cannot start a worker");
      Mockito.verifyNoInteractions(opener);
      verify(this.sender).sendMessage(Message.UNSUPPORTED_DIMENSION.build());
      assertFalse(Files.exists(this.streams.resolve("limit.mcs.part")));
    } finally {
      this.finish();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "4096x1", "1x4096" })
  void acceptsOfflineDimensionsAtTheCodecLimit(final String dimensions) throws Exception {
    final Mcv2FileEncoder.FrameReader frames = mock(Mcv2FileEncoder.FrameReader.class);
    when(frames.read(any(byte[].class))).thenReturn(true, false);
    this.command.setOpener((_, _, _) -> frames);
    this.command.encode(this.sender, "clip.mp4", "limit.mcs", dimensions, Mcv2Profile.DEFAULT);
    this.finish();
    assertEquals(1, Mcv2PlayCommand.read(this.streams.resolve("limit.mcs")).size());
    verify(frames).close();
  }

  @Test
  void reportsRecoverableEncodeFailuresAndRemovesOnlyItsPartialOutput() throws Exception {
    final Path target = this.folder.resolve("old.mcs");
    final byte[] previous = { 1, 2, 3 };
    Files.write(target, previous);
    assertDoesNotThrow(() ->
      Mcv2PlayCommand.encodeFile(
        this.sender,
        (_, _, _) -> {
          throw new IllegalStateException("reader rejected the source");
        },
        Path.of("clip.mp4"),
        target,
        16,
        16,
        Mcv2Profile.DEFAULT,
        Pool.shared(),
        0
      )
    );
    assertFalse(Files.exists(this.folder.resolve("old.mcs.part")));
    assertArrayEquals(previous, Files.readAllBytes(target));
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_ERROR.build("reader rejected the source"));
  }

  @Test
  void runsOneEncodeAtATimeAndStopsItWhenAsked() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final SolidFrames frames = new SolidFrames(1000, release);
    this.command.setOpener((_, _, _) -> frames);
    final Path output = this.streams.resolve("long.mcs");
    this.command.encode(this.sender, "long.mp4", "long.mcs", "16x16", Mcv2Profile.DEFAULT);
    final Thread thread = this.command.getEncoding();
    // a second encode while the first runs is refused
    this.command.encode(this.sender, "other.mp4", "other.mcs", "16x16", Mcv2Profile.DEFAULT);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_BUSY.build());
    this.command.cancel(this.sender);
    assertSame(thread, this.command.getEncoding(), "the cancelled encode is the running one until its thread ends");
    Objects.requireNonNull(thread).join(TimeUnit.SECONDS.toMillis(60));
    assertFalse(thread.isAlive());
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_CANCELLED.build());
    assertTrue(frames.closed);
    assertFalse(Files.exists(output));
    assertFalse(Files.exists(this.streams.resolve("long.mcs.part")));
    // nothing left to cancel
    this.command.cancel(this.sender);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_NONE.build());
    // a size that is not one is refused before anything starts
    this.command.encode(this.sender, "clip.mp4", "long.mcs", "big", Mcv2Profile.DEFAULT);
    assertSame(thread, this.command.getEncoding());
  }

  @Test
  void aCancelStopsTheEncodeEvenWhenItsVideoDropsTheInterrupt() throws Exception {
    final CountDownLatch cancelled = new CountDownLatch(1);
    final DropsInterrupts frames = new DropsInterrupts(cancelled);
    this.command.setOpener((_, _, _) -> frames);
    this.command.encode(this.sender, "long.mp4", "long.mcs", "16x16", Mcv2Profile.DEFAULT);
    final Thread thread = Objects.requireNonNull(this.command.getEncoding());
    this.command.cancel(this.sender);
    cancelled.countDown();
    thread.join(TimeUnit.SECONDS.toMillis(60));
    assertFalse(thread.isAlive());
    assertTrue(frames.dropped, "the video dropped the cancel's interrupt");
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_CANCELLED.build());
    assertFalse(Files.exists(this.streams.resolve("long.mcs")));
    assertFalse(Files.exists(this.streams.resolve("long.mcs.part")));
  }

  @Test
  void aCancelStopsTheEncodeEvenWhenOpeningTheVideoDropsTheInterrupt() throws Exception {
    final CountDownLatch cancelled = new CountDownLatch(1);
    final AtomicBoolean dropped = new AtomicBoolean();
    this.command.setOpener((_, _, _) -> {
      Uninterruptibles.awaitUninterruptibly(cancelled);
      dropped.set(Thread.interrupted());
      return new WaitsForInterrupt();
    });
    this.command.encode(this.sender, "long.mp4", "long.mcs", "16x16", Mcv2Profile.DEFAULT);
    final Thread thread = Objects.requireNonNull(this.command.getEncoding());
    this.command.cancel(this.sender);
    cancelled.countDown();
    thread.join(TimeUnit.SECONDS.toMillis(60));
    assertFalse(thread.isAlive(), "the encode waits for a frame though it was cancelled");
    assertTrue(dropped.get(), "opening the video dropped the cancel's interrupt");
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_CANCELLED.build());
    assertFalse(Files.exists(this.streams.resolve("long.mcs.part")));
  }

  @Test
  void aCancelBeforeAnyEncodeSaysThereIsNone() {
    this.command.cancel(this.sender);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_NONE.build());
    assertNull(this.command.getEncoding());
  }

  @Test
  void aNewEncodeWaitsUntilACancelledOneHasEnded() throws Exception {
    final CountDownLatch mayClose = new CountDownLatch(1);
    this.command.setOpener((_, _, _) -> new SlowToClose(mayClose));
    this.command.encode(this.sender, "long.mp4", "long.mcs", "16x16", Mcv2Profile.DEFAULT);
    final Thread cancelled = Objects.requireNonNull(this.command.getEncoding());
    this.command.cancel(this.sender);
    // the cancelled encode still lets go of its video, and deletes long.mcs.part once it has
    final CountDownLatch released = new CountDownLatch(0);
    this.command.setOpener((_, _, _) -> new SolidFrames(2, released));
    this.command.encode(this.sender, "again.mp4", "long.mcs", "16x16", Mcv2Profile.DEFAULT);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_BUSY.build());
    assertSame(cancelled, this.command.getEncoding());
    mayClose.countDown();
    cancelled.join(TimeUnit.SECONDS.toMillis(60));
    assertFalse(cancelled.isAlive());
    // once it has ended, the same file is encoded and kept
    this.command.encode(this.sender, "again.mp4", "long.mcs", "16x16", Mcv2Profile.DEFAULT);
    final Thread again = Objects.requireNonNull(this.command.getEncoding());
    again.join(TimeUnit.SECONDS.toMillis(60));
    assertTrue(Files.exists(this.streams.resolve("long.mcs")));
    assertFalse(Files.exists(this.streams.resolve("long.mcs.part")));
  }

  @Test
  void stopsTheStreamWhenThePluginStops() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (final MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, "5x3", 20, 2, file.toString());
      this.runHandedOver();
      this.command.shutdown();
      verify(this.task).cancel();
      verify(channels.constructed().getFirst()).close();
    }
  }

  @Test
  void removesTheUnfinishedFileOfAFailedEncode() throws Exception {
    // the stream is written, but a folder that is not empty stands where it would go
    final Path target = this.folder.resolve("taken.mcs");
    Files.createDirectories(target.resolve("inside"));
    Mcv2PlayCommand.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(1, new CountDownLatch(0)),
      Path.of("e.mp4"),
      target,
      16,
      16,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    assertTrue(this.finish().getLast().startsWith("The MCV2 encode failed: "));
    assertFalse(Files.exists(this.folder.resolve("taken.mcs.part")));
  }

  @Test
  void tellsTimesThatTheEncodeCouldHaveTaken() throws Exception {
    // a frame is reported every frame; no report can say more milliseconds per frame, nor the end more seconds, than the
    // whole encode took
    final Path output = this.folder.resolve("timed.mcs");
    final AtomicLong openingNanos = new AtomicLong();
    final long started = System.nanoTime();
    Mcv2PlayCommand.encodeFile(
      this.sender,
      (_, _, _) -> {
        final long openingStarted = System.nanoTime();
        try {
          Thread.sleep(700);
        } catch (final InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
        openingNanos.set(System.nanoTime() - openingStarted);
        return new SolidFrames(3, new CountDownLatch(0));
      },
      Path.of("f.mp4"),
      output,
      320,
      180,
      Mcv2Profile.DEFAULT,
      Pool.shared(),
      0
    );
    final double took = (System.nanoTime() - started) / 1e6;
    final List<String> told = this.finish();
    final double openingMillis = openingNanos.get() / 1e6;
    assertTrue(openingMillis >= 600, "opening consumed independently measured time");
    final Matcher progress = Pattern.compile("MCV2 encode: (\\d+) frames, (\\d+) ms per frame").matcher(told.get(2));
    assertTrue(progress.find(), told.get(2));
    assertEquals(3, Integer.parseInt(progress.group(1)));
    assertTrue(Integer.parseInt(progress.group(2)) <= took / 3 + 0.5, told.get(2) + " in " + took + " ms");
    assertTrue(Integer.parseInt(progress.group(2)) >= openingMillis / 3 - 0.5, "progress includes source opening");
    final Matcher done = Pattern.compile(" in (\\d+) s, ").matcher(told.getLast());
    assertTrue(done.find(), told.getLast());
    assertTrue(Integer.parseInt(done.group(1)) <= took / 1000 + 0.5, told.getLast() + " in " + took + " ms");
    assertTrue(Integer.parseInt(done.group(1)) >= openingMillis / 1000 - 0.5, "completion includes source opening");
  }

  @Test
  void stopsTheEncodeWhenThePluginStops() throws Exception {
    final SolidFrames frames = new SolidFrames(1000, new CountDownLatch(1));
    this.command.setOpener((_, _, _) -> frames);
    this.command.encode(this.sender, "long.mp4", "long.mcs", "16x16", Mcv2Profile.DEFAULT);
    final Thread thread = Objects.requireNonNull(this.command.getEncoding());
    this.command.shutdown();
    thread.join(TimeUnit.SECONDS.toMillis(60));
    assertFalse(thread.isAlive());
    assertTrue(frames.closed);
    // a plugin that stops without an encode has nothing to stop
    this.command.shutdown();
  }

  @Test
  void keepsStreamFilesInThePluginsFolder() throws IOException, InterruptedException {
    final Path folder = this.streams.toAbsolutePath().normalize();
    assertFalse(Files.exists(folder));
    assertEquals(folder.resolve("clip.mcs"), this.command.streamFile("clip.mcs"));
    assertTrue(Files.isDirectory(folder));
    assertEquals(folder.resolve("clip.mcs"), this.command.streamFile("sub/../clip.mcs"));
    assertEquals(folder.resolve("sub/clip.mcs"), this.command.streamFile(folder.resolve("sub/clip.mcs").toString()));
    // a name that leads out of the folder, or names the folder itself, is refused
    for (final String name : List.of(
      "../clip.mcs",
      "sub/../../clip.mcs",
      "/etc/passwd",
      this.folder.resolve("clip.mcs").toString(),
      "",
      "."
    )) {
      assertThrows(IOException.class, () -> this.command.streamFile(name), name);
    }
    // so neither a stream is read from outside it nor an encode written there
    this.command.play(this.sender, this.selector, "5x3", 20, 2, "/etc/passwd");
    this.runHandedOver();
    this.command.stream(this.sender, this.selector, "5x3", 20, 60, "../stream.mcs");
    this.runHandedOver();
    verify(this.packs, never()).open(any());
    this.command.encode(this.sender, "clip.mp4", "../../escape.mcs", "16x16", Mcv2Profile.DEFAULT);
    assertNull(this.command.getEncoding());
    final List<String> told = this.finish();
    assertEquals(3, told.size());
    final String refusal = "Stream files are kept in " + folder + ", and ../../escape.mcs is not a file in it";
    assertEquals(text(Message.MCV2_ENCODE_ERROR.build(refusal)), told.get(2));
    assertFalse(Files.exists(this.folder.getParent().resolve("escape.mcs")));
  }

  /** A record of a stream file: the length, little-endian, then that many bytes. */
  private static byte[] record(final int length) {
    final byte[] record = new byte[Integer.BYTES + length];
    ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN).putInt(0, length);
    return record;
  }

  private static byte[] concat(final byte[] first, final byte[] second) {
    final byte[] both = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, both, first.length, second.length);
    return both;
  }

  @Test
  void readsLengthPrefixedFrames() throws IOException {
    final List<byte[]> stream = Mcv2PlaybackTest.stream();
    final List<byte[]> read = Mcv2PlayCommand.read(this.archive(stream));
    assertEquals(3, read.size());
    assertArrayEquals(stream.get(2), read.get(2));
    final Path truncatedLength = Files.write(this.folder.resolve("a.mcs"), new byte[] { 1, 0, 0 });
    assertEquals("Truncated frame length", assertThrows(IOException.class, () -> Mcv2PlayCommand.read(truncatedLength)).getMessage());
    final Path truncatedFrame = Files.write(this.folder.resolve("b.mcs"), new byte[] { 9, 0, 0, 0, 1 });
    assertEquals("Truncated frame", assertThrows(IOException.class, () -> Mcv2PlayCommand.read(truncatedFrame)).getMessage());
    final Path empty = Files.write(this.folder.resolve("c.mcs"), new byte[0]);
    assertEquals("Empty stream", assertThrows(IOException.class, () -> Mcv2PlayCommand.read(empty)).getMessage());
    // only a regular file is read, and only up to the limit: a device would never end, a huge file fill the memory
    assertEquals(
      "Not a stream file: " + this.folder.getFileName(),
      assertThrows(IOException.class, () -> Mcv2PlayCommand.read(this.folder)).getMessage()
    );
    assertEquals(
      "The stream file is larger than 4 bytes",
      assertThrows(IOException.class, () -> Mcv2PlayCommand.read(truncatedFrame, 4)).getMessage()
    );
    // the shortest frame is its header, and a file of one takes exactly its size
    final byte[] shortest = record(Mcv2Decoder.HEADER_BYTES);
    assertEquals(1, Mcv2PlayCommand.read(Files.write(this.folder.resolve("d.mcs"), shortest), shortest.length).size());
    // after a first frame: three bytes are no length, a length past the end no frame
    final Path shortTail = Files.write(this.folder.resolve("e.mcs"), concat(shortest, new byte[] { 1, 0, 0 }));
    assertEquals("Truncated frame length", assertThrows(IOException.class, () -> Mcv2PlayCommand.read(shortTail)).getMessage());
    final Path longTail = Files.write(this.folder.resolve("f.mcs"), concat(shortest, new byte[] { 9, 0, 0, 0, 1 }));
    assertEquals("Truncated frame", assertThrows(IOException.class, () -> Mcv2PlayCommand.read(longTail)).getMessage());
    // a record shorter than a frame's header is no frame, an empty one neither: refused before it is copied, as a file
    // of such records took several times its size in memory
    final Path tiny = Files.write(this.folder.resolve("g.mcs"), new byte[] { 1, 0, 0, 0, 7 });
    assertEquals("Invalid MCV2 frame length: 1", assertThrows(IOException.class, () -> Mcv2PlayCommand.read(tiny)).getMessage());
    final Path empties = Files.write(this.folder.resolve("i.mcs"), concat(shortest, new byte[] { 0, 0, 0, 0 }));
    assertEquals("Invalid MCV2 frame length: 0", assertThrows(IOException.class, () -> Mcv2PlayCommand.read(empties)).getMessage());
    // and one longer than a frame may be
    final Path huge = Files.write(this.folder.resolve("j.mcs"), record(Mcv2Decoder.MAX_FRAME_BYTES + 1));
    assertEquals(
      "Invalid MCV2 frame length: " + (Mcv2Decoder.MAX_FRAME_BYTES + 1),
      assertThrows(IOException.class, () -> Mcv2PlayCommand.read(huge)).getMessage()
    );
    // while the longest a frame may be is one
    final byte[] longest = record(Mcv2Decoder.MAX_FRAME_BYTES);
    assertEquals(1, Mcv2PlayCommand.read(Files.write(this.folder.resolve("k.mcs"), longest)).size());
    // every byte of the length counts, little-endian: 16 bytes follow, fewer than any of these lengths
    for (int high = 1; high < 4; high++) {
      final byte[] data = new byte[4 + 16];
      data[0] = 16;
      data[high] = 1;
      final Path file = Files.write(this.folder.resolve("h" + high + ".mcs"), data);
      assertEquals("Truncated frame", assertThrows(IOException.class, () -> Mcv2PlayCommand.read(file)).getMessage());
    }
    assertThrows(NullPointerException.class, () -> new Mcv2PlayCommand(null));
  }
}
