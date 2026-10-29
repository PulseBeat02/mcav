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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Pacer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Pack;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

/**
 * Tests {@link Mcv2Support}: a screen's wall is found by the frame that holds its top-left map, and its output takes a
 * slot of the one pack, which the sender learns when it cannot.
 */
final class Mcv2SupportTest {

  @TempDir
  private Path folder;

  private Mcv2PackServer packs;

  private Mcv2Support support;

  private CommandSender sender;

  private World world;

  private final UUID viewer = UUID.randomUUID();

  static ItemStack map(final Material material, final boolean hasId, final int id) {
    final ItemStack item = mock(ItemStack.class);
    when(item.getType()).thenReturn(material);
    final MapMeta metadata = mock(MapMeta.class);
    when(metadata.hasMapId()).thenReturn(hasId);
    when(metadata.getMapId()).thenReturn(id);
    when(item.getItemMeta()).thenReturn(metadata);
    return item;
  }

  static ItemFrame frame(final ItemStack item) {
    final ItemFrame frame = mock(ItemFrame.class);
    when(frame.getItem()).thenReturn(item);
    return frame;
  }

  @BeforeEach
  void createSupport() {
    TestServer.reset();
    this.packs = mock(Mcv2PackServer.class);
    this.support = new Mcv2Support(this.packs);
    this.sender = mock(CommandSender.class);
    this.world = mock(World.class);
    final ItemFrame frame = frame(map(Material.FILLED_MAP, true, 20));
    when(frame.getLocation()).thenReturn(new Location(this.world, 5.5, 64.5, 7.03));
    when(frame.getFacing()).thenReturn(BlockFace.SOUTH);
    when(this.world.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(frame));
    when(TestServer.server().getWorlds()).thenReturn(List.of(this.world));
  }

  private Mcv2Configuration configure(final int map) {
    return this.support.configure(this.sender, Pair.pair(5, 3), Pair.pair(640, 384), map, Settings.DEFAULT, List.of(this.viewer));
  }

  private @Nullable Mcv2Configuration configure(final int columns, final int rows, final int width, final int height) {
    return this.support.configure(
      this.sender,
      Pair.pair(columns, rows),
      Pair.pair(width, height),
      20,
      Settings.DEFAULT,
      List.of(this.viewer)
    );
  }

  @Test
  void shouldFindTheFrameHoldingAMap() {
    final ItemFrame wrongMap = frame(map(Material.FILLED_MAP, true, 3));
    final ItemFrame noId = frame(map(Material.FILLED_MAP, false, 7));
    final ItemFrame other = frame(map(Material.STONE, true, 7));
    final ItemStack plain = mock(ItemStack.class);
    when(plain.getType()).thenReturn(Material.FILLED_MAP);
    when(plain.getItemMeta()).thenReturn(mock(ItemMeta.class));
    final ItemFrame notAMap = frame(plain);
    final ItemFrame wanted = frame(map(Material.FILLED_MAP, true, 7));
    final World first = mock(World.class);
    when(first.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(wrongMap, noId));
    final World second = mock(World.class);
    when(second.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(other, notAMap, wanted));

    final ItemFrame frame = Mcv2Support.findFrame(List.of(first, second), 7);
    assertSame(wanted, frame);

    final ItemFrame mcv2SupportFrame = Mcv2Support.findFrame(List.of(first, second), 8);
    assertNull(mcv2SupportFrame);

    final boolean holdsMap = Mcv2Support.holdsMap(map(Material.FILLED_MAP, true, 3), 7);
    assertFalse(holdsMap);

    final boolean mcv2SupportHoldsMap = Mcv2Support.holdsMap(map(Material.FILLED_MAP, true, 7), 7);
    assertTrue(mcv2SupportHoldsMap);
  }

  @Test
  void shouldConfigureTheScreenOnTheWallThatHoldsTheMap() {
    final Mcv2Configuration configuration = Objects.requireNonNull(this.configure(20));

    final Location expected = new Location(this.world, 5, 64, 7);
    final Location origin = configuration.getOrigin();
    assertEquals(expected, origin);

    final BlockFace facing = configuration.getFacing();
    assertEquals(BlockFace.SOUTH, facing);

    final int map = configuration.getMap();
    assertEquals(20, map);

    final int columns = configuration.getColumns();
    assertEquals(5, columns);

    final int rows = configuration.getRows();
    assertEquals(3, rows);

    final int videoWidth = configuration.getVideoWidth();
    assertEquals(640, videoWidth);

    final int videoHeight = configuration.getVideoHeight();
    assertEquals(384, videoHeight);

    final Settings settings = configuration.getSettings();
    assertEquals(Settings.DEFAULT, settings);

    final List<UUID> copy = List.copyOf(configuration.getViewers());
    assertEquals(List.of(this.viewer), copy);

    final long backlogLimit = configuration.getBacklogLimit();
    assertEquals(Mcv2Configuration.DEFAULT_BACKLOG_LIMIT, backlogLimit);

    final int pageSlots = configuration.getPageSlots();
    assertEquals(Mcv2Configuration.MAXIMUM_PAGE_SLOTS, pageSlots);
  }

  @Test
  void shouldTakeThePageSlotsAndTheBacklogLimitOfAMeasurement() {
    System.setProperty(VideoMcv2Command.PAGE_SLOTS_PROPERTY, "8");
    System.setProperty(VideoMcv2Command.BACKLOG_PROPERTY, "65536");
    try {
      final Mcv2Configuration limited = Objects.requireNonNull(this.configure(20));

      final int pageSlots = limited.getPageSlots();
      assertEquals(8, pageSlots);

      final long backlogLimit = limited.getBacklogLimit();
      assertEquals(65536, backlogLimit);

      final int unsentLimit = limited.getUnsentLimit();
      assertEquals(Mcv2Configuration.DEFAULT_UNSENT_LIMIT, unsentLimit);
    } finally {
      System.clearProperty(VideoMcv2Command.PAGE_SLOTS_PROPERTY);
      System.clearProperty(VideoMcv2Command.BACKLOG_PROPERTY);
    }
  }

  @Test
  void shouldTellTheSenderWhenTheScreenIsLargerThanMcv2Plays() {
    // the plugin's walls go up to 64 maps a side and its videos up to 8192 pixels, MCV2's to 63 and 4096
    final Mcv2Configuration configured = this.configure(64, 3, 640, 384);
    assertNull(configured);

    final Mcv2Configuration secondConfigured = this.configure(5, 64, 640, 384);
    assertNull(secondConfigured);

    final Mcv2Configuration thirdConfigured = this.configure(5, 3, 4097, 384);
    assertNull(thirdConfigured);

    final Mcv2Configuration fourthConfigured = this.configure(5, 3, 640, 4097);
    assertNull(fourthConfigured);

    verify(this.sender, times(4)).sendMessage(Message.MCV2_SIZE_ERROR.build());
    final Mcv2Configuration largest = Objects.requireNonNull(this.configure(63, 63, 4096, 4096));

    final int columns = largest.getColumns();
    assertEquals(63, columns);

    final int videoHeight = largest.getVideoHeight();
    assertEquals(4096, videoHeight);
  }

  @Test
  void shouldTellTheSenderWhenNoFrameHoldsTheMap() {
    final Mcv2Configuration configured = this.configure(21);
    assertNull(configured);

    verify(this.sender).sendMessage(Message.MCV2_SCREEN_ERROR.build(21));
  }

  @Test
  void shouldOpenASlotOrTellTheSenderThatEveryOnePlays() {
    final Mcv2Configuration configuration = Objects.requireNonNull(this.configure(20));
    final Mcv2PackServer.Lease lease = mock(Mcv2PackServer.Lease.class);
    when(this.packs.open(configuration)).thenReturn(lease).thenThrow(new IllegalStateException("full!"));

    final Mcv2PackServer.Lease opened = this.support.open(this.sender, configuration);
    assertSame(lease, opened);

    final Mcv2PackServer.Lease supportOpened = this.support.open(this.sender, configuration);
    assertNull(supportOpened);

    verify(this.sender).sendMessage(Message.MCV2_FULL.build());

    final Mcv2Output mcv2Output = this.support.output(this.sender, configuration, DitheringArgument.NEAREST_COLOR);
    assertNull(mcv2Output);
  }

  @Test
  void shouldCreateTheOutputOfAScreenInItsSlot() {
    final Mcv2Configuration configuration = Objects.requireNonNull(this.configure(20));
    final Mcv2Configuration slotted = configuration.withSlot(3, Mcv2Configuration.DEFAULT_PAGE_MAP + 16, 1234);
    final Mcv2PackServer.Lease lease = mock(Mcv2PackServer.Lease.class);
    when(lease.getConfiguration()).thenReturn(slotted);
    when(this.packs.open(configuration)).thenReturn(lease);
    final Mcv2Viewers viewers = mock(Mcv2Viewers.class);
    when(this.packs.getViewers()).thenReturn(viewers);
    final List<List<?>> arguments = new ArrayList<>();
    try (
      final MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class, (_, context) ->
        arguments.add(context.arguments())
      )
    ) {
      final Mcv2Output output = Objects.requireNonNull(this.support.output(this.sender, configuration, DitheringArgument.NEAREST_COLOR));
      final Mcv2Result result = results.constructed().getFirst();

      final Mcv2Result outputResult = output.getResult();
      assertSame(result, outputResult);

      final Object argument = arguments.getFirst().getFirst();
      assertSame(slotted, argument);

      final Object argumentsArgument = arguments.getFirst().get(1);
      assertSame(viewers, argumentsArgument);

      final DitherAlgorithm expected = DitheringArgument.NEAREST_COLOR.createAlgorithm();
      final Object secondArgument = arguments.getFirst().get(2);
      assertSame(expected, secondArgument);

      @SuppressWarnings("unchecked")
      final ArgumentCaptor<Consumer<Mcv2Pacer.Change>> listeners = ArgumentCaptor.forClass(Consumer.class);
      verify(result).setPacingListener(listeners.capture());
      final Mcv2Pacer.Change change = Mcv2Pacer.Change.of(
        Mcv2Pacer.Rung.of(640, 384, 1),
        Mcv2Pacer.Rung.of(640, 384, 2),
        true,
        20,
        16.7,
        60
      );
      listeners.getValue().accept(change);
      verify(this.sender).sendMessage(Message.MCV2_PACING.build(change.describe()));
      @SuppressWarnings("unchecked")
      final ArgumentCaptor<List<int[]>> sizes = ArgumentCaptor.forClass(List.class);
      verify(result).setSmallerSizes(sizes.capture(), any());
      verify(result).setSmallerSizes(any(), Mockito.same(lease));

      final List<String> list = sizes
        .getValue()
        .stream()
        .map(size -> size[0] + "x" + size[1])
        .toList();
      assertEquals(List.of("426x256", "320x192"), list);
    }
  }

  @Test
  void shouldRecordTheFramesOfAScreenForAMeasurement() {
    final Mcv2Configuration configuration = Objects.requireNonNull(this.configure(20));
    final Mcv2PackServer.Lease lease = mock(Mcv2PackServer.Lease.class);
    when(lease.getConfiguration()).thenReturn(configuration.withSlot(3, Mcv2Configuration.DEFAULT_PAGE_MAP + 16, 0));
    when(this.packs.open(configuration)).thenReturn(lease);
    System.setProperty(Mcv2Support.RECORD_PROPERTY, this.folder.toString());
    try (final MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class)) {
      this.support.output(this.sender, configuration, DitheringArgument.NEAREST_COLOR);
      verify(results.constructed().getFirst()).setFrameListener(any(FrameRecorder.class));
    } finally {
      System.clearProperty(Mcv2Support.RECORD_PROPERTY);
    }
    try (final MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class)) {
      this.support.output(this.sender, configuration, DitheringArgument.NEAREST_COLOR);
      verify(results.constructed().getFirst(), Mockito.never()).setFrameListener(any());
    }
  }

  @Test
  void shouldStepDownToTwoThirdsAndHalfOfTheVideo() {
    final List<String> described = described(Mcv2Support.smallerSizes(1920, 1080));
    assertEquals(List.of("1280x720", "960x540"), described);

    final List<String> secondDescribed = described(Mcv2Support.smallerSizes(384, 216));
    assertEquals(List.of("256x144", "192x108"), secondDescribed);
    // a size under 128 by 72 is left out

    final List<String> thirdDescribed = described(Mcv2Support.smallerSizes(240, 120));
    assertEquals(List.of("160x80"), thirdDescribed);

    final List<String> fourthDescribed = described(Mcv2Support.smallerSizes(200, 100));
    assertEquals(List.of(), fourthDescribed);
    // exactly 128 by 72 is kept

    final List<String> fifthDescribed = described(Mcv2Support.smallerSizes(192, 108));
    assertEquals(List.of("128x72"), fifthDescribed);
  }

  private static List<String> described(final List<int[]> sizes) {
    return sizes
      .stream()
      .map(size -> size[0] + "x" + size[1])
      .toList();
  }

  @Test
  void shouldStartAndStopThePackServer() {
    final Mcv2Viewers viewers = mock(Mcv2Viewers.class);
    when(this.packs.getViewers()).thenReturn(viewers);
    this.support.start();

    final Mcv2Viewers supportViewers = this.support.getViewers();
    assertSame(viewers, supportViewers);

    this.support.shutdown();
    verify(this.packs).start();
    verify(this.packs).shutdown();
    assertThrows(NullPointerException.class, () -> new Mcv2Support(null));
  }

  @Test
  void shouldWriteThePackIntoItsOwnFolderAndTellThePlayers() {
    try (
      final MockedConstruction<Mcv2PackServer> servers = Mockito.mockConstruction(Mcv2PackServer.class, (_, context) ->
        assertEquals(this.folder.resolve("mcv2").resolve("pack"), context.arguments().getFirst())
      )
    ) {
      final Mcv2Support real = new Mcv2Support(this.folder, PackHosting::injector);
      real.shutdown();
      verify(servers.constructed().getFirst()).shutdown();
    }
    final Player player = mock(Player.class);
    Mcv2Support.tellOffered(player);
    Mcv2Support.tellRefused(player);
    verify(player).sendMessage(Message.MCV2_PACK.build());
    verify(player).sendMessage(Message.MCV2_REFUSED.build());
  }

  @Test
  void shouldShowTheDebugViewOnlyWhenItsPropertyIsSet() {
    final List<Object> debugViews = new ArrayList<>();
    try (
      final MockedConstruction<Mcv2PackServer> _ = Mockito.mockConstruction(Mcv2PackServer.class, (_, context) ->
        debugViews.add(context.arguments().get(2))
      )
    ) {
      System.setProperty(Mcv2Support.DEBUG_VIEW, "true");
      try {
        final Mcv2Support _ = new Mcv2Support(this.folder, PackHosting::injector);
      } finally {
        System.clearProperty(Mcv2Support.DEBUG_VIEW);
      }
      final Mcv2Support _ = new Mcv2Support(this.folder, PackHosting::injector);
    }

    final List<Mcv2Pack.DebugView> expected = List.of(Mcv2Pack.DebugView.SHOWN, Mcv2Pack.DebugView.HIDDEN);
    assertEquals(expected, debugViews);
  }
}
