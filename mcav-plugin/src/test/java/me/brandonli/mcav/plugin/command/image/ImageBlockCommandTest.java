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
package me.brandonli.mcav.plugin.command.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.testing.FakeWorld;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class ImageBlockCommandTest {

  private static final String RESOLUTION = "4x2";
  private static final String MRL = "picture.png";

  private static final int RESOLUTION_WIDTH = 4;
  private static final int RESOLUTION_HEIGHT = 2;

  private ImageBlockCommand command;
  private MultiplePlayerSelector selector;
  private CommandSender sender;
  private UUID viewer;
  private Location location;

  @BeforeEach
  void createCommand() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final ImageManager manager = mock(ImageManager.class);
    when(plugin.getImageManager()).thenReturn(manager);
    final ImageBlockCommand realCommand = new ImageBlockCommand(plugin);
    this.command = spy(realCommand);
    doNothing().when(this.command).displayImage(any(), any(), any(), any(), isNull());

    this.viewer = UUID.randomUUID();
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(this.viewer);
    final List<Player> selected = List.of(player);
    this.selector = mock(MultiplePlayerSelector.class);
    when(this.selector.values()).thenReturn(selected);
    this.sender = mock(CommandSender.class);

    final FakeWorld world = new FakeWorld();
    this.location = world.createLocation(1.0, 64.0, 2.0);
  }

  @Test
  void shouldShowTheImageAsBlocksToTheSelectedPlayers() {
    this.command.showImage(this.sender, this.selector, RESOLUTION, this.location, MRL, null);
    final AbstractImageCommand.ImageConfigurationProvider provider = this.captureProvider();
    final BlockConfiguration configuration = this.createImage(provider);

    final Collection<UUID> viewers = configuration.getViewers();
    final List<UUID> viewerList = new ArrayList<>(viewers);
    final List<UUID> expectedViewers = List.of(this.viewer);
    final int width = configuration.getBlockWidth();
    final int height = configuration.getBlockHeight();
    final Location position = configuration.getPosition();
    assertEquals(expectedViewers, viewerList);
    assertEquals(RESOLUTION_WIDTH, width);
    assertEquals(RESOLUTION_HEIGHT, height);
    assertEquals(this.location, position);
  }

  private AbstractImageCommand.ImageConfigurationProvider captureProvider() {
    final ArgumentCaptor<AbstractImageCommand.ImageConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractImageCommand.ImageConfigurationProvider.class
    );
    verify(this.command).displayImage(providers.capture(), eq(this.sender), eq(RESOLUTION), eq(MRL), isNull());

    return providers.getValue();
  }

  private BlockConfiguration createImage(final AbstractImageCommand.ImageConfigurationProvider provider) {
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final DisplayableImage display = mock(DisplayableImage.class);
    final ArgumentCaptor<BlockConfiguration> configurations = ArgumentCaptor.forClass(BlockConfiguration.class);
    try (final MockedStatic<DisplayableImage> displays = Mockito.mockStatic(DisplayableImage.class)) {
      displays.when(() -> DisplayableImage.block(any())).thenReturn(display);
      final DisplayableImage created = this.command.createImage(resolution, provider);
      assertSame(display, created);
      displays.verify(() -> DisplayableImage.block(configurations.capture()));
    }
    return configurations.getValue();
  }

  @Test
  void shouldRefuseNullArguments() {
    final AbstractImageCommand.ImageConfigurationProvider provider = _ -> "configuration";
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    assertThrows(NullPointerException.class, () -> this.command.showImage(null, this.selector, RESOLUTION, this.location, MRL, null));
    assertThrows(NullPointerException.class, () -> this.command.showImage(this.sender, null, RESOLUTION, this.location, MRL, null));
    assertThrows(NullPointerException.class, () -> this.command.showImage(this.sender, this.selector, null, this.location, MRL, null));
    assertThrows(NullPointerException.class, () -> this.command.showImage(this.sender, this.selector, RESOLUTION, null, MRL, null));
    assertThrows(NullPointerException.class, () ->
      this.command.showImage(this.sender, this.selector, RESOLUTION, this.location, null, null)
    );
    assertThrows(NullPointerException.class, () -> this.command.createImage(null, provider));
    assertThrows(NullPointerException.class, () -> this.command.createImage(resolution, null));
  }

  @Test
  void shouldPassTheFiltersOnToTheImage() {
    final String filters = "invert";
    doNothing().when(this.command).displayImage(any(), any(), any(), any(), eq(filters));
    this.command.showImage(this.sender, this.selector, RESOLUTION, this.location, MRL, filters);

    verify(this.command).displayImage(any(), eq(this.sender), eq(RESOLUTION), eq(MRL), eq(filters));
  }

  @Test
  void shouldRefuseAProviderThatBuildsAnotherConfiguration() {
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final AbstractImageCommand.ImageConfigurationProvider provider = _ -> new Object();
    assertThrows(ClassCastException.class, () -> this.command.createImage(resolution, provider));
  }
}
