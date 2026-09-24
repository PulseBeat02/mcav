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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.result.ScoreboardResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
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
import org.mockito.Mockito;

/**
 * Tests {@link VideoScoreboardCommand}. The common playback flow is skipped, so only the arguments passed to it
 * and the sidebar it creates are checked.
 */
final class VideoScoreboardCommandTest {

  private VideoPlayerManager manager;
  private VideoScoreboardCommand command;
  private MultiplePlayerSelector selector;
  private CommandSender sender;
  private UUID viewer;

  @BeforeEach
  void createCommand() {
    TestServer.resetWithDeferredTasks();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = mock(VideoPlayerManager.class);
    org.mockito.Mockito.doAnswer(invocation -> {
      final FunctionalVideoFilter filter = invocation.getArgument(0);
      final org.bukkit.scheduler.BukkitScheduler scheduler = org.bukkit.Bukkit.getScheduler();
      scheduler.runTask(plugin, filter::start);
      return null;
    })
      .when(this.manager)
      .startFilter(any(FunctionalVideoFilter.class));
    final AudioProvider provider = mock(AudioProvider.class);
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(plugin.getAudioProvider()).thenReturn(provider);
    final VideoScoreboardCommand realCommand = new VideoScoreboardCommand(plugin);
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

  private AbstractVideoCommand.VideoConfigurationProvider playAndCaptureProvider() {
    this.command.playVideo(this.sender, this.selector, PlayerArgument.DEVICE, AudioArgument.SIMPLE_VOICE_CHAT, "24x15", "#", "", "0");
    final ArgumentCaptor<AbstractVideoCommand.VideoConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractVideoCommand.VideoConfigurationProvider.class
    );
    verify(this.command).playVideo(
      providers.capture(),
      eq(this.sender),
      eq(this.selector),
      eq(PlayerArgument.DEVICE),
      eq(AudioArgument.SIMPLE_VOICE_CHAT),
      eq("24x15"),
      eq("0"),
      eq("")
    );
    return providers.getValue();
  }

  private void assertSidebar(final ScoreboardConfiguration configuration) {
    final Collection<UUID> viewers = configuration.getViewers();
    final List<UUID> viewerList = new ArrayList<>(viewers);
    final List<UUID> expectedViewers = List.of(this.viewer);
    final int width = configuration.getWidth();
    final int lines = configuration.getLines();
    final String character = configuration.getCharacter();
    assertEquals(expectedViewers, viewerList);
    assertEquals(24, width);
    assertEquals(15, lines);
    assertEquals("#", character);
  }

  private void assertStartedOnTheMainThread(final FunctionalVideoFilter result) {
    verify(this.manager).startFilter(result);
    verify(result, never()).start();
    final int tasks = TestServer.runPendingTasks();
    assertEquals(1, tasks);
    verify(result).start();
  }

  @Test
  void passesTheArgumentsOnAndConfiguresTheSidebar() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.playAndCaptureProvider();
    final Pair<Integer, Integer> resolution = Pair.pair(24, 15);

    final Object built = configurationProvider.buildConfiguration(resolution);

    final ScoreboardConfiguration configuration = assertInstanceOf(ScoreboardConfiguration.class, built);
    this.assertSidebar(configuration);
  }

  @Test
  void showsTheFramesOnASidebarShownOnTheMainThread() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.playAndCaptureProvider();
    final Pair<Integer, Integer> resolution = Pair.pair(24, 15);
    final List<List<?>> arguments = new ArrayList<>();
    try (
      final MockedConstruction<ScoreboardResult> results = Mockito.mockConstruction(ScoreboardResult.class, (_, context) -> {
        final List<?> constructorArguments = context.arguments();
        arguments.add(constructorArguments);
      })
    ) {
      final VideoPipelineStep pipeline = this.command.createVideoFilter(resolution, configurationProvider);
      final List<ScoreboardResult> constructed = results.constructed();
      final ScoreboardResult result = constructed.getFirst();
      final VideoFilter filter = pipeline.getFilter();
      assertSame(result, filter);
      this.assertStartedOnTheMainThread(result);
    }

    final List<?> constructorArguments = arguments.getFirst();
    final ScoreboardConfiguration configuration = (ScoreboardConfiguration) constructorArguments.getFirst();
    this.assertSidebar(configuration);
  }

  @Test
  void refusesMoreLinesThanAScoreboardShows() {
    this.command.playVideo(this.sender, this.selector, PlayerArgument.DEVICE, AudioArgument.NONE, "24x16", "#", "", "0");

    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.SCOREBOARD_LINES.build();
    final List<Component> expectedMessages = List.of(error);
    assertEquals(expectedMessages, messages);
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
    verify(this.manager, never()).getStatus();
    verify(this.manager, never()).getService();
  }
}
