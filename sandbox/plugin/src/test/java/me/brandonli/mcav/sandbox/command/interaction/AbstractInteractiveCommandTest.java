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
package me.brandonli.mcav.sandbox.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.FakeWorld;
import me.brandonli.mcav.sandbox.testing.StandardErrorCapture;
import me.brandonli.mcav.sandbox.testing.TestCommandManager;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.InteractUtils;
import me.brandonli.mcav.sandbox.utils.Keys;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link AbstractInteractiveCommand} with an interactive player that is a plain string and a command that
 * records what it forwards. The Bukkit events are mocked, since their constructors are internal to the server.
 */
final class AbstractInteractiveCommandTest {

  private MCAVSandbox plugin;
  private RecordingCommand command;
  private FakeWorld fakeWorld;
  private Player player;
  private MockedStatic<InteractUtils> interactions;

  /**
   * A command that records the clicks and texts it forwards and the players it releases.
   */
  private static final class RecordingCommand extends AbstractInteractiveCommand<String> {

    private final List<String> forwarded;
    private final List<String> released;

    RecordingCommand(final MCAVSandbox plugin) {
      super(plugin);
      this.forwarded = new ArrayList<>();
      this.released = new ArrayList<>();
    }

    @Override
    protected void handleLeftClick(final String current, final int x, final int y) {
      this.forwarded.add(current + " left " + x + "," + y);
    }

    @Override
    protected void handleRightClick(final String current, final int x, final int y) {
      this.forwarded.add(current + " right " + x + "," + y);
    }

    @Override
    protected void handleTextInput(final String current, final String text) {
      this.forwarded.add(current + " text " + text);
    }

    @Override
    protected Component createStartMessage(final boolean success, final @Nullable Throwable error) {
      if (success) {
        return Component.text("started");
      }
      final String reason = error == null ? "refused" : error.getMessage();
      return Component.text("failed: " + reason);
    }

    @Override
    protected void releasePlayer(final String current) {
      this.released.add(current);
    }
  }

  @BeforeEach
  void createCommand() {
    final Server server = TestServer.reset();
    this.plugin = mock(MCAVSandbox.class);
    when(this.plugin.getServer()).thenReturn(server);
    this.command = new RecordingCommand(this.plugin);
    this.fakeWorld = new FakeWorld();
    this.player = mock(Player.class);
    this.interactions = Mockito.mockStatic(InteractUtils.class);
  }

  @AfterEach
  void shutDown() {
    this.interactions.close();
    this.command.shutdown();
  }

  private ItemFrame addScreenFrame(final double x, final double y, final double z) {
    final Location location = this.fakeWorld.location(x, y, z);
    return this.fakeWorld.addFrame(location, BlockFace.SOUTH, Keys.MAP_KEY);
  }

  // plain frames hang at the height of the screen frames, so only their position along the wall differs
  private ItemFrame addPlainFrame(final double x, final double z) {
    final Location location = this.fakeWorld.location(x, 64.5, z);
    return this.fakeWorld.addFrame(location, BlockFace.SOUTH);
  }

  private void aimAtBlock(final Block block) {
    final Vector hit = new Vector(0.5, 64.5, 0.0);
    final RayTraceResult ray = new RayTraceResult(hit, block, BlockFace.SOUTH);
    when(this.player.rayTraceBlocks(100.0, FluidCollisionMode.NEVER)).thenReturn(ray);
  }

  private static MultiplePlayerSelector mockViewers(final UUID viewer) {
    final Player viewerPlayer = mock(Player.class);
    when(viewerPlayer.getUniqueId()).thenReturn(viewer);
    final MultiplePlayerSelector viewers = mock(MultiplePlayerSelector.class);
    final List<Player> players = List.of(viewerPlayer);
    when(viewers.values()).thenReturn(players);
    return viewers;
  }

  private static ScreenSettings wallSettings(final UUID viewer, final DitheringArgument dithering) {
    final MultiplePlayerSelector viewers = mockViewers(viewer);
    final Pair<Integer, Integer> blocks = Pair.pair(4, 3);
    final Pair<Integer, Integer> resolution = Pair.pair(512, 384);
    return new ScreenSettings(viewers, blocks, resolution, 7, dithering);
  }

  // creates a screen like the commands do, with its maps and dithering mocked
  private Screen createMockedScreen() {
    final UUID viewer = UUID.randomUUID();
    final ScreenSettings settings = wallSettings(viewer, DitheringArgument.NEAREST_COLOR);
    final FunctionalVideoFilter ditherFilter = mock(FunctionalVideoFilter.class);
    try (
      final MockedConstruction<CompressedMapResult> results = Mockito.mockConstruction(CompressedMapResult.class);
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(ditherFilter);
      final Screen screen = this.command.createScreen(settings);
      final List<CompressedMapResult> constructed = results.constructed();
      assertEquals(1, constructed.size());
      return screen;
    }
  }

  private EntityDamageByEntityEvent damage(final Entity target, final Entity damager) {
    final EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
    when(event.getEntity()).thenReturn(target);
    when(event.getDamager()).thenReturn(damager);
    return event;
  }

  private BlockBreakEvent blockBreak() {
    final BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.getPlayer()).thenReturn(this.player);
    return event;
  }

  private PlayerInteractEntityEvent rightClick(final Entity clicked) {
    final PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
    when(event.getPlayer()).thenReturn(this.player);
    when(event.getRightClicked()).thenReturn(clicked);
    return event;
  }

  private static HangingBreakEvent hangingBreak(final ItemFrame frame) {
    final HangingBreakEvent event = mock(HangingBreakEvent.class);
    when(event.getEntity()).thenReturn(frame);
    return event;
  }

  private AsyncChatEvent chat(final Component message) {
    final AsyncChatEvent event = mock(AsyncChatEvent.class);
    when(event.getPlayer()).thenReturn(this.player);
    when(event.message()).thenReturn(message);
    return event;
  }

  private void assertForwarded(final String... expected) {
    final List<String> expectedForwarded = List.of(expected);
    assertEquals(expectedForwarded, this.command.forwarded);
  }

  private void assertReleased(final String... expected) {
    final List<String> expectedReleased = List.of(expected);
    assertEquals(expectedReleased, this.command.released);
  }

  private static void assertReceivedText(final CommandSender sender, final String... expected) {
    final List<String> messages = Components.receivedText(sender);
    final List<String> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, messages);
  }

  private void assertScreenDithersOnto(
    final Screen screen,
    final CompressedMapResult maps,
    final FunctionalVideoFilter ditherFilter,
    final MockedStatic<DitherFilter> dithers
  ) {
    final CompressedMapResult screenMaps = screen.getMaps();
    assertSame(maps, screenMaps);
    final DitherAlgorithm algorithm = DitheringArgument.NEAREST_COLOR.createAlgorithm();
    dithers.verify(() -> DitherFilter.dither(algorithm, maps));
    verify(ditherFilter).start();
    final VideoPipelineStep pipeline = screen.getPipeline();
    final VideoFilter filter = pipeline.getFilter();
    assertSame(ditherFilter, filter);
    assertSame(maps, this.command.result);
  }

  private static void assertWallConfiguration(final List<List<?>> arguments, final UUID viewer) {
    final List<?> constructorArguments = arguments.getFirst();
    final MapConfiguration configuration = (MapConfiguration) constructorArguments.getFirst();
    final Collection<UUID> viewerCollection = configuration.getViewers();
    final List<UUID> viewerIds = new ArrayList<>(viewerCollection);
    final List<UUID> expectedViewers = List.of(viewer);
    final int map = configuration.getMap();
    final int blockWidth = configuration.getMapBlockWidth();
    final int blockHeight = configuration.getMapBlockHeight();
    final int width = configuration.getMapWidthResolution();
    final int height = configuration.getMapHeightResolution();
    assertEquals(7, map);
    assertEquals(4, blockWidth);
    assertEquals(3, blockHeight);
    assertEquals(512, width);
    assertEquals(384, height);
    assertEquals(expectedViewers, viewerIds);
  }

  @Test
  void listensForEventsOnceRegistered() {
    final TestCommandManager manager = new TestCommandManager();
    final AnnotationParser<CommandSender> parser = new AnnotationParser<>(manager, CommandSender.class);

    this.command.registerFeature(parser);

    final PluginManager pluginManager = TestServer.pluginManager();
    verify(pluginManager).registerEvents(this.command, this.plugin);
  }

  @Test
  void releasesEverythingAndStopsListeningWhenShutDown() {
    this.command.player = "browser";
    final CompressedMapResult maps = mock(CompressedMapResult.class);
    this.command.result = maps;
    this.interactions.close();

    try (final MockedStatic<HandlerList> handlers = Mockito.mockStatic(HandlerList.class)) {
      this.command.shutdown();
      handlers.verify(() -> HandlerList.unregisterAll(this.command));
    }

    this.interactions = Mockito.mockStatic(InteractUtils.class);
    this.assertReleased("browser");
    verify(maps).release();
    assertNull(this.command.player);
    assertNull(this.command.result);
    final boolean stopped = this.command.service.isShutdown();
    assertTrue(stopped);
  }

  @Test
  void releasesNothingWhenNothingRuns() {
    this.command.releaseCurrent();

    final boolean nothingReleased = this.command.released.isEmpty();
    assertTrue(nothingReleased);
  }

  @Test
  void parsesDimensionsAndTellsTheSenderWhenTheyAreInvalid() {
    final CommandSender sender = mock(CommandSender.class);

    final Pair<Integer, Integer> valid = AbstractInteractiveCommand.parseDimensions(sender, "5x3");
    assertNotNull(valid);
    final int width = valid.getFirst();
    final int height = valid.getSecond();
    assertEquals(5, width);
    assertEquals(3, height);
    verify(sender, never()).sendMessage(any(Component.class));

    final Pair<Integer, Integer> invalid = AbstractInteractiveCommand.parseDimensions(sender, "5 by 3");
    assertNull(invalid);
    final List<Component> messages = Components.received(sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expectedMessages = List.of(error);
    assertEquals(expectedMessages, messages);
  }

  @Test
  void createsTheMapsOfTheScreenAndReleasesTheOldOnes() {
    this.command.player = "old";
    final CompressedMapResult oldMaps = mock(CompressedMapResult.class);
    this.command.result = oldMaps;
    final UUID viewer = UUID.randomUUID();
    final ScreenSettings settings = wallSettings(viewer, DitheringArgument.NEAREST_COLOR);
    final FunctionalVideoFilter ditherFilter = mock(FunctionalVideoFilter.class);
    final List<List<?>> arguments = new ArrayList<>();

    try (
      final MockedConstruction<CompressedMapResult> results = Mockito.mockConstruction(CompressedMapResult.class, (_, context) -> {
        final List<?> constructorArguments = context.arguments();
        arguments.add(constructorArguments);
      });
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(ditherFilter);
      final Screen screen = this.command.createScreen(settings);
      final List<CompressedMapResult> constructed = results.constructed();
      final CompressedMapResult maps = constructed.getFirst();
      this.assertScreenDithersOnto(screen, maps, ditherFilter, dithers);
    }

    this.assertReleased("old");
    verify(oldMaps).release();
    assertNull(this.command.player);
    assertWallConfiguration(arguments, viewer);
  }

  @Test
  void givesEveryScreenItsOwnTemporalDitheringAlgorithm() {
    final UUID viewer = UUID.randomUUID();
    final ScreenSettings settings = wallSettings(viewer, DitheringArgument.FLOYD_STEINBERG_TEMPORAL);
    final FunctionalVideoFilter ditherFilter = mock(FunctionalVideoFilter.class);
    final ArgumentCaptor<DitherAlgorithm> algorithms = ArgumentCaptor.forClass(DitherAlgorithm.class);

    try (
      final MockedConstruction<CompressedMapResult> _ = Mockito.mockConstruction(CompressedMapResult.class);
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(ditherFilter);
      this.command.createScreen(settings);
      this.command.createScreen(settings);
      dithers.verify(() -> DitherFilter.dither(algorithms.capture(), any()), times(2));
    }

    final List<DitherAlgorithm> used = algorithms.getAllValues();
    final DitherAlgorithm first = used.getFirst();
    final DitherAlgorithm second = used.getLast();
    assertNotSame(first, second);
  }

  @Test
  void tellsTheSenderWhenThePlayerStarted() {
    final Screen screen = this.createMockedScreen();
    final CompressedMapResult maps = screen.getMaps();
    final CommandSender sender = mock(CommandSender.class);
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);

    this.command.reportStartWhenDone(sender, "browser", screen, start, "the browser");

    assertReceivedText(sender, "started");
    assertEquals("browser", this.command.player);
    assertSame(maps, this.command.result);
    final boolean nothingReleased = this.command.released.isEmpty();
    assertTrue(nothingReleased);
    verify(maps, never()).release();
  }

  @Test
  void releasesAPlayerThatRefusedToStart() {
    final Screen screen = this.createMockedScreen();
    final CompressedMapResult maps = screen.getMaps();
    final CommandSender sender = mock(CommandSender.class);
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(false);

    this.command.reportStartWhenDone(sender, "browser", screen, start, "the browser");

    assertReceivedText(sender, "failed: refused");
    this.assertReleased("browser");
    verify(maps).release();
    assertNull(this.command.player);
    assertNull(this.command.result);
  }

  @Test
  void namesWhatFailedToStartInTheLog() {
    final Screen screen = this.createMockedScreen();
    final CommandSender sender = mock(CommandSender.class);
    final IllegalStateException missingChrome = new IllegalStateException("no chrome");
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(missingChrome);
    final String output;
    try (final StandardErrorCapture capture = StandardErrorCapture.start()) {
      this.command.reportStartWhenDone(sender, "browser", screen, start, "the browser");
      output = capture.getOutput();
    }
    final boolean named = output.contains("the browser");
    assertTrue(named, "the log of a failed start names what could not be started");
  }

  @Test
  void releasesAPlayerThatFailedToStart() {
    final Screen screen = this.createMockedScreen();
    final CompressedMapResult maps = screen.getMaps();
    final CommandSender sender = mock(CommandSender.class);
    final IllegalStateException missingChrome = new IllegalStateException("no chrome");
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(missingChrome);

    this.command.reportStartWhenDone(sender, "browser", screen, start, "the browser");

    assertReceivedText(sender, "failed: no chrome");
    this.assertReleased("browser");
    verify(maps).release();
  }

  @Test
  void releasesOnlyItsOwnPlayerAndMapsWhenAnOverlappingStartFails() {
    final CommandSender sender = mock(CommandSender.class);
    final Screen firstScreen = this.createMockedScreen();
    final CompressedMapResult firstMaps = firstScreen.getMaps();
    final CompletableFuture<Boolean> firstStart = new CompletableFuture<>();
    this.command.reportStartWhenDone(sender, "first", firstScreen, firstStart, "the first browser");

    // a second create command replaces the first player while it is still starting
    final Screen secondScreen = this.createMockedScreen();
    final CompressedMapResult secondMaps = secondScreen.getMaps();
    final CompletableFuture<Boolean> secondStart = new CompletableFuture<>();
    this.command.reportStartWhenDone(sender, "second", secondScreen, secondStart, "the second browser");
    this.assertReleased("first");
    verify(firstMaps).release();

    final IllegalStateException missingChrome = new IllegalStateException("no chrome");
    firstStart.completeExceptionally(missingChrome);
    assertEquals("second", this.command.player);
    assertSame(secondMaps, this.command.result);
    this.assertReleased("first");
    verify(firstMaps, times(1)).release();
    verify(secondMaps, never()).release();

    secondStart.complete(false);
    this.assertReleased("first", "second");
    verify(secondMaps).release();
    assertNull(this.command.player);
    assertNull(this.command.result);
    assertReceivedText(sender, "failed: no chrome", "failed: refused");
  }

  @Test
  void leavesAFailedPlayerAloneThatWasAlreadyReleased() {
    final CommandSender sender = mock(CommandSender.class);
    final Screen screen = this.createMockedScreen();
    final CompressedMapResult maps = screen.getMaps();
    final CompletableFuture<Boolean> start = new CompletableFuture<>();
    this.command.reportStartWhenDone(sender, "browser", screen, start, "the browser");

    // the screen is released while the player is still starting, so no player is running when the start fails
    this.command.releaseCurrent();
    this.assertReleased("browser");
    verify(maps).release();

    final IllegalStateException missingChrome = new IllegalStateException("no chrome");
    start.completeExceptionally(missingChrome);

    this.assertReleased("browser");
    verify(maps, times(1)).release();
    assertNull(this.command.player);
    assertNull(this.command.result);
    assertReceivedText(sender, "failed: no chrome");
  }

  @Test
  void stillReleasesAFailedPlayerWhenThePluginIsDisabledMeanwhile() {
    final Screen screen = this.createMockedScreen();
    final CompressedMapResult maps = screen.getMaps();
    final BukkitScheduler scheduler = TestServer.scheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(new IllegalPluginAccessException("disabled"));
    final CommandSender sender = mock(CommandSender.class);
    final CompletableFuture<Boolean> start = new CompletableFuture<>();
    this.command.reportStartWhenDone(sender, "browser", screen, start, "the browser");

    start.complete(false);

    this.assertReleased("browser");
    verify(maps).release();
    verify(sender, never()).sendMessage(any(Component.class));
  }

  @Test
  void forwardsBrokenBlocksBehindTheScreenAsLeftClicks() {
    this.command.player = "browser";
    final Block block = this.fakeWorld.block(0, 64, -1);
    this.aimAtBlock(block);
    final Zombie zombie = mock(Zombie.class);
    final Location zombieLocation = this.fakeWorld.location(0.4, 64.4, -0.6);
    when(zombie.getLocation()).thenReturn(zombieLocation);
    this.fakeWorld.addEntity(zombie);
    this.addPlainFrame(0.5, -0.5);
    final ItemFrame far = this.addScreenFrame(0.5, 64.5, -0.1);
    final ItemFrame near = this.addScreenFrame(0.1, 64.1, -0.9);
    this.addScreenFrame(0.4, 64.4, -0.6);
    this.interactions.when(() -> InteractUtils.getBoardCoordinates(this.player, near)).thenReturn(new int[] { 10, 20 });
    this.interactions.when(() -> InteractUtils.getBoardCoordinates(this.player, far)).thenReturn(new int[] { 99, 99 });
    final BlockBreakEvent event = this.blockBreak();

    this.command.onBlockBreak(event);

    verify(event).setCancelled(true);
    this.assertForwarded("browser left 10,20");
  }

  @Test
  void forwardsTheClickToTheFirstOfTwoEquallyCloseScreens() {
    this.command.player = "browser";
    final Block block = this.fakeWorld.block(0, 64, -1);
    this.aimAtBlock(block);
    // both frames are exactly as far from the block, so the one that was found first stays the closest
    final ItemFrame first = this.addScreenFrame(0.2, 64.2, -0.8);
    final ItemFrame second = this.addScreenFrame(-0.2, 63.8, -0.8);
    this.interactions.when(() -> InteractUtils.getBoardCoordinates(this.player, first)).thenReturn(new int[] { 10, 20 });
    this.interactions.when(() -> InteractUtils.getBoardCoordinates(this.player, second)).thenReturn(new int[] { 99, 99 });
    final BlockBreakEvent event = this.blockBreak();

    this.command.onBlockBreak(event);

    verify(event).setCancelled(true);
    this.assertForwarded("browser left 10,20");
  }

  @Test
  void letsBlocksBreakWhenNoPlayerRuns() {
    final Block block = this.fakeWorld.block(0, 64, -1);
    this.aimAtBlock(block);
    this.addScreenFrame(0.5, 64.5, -0.5);
    final BlockBreakEvent event = this.blockBreak();

    this.command.onBlockBreak(event);

    verify(event, never()).setCancelled(anyBoolean());
    this.interactions.verifyNoInteractions();
  }

  @Test
  void letsBlocksBreakWhenThePlayerLooksAtNothing() {
    this.command.player = "browser";
    final BlockBreakEvent withoutRay = this.blockBreak();
    this.command.onBlockBreak(withoutRay);

    final Vector hit = new Vector(0.5, 64.5, 0.0);
    final RayTraceResult entityRay = new RayTraceResult(hit);
    when(this.player.rayTraceBlocks(100.0, FluidCollisionMode.NEVER)).thenReturn(entityRay);
    final BlockBreakEvent withoutBlock = this.blockBreak();
    this.command.onBlockBreak(withoutBlock);

    verify(withoutRay, never()).setCancelled(anyBoolean());
    verify(withoutBlock, never()).setCancelled(anyBoolean());
    this.interactions.verifyNoInteractions();
  }

  @Test
  void letsBlocksBreakAwayFromScreens() {
    this.command.player = "browser";
    final Block block = this.fakeWorld.block(0, 64, -1);
    this.aimAtBlock(block);
    final BlockBreakEvent event = this.blockBreak();

    this.command.onBlockBreak(event);

    verify(event, never()).setCancelled(anyBoolean());
    this.interactions.verifyNoInteractions();
  }

  @Test
  void letsBlocksBreakWhenTheScreenIsIncomplete() {
    this.command.player = "browser";
    final Block block = this.fakeWorld.block(0, 64, -1);
    this.aimAtBlock(block);
    final ItemFrame frame = this.addScreenFrame(0.5, 64.5, -0.5);
    this.interactions.when(() -> InteractUtils.getBoardCoordinates(this.player, frame)).thenReturn(null);
    final BlockBreakEvent event = this.blockBreak();

    this.command.onBlockBreak(event);

    verify(event, never()).setCancelled(anyBoolean());
    final boolean nothingForwarded = this.command.forwarded.isEmpty();
    assertTrue(nothingForwarded);
  }

  @Test
  void forwardsPunchesOnTheScreenAsLeftClicks() {
    this.command.player = "browser";
    final ItemFrame frame = this.addScreenFrame(0.5, 64.5, 0.0);
    this.interactions.when(() -> InteractUtils.getBoardCoordinates(this.player)).thenReturn(new int[] { 3, 4 });
    final EntityDamageByEntityEvent event = this.damage(frame, this.player);

    this.command.onScreenDamage(event);

    verify(event).setCancelled(true);
    this.assertForwarded("browser left 3,4");
  }

  @Test
  void protectsTheScreenWithoutClickingWhenNoPlayerRuns() {
    final ItemFrame frame = this.addScreenFrame(0.5, 64.5, 0.0);
    final EntityDamageByEntityEvent event = this.damage(frame, this.player);

    this.command.onScreenDamage(event);

    verify(event).setCancelled(true);
    this.interactions.verifyNoInteractions();
  }

  @Test
  void protectsTheScreenFromArrowsWithoutClicking() {
    this.command.player = "browser";
    final ItemFrame frame = this.addScreenFrame(0.5, 64.5, 0.0);
    final Arrow arrow = mock(Arrow.class);
    when(arrow.getShooter()).thenReturn(this.player);
    final EntityDamageByEntityEvent event = this.damage(frame, arrow);

    this.command.onScreenDamage(event);

    verify(event).setCancelled(true);
    this.interactions.verifyNoInteractions();
  }

  @Test
  void ignoresDamageThatNoPlayerCaused() {
    this.command.player = "browser";
    final ItemFrame frame = this.addScreenFrame(0.5, 64.5, 0.0);
    final Arrow arrow = mock(Arrow.class);
    final Skeleton skeleton = mock(Skeleton.class);
    when(arrow.getShooter()).thenReturn(skeleton);
    final EntityDamageByEntityEvent byArrow = this.damage(frame, arrow);
    final Zombie zombie = mock(Zombie.class);
    final EntityDamageByEntityEvent byZombie = this.damage(frame, zombie);

    this.command.onScreenDamage(byArrow);
    this.command.onScreenDamage(byZombie);

    verify(byArrow, never()).setCancelled(anyBoolean());
    verify(byZombie, never()).setCancelled(anyBoolean());
  }

  @Test
  void ignoresDamageToOtherEntities() {
    this.command.player = "browser";
    final ItemFrame plainFrame = this.addPlainFrame(0.5, 0.0);
    final Zombie zombie = mock(Zombie.class);
    final EntityDamageByEntityEvent frameEvent = this.damage(plainFrame, this.player);
    final EntityDamageByEntityEvent zombieEvent = this.damage(zombie, this.player);

    this.command.onScreenDamage(frameEvent);
    this.command.onScreenDamage(zombieEvent);

    verify(frameEvent, never()).setCancelled(anyBoolean());
    verify(zombieEvent, never()).setCancelled(anyBoolean());
  }

  @Test
  void protectsTheScreenWhenThePunchMissesThePicture() {
    this.command.player = "browser";
    final ItemFrame frame = this.addScreenFrame(0.5, 64.5, 0.0);
    this.interactions.when(() -> InteractUtils.getBoardCoordinates(this.player)).thenReturn(null);
    final EntityDamageByEntityEvent event = this.damage(frame, this.player);

    this.command.onScreenDamage(event);

    verify(event).setCancelled(true);
    final boolean nothingForwarded = this.command.forwarded.isEmpty();
    assertTrue(nothingForwarded);
  }

  @Test
  void forwardsRightClicksOnTheScreen() {
    this.command.player = "browser";
    final ItemFrame frame = this.addScreenFrame(0.5, 64.5, 0.0);
    this.interactions.when(() -> InteractUtils.getBoardCoordinates(this.player)).thenReturn(new int[] { 7, 8 });
    final PlayerInteractEntityEvent event = this.rightClick(frame);

    this.command.onPlayerInteractEntity(event);

    verify(event).setCancelled(true);
    this.assertForwarded("browser right 7,8");
  }

  @Test
  void ignoresRightClicksThatCannotBeForwarded() {
    final ItemFrame frame = this.addScreenFrame(0.5, 64.5, 0.0);
    final PlayerInteractEntityEvent withoutPlayer = this.rightClick(frame);
    this.command.onPlayerInteractEntity(withoutPlayer);

    this.command.player = "browser";
    final Zombie zombie = mock(Zombie.class);
    final PlayerInteractEntityEvent onZombie = this.rightClick(zombie);
    this.command.onPlayerInteractEntity(onZombie);
    this.interactions.when(() -> InteractUtils.getBoardCoordinates(this.player)).thenReturn(null);
    final PlayerInteractEntityEvent missed = this.rightClick(frame);
    this.command.onPlayerInteractEntity(missed);

    verify(withoutPlayer, never()).setCancelled(anyBoolean());
    verify(onZombie, never()).setCancelled(anyBoolean());
    verify(missed, never()).setCancelled(anyBoolean());
    final boolean nothingForwarded = this.command.forwarded.isEmpty();
    assertTrue(nothingForwarded);
  }

  @Test
  void forwardsTheChatOfPlayersWhoEnabledInteraction() {
    this.command.player = "browser";
    final Component enable = Component.text("on");
    final Component disable = Component.text("off");
    final Component message = Component.text("hello world");

    this.command.toggleInteraction(this.player, enable, disable);
    final AsyncChatEvent event = this.chat(message);
    this.command.onChatMessage(event);
    verify(event).setCancelled(true);
    this.assertForwarded("browser text hello world");

    this.command.toggleInteraction(this.player, enable, disable);
    final AsyncChatEvent later = this.chat(message);
    this.command.onChatMessage(later);
    verify(later, never()).setCancelled(anyBoolean());

    final List<Component> replies = Components.received(this.player);
    final List<Component> expectedReplies = List.of(enable, disable);
    assertEquals(expectedReplies, replies);
  }

  @Test
  void keepsTheChatWhenNoPlayerRuns() {
    final Component enable = Component.text("on");
    final Component disable = Component.text("off");
    final Component message = Component.text("hello world");
    this.command.toggleInteraction(this.player, enable, disable);
    final AsyncChatEvent event = this.chat(message);

    this.command.onChatMessage(event);

    verify(event, never()).setCancelled(anyBoolean());
    final boolean nothingForwarded = this.command.forwarded.isEmpty();
    assertTrue(nothingForwarded);
  }

  @Test
  void protectsScreenFramesFromBreaking() {
    final ItemFrame frame = this.addScreenFrame(0.5, 64.5, 0.0);
    final ItemFrame plainFrame = this.addPlainFrame(1.5, 0.0);
    final HangingBreakEvent screenEvent = hangingBreak(frame);
    final HangingBreakEvent plainEvent = hangingBreak(plainFrame);

    this.command.onScreenBreak(screenEvent);
    this.command.onScreenBreak(plainEvent);

    verify(screenEvent).setCancelled(true);
    verify(plainEvent, never()).setCancelled(anyBoolean());
  }

  @Test
  void releasesThePlayerAndTellsTheSender() {
    this.command.player = "browser";
    final CommandSender sender = mock(CommandSender.class);
    final Component message = Component.text("released");

    this.command.releaseResource(sender, message);

    verify(sender).sendMessage(message);
    this.assertReleased("browser");
    assertNull(this.command.player);
  }
}
