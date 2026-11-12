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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.result.ScoreboardResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.AudioArgument;
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
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

final class VideoScoreboardCommandTest {

  private static final String RESOLUTION = "24x15";
  private static final int RESOLUTION_WIDTH = 24;
  private static final int RESOLUTION_HEIGHT = 15;

  private static final String CHARACTER = "#";

  private static final String MRL = "0";

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
    Mockito.doAnswer(invocation -> this.runFilterOnMainThread(invocation, plugin))
      .when(this.manager)
      .startFilter(any(FunctionalVideoFilter.class));
    final AudioProvider provider = mock(AudioProvider.class);
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(plugin.getAudioProvider()).thenReturn(provider);
    final VideoScoreboardCommand realCommand = new VideoScoreboardCommand(plugin);
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
  void shouldPassTheArgumentsOnAndConfigureTheSidebar() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.playAndCaptureProvider();
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);

    final Object built = configurationProvider.buildConfiguration(resolution);

    final ScoreboardConfiguration configuration = assertInstanceOf(ScoreboardConfiguration.class, built);
    this.assertSidebar(configuration);
  }

  private AbstractVideoCommand.VideoConfigurationProvider playAndCaptureProvider() {
    this.command.playVideo(
      this.sender,
      this.selector,
      PlayerArgument.DEVICE,
      AudioArgument.SIMPLE_VOICE_CHAT,
      RESOLUTION,
      CHARACTER,
      "",
      MRL,
      null
    );
    final ArgumentCaptor<AbstractVideoCommand.VideoConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractVideoCommand.VideoConfigurationProvider.class
    );
    verify(this.command).playVideo(
      providers.capture(),
      eq(this.sender),
      eq(this.selector),
      eq(PlayerArgument.DEVICE),
      eq(AudioArgument.SIMPLE_VOICE_CHAT),
      eq(RESOLUTION),
      eq(MRL),
      eq(""),
      isNull()
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
    assertEquals(RESOLUTION_WIDTH, width);
    assertEquals(RESOLUTION_HEIGHT, lines);
    assertEquals(CHARACTER, character);
  }

  @Test
  void shouldShowTheFramesOnASidebarShownOnTheMainThread() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.playAndCaptureProvider();
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final List<List<?>> arguments = new ArrayList<>();
    try (
      final MockedConstruction<ScoreboardResult> results = Mockito.mockConstruction(ScoreboardResult.class, (_, context) ->
        this.recordArguments(context, arguments)
      )
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

  private void recordArguments(final MockedConstruction.Context context, final List<List<?>> arguments) {
    final List<?> constructorArguments = context.arguments();
    arguments.add(constructorArguments);
  }

  private void assertStartedOnTheMainThread(final FunctionalVideoFilter result) {
    verify(this.manager).startFilter(result);
    verify(result, never()).start();

    final int tasks = TestServer.runPendingTasks();
    assertEquals(1, tasks);
    verify(result).start();
  }

  @Test
  void shouldRefuseMoreLinesThanAScoreboardShows() {
    this.command.playVideo(this.sender, this.selector, PlayerArgument.DEVICE, AudioArgument.NONE, "24x16", CHARACTER, "", MRL, null);

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
      anyString(),
      isNull()
    );
    verify(this.manager, never()).getStarting();
    verify(this.manager, never()).getService();
  }

  @Test
  void shouldRefuseNullArguments() {
    final PlayerArgument ffmpeg = PlayerArgument.FFMPEG;
    final AudioArgument none = AudioArgument.NONE;
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ -> new Object();

    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(null, this.selector, ffmpeg, none, RESOLUTION, CHARACTER, "", MRL, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, null, ffmpeg, none, RESOLUTION, CHARACTER, "", MRL, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, null, none, RESOLUTION, CHARACTER, "", MRL, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, ffmpeg, null, RESOLUTION, CHARACTER, "", MRL, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, ffmpeg, none, null, CHARACTER, "", MRL, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, ffmpeg, none, RESOLUTION, null, "", MRL, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, ffmpeg, none, RESOLUTION, CHARACTER, null, MRL, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, ffmpeg, none, RESOLUTION, CHARACTER, "", null, null)
    );
    assertThrows(NullPointerException.class, () -> this.command.createVideoFilter(null, provider));
    assertThrows(NullPointerException.class, () -> this.command.createVideoFilter(resolution, null));
  }
}
