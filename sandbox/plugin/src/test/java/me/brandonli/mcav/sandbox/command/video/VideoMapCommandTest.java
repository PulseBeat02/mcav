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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.command.MapDisplaySettings;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link VideoMapCommand}. The common playback flow is skipped, so only the arguments passed to it and the
 * maps and dithering it creates are checked.
 */
final class VideoMapCommandTest {

  private VideoPlayerManager manager;
  private VideoMapCommand command;
  private MultiplePlayerSelector selector;
  private CommandSender sender;
  private UUID viewer;

  @BeforeEach
  void createCommand() {
    TestServer.resetWithDeferredTasks();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = mock(VideoPlayerManager.class);
    final AudioProvider provider = mock(AudioProvider.class);
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(plugin.getAudioProvider()).thenReturn(provider);
    final VideoMapCommand realCommand = new VideoMapCommand(plugin);
    this.command = spy(realCommand);
    skipPlayback(this.command);

    this.viewer = UUID.randomUUID();
    this.selector = mockSelector(this.viewer);
    this.sender = mock(CommandSender.class);
  }

  private static void skipPlayback(final AbstractVideoCommand command) {
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
        anyString()
      );
  }

  private static MultiplePlayerSelector mockSelector(final UUID viewer) {
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(viewer);
    final MultiplePlayerSelector selector = mock(MultiplePlayerSelector.class);
    final List<Player> players = List.of(player);
    when(selector.values()).thenReturn(players);
    return selector;
  }

  private void playOnWall(final String blockDimensions, final DitheringArgument dithering) {
    this.command.playMapVideo(
        this.sender,
        this.selector,
        PlayerArgument.FFMPEG,
        AudioArgument.NONE,
        "640x384",
        blockDimensions,
        20,
        dithering,
        "",
        "clip.mp4"
      );
  }

  private AbstractVideoCommand.VideoConfigurationProvider playAndCaptureProvider(final DitheringArgument dithering) {
    this.playOnWall("5x3", dithering);
    final ArgumentCaptor<AbstractVideoCommand.VideoConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractVideoCommand.VideoConfigurationProvider.class
    );
    verify(this.command).playVideo(
      providers.capture(),
      eq(this.sender),
      eq(this.selector),
      eq(PlayerArgument.FFMPEG),
      eq(AudioArgument.NONE),
      eq("640x384"),
      eq("clip.mp4"),
      eq("")
    );
    return providers.getValue();
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
    assertEquals(20, map);
    assertEquals(5, blockWidth);
    assertEquals(3, blockHeight);
    assertEquals(640, width);
    assertEquals(384, height);
  }

  private void assertStartedOnTheMainThread(final FunctionalVideoFilter ditherFilter) {
    verify(this.manager).setFilter(ditherFilter);
    verify(ditherFilter, never()).start();
    final int tasks = TestServer.runPendingTasks();
    assertEquals(1, tasks);
    verify(ditherFilter).start();
  }

  @Test
  void passesTheArgumentsOnAndConfiguresTheWallOfMaps() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider =
      this.playAndCaptureProvider(DitheringArgument.FILTER_LITE);
    final Pair<Integer, Integer> resolution = Pair.pair(640, 384);

    final Object built = configurationProvider.buildConfiguration(resolution);

    final MapDisplaySettings settings = assertInstanceOf(MapDisplaySettings.class, built);
    final MapConfiguration configuration = settings.getConfiguration();
    this.assertWall(configuration);
  }

  @Test
  void dithersTheVideoOntoTheWallOfMaps() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider =
      this.playAndCaptureProvider(DitheringArgument.FILTER_LITE);
    final Pair<Integer, Integer> resolution = Pair.pair(640, 384);
    final DitherAlgorithm algorithm = DitheringArgument.FILTER_LITE.createAlgorithm();
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

  @Test
  void givesEveryVideoItsOwnTemporalDitheringAlgorithm() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider =
      this.playAndCaptureProvider(DitheringArgument.FLOYD_STEINBERG_TEMPORAL);
    final Pair<Integer, Integer> resolution = Pair.pair(640, 384);
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

  @Test
  void refusesInvalidWallSizes() {
    this.playOnWall("5x", DitheringArgument.FILTER_LITE);

    verify(this.command, never()).playVideo(
      any(AbstractVideoCommand.VideoConfigurationProvider.class),
      any(CommandSender.class),
      any(MultiplePlayerSelector.class),
      any(PlayerArgument.class),
      any(AudioArgument.class),
      anyString(),
      anyString(),
      anyString()
    );
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expectedMessages = List.of(error);
    assertEquals(expectedMessages, messages);
  }
}
