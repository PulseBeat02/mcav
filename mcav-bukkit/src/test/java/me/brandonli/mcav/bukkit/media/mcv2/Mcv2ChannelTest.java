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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.InvocationOnMock;

final class Mcv2ChannelTest {

  private static final UUID LOADED = UUID.fromString("00000000-0000-0000-0000-000000000031");

  private static final UUID WITHOUT = UUID.fromString("00000000-0000-0000-0000-000000000032");

  private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000033");

  private static final int WALL_MAP = 100;
  private static final int PAGE_MAP = 500;

  private static final int MAP_SIZE = 128;

  private static final MapTilePatch ANCHOR = MapTilePatch.of(WALL_MAP, 0, 0, MAP_SIZE, 1, new byte[MAP_SIZE]);

  private static final int FRAME_SIZE = 32;

  private static final int GROUND_LEVEL = 64;
  private static final double STANDING_LEVEL = 64.5;
  private static final double BLOCK_CENTER = 0.5;

  private static final int BACKLOG_LIMIT = 400;

  private static final int VIEW_DISTANCE = 6;

  private static final int SNAPSHOT_TICKS = 11;
  private static final int FIRST_DRAW_DELAY_TICKS = 120;
  private static final int LATER_TICKS = 600;

  private static final String SECOND_VIEWER_ID = "00000000-0000-0000-0000-000000000034";
  private static final String OVERWORLD_ID = "00000000-0000-0000-0000-00000000a0a0";
  private static final String NETHER_ID = "00000000-0000-0000-0000-00000000b0b0";

  private Set<UUID> pageTrackers;
  private Set<UUID> wallTrackers;

  private AtomicBoolean pageValid;
  private AtomicBoolean wallValid;

  private FakeServer server;

  private CraftPlayer player;

  private Mcv2Viewers viewers;

  private Mcv2Screen screen;

  private Mcv2Configuration configuration;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.pageTrackers = new HashSet<>();
    this.wallTrackers = new HashSet<>();
    this.pageValid = new AtomicBoolean(true);
    this.wallValid = new AtomicBoolean(true);
    this.server = FakeServer.start();
    this.player = this.server.addPlayer(LOADED);
    this.server.addPlayer(WITHOUT);
    this.server.injectModule();
    this.viewers = mock(Mcv2Viewers.class);
    when(this.viewers.isLoaded(LOADED)).thenReturn(true);
    when(this.viewers.isLoaded(OFFLINE)).thenReturn(true);
    this.screen = mock(Mcv2Screen.class);
    when(this.screen.getAnchors()).thenReturn(List.of(ANCHOR));
    this.configuration = Mcv2Configuration.builder()
      .viewers(new ArrayList<>(List.of(LOADED, WITHOUT, OFFLINE)))
      .origin(new Location(mock(World.class), 0, GROUND_LEVEL, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .pageMap(PAGE_MAP)
      .build();
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  static byte[] keyframe() {
    return Mcv2Trees.write(
      FRAME_SIZE,
      FRAME_SIZE,
      0,
      0,
      Mcv2FrameKind.KEYFRAME,
      List.of(Node.leaf(Mcv2Decoder.MODE_SOLID, 0, new byte[] { 1, 2, 3 }))
    );
  }

  private static byte[] predicted() {
    return Mcv2Trees.write(
      FRAME_SIZE,
      FRAME_SIZE,
      1,
      0,
      Mcv2FrameKind.PREDICTED,
      List.of(Node.leaf(Mcv2Decoder.MODE_MOTION, 0, new byte[] { 1, 1 }))
    );
  }

  static byte[] frame(final long id, final long reference, final Mcv2FrameKind kind) {
    final Node root =
      kind == Mcv2FrameKind.KEYFRAME
        ? Node.leaf(Mcv2Decoder.MODE_SOLID, 0, new byte[] { 1, 2, 3 })
        : Node.leaf(Mcv2Decoder.MODE_MOTION, 0, new byte[] { 1, 1 });
    return Mcv2Trees.write(32, 32, id, reference, kind, List.of(root));
  }

  static byte[] large() {
    return Mcv2Trees.twoPages();
  }

  @Test
  void shouldRequestNoKeyframeBeforeThePlayerTracksEveryFrame() {
    final Mcv2Channel channel = this.trackingChannel();
    final boolean requestedOnShow = channel.takeKeyframeRequest();
    assertFalse(requestedOnShow, "Showing a screen must defer its keyframe request until every frame is tracked!");

    this.advanceTracking(channel, FIRST_DRAW_DELAY_TICKS);
    final boolean requestedWithoutTracking = channel.takeKeyframeRequest();
    assertFalse(requestedWithoutTracking, "Elapsed time alone must not request a keyframe without frame tracking!");

    this.pageTrackers.add(LOADED);
    channel.checkTracking();
    this.advanceTracking(channel, FIRST_DRAW_DELAY_TICKS);
    final boolean requestedWithOnlyPages = channel.takeKeyframeRequest();
    assertFalse(requestedWithOnlyPages, "Tracking only the page frames must not request a keyframe before the wall frames!");

    this.pageTrackers.clear();
    this.wallTrackers.add(LOADED);
    channel.checkTracking();
    this.advanceTracking(channel, FIRST_DRAW_DELAY_TICKS);
    final boolean requestedWithOnlyWall = channel.takeKeyframeRequest();
    final List<ClientboundMapItemDataPacket> maps = this.mapPackets(LOADED);
    assertFalse(requestedWithOnlyWall, "Tracking only the wall frames must not request a keyframe before the page frames!");
    assertEquals(List.of(), maps, "Incomplete tracking must send no anchors!");
  }

  private Mcv2Channel trackingChannel() {
    this.prepareTrackingFrames();
    final int loadTick = Bukkit.getCurrentTick() - FIRST_DRAW_DELAY_TICKS;
    return this.shownTrackingChannel(loadTick);
  }

  private void prepareTrackingFrames() {
    final ItemFrame page = this.trackedFrame(this.pageTrackers, this.pageValid);
    final ItemFrame wall = this.trackedFrame(this.wallTrackers, this.wallValid);
    when(this.screen.getFrames()).thenReturn(List.of(page));
    when(this.screen.getWallFrames()).thenReturn(List.of(wall));
  }

  private ItemFrame trackedFrame(final Set<UUID> trackers, final AtomicBoolean valid) {
    final ItemFrame frame = mock(ItemFrame.class);
    when(frame.getUniqueId()).thenReturn(UUID.randomUUID());
    when(frame.isValid()).thenAnswer(_ -> valid.get());
    when(frame.isTrackedBy(any(Player.class))).thenAnswer(invocation -> this.isTrackedBy(trackers, invocation));

    return frame;
  }

  private boolean isTrackedBy(final Set<UUID> trackers, final InvocationOnMock invocation) {
    final Player viewer = invocation.getArgument(0);
    final UUID uuid = viewer.getUniqueId();
    return trackers.contains(uuid);
  }

  private Mcv2Channel shownTrackingChannel(final int loadTick) {
    when(this.viewers.findLoadTick(LOADED)).thenReturn(OptionalInt.of(loadTick));
    when(this.viewers.findLoadTick(WITHOUT)).thenReturn(OptionalInt.of(loadTick));
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    channel.checkTracking();

    return channel;
  }

  private void advanceTracking(final Mcv2Channel channel, final int ticks) {
    for (int tick = 0; tick < ticks; tick++) {
      this.server.advanceTick();
      channel.checkTracking();
    }
  }

  private List<ClientboundMapItemDataPacket> mapPackets(final UUID viewer) {
    final List<Packet<?>> packets = this.server.getSentPackets(viewer);
    return packets
      .stream()
      .flatMap(packet -> MapPackets.unbundle(packet).stream())
      .toList();
  }

  @Test
  void shouldOpenTheLinkOneSnapshotAfterTheLastFrameIsTracked() {
    when(this.viewers.isLoaded(WITHOUT)).thenReturn(true);
    this.pageTrackers.add(LOADED);
    final Mcv2Channel channel = this.trackingChannel();
    this.advanceTracking(channel, FIRST_DRAW_DELAY_TICKS);
    this.wallTrackers.add(LOADED);
    channel.checkTracking();
    this.advanceTracking(channel, SNAPSHOT_TICKS - 1);
    final List<ClientboundMapItemDataPacket> beforeOpening = this.mapPackets(LOADED);
    final boolean requestedAtTen = channel.takeKeyframeRequest();
    assertEquals(List.of(), beforeOpening, "Anchors must wait through tick 10 after the last frame is first seen tracked!");
    assertFalse(requestedAtTen, "A keyframe must not be requested at tick 10 of the snapshot gate!");
    this.assertClosed(channel, LOADED);

    this.advanceTracking(channel, 1);
    this.assertAnchorCount(LOADED, 1);
    final List<ClientboundMapItemDataPacket> otherViewer = this.mapPackets(WITHOUT);
    final boolean requestedAtEleven = channel.takeKeyframeRequest();
    final boolean requestedAgain = channel.takeKeyframeRequest();
    assertEquals(List.of(), otherViewer, "Opening one link must send anchors only to that viewer!");
    assertTrue(requestedAtEleven, "Tick 11 after complete tracking must open the link and request its keyframe!");
    assertFalse(requestedAgain, "Opening a link must request exactly one keyframe!");
  }

  private void assertClosed(final Mcv2Channel channel, final UUID viewer) {
    final Map<UUID, Mcv2Link> links = channel.getLinks();
    final Mcv2Link link = assertInstanceOf(Mcv2Link.class, links.get(viewer), "Showing the viewer must install its closed link!");
    final boolean keyframe = link.offer(0, 0, Mcv2FrameKind.KEYFRAME, FRAME_SIZE);
    final boolean predicted = link.offer(1, 0, Mcv2FrameKind.PREDICTED, FRAME_SIZE);
    assertFalse(keyframe, "A link must admit no keyframe before both opening gates pass!");
    assertFalse(predicted, "A closed link must admit no P frame either!");
  }

  private void assertAnchorCount(final UUID viewer, final int expected) {
    final List<ClientboundMapItemDataPacket> maps = this.mapPackets(viewer);
    final List<Integer> mapIds = maps
      .stream()
      .map(packet -> packet.mapId().id())
      .toList();
    final List<Integer> expectedMapIds = Collections.nCopies(expected, WALL_MAP);
    assertEquals(expectedMapIds, mapIds, "Every link opening must send exactly its anchors to that viewer!");
    for (final ClientboundMapItemDataPacket map : maps) {
      MapPackets.assertMapPacket(map, ANCHOR);
    }
  }

  @Test
  void shouldOpenTheLinkOnlyAFirstDrawDelayAfterThePackLoaded() {
    this.prepareTrackingFrames();
    this.pageTrackers.add(LOADED);
    this.wallTrackers.add(LOADED);
    final int loadTick = Bukkit.getCurrentTick();
    final Mcv2Channel channel = this.shownTrackingChannel(loadTick);
    this.advanceTracking(channel, FIRST_DRAW_DELAY_TICKS - 1);
    final List<ClientboundMapItemDataPacket> beforeOpening = this.mapPackets(LOADED);
    final boolean requestedAtOneHundredNineteen = channel.takeKeyframeRequest();
    assertEquals(List.of(), beforeOpening, "Anchors must wait through tick 119 after the pack loaded even with old tracking!");
    assertFalse(requestedAtOneHundredNineteen, "The first-draw gate must defer the request through tick 119 after load!");
    this.assertClosed(channel, LOADED);

    this.advanceTracking(channel, 1);
    this.assertAnchorCount(LOADED, 1);
    final boolean requestedAtOneHundredTwenty = channel.takeKeyframeRequest();
    final boolean requestedAgain = channel.takeKeyframeRequest();
    assertTrue(requestedAtOneHundredTwenty, "Tick 120 after pack load must open the link when the snapshot gate already passed!");
    assertFalse(requestedAgain, "Passing the first-draw gate must request only one keyframe!");
  }

  @Test
  void shouldAdmitNoFrameToAClosedLink() {
    when(this.viewers.isLoaded(WITHOUT)).thenReturn(true);
    final Mcv2Channel channel = this.trackingChannel();
    channel.send(keyframe());
    channel.send(predicted());
    final List<ClientboundMapItemDataPacket> closedViewer = this.mapPackets(WITHOUT);
    final List<ClientboundMapItemDataPacket> closedFirstViewer = this.mapPackets(LOADED);
    assertEquals(List.of(), closedViewer, "A newly shown closed link must receive neither a keyframe nor a P frame!");
    assertEquals(List.of(), closedFirstViewer, "Every fresh link must stay closed until its own gates pass!");

    this.pageTrackers.add(LOADED);
    this.wallTrackers.add(LOADED);
    channel.checkTracking();
    this.advanceTracking(channel, SNAPSHOT_TICKS);
    final boolean requestedForFirstViewer = channel.takeKeyframeRequest();
    assertTrue(requestedForFirstViewer, "Opening the first viewer must request its keyframe!");

    channel.send(frame(2, 2, Mcv2FrameKind.KEYFRAME));
    channel.send(frame(3, 2, Mcv2FrameKind.PREDICTED));
    final List<ClientboundMapItemDataPacket> otherViewersFrames = this.mapPackets(WITHOUT);
    final List<ClientboundMapItemDataPacket> openedViewer = this.mapPackets(LOADED);
    final boolean openedViewerReceivedVideo = openedViewer.stream().anyMatch(packet -> packet.mapId().id() == PAGE_MAP);
    assertEquals(List.of(), otherViewersFrames, "Another viewer's keyframe must never enter a still-closed link!");
    assertTrue(openedViewerReceivedVideo, "The opened viewer must receive video while the other link remains closed!");
    this.assertClosed(channel, WITHOUT);
  }

  @Test
  void shouldCloseALinkWhoseFramesAreNoLongerTracked() {
    this.pageTrackers.add(LOADED);
    this.wallTrackers.add(LOADED);
    final Mcv2Channel channel = this.trackingChannel();
    this.advanceTracking(channel, SNAPSHOT_TICKS);
    this.assertAnchorCount(LOADED, 1);
    final boolean firstRequest = channel.takeKeyframeRequest();
    assertTrue(firstRequest, "The first opening must request one keyframe!");

    this.wallTrackers.remove(LOADED);
    channel.checkTracking();
    this.assertClosed(channel, LOADED);
    final int reloadTick = Bukkit.getCurrentTick();
    when(this.viewers.findLoadTick(LOADED)).thenReturn(OptionalInt.of(reloadTick));
    this.wallTrackers.add(LOADED);
    channel.checkTracking();
    this.advanceTracking(channel, SNAPSHOT_TICKS);
    final boolean requestedBeforeReloadDelay = channel.takeKeyframeRequest();
    assertFalse(requestedBeforeReloadDelay, "Tracking again must not reopen a link before the new pack's first-draw delay!");
    this.assertAnchorCount(LOADED, 1);
    this.assertClosed(channel, LOADED);

    this.advanceTracking(channel, FIRST_DRAW_DELAY_TICKS - SNAPSHOT_TICKS - 1);
    this.wallTrackers.remove(LOADED);
    channel.checkTracking();
    this.wallTrackers.add(LOADED);
    channel.checkTracking();
    this.advanceTracking(channel, SNAPSHOT_TICKS - 1);
    final boolean requestedBeforeNewSnapshot = channel.takeKeyframeRequest();
    assertFalse(requestedBeforeNewSnapshot, "Even after the pack gate passes, retracking must restart the full snapshot delay!");
    this.assertAnchorCount(LOADED, 1);
    this.assertClosed(channel, LOADED);

    this.advanceTracking(channel, 1);
    this.assertAnchorCount(LOADED, 2);
    final boolean reopenedRequest = channel.takeKeyframeRequest();
    final boolean repeatedRequest = channel.takeKeyframeRequest();
    assertTrue(reopenedRequest, "Passing both gates again must reopen with fresh anchors and a keyframe request!");
    assertFalse(repeatedRequest, "Reopening must request exactly one keyframe!");

    final Map<UUID, Mcv2Link> reopenedLinks = channel.getLinks();
    final Mcv2Link reopenedLink = assertInstanceOf(Mcv2Link.class, reopenedLinks.get(LOADED), "Retracking must retain the viewer's link!");
    final boolean admittedAfterReopening = reopenedLink.offer(2, 2, Mcv2FrameKind.KEYFRAME, FRAME_SIZE);
    assertTrue(admittedAfterReopening, "Passing both gates again must actually admit a keyframe to the reopened link!");
  }

  @Test
  void shouldOpenTheLinkWhileAFrameIsNoLongerValid() {
    this.pageTrackers.add(LOADED);
    this.wallValid.set(false);
    final Mcv2Channel channel = this.trackingChannel();
    this.advanceTracking(channel, SNAPSHOT_TICKS - 1);
    final List<ClientboundMapItemDataPacket> beforeOpening = this.mapPackets(LOADED);
    final boolean requestedAtTen = channel.takeKeyframeRequest();
    assertEquals(List.of(), beforeOpening, "Skipping an invalid frame must still defer anchors through snapshot tick 10!");
    assertFalse(requestedAtTen, "Skipping an invalid frame must still defer the request through snapshot tick 10!");
    this.assertClosed(channel, LOADED);

    this.advanceTracking(channel, 1);
    this.assertAnchorCount(LOADED, 1);
    final boolean requestedAtEleven = channel.takeKeyframeRequest();
    final boolean requestedAgain = channel.takeKeyframeRequest();
    assertTrue(requestedAtEleven, "An invalid untracked wall frame must not prevent valid tracked frames from opening at tick 11!");
    assertFalse(requestedAgain, "Opening past an invalid frame must request only one keyframe!");
  }

  @Test
  void shouldRequestOneKeyframeForEachOpenedLink() throws Exception {
    when(this.viewers.isLoaded(WITHOUT)).thenReturn(true);
    final Mcv2Channel channel = this.trackingChannel();
    final PrerecordedPlayback playback = new PrerecordedPlayback(channel, List.of(keyframe(), predicted()));
    playback.run();
    final int beforeFirstOpening = playback.getRequests();
    assertEquals(0, beforeFirstOpening, "Prerecorded playback must receive no restart request before the first link opens!");

    this.pageTrackers.add(LOADED);
    this.wallTrackers.add(LOADED);
    channel.checkTracking();
    this.advancePlayback(channel, playback, SNAPSHOT_TICKS - 1);
    final int beforeFirstSnapshot = playback.getRequests();
    assertEquals(0, beforeFirstSnapshot, "Prerecorded playback must receive no request through the first viewer's snapshot tick 10!");

    this.advancePlayback(channel, playback, 1);
    final int afterFirstOpening = playback.getRequests();
    assertEquals(1, afterFirstOpening, "The first opened link must request exactly one prerecorded playback restart!");

    this.pageTrackers.add(WITHOUT);
    this.wallTrackers.add(WITHOUT);
    channel.checkTracking();
    this.advancePlayback(channel, playback, SNAPSHOT_TICKS - 1);
    final int beforeSecondOpening = playback.getRequests();
    assertEquals(1, beforeSecondOpening, "Waiting for the second viewer must not repeat the first viewer's request!");

    this.advancePlayback(channel, playback, 1);
    final int afterSecondOpening = playback.getRequests();
    assertEquals(2, afterSecondOpening, "The second opened link must add exactly one prerecorded playback restart request!");

    this.advancePlayback(channel, playback, LATER_TICKS);
    final int manyTicksLater = playback.getRequests();
    assertEquals(2, manyTicksLater, "Open links must never repeat requests that restart shared prerecorded playback!");
  }

  private void advancePlayback(final Mcv2Channel channel, final PrerecordedPlayback playback, final int ticks) throws Exception {
    for (int tick = 0; tick < ticks; tick++) {
      this.server.advanceTick();
      channel.checkTracking();
      playback.run();
      this.server.completeWrites(LOADED);
      this.server.completeWrites(WITHOUT);
    }
  }

  @Test
  void shouldShowTheScreenBeforeAViewerReceivesFrames() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    final Mcv2Screen shownScreen = channel.getScreen();
    assertSame(this.screen, shownScreen);

    final Set<UUID> expectedDithered = Set.of(LOADED, WITHOUT, OFFLINE);
    final Set<UUID> dithered = channel.update();
    assertEquals(expectedDithered, dithered);

    final Set<UUID> recipients = channel.getRecipients();
    assertEquals(Set.of(), recipients);

    // updating again does not show the screen twice
    channel.update();
    final int scheduledTasks = this.server.getScheduledTaskCount();
    assertEquals(2, scheduledTasks);

    this.server.runTasks();
    verify(this.screen).show(this.player);

    channel.open();
    this.openViewerLinks(LOADED);
    final boolean keyframeRequested = channel.takeKeyframeRequest();
    assertTrue(keyframeRequested);

    final boolean keyframeRequestedAgain = channel.takeKeyframeRequest();
    assertFalse(keyframeRequestedAgain);

    final Set<UUID> expectedDitheredWhenShown = Set.of(WITHOUT, OFFLINE);
    final Set<UUID> ditheredWhenShown = channel.update();
    assertEquals(expectedDitheredWhenShown, ditheredWhenShown);

    final Set<UUID> expectedRecipientsWhenShown = Set.of(LOADED);
    final Set<UUID> recipientsWhenShown = channel.getRecipients();
    assertEquals(expectedRecipientsWhenShown, recipientsWhenShown);

    when(this.viewers.isLoaded(LOADED)).thenReturn(false);
    final Set<UUID> expectedDitheredWhenUnloaded = Set.of(LOADED, WITHOUT, OFFLINE);
    final Set<UUID> ditheredWhenUnloaded = channel.update();
    assertEquals(expectedDitheredWhenUnloaded, ditheredWhenUnloaded);

    final Set<UUID> recipientsWhenUnloaded = channel.getRecipients();
    assertEquals(Set.of(), recipientsWhenUnloaded);

    channel.show(LOADED);
    verify(this.screen).show(this.player);

    channel.requestKeyframe();
    final boolean keyframeRequestedOnDemand = channel.takeKeyframeRequest();
    assertTrue(keyframeRequestedOnDemand);
  }

  private void openViewerLinks(final UUID... viewers) {
    this.prepareTrackingFrames();
    final int loadTick = this.server.getCurrentTick() - FIRST_DRAW_DELAY_TICKS;
    for (final UUID viewer : viewers) {
      when(this.viewers.findLoadTick(viewer)).thenReturn(OptionalInt.of(loadTick));
      this.pageTrackers.add(viewer);
      this.wallTrackers.add(viewer);
    }

    this.server.runTimers(1L);
    this.server.advanceTicks(SNAPSHOT_TICKS);
    this.server.runTimers(1L);
    for (final UUID viewer : viewers) {
      this.assertAnchorCount(viewer, 1);
      this.server.clearSentPackets(viewer);
    }
  }

  @Test
  void shouldHideAnAlreadyShownScreenWhenUnloadingThePack() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    when(this.viewers.isLoaded(LOADED)).thenReturn(false);
    channel.update();
    channel.update();
    this.server.runTasks();
    verify(this.screen).hide(this.player);

    final Map<UUID, Mcv2Link> links = channel.getLinks();
    assertEquals(Map.of(), links);

    final Set<UUID> recipients = channel.getRecipients();
    assertEquals(Set.of(), recipients);

    when(this.viewers.isLoaded(LOADED)).thenReturn(true);
    channel.update();
    this.server.runTasks();
    verify(this.screen, times(2)).show(this.player);

    channel.update();
    final Set<UUID> expectedRecipients = Set.of(LOADED);
    final Set<UUID> recipientsWhenReloaded = channel.getRecipients();
    assertEquals(expectedRecipients, recipientsWhenReloaded);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1, 2 })
  void shouldNotKeepALinkForAViewerRetiredDuringShow(final int retirement) {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    doAnswer(_ -> this.retireViewerDuringShow(retirement, channel))
      .when(this.screen)
      .show(this.player);
    channel.update();
    this.server.runTasks();
    final Map<UUID, Mcv2Link> links = channel.getLinks();
    assertEquals(Map.of(), links, "a retired show cannot publish a receiving link");
    verify(this.screen).hide(this.player);
  }

  private @Nullable Object retireViewerDuringShow(final int retirement, final Mcv2Channel channel) {
    if (retirement == 2) {
      this.configuration.getViewers().remove(LOADED);
    } else {
      when(this.viewers.isLoaded(LOADED)).thenReturn(false);
    }

    if (retirement == 0) {
      channel.update();
    }

    return null;
  }

  @Test
  void shouldLetAConcurrentCloseOwnTheCleanupOfAPublishedLink() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    final AtomicInteger loadedChecks = new AtomicInteger();
    when(this.viewers.isLoaded(LOADED)).thenAnswer(_ -> this.closeChannelAfterFirstCheck(loadedChecks, channel));
    this.server.runTasks();
    final int checks = loadedChecks.get();
    assertEquals(2, checks, "close overlaps the check after link publication");

    final Map<UUID, Mcv2Link> links = channel.getLinks();
    assertEquals(Map.of(), links);

    final Set<UUID> recipients = channel.getRecipients();
    assertEquals(Set.of(), recipients);
    verify(this.screen).show(this.player);
    verify(this.screen).remove();
    verify(this.screen, never()).hide(this.player);

    final boolean keyframeRequested = channel.takeKeyframeRequest();
    assertFalse(keyframeRequested, "a retired show must not request another frame");
  }

  private boolean closeChannelAfterFirstCheck(final AtomicInteger loadedChecks, final Mcv2Channel channel) {
    if (loadedChecks.getAndIncrement() == 0) {
      return true;
    }

    channel.close();

    return false;
  }

  @Test
  void shouldShowTheScreenNoMoreToAViewerRemovedFromIt() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    final Set<UUID> expectedRecipients = Set.of(LOADED);
    final Set<UUID> recipients = channel.getRecipients();
    assertEquals(expectedRecipients, recipients);

    this.configuration.getViewers().remove(LOADED);
    final Set<UUID> expectedDithered = Set.of(WITHOUT, OFFLINE);
    final Set<UUID> dithered = channel.update();
    assertEquals(expectedDithered, dithered);

    final Set<UUID> recipientsAfterRemoval = channel.getRecipients();
    assertEquals(Set.of(), recipientsAfterRemoval);

    final Map<UUID, Mcv2Link> links = channel.getLinks();
    assertEquals(Map.of(), links, "the link is retired");

    this.server.runTasks();
    verify(this.screen).hide(this.player);
  }

  @Test
  void shouldHideNothingFromARemovedViewerWhoLeftTheServer() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    this.configuration.getViewers().remove(LOADED);
    channel.update();
    this.server.removePlayer(LOADED);
    this.server.runTasks();
    verify(this.screen, never()).hide(this.player);

    final Map<UUID, Mcv2Link> links = channel.getLinks();
    assertEquals(Map.of(), links);
  }

  @Test
  void shouldHideEveryViewerRemovedWhileTheScreenCloses() {
    final UUID second = UUID.fromString(SECOND_VIEWER_ID);
    final CraftPlayer secondPlayer = this.server.addPlayer(second);
    when(this.viewers.isLoaded(second)).thenReturn(true);
    this.configuration.getViewers().add(second);
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    final Set<UUID> expectedLinked = Set.of(LOADED, second);
    final Set<UUID> linked = channel.getLinks().keySet();
    assertEquals(expectedLinked, linked);

    this.configuration.getViewers().removeAll(List.of(LOADED, second));
    /*
     * the main thread closes the screen while the video's thread retires both viewers: the close clears the link of
     * whichever viewer the update reaches second
     */
    final List<Runnable> hides = new ArrayList<>();
    final AtomicBoolean closed = new AtomicBoolean();
    doAnswer(invocation -> this.recordHideAfterClosing(invocation, closed, channel, hides))
      .when(this.server.getScheduler())
      .runTask(any(Plugin.class), any(Runnable.class));
    channel.update();
    hides.forEach(Runnable::run);
    verify(this.screen).hide(this.player);
    verify(this.screen).hide(secondPlayer);
  }

  private BukkitTask recordHideAfterClosing(
    final InvocationOnMock invocation,
    final AtomicBoolean closed,
    final Mcv2Channel channel,
    final List<Runnable> hides
  ) {
    if (closed.compareAndSet(false, true)) {
      channel.close();
    }

    hides.add(invocation.getArgument(1));

    return mock(BukkitTask.class);
  }

  @Test
  void shouldLetAViewerAddedBackBeforeTheScreenIsHiddenKeepIt() {
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

    final Set<UUID> expectedLinked = Set.of(LOADED);
    final Set<UUID> linked = channel.getLinks().keySet();
    assertEquals(expectedLinked, linked);
  }

  @Test
  void shouldNotShowTheScreenToAViewerRemovedBeforeItIsShown() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.configuration.getViewers().remove(LOADED);
    this.server.runTasks();
    verify(this.screen, never()).show(this.player);

    final Map<UUID, Mcv2Link> links = channel.getLinks();
    assertEquals(Map.of(), links);
  }

  @Test
  void shouldSendPagesAndWithAKeyframeTheAnchors() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    channel.open();
    this.openViewerLinks(LOADED);
    final int colors = channel.send(keyframe()).orElseThrow();
    final boolean positiveWholeRows = colors > 0 && colors % MAP_SIZE == 0;
    assertTrue(positiveWholeRows);

    final List<ClientboundMapItemDataPacket> first = MapPackets.unbundle(this.server.getSentPackets(LOADED).getFirst());
    final int firstSize = first.size();
    assertEquals(2, firstSize, "the page, then the anchor");

    channel.send(predicted());
    final List<ClientboundMapItemDataPacket> second = MapPackets.unbundle(this.server.getSentPackets(LOADED).get(1));
    final int secondSize = second.size();
    assertEquals(1, secondSize);

    final List<Packet<?>> sentWithout = this.server.getSentPackets(WITHOUT);
    assertEquals(List.of(), sentWithout);
  }

  @Test
  void shouldHoldBackAViewerWhoseConnectionFellBehind() throws Exception {
    final UUID other = UUID.fromString(SECOND_VIEWER_ID);
    final CraftPlayer second = this.server.addPlayer(other);
    PacketUtils.initialize();
    when(this.viewers.isLoaded(other)).thenReturn(true);
    // room for one page and its anchor, not for a second frame on top
    final Mcv2Configuration tight = Mcv2Configuration.builder()
      .viewers(List.of(LOADED, other))
      .origin(new Location(mock(World.class), 0, GROUND_LEVEL, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .pageMap(PAGE_MAP)
      .backlogLimit(BACKLOG_LIMIT)
      .unsentLimit(0)
      .build();
    final Mcv2Channel channel = new Mcv2Channel(tight, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    channel.open();
    this.openViewerLinks(LOADED, other);
    verify(this.screen).show(second);

    channel.send(frame(0, 0, Mcv2FrameKind.KEYFRAME));
    this.server.completeWrites(other);
    channel.send(frame(1, 0, Mcv2FrameKind.PREDICTED));
    this.server.completeWrites(other);
    channel.send(frame(2, 1, Mcv2FrameKind.PREDICTED));
    this.server.completeWrites(other);
    final int otherPackets = this.server.getSentPackets(other).size();
    assertEquals(3, otherPackets);

    final int loadedPackets = this.server.getSentPackets(LOADED).size();
    assertEquals(2, loadedPackets, "the keyframe, and the frame the backlog still allowed");

    final Mcv2Link slow = channel.getLinks().get(LOADED);
    final long behind = slow.getBehind();
    assertEquals(1, behind);

    final boolean overTheLimit = slow.getBacklog() > BACKLOG_LIMIT;
    assertTrue(overTheLimit);

    // once it has written, frame 3 predicts from frame 2, which it missed: it waits for the next keyframe
    this.server.completeWrites(LOADED);
    final long backlog = slow.getBacklog();
    assertEquals(0, backlog);

    channel.send(frame(3, 2, Mcv2FrameKind.PREDICTED));
    final int loadedPacketsWhileWaiting = this.server.getSentPackets(LOADED).size();
    assertEquals(2, loadedPacketsWhileWaiting);

    final long undecodable = slow.getUndecodable();
    assertEquals(1, undecodable);

    channel.send(frame(4, 4, Mcv2FrameKind.KEYFRAME));
    final int loadedPacketsAfterKeyframe = this.server.getSentPackets(LOADED).size();
    assertEquals(3, loadedPacketsAfterKeyframe);

    final int otherPacketsAfterKeyframe = this.server.getSentPackets(other).size();
    assertEquals(5, otherPacketsAfterKeyframe);

    final long otherBehind = channel.getLinks().get(other).getBehind();
    assertEquals(0, otherBehind);
  }

  @Test
  void shouldRecordEverySendForTheFlightRecorder(@TempDir final Path directory) throws Exception {
    final Mcv2Configuration tight = Mcv2Configuration.builder()
      .viewers(List.of(LOADED))
      .origin(new Location(mock(World.class), 0, GROUND_LEVEL, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .pageMap(PAGE_MAP)
      .pageSlots(1)
      .backlogLimit(BACKLOG_LIMIT)
      .build();
    final Mcv2Channel channel = new Mcv2Channel(tight, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    channel.open();
    this.openViewerLinks(LOADED);
    try (final Recording recording = new Recording()) {
      recording.enable("me.brandonli.mcav.Mcv2Send");
      recording.start();
      // sent, sent, held back by the backlog, then waiting for its reference, and one too large for the slot
      channel.send(frame(0, 0, Mcv2FrameKind.KEYFRAME));
      channel.send(frame(1, 0, Mcv2FrameKind.PREDICTED));
      channel.send(frame(2, 1, Mcv2FrameKind.PREDICTED));
      this.server.completeWrites(LOADED);
      channel.send(frame(3, 2, Mcv2FrameKind.PREDICTED));
      channel.send(large());
      recording.stop();
      final Path file = directory.resolve("sends.jfr");
      recording.dump(file);
      final List<RecordedEvent> sends = RecordingFile.readAllEvents(file)
        .stream()
        .filter(event -> event.getEventType().getName().equals("me.brandonli.mcav.Mcv2Send"))
        .toList();
      final int sendCount = sends.size();
      assertEquals(5, sendCount);

      final boolean firstIsKeyframe = sends.get(0).getBoolean("keyframe");
      assertTrue(firstIsKeyframe);

      final int sentTo = sends.get(1).getInt("sentTo");
      assertEquals(1, sentTo);

      final long referenceId = sends.get(1).getLong("referenceId");
      assertEquals(0, referenceId);

      final int behind = sends.get(2).getInt("behind");
      assertEquals(1, behind);

      final boolean overTheLimit = sends.get(2).getLong("backlog") > BACKLOG_LIMIT;
      assertTrue(overTheLimit);

      final int waiting = sends.get(3).getInt("waiting");
      assertEquals(1, waiting);

      final int colors = sends.get(4).getInt("colors");
      assertEquals(-1, colors);

      final boolean sentRecorded = sends.get(0).getLong("sent") > 0;
      assertTrue(sentRecorded);

      final int bytes = sends.get(0).getInt("bytes");
      assertEquals(48, bytes);
    }
  }

  @Test
  void shouldForgetTheBacklogOfAViewerWhoLeft() throws Exception {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    channel.open();
    this.openViewerLinks(LOADED);
    this.server.removePlayer(LOADED);
    PacketUtils.initialize();
    channel.send(frame(0, 0, Mcv2FrameKind.KEYFRAME));
    // nothing reached a connection, so nothing waits to be written
    final long backlog = channel.getLinks().get(LOADED).getBacklog();
    assertEquals(0, backlog);

    final long sent = channel.getLinks().get(LOADED).getSent();
    assertEquals(1, sent);

    final int writes = this.server.completeWrites(LOADED);
    assertEquals(0, writes);

    when(this.viewers.isLoaded(LOADED)).thenReturn(false);
    channel.update();
    final Map<UUID, Mcv2Link> links = channel.getLinks();
    assertEquals(Map.of(), links);
  }

  @Test
  void shouldSendEachPageToItsOwnMap() {
    // a wall of two maps: a page frame hangs behind each, so it carries two page slots
    final Mcv2Configuration two = Mcv2Configuration.builder()
      .viewers(List.of(LOADED))
      .origin(new Location(mock(World.class), 0, GROUND_LEVEL, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(2)
      .rows(1)
      .pageMap(PAGE_MAP)
      .pageSlots(2)
      .build();
    final Mcv2Channel channel = new Mcv2Channel(two, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    channel.open();
    this.openViewerLinks(LOADED);
    final boolean sentColors = channel.send(large()).orElseThrow() > 0;
    assertTrue(sentColors);

    // the two pages on maps 500 and 501, then the keyframe's anchor
    final List<ClientboundMapItemDataPacket> sent = MapPackets.unbundle(this.server.getSentPackets(LOADED).getFirst());
    final List<Integer> expected = List.of(PAGE_MAP, 501, WALL_MAP);
    final List<Integer> mapIds = sent
      .stream()
      .map(packet -> packet.mapId().id())
      .toList();
    assertEquals(expected, mapIds);
  }

  @Test
  void shouldDropAFrameWithMorePagesThanSlots() {
    final Mcv2Configuration narrow = Mcv2Configuration.builder()
      .viewers(List.of(LOADED))
      .origin(new Location(mock(World.class), 0, GROUND_LEVEL, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .pageMap(PAGE_MAP)
      .pageSlots(1)
      .build();
    final Mcv2Channel channel = new Mcv2Channel(narrow, this.viewers, this.screen);
    final boolean notSent = channel.send(large()).isEmpty();
    assertTrue(notSent);

    final boolean keyframeRequested = channel.takeKeyframeRequest();
    assertTrue(keyframeRequested);
  }

  @Test
  void shouldSendNothingToAReceivingViewerOfAFrameWithMorePagesThanSlots() {
    final Mcv2Configuration narrow = Mcv2Configuration.builder()
      .viewers(List.of(LOADED))
      .origin(new Location(mock(World.class), 0, GROUND_LEVEL, 0))
      .facing(BlockFace.SOUTH)
      .map(WALL_MAP)
      .columns(1)
      .rows(1)
      .pageMap(PAGE_MAP)
      .pageSlots(1)
      .build();
    final Mcv2Channel channel = new Mcv2Channel(narrow, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    final boolean notSent = channel.send(large()).isEmpty();
    assertTrue(notSent);

    final List<Packet<?>> sent = this.server.getSentPackets(LOADED);
    assertEquals(List.of(), sent);
  }

  @Test
  void shouldRefuseANullFrameAndBytesThatAreNotAnMcv2Frame() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    assertThrows(IllegalArgumentException.class, () -> channel.send(new byte[48]));
    assertThrows(NullPointerException.class, () -> channel.send(null));
  }

  @Test
  void shouldOpenAndCloseTheScreen() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.open();
    verify(this.screen).build();

    channel.update();
    this.server.runTasks();
    channel.update();
    channel.send(keyframe());
    final Set<UUID> expectedLinked = Set.of(LOADED);
    final Set<UUID> linked = channel.getLinks().keySet();
    assertEquals(expectedLinked, linked);

    channel.close();
    verify(this.screen).remove();

    final Map<UUID, Mcv2Link> links = channel.getLinks();
    assertEquals(Map.of(), links);

    final Set<UUID> recipients = channel.getRecipients();
    assertEquals(Set.of(), recipients);

    // a show that was scheduled before the close does nothing
    channel.show(LOADED);
    verify(this.screen).show(this.player);
  }

  @Test
  void shouldRefuseANullConfigurationViewersOrScreen() {
    assertThrows(NullPointerException.class, () -> new Mcv2Channel(null, this.viewers));
    assertThrows(NullPointerException.class, () -> new Mcv2Channel(null, this.viewers, this.screen));
    assertThrows(NullPointerException.class, () -> new Mcv2Channel(this.configuration, null));
    assertThrows(NullPointerException.class, () -> new Mcv2Channel(this.configuration, this.viewers, null));
  }

  @Test
  void shouldForgetAViewerWhoLeftBeforeTheScreenWasShown() {
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    // the offline viewer's show found no player; the next update schedules it again
    channel.update();
    verify(this.screen).show(this.player);

    final int scheduledTasks = this.server.getScheduledTaskCount();
    assertEquals(1, scheduledTasks);
  }

  private Mcv2Channel watchedFrom(final World world, final AtomicReference<Location> position) {
    return this.watchedFrom(world, position, BlockFace.SOUTH, 1, 1);
  }

  private Mcv2Channel watchedFrom(
    final World world,
    final AtomicReference<Location> position,
    final BlockFace facing,
    final int columns,
    final int rows
  ) {
    when(world.getUID()).thenReturn(UUID.fromString(OVERWORLD_ID));
    final Mcv2Configuration wall = Mcv2Configuration.builder()
      .viewers(List.of(LOADED))
      .origin(new Location(world, 0, GROUND_LEVEL, 0))
      .facing(facing)
      .map(WALL_MAP)
      .columns(columns)
      .rows(rows)
      .pageMap(PAGE_MAP)
      .pageSlots(1)
      .build();
    when(this.player.getViewDistance()).thenReturn(VIEW_DISTANCE);
    when(this.player.getWorld()).thenAnswer(_ -> position.get().getWorld());
    when(this.player.getLocation()).thenAnswer(_ -> position.get());
    return new Mcv2Channel(wall, this.viewers, this.screen);
  }

  private void tick(final Mcv2Channel channel) {
    this.server.runTasks();
    channel.update();
    this.server.runTasks();
    channel.update();
  }

  @Test
  void shouldShowTheScreenAgainToAViewerWhoRejoinedWhileNoFrameCame() {
    when(this.viewers.getSession(LOADED)).thenReturn(1L);
    final Mcv2Channel channel = new Mcv2Channel(this.configuration, this.viewers, this.screen);
    channel.update();
    this.server.runTasks();
    channel.update();
    final Set<UUID> expectedRecipients = Set.of(LOADED);
    final Set<UUID> recipients = channel.getRecipients();
    assertEquals(expectedRecipients, recipients);
    verify(this.screen).show(this.player);

    // paused, so no update runs while the viewer rejoins and reloads the pack; the new client never saw the page frames, hidden by default
    this.server.removePlayer(LOADED);
    final CraftPlayer rejoined = this.server.addPlayer(LOADED);
    when(this.viewers.getSession(LOADED)).thenReturn(2L);

    final Set<UUID> expectedDithered = Set.of(LOADED, WITHOUT, OFFLINE);
    final Set<UUID> dithered = channel.update();
    assertEquals(expectedDithered, dithered, "no frames to a client that cannot show them");

    final Set<UUID> recipientsAfterRejoining = channel.getRecipients();
    assertEquals(Set.of(), recipientsAfterRejoining);

    this.server.runTasks();
    verify(this.screen).show(rejoined);

    channel.update();
    final Set<UUID> expectedRecipientsWhenShown = Set.of(LOADED);
    final Set<UUID> recipientsWhenShown = channel.getRecipients();
    assertEquals(expectedRecipientsWhenShown, recipientsWhenShown, "the frames resume once the screen is shown again");
  }

  @Test
  void shouldSendNoFramesToAViewerOutOfSightOfTheWallUntilTheyComeBack() {
    final World world = mock(World.class);
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, BACKLOG_LIMIT, GROUND_LEVEL, 0));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    this.tick(channel);
    final Set<UUID> dithered = channel.update();
    assertEquals(Set.of(), dithered, "too far for the dithered maps as well");

    channel.takeKeyframeRequest();
    final boolean sentColors = channel.send(keyframe()).orElseThrow() > 0;
    assertTrue(sentColors);

    final List<Packet<?>> sentWhileAway = this.server.getSentPackets(LOADED);
    assertEquals(List.of(), sentWhileAway, "400 blocks away with a view distance of 6 chunks");

    position.set(new Location(world, 0, GROUND_LEVEL, 4));
    this.tick(channel);
    this.openViewerLinks(LOADED);
    final boolean keyframeRequested = channel.takeKeyframeRequest();
    assertTrue(keyframeRequested);

    channel.send(frame(1, 0, Mcv2FrameKind.PREDICTED));
    final List<Packet<?>> sentBeforeKeyframe = this.server.getSentPackets(LOADED);
    assertEquals(List.of(), sentBeforeKeyframe, "a P frame before the keyframe is useless to them");

    channel.send(frame(2, 2, Mcv2FrameKind.KEYFRAME));
    final int packetCount = this.server.getSentPackets(LOADED).size();
    assertEquals(1, packetCount);

    channel.close();
  }

  @Test
  void shouldRecheckTheViewersDistanceInAScheduledShow() {
    final World world = mock(World.class);
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, 0, GROUND_LEVEL, 4));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    try {
      channel.update();
      position.set(new Location(world, BACKLOG_LIMIT, GROUND_LEVEL, 0));
      channel.measureRange();
      this.server.runTasks();
      final Map<UUID, Mcv2Link> links = channel.getLinks();
      assertEquals(Map.of(), links);

      final Set<UUID> recipients = channel.getRecipients();
      assertEquals(Set.of(), recipients);
      verify(this.screen, never()).show(this.player);

      final boolean keyframeRequested = channel.takeKeyframeRequest();
      assertFalse(keyframeRequested, "an out-of-range show must not request another frame");
    } finally {
      channel.close();
    }
  }

  @Test
  void shouldSendNoFramesToAViewerInAnotherWorld() {
    final World world = mock(World.class);
    final World nether = mock(World.class);
    when(nether.getUID()).thenReturn(UUID.fromString(NETHER_ID));
    final AtomicReference<Location> position = new AtomicReference<>(new Location(nether, 0, GROUND_LEVEL, 4));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    this.tick(channel);
    channel.send(keyframe());
    final List<Packet<?>> sent = this.server.getSentPackets(LOADED);
    assertEquals(List.of(), sent, "the same place in another world");

    channel.close();
  }

  @Test
  void shouldHideAViewerRemovedWhileOutOfRange() {
    final World world = this.configuration.getOrigin().getWorld();
    this.assertHiddenWhenRemovedAt(new Location(world, BACKLOG_LIMIT, GROUND_LEVEL, 0));
  }

  @Test
  void shouldHideAViewerRemovedWhileInAnotherWorld() {
    final World nether = mock(World.class);
    when(nether.getUID()).thenReturn(UUID.fromString(NETHER_ID));
    this.assertHiddenWhenRemovedAt(new Location(nether, 0, GROUND_LEVEL, 4));
  }

  private Mcv2Channel seenFrom(final AtomicReference<Location> position) {
    final World world = this.configuration.getOrigin().getWorld();
    when(world.getUID()).thenReturn(UUID.fromString(OVERWORLD_ID));
    when(this.player.getViewDistance()).thenReturn(VIEW_DISTANCE);
    when(this.player.getWorld()).thenAnswer(_ -> position.get().getWorld());
    when(this.player.getLocation()).thenAnswer(_ -> position.get());
    return new Mcv2Channel(this.configuration, this.viewers, this.screen);
  }

  private void assertHiddenWhenRemovedAt(final Location elsewhere) {
    final World world = this.configuration.getOrigin().getWorld();
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, 0, GROUND_LEVEL, 4));
    final Mcv2Channel channel = this.seenFrom(position);
    channel.open();
    this.tick(channel);
    final Set<UUID> expectedRecipients = Set.of(LOADED);
    final Set<UUID> recipients = channel.getRecipients();
    assertEquals(expectedRecipients, recipients);
    verify(this.screen).show(this.player);

    position.set(elsewhere);
    this.tick(channel);
    final Map<UUID, Mcv2Link> links = channel.getLinks();
    assertEquals(Map.of(), links, "nothing is sent to a viewer who cannot see the wall");
    // hiding the screen would take away the team every screen shown to them shares
    verify(this.screen, never()).hide(this.player);

    this.configuration.getViewers().remove(LOADED);
    channel.update();
    this.server.runTasks();
    // and once: the next update has no one left to retire
    channel.update();
    this.server.runTasks();
    verify(this.screen).hide(this.player);

    channel.close();
  }

  @Test
  void shouldHideNothingFromAViewerNeverShownTheScreenWhenRemovedWhileAway() {
    final World world = this.configuration.getOrigin().getWorld();
    final Mcv2Channel channel = this.seenFrom(new AtomicReference<>(new Location(world, BACKLOG_LIMIT, GROUND_LEVEL, 0)));
    channel.open();
    this.tick(channel);
    this.configuration.getViewers().remove(LOADED);
    channel.update();
    this.server.runTasks();
    verify(this.screen, never()).show(this.player);
    // a hide would take away the team of the other screens shown to them
    verify(this.screen, never()).hide(this.player);

    channel.close();
  }

  @Test
  void shouldNotLetAViewerAtTheEdgeOfTheirViewDistanceComeAndGo() {
    final World world = mock(World.class);
    // 96 blocks from the wall's block: the six chunks of the view distance
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, 97, GROUND_LEVEL, 0));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    this.tick(channel);
    final Set<UUID> expectedRecipients = Set.of(LOADED);
    final Set<UUID> recipients = channel.getRecipients();
    assertEquals(expectedRecipients, recipients);

    // up to 32 blocks farther the viewer stays
    position.set(new Location(world, 129, GROUND_LEVEL, 0));
    this.tick(channel);
    final Set<UUID> expectedRecipientsFarther = Set.of(LOADED);
    final Set<UUID> recipientsFarther = channel.getRecipients();
    assertEquals(expectedRecipientsFarther, recipientsFarther);

    position.set(new Location(world, 130, GROUND_LEVEL, 0));
    this.tick(channel);
    final Set<UUID> recipientsBeyond = channel.getRecipients();
    assertEquals(Set.of(), recipientsBeyond);

    // and once gone, comes back only within the view distance
    position.set(new Location(world, 98, GROUND_LEVEL, 0));
    this.tick(channel);
    final Set<UUID> recipientsOnTheWayBack = channel.getRecipients();
    assertEquals(Set.of(), recipientsOnTheWayBack);

    position.set(new Location(world, 97, GROUND_LEVEL, 0));
    this.tick(channel);
    final Set<UUID> expectedRecipientsBack = Set.of(LOADED);
    final Set<UUID> recipientsBack = channel.getRecipients();
    assertEquals(expectedRecipientsBack, recipientsBack);

    channel.close();
  }

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
  void shouldLetAWideWallReachAsFarAsItsLastColumn() {
    // a view distance of 6 chunks and the margin of a viewer who has not left: 128 blocks from the nearest block
    final boolean receivesEast = this.receivesFrom(BlockFace.SOUTH, 5, 1, 133, STANDING_LEVEL, BLOCK_CENTER);
    assertTrue(receivesEast, "128 blocks east of the fifth column");

    final boolean receivesSouth = this.receivesFrom(BlockFace.WEST, 5, 1, BLOCK_CENTER, STANDING_LEVEL, 133);
    assertTrue(receivesSouth, "128 blocks south of the fifth column");
  }

  @Test
  void shouldLetATallWallReachDownToItsLowestRow() {
    // the third row from the top is the block at y 62
    final boolean receivesJustInReach = this.receivesFrom(BlockFace.SOUTH, 1, 3, BLOCK_CENTER, -66, BLOCK_CENTER);
    assertTrue(receivesJustInReach, "128 blocks below the lowest row");

    final boolean receivesJustOutOfReach = this.receivesFrom(BlockFace.SOUTH, 1, 3, BLOCK_CENTER, -67, BLOCK_CENTER);
    assertFalse(receivesJustOutOfReach, "129 blocks below it");
  }

  @Test
  void shouldPlaceAViewerOffTheWallOnTwoAxesAsFarAsBothTogether() {
    // 100 blocks off on each of two axes: 141 blocks away, though neither alone is out of reach
    final boolean receivesEastAndAbove = this.receivesFrom(BlockFace.SOUTH, 1, 1, 101, 165, BLOCK_CENTER);
    assertFalse(receivesEastAndAbove, "east and above");

    final boolean receivesEastAndSouth = this.receivesFrom(BlockFace.SOUTH, 1, 1, 101, STANDING_LEVEL, 101);
    assertFalse(receivesEastAndSouth, "east and south");
  }

  @Test
  void shouldKnowAtOnceWhoIsTooFarWhenTheChannelOpens() {
    final World world = mock(World.class);
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, BACKLOG_LIMIT, GROUND_LEVEL, 0));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    // before the first measurement of the timer the viewer 400 blocks away is in neither group
    final Set<UUID> dithered = channel.update();
    assertEquals(Set.of(), dithered);

    final int scheduledTasks = this.server.getScheduledTaskCount();
    assertEquals(2, scheduledTasks, "only the measurement and the tracking check: the screen is not shown to them");

    channel.close();
  }

  @Test
  void shouldStopMeasuringTheViewersDistancesWhenClosing() {
    final World world = mock(World.class);
    final AtomicReference<Location> position = new AtomicReference<>(new Location(world, 0, GROUND_LEVEL, 4));
    final Mcv2Channel channel = this.watchedFrom(world, position);
    channel.open();
    final int scheduledTasks = this.server.getScheduledTaskCount();
    assertEquals(2, scheduledTasks, "Open must schedule both range measurement and per-tick tracking!");
    verify(this.server.getScheduler(), description("Open must schedule tracking every tick starting next tick!")).runTaskTimer(
      eq(this.server.getPlugin()),
      any(Runnable.class),
      eq(1L),
      eq(1L)
    );

    channel.removeFrames();
    final int scheduledTasksWithoutFrames = this.server.getScheduledTaskCount();
    assertEquals(1, scheduledTasksWithoutFrames, "Removing frames must cancel tracking while retaining range measurement!");

    position.set(new Location(world, BACKLOG_LIMIT, GROUND_LEVEL, 0));
    this.server.runTasks();
    final Set<UUID> nearWithoutFrames = channel.near(Set.of(LOADED));
    assertEquals(Set.of(), nearWithoutFrames, "The remaining timer after removeFrames must still measure range!");

    channel.open();
    final int scheduledTasksAfterRestoringFrames = this.server.getScheduledTaskCount();
    assertEquals(2, scheduledTasksAfterRestoringFrames, "Restoring frames must schedule their tracking timer again!");
    verify(this.server.getScheduler(), times(2).description("Restoring frames must register the replacement tracking timer!")).runTaskTimer(
      eq(this.server.getPlugin()),
      any(Runnable.class),
      eq(1L),
      eq(1L)
    );

    channel.close();
    final int scheduledTasksAfterClose = this.server.getScheduledTaskCount();
    assertEquals(0, scheduledTasksAfterClose, "Close must cancel both the tracking timer and the range timer!");

    channel.open();
    final int scheduledTasksAfterReopen = this.server.getScheduledTaskCount();
    assertEquals(2, scheduledTasksAfterReopen, "Reopening a closed channel must schedule both timers again!");
    verify(this.server.getScheduler(), times(3).description("Reopening must register a new tracking timer again!")).runTaskTimer(
      eq(this.server.getPlugin()),
      any(Runnable.class),
      eq(1L),
      eq(1L)
    );

    channel.close();
    final int scheduledTasksAfterSecondClose = this.server.getScheduledTaskCount();
    assertEquals(0, scheduledTasksAfterSecondClose, "Closing the reopened channel must cancel its replacement tracking timer!");
  }

  private static final class PrerecordedPlayback implements Runnable {

    private final Mcv2Channel channel;
    private final List<byte[]> frames;

    private int next;
    private int requests;
    private long frameId;

    private PrerecordedPlayback(final Mcv2Channel channel, final List<byte[]> frames) {
      this.channel = channel;
      this.frames = List.copyOf(frames);
    }

    @Override
    public void run() {
      this.channel.update();
      if (this.channel.takeKeyframeRequest()) {
        this.requests++;
        this.next = 0;
      }

      final Set<UUID> recipients = this.channel.getRecipients();
      if (recipients.isEmpty()) {
        return;
      }

      final byte[] prerecordedFrame = this.frames.get(this.next);
      final byte[] frame = prerecordedFrame.clone();
      final long reference = this.next == 0 ? this.frameId : this.frameId - 1;
      Mcv2Decoder.putUnsignedInt(frame, Mcv2Decoder.FRAME_ID_OFFSET, this.frameId);
      Mcv2Decoder.putUnsignedInt(frame, Mcv2Decoder.REFERENCE_ID_OFFSET, reference);
      this.channel.send(frame);
      this.frameId++;
      this.next = (this.next + 1) % this.frames.size();
    }

    private int getRequests() {
      return this.requests;
    }
  }
}
