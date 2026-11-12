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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.command.MapDisplaySettings;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.plugin.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

final class VideoMapCommandTest {

  private static final String BLOCK_DIMENSIONS = "5x3";
  private static final int BLOCK_WIDTH = 5;
  private static final int BLOCK_HEIGHT = 3;

  private static final String RESOLUTION = "640x384";
  private static final int RESOLUTION_WIDTH = 640;
  private static final int RESOLUTION_HEIGHT = 384;

  private static final int MAP_ID = 20;

  private static final String VIDEO_FILE_NAME = "clip.mp4";

  private VideoPlayerManager manager;
  private VideoMapCommand command;
  private MultiplePlayerSelector selector;
  private CommandSender sender;
  private UUID viewer;
  private PluginDataConfigurationMapper configuration;
  private Mcv2Support support;

  @BeforeEach
  void createCommand() {
    TestServer.resetWithDeferredTasks();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = mock(VideoPlayerManager.class);
    Mockito.doAnswer(invocation -> this.runFilterOnMainThread(invocation, plugin))
      .when(this.manager)
      .startFilter(any(FunctionalVideoFilter.class));
    final AudioProvider provider = mock(AudioProvider.class);
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(plugin.getAudioProvider()).thenReturn(provider);
    when(plugin.getOnlinePlayers()).thenReturn(new OnlinePlayers());
    this.configuration = mock(PluginDataConfigurationMapper.class);
    when(this.configuration.getMcv2DefaultCodec()).thenReturn(MapCodec.DITHER);
    when(plugin.getConfiguration()).thenReturn(this.configuration);
    this.support = mock(Mcv2Support.class);
    Mockito.doCallRealMethod().when(this.support).createMcv2Filter(any());
    when(plugin.getMcv2Support()).thenReturn(this.support);
    final VideoMapCommand realCommand = new VideoMapCommand(plugin);
    this.command = spy(realCommand);
    this.skipPlayback(this.command);

    this.viewer = UUID.randomUUID();
    this.selector = this.mockSelector(this.viewer);
    this.sender = mock(CommandSender.class);
  }

  private @Nullable Object runFilterOnMainThread(final InvocationOnMock invocation, final MCAVSandbox plugin) {
    final FunctionalVideoFilter filter = invocation.getArgument(0);
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTask(plugin, filter::start);

    return null;
  }

  private void skipPlayback(final AbstractVideoCommand command) {
    doNothing()
      .when(command)
      .playVideo(
        any(AbstractVideoCommand.VideoConfigurationProvider.class),
        any(CommandSender.class),
        any(MultiplePlayerSelector.class),
        any(PlayerArgument.class),
        any(AudioArgument.class),
        anyString(),
        anyString(),
        anyString(),
        isNull()
      );
  }

  private MultiplePlayerSelector mockSelector(final UUID viewer) {
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(viewer);
    final MultiplePlayerSelector selector = mock(MultiplePlayerSelector.class);
    final List<Player> players = List.of(player);
    when(selector.values()).thenReturn(players);

    return selector;
  }

  @Test
  void shouldPassTheArgumentsOnAndConfigureTheWallOfMaps() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.playAndCaptureProvider(
      DitheringArgument.FILTER_LITE
    );
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);

    final Object built = configurationProvider.buildConfiguration(resolution);

    final MapDisplaySettings settings = assertInstanceOf(MapDisplaySettings.class, built);

    final MapConfiguration configuration = settings.getConfiguration();
    this.assertWall(configuration);
  }

  private AbstractVideoCommand.VideoConfigurationProvider playAndCaptureProvider(final DitheringArgument dithering) {
    this.playOnWall(BLOCK_DIMENSIONS, dithering);
    final ArgumentCaptor<AbstractVideoCommand.VideoConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractVideoCommand.VideoConfigurationProvider.class
    );
    verify(this.command).playVideo(
      providers.capture(),
      eq(this.sender),
      eq(this.selector),
      eq(PlayerArgument.FFMPEG),
      eq(AudioArgument.NONE),
      eq(RESOLUTION),
      eq(VIDEO_FILE_NAME),
      eq(""),
      isNull()
    );

    return providers.getValue();
  }

  private void playOnWall(final String blockDimensions, final DitheringArgument dithering) {
    this.command.playMapVideo(
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      RESOLUTION,
      blockDimensions,
      MAP_ID,
      dithering,
      "",
      VIDEO_FILE_NAME,
      null,
      null
    );
  }

  private void assertWall(final MapConfiguration configuration) {
    final Collection<UUID> viewers = configuration.getViewers();
    final List<UUID> viewerList = new ArrayList<>(viewers);
    final List<UUID> expectedViewers = List.of(this.viewer);
    final int map = configuration.getMap();
    final int blockWidth = configuration.getMapBlockWidth();
    final int blockHeight = configuration.getMapBlockHeight();
    final int width = configuration.getMapWidthResolution();
    final int height = configuration.getMapHeightResolution();
    assertEquals(expectedViewers, viewerList);
    assertEquals(MAP_ID, map);
    assertEquals(BLOCK_WIDTH, blockWidth);
    assertEquals(BLOCK_HEIGHT, blockHeight);
    assertEquals(RESOLUTION_WIDTH, width);
    assertEquals(RESOLUTION_HEIGHT, height);
  }

  @Test
  void shouldDitherTheVideoOntoTheWallOfMaps() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.playAndCaptureProvider(
      DitheringArgument.FILTER_LITE
    );
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final DitherAlgorithm algorithm = DitheringArgument.FILTER_LITE.createAlgorithm();
    final FunctionalVideoFilter ditherFilter = mock(FunctionalVideoFilter.class);
    final List<List<?>> arguments = new ArrayList<>();
    try (
      final MockedConstruction<CompressedMapResult> results = Mockito.mockConstruction(CompressedMapResult.class, (_, context) ->
        this.recordArguments(context, arguments)
      );
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(ditherFilter);
      final VideoPipelineStep pipeline = this.command.createVideoFilter(resolution, configurationProvider);
      final List<CompressedMapResult> constructed = results.constructed();
      final CompressedMapResult result = constructed.getFirst();
      dithers.verify(() -> DitherFilter.dither(algorithm, result));

      final VideoFilter filter = pipeline.getFilter();
      assertSame(ditherFilter, filter);
      this.assertStartedOnTheMainThread(ditherFilter);
    }

    final List<?> constructorArguments = arguments.getFirst();
    final MapConfiguration used = (MapConfiguration) constructorArguments.getFirst();
    this.assertWall(used);
  }

  private void recordArguments(final MockedConstruction.Context context, final List<List<?>> arguments) {
    final List<?> constructorArguments = context.arguments();
    arguments.add(constructorArguments);
  }

  private void assertStartedOnTheMainThread(final FunctionalVideoFilter ditherFilter) {
    verify(this.manager).startFilter(ditherFilter);
    verify(ditherFilter, never()).start();

    final int tasks = TestServer.runPendingTasks();
    assertEquals(1, tasks);
    verify(ditherFilter).start();
  }

  @Test
  void shouldGiveEveryVideoItsOwnTemporalDitheringAlgorithm() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.playAndCaptureProvider(
      DitheringArgument.FLOYD_STEINBERG_TEMPORAL
    );
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final FunctionalVideoFilter ditherFilter = mock(FunctionalVideoFilter.class);
    final ArgumentCaptor<DitherAlgorithm> algorithms = ArgumentCaptor.forClass(DitherAlgorithm.class);
    try (
      final MockedConstruction<CompressedMapResult> _ = Mockito.mockConstruction(CompressedMapResult.class);
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(ditherFilter);
      this.command.createVideoFilter(resolution, configurationProvider);
      this.command.createVideoFilter(resolution, configurationProvider);
      dithers.verify(() -> DitherFilter.dither(algorithms.capture(), any()), times(2));
    }

    final List<DitherAlgorithm> used = algorithms.getAllValues();
    final DitherAlgorithm first = used.getFirst();
    final DitherAlgorithm second = used.getLast();
    assertNotSame(first, second);
  }

  @ParameterizedTest
  @ValueSource(strings = { "5x", "65x1", "1x65" })
  void shouldRefuseInvalidWallSizes(final String wall) {
    this.playOnWall(wall, DitheringArgument.FILTER_LITE);

    verify(this.command, never()).playVideo(
      any(AbstractVideoCommand.VideoConfigurationProvider.class),
      any(CommandSender.class),
      any(MultiplePlayerSelector.class),
      any(PlayerArgument.class),
      any(AudioArgument.class),
      anyString(),
      anyString(),
      anyString(),
      isNull()
    );

    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expectedMessages = List.of(error);
    assertEquals(expectedMessages, messages);
  }

  @Test
  void shouldEncodeTheVideoWithMcv2WhenTheFlagSaysSo() {
    final Mcv2Configuration wall = mock(Mcv2Configuration.class);
    when(
      this.support.configure(
        eq(this.sender),
        eq(Pair.pair(BLOCK_WIDTH, BLOCK_HEIGHT)),
        eq(Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT)),
        eq(MAP_ID),
        eq(Settings.DEFAULT),
        any()
      )
    ).thenReturn(Optional.of(wall));
    this.playWithCodec(RESOLUTION, MapCodec.MCV2);
    final ArgumentCaptor<AbstractVideoCommand.VideoConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractVideoCommand.VideoConfigurationProvider.class
    );
    verify(this.command).playVideo(
      providers.capture(),
      eq(this.sender),
      eq(this.selector),
      eq(PlayerArgument.FFMPEG),
      eq(AudioArgument.NONE),
      eq(RESOLUTION),
      eq(VIDEO_FILE_NAME),
      eq(""),
      isNull()
    );

    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final Object builtConfiguration = providers.getValue().buildConfiguration(resolution);
    final VideoMcv2Command.Mcv2Settings settings = assertInstanceOf(VideoMcv2Command.Mcv2Settings.class, builtConfiguration);

    final Mcv2Configuration configuration = settings.configuration();
    assertSame(wall, configuration);

    final Mcv2Output output = mock(Mcv2Output.class);
    when(this.support.output(this.sender, wall, DitheringArgument.NEAREST_COLOR)).thenReturn(Optional.of(output));

    final VideoPipelineStep pipeline = this.command.createVideoFilter(resolution, providers.getValue());

    final VideoFilter filter = pipeline.getFilter();
    assertSame(output, filter);

    this.assertStartedOnTheMainThread(output);
  }

  private void playWithCodec(final String resolution, final @Nullable MapCodec codec) {
    this.command.playMapVideo(
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      resolution,
      BLOCK_DIMENSIONS,
      MAP_ID,
      DitheringArgument.NEAREST_COLOR,
      "",
      VIDEO_FILE_NAME,
      codec,
      null
    );
  }

  @Test
  void shouldApplyTheConfiguredCodecWithoutTheFlag() {
    when(this.configuration.getMcv2DefaultCodec()).thenReturn(MapCodec.MCV2);
    this.playWithCodec(RESOLUTION, null);
    verify(this.support).configure(
      eq(this.sender),
      eq(Pair.pair(BLOCK_WIDTH, BLOCK_HEIGHT)),
      eq(Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT)),
      eq(MAP_ID),
      eq(Settings.DEFAULT),
      any()
    );
  }

  @Test
  void shouldNotStartAnMcv2VideoWithAnInvalidResolution() {
    this.playWithCodec("wide", MapCodec.MCV2);
    verify(this.support, never()).configure(any(), any(), any(), eq(MAP_ID), any(), any());
    verify(this.command, never()).playVideo(any(), any(), any(), any(), any(), anyString(), anyString(), anyString(), isNull());
  }

  @Test
  void shouldDitherAnMcv2VideoWithoutAWall() {
    this.playWithCodec(RESOLUTION, MapCodec.MCV2);
    final ArgumentCaptor<AbstractVideoCommand.VideoConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractVideoCommand.VideoConfigurationProvider.class
    );
    verify(this.command).playVideo(
      providers.capture(),
      eq(this.sender),
      eq(this.selector),
      eq(PlayerArgument.FFMPEG),
      eq(AudioArgument.NONE),
      eq(RESOLUTION),
      eq(VIDEO_FILE_NAME),
      eq(""),
      isNull()
    );

    final Object configuration = providers.getValue().buildConfiguration(Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT));
    assertInstanceOf(MapDisplaySettings.class, configuration);
  }

  @Test
  void shouldRefuseNullArguments() {
    final PlayerArgument ffmpeg = PlayerArgument.FFMPEG;
    final AudioArgument none = AudioArgument.NONE;
    final DitheringArgument dithering = DitheringArgument.NEAREST_COLOR;
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ -> new Object();

    assertThrows(NullPointerException.class, () ->
      this.command.playMapVideo(
        this.sender,
        null,
        ffmpeg,
        none,
        RESOLUTION,
        BLOCK_DIMENSIONS,
        MAP_ID,
        dithering,
        "",
        VIDEO_FILE_NAME,
        null,
        null
      )
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playMapVideo(
        this.sender,
        this.selector,
        ffmpeg,
        none,
        RESOLUTION,
        BLOCK_DIMENSIONS,
        MAP_ID,
        null,
        "",
        VIDEO_FILE_NAME,
        null,
        null
      )
    );
    assertThrows(NullPointerException.class, () -> this.command.createVideoFilter(null, provider));
    assertThrows(NullPointerException.class, () -> this.command.createVideoFilter(resolution, null));
  }

  @Test
  void shouldPassTheFiltersOnToTheVideo() {
    final String filters = "invert";
    doNothing()
      .when(this.command)
      .playVideo(
        any(AbstractVideoCommand.VideoConfigurationProvider.class),
        any(CommandSender.class),
        any(MultiplePlayerSelector.class),
        any(PlayerArgument.class),
        any(AudioArgument.class),
        anyString(),
        anyString(),
        anyString(),
        eq(filters)
      );
    this.command.playMapVideo(
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      RESOLUTION,
      BLOCK_DIMENSIONS,
      MAP_ID,
      DitheringArgument.NEAREST_COLOR,
      "",
      VIDEO_FILE_NAME,
      null,
      filters
    );

    verify(this.command).playVideo(
      any(AbstractVideoCommand.VideoConfigurationProvider.class),
      eq(this.sender),
      eq(this.selector),
      eq(PlayerArgument.FFMPEG),
      eq(AudioArgument.NONE),
      eq(RESOLUTION),
      eq(VIDEO_FILE_NAME),
      eq(""),
      eq(filters)
    );
  }

  @Test
  void shouldRefuseAProviderThatBuildsAnotherConfiguration() {
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ -> new Object();
    assertThrows(ClassCastException.class, () -> this.command.createVideoFilter(resolution, provider));
  }
}
