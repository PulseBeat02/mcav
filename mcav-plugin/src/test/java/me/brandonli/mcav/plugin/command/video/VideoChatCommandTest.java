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
import me.brandonli.mcav.bukkit.media.config.ChatConfiguration;
import me.brandonli.mcav.bukkit.media.result.ChatResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
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

final class VideoChatCommandTest {

  private static final String RESOLUTION = "16x16";
  private static final int RESOLUTION_SIDE = 16;

  private static final String CHARACTER = "#";

  private static final String VIDEO_FILE_NAME = "clip.mp4";

  private VideoPlayerManager manager;
  private VideoChatCommand command;
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
    final VideoChatCommand realCommand = new VideoChatCommand(plugin);
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
  void shouldPassTheArgumentsOnAndConfigureTheChat() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.playAndCaptureProvider();
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_SIDE, RESOLUTION_SIDE);

    final Object built = configurationProvider.buildConfiguration(resolution);

    final ChatConfiguration configuration = assertInstanceOf(ChatConfiguration.class, built);
    this.assertChat(configuration);
  }

  private AbstractVideoCommand.VideoConfigurationProvider playAndCaptureProvider() {
    this.command.playVideo(
      this.sender,
      this.selector,
      PlayerArgument.VLC,
      AudioArgument.NONE,
      RESOLUTION,
      CHARACTER,
      "",
      VIDEO_FILE_NAME,
      null
    );
    final ArgumentCaptor<AbstractVideoCommand.VideoConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractVideoCommand.VideoConfigurationProvider.class
    );
    verify(this.command).playVideo(
      providers.capture(),
      eq(this.sender),
      eq(this.selector),
      eq(PlayerArgument.VLC),
      eq(AudioArgument.NONE),
      eq(RESOLUTION),
      eq(VIDEO_FILE_NAME),
      eq(""),
      isNull()
    );

    return providers.getValue();
  }

  private void assertChat(final ChatConfiguration configuration) {
    final Collection<UUID> viewers = configuration.getViewers();
    final List<UUID> viewerList = new ArrayList<>(viewers);
    final List<UUID> expectedViewers = List.of(this.viewer);
    final int width = configuration.getChatWidth();
    final int height = configuration.getChatHeight();
    final String character = configuration.getCharacter();
    assertEquals(expectedViewers, viewerList);
    assertEquals(RESOLUTION_SIDE, width);
    assertEquals(RESOLUTION_SIDE, height);
    assertEquals(CHARACTER, character);
  }

  @Test
  void shouldShowTheFramesInTheChatStartedOnTheMainThread() {
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.playAndCaptureProvider();
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_SIDE, RESOLUTION_SIDE);
    final List<List<?>> arguments = new ArrayList<>();
    try (
      final MockedConstruction<ChatResult> results = Mockito.mockConstruction(ChatResult.class, (_, context) ->
        this.recordArguments(context, arguments)
      )
    ) {
      final VideoPipelineStep pipeline = this.command.createVideoFilter(resolution, configurationProvider);
      final List<ChatResult> constructed = results.constructed();
      final ChatResult result = constructed.getFirst();
      final VideoFilter filter = pipeline.getFilter();
      assertSame(result, filter);
      this.assertStartedOnTheMainThread(result);
    }

    final List<?> constructorArguments = arguments.getFirst();
    final ChatConfiguration configuration = (ChatConfiguration) constructorArguments.getFirst();
    this.assertChat(configuration);
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
  void shouldRefuseNullArguments() {
    final PlayerArgument ffmpeg = PlayerArgument.FFMPEG;
    final AudioArgument none = AudioArgument.NONE;
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_SIDE, RESOLUTION_SIDE);
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ -> new Object();

    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(null, this.selector, ffmpeg, none, RESOLUTION, CHARACTER, "", VIDEO_FILE_NAME, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, null, ffmpeg, none, RESOLUTION, CHARACTER, "", VIDEO_FILE_NAME, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, null, none, RESOLUTION, CHARACTER, "", VIDEO_FILE_NAME, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, ffmpeg, null, RESOLUTION, CHARACTER, "", VIDEO_FILE_NAME, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, ffmpeg, none, null, CHARACTER, "", VIDEO_FILE_NAME, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, ffmpeg, none, RESOLUTION, null, "", VIDEO_FILE_NAME, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, ffmpeg, none, RESOLUTION, CHARACTER, null, VIDEO_FILE_NAME, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(this.sender, this.selector, ffmpeg, none, RESOLUTION, CHARACTER, "", null, null)
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
    this.command.playVideo(
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      RESOLUTION,
      CHARACTER,
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

  @Test
  void shouldRefuseAProviderThatBuildsAnotherConfiguration() {
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_SIDE, RESOLUTION_SIDE);
    final AbstractVideoCommand.VideoConfigurationProvider provider = _ -> new Object();
    assertThrows(ClassCastException.class, () -> this.command.createVideoFilter(resolution, provider));
  }
}
