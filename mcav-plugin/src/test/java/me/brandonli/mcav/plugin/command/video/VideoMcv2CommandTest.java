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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class VideoMcv2CommandTest {

  private static final int MAP_ID = 20;

  private static final String BLOCK_DIMENSIONS = "5x3";
  private static final int BLOCK_WIDTH = 5;
  private static final int BLOCK_HEIGHT = 3;

  private static final String RESOLUTION = "640x384";
  private static final int RESOLUTION_WIDTH = 640;
  private static final int RESOLUTION_HEIGHT = 384;

  private static final String VIDEO_FILE_NAME = "clip.mp4";

  private VideoPlayerManager manager;

  private Mcv2Support support;

  private VideoMcv2Command command;

  private MultiplePlayerSelector selector;

  private CommandSender sender;

  private final UUID viewer = UUID.randomUUID();

  @BeforeEach
  void createCommand() {
    TestServer.resetWithDeferredTasks();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = mock(VideoPlayerManager.class);
    this.support = mock(Mcv2Support.class);
    Mockito.doCallRealMethod().when(this.support).createMcv2Filter(any());
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(plugin.getAudioProvider()).thenReturn(mock(AudioProvider.class));
    when(plugin.getMcv2Support()).thenReturn(this.support);
    when(plugin.getOnlinePlayers()).thenReturn(new OnlinePlayers());
    this.command = spy(new VideoMcv2Command(plugin));
    doNothing().when(this.command).playVideo(any(), any(), any(), any(), any(), anyString(), anyString(), anyString(), isNull());
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(this.viewer);
    this.selector = mock(MultiplePlayerSelector.class);
    when(this.selector.values()).thenReturn(List.of(player));
    this.sender = mock(CommandSender.class);
  }

  @Test
  void shouldConfigureTheWallAndPlayOnIt() {
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    when(
      this.support.configure(
        eq(this.sender),
        eq(Pair.pair(BLOCK_WIDTH, BLOCK_HEIGHT)),
        eq(Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT)),
        eq(MAP_ID),
        eq(Settings.DEFAULT),
        eq(List.of(this.viewer))
      )
    ).thenReturn(Optional.of(configuration));

    this.play(RESOLUTION, BLOCK_DIMENSIONS, MAP_ID);

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

    final Object built = providers.getValue().buildConfiguration(Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT));
    final VideoMcv2Command.Mcv2Settings settings = assertInstanceOf(VideoMcv2Command.Mcv2Settings.class, built);

    final Mcv2Configuration settingsConfiguration = settings.configuration();
    assertSame(configuration, settingsConfiguration);

    final DitheringArgument dithering = settings.dithering();
    assertEquals(DitheringArgument.FILTER_LITE, dithering);

    final CommandSender sender = settings.sender();
    assertSame(this.sender, sender);
  }

  private void play(final String resolution, final String wall, final int map) {
    this.command.playMcv2Video(
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      resolution,
      wall,
      map,
      Mcv2Profile.DEFAULT,
      DitheringArgument.FILTER_LITE,
      "",
      VIDEO_FILE_NAME,
      null
    );
  }

  @Test
  void shouldStartTheOutputInASlotOfThePackOnThePlayerManager() {
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    final Mcv2Output output = mock(Mcv2Output.class);
    when(this.support.output(this.sender, configuration, DitheringArgument.FILTER_LITE)).thenReturn(Optional.of(output));
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ ->
      new VideoMcv2Command.Mcv2Settings(configuration, DitheringArgument.FILTER_LITE, this.sender);

    final VideoPipelineStep step = this.command.createVideoFilter(Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT), provider);

    verify(this.manager).startFilter(output);

    final VideoFilter filter = step.getFilter();
    assertSame(output, filter);
  }

  @Test
  void shouldDitherTheWallWhenEverySlotOfThePackPlays() {
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    when(configuration.getColumns()).thenReturn(BLOCK_WIDTH);
    when(configuration.getRows()).thenReturn(BLOCK_HEIGHT);
    when(configuration.getVideoWidth()).thenReturn(RESOLUTION_WIDTH);
    when(configuration.getVideoHeight()).thenReturn(RESOLUTION_HEIGHT);
    when(configuration.getMap()).thenReturn(MAP_ID);
    when(configuration.getViewers()).thenReturn(List.of(this.viewer));
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ ->
      new VideoMcv2Command.Mcv2Settings(configuration, DitheringArgument.FILTER_LITE, this.sender);
    final FunctionalVideoFilter dithered = mock(FunctionalVideoFilter.class);
    try (
      final MockedConstruction<CompressedMapResult> maps = Mockito.mockConstruction(CompressedMapResult.class, (_, context) ->
        this.assertWallConfiguration(context)
      );
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(dithered);

      final VideoPipelineStep step = this.command.createVideoFilter(Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT), provider);

      dithers.verify(() -> DitherFilter.dither(DitheringArgument.FILTER_LITE.createAlgorithm(), maps.constructed().getFirst()));
      verify(this.manager).startFilter(dithered);

      final VideoFilter filter = step.getFilter();
      assertSame(dithered, filter);
    }
  }

  private void assertWallConfiguration(final MockedConstruction.Context context) {
    final MapConfiguration wall = (MapConfiguration) context.arguments().getFirst();
    final int mapId = wall.getMap();
    assertEquals(MAP_ID, mapId);
    final int mapBlockWidth = wall.getMapBlockWidth();
    assertEquals(BLOCK_WIDTH, mapBlockWidth);
    final int mapBlockHeight = wall.getMapBlockHeight();
    assertEquals(BLOCK_HEIGHT, mapBlockHeight);
    final int mapWidthResolution = wall.getMapWidthResolution();
    assertEquals(RESOLUTION_WIDTH, mapWidthResolution);
    final int mapHeightResolution = wall.getMapHeightResolution();
    assertEquals(RESOLUTION_HEIGHT, mapHeightResolution);
    final List<UUID> expectedViewers = List.of(this.viewer);
    final List<UUID> viewers = List.copyOf(wall.getViewers());
    assertEquals(expectedViewers, viewers);
  }

  @Test
  void shouldRefuseInvalidSizesAndMissingWalls() {
    this.play(RESOLUTION, "65x1", MAP_ID);
    this.play("nope", BLOCK_DIMENSIONS, MAP_ID);
    verify(this.support, never()).configure(any(), any(), any(), eq(MAP_ID), any(), any());

    this.play(RESOLUTION, BLOCK_DIMENSIONS, 21);
    verify(this.support).configure(any(), any(), any(), eq(21), any(), any());
    verify(this.command, never()).playVideo(any(), any(), any(), any(), any(), anyString(), anyString(), anyString(), isNull());
  }

  @Test
  void shouldRefuseNullArguments() {
    final Mcv2Profile preset = Mcv2Profile.DEFAULT;
    final DitheringArgument dithering = DitheringArgument.FILTER_LITE;
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ -> new Object();

    assertThrows(NullPointerException.class, () -> this.playWith(null, preset, dithering));
    assertThrows(NullPointerException.class, () -> this.playWith(this.selector, null, dithering));
    assertThrows(NullPointerException.class, () -> this.playWith(this.selector, preset, null));
    assertThrows(NullPointerException.class, () -> this.command.createVideoFilter(null, provider));
    assertThrows(NullPointerException.class, () -> this.command.createVideoFilter(resolution, null));
  }

  private void playWith(
    final @Nullable MultiplePlayerSelector selector,
    final @Nullable Mcv2Profile preset,
    final @Nullable DitheringArgument dithering
  ) {
    this.command.playMcv2Video(
      this.sender,
      selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      RESOLUTION,
      BLOCK_DIMENSIONS,
      MAP_ID,
      preset,
      dithering,
      "",
      VIDEO_FILE_NAME,
      null
    );
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
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    when(this.support.configure(any(), any(), any(), eq(MAP_ID), any(), any())).thenReturn(Optional.of(configuration));
    this.command.playMcv2Video(
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      RESOLUTION,
      BLOCK_DIMENSIONS,
      MAP_ID,
      Mcv2Profile.DEFAULT,
      DitheringArgument.FILTER_LITE,
      "",
      VIDEO_FILE_NAME,
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
}
