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
import java.util.Optional;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

final class Mcv2SupportTest {

  private static final int MAP_ID = 20;

  private static final int COLUMNS = 5;
  private static final int ROWS = 3;
  private static final int VIDEO_WIDTH = 640;
  private static final int VIDEO_HEIGHT = 384;

  private static final int SLOT = 3;
  private static final int PAGE_MAP_OFFSET = 16;

  private static final String DIMENSION_SEPARATOR = "x";

  private static final String MCV2_FOLDER_NAME = "mcv2";
  private static final String PACK_FOLDER_NAME = "pack";

  @TempDir
  private Path folder;

  private Mcv2PackServer packs;

  private Mcv2Support support;

  private CommandSender sender;

  private World world;

  private final UUID viewer = UUID.randomUUID();

  @BeforeEach
  void createSupport() {
    TestServer.reset();
    this.packs = mock(Mcv2PackServer.class);
    this.support = new Mcv2Support(this.packs);
    this.sender = mock(CommandSender.class);
    this.world = mock(World.class);
    final ItemFrame frame = frame(map(Material.FILLED_MAP, true, MAP_ID));
    when(frame.getLocation()).thenReturn(new Location(this.world, 5.5, 64.5, 7.03));
    when(frame.getFacing()).thenReturn(BlockFace.SOUTH);
    when(this.world.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(frame));
    when(TestServer.server().getWorlds()).thenReturn(List.of(this.world));
  }

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

    final ItemFrame frame = Mcv2Support.findFrame(List.of(first, second), 7).orElseThrow();
    assertSame(wanted, frame);

    final Optional<ItemFrame> mcv2SupportFrame = Mcv2Support.findFrame(List.of(first, second), 8);
    assertEquals(Optional.empty(), mcv2SupportFrame);

    final boolean holdsMap = Mcv2Support.holdsMap(map(Material.FILLED_MAP, true, 3), 7);
    assertFalse(holdsMap);

    final boolean mcv2SupportHoldsMap = Mcv2Support.holdsMap(map(Material.FILLED_MAP, true, 7), 7);
    assertTrue(mcv2SupportHoldsMap);
  }

  @Test
  void shouldConfigureTheScreenOnTheWallThatHoldsTheMap() {
    final Mcv2Configuration configuration = this.configure(20).orElseThrow();

    final Location expected = new Location(this.world, 5, 64, 7);
    final Location origin = configuration.getOrigin();
    assertEquals(expected, origin);

    final BlockFace facing = configuration.getFacing();
    assertEquals(BlockFace.SOUTH, facing);

    final int map = configuration.getMap();
    assertEquals(MAP_ID, map);

    final int columns = configuration.getColumns();
    assertEquals(COLUMNS, columns);

    final int rows = configuration.getRows();
    assertEquals(ROWS, rows);

    final int videoWidth = configuration.getVideoWidth();
    assertEquals(VIDEO_WIDTH, videoWidth);

    final int videoHeight = configuration.getVideoHeight();
    assertEquals(VIDEO_HEIGHT, videoHeight);

    final Settings settings = configuration.getSettings();
    assertEquals(Settings.DEFAULT, settings);

    final List<UUID> copy = List.copyOf(configuration.getViewers());
    assertEquals(List.of(this.viewer), copy);

    final long backlogLimit = configuration.getBacklogLimit();
    assertEquals(Mcv2Configuration.DEFAULT_BACKLOG_LIMIT, backlogLimit);

    final int pageSlots = configuration.getPageSlots();
    assertEquals(Mcv2Configuration.MAXIMUM_PAGE_SLOTS, pageSlots);
  }

  private Optional<Mcv2Configuration> configure(final int map) {
    return this.support.configure(
      this.sender,
      Pair.pair(COLUMNS, ROWS),
      Pair.pair(VIDEO_WIDTH, VIDEO_HEIGHT),
      map,
      Settings.DEFAULT,
      List.of(this.viewer)
    );
  }

  private Optional<Mcv2Configuration> configure(final int columns, final int rows, final int width, final int height) {
    return this.support.configure(
      this.sender,
      Pair.pair(columns, rows),
      Pair.pair(width, height),
      MAP_ID,
      Settings.DEFAULT,
      List.of(this.viewer)
    );
  }

  @Test
  void shouldTakeThePageSlotsAndTheBacklogLimitOfAMeasurement() {
    System.setProperty(VideoMcv2Command.PAGE_SLOTS_PROPERTY, "8");
    System.setProperty(VideoMcv2Command.BACKLOG_PROPERTY, "65536");
    try {
      final Mcv2Configuration limited = this.configure(20).orElseThrow();

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
    final Optional<Mcv2Configuration> configured = this.configure(64, ROWS, VIDEO_WIDTH, VIDEO_HEIGHT);
    assertEquals(Optional.empty(), configured);

    final Optional<Mcv2Configuration> secondConfigured = this.configure(COLUMNS, 64, VIDEO_WIDTH, VIDEO_HEIGHT);
    assertEquals(Optional.empty(), secondConfigured);

    final Optional<Mcv2Configuration> thirdConfigured = this.configure(COLUMNS, ROWS, 4097, VIDEO_HEIGHT);
    assertEquals(Optional.empty(), thirdConfigured);

    final Optional<Mcv2Configuration> fourthConfigured = this.configure(COLUMNS, ROWS, VIDEO_WIDTH, 4097);
    assertEquals(Optional.empty(), fourthConfigured);

    verify(this.sender, times(4)).sendMessage(Message.MCV2_SIZE_ERROR.build());

    final Mcv2Configuration largest = this.configure(63, 63, 4096, 4096).orElseThrow();

    final int columns = largest.getColumns();
    assertEquals(63, columns);

    final int videoHeight = largest.getVideoHeight();
    assertEquals(4096, videoHeight);
  }

  @Test
  void shouldTellTheSenderWhenNoFrameHoldsTheMap() {
    final Optional<Mcv2Configuration> configured = this.configure(21);
    assertEquals(Optional.empty(), configured);

    verify(this.sender).sendMessage(Message.MCV2_SCREEN_ERROR.build(21));
  }

  @Test
  void shouldOpenASlotOrTellTheSenderThatEveryOnePlays() {
    final Mcv2Configuration configuration = this.configure(20).orElseThrow();
    final Mcv2PackServer.Lease lease = mock(Mcv2PackServer.Lease.class);
    when(this.packs.open(configuration)).thenReturn(lease).thenThrow(new IllegalStateException("full!"));

    final Mcv2PackServer.Lease opened = this.support.open(this.sender, configuration).orElseThrow();
    assertSame(lease, opened);

    final Optional<Mcv2PackServer.Lease> supportOpened = this.support.open(this.sender, configuration);
    assertEquals(Optional.empty(), supportOpened);

    verify(this.sender).sendMessage(Message.MCV2_FULL.build());

    final Optional<Mcv2Output> mcv2Output = this.support.output(this.sender, configuration, DitheringArgument.NEAREST_COLOR);
    assertEquals(Optional.empty(), mcv2Output);
  }

  @Test
  void shouldCreateTheOutputOfAScreenInItsSlot() {
    final Mcv2Configuration configuration = this.configure(20).orElseThrow();
    final Mcv2Configuration slotted = configuration.withSlot(SLOT, Mcv2Configuration.DEFAULT_PAGE_MAP + PAGE_MAP_OFFSET, 1234);
    final Mcv2PackServer.Lease lease = mock(Mcv2PackServer.Lease.class);
    when(lease.getConfiguration()).thenReturn(slotted);
    when(this.packs.open(configuration)).thenReturn(lease);
    final Mcv2Viewers viewers = mock(Mcv2Viewers.class);
    when(this.packs.getViewers()).thenReturn(viewers);
    final List<List<?>> arguments = new ArrayList<>();
    try (
      final MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class, (_, context) ->
        this.recordArguments(context, arguments)
      )
    ) {
      final Mcv2Output output = this.support.output(this.sender, configuration, DitheringArgument.NEAREST_COLOR).orElseThrow();
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

      @SuppressWarnings("unchecked") // a class literal has no type arguments
      final ArgumentCaptor<Consumer<Mcv2Pacer.Change>> listeners = ArgumentCaptor.forClass(Consumer.class);
      verify(result).setPacingListener(listeners.capture());

      final Mcv2Pacer.Change change = Mcv2Pacer.Change.of(
        Mcv2Pacer.Rung.of(VIDEO_WIDTH, VIDEO_HEIGHT, 1),
        Mcv2Pacer.Rung.of(VIDEO_WIDTH, VIDEO_HEIGHT, 2),
        true,
        20,
        16.7,
        60
      );
      listeners.getValue().accept(change);
      verify(this.sender).sendMessage(Message.MCV2_PACING.build(change.describe()));

      @SuppressWarnings("unchecked") // a class literal has no type arguments
      final ArgumentCaptor<List<int[]>> sizes = ArgumentCaptor.forClass(List.class);
      verify(result).setSmallerSizes(sizes.capture(), any());
      verify(result).setSmallerSizes(any(), Mockito.same(lease));

      final List<String> list = sizes
        .getValue()
        .stream()
        .map(size -> size[0] + DIMENSION_SEPARATOR + size[1])
        .toList();
      assertEquals(List.of("426x256", "320x192"), list);
    }
  }

  private void recordArguments(final MockedConstruction.Context context, final List<List<?>> arguments) {
    arguments.add(context.arguments());
  }

  @Test
  void shouldRecordTheFramesOfAScreenForAMeasurement() {
    final Mcv2Configuration configuration = this.configure(20).orElseThrow();
    final Mcv2PackServer.Lease lease = mock(Mcv2PackServer.Lease.class);
    when(lease.getConfiguration()).thenReturn(configuration.withSlot(SLOT, Mcv2Configuration.DEFAULT_PAGE_MAP + PAGE_MAP_OFFSET, 0));
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
    final List<String> described = this.described(Mcv2Support.smallerSizes(1920, 1080));
    assertEquals(List.of("1280x720", "960x540"), described);

    final List<String> secondDescribed = this.described(Mcv2Support.smallerSizes(VIDEO_HEIGHT, 216));
    assertEquals(List.of("256x144", "192x108"), secondDescribed);
    // a size under 128 by 72 is left out

    final List<String> thirdDescribed = this.described(Mcv2Support.smallerSizes(240, 120));
    assertEquals(List.of("160x80"), thirdDescribed);

    final List<String> fourthDescribed = this.described(Mcv2Support.smallerSizes(200, 100));
    assertEquals(List.of(), fourthDescribed);
    // exactly 128 by 72 is kept

    final List<String> fifthDescribed = this.described(Mcv2Support.smallerSizes(192, 108));
    assertEquals(List.of("128x72"), fifthDescribed);
  }

  private List<String> described(final List<int[]> sizes) {
    return sizes
      .stream()
      .map(size -> size[0] + DIMENSION_SEPARATOR + size[1])
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
        this.assertPackFolder(context)
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

  private void assertPackFolder(final MockedConstruction.Context context) {
    assertEquals(this.folder.resolve(MCV2_FOLDER_NAME).resolve(PACK_FOLDER_NAME), context.arguments().getFirst());
  }

  @Test
  void shouldShowTheDebugViewOnlyWhenItsPropertyIsSet() {
    final List<Object> debugViews = new ArrayList<>();
    try (
      final MockedConstruction<Mcv2PackServer> _ = Mockito.mockConstruction(Mcv2PackServer.class, (_, context) ->
        this.recordDebugView(context, debugViews)
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

  private void recordDebugView(final MockedConstruction.Context context, final List<Object> debugViews) {
    debugViews.add(context.arguments().get(2));
  }
}
