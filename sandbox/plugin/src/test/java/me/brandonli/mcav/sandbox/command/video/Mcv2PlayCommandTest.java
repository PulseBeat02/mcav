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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.TestServer;
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
    final Path file = Files.createTempFile(this.folder, "stream", ".mcs");
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
      verify(this.support, Mockito.times(3)).close();
    }
  }

  @Test
  void refusesWhatItCannotPlay() throws IOException {
    this.command.play(this.sender, this.selector, "65x1", 20, 2, "anything");
    this.command.play(this.sender, this.selector, "5x3", 20, 2, this.folder.resolve("missing.mcs").toString());
    this.command.play(this.sender, this.selector, "5x3", 20, 2, this.archive(List.of(new byte[48])).toString());
    // the wall size, the missing file and the frame that is not MCV2 are each reported
    verify(this.sender, Mockito.times(3)).sendMessage(any(net.kyori.adventure.text.Component.class));
    this.command.play(this.sender, this.selector, "5x3", 21, 2, this.archive(Mcv2PlaybackTest.stream()).toString());
    verify(this.sender).sendMessage(Message.MCV2_SCREEN_ERROR.build(21));
    verify(this.support, never()).offer(any(), any());
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
    assertThrows(NullPointerException.class, () -> new Mcv2PlayCommand(null));
  }
}
