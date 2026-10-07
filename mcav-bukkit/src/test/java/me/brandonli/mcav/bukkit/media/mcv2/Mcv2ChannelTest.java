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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.bukkit.media.mcv2.encode.FrameWriter;
import me.brandonli.mcav.bukkit.media.mcv2.encode.TreeNode;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
  static byte[] frame(final long id, final long reference, final boolean isKeyframe) {
    final TreeNode root = isKeyframe
      ? TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[] { 1, 2, 3 })
      : TreeNode.leaf(Mcv2Format.MODE_MOTION, 0, new byte[] { 1, 1 });
    return FrameWriter.write(32, 32, id, reference, isKeyframe, 0, 0, List.of(root), FrameWriter.Options.production(false));
  }

  /** A keyframe of two pages: 64 roots of 192-byte intra grids. */
  static byte[] large() {
    final List<TreeNode> roots = new ArrayList<>();
    for (int index = 0; index < 64; index++) {
      final byte[] grid = new byte[192];
      grid[0] = (byte) index;
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
  void unloadingThePackHidesAnAlreadyShownScreen() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    when(this.viewers.isLoaded(LOADED)).thenReturn(false);
    channel.update();
    channel.update();
    this.server.runTasks();
    verify(this.screen).hide(this.player);
    assertEquals(Map.of(), channel.getLinks());
    assertEquals(Set.of(), channel.getRecipients());
    when(this.viewers.isLoaded(LOADED)).thenReturn(true);
    channel.update();
    this.server.runTasks();
    verify(this.screen, times(2)).show(this.player);
    channel.update();
    assertEquals(Set.of(LOADED), channel.getRecipients());
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1, 2 })
  void aViewerRetiredDuringShowDoesNotKeepALink(final int retirement) {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    doAnswer(_ -> {
      if (retirement == 2) {
        this.configuration.getViewers().remove(LOADED);
      } else {
        when(this.viewers.isLoaded(LOADED)).thenReturn(false);
      }
      if (retirement == 0) {
        channel.update();
      }
      return null;
    })
      .when(this.screen)
      .show(this.player);
    channel.update();
    this.server.runTasks();
    assertEquals(Map.of(), channel.getLinks(), "a retired show cannot publish a receiving link");
    verify(this.screen).hide(this.player);
  }

  @Test
  void aConcurrentCloseOwnsTheCleanupOfAPublishedLink() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    final AtomicInteger loadedChecks = new AtomicInteger();
    when(this.viewers.isLoaded(LOADED)).thenAnswer(_ -> {
      if (loadedChecks.getAndIncrement() == 0) {
        return true;
      }
      channel.close();
      return false;
    });
    this.server.runTasks();
    assertEquals(2, loadedChecks.get(), "close overlaps the check after link publication");
    assertEquals(Map.of(), channel.getLinks());
    assertEquals(Set.of(), channel.getRecipients());
    verify(this.screen).show(this.player);
    verify(this.screen).remove();
    verify(this.screen, never()).hide(this.player);
    assertFalse(channel.takeKeyframeRequest(), "a retired show must not request another frame");
  }

  @Test
  void aViewerRemovedFromTheScreenIsShownItNoMore() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    assertEquals(Set.of(LOADED), channel.getRecipients());
    this.configuration.getViewers().remove(LOADED);
    assertEquals(Set.of(WITHOUT, OFFLINE), channel.update());
    assertEquals(Set.of(), channel.getRecipients());
    assertEquals(Map.of(), channel.getLinks(), "the link is retired");
    this.server.runTasks();
    verify(this.screen).hide(this.player);
  }

  @Test
  void aRemovedViewerWhoLeftTheServerHasNothingToHide() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    this.configuration.getViewers().remove(LOADED);
    channel.update();
    this.server.removePlayer(LOADED);
    this.server.runTasks();
    verify(this.screen, never()).hide(this.player);
    assertEquals(Map.of(), channel.getLinks());
  }

  @Test
  void everyViewerRemovedWhileTheScreenClosesIsHidden() {
    final UUID second = UUID.fromString("00000000-0000-0000-0000-000000000034");
    final CraftPlayer secondPlayer = this.server.addPlayer(second);
    when(this.viewers.isLoaded(second)).thenReturn(true);
    this.configuration.getViewers().add(second);
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    assertEquals(Set.of(LOADED, second), channel.getLinks().keySet());
    this.configuration.getViewers().removeAll(List.of(LOADED, second));
    // the main thread closes the screen while the video's thread retires both viewers: the close clears the link of
    // whichever viewer the update reaches second
    final List<Runnable> hides = new ArrayList<>();
    final AtomicBoolean closed = new AtomicBoolean();
    doAnswer(invocation -> {
      if (closed.compareAndSet(false, true)) {
        channel.close();
      }
      hides.add(invocation.getArgument(1));
      return mock(BukkitTask.class);
    })
      .when(this.server.getScheduler())
      .runTask(any(Plugin.class), any(Runnable.class));
    channel.update();
    hides.forEach(Runnable::run);
    // each keeps the screen's team otherwise
    verify(this.screen).hide(this.player);
    verify(this.screen).hide(secondPlayer);
  }

  @Test
  void aViewerAddedBackBeforeTheScreenIsHiddenKeepsIt() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    final Collection<UUID> selected = this.configuration.getViewers();
    channel.update();
    this.server.runTasks();
    selected.remove(LOADED);
    channel.update();
    selected.add(LOADED);
    channel.update();
    this.server.runTasks();
    verify(this.screen, never()).hide(this.player);
    verify(this.screen, times(2)).show(this.player);
    assertEquals(Set.of(LOADED), channel.getLinks().keySet());
  }

  @Test
  void aViewerRemovedBeforeTheScreenIsShownIsNotShownIt() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.configuration.getViewers().remove(LOADED);
    this.server.runTasks();
    verify(this.screen, never()).show(this.player);
    assertEquals(Map.of(), channel.getLinks());
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
      .unsentLimit(0)
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
  void recordsEverySendForTheFlightRecorder(@TempDir final Path directory) throws Exception {
    final Mcv2Configuration tight = Mcv2Configuration.builder()
      .viewers(List.of(LOADED))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(1)
      .rows(1)
      .pageMap(500)
      .pageSlots(1)
      .backlogLimit(400)
      .build();
    final Mcv2Channel channel = new Mcv2Channel(tight, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    try (final Recording recording = new Recording()) {
      recording.enable("me.brandonli.mcav.Mcv2Send");
      recording.start();
      // sent, sent, held back by the backlog, then waiting for its reference, and one too large for the slot
      channel.send(frame(0, 0, true));
      channel.send(frame(1, 0, false));
      channel.send(frame(2, 1, false));
      this.server.completeWrites(LOADED);
      channel.send(frame(3, 2, false));
      channel.send(large());
      recording.stop();
      final Path file = directory.resolve("sends.jfr");
      recording.dump(file);
      final List<RecordedEvent> sends = RecordingFile.readAllEvents(file)
        .stream()
        .filter(event -> event.getEventType().getName().equals("me.brandonli.mcav.Mcv2Send"))
        .toList();
      assertEquals(5, sends.size());
      assertTrue(sends.get(0).getBoolean("keyframe"));
      assertEquals(1, sends.get(1).getInt("sentTo"));
      assertEquals(0, sends.get(1).getLong("referenceId"));
      assertEquals(1, sends.get(2).getInt("behind"));
      assertTrue(sends.get(2).getLong("backlog") > 400);
      assertEquals(1, sends.get(3).getInt("waiting"));
      assertEquals(-1, sends.get(4).getInt("colors"));
      assertTrue(sends.get(0).getLong("sent") > 0 && sends.get(0).getInt("bytes") > 48);
    }
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
  void sendsEachPageToItsOwnMap() {
    // a wall of two maps: a page frame hangs behind each, so it carries two page slots
    final Mcv2Configuration two = Mcv2Configuration.builder()
      .viewers(List.of(LOADED))
      .origin(new Location(mock(World.class), 0, 64, 0))
      .facing(BlockFace.SOUTH)
      .map(100)
      .columns(2)
      .rows(1)
      .pageMap(500)
      .pageSlots(2)
      .build();
    final Mcv2Channel channel = new Mcv2Channel(two, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    assertTrue(channel.send(large()) > 0);
    // the two pages on maps 500 and 501, then the keyframe's anchor
    final List<ClientboundMapItemDataPacket> sent = MapPackets.unbundle(this.server.getSentPackets(LOADED).getFirst());
    assertEquals(
      List.of(500, 501, 100),
      sent
        .stream()
        .map(packet -> packet.mapId().id())
        .toList()
    );
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
    channel.send(keyframe());
    assertEquals(Set.of(LOADED), channel.getLinks().keySet());
    channel.close();
    verify(this.screen).remove();
    assertEquals(Map.of(), channel.getLinks());
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

  /** A wall of one map at (0, 64, 0) in a world of its own, watched by the viewer with the pack at a view distance of 6. */
  private Mcv2Channel watchedFrom(final World world, final AtomicReference<Location> position) {
    return this.watchedFrom(world, position, BlockFace.SOUTH, 1, 1);
  }

  /** A wall of the given shape whose top-left block is at 0, 64, 0, watched by the viewer with the pack. */
  private Mcv2Channel watchedFrom(
    final World world,
    final AtomicReference<Location> position,
    final BlockFace facing,
    final int columns,
    final int rows
  ) {
    when(world.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000a0a0"));
    final Mcv2Configuration wall = Mcv2Configuration.builder()
      .viewers(List.of(LOADED))
      .origin(new Location(world, 0, 64, 0))
      .facing(facing)
      .map(100)
      .columns(columns)
      .rows(rows)
      .pageMap(500)
      .pageSlots(1)
      .build();
    when(this.player.getViewDistance()).thenReturn(6);
    when(this.player.getWorld()).thenAnswer(_ -> position.get().getWorld());
    when(this.player.getLocation()).thenAnswer(_ -> position.get());
    return new Mcv2Channel(wall, this.viewers, this.screen);
  }

  /** Lets a second pass on the main thread measure the distances, show the screen and sort the viewers again. */
  private void tick(final Mcv2Channel channel) {
    this.server.runTasks();
    channel.update();
    this.server.runTasks();
    channel.update();
  }

  @Test
  void aViewerOutOfSightOfTheWallGetsNoFramesUntilTheyComeBack() {
    final World world = mock(World.class);
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, 400, 64, 0));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    this.tick(channel);
    assertEquals(Set.of(), channel.update(), "too far for the dithered maps as well");
    channel.takeKeyframeRequest();
    assertTrue(channel.send(keyframe()) > 0);
    assertEquals(List.of(), this.server.getSentPackets(LOADED), "400 blocks away with a view distance of 6 chunks");
    // the viewer walks up to the wall: shown the screen anew, they start on a keyframe
    position.set(new Location(world, 0, 64, 4));
    this.tick(channel);
    assertTrue(channel.takeKeyframeRequest());
    channel.send(frame(1, 0, false));
    assertEquals(List.of(), this.server.getSentPackets(LOADED), "a P frame before the keyframe is useless to them");
    channel.send(frame(2, 2, true));
    assertEquals(1, this.server.getSentPackets(LOADED).size());
    channel.close();
  }

  @Test
  void aScheduledShowRechecksTheViewersDistance() {
    final World world = mock(World.class);
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, 0, 64, 4));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    try {
      channel.update();
      position.set(new Location(world, 400, 64, 0));
      channel.measureRange();
      this.server.runTasks();
      assertEquals(Map.of(), channel.getLinks());
      assertEquals(Set.of(), channel.getRecipients());
      verify(this.screen, never()).show(this.player);
      assertFalse(channel.takeKeyframeRequest(), "an out-of-range show must not request another frame");
    } finally {
      channel.close();
    }
  }

  @Test
  void aViewerInAnotherWorldGetsNoFrames() {
    final World world = mock(World.class);
    final World nether = mock(World.class);
    when(nether.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-00000000b0b0"));
    final AtomicReference<Location> position = new AtomicReference<>(new Location(nether, 0, 64, 4));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    this.tick(channel);
    channel.send(keyframe());
    assertEquals(List.of(), this.server.getSentPackets(LOADED), "the same place in another world");
    channel.close();
  }

  @Test
  void aViewerAtTheEdgeOfTheirViewDistanceDoesNotComeAndGo() {
    final World world = mock(World.class);
    // 96 blocks from the wall's block: the six chunks of the view distance
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, 97, 64, 0));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    this.tick(channel);
    assertEquals(Set.of(LOADED), channel.getRecipients());
    // up to 32 blocks farther the viewer stays
    position.set(new Location(world, 129, 64, 0));
    this.tick(channel);
    assertEquals(Set.of(LOADED), channel.getRecipients());
    position.set(new Location(world, 130, 64, 0));
    this.tick(channel);
    assertEquals(Set.of(), channel.getRecipients());
    // and once gone, comes back only within the view distance
    position.set(new Location(world, 98, 64, 0));
    this.tick(channel);
    assertEquals(Set.of(), channel.getRecipients());
    position.set(new Location(world, 97, 64, 0));
    this.tick(channel);
    assertEquals(Set.of(LOADED), channel.getRecipients());
    channel.close();
  }

  /** Whether the viewer with the pack is sent the stream when they stand at a point, measured afresh. */
  private boolean receivesFrom(
    final BlockFace facing,
    final int columns,
    final int rows,
    final double east,
    final double height,
    final double south
  ) {
    final World world = mock(World.class);
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, east, height, south));
    final Mcv2Channel channel = this.watchedFrom(world, position, facing, columns, rows);
    channel.open();
    this.tick(channel);
    final boolean receiving = channel.getRecipients().contains(LOADED);
    channel.close();
    return receiving;
  }

  @Test
  void aWideWallReachesAsFarAsItsLastColumn() {
    // a view distance of 6 chunks and the margin of a viewer who has not left: 128 blocks from the nearest block
    assertTrue(this.receivesFrom(BlockFace.SOUTH, 5, 1, 133, 64.5, 0.5), "128 blocks east of the fifth column");
    assertTrue(this.receivesFrom(BlockFace.WEST, 5, 1, 0.5, 64.5, 133), "128 blocks south of the fifth column");
  }

  @Test
  void aTallWallReachesDownToItsLowestRow() {
    // the third row from the top is the block at y 62
    assertTrue(this.receivesFrom(BlockFace.SOUTH, 1, 3, 0.5, -66, 0.5), "128 blocks below the lowest row");
    assertFalse(this.receivesFrom(BlockFace.SOUTH, 1, 3, 0.5, -67, 0.5), "129 blocks below it");
  }

  @Test
  void aViewerOffTheWallOnTwoAxesIsAsFarAsBothTogether() {
    // 100 blocks off on each of two axes: 141 blocks away, though neither alone is out of reach
    assertFalse(this.receivesFrom(BlockFace.SOUTH, 1, 1, 101, 165, 0.5), "east and above");
    assertFalse(this.receivesFrom(BlockFace.SOUTH, 1, 1, 101, 64.5, 101), "east and south");
  }

  @Test
  void anOpenedChannelKnowsAtOnceWhoIsTooFar() {
    final World world = mock(World.class);
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, 400, 64, 0));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    // before the first measurement of the timer the viewer 400 blocks away is in neither group
    assertEquals(Set.of(), channel.update());
    assertEquals(1, this.server.getScheduledTaskCount(), "only the measurement: the screen is not shown to them");
    channel.close();
  }

  @Test
  void closingStopsMeasuringTheViewersDistances() {
    final World world = mock(World.class);
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, 0, 64, 4));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    assertEquals(1, this.server.getScheduledTaskCount(), "the measurement every second");
    channel.close();
    assertEquals(0, this.server.getScheduledTaskCount());
  }
}
