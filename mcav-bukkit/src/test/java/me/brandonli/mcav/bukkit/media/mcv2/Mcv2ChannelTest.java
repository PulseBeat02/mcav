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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.media.mcv2.encode.FrameWriter;
import me.brandonli.mcav.media.mcv2.encode.TreeNode;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class Mcv2ChannelTest {

  private static final UUID LOADED = UUID.fromString("00000000-0000-0000-0000-000000000031");
  private static final UUID WITHOUT = UUID.fromString("00000000-0000-0000-0000-000000000032");
  private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000033");

  private FakeServer server;
  private CraftPlayer player;
  private Mcv2Viewers viewers;
  private Mcv2Screen screen;
  private Mcv2Configuration configuration;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.player = this.server.addPlayer(LOADED);
    this.server.addPlayer(WITHOUT);
    this.server.injectModule();
    this.viewers = mock(Mcv2Viewers.class);
    when(this.viewers.isLoaded(LOADED)).thenReturn(true);
    when(this.viewers.isLoaded(OFFLINE)).thenReturn(true);
    this.screen = mock(Mcv2Screen.class);
    when(this.screen.anchors()).thenReturn(List.of(new MapTilePatch(100, 0, 0, 128, 1, new byte[128])));
    this.configuration = Mcv2Configuration.builder()
      .viewers(new ArrayList<>(List.of(LOADED, WITHOUT, OFFLINE)))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .pageMap(500)
      .build();
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  static byte[] keyframe() {
    return FrameWriter.write(
      32,
      32,
      0,
      0,
      true,
      0,
      0,
      List.of(TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[] { 1, 2, 3 })),
      FrameWriter.Options.production(false)
    );
  }

  static byte[] predicted() {
    return FrameWriter.write(
      32,
      32,
      1,
      0,
      false,
      0,
      0,
      List.of(TreeNode.leaf(Mcv2Format.MODE_MOTION, 0, new byte[] { 1, 1 })),
      FrameWriter.Options.production(false)
    );
  }

  /** A frame of the 32x32 test video: one solid root in a keyframe, one motion root in a P frame. */
  static byte[] frame(final long id, final long reference, final boolean keyframe) {
    final TreeNode root = keyframe
      ? TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[] { 1, 2, 3 })
      : TreeNode.leaf(Mcv2Format.MODE_MOTION, 0, new byte[] { 1, 1 });
    return FrameWriter.write(32, 32, id, reference, keyframe, 0, 0, List.of(root), FrameWriter.Options.production(false));
  }

  /** A keyframe of two pages: 64 roots of 192-byte intra grids. */
  static byte[] large() {
    final List<TreeNode> roots = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      final byte[] grid = new byte[192];
      grid[0] = (byte) i;
      roots.add(TreeNode.leaf(Mcv2Format.MODE_INTRA + 3, 0, grid));
    }
    return FrameWriter.write(256, 256, 0, 0, true, 0, 0, roots, FrameWriter.Options.production(false));
  }

  @Test
  void showsTheScreenBeforeAViewerReceivesFrames() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    assertSame(this.screen, channel.getScreen());
    assertEquals(Set.of(LOADED, WITHOUT, OFFLINE), channel.update());
    assertEquals(Set.of(), channel.getRecipients());
    // updating again does not show the screen twice
    channel.update();
    assertEquals(2, this.server.getScheduledTaskCount());
    this.server.runTasks();
    verify(this.screen).show(this.player);
    assertTrue(channel.takeKeyframeRequest());
    assertFalse(channel.takeKeyframeRequest());
    assertEquals(Set.of(WITHOUT, OFFLINE), channel.update());
    assertEquals(Set.of(LOADED), channel.getRecipients());
    // a viewer whose pack is gone is shown the dithered maps again, and would be shown the screen anew
    when(this.viewers.isLoaded(LOADED)).thenReturn(false);
    assertEquals(Set.of(LOADED, WITHOUT, OFFLINE), channel.update());
    assertEquals(Set.of(), channel.getRecipients());
    channel.show(LOADED);
    verify(this.screen).show(this.player);
    channel.requestKeyframe();
    assertTrue(channel.takeKeyframeRequest());
  }

  @Test
  void sendsPagesAndWithAKeyframeTheAnchors() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    final int colors = channel.send(keyframe());
    assertTrue(colors > 0 && colors % 128 == 0);
    final List<ClientboundMapItemDataPacket> first = MapPackets.unbundle(this.server.getSentPackets(LOADED).getFirst());
    assertEquals(2, first.size(), "the page, then the anchor");
    channel.send(predicted());
    final List<ClientboundMapItemDataPacket> second = MapPackets.unbundle(this.server.getSentPackets(LOADED).get(1));
    assertEquals(1, second.size());
    assertEquals(List.of(), this.server.getSentPackets(WITHOUT));
  }

  @Test
  void holdsBackAViewerWhoseConnectionFellBehind() throws Exception {
    final UUID other = UUID.fromString("00000000-0000-0000-0000-000000000034");
    final CraftPlayer second = this.server.addPlayer(other);
    PacketUtils.init();
    when(this.viewers.isLoaded(other)).thenReturn(true);
    // room for one page and its anchor, not for a second frame on top
    final Mcv2Configuration tight = Mcv2Configuration.builder()
      .viewers(List.of(LOADED, other))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .pageMap(500)
      .backlogLimit(400)
      .build();
    final Mcv2Channel channel = new Mcv2Channel(tight, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    verify(this.screen).show(second);
    channel.send(frame(0, 0, true));
    // the second viewer's connection writes everything at once; the first's writes nothing
    this.server.completeWrites(other);
    channel.send(frame(1, 0, false));
    this.server.completeWrites(other);
    channel.send(frame(2, 1, false));
    this.server.completeWrites(other);
    assertEquals(3, this.server.getSentPackets(other).size());
    assertEquals(2, this.server.getSentPackets(LOADED).size(), "the keyframe, and the frame the backlog still allowed");
    final Mcv2Link slow = channel.getLinks().get(LOADED);
    assertEquals(1, slow.getBehind());
    assertTrue(slow.getBacklog() > 400);
    // once it has written, frame 3 predicts from frame 2, which it missed: it waits for the next keyframe
    this.server.completeWrites(LOADED);
    assertEquals(0, slow.getBacklog());
    channel.send(frame(3, 2, false));
    assertEquals(2, this.server.getSentPackets(LOADED).size());
    assertEquals(1, slow.getUndecodable());
    channel.send(frame(4, 4, true));
    assertEquals(3, this.server.getSentPackets(LOADED).size());
    assertEquals(5, this.server.getSentPackets(other).size());
    assertEquals(0, channel.getLinks().get(other).getBehind());
  }

  @Test
  void forgetsTheBacklogOfAViewerWhoLeft() throws Exception {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    this.server.removePlayer(LOADED);
    PacketUtils.init();
    channel.send(frame(0, 0, true));
    // nothing reached a connection, so nothing waits to be written
    assertEquals(0, channel.getLinks().get(LOADED).getBacklog());
    assertEquals(1, channel.getLinks().get(LOADED).getSent());
    assertEquals(0, this.server.completeWrites(LOADED));
    // a viewer whose pack is gone loses its link
    when(this.viewers.isLoaded(LOADED)).thenReturn(false);
    channel.update();
    assertEquals(Map.of(), channel.getLinks());
  }

  @Test
  void dropsAFrameWithMorePagesThanSlots() {
    final Mcv2Configuration narrow = Mcv2Configuration.builder()
      .viewers(List.of(LOADED))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .pageMap(500)
      .pageSlots(1)
      .build();
    final Mcv2Channel channel = new Mcv2Channel(narrow, this.viewers, this.screen);
    assertEquals(-1, channel.send(large()));
    assertTrue(channel.takeKeyframeRequest());
    assertThrows(IllegalArgumentException.class, () -> channel.send(new byte[48]));
    assertThrows(NullPointerException.class, () -> channel.send(null));
  }

  @Test
  void opensAndClosesTheScreen() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.open();
    verify(this.screen).build();
    channel.update();
    this.server.runTasks();
    channel.update();
    channel.close();
    verify(this.screen).remove();
    assertEquals(Set.of(), channel.getRecipients());
    // a show that was scheduled before the close does nothing
    channel.show(LOADED);
    verify(this.screen).show(this.player);
    assertThrows(NullPointerException.class, () -> new Mcv2Channel(null, this.viewers));
    assertThrows(NullPointerException.class, () -> new Mcv2Channel(this.configuration, null));
    assertThrows(NullPointerException.class, () -> new Mcv2Channel(this.configuration, this.viewers, null));
  }

  @Test
  void forgetsAViewerWhoLeftBeforeTheScreenWasShown() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    // the offline viewer's show found no player; the next update schedules it again
    channel.update();
    verify(this.screen).show(this.player);
    assertEquals(1, this.server.getScheduledTaskCount());
  }
}
