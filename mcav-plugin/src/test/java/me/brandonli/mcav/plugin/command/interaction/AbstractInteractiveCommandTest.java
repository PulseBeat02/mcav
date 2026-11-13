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
package me.brandonli.mcav.plugin.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.video.Mcv2Output;
import me.brandonli.mcav.plugin.command.video.Mcv2Support;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.FakeWorld;
import me.brandonli.mcav.plugin.testing.StandardErrorCapture;
import me.brandonli.mcav.plugin.testing.TestCommandManager;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.InteractUtils;
import me.brandonli.mcav.plugin.utils.Keys;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
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
import org.mockito.invocation.InvocationOnMock;

// Bukkit events are mocked: their constructors are internal to the server
final class AbstractInteractiveCommandTest {

  private static final String INTERACT_PERMISSION = "mcav.test.interact";

  private static final int BLOCK_Y = 64;
  private static final double BLOCK_CENTER_X = 0.5;
  private static final double BLOCK_CENTER_Y = 64.5;
  private static final double BLOCK_CENTER_Z = -0.5;

  private static final double CORNER_FRAME_X = 0.1;
  private static final double CORNER_FRAME_Y = 64.1;
  private static final double CORNER_FRAME_Z = -0.9;
  private static final double FRONT_FRAME_Z = -0.1;

  private static final double RAY_TRACE_DISTANCE = 100.0;

  private static final int AIMED_BOARD_X = 10;
  private static final int AIMED_BOARD_Y = 20;
  private static final int PUNCHED_BOARD_X = 3;
  private static final int PUNCHED_BOARD_Y = 4;
  private static final int SOURCE_BOARD_X = 200;
  private static final int SOURCE_BOARD_Y = 100;
  private static final int OTHER_BOARD_COORDINATE = 99;

  private static final int WALL_BLOCK_WIDTH = 4;
  private static final int WALL_BLOCK_HEIGHT = 3;
  private static final int WALL_PIXEL_WIDTH = 512;
  private static final int WALL_PIXEL_HEIGHT = 384;

  private static final int SOURCE_PIXEL_WIDTH = 384;
  private static final int SOURCE_PIXEL_HEIGHT = 256;

  private static final int FIRST_MAP_ID = 7;
  private static final int MAP_ID_BELOW_RANGE = 6;
  private static final int MAP_ID_ABOVE_RANGE = 19;

  private static final String BROWSER = "browser";
  private static final String BROWSER_DESCRIPTION = "the browser";
  private static final String FIRST_BROWSER = "first";
  private static final String SECOND_BROWSER = "second";

  private static final String STARTED_TEXT = "started";
  private static final String REFUSED_TEXT = "failed: refused";
  private static final String ENABLED_TEXT = "on";
  private static final String DISABLED_TEXT = "off";
  private static final String CHAT_TEXT = "hello world";

  private static final String COORDINATE_SEPARATOR = ",";
  private static final String AIMED_LEFT_CLICK = "browser left 10,20";

  private static final String MISSING_CHROME_MESSAGE = "no chrome!";

  private static final String DITHER_CREATION_FAILURE_MESSAGE = "dither unavailable!";

  private static final String FATAL_FAILURE_MESSAGE = "fatal sentinel!";

  private static final String STARTUP_TASK_FAILURE_MESSAGE = "startup task failed!";

  private MCAVSandbox plugin;
  private RecordingCommand command;
  private FakeWorld fakeWorld;
  private Player player;
  private MockedStatic<InteractUtils> interactions;

  @BeforeEach
  void createCommand() {
    final Server server = TestServer.reset();
    this.plugin = mock(MCAVSandbox.class);
    when(this.plugin.getOnlinePlayers()).thenReturn(new OnlinePlayers());
    final PluginDataConfigurationMapper defaults = mock(PluginDataConfigurationMapper.class);
    when(defaults.getMcv2DefaultCodec()).thenReturn(MapCodec.DITHER);
    when(this.plugin.getConfiguration()).thenReturn(defaults);
    when(this.plugin.getServer()).thenReturn(server);
    this.command = new RecordingCommand(this.plugin);
    this.fakeWorld = new FakeWorld();
    this.player = mock(Player.class);
    when(this.player.hasPermission(INTERACT_PERMISSION)).thenReturn(true);
    this.interactions = Mockito.mockStatic(InteractUtils.class);
  }

  @AfterEach
  void shutDown() {
    this.interactions.close();
    this.command.shutdown();
  }

  @Test
  void shouldListenForEventsOnceRegistered() {
    final TestCommandManager manager = new TestCommandManager();
    final AnnotationParser<CommandSender> parser = new AnnotationParser<>(manager, CommandSender.class);

    this.command.registerFeature(parser);

    final PluginManager pluginManager = TestServer.getPluginManager();
    verify(pluginManager).registerEvents(this.command, this.plugin);
  }

  @Test
  void shouldRefuseANullPluginOrExecutor() {
    final ExecutorService executor = mock(ExecutorService.class);
    assertThrows(NullPointerException.class, () -> new RecordingCommand(null));
    assertThrows(NullPointerException.class, () -> new RecordingCommand(null, executor));
    assertThrows(NullPointerException.class, () -> new RecordingCommand(this.plugin, null));
  }

  @Test
  void shouldReleaseEverythingAndStopListeningWhenShutDown() {
    this.command.player = BROWSER;
    final FunctionalVideoFilter maps = mock(FunctionalVideoFilter.class);
    this.command.output = maps;
    this.interactions.close();

    try (final MockedStatic<HandlerList> handlers = Mockito.mockStatic(HandlerList.class)) {
      this.command.shutdown();
      handlers.verify(() -> HandlerList.unregisterAll(this.command));
    }

    this.interactions = Mockito.mockStatic(InteractUtils.class);
    this.assertReleased(BROWSER);
    verify(maps).release();
    assertNull(this.command.player);
    assertNull(this.command.output);

    final boolean stopped = this.command.service.isShutdown();
    assertTrue(stopped);
  }

  private void assertReleased(final String... expected) {
    final List<String> expectedReleased = List.of(expected);
    assertEquals(expectedReleased, this.command.released);
  }

  @Test
  void shouldStopAPlayerInTheBackgroundUntilItShutsDown() throws InterruptedException {
    final Thread caller = Thread.currentThread();
    final CountDownLatch stopped = new CountDownLatch(1);
    final AtomicReference<Thread> where = new AtomicReference<>();
    this.command.releaseInTheBackground(() -> this.recordStoppingThread(where, stopped));

    final boolean completed = stopped.await(5, TimeUnit.SECONDS);
    assertTrue(completed);

    final Thread whereWhere = where.get();
    assertNotSame(caller, whereWhere, "the calling thread, often the main thread, does not wait");

    this.command.releaseInTheBackground(this::throwReleaseFailure);
    this.command.shutdown();
    final AtomicReference<Thread> atShutdown = new AtomicReference<>();
    this.command.releaseInTheBackground(() -> atShutdown.set(Thread.currentThread()));

    final Thread atShutdownAtShutdown = atShutdown.get();
    assertSame(caller, atShutdownAtShutdown, "a command that shuts down stops its player before it is gone");
  }

  private void recordStoppingThread(final AtomicReference<Thread> where, final CountDownLatch stopped) {
    where.set(Thread.currentThread());
    stopped.countDown();
  }

  private void throwReleaseFailure() {
    final String message = "release broke!";
    throw new IllegalStateException(message);
  }

  @Test
  void shouldStopAPlayerAtOnceWhenItsThreadTakesNoMoreWork() {
    final ExecutorService stoppedWorker = Executors.newSingleThreadExecutor();
    stoppedWorker.shutdown();
    final RecordingCommand command = new RecordingCommand(this.plugin, stoppedWorker);
    final AtomicReference<Thread> where = new AtomicReference<>();
    command.releaseInTheBackground(() -> where.set(Thread.currentThread()));

    final Thread expected = Thread.currentThread();
    final Thread whereWhere = where.get();
    assertSame(expected, whereWhere);
  }

  @Test
  void shouldReleaseNothingWhenNothingRuns() {
    this.command.releaseCurrent();

    final boolean nothingReleased = this.command.released.isEmpty();
    assertTrue(nothingReleased);
  }

  @Test
  void shouldParseDimensionsAndTellTheSenderWhenTheyAreInvalid() {
    final CommandSender sender = mock(CommandSender.class);

    final Pair<Integer, Integer> valid = AbstractInteractiveCommand.parseDimensions(sender, "5x3").orElseThrow();

    final int width = valid.getFirst();
    final int height = valid.getSecond();
    assertEquals(5, width);
    assertEquals(3, height);
    verify(sender, never()).sendMessage(any(Component.class));

    final Optional<Pair<Integer, Integer>> invalid = AbstractInteractiveCommand.parseDimensions(sender, "5 by 3");
    assertEquals(Optional.empty(), invalid);

    final List<Component> messages = Components.received(sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expectedMessages = List.of(error);
    assertEquals(expectedMessages, messages);
  }

  @Test
  void shouldCreateTheMapsOfTheScreenAndReleaseTheOldOnes() {
    this.command.player = "old";
    final FunctionalVideoFilter oldMaps = mock(FunctionalVideoFilter.class);
    this.command.output = oldMaps;
    final UUID viewer = UUID.randomUUID();
    final ScreenSettings settings = this.wallSettings(viewer, DitheringArgument.NEAREST_COLOR);
    final FunctionalVideoFilter ditherFilter = mock(FunctionalVideoFilter.class);
    final List<List<?>> arguments = new ArrayList<>();

    try (
      final MockedConstruction<CompressedMapResult> results = Mockito.mockConstruction(CompressedMapResult.class, (_, context) ->
        this.recordArguments(context, arguments)
      );
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
    this.assertWallConfiguration(arguments, viewer);
  }

  private void recordArguments(final MockedConstruction.Context context, final List<List<?>> arguments) {
    final List<?> constructorArguments = context.arguments();
    arguments.add(constructorArguments);
  }

  private ScreenSettings wallSettings(final UUID viewer, final DitheringArgument dithering) {
    final MultiplePlayerSelector viewers = this.mockViewers(viewer);
    final Pair<Integer, Integer> blocks = Pair.pair(WALL_BLOCK_WIDTH, WALL_BLOCK_HEIGHT);
    final Pair<Integer, Integer> resolution = Pair.pair(WALL_PIXEL_WIDTH, WALL_PIXEL_HEIGHT);
    return new ScreenSettings(mock(CommandSender.class), viewers, blocks, resolution, FIRST_MAP_ID, dithering, MapCodec.DITHER);
  }

  private MultiplePlayerSelector mockViewers(final UUID viewer) {
    final Player viewerPlayer = mock(Player.class);
    when(viewerPlayer.getUniqueId()).thenReturn(viewer);
    final MultiplePlayerSelector viewers = mock(MultiplePlayerSelector.class);
    final List<Player> players = List.of(viewerPlayer);
    when(viewers.values()).thenReturn(players);

    return viewers;
  }

  private void assertScreenDithersOnto(
    final Screen screen,
    final CompressedMapResult maps,
    final FunctionalVideoFilter ditherFilter,
    final MockedStatic<DitherFilter> dithers
  ) {
    final FunctionalVideoFilter screenMaps = screen.getOutput();
    assertSame(ditherFilter, screenMaps);

    for (int mapId = FIRST_MAP_ID; mapId < MAP_ID_ABOVE_RANGE; mapId++) {
      final boolean secondOwnsMap = screen.ownsMap(mapId);
      assertTrue(secondOwnsMap, "all twelve maps of the 4x3 wall accept interaction");
    }

    final boolean ownsMap = screen.ownsMap(MAP_ID_BELOW_RANGE);
    assertFalse(ownsMap);

    final boolean screenOwnsMap = screen.ownsMap(MAP_ID_ABOVE_RANGE);
    assertFalse(screenOwnsMap);

    final DitherAlgorithm algorithm = DitheringArgument.NEAREST_COLOR.createAlgorithm();
    dithers.verify(() -> DitherFilter.dither(algorithm, maps));
    verify(ditherFilter).start();

    final VideoPipelineStep pipeline = screen.getPipeline();
    final VideoFilter filter = pipeline.getFilter();
    assertSame(ditherFilter, filter);
    assertSame(ditherFilter, this.command.output);
  }

  private void assertWallConfiguration(final List<List<?>> arguments, final UUID viewer) {
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
    assertEquals(FIRST_MAP_ID, map);
    assertEquals(WALL_BLOCK_WIDTH, blockWidth);
    assertEquals(WALL_BLOCK_HEIGHT, blockHeight);
    assertEquals(WALL_PIXEL_WIDTH, width);
    assertEquals(WALL_PIXEL_HEIGHT, height);
    assertEquals(expectedViewers, viewerIds);
  }

  @Test
  void shouldGiveEveryScreenItsOwnTemporalDitheringAlgorithm() {
    final UUID viewer = UUID.randomUUID();
    final ScreenSettings settings = this.wallSettings(viewer, DitheringArgument.FLOYD_STEINBERG_TEMPORAL);
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
  void shouldTellTheSenderWhenThePlayerStarted() {
    final Screen screen = this.createMockedScreen();
    final FunctionalVideoFilter maps = screen.getOutput();
    final CommandSender sender = mock(CommandSender.class);
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);

    this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER_DESCRIPTION);

    this.assertReceivedText(sender, STARTED_TEXT);
    assertEquals(BROWSER, this.command.player);
    assertSame(maps, this.command.output);

    final boolean nothingReleased = this.command.released.isEmpty();
    assertTrue(nothingReleased);
    verify(maps, never()).release();
  }

  private Screen createMockedScreen() {
    final UUID viewer = UUID.randomUUID();
    return this.createMockedScreen(this.wallSettings(viewer, DitheringArgument.NEAREST_COLOR));
  }

  private Screen createMockedScreen(final ScreenSettings settings) {
    final FunctionalVideoFilter ditherFilter = mock(FunctionalVideoFilter.class);
    try (
      final MockedConstruction<CompressedMapResult> results = Mockito.mockConstruction(CompressedMapResult.class);
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(ditherFilter);
      final Screen screen = this.command.createScreen(settings);
      final List<CompressedMapResult> constructed = results.constructed();

      final int size = constructed.size();
      assertEquals(1, size);

      return screen;
    }
  }

  private void assertReceivedText(final CommandSender sender, final String... expected) {
    final List<String> messages = Components.receivedText(sender);
    final List<String> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, messages);
  }

  @Test
  void shouldReleaseAPlayerThatRefusedToStart() {
    final Screen screen = this.createMockedScreen();
    final FunctionalVideoFilter maps = screen.getOutput();
    final CommandSender sender = mock(CommandSender.class);
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(false);

    this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER_DESCRIPTION);

    this.assertReceivedText(sender, REFUSED_TEXT);
    this.assertReleased(BROWSER);
    verify(maps).release();
    assertNull(this.command.player);
    assertNull(this.command.output);
  }

  @Test
  void shouldNameWhatFailedToStartInTheLog() {
    final Screen screen = this.createMockedScreen();
    final CommandSender sender = mock(CommandSender.class);
    final IllegalStateException missingChrome = new IllegalStateException(MISSING_CHROME_MESSAGE);
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(missingChrome);
    final String output;
    try (final StandardErrorCapture capture = StandardErrorCapture.start()) {
      this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER_DESCRIPTION);
      output = capture.getOutput();
    }
    final boolean named = output.contains(BROWSER_DESCRIPTION);
    assertTrue(named, "the log of a failed start names what could not be started");
  }

  @Test
  void shouldReleaseAPlayerThatFailedToStart() {
    final Screen screen = this.createMockedScreen();
    final FunctionalVideoFilter maps = screen.getOutput();
    final CommandSender sender = mock(CommandSender.class);
    final IllegalStateException missingChrome = new IllegalStateException(MISSING_CHROME_MESSAGE);
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(missingChrome);

    this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER_DESCRIPTION);

    this.assertReceivedText(sender, "failed: no chrome!");
    this.assertReleased(BROWSER);
    verify(maps).release();
  }

  @Test
  void shouldReleaseOnlyItsOwnPlayerAndMapsWhenAnOverlappingStartFails() {
    final CommandSender sender = mock(CommandSender.class);
    final Screen firstScreen = this.createMockedScreen();
    final FunctionalVideoFilter firstMaps = firstScreen.getOutput();
    final CompletableFuture<Boolean> firstStart = new CompletableFuture<>();
    this.command.reportStartWhenDone(sender, FIRST_BROWSER, firstScreen, firstStart, "the first browser");

    // a second create command replaces the first player while it is still starting
    final Screen secondScreen = this.createMockedScreen();
    final FunctionalVideoFilter secondMaps = secondScreen.getOutput();
    final CompletableFuture<Boolean> secondStart = new CompletableFuture<>();
    this.command.reportStartWhenDone(sender, SECOND_BROWSER, secondScreen, secondStart, "the second browser");
    this.assertReleased(FIRST_BROWSER);
    verify(firstMaps).release();

    final IllegalStateException missingChrome = new IllegalStateException(MISSING_CHROME_MESSAGE);
    firstStart.completeExceptionally(missingChrome);
    assertEquals(SECOND_BROWSER, this.command.player);
    assertSame(secondMaps, this.command.output);
    this.assertReleased(FIRST_BROWSER);
    verify(firstMaps, times(1)).release();
    verify(secondMaps, never()).release();

    secondStart.complete(false);
    this.assertReleased(FIRST_BROWSER, SECOND_BROWSER);
    verify(secondMaps).release();
    assertNull(this.command.player);
    assertNull(this.command.output);
    this.assertReceivedText(sender, REFUSED_TEXT);
  }

  @Test
  void shouldLeaveAFailedPlayerAloneThatWasAlreadyReleased() {
    final CommandSender sender = mock(CommandSender.class);
    final Screen screen = this.createMockedScreen();
    final FunctionalVideoFilter maps = screen.getOutput();
    final CompletableFuture<Boolean> start = new CompletableFuture<>();
    this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER_DESCRIPTION);

    // the screen is released while the player is still starting, so no player is running when the start fails
    this.command.releaseCurrent();
    this.assertReleased(BROWSER);
    verify(maps).release();

    final IllegalStateException missingChrome = new IllegalStateException(MISSING_CHROME_MESSAGE);
    start.completeExceptionally(missingChrome);

    this.assertReleased(BROWSER);
    verify(maps, times(1)).release();
    assertNull(this.command.player);
    assertNull(this.command.output);
    verify(sender, never()).sendMessage(any(Component.class));
  }

  @Test
  void shouldStillReleaseAFailedPlayerWhenThePluginIsDisabledMeanwhile() {
    final Screen screen = this.createMockedScreen();
    final FunctionalVideoFilter maps = screen.getOutput();
    final BukkitScheduler scheduler = TestServer.getScheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(new IllegalPluginAccessException("disabled!"));
    final CommandSender sender = mock(CommandSender.class);
    final CompletableFuture<Boolean> start = new CompletableFuture<>();
    this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER_DESCRIPTION);

    start.complete(false);

    this.assertReleased(BROWSER);
    verify(maps).release();
    verify(sender, never()).sendMessage(any(Component.class));
  }

  @Test
  void shouldReleaseTheOutputOfAFailedStartOnTheMainThreadAndTellTheSender() throws InterruptedException {
    TestServer.resetWithDeferredTasks();
    final Screen screen = this.createMockedScreen();
    final FunctionalVideoFilter maps = screen.getOutput();
    final AtomicInteger mainThreadReleases = new AtomicInteger();
    Mockito.doAnswer(_ -> this.releaseOnTheMainThreadOnly(mainThreadReleases))
      .when(maps)
      .release();
    final CommandSender sender = mock(CommandSender.class);
    final CompletableFuture<Boolean> start = new CompletableFuture<>();
    this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER_DESCRIPTION);

    final IllegalStateException missingChrome = new IllegalStateException(MISSING_CHROME_MESSAGE);
    final Thread worker = Thread.ofPlatform().start(() -> start.completeExceptionally(missingChrome));
    worker.join();
    TestServer.runPendingTasks();

    final int releases = mainThreadReleases.get();
    assertEquals(1, releases);
    this.assertReleased(BROWSER);
    assertNull(this.command.output);
    this.assertReceivedText(sender, "failed: no chrome!");
  }

  // Paper refuses to remove the page frames of an MCV2 screen off the main thread
  private @Nullable Object releaseOnTheMainThreadOnly(final AtomicInteger mainThreadReleases) {
    if (!Bukkit.isPrimaryThread()) {
      throw new IllegalStateException("Maps may only be released on the main thread!");
    }

    mainThreadReleases.incrementAndGet();
    return null;
  }

  @Test
  void shouldNeverStartAQueuedPlayerAfterShutdown() throws InterruptedException {
    final List<Runnable> pending = this.useDeferredStartup();
    final Screen screen = this.createMockedScreen();
    final AtomicBoolean opened = new AtomicBoolean();
    final ExecutorService executor = this.command.startExecutor(BROWSER, screen);
    final CompletableFuture<Boolean> start = CompletableFuture.supplyAsync(() -> this.markOpened(opened), executor);
    final CommandSender sender = mock(CommandSender.class);
    this.command.reportStartWhenDone(sender, BROWSER, screen, start, "queued browser");
    this.command.shutdown();
    final Runnable task = pending.getFirst();
    task.run();
    final boolean didOpen = opened.get();
    final boolean cancelled = start.isCancelled();
    assertFalse(didOpen);
    assertTrue(cancelled);
    this.assertReleased(BROWSER);
    assertNull(this.command.player);
    verify(sender, never()).sendMessage(any(Component.class));
  }

  private Boolean markOpened(final AtomicBoolean opened) {
    opened.set(true);

    return true;
  }

  private List<Runnable> useDeferredStartup() throws InterruptedException {
    this.command.shutdown();
    final ExecutorService executor = mock(ExecutorService.class);
    final List<Runnable> pending = new ArrayList<>();
    Mockito.doAnswer(invocation -> this.deferTask(invocation, pending))
      .when(executor)
      .execute(any(Runnable.class));
    when(executor.awaitTermination(Mockito.anyLong(), any(TimeUnit.class))).thenReturn(true);
    this.command = new RecordingCommand(this.plugin, executor);

    return pending;
  }

  private @Nullable Object deferTask(final InvocationOnMock invocation, final List<Runnable> pending) {
    final Runnable task = invocation.getArgument(0);
    pending.add(task);

    return null;
  }

  @Test
  void shouldReleaseAnInFlightPlayerOnlyAfterItsCancelledStartupReturns() throws InterruptedException {
    final List<Runnable> pending = this.useDeferredStartup();
    final Screen screen = this.createMockedScreen();
    final ExecutorService executor = this.command.startExecutor(BROWSER, screen);
    final CompletableFuture<Boolean> start = CompletableFuture.supplyAsync(this::releaseDuringStartup, executor);
    final CommandSender sender = mock(CommandSender.class);
    this.command.reportStartWhenDone(sender, BROWSER, screen, start, "late browser");
    final Runnable task = pending.getFirst();
    task.run();
    this.assertReleased(BROWSER);

    final FunctionalVideoFilter maps = screen.getOutput();
    verify(maps, times(1)).release();
    assertNull(this.command.player);
    verify(sender, never()).sendMessage(any(Component.class));
  }

  private Boolean releaseDuringStartup() {
    // Models a release command arriving after startup began but before it creates its native resource.
    this.command.releaseCurrent();
    this.assertReleased();

    return true;
  }

  @Test
  void shouldIgnoreAQueuedSuccessMessageAfterTheScreenWasReleased() {
    TestServer.resetWithDeferredTasks();
    final Screen screen = this.createMockedScreen();
    final CommandSender sender = mock(CommandSender.class);
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER);
    this.command.releaseCurrent();
    TestServer.runPendingTasks();
    verify(sender, never()).sendMessage(any(Component.class));
  }

  @Test
  void shouldNotForwardClicksOnAnotherCommandsMapRange() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final ItemFrame otherScreen = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    this.setMapId(otherScreen, MAP_ID_ABOVE_RANGE); // this command owns 7..18, so 19 belongs to another screen
    final PlayerInteractEntityEvent right = this.rightClick(otherScreen);
    final EntityDamageByEntityEvent left = this.damage(otherScreen, this.player);
    this.interactions
      .when(() -> InteractUtils.findBoardCoordinates(this.player, otherScreen))
      .thenReturn(Optional.of(new int[] { PUNCHED_BOARD_X, PUNCHED_BOARD_Y }));
    this.command.onPlayerInteractEntity(right);
    this.command.onScreenDamage(left);
    this.assertForwarded();
    verify(right, never()).setCancelled(anyBoolean());
    verify(left).setCancelled(true); // protecting marked frames is independent of routing their input
    this.interactions.verifyNoInteractions();
  }

  private void setMapId(final ItemFrame frame, final int id) {
    final ItemStack item = mock(ItemStack.class);
    final MapMeta metadata = mock(MapMeta.class);
    final MapView map = mock(MapView.class);
    when(map.getId()).thenReturn(id);
    when(metadata.getMapView()).thenReturn(map);
    when(item.getItemMeta()).thenReturn(metadata);
    when(frame.getItem()).thenReturn(item);
  }

  private ItemFrame addScreenFrame(final double frameX, final double frameY, final double frameZ) {
    final Location location = this.fakeWorld.createLocation(frameX, frameY, frameZ);
    final ItemFrame frame = this.fakeWorld.addFrame(location, BlockFace.SOUTH, Keys.MAP_KEY);
    this.setMapId(frame, FIRST_MAP_ID);
    return frame;
  }

  private EntityDamageByEntityEvent damage(final Entity target, final Entity damager) {
    final EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
    when(event.getEntity()).thenReturn(target);
    when(event.getDamager()).thenReturn(damager);

    return event;
  }

  private PlayerInteractEntityEvent rightClick(final Entity clicked) {
    final PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
    when(event.getPlayer()).thenReturn(this.player);
    when(event.getRightClicked()).thenReturn(clicked);

    return event;
  }

  private void assertForwarded(final String... expected) {
    final List<String> expectedForwarded = List.of(expected);
    assertEquals(expectedForwarded, this.command.forwarded);
  }

  @Test
  void shouldReleaseMapsAndStopListeningEvenWhenPlayerCleanupFails() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final FunctionalVideoFilter maps = Objects.requireNonNull(this.command.output);
    final IllegalStateException failure = new IllegalStateException("backend cleanup!");
    this.command.releaseFailure = failure;
    try (final MockedStatic<HandlerList> handlers = Mockito.mockStatic(HandlerList.class)) {
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, this.command::shutdown);
      assertSame(failure, thrown);
      verify(maps).release();
      handlers.verify(() -> HandlerList.unregisterAll(this.command));

      final boolean stopped = this.command.service.isShutdown();
      assertTrue(stopped);
    }
  }

  @Test
  void shouldForwardBrokenBlocksBehindTheScreenAsLeftClicks() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final Block block = this.fakeWorld.block(0, BLOCK_Y, -1);
    this.aimAtBlock(block);
    final Zombie zombie = mock(Zombie.class);
    final Location zombieLocation = this.fakeWorld.createLocation(0.4, 64.4, -0.6);
    when(zombie.getLocation()).thenReturn(zombieLocation);
    this.fakeWorld.addEntity(zombie);
    this.addPlainFrame(BLOCK_CENTER_X, BLOCK_CENTER_Z);
    final ItemFrame far = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, FRONT_FRAME_Z);
    final ItemFrame near = this.addScreenFrame(CORNER_FRAME_X, CORNER_FRAME_Y, CORNER_FRAME_Z);
    this.addScreenFrame(0.4, 64.4, -0.6);
    this.interactions
      .when(() -> InteractUtils.findBoardCoordinates(this.player, near))
      .thenReturn(Optional.of(new int[] { AIMED_BOARD_X, AIMED_BOARD_Y }));
    this.interactions
      .when(() -> InteractUtils.findBoardCoordinates(this.player, far))
      .thenReturn(Optional.of(new int[] { OTHER_BOARD_COORDINATE, OTHER_BOARD_COORDINATE }));
    final BlockBreakEvent event = this.blockBreak();

    this.command.onBlockBreak(event);

    verify(event).setCancelled(true);
    this.assertForwarded(AIMED_LEFT_CLICK);
  }

  private ItemFrame addPlainFrame(final double frameX, final double frameZ) {
    final Location location = this.fakeWorld.createLocation(frameX, BLOCK_CENTER_Y, frameZ);
    return this.fakeWorld.addFrame(location, BlockFace.SOUTH);
  }

  private void aimAtBlock(final Block block) {
    final Vector hit = new Vector(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    final RayTraceResult ray = new RayTraceResult(hit, block, BlockFace.SOUTH);
    when(this.player.rayTraceBlocks(RAY_TRACE_DISTANCE, FluidCollisionMode.NEVER)).thenReturn(ray);
  }

  private BlockBreakEvent blockBreak() {
    final BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.getPlayer()).thenReturn(this.player);

    return event;
  }

  @Test
  void shouldForwardTheClickToTheFirstOfTwoEquallyCloseScreens() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final Block block = this.fakeWorld.block(0, BLOCK_Y, -1);
    this.aimAtBlock(block);
    // both frames are exactly as far from the block, so the one that was found first stays the closest
    final ItemFrame first = this.addScreenFrame(0.2, 64.2, -0.8);
    final ItemFrame second = this.addScreenFrame(-0.2, 63.8, -0.8);
    this.interactions
      .when(() -> InteractUtils.findBoardCoordinates(this.player, first))
      .thenReturn(Optional.of(new int[] { AIMED_BOARD_X, AIMED_BOARD_Y }));
    this.interactions
      .when(() -> InteractUtils.findBoardCoordinates(this.player, second))
      .thenReturn(Optional.of(new int[] { OTHER_BOARD_COORDINATE, OTHER_BOARD_COORDINATE }));
    final BlockBreakEvent event = this.blockBreak();

    this.command.onBlockBreak(event);

    verify(event).setCancelled(true);
    this.assertForwarded(AIMED_LEFT_CLICK);
  }

  @Test
  void shouldLetBlocksBreakWhenNoPlayerRuns() {
    final Block block = this.fakeWorld.block(0, BLOCK_Y, -1);
    this.aimAtBlock(block);
    this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, BLOCK_CENTER_Z);
    final BlockBreakEvent event = this.blockBreak();

    this.command.onBlockBreak(event);

    verify(event, never()).setCancelled(anyBoolean());
    this.interactions.verifyNoInteractions();
  }

  @Test
  void shouldLetBlocksBreakWhenThePlayerLooksAtNothing() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final BlockBreakEvent withoutRay = this.blockBreak();
    this.command.onBlockBreak(withoutRay);

    final Vector hit = new Vector(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    final RayTraceResult entityRay = new RayTraceResult(hit);
    when(this.player.rayTraceBlocks(RAY_TRACE_DISTANCE, FluidCollisionMode.NEVER)).thenReturn(entityRay);
    final BlockBreakEvent withoutBlock = this.blockBreak();
    this.command.onBlockBreak(withoutBlock);

    verify(withoutRay, never()).setCancelled(anyBoolean());
    verify(withoutBlock, never()).setCancelled(anyBoolean());
    this.interactions.verifyNoInteractions();
  }

  @Test
  void shouldLetBlocksBreakAwayFromScreens() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final Block block = this.fakeWorld.block(0, BLOCK_Y, -1);
    this.aimAtBlock(block);
    final BlockBreakEvent event = this.blockBreak();

    this.command.onBlockBreak(event);

    verify(event, never()).setCancelled(anyBoolean());
    this.interactions.verifyNoInteractions();
  }

  @Test
  void shouldLetBlocksBreakWhenTheScreenIsIncomplete() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final Block block = this.fakeWorld.block(0, BLOCK_Y, -1);
    this.aimAtBlock(block);
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, BLOCK_CENTER_Z);
    this.interactions.when(() -> InteractUtils.findBoardCoordinates(this.player, frame)).thenReturn(Optional.empty());
    final BlockBreakEvent event = this.blockBreak();

    this.command.onBlockBreak(event);

    verify(event, never()).setCancelled(anyBoolean());

    final boolean nothingForwarded = this.command.forwarded.isEmpty();
    assertTrue(nothingForwarded);
  }

  @Test
  void shouldForwardPunchesOnTheScreenAsLeftClicks() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    this.interactions
      .when(() -> InteractUtils.findBoardCoordinates(this.player, frame))
      .thenReturn(Optional.of(new int[] { PUNCHED_BOARD_X, PUNCHED_BOARD_Y }));
    final EntityDamageByEntityEvent event = this.damage(frame, this.player);

    this.command.onScreenDamage(event);

    verify(event).setCancelled(true);
    this.assertForwarded("browser left 3,4");
  }

  @Test
  void shouldProtectTheScreenWithoutClickingWhenNoPlayerRuns() {
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    final EntityDamageByEntityEvent event = this.damage(frame, this.player);

    this.command.onScreenDamage(event);

    verify(event).setCancelled(true);
    this.interactions.verifyNoInteractions();
  }

  @Test
  void shouldProtectTheScreenFromArrowsWithoutClicking() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    final Arrow arrow = mock(Arrow.class);
    when(arrow.getShooter()).thenReturn(this.player);
    final EntityDamageByEntityEvent event = this.damage(frame, arrow);

    this.command.onScreenDamage(event);

    verify(event).setCancelled(true);
    this.interactions.verifyNoInteractions();
  }

  @Test
  void shouldIgnoreDamageThatNoPlayerCaused() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
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
  void shouldIgnoreDamageToOtherEntities() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final ItemFrame plainFrame = this.addPlainFrame(BLOCK_CENTER_X, 0.0);
    final Zombie zombie = mock(Zombie.class);
    final EntityDamageByEntityEvent frameEvent = this.damage(plainFrame, this.player);
    final EntityDamageByEntityEvent zombieEvent = this.damage(zombie, this.player);

    this.command.onScreenDamage(frameEvent);
    this.command.onScreenDamage(zombieEvent);

    verify(frameEvent, never()).setCancelled(anyBoolean());
    verify(zombieEvent, never()).setCancelled(anyBoolean());
  }

  @Test
  void shouldProtectTheScreenWhenThePunchMissesThePicture() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    this.interactions.when(() -> InteractUtils.findBoardCoordinates(this.player, frame)).thenReturn(Optional.empty());
    final EntityDamageByEntityEvent event = this.damage(frame, this.player);

    this.command.onScreenDamage(event);

    verify(event).setCancelled(true);

    final boolean nothingForwarded = this.command.forwarded.isEmpty();
    assertTrue(nothingForwarded);
  }

  @Test
  void shouldForwardRightClicksOnTheScreen() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    this.interactions.when(() -> InteractUtils.findBoardCoordinates(this.player, frame)).thenReturn(Optional.of(new int[] { 7, 8 }));
    final PlayerInteractEntityEvent event = this.rightClick(frame);

    this.command.onPlayerInteractEntity(event);

    verify(event).setCancelled(true);
    this.assertForwarded("browser right 7,8");
  }

  @Test
  void shouldIgnoreRightClicksThatCannotBeForwarded() {
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    final PlayerInteractEntityEvent withoutPlayer = this.rightClick(frame);
    this.command.onPlayerInteractEntity(withoutPlayer);

    this.createMockedScreen();
    this.command.player = BROWSER;
    final Zombie zombie = mock(Zombie.class);
    final PlayerInteractEntityEvent onZombie = this.rightClick(zombie);
    this.command.onPlayerInteractEntity(onZombie);
    this.interactions.when(() -> InteractUtils.findBoardCoordinates(this.player, frame)).thenReturn(Optional.empty());
    final PlayerInteractEntityEvent missed = this.rightClick(frame);
    this.command.onPlayerInteractEntity(missed);

    verify(withoutPlayer, never()).setCancelled(anyBoolean());
    verify(onZombie, never()).setCancelled(anyBoolean());
    verify(missed, never()).setCancelled(anyBoolean());

    final boolean nothingForwarded = this.command.forwarded.isEmpty();
    assertTrue(nothingForwarded);
  }

  @Test
  void shouldForwardTheChatOfPlayersWhoEnabledInteraction() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final Component enable = Component.text(ENABLED_TEXT);
    final Component disable = Component.text(DISABLED_TEXT);
    final Component message = Component.text(CHAT_TEXT);

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

  private AsyncChatEvent chat(final Component message) {
    final AsyncChatEvent event = mock(AsyncChatEvent.class);
    when(event.getPlayer()).thenReturn(this.player);
    when(event.message()).thenReturn(message);

    return event;
  }

  @Test
  void shouldKeepTheScreenSafeFromAPlayerWhoMayNotInteractWithoutForwardingTheClick() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    when(this.player.hasPermission(INTERACT_PERMISSION)).thenReturn(false);
    final Block block = this.fakeWorld.block(0, BLOCK_Y, -1);
    this.aimAtBlock(block);
    // the frame hangs on the block the player looks at, as in the test that forwards the click
    final ItemFrame frame = this.addScreenFrame(CORNER_FRAME_X, CORNER_FRAME_Y, CORNER_FRAME_Z);
    this.interactions
      .when(() -> InteractUtils.findBoardCoordinates(this.player, frame))
      .thenReturn(Optional.of(new int[] { AIMED_BOARD_X, AIMED_BOARD_Y }));
    final BlockBreakEvent broken = this.blockBreak();
    final PlayerInteractEntityEvent right = this.rightClick(frame);
    final EntityDamageByEntityEvent punch = this.damage(frame, this.player);

    this.command.onBlockBreak(broken);
    this.command.onPlayerInteractEntity(right);
    this.command.onScreenDamage(punch);

    this.assertForwarded();
    verify(broken).setCancelled(true);
    verify(right).setCancelled(true);
    verify(punch).setCancelled(true);
  }

  @Test
  void shouldForwardTheClicksOfAPlayerWhoMayInteract() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, FRONT_FRAME_Z);
    this.interactions.when(() -> InteractUtils.findBoardCoordinates(this.player, frame)).thenReturn(Optional.of(new int[] { 1, 2 }));
    final PlayerInteractEntityEvent right = this.rightClick(frame);
    final EntityDamageByEntityEvent punch = this.damage(frame, this.player);

    this.command.onPlayerInteractEntity(right);
    this.command.onScreenDamage(punch);

    this.assertForwarded("browser right 1,2", "browser left 1,2");
  }

  @Test
  void shouldStopForwardingTheChatOfAPlayerWhoLostThePermission() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final Component enable = Component.text(ENABLED_TEXT);
    final Component disable = Component.text(DISABLED_TEXT);
    final Component message = Component.text(CHAT_TEXT);
    this.command.toggleInteraction(this.player, enable, disable);
    when(this.player.hasPermission(INTERACT_PERMISSION)).thenReturn(false);
    final AsyncChatEvent event = this.chat(message);

    this.command.onChatMessage(event);

    verify(event, never()).setCancelled(anyBoolean());
    this.assertForwarded();
  }

  @Test
  void shouldKeepTheChatWhenNoPlayerRuns() {
    final Component enable = Component.text(ENABLED_TEXT);
    final Component disable = Component.text(DISABLED_TEXT);
    final Component message = Component.text(CHAT_TEXT);
    this.command.toggleInteraction(this.player, enable, disable);
    final AsyncChatEvent event = this.chat(message);

    this.command.onChatMessage(event);

    verify(event, never()).setCancelled(anyBoolean());

    final boolean nothingForwarded = this.command.forwarded.isEmpty();
    assertTrue(nothingForwarded);
  }

  @Test
  void shouldProtectScreenFramesFromBreaking() {
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    final ItemFrame plainFrame = this.addPlainFrame(1.5, 0.0);
    final HangingBreakEvent screenEvent = this.hangingBreak(frame);
    final HangingBreakEvent plainEvent = this.hangingBreak(plainFrame);

    this.command.onScreenBreak(screenEvent);
    this.command.onScreenBreak(plainEvent);

    verify(screenEvent).setCancelled(true);
    verify(plainEvent, never()).setCancelled(anyBoolean());
  }

  private HangingBreakEvent hangingBreak(final ItemFrame frame) {
    final HangingBreakEvent event = mock(HangingBreakEvent.class);
    when(event.getEntity()).thenReturn(frame);

    return event;
  }

  @Test
  void shouldReleaseThePlayerAndTellTheSender() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final CommandSender sender = mock(CommandSender.class);
    final Component message = Component.text("released");

    this.command.releaseResource(sender, message);

    verify(sender).sendMessage(message);
    this.assertReleased(BROWSER);
    assertNull(this.command.player);
  }

  @Test
  void shouldRejectScreenCreationAfterShutdownWithoutAcquiringMaps() {
    this.command.shutdown();
    final ScreenSettings settings = this.wallSettings(UUID.randomUUID(), DitheringArgument.NEAREST_COLOR);
    try (final MockedConstruction<CompressedMapResult> maps = Mockito.mockConstruction(CompressedMapResult.class)) {
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.command.createScreen(settings));

      final String message = thrown.getMessage();
      assertEquals("The interactive command is shut down!", message);

      final List<CompressedMapResult> constructed = maps.constructed();

      final boolean empty = constructed.isEmpty();
      assertTrue(empty);
    }
  }

  @Test
  void shouldCloseOwnedMapsWhenDitherCreationFails() {
    final ScreenSettings settings = this.wallSettings(UUID.randomUUID(), DitheringArgument.NEAREST_COLOR);
    final IllegalStateException failure = new IllegalStateException(DITHER_CREATION_FAILURE_MESSAGE);
    try (
      final MockedConstruction<CompressedMapResult> maps = Mockito.mockConstruction(CompressedMapResult.class);
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenThrow(failure);
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.command.createScreen(settings));
      assertSame(failure, thrown);

      final List<CompressedMapResult> constructed = maps.constructed();

      final int size = constructed.size();
      assertEquals(1, size);

      final CompressedMapResult acquired = constructed.getFirst();
      verify(acquired).release();
      assertNull(this.command.output);
    }
  }

  @Test
  void shouldKeepTheDitherFailureWhenTheMapsFailToReleaseToo() {
    final ScreenSettings settings = this.wallSettings(UUID.randomUUID(), DitheringArgument.NEAREST_COLOR);
    final IllegalStateException failure = new IllegalStateException(DITHER_CREATION_FAILURE_MESSAGE);
    final IllegalStateException cleanup = new IllegalStateException("maps stuck!");
    try (
      final MockedConstruction<CompressedMapResult> _ = Mockito.mockConstruction(CompressedMapResult.class, (maps, _) ->
        this.failMapsRelease(maps, cleanup)
      );
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenThrow(failure);
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.command.createScreen(settings));
      assertSame(failure, thrown);

      final List<Throwable> list = List.of(thrown.getSuppressed());
      final List<IllegalStateException> expectedFailures = List.of(cleanup);
      assertEquals(expectedFailures, list);
    }
  }

  private void failMapsRelease(final CompressedMapResult maps, final Throwable cleanup) {
    Mockito.doThrow(cleanup).when(maps).release();
  }

  @Test
  void shouldPropagateFatalDitherFailureWithoutReleasingTheMaps() {
    final ScreenSettings settings = this.wallSettings(UUID.randomUUID(), DitheringArgument.NEAREST_COLOR);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    try (
      final MockedConstruction<CompressedMapResult> maps = Mockito.mockConstruction(CompressedMapResult.class);
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenThrow(fatal);
      final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.command.createScreen(settings));
      assertSame(fatal, thrown);

      final List<CompressedMapResult> constructed = maps.constructed();

      final int size = constructed.size();
      assertEquals(1, size);

      final CompressedMapResult acquired = constructed.getFirst();
      verify(acquired, never()).release();
    }
  }

  @Test
  void shouldPropagateFatalMapsReleaseFailureAfterADitherFailure() {
    final ScreenSettings settings = this.wallSettings(UUID.randomUUID(), DitheringArgument.NEAREST_COLOR);
    final IllegalStateException failure = new IllegalStateException(DITHER_CREATION_FAILURE_MESSAGE);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    try (
      final MockedConstruction<CompressedMapResult> _ = Mockito.mockConstruction(CompressedMapResult.class, (maps, _) ->
        this.failMapsRelease(maps, fatal)
      );
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenThrow(failure);
      final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.command.createScreen(settings));
      assertSame(fatal, thrown);
    }
  }

  @Test
  void shouldCreateAnMcv2ScreenWhenTheSettingsAskForIt() {
    final CommandSender sender = mock(CommandSender.class);
    final UUID viewer = UUID.randomUUID();
    final Mcv2Support support = mock(Mcv2Support.class);
    when(this.plugin.getMcv2Support()).thenReturn(support);
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    when(
      support.configure(
        sender,
        Pair.pair(WALL_BLOCK_WIDTH, WALL_BLOCK_HEIGHT),
        Pair.pair(WALL_PIXEL_WIDTH, WALL_PIXEL_HEIGHT),
        FIRST_MAP_ID,
        Settings.DEFAULT,
        List.of(viewer)
      )
    ).thenReturn(Optional.of(configuration));
    final Mcv2Output output = mock(Mcv2Output.class);
    when(support.output(sender, configuration, DitheringArgument.NEAREST_COLOR)).thenReturn(Optional.of(output));
    try (final MockedConstruction<CompressedMapResult> maps = Mockito.mockConstruction(CompressedMapResult.class)) {
      final Screen screen = this.command.createScreen(this.mcv2Settings(sender, viewer));

      final FunctionalVideoFilter screenOutput = screen.getOutput();
      assertSame(output, screenOutput);
      assertSame(output, this.command.output);
      verify(output).start();

      final boolean empty = maps.constructed().isEmpty();
      assertTrue(empty, "the MCV2 output dithers for the viewers without the pack itself");
    }
  }

  private ScreenSettings mcv2Settings(final CommandSender sender, final UUID viewer) {
    final MultiplePlayerSelector viewers = this.mockViewers(viewer);
    return new ScreenSettings(
      sender,
      viewers,
      Pair.pair(WALL_BLOCK_WIDTH, WALL_BLOCK_HEIGHT),
      Pair.pair(WALL_PIXEL_WIDTH, WALL_PIXEL_HEIGHT),
      FIRST_MAP_ID,
      DitheringArgument.NEAREST_COLOR,
      MapCodec.MCV2
    );
  }

  @Test
  void shouldDitherTheScreenWhenMcv2CannotBeHad() {
    final CommandSender sender = mock(CommandSender.class);
    final Mcv2Support support = mock(Mcv2Support.class);
    when(this.plugin.getMcv2Support()).thenReturn(support);
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    // no frame holds the map the first time, and every slot of the pack plays the second time
    when(support.configure(any(), any(), any(), eq(FIRST_MAP_ID), any(), any()))
      .thenReturn(Optional.empty())
      .thenReturn(Optional.of(configuration));
    final FunctionalVideoFilter ditherFilter = mock(FunctionalVideoFilter.class);
    try (
      final MockedConstruction<CompressedMapResult> maps = Mockito.mockConstruction(CompressedMapResult.class);
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(ditherFilter);

      final FunctionalVideoFilter output = this.command.createScreen(this.mcv2Settings(sender, UUID.randomUUID())).getOutput();
      assertSame(ditherFilter, output);

      final FunctionalVideoFilter commandOutput = this.command.createScreen(this.mcv2Settings(sender, UUID.randomUUID())).getOutput();
      assertSame(ditherFilter, commandOutput);

      final int size = maps.constructed().size();
      assertEquals(2, size);
    }
    verify(support).output(sender, configuration, DitheringArgument.NEAREST_COLOR);
  }

  @Test
  void shouldLetTheFlagChooseTheCodecOverTheConfiguredDefault() {
    final MapCodec codec = this.command.chooseCodec(MapCodec.MCV2);
    assertSame(MapCodec.MCV2, codec);

    final MapCodec commandCodec = this.command.chooseCodec(null);
    assertSame(MapCodec.DITHER, commandCodec);
  }

  @Test
  void shouldPreserveSetupFailureAndSuppressADistinctCleanupFailure() {
    final IllegalStateException failure = new IllegalStateException("attach failed!");
    final IllegalStateException cleanup = new IllegalStateException("close failed!");
    this.assertSynchronousFailureCleanup(failure, cleanup);

    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
  }

  private void assertSynchronousFailureCleanup(final RuntimeException failure, final RuntimeException cleanup) {
    final Screen screen = this.createMockedScreen();
    final FunctionalVideoFilter maps = screen.getOutput();
    this.command.ownCreatedPlayer(BROWSER);
    this.command.releaseFailure = cleanup;
    final RuntimeException thrown = assertThrows(RuntimeException.class, () -> this.createFailingResource(failure));
    assertSame(failure, thrown);
    this.assertReleased(BROWSER);
    verify(maps).release();
    assertNull(this.command.player);
    assertNull(this.command.output);
  }

  private void createFailingResource(final RuntimeException failure) {
    this.command.createResource(() -> this.throwFailure(failure));
  }

  private void throwFailure(final RuntimeException failure) {
    throw failure;
  }

  @Test
  void shouldNotSelfSuppressARepeatedSetupAndCleanupFailure() {
    final IllegalStateException failure = new IllegalStateException("same backend failure!");
    this.assertSynchronousFailureCleanup(failure, failure);

    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(0, suppressed.length);
  }

  @Test
  void shouldRetainASuccessfullyStartedPlayerUntilExplicitRelease() throws InterruptedException {
    final List<Runnable> pending = this.useDeferredStartup();
    final Screen screen = this.createMockedScreen();
    final ExecutorService executor = this.command.startExecutor(BROWSER, screen);
    final CompletableFuture<Boolean> start = CompletableFuture.supplyAsync(() -> true, executor);
    final CommandSender sender = mock(CommandSender.class);
    this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER);
    final Runnable task = pending.getFirst();
    task.run();
    this.assertReceivedText(sender, STARTED_TEXT);
    this.assertReleased();
    assertEquals(BROWSER, this.command.player);

    final FunctionalVideoFilter maps = screen.getOutput();
    verify(maps, never()).release();

    this.command.releaseCurrent();
    this.assertReleased(BROWSER);
    verify(maps).release();
  }

  @Test
  void shouldCancelAStartFutureBoundAfterItsScreenWasReleased() {
    final Screen screen = this.createMockedScreen();
    this.command.ownCreatedPlayer(BROWSER);
    this.command.releaseCurrent();
    final CompletableFuture<Boolean> start = new CompletableFuture<>();
    final CommandSender sender = mock(CommandSender.class);
    this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER);
    final boolean cancelled = start.isCancelled();
    assertTrue(cancelled);
    assertNull(this.command.player);
    this.assertReleased(BROWSER);
    verify(sender, never()).sendMessage(any(Component.class));
  }

  @Test
  void shouldCancelAStartFutureBoundAfterShutdown() {
    final Screen screen = this.createMockedScreen();
    this.command.ownCreatedPlayer(BROWSER);
    this.command.shutdown();
    final CompletableFuture<Boolean> start = new CompletableFuture<>();
    final CommandSender sender = mock(CommandSender.class);
    this.command.reportStartWhenDone(sender, BROWSER, screen, start, BROWSER);
    final boolean cancelled = start.isCancelled();
    assertTrue(cancelled);
    assertNull(this.command.player);
    this.assertReleased(BROWSER);
    verify(sender, never()).sendMessage(any(Component.class));
  }

  @Test
  void shouldFinishStartupOwnershipWhenAnExecutorTaskThrows() throws InterruptedException {
    final RuntimeException failure = this.runFailedStartupTask(TaskCancellation.NONE, null);
    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(0, suppressed.length);
  }

  private RuntimeException runFailedStartupTask(final TaskCancellation cancellation, final @Nullable RuntimeException cleanup)
    throws InterruptedException {
    final List<Runnable> pending = this.useDeferredStartup();
    final Screen screen = this.createMockedScreen();
    this.command.ownCreatedPlayer(BROWSER);
    final ExecutorService executor = this.command.startExecutor(BROWSER, screen);
    final RuntimeException failure = new IllegalStateException(STARTUP_TASK_FAILURE_MESSAGE);
    if (cancellation == TaskCancellation.CANCEL_BEFORE_THROWING) {
      executor.execute(() -> this.releaseAndFailStartupTask(cleanup, failure));
    } else {
      executor.execute(() -> this.failStartupTask(cleanup, failure));
    }
    final Runnable task = pending.getFirst();
    final RuntimeException thrown = assertThrows(RuntimeException.class, task::run);
    assertSame(failure, thrown);

    if (cancellation == TaskCancellation.CANCEL_BEFORE_THROWING) {
      this.assertReleased(BROWSER);

      final FunctionalVideoFilter maps = screen.getOutput();
      verify(maps).release();
      assertNull(this.command.player);
    } else {
      this.assertReleased();

      this.command.releaseCurrent();
      this.assertReleased(BROWSER);
    }
    return failure;
  }

  private void releaseAndFailStartupTask(final @Nullable RuntimeException cleanup, final RuntimeException failure) {
    this.command.releaseCurrent();
    this.failStartupTask(cleanup, failure);
  }

  private void failStartupTask(final @Nullable RuntimeException cleanup, final RuntimeException failure) {
    this.command.releaseFailure = cleanup;
    throw failure;
  }

  @Test
  void shouldReleaseACancelledExecutorTaskEvenWhenItThrows() throws InterruptedException {
    final RuntimeException failure = this.runFailedStartupTask(TaskCancellation.CANCEL_BEFORE_THROWING, null);
    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(0, suppressed.length);
  }

  @Test
  void shouldPreserveExecutorTaskFailureWhenCancelledCleanupAlsoFails() throws InterruptedException {
    final RuntimeException cleanup = new IllegalStateException("backend close failed!");
    final RuntimeException failure = this.runFailedStartupTask(TaskCancellation.CANCEL_BEFORE_THROWING, cleanup);
    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
  }

  @Test
  void shouldAvoidSelfSuppressionWhenStartupAndCleanupThrowTheSameFailure() throws InterruptedException {
    final List<Runnable> pending = this.useDeferredStartup();
    final Screen screen = this.createMockedScreen();
    this.command.ownCreatedPlayer(BROWSER);
    final ExecutorService executor = this.command.startExecutor(BROWSER, screen);
    final RuntimeException failure = new IllegalStateException("backend failure!");
    executor.execute(() -> this.releaseAndFail(failure));
    final Runnable task = pending.getFirst();
    final RuntimeException thrown = assertThrows(RuntimeException.class, task::run);
    assertSame(failure, thrown);

    final Throwable[] suppressed = thrown.getSuppressed();
    assertEquals(0, suppressed.length);
    this.assertReleased(BROWSER);
  }

  private void releaseAndFail(final RuntimeException failure) {
    this.command.releaseCurrent();
    this.command.releaseFailure = failure;
    throw failure;
  }

  @Test
  void shouldIgnoreMarkedFramesWithMissingMapMetadata() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    final ItemStack item = mock(ItemStack.class);
    final ItemMeta metadata = mock(ItemMeta.class);
    when(item.getItemMeta()).thenReturn(metadata);
    when(frame.getItem()).thenReturn(item);
    final PlayerInteractEntityEvent wrongMetadata = this.rightClick(frame);
    this.command.onPlayerInteractEntity(wrongMetadata);
    final MapMeta mapMetadata = mock(MapMeta.class);
    when(item.getItemMeta()).thenReturn(mapMetadata);
    final PlayerInteractEntityEvent missingView = this.rightClick(frame);
    this.command.onPlayerInteractEntity(missingView);
    verify(wrongMetadata, never()).setCancelled(anyBoolean());
    verify(missingView, never()).setCancelled(anyBoolean());
    this.assertForwarded();
    this.interactions.verifyNoInteractions();
  }

  @Test
  void shouldIgnoreAPlayerWhoseScreenOwnershipWasAlreadyCleared() {
    // A startup failure clears screen ownership on the worker after the event captured its player.
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    final PlayerInteractEntityEvent event = this.rightClick(frame);
    this.command.onPlayerInteractEntity(event);
    verify(event, never()).setCancelled(anyBoolean());
    this.assertForwarded();
    this.interactions.verifyNoInteractions();
  }

  @Test
  void shouldIgnoreMapsImmediatelyBelowTheOwnedRange() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, 0.0);
    this.setMapId(frame, MAP_ID_BELOW_RANGE);
    final PlayerInteractEntityEvent event = this.rightClick(frame);
    this.command.onPlayerInteractEntity(event);
    verify(event, never()).setCancelled(anyBoolean());
    this.assertForwarded();
    this.interactions.verifyNoInteractions();
  }

  @Test
  void shouldLetBlocksBehindAnotherCommandsScreenBreak() {
    this.createMockedScreen();
    this.command.player = BROWSER;
    final Block block = this.fakeWorld.block(0, BLOCK_Y, -1);
    this.aimAtBlock(block);
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, BLOCK_CENTER_Z);
    this.setMapId(frame, MAP_ID_ABOVE_RANGE);
    final BlockBreakEvent event = this.blockBreak();
    this.command.onBlockBreak(event);
    verify(event, never()).setCancelled(anyBoolean());
    this.assertForwarded();
    this.interactions.verifyNoInteractions();
  }

  @Test
  void shouldPreserveANewerScreenWhenFailureRacesAfterTheCancellationCheck() throws Exception {
    TestServer.resetWithDeferredTasks();
    final Screen firstScreen = this.createMockedScreen();
    final FunctionalVideoFilter firstMaps = firstScreen.getOutput();
    final CompletableFuture<Boolean> firstStart = new CompletableFuture<>();
    final CommandSender sender = mock(CommandSender.class);
    this.command.reportStartWhenDone(sender, FIRST_BROWSER, firstScreen, firstStart, "first browser");
    final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
    final CountDownLatch loggedFailure = new CountDownLatch(1);
    final CountDownLatch resumeFailure = new CountDownLatch(1);
    final IllegalStateException failure = Mockito.spy(new IllegalStateException("startup failed!"));
    // Logging falls between the cancellation check and the ownership lock, so pausing it lets the main thread replace the screen.
    Mockito.doAnswer(invocation -> this.pauseFailureLogging(invocation, loggedFailure, resumeFailure, callbackFailure))
      .when(failure)
      .printStackTrace(any(PrintStream.class));
    try (final ExecutorService worker = Executors.newSingleThreadExecutor()) {
      final CompletableFuture<Boolean> completion = CompletableFuture.supplyAsync(() -> firstStart.completeExceptionally(failure), worker);
      try {
        final boolean reachedFailure = loggedFailure.await(10, TimeUnit.SECONDS);
        assertTrue(reachedFailure, "The failed start must reach its log before replacement");

        final Screen secondScreen = this.createMockedScreen();
        final FunctionalVideoFilter secondMaps = secondScreen.getOutput();
        final CompletableFuture<Boolean> secondStart = new CompletableFuture<>();
        this.command.reportStartWhenDone(sender, SECOND_BROWSER, secondScreen, secondStart, "second browser");
        resumeFailure.countDown();
        final boolean completed = completion.get(10, TimeUnit.SECONDS);
        assertTrue(completed);

        final Throwable callbackFailureCallbackFailure = callbackFailure.get();
        assertNull(callbackFailureCallbackFailure, "callback assertions reach the test thread");

        TestServer.runPendingTasks();
        assertEquals(SECOND_BROWSER, this.command.player);
        assertSame(secondMaps, this.command.output);
        this.assertReleased(FIRST_BROWSER);
        verify(firstMaps, times(1)).release();
        verify(secondMaps, never()).release();
        verify(sender, never()).sendMessage(any(Component.class));

        secondStart.complete(true);
        TestServer.runPendingTasks();
        this.assertReceivedText(sender, STARTED_TEXT);
      } finally {
        resumeFailure.countDown();
      }
    }
  }

  private @Nullable Object pauseFailureLogging(
    final InvocationOnMock invocation,
    final CountDownLatch loggedFailure,
    final CountDownLatch resumeFailure,
    final AtomicReference<Throwable> callbackFailure
  ) throws Throwable {
    loggedFailure.countDown();
    try {
      final boolean resumed = resumeFailure.await(10, TimeUnit.SECONDS);
      assertTrue(resumed, "The main thread must release the failure callback");

      return invocation.callRealMethod();
    } catch (final Throwable exception) {
      callbackFailure.set(exception);
      throw exception;
    }
  }

  @Test
  void shouldPropagateFatalScreenCreationFailureWithoutAttemptingCleanup() {
    final UUID viewer = UUID.randomUUID();
    final ScreenSettings settings = this.wallSettings(viewer, DitheringArgument.NEAREST_COLOR);
    final FunctionalVideoFilter ditherFilter = mock(FunctionalVideoFilter.class);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    try (
      final MockedConstruction<CompressedMapResult> results = Mockito.mockConstruction(CompressedMapResult.class);
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(ditherFilter);
      Mockito.doThrow(fatal).when(ditherFilter).start();
      final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.command.createScreen(settings));
      assertSame(fatal, thrown);

      final List<CompressedMapResult> constructed = results.constructed();

      final int size = constructed.size();
      assertEquals(1, size);

      final CompressedMapResult maps = constructed.getFirst();
      verify(maps, never()).release();
      verify(ditherFilter, never()).release();
      this.assertReleased();
    }
  }

  @Test
  void shouldPropagateFatalResourceCreationFailureWithoutAttemptingCleanup() {
    this.command.ownCreatedPlayer(BROWSER);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.createFatallyFailingResource(fatal));
    assertSame(fatal, thrown);
    this.assertReleased();
    assertEquals(BROWSER, this.command.player);
  }

  private void createFatallyFailingResource(final OutOfMemoryError fatal) {
    this.command.createResource(() -> this.throwFatal(fatal));
  }

  private void throwFatal(final OutOfMemoryError fatal) {
    throw fatal;
  }

  @Test
  void shouldPropagateFatalBackgroundReleaseFailure() {
    final ExecutorService direct = MoreExecutors.newDirectExecutorService();
    final RecordingCommand command = new RecordingCommand(this.plugin, direct);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.releaseFatallyInTheBackground(command, fatal));
    assertSame(fatal, thrown);
  }

  private void releaseFatallyInTheBackground(final RecordingCommand command, final OutOfMemoryError fatal) {
    command.releaseInTheBackground(() -> this.throwFatal(fatal));
  }

  @Test
  void shouldPropagateFatalCleanupFailureDuringFailureRelease() {
    this.command.ownCreatedPlayer(BROWSER);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    this.command.releaseFailure = fatal;
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, this::createBrokenResource);
    assertSame(fatal, thrown);
    this.assertReleased(BROWSER);
  }

  private void createBrokenResource() {
    this.command.createResource(this::throwResourceFailure);
  }

  private void throwResourceFailure() {
    final String message = "resource failure!";
    throw new IllegalStateException(message);
  }

  @Test
  void shouldPropagateFatalStartTaskFailureWithoutAttemptingFinishStartTask() throws InterruptedException {
    final List<Runnable> pending = this.useDeferredStartup();
    final Screen screen = this.createMockedScreen();
    this.command.ownCreatedPlayer(BROWSER);
    final ExecutorService executor = this.command.startExecutor(BROWSER, screen);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    executor.execute(() -> this.cancelAndFail(screen, fatal));
    final Runnable task = pending.getFirst();
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, task::run);
    assertSame(fatal, thrown);
    this.assertReleased();
  }

  private void cancelAndFail(final Screen screen, final OutOfMemoryError fatal) {
    screen.cancel();
    throw fatal;
  }

  @Test
  void shouldPropagateFatalCleanupFailureDuringStartTaskFailureHandling() throws InterruptedException {
    final List<Runnable> pending = this.useDeferredStartup();
    final Screen screen = this.createMockedScreen();
    this.command.ownCreatedPlayer(BROWSER);
    final ExecutorService executor = this.command.startExecutor(BROWSER, screen);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    executor.execute(() -> this.cancelAndFailStartupTask(screen, fatal));
    final Runnable task = pending.getFirst();
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, task::run);
    assertSame(fatal, thrown);
    this.assertReleased(BROWSER);

    this.command.releaseFailure = null;
  }

  private void cancelAndFailStartupTask(final Screen screen, final OutOfMemoryError fatal) {
    screen.cancel();
    this.command.releaseFailure = fatal;
    final String message = STARTUP_TASK_FAILURE_MESSAGE;
    throw new IllegalStateException(message);
  }

  @Test
  void shouldTellTheLogOnceThatTheMapsOfAScreenShowAPicture() {
    final List<String> messages = new ArrayList<>();
    final VideoFilter announcement = this.command.announceFirstPicture(4, 6, messages::add);
    final byte[] pixels = { 3, 5, 7, 11, 13, 17 };
    try (final ImageBuffer frame = ImageBuffer.bytes(pixels.clone(), 2, 1)) {
      final boolean filtered = announcement.applyFilter(frame, OriginalVideoMetadata.EMPTY);
      assertFalse(filtered, "the step leaves the frame as it is");

      final ByteBuffer expected = ByteBuffer.wrap(pixels);
      final ByteBuffer data = frame.getData();
      assertEquals(expected, data);
      assertEquals(List.of("Maps 4 to 9 show their first picture."), messages, "at the first picture");

      final boolean announcementFiltered = announcement.applyFilter(frame, OriginalVideoMetadata.EMPTY);
      assertFalse(announcementFiltered);

      final ByteBuffer byteBufferExpected = ByteBuffer.wrap(pixels);
      final ByteBuffer frameData = frame.getData();
      assertEquals(byteBufferExpected, frameData);

      final boolean secondFiltered = announcement.applyFilter(frame, OriginalVideoMetadata.EMPTY);
      assertFalse(secondFiltered);

      final ByteBuffer secondExpected = ByteBuffer.wrap(pixels);
      final ByteBuffer secondData = frame.getData();
      assertEquals(secondExpected, secondData);
      assertEquals(List.of("Maps 4 to 9 show their first picture."), messages, "and never again");
    }
  }

  @Test
  void shouldForwardTheClickedPixelOfTheSourceCenteredOnTheWall() {
    // a 384x256 source on a wall of 4x3 maps, 512x384 pixels: the dithered maps leave a 64-pixel border around it
    final MultiplePlayerSelector viewers = this.mockViewers(UUID.randomUUID());
    final Pair<Integer, Integer> blocks = Pair.pair(WALL_BLOCK_WIDTH, WALL_BLOCK_HEIGHT);
    final Pair<Integer, Integer> resolution = Pair.pair(SOURCE_PIXEL_WIDTH, SOURCE_PIXEL_HEIGHT);
    this.createMockedScreen(
      new ScreenSettings(
        mock(CommandSender.class),
        viewers,
        blocks,
        resolution,
        FIRST_MAP_ID,
        DitheringArgument.NEAREST_COLOR,
        MapCodec.DITHER
      )
    );
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, FRONT_FRAME_Z);
    this.interactions
      .when(() -> InteractUtils.findBoardCoordinates(this.player, frame))
      .thenReturn(Optional.of(new int[] { SOURCE_BOARD_X, SOURCE_BOARD_Y }));

    this.command.onPlayerInteractEntity(this.rightClick(frame));
    this.command.onScreenDamage(this.damage(frame, this.player));

    this.assertForwarded("browser right 136,36", "browser left 136,36");
  }

  @Test
  void shouldForwardNoClickOnTheBorderAroundASmallerSource() {
    final MultiplePlayerSelector viewers = this.mockViewers(UUID.randomUUID());
    final Pair<Integer, Integer> blocks = Pair.pair(WALL_BLOCK_WIDTH, WALL_BLOCK_HEIGHT);
    final Pair<Integer, Integer> resolution = Pair.pair(SOURCE_PIXEL_WIDTH, SOURCE_PIXEL_HEIGHT);
    this.createMockedScreen(
      new ScreenSettings(
        mock(CommandSender.class),
        viewers,
        blocks,
        resolution,
        FIRST_MAP_ID,
        DitheringArgument.NEAREST_COLOR,
        MapCodec.DITHER
      )
    );
    this.command.player = BROWSER;
    final Block block = this.fakeWorld.block(0, BLOCK_Y, -1);
    this.aimAtBlock(block);
    final ItemFrame frame = this.addScreenFrame(CORNER_FRAME_X, CORNER_FRAME_Y, CORNER_FRAME_Z);
    this.interactions
      .when(() -> InteractUtils.findBoardCoordinates(this.player, frame))
      .thenReturn(Optional.of(new int[] { AIMED_BOARD_X, AIMED_BOARD_Y }));
    final PlayerInteractEntityEvent right = this.rightClick(frame);
    final EntityDamageByEntityEvent punch = this.damage(frame, this.player);
    final BlockBreakEvent broken = this.blockBreak();

    this.command.onPlayerInteractEntity(right);
    this.command.onScreenDamage(punch);
    this.command.onBlockBreak(broken);

    // the frame is still the screen's: its map does not turn and its wall does not break, but no pixel was clicked
    verify(right).setCancelled(true);
    verify(punch).setCancelled(true);
    verify(broken).setCancelled(true);
    this.assertForwarded();
  }

  @Test
  void shouldForwardTheClickedPixelOfTheSourceStretchedOverAnMcv2Wall() {
    final CommandSender sender = mock(CommandSender.class);
    final UUID viewer = UUID.randomUUID();
    final Mcv2Support support = mock(Mcv2Support.class);
    when(this.plugin.getMcv2Support()).thenReturn(support);
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    when(
      support.configure(
        sender,
        Pair.pair(WALL_BLOCK_WIDTH, WALL_BLOCK_HEIGHT),
        Pair.pair(1024, 768),
        FIRST_MAP_ID,
        Settings.DEFAULT,
        List.of(viewer)
      )
    ).thenReturn(Optional.of(configuration));
    when(support.output(sender, configuration, DitheringArgument.NEAREST_COLOR)).thenReturn(Optional.of(mock(Mcv2Output.class)));
    final MultiplePlayerSelector viewers = this.mockViewers(viewer);
    this.command.createScreen(
      new ScreenSettings(
        sender,
        viewers,
        Pair.pair(WALL_BLOCK_WIDTH, WALL_BLOCK_HEIGHT),
        Pair.pair(1024, 768),
        FIRST_MAP_ID,
        DitheringArgument.NEAREST_COLOR,
        MapCodec.MCV2
      )
    );
    this.command.player = BROWSER;
    final ItemFrame frame = this.addScreenFrame(BLOCK_CENTER_X, BLOCK_CENTER_Y, FRONT_FRAME_Z);
    this.interactions
      .when(() -> InteractUtils.findBoardCoordinates(this.player, frame))
      .thenReturn(Optional.of(new int[] { SOURCE_BOARD_X, SOURCE_BOARD_Y }));

    this.command.onPlayerInteractEntity(this.rightClick(frame));

    // the pack stretches the 1024x768 picture over the 512x384 pixels of the wall
    this.assertForwarded("browser right 400,200");
  }

  private static final class RecordingCommand extends AbstractInteractiveCommand<String> {

    private final List<String> forwarded;

    private final List<String> released;
    private @Nullable Throwable releaseFailure;

    RecordingCommand(final MCAVSandbox plugin) {
      super(plugin);
      this.forwarded = new ArrayList<>();
      this.released = new ArrayList<>();
    }

    RecordingCommand(final MCAVSandbox plugin, final ExecutorService executor) {
      super(plugin, executor);
      this.forwarded = new ArrayList<>();
      this.released = new ArrayList<>();
    }

    @Override
    protected void forwardLeftClick(final String current, final int screenX, final int screenY) {
      this.forwarded.add("%s left %s%s%s".formatted(current, screenX, COORDINATE_SEPARATOR, screenY));
    }

    @Override
    protected void forwardRightClick(final String current, final int screenX, final int screenY) {
      this.forwarded.add("%s right %s%s%s".formatted(current, screenX, COORDINATE_SEPARATOR, screenY));
    }

    @Override
    protected void forwardTextInput(final String current, final String text) {
      this.forwarded.add("%s text %s".formatted(current, text));
    }

    @Override
    protected Component createStartMessage(final StartOutcome outcome, final @Nullable Throwable failure) {
      if (outcome == StartOutcome.STARTED) {
        return Component.text(STARTED_TEXT);
      }

      final String reason = failure == null ? "refused" : failure.getMessage();
      return Component.text("failed: %s".formatted(reason));
    }

    @Override
    protected void releasePlayer(final String current) {
      this.released.add(current);
      final Throwable failure = this.releaseFailure;
      if (failure instanceof final RuntimeException runtimeException) {
        throw runtimeException;
      }

      if (failure instanceof final Error error) {
        throw error;
      }
    }

    @Override
    protected String getInteractionPermission() {
      return INTERACT_PERMISSION;
    }
  }

  private enum TaskCancellation {
    CANCEL_BEFORE_THROWING,
    NONE,
  }
}
