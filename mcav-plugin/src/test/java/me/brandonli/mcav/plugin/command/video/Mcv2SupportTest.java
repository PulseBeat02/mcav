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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
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
import net.kyori.adventure.text.Component;
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

  private static final String WORLD_NAME = "world";

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
    when(frame.getWorld()).thenReturn(this.world);
    when(this.world.getName()).thenReturn(WORLD_NAME);
    when(this.world.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(frame));
    when(TestServer.getServer().getWorlds()).thenReturn(List.of(this.world));
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
  void shouldFindEveryFrameHoldingAMap() {
    final ItemFrame wrongMap = frame(map(Material.FILLED_MAP, true, 3));
    final ItemFrame earlier = frame(map(Material.FILLED_MAP, true, 7));
    final ItemFrame noId = frame(map(Material.FILLED_MAP, false, 7));
    final ItemFrame other = frame(map(Material.STONE, true, 7));
    final ItemStack plain = mock(ItemStack.class);
    when(plain.getType()).thenReturn(Material.FILLED_MAP);
    when(plain.getItemMeta()).thenReturn(mock(ItemMeta.class));
    final ItemFrame notAMap = frame(plain);
    final ItemFrame wanted = frame(map(Material.FILLED_MAP, true, 7));
    final World first = mock(World.class);
    when(first.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(wrongMap, earlier, noId));
    final World second = mock(World.class);
    when(second.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(other, notAMap, wanted));

    final List<ItemFrame> frames = Mcv2Support.findFrames(List.of(first, second), 7);
    final List<ItemFrame> expected = List.of(earlier, wanted);
    assertEquals(expected, frames);

    final List<ItemFrame> noFrames = Mcv2Support.findFrames(List.of(first, second), 8);
    assertEquals(List.of(), noFrames);

    final boolean holdsMap = Mcv2Support.holdsMap(map(Material.FILLED_MAP, true, 3), 7);
    assertFalse(holdsMap);

    final boolean mcv2SupportHoldsMap = Mcv2Support.holdsMap(map(Material.FILLED_MAP, true, 7), 7);
    assertTrue(mcv2SupportHoldsMap);
  }

  @Test
  void shouldConfigureTheScreenOnTheWallThatHoldsTheMap() {
    final Mcv2Configuration configuration = this.configure(MAP_ID).orElseThrow();

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
    final List<UUID> expectedViewers = List.of(this.viewer);
    assertEquals(expectedViewers, copy);

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
    System.setProperty(Mcv2Support.PAGE_SLOTS_PROPERTY, "8");
    System.setProperty(Mcv2Support.BACKLOG_PROPERTY, "65536");
    try {
      final Mcv2Configuration limited = this.configure(MAP_ID).orElseThrow();

      final int pageSlots = limited.getPageSlots();
      assertEquals(8, pageSlots);

      final long backlogLimit = limited.getBacklogLimit();
      assertEquals(65536, backlogLimit);

      final int unsentLimit = limited.getUnsentLimit();
      assertEquals(Mcv2Configuration.DEFAULT_UNSENT_LIMIT, unsentLimit);
    } finally {
      System.clearProperty(Mcv2Support.PAGE_SLOTS_PROPERTY);
      System.clearProperty(Mcv2Support.BACKLOG_PROPERTY);
    }
  }

  @Test
  void shouldTakeTheBacklogLimitOfAMeasurement() {
    System.setProperty(Mcv2Support.BACKLOG_PROPERTY, "65536");
    try {
      final long limitedBacklogLimit = this.support.readBacklogLimit();
      assertEquals(65536, limitedBacklogLimit);

      final int unsentLimit = this.support.readUnsentLimit();
      assertEquals(Mcv2Configuration.DEFAULT_UNSENT_LIMIT, unsentLimit);

      System.setProperty(Mcv2Support.BACKLOG_PROPERTY, "none");

      final long secondBacklogLimit = this.support.readBacklogLimit();
      assertEquals(Long.MAX_VALUE, secondBacklogLimit);
      // no backpressure at all: no cap on a viewer's unsent bytes either

      final int unlimitedUnsentLimit = this.support.readUnsentLimit();
      assertEquals(0, unlimitedUnsentLimit);
    } finally {
      System.clearProperty(Mcv2Support.BACKLOG_PROPERTY);
    }

    final long backlogLimit = this.support.readBacklogLimit();
    assertEquals(Mcv2Configuration.DEFAULT_BACKLOG_LIMIT, backlogLimit);
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
  void shouldRefuseAMapThatSeveralFramesHold() {
    final World nether = mock(World.class);
    when(nether.getName()).thenReturn("world_nether");
    final ItemFrame copy = frame(map(Material.FILLED_MAP, true, MAP_ID));
    when(copy.getWorld()).thenReturn(nether);
    when(copy.getLocation()).thenReturn(new Location(nether, 2010.5, -59.5, 2990.03));
    when(nether.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(copy));
    when(TestServer.getServer().getWorlds()).thenReturn(List.of(this.world, nether));

    final Optional<Mcv2Configuration> configured = this.configure(MAP_ID);
    assertEquals(Optional.empty(), configured);

    final String reason =
      "map 20 hangs in 2 item frames, at world 5 64 7, world_nether 2010 -60 2990. MCV2 plays on one wall per map, so remove the other walls or build each screen with its own map id!";
    final Component refused = Message.MCV2_SCREEN_DUPLICATE.build(reason);
    verify(this.sender).sendMessage(refused);
  }

  @Test
  void shouldOpenASlotOrTellTheSenderThatEveryOnePlays() {
    final Mcv2Configuration configuration = this.configure(MAP_ID).orElseThrow();
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
    final Mcv2Configuration configuration = this.configure(MAP_ID).orElseThrow();
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
      final List<String> expectedSizes = List.of("426x256", "320x192");
      assertEquals(expectedSizes, list);
    }
  }

  private void recordArguments(final MockedConstruction.Context context, final List<List<?>> arguments) {
    arguments.add(context.arguments());
  }

  @Test
  void shouldRecordTheFramesOfAScreenForAMeasurement() throws IOException {
    final Mcv2Configuration configuration = this.configure(MAP_ID).orElseThrow();
    final Mcv2PackServer.Lease lease = mock(Mcv2PackServer.Lease.class);
    when(lease.getConfiguration()).thenReturn(configuration.withSlot(SLOT, Mcv2Configuration.DEFAULT_PAGE_MAP + PAGE_MAP_OFFSET, 0));
    when(this.packs.open(configuration)).thenReturn(lease);
    System.setProperty(Mcv2Support.RECORD_PROPERTY, this.folder.toString());
    try (final MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class)) {
      this.support.output(this.sender, configuration, DitheringArgument.NEAREST_COLOR);
      final ArgumentCaptor<FrameRecorder> recorders = ArgumentCaptor.forClass(FrameRecorder.class);
      verify(results.constructed().getFirst()).setFrameListener(recorders.capture());

      final FrameRecorder recorder = recorders.getValue();
      recorder.accept(new byte[] { 7 });
    } finally {
      System.clearProperty(Mcv2Support.RECORD_PROPERTY);
    }
    try (final MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class)) {
      this.support.output(this.sender, configuration, DitheringArgument.NEAREST_COLOR);
      verify(results.constructed().getFirst(), Mockito.never()).setFrameListener(any());
    }

    final List<Path> files = this.listFolder();
    final int count = files.size();
    assertEquals(1, count);

    final Path file = files.getFirst();
    final Path fileName = file.getFileName();
    final String name = fileName.toString();
    final boolean named = name.startsWith("screen-%s-".formatted(SLOT));
    assertTrue(named, name);

    final byte[] allBytes = Files.readAllBytes(file);
    final byte[] expected = { 1, 0, 0, 0, 7 };
    assertArrayEquals(expected, allBytes);
  }

  private List<Path> listFolder() throws IOException {
    try (final Stream<Path> files = Files.list(this.folder)) {
      return files.toList();
    }
  }

  @Test
  void shouldStepDownToTwoThirdsAndHalfOfTheVideo() {
    final List<String> described = this.described(this.support.computeSmallerSizes(1920, 1080));
    final List<String> expectedSizes = List.of("1280x720", "960x540");
    assertEquals(expectedSizes, described);

    final List<String> secondDescribed = this.described(this.support.computeSmallerSizes(VIDEO_HEIGHT, 216));
    final List<String> expectedSecondSizes = List.of("256x144", "192x108");
    assertEquals(expectedSecondSizes, secondDescribed);
    // a size under 128 by 72 is left out

    final List<String> thirdDescribed = this.described(this.support.computeSmallerSizes(240, 120));
    final List<String> expectedThirdSizes = List.of("160x80");
    assertEquals(expectedThirdSizes, thirdDescribed);

    final List<String> fourthDescribed = this.described(this.support.computeSmallerSizes(200, 100));
    assertEquals(List.of(), fourthDescribed);
    // exactly 128 by 72 is kept

    final List<String> fifthDescribed = this.described(this.support.computeSmallerSizes(192, 108));
    final List<String> expectedFifthSizes = List.of("128x72");
    assertEquals(expectedFifthSizes, fifthDescribed);
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
  }

  @Test
  void shouldRefuseANullPackServer() {
    final String message = assertThrows(NullPointerException.class, () -> new Mcv2Support(null)).getMessage();
    assertEquals("Pack server must not be null!", message);
  }

  @Test
  void shouldWriteThePackIntoItsOwnFolderAndTellThePlayers() {
    final Function<Path, PackHosting> hosting = PackHosting::injector;
    final List<List<?>> arguments = new ArrayList<>();
    try (
      final MockedConstruction<Mcv2PackServer> servers = Mockito.mockConstruction(Mcv2PackServer.class, (_, context) ->
        this.recordArguments(context, arguments)
      )
    ) {
      final Mcv2Support real = new Mcv2Support(this.folder, hosting);
      real.shutdown();
      verify(servers.constructed().getFirst()).shutdown();
    }
    final List<?> serverArguments = arguments.getFirst();
    final Path expected = this.folder.resolve(MCV2_FOLDER_NAME).resolve(PACK_FOLDER_NAME);
    final Object packFolder = serverArguments.getFirst();
    assertEquals(expected, packFolder);

    final Object packHosting = serverArguments.get(1);
    assertSame(hosting, packHosting);

    @SuppressWarnings("unchecked") // the pack server's constructor takes a Consumer<Player>
    final Consumer<Player> onOffered = (Consumer<Player>) serverArguments.get(3);
    @SuppressWarnings("unchecked") // the pack server's constructor takes a Consumer<Player>
    final Consumer<Player> onRefused = (Consumer<Player>) serverArguments.get(4);
    final Player offered = mock(Player.class);
    final Player refused = mock(Player.class);
    onOffered.accept(offered);
    onRefused.accept(refused);
    verify(offered).sendMessage(Message.MCV2_PACK.build());
    verify(refused).sendMessage(Message.MCV2_REFUSED.build());
  }

  @Test
  void shouldShowTheDebugViewOnlyWhenItsPropertyIsSet() {
    final List<Object> debugViews = new ArrayList<>();
    try (
      final MockedConstruction<Mcv2PackServer> _ = Mockito.mockConstruction(Mcv2PackServer.class, (_, context) ->
        this.recordDebugView(context, debugViews)
      )
    ) {
      System.setProperty(Mcv2Support.DEBUG_VIEW_PROPERTY, "true");
      try {
        final Mcv2Support _ = new Mcv2Support(this.folder, PackHosting::injector);
      } finally {
        System.clearProperty(Mcv2Support.DEBUG_VIEW_PROPERTY);
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
