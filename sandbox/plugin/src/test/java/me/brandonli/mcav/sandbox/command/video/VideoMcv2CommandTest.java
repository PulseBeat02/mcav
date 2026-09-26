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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.media.mcv2.encode.EncoderSettings;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

final class VideoMcv2CommandTest {

  private VideoPlayerManager manager;
  private Mcv2Support support;
  private Mcv2Viewers viewers;
  private VideoMcv2Command command;
  private MultiplePlayerSelector selector;
  private CommandSender sender;
  private Player player;
  private final UUID viewer = UUID.randomUUID();
  private World world;

  @BeforeEach
  void createCommand() {
    TestServer.resetWithDeferredTasks();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = mock(VideoPlayerManager.class);
    this.support = mock(Mcv2Support.class);
    this.viewers = mock(Mcv2Viewers.class);
    when(this.support.offer(any(), any())).thenReturn(this.viewers);
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(plugin.getAudioProvider()).thenReturn(mock(AudioProvider.class));
    when(plugin.getMcv2Support()).thenReturn(this.support);
    this.command = spy(new VideoMcv2Command(plugin));
    doNothing().when(this.command).playVideo(any(), any(), any(), any(), any(), anyString(), anyString(), anyString());
    this.player = mock(Player.class);
    when(this.player.getUniqueId()).thenReturn(this.viewer);
    this.selector = mock(MultiplePlayerSelector.class);
    when(this.selector.values()).thenReturn(List.of(this.player));
    this.sender = mock(CommandSender.class);
    this.world = mock(World.class);
    final ItemFrame frame = Mcv2SupportTest.frame(Mcv2SupportTest.map(Material.FILLED_MAP, true, 20));
    when(frame.getLocation()).thenReturn(new Location(this.world, 5.5, 64.5, 7.03));
    when(frame.getFacing()).thenReturn(BlockFace.SOUTH);
    when(this.world.getEntitiesByClass(ItemFrame.class)).thenReturn(List.of(frame));
    when(TestServer.server().getWorlds()).thenReturn(List.of(this.world));
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
        Mcv2Profile.LOW,
        DitheringArgument.FILTER_LITE,
        "",
        "clip.mp4"
      );
  }

  @Test
  void offersThePackAndPlaysOnTheWall() {
    this.play("640x384", "5x3", 20);
    final ArgumentCaptor<Mcv2Configuration> configurations = ArgumentCaptor.forClass(Mcv2Configuration.class);
    verify(this.support).offer(configurations.capture(), eq(List.of(this.player)));
    final Mcv2Configuration configuration = configurations.getValue();
    assertEquals(new Location(this.world, 5, 64, 7), configuration.getOrigin());
    assertEquals(BlockFace.SOUTH, configuration.getFacing());
    assertEquals(20, configuration.getMap());
    assertEquals(5, configuration.getColumns());
    assertEquals(3, configuration.getRows());
    assertEquals(640, configuration.getVideoWidth());
    assertEquals(384, configuration.getVideoHeight());
    assertEquals(EncoderSettings.LOW_BANDWIDTH, configuration.getSettings());
    assertEquals(List.of(this.viewer), List.copyOf(configuration.getViewers()));
    assertEquals(Mcv2Configuration.DEFAULT_BACKLOG_LIMIT, configuration.getBacklogLimit());
    assertEquals(4, configuration.getPageSlots());
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
    final Object built = providers.getValue().buildConfiguration(Pair.pair(640, 384));
    final VideoMcv2Command.Mcv2Settings settings = assertInstanceOf(VideoMcv2Command.Mcv2Settings.class, built);
    assertSame(configuration, settings.configuration());
    assertSame(this.viewers, settings.viewers());
    assertEquals(DitheringArgument.FILTER_LITE, settings.dithering());
  }

  @Test
  void startsTheResultOnThePlayerManager() {
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ ->
      new VideoMcv2Command.Mcv2Settings(configuration, this.viewers, DitheringArgument.FILTER_LITE);
    try (MockedConstruction<Mcv2Result> results = Mockito.mockConstruction(Mcv2Result.class)) {
      final VideoPipelineStep step = this.command.createVideoFilter(Pair.pair(640, 384), provider);
      final Mcv2Result result = results.constructed().getFirst();
      verify(this.manager).startFilter(result);
      assertSame(result, step.getFilter());
    }
  }

  @Test
  void takesThePageSlotsAndTheBacklogLimitOfAMeasurement() {
    System.setProperty(VideoMcv2Command.PAGE_SLOTS_PROPERTY, "8");
    System.setProperty(VideoMcv2Command.BACKLOG_PROPERTY, "65536");
    try {
      final Mcv2Configuration limited = VideoMcv2Command.configure(
        this.sender,
        Pair.pair(5, 3),
        Pair.pair(640, 384),
        20,
        Mcv2Profile.LIVE,
        List.of()
      );
      assertEquals(8, Objects.requireNonNull(limited).getPageSlots());
      assertEquals(65536, limited.getBacklogLimit());
      assertEquals(EncoderSettings.LIVE, limited.getSettings());
      System.setProperty(VideoMcv2Command.BACKLOG_PROPERTY, "none");
      assertEquals(Long.MAX_VALUE, VideoMcv2Command.backlogLimit());
    } finally {
      System.clearProperty(VideoMcv2Command.PAGE_SLOTS_PROPERTY);
      System.clearProperty(VideoMcv2Command.BACKLOG_PROPERTY);
    }
    assertEquals(Mcv2Configuration.DEFAULT_BACKLOG_LIMIT, VideoMcv2Command.backlogLimit());
  }

  @Test
  void refusesInvalidSizesAndMissingWalls() {
    this.play("640x384", "65x1", 20);
    this.play("nope", "5x3", 20);
    verify(this.support, never()).offer(any(), any());
    this.play("640x384", "5x3", 21);
    verify(this.sender).sendMessage(Message.MCV2_SCREEN_ERROR.build(21));
    verify(this.support, never()).offer(any(), any());
    verify(this.command, never()).playVideo(any(), any(), any(), any(), any(), anyString(), anyString(), anyString());
    assertNull(VideoMcv2Command.configure(this.sender, Pair.pair(5, 3), Pair.pair(640, 384), 99, Mcv2Profile.SHIP, List.of()));
  }
}
