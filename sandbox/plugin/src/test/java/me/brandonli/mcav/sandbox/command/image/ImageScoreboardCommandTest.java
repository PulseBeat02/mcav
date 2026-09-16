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
package me.brandonli.mcav.sandbox.command.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link ImageScoreboardCommand}.
 */
final class ImageScoreboardCommandTest {

  private static final String RESOLUTION = "24x15";
  private static final String CHARACTER = "#";
  private static final String MRL = "picture.png";

  private ImageScoreboardCommand command;
  private MultiplePlayerSelector selector;
  private CommandSender sender;
  private UUID viewer;

  @BeforeEach
  void createCommand() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final ImageManager manager = mock(ImageManager.class);
    when(plugin.getImageManager()).thenReturn(manager);
    final ImageScoreboardCommand realCommand = new ImageScoreboardCommand(plugin);
    this.command = spy(realCommand);
    doNothing().when(this.command).displayImage(any(), any(), any(), any());

    this.viewer = UUID.randomUUID();
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(this.viewer);
    final List<Player> selected = List.of(player);
    this.selector = mock(MultiplePlayerSelector.class);
    when(this.selector.values()).thenReturn(selected);
    this.sender = mock(CommandSender.class);
  }

  private AbstractImageCommand.ImageConfigurationProvider captureProvider() {
    final ArgumentCaptor<AbstractImageCommand.ImageConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractImageCommand.ImageConfigurationProvider.class
    );
    verify(this.command).displayImage(providers.capture(), eq(this.sender), eq(RESOLUTION), eq(MRL));
    return providers.getValue();
  }

  private ScoreboardConfiguration createImage(final AbstractImageCommand.ImageConfigurationProvider provider) {
    final Pair<Integer, Integer> resolution = Pair.pair(24, 15);
    final DisplayableImage display = mock(DisplayableImage.class);
    final ArgumentCaptor<ScoreboardConfiguration> configurations = ArgumentCaptor.forClass(ScoreboardConfiguration.class);
    try (final MockedStatic<DisplayableImage> displays = Mockito.mockStatic(DisplayableImage.class)) {
      displays.when(() -> DisplayableImage.scoreboard(any())).thenReturn(display);
      final DisplayableImage created = this.command.createImage(resolution, provider);
      assertSame(display, created);
      displays.verify(() -> DisplayableImage.scoreboard(configurations.capture()));
    }
    return configurations.getValue();
  }

  @Test
  void showsTheImageOnTheScoreboardOfTheSelectedPlayers() {
    this.command.showImage(this.sender, this.selector, RESOLUTION, CHARACTER, MRL);
    final AbstractImageCommand.ImageConfigurationProvider provider = this.captureProvider();
    final ScoreboardConfiguration configuration = this.createImage(provider);

    final Collection<UUID> viewers = configuration.getViewers();
    final List<UUID> viewerList = new ArrayList<>(viewers);
    final List<UUID> expectedViewers = List.of(this.viewer);
    final int width = configuration.getWidth();
    final int lines = configuration.getLines();
    final String character = configuration.getCharacter();
    assertEquals(expectedViewers, viewerList);
    assertEquals(24, width);
    assertEquals(15, lines);
    assertEquals(CHARACTER, character);
  }

  @Test
  void refusesMoreLinesThanAScoreboardShows() {
    this.command.showImage(this.sender, this.selector, "24x16", CHARACTER, MRL);
    verify(this.command, never()).displayImage(any(), any(), any(), any());
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.SCOREBOARD_LINES.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
  }

  @Test
  void refusesNullArguments() {
    final AbstractImageCommand.ImageConfigurationProvider provider = _ -> "configuration";
    final Pair<Integer, Integer> resolution = Pair.pair(24, 15);
    assertThrows(NullPointerException.class, () -> this.command.showImage(null, this.selector, RESOLUTION, CHARACTER, MRL));
    assertThrows(NullPointerException.class, () -> this.command.showImage(this.sender, null, RESOLUTION, CHARACTER, MRL));
    assertThrows(NullPointerException.class, () -> this.command.showImage(this.sender, this.selector, null, CHARACTER, MRL));
    assertThrows(NullPointerException.class, () -> this.command.showImage(this.sender, this.selector, RESOLUTION, null, MRL));
    assertThrows(NullPointerException.class, () -> this.command.showImage(this.sender, this.selector, RESOLUTION, CHARACTER, null));
    assertThrows(NullPointerException.class, () -> this.command.createImage(null, provider));
    assertThrows(NullPointerException.class, () -> this.command.createImage(resolution, null));
  }
}
