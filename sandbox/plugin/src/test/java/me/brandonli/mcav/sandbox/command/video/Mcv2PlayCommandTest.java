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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.media.mcv2.encode.EncoderPool;
import me.brandonli.mcav.media.mcv2.encode.Mcv2FileEncoder;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.TestServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

final class Mcv2PlayCommandTest {

  @TempDir
  private Path folder;

  /** The plugin's folder of stream files. */
  private Path streams;

  private MCAVSandbox plugin;

  private Mcv2Support support;

  private Mcv2Viewers viewers;

  private Mcv2PlayCommand command;

  private MultiplePlayerSelector selector;

  private CommandSender sender;

  private BukkitTask task;

  private Player player;

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    this.plugin = mock(MCAVSandbox.class);
    when(this.plugin.getDataFolder()).thenReturn(this.folder.toFile());
    this.streams = this.folder.resolve(Mcv2PlayCommand.STREAMS);
    this.support = mock(Mcv2Support.class);
    this.viewers = mock(Mcv2Viewers.class);
    when(this.support.offer(any(), any())).thenReturn(this.viewers);
    when(this.plugin.getMcv2Support()).thenReturn(this.support);
    this.command = new Mcv2PlayCommand(this.plugin);
    this.player = mock(Player.class);
    when(this.player.getUniqueId()).thenReturn(UUID.randomUUID());
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
  void playsAStreamOnTheWallUntilStopped() throws IOException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, "5x3", 20, 2, file.toString());
      final Mcv2Channel channel = channels.constructed().getFirst();
      verify(channel).open();
      final ArgumentCaptor<Mcv2Configuration> configurations = ArgumentCaptor.forClass(Mcv2Configuration.class);
      verify(this.support).offer(configurations.capture(), eq(List.of(this.player)));
      assertEquals(32, configurations.getValue().getVideoWidth());
      assertEquals(32, configurations.getValue().getVideoHeight());
      final ArgumentCaptor<Runnable> playbacks = ArgumentCaptor.forClass(Runnable.class);
      verify(TestServer.scheduler()).runTaskTimerAsynchronously(eq(this.plugin), playbacks.capture(), eq(0L), eq(2L));
      assertInstanceOf(Mcv2Playback.class, playbacks.getValue());
      verify(this.sender).sendMessage(Message.MCV2_PLAY.build());
      // a new stream replaces the one before
      this.command.play(this.sender, this.selector, "5x3", 20, 2, file.toString());
      verify(this.task).cancel();
      verify(channel).close();
      this.command.stop(this.sender);
      verify(channels.constructed().getLast()).close();
      verify(this.sender).sendMessage(Message.MCV2_STOP.build());
      // the pack stays served for the next stream
      verify(this.support, never()).close();
    }
  }

  @Test
  void streamsAtAFrameRateOffTheServerTick() throws IOException, InterruptedException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (
      MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class, (channel, context) ->
        when(channel.getRecipients()).thenReturn(Set.of(UUID.randomUUID()))
      )
    ) {
      this.command.stream(this.sender, this.selector, "5x3", 20, 100, file.toString());
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
      verify(this.sender, Mockito.times(3)).sendMessage(any(Component.class));
    }
  }

  @Test
  void refusesWhatItCannotPlay() throws IOException {
    this.command.play(this.sender, this.selector, "65x1", 20, 2, "anything");
    this.command.play(this.sender, this.selector, "5x3", 20, 2, this.streams.resolve("missing.mcs").toString());
    this.command.play(this.sender, this.selector, "5x3", 20, 2, this.archive(List.of(new byte[48])).toString());
    // the wall size, the missing file and the frame that is not MCV2 are each reported
    verify(this.sender, Mockito.times(3)).sendMessage(any(Component.class));
    this.command.play(this.sender, this.selector, "5x3", 21, 2, this.archive(Mcv2PlaybackTest.stream()).toString());
    verify(this.sender).sendMessage(Message.MCV2_SCREEN_ERROR.build(21));
    verify(this.support, never()).offer(any(), any());
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
    this.command.encode(this.sender, "clip.mp4", "clip.mcs", "16x16", Mcv2Profile.LIVE);
    final List<String> told = this.finish();
    assertEquals(List.of("clip.mp4 16x16"), opened);
    assertEquals(2, told.size());
    final int threads = EncoderPool.shared().getThreads();
    assertEquals(
      text(
        Message.MCV2_ENCODE_START.build("clip.mp4 into " + output + " at 16x16 with the live profile, on " + threads + " encoder threads")
      ),
      told.getFirst()
    );
    assertTrue(told.get(1).startsWith("MCV2 encode finished: 3 frames (1 keyframes) into " + output), told.get(1));
    assertTrue(frames.closed);
    assertEquals(3, Mcv2PlayCommand.read(output).size());
    assertFalse(Files.exists(this.streams.resolve("clip.mcs.part")));
    // the encode is over: the next one may start, and cancelling after it finished finds nothing to stop
    this.command.setOpener((_, _, _) -> new SolidFrames(1, new CountDownLatch(0)));
    this.command.encode(this.sender, "next.mp4", "next.mcs", "16x16", Mcv2Profile.LIVE);
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
      Mcv2Profile.LIVE,
      EncoderPool.shared(),
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
      Mcv2Profile.LIVE,
      EncoderPool.shared(),
      0
    );
    Mcv2PlayCommand.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(1, new CountDownLatch(0)),
      Path.of("c.mp4"),
      this.folder.resolve("missing").resolve("out.mcs"),
      16,
      16,
      Mcv2Profile.LIVE,
      EncoderPool.shared(),
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
      Mcv2Profile.LIVE,
      EncoderPool.shared(),
      0
    );
    final List<String> failures = this.finish();
    assertEquals(text(Message.MCV2_ENCODE_ERROR.build("no such video")), failures.getFirst());
    assertTrue(failures.get(1).startsWith("The MCV2 encode failed: "), failures.get(1));
    assertTrue(failures.get(2).startsWith("The MCV2 encode failed: "), failures.get(2));
    assertEquals(3, failures.size());
    assertTrue(Files.isDirectory(this.folder.resolve("blocked.mcs.part")));
  }

  @Test
  void runsOneEncodeAtATimeAndStopsItWhenAsked() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final SolidFrames frames = new SolidFrames(1000, release);
    this.command.setOpener((_, _, _) -> frames);
    final Path output = this.streams.resolve("long.mcs");
    this.command.encode(this.sender, "long.mp4", "long.mcs", "16x16", Mcv2Profile.LIVE);
    final Thread thread = this.command.getEncoding();
    // a second encode while the first runs is refused
    this.command.encode(this.sender, "other.mp4", "other.mcs", "16x16", Mcv2Profile.LIVE);
    verify(this.sender).sendMessage(Message.MCV2_ENCODE_BUSY.build());
    this.command.cancel(this.sender);
    assertNull(this.command.getEncoding());
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
    this.command.encode(this.sender, "clip.mp4", "long.mcs", "big", Mcv2Profile.LIVE);
    assertNull(this.command.getEncoding());
  }

  @Test
  void stopsTheStreamWhenThePluginStops() throws IOException {
    final Path file = this.archive(Mcv2PlaybackTest.stream());
    try (MockedConstruction<Mcv2Channel> channels = Mockito.mockConstruction(Mcv2Channel.class)) {
      this.command.play(this.sender, this.selector, "5x3", 20, 2, file.toString());
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
      Mcv2Profile.LIVE,
      EncoderPool.shared(),
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
    final long started = System.nanoTime();
    Mcv2PlayCommand.encodeFile(
      this.sender,
      (_, _, _) -> new SolidFrames(3, new CountDownLatch(0)),
      Path.of("f.mp4"),
      output,
      320,
      180,
      Mcv2Profile.LIVE,
      EncoderPool.shared(),
      0
    );
    final double took = (System.nanoTime() - started) / 1e6;
    final List<String> told = this.finish();
    final Matcher progress = Pattern.compile("MCV2 encode: (\\d+) frames, (\\d+) ms per frame").matcher(told.get(2));
    assertTrue(progress.find(), told.get(2));
    assertEquals(3, Integer.parseInt(progress.group(1)));
    assertTrue(Integer.parseInt(progress.group(2)) <= took / 3 + 0.5, told.get(2) + " in " + took + " ms");
    final Matcher done = Pattern.compile(" in (\\d+) s, ").matcher(told.getLast());
    assertTrue(done.find(), told.getLast());
    assertTrue(Integer.parseInt(done.group(1)) <= took / 1000 + 0.5, told.getLast() + " in " + took + " ms");
  }

  @Test
  void stopsTheEncodeWhenThePluginStops() throws Exception {
    final SolidFrames frames = new SolidFrames(1000, new CountDownLatch(1));
    this.command.setOpener((_, _, _) -> frames);
    this.command.encode(this.sender, "long.mp4", "long.mcs", "16x16", Mcv2Profile.LIVE);
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
    this.command.stream(this.sender, this.selector, "5x3", 20, 60, "../stream.mcs");
    verify(this.support, never()).offer(any(), any());
    this.command.encode(this.sender, "clip.mp4", "../../escape.mcs", "16x16", Mcv2Profile.LIVE);
    assertNull(this.command.getEncoding());
    final List<String> told = this.finish();
    assertEquals(3, told.size());
    final String refusal = "Stream files are kept in " + folder + ", and ../../escape.mcs is not a file in it";
    assertEquals(text(Message.MCV2_ENCODE_ERROR.build(refusal)), told.get(2));
    assertFalse(Files.exists(this.folder.getParent().resolve("escape.mcs")));
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
    assertEquals(1, Mcv2PlayCommand.read(Files.write(this.folder.resolve("d.mcs"), new byte[] { 1, 0, 0, 0, 7 }), 5).size());
    // after a first frame: three bytes are no length, a length past the end no frame
    final Path shortTail = Files.write(this.folder.resolve("e.mcs"), new byte[] { 1, 0, 0, 0, 7, 1, 0, 0 });
    assertEquals("Truncated frame length", assertThrows(IOException.class, () -> Mcv2PlayCommand.read(shortTail)).getMessage());
    final Path longTail = Files.write(this.folder.resolve("f.mcs"), new byte[] { 1, 0, 0, 0, 7, 9, 0, 0, 0, 1 });
    assertEquals("Truncated frame", assertThrows(IOException.class, () -> Mcv2PlayCommand.read(longTail)).getMessage());
    // four bytes are a length: an empty frame
    final List<byte[]> empties = Mcv2PlayCommand.read(Files.write(this.folder.resolve("g.mcs"), new byte[] { 0, 0, 0, 0 }));
    assertEquals(1, empties.size());
    assertEquals(0, empties.getFirst().length);
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
