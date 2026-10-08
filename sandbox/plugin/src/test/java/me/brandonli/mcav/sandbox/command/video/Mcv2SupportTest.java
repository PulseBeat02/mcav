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
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Pacer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
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
    final MapMeta meta = mock(MapMeta.class);
    when(meta.hasMapId()).thenReturn(hasId);
    when(meta.getMapId()).thenReturn(id);
    when(item.getItemMeta()).thenReturn(meta);
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
  void findsTheFrameHoldingAMap() {
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
    assertSame(wanted, Mcv2Support.findFrame(List.of(first, second), 7));
    assertNull(Mcv2Support.findFrame(List.of(first, second), 8));
    assertFalse(Mcv2Support.holdsMap(map(Material.FILLED_MAP, true, 3), 7));
    assertTrue(Mcv2Support.holdsMap(map(Material.FILLED_MAP, true, 7), 7));
  }

  @Test
  void configuresTheScreenOnTheWallThatHoldsTheMap() {
    final Mcv2Configuration configuration = Objects.requireNonNull(this.configure(20));
    assertEquals(new Location(this.world, 5, 64, 7), configuration.getOrigin());
    assertEquals(BlockFace.SOUTH, configuration.getFacing());
    assertEquals(20, configuration.getMap());
    assertEquals(5, configuration.getColumns());
    assertEquals(3, configuration.getRows());
    assertEquals(640, configuration.getVideoWidth());
    assertEquals(384, configuration.getVideoHeight());
    assertEquals(Settings.DEFAULT, configuration.getSettings());
    assertEquals(List.of(this.viewer), List.copyOf(configuration.getViewers()));
    assertEquals(Mcv2Configuration.DEFAULT_BACKLOG_LIMIT, configuration.getBacklogLimit());
    assertEquals(Mcv2Configuration.MAX_PAGE_SLOTS, configuration.getPageSlots());
  }

  @Test
  void takesThePageSlotsAndTheBacklogLimitOfAMeasurement() {
    System.setProperty(VideoMcv2Command.PAGE_SLOTS_PROPERTY, "8");
    System.setProperty(VideoMcv2Command.BACKLOG_PROPERTY, "65536");
    try {
      final Mcv2Configuration limited = Objects.requireNonNull(this.configure(20));
      assertEquals(8, limited.getPageSlots());
      assertEquals(65536, limited.getBacklogLimit());
      assertEquals(Mcv2Configuration.DEFAULT_UNSENT_LIMIT, limited.getUnsentLimit());
    } finally {
      System.clearProperty(VideoMcv2Command.PAGE_SLOTS_PROPERTY);
      System.clearProperty(VideoMcv2Command.BACKLOG_PROPERTY);
    }
  }

  @Test
  void tellsTheSenderWhenTheScreenIsLargerThanMcv2Plays() {
    // the plugin's walls go up to 64 maps a side and its videos up to 8192 pixels, MCV2's to 63 and 4096
    assertNull(this.configure(64, 3, 640, 384));
    assertNull(this.configure(5, 64, 640, 384));
    assertNull(this.configure(5, 3, 4097, 384));
    assertNull(this.configure(5, 3, 640, 4097));
    verify(this.sender, times(4)).sendMessage(Message.MCV2_SIZE_ERROR.build());
    final Mcv2Configuration largest = Objects.requireNonNull(this.configure(63, 63, 4096, 4096));
    assertEquals(63, largest.getColumns());
    assertEquals(4096, largest.getVideoHeight());
  }

  @Test
  void tellsTheSenderWhenNoFrameHoldsTheMap() {
    assertNull(this.configure(21));
    verify(this.sender).sendMessage(Message.MCV2_SCREEN_ERROR.build(21));
  }

  @Test
  void opensASlotOrTellsTheSenderThatEveryOnePlays() {
    final Mcv2Configuration configuration = Objects.requireNonNull(this.configure(20));
    final Mcv2PackServer.Lease lease = mock(Mcv2PackServer.Lease.class);
    when(this.packs.open(configuration)).thenReturn(lease).thenThrow(new IllegalStateException("full"));

    assertSame(lease, this.support.open(this.sender, configuration));
    assertNull(this.support.open(this.sender, configuration));

    verify(this.sender).sendMessage(Message.MCV2_FULL.build());
    assertNull(this.support.output(this.sender, configuration, DitheringArgument.NEAREST_COLOR));
  }

  @Test
  void createsTheOutputOfAScreenInItsSlot() {
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
      assertSame(result, output.getResult());
      assertSame(slotted, arguments.getFirst().getFirst());
      assertSame(viewers, arguments.getFirst().get(1));
      assertSame(DitheringArgument.NEAREST_COLOR.createAlgorithm(), arguments.getFirst().get(2));
      // the screen's steps are told to whoever started it
      @SuppressWarnings("unchecked")
      final ArgumentCaptor<Consumer<Mcv2Pacer.Change>> listeners = ArgumentCaptor.forClass(Consumer.class);
      verify(result).setPacingListener(listeners.capture());
      final Mcv2Pacer.Change change = new Mcv2Pacer.Change(
        new Mcv2Pacer.Rung(640, 384, 1),
        new Mcv2Pacer.Rung(640, 384, 2),
        true,
        20,
        16.7,
        60
      );
      listeners.getValue().accept(change);
      verify(this.sender).sendMessage(Message.MCV2_PACING.build(change.describe()));
      // the screen may step down to two smaller videos, each in a slot of the pack its lease holds
      @SuppressWarnings("unchecked")
      final ArgumentCaptor<List<int[]>> sizes = ArgumentCaptor.forClass(List.class);
      verify(result).setSmallerSizes(sizes.capture(), any());
      verify(result).setSmallerSizes(any(), Mockito.same(lease));
      assertEquals(
        List.of("426x256", "320x192"),
        sizes
          .getValue()
          .stream()
          .map(size -> size[0] + "x" + size[1])
          .toList()
      );
    }
  }

  @Test
  void recordsTheFramesOfAScreenForAMeasurement() {
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
  void stepsDownToTwoThirdsAndHalfOfTheVideo() {
    assertEquals(List.of("1280x720", "960x540"), described(Mcv2Support.smallerSizes(1920, 1080)));
    assertEquals(List.of("256x144", "192x108"), described(Mcv2Support.smallerSizes(384, 216)));
    // a size under 128 by 72 is left out
    assertEquals(List.of("160x80"), described(Mcv2Support.smallerSizes(240, 120)));
    assertEquals(List.of(), described(Mcv2Support.smallerSizes(200, 100)));
    // exactly 128 by 72 is kept
    assertEquals(List.of("128x72"), described(Mcv2Support.smallerSizes(192, 108)));
  }

  private static List<String> described(final List<int[]> sizes) {
    return sizes
      .stream()
      .map(size -> size[0] + "x" + size[1])
      .toList();
  }

  @Test
  void startsAndStopsThePackServer() {
    final Mcv2Viewers viewers = mock(Mcv2Viewers.class);
    when(this.packs.getViewers()).thenReturn(viewers);
    this.support.start();
    assertSame(viewers, this.support.getViewers());
    this.support.shutdown();
    verify(this.packs).start();
    verify(this.packs).shutdown();
    assertThrows(NullPointerException.class, () -> new Mcv2Support(null));
  }

  @Test
  void writesThePackIntoItsOwnFolderAndTellsThePlayers() {
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
}
