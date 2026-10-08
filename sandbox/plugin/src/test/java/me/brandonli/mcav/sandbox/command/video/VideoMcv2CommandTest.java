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
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.listener.OnlinePlayers;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
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
 * Tests {@link VideoMcv2Command}: the wall is configured from the command on the main thread, and the video's output
 * takes a slot of the MCV2 pack when it starts, or dithers the wall when every slot plays.
 */
final class VideoMcv2CommandTest {

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

  private void play(final String resolution, final String wall, final int map) {
    this.command.playMcv2Video(
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      resolution,
      wall,
      map,
      Mcv2Profile.ADAPTIVE,
      DitheringArgument.FILTER_LITE,
      "",
      "clip.mp4",
      null
    );
  }

  @Test
  void configuresTheWallAndPlaysOnIt() {
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    when(
      this.support.configure(
        eq(this.sender),
        eq(Pair.pair(5, 3)),
        eq(Pair.pair(640, 384)),
        eq(20),
        eq(Settings.ADAPTIVE),
        eq(List.of(this.viewer))
      )
    ).thenReturn(configuration);

    this.play("640x384", "5x3", 20);

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
      eq(""),
      isNull()
    );
    final Object built = providers.getValue().buildConfiguration(Pair.pair(640, 384));
    final VideoMcv2Command.Mcv2Settings settings = assertInstanceOf(VideoMcv2Command.Mcv2Settings.class, built);
    assertSame(configuration, settings.configuration());
    assertEquals(DitheringArgument.FILTER_LITE, settings.dithering());
    assertSame(this.sender, settings.sender());
  }

  @Test
  void startsTheOutputInASlotOfThePackOnThePlayerManager() {
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    final Mcv2Output output = mock(Mcv2Output.class);
    when(this.support.output(this.sender, configuration, DitheringArgument.FILTER_LITE)).thenReturn(output);
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ ->
      new VideoMcv2Command.Mcv2Settings(configuration, DitheringArgument.FILTER_LITE, this.sender);

    final VideoPipelineStep step = this.command.createVideoFilter(Pair.pair(640, 384), provider);

    verify(this.manager).startFilter(output);
    assertSame(output, step.getFilter());
  }

  @Test
  void dithersTheWallWhenEverySlotOfThePackPlays() {
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    when(configuration.getColumns()).thenReturn(5);
    when(configuration.getRows()).thenReturn(3);
    when(configuration.getVideoWidth()).thenReturn(640);
    when(configuration.getVideoHeight()).thenReturn(384);
    when(configuration.getMap()).thenReturn(20);
    when(configuration.getViewers()).thenReturn(List.of(this.viewer));
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ ->
      new VideoMcv2Command.Mcv2Settings(configuration, DitheringArgument.FILTER_LITE, this.sender);
    final FunctionalVideoFilter dithered = mock(FunctionalVideoFilter.class);
    try (
      final MockedConstruction<CompressedMapResult> maps = Mockito.mockConstruction(CompressedMapResult.class, (_, context) -> {
        final MapConfiguration wall = (MapConfiguration) context.arguments().getFirst();
        assertEquals(20, wall.getMap());
        assertEquals(5, wall.getMapBlockWidth());
        assertEquals(3, wall.getMapBlockHeight());
        assertEquals(640, wall.getMapWidthResolution());
        assertEquals(384, wall.getMapHeightResolution());
        assertEquals(List.of(this.viewer), List.copyOf(wall.getViewers()));
      });
      final MockedStatic<DitherFilter> dithers = Mockito.mockStatic(DitherFilter.class)
    ) {
      dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(dithered);

      final VideoPipelineStep step = this.command.createVideoFilter(Pair.pair(640, 384), provider);

      dithers.verify(() -> DitherFilter.dither(DitheringArgument.FILTER_LITE.createAlgorithm(), maps.constructed().getFirst()));
      verify(this.manager).startFilter(dithered);
      assertSame(dithered, step.getFilter());
    }
  }

  @Test
  void takesTheBacklogLimitOfAMeasurement() {
    System.setProperty(VideoMcv2Command.BACKLOG_PROPERTY, "65536");
    try {
      assertEquals(65536, VideoMcv2Command.backlogLimit());
      assertEquals(Mcv2Configuration.DEFAULT_UNSENT_LIMIT, VideoMcv2Command.unsentLimit());
      System.setProperty(VideoMcv2Command.BACKLOG_PROPERTY, "none");
      assertEquals(Long.MAX_VALUE, VideoMcv2Command.backlogLimit());
      // no backpressure at all: no cap on a viewer's unsent bytes either
      assertEquals(0, VideoMcv2Command.unsentLimit());
    } finally {
      System.clearProperty(VideoMcv2Command.BACKLOG_PROPERTY);
    }
    assertEquals(Mcv2Configuration.DEFAULT_BACKLOG_LIMIT, VideoMcv2Command.backlogLimit());
  }

  @Test
  void refusesInvalidSizesAndMissingWalls() {
    this.play("640x384", "65x1", 20);
    this.play("nope", "5x3", 20);
    verify(this.support, never()).configure(any(), any(), any(), eq(20), any(), any());
    this.play("640x384", "5x3", 21);
    verify(this.support).configure(any(), any(), any(), eq(21), any(), any());
    verify(this.command, never()).playVideo(any(), any(), any(), any(), any(), anyString(), anyString(), anyString(), isNull());
  }
}
