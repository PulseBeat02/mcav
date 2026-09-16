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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.MapDisplaySettings;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
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
 * Tests {@link ImageMapCommand}.
 */
final class ImageMapCommandTest {

  private static final String RESOLUTION = "384x256";
  private static final String BLOCKS = "3x2";
  private static final int MAP_ID = 5;
  private static final String MRL = "picture.png";

  private ImageMapCommand command;
  private MultiplePlayerSelector selector;
  private CommandSender sender;
  private UUID viewer;

  @BeforeEach
  void createCommand() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final ImageManager manager = mock(ImageManager.class);
    when(plugin.getImageManager()).thenReturn(manager);
    final ImageMapCommand realCommand = new ImageMapCommand(plugin);
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

  private AbstractImageCommand.ImageConfigurationProvider showAndCaptureProvider(final DitheringArgument dithering) {
    this.command.showMapImage(this.sender, this.selector, RESOLUTION, BLOCKS, MAP_ID, dithering, MRL);
    final ArgumentCaptor<AbstractImageCommand.ImageConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractImageCommand.ImageConfigurationProvider.class
    );
    verify(this.command).displayImage(providers.capture(), eq(this.sender), eq(RESOLUTION), eq(MRL));
    return providers.getValue();
  }

  private DitherAlgorithm createImageAndCaptureAlgorithm(final AbstractImageCommand.ImageConfigurationProvider provider) {
    final Pair<Integer, Integer> resolution = Pair.pair(384, 256);
    final DisplayableImage display = mock(DisplayableImage.class);
    final ArgumentCaptor<MapConfiguration> configurations = ArgumentCaptor.forClass(MapConfiguration.class);
    final ArgumentCaptor<DitherAlgorithm> algorithms = ArgumentCaptor.forClass(DitherAlgorithm.class);
    try (final MockedStatic<DisplayableImage> displays = Mockito.mockStatic(DisplayableImage.class)) {
      displays.when(() -> DisplayableImage.map(any(), any())).thenReturn(display);
      final DisplayableImage created = this.command.createImage(resolution, provider);
      assertSame(display, created);
      displays.verify(() -> DisplayableImage.map(configurations.capture(), algorithms.capture()));
    }

    final MapConfiguration used = configurations.getValue();
    final int usedMap = used.getMap();
    assertEquals(MAP_ID, usedMap);
    return algorithms.getValue();
  }

  @Test
  void buildsTheSettingsOfTheWallOfMaps() {
    final AbstractImageCommand.ImageConfigurationProvider provider = this.showAndCaptureProvider(DitheringArgument.FILTER_LITE);
    final Pair<Integer, Integer> resolution = Pair.pair(384, 256);
    final Object built = provider.buildConfiguration(resolution);
    final MapDisplaySettings settings = assertInstanceOf(MapDisplaySettings.class, built);
    final MapConfiguration configuration = settings.getConfiguration();

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
    assertEquals(3, blockWidth);
    assertEquals(2, blockHeight);
    assertEquals(384, width);
    assertEquals(256, height);
  }

  @Test
  void dithersTheImageWithTheChosenAlgorithm() {
    final AbstractImageCommand.ImageConfigurationProvider provider = this.showAndCaptureProvider(DitheringArgument.FILTER_LITE);
    final DitherAlgorithm algorithm = this.createImageAndCaptureAlgorithm(provider);
    final DitherAlgorithm expected = DitheringArgument.FILTER_LITE.createAlgorithm();
    assertSame(expected, algorithm);
  }

  @Test
  void givesEveryImageItsOwnTemporalAlgorithm() {
    final AbstractImageCommand.ImageConfigurationProvider provider =
      this.showAndCaptureProvider(DitheringArgument.FLOYD_STEINBERG_TEMPORAL);
    final DitherAlgorithm first = this.createImageAndCaptureAlgorithm(provider);
    final DitherAlgorithm second = this.createImageAndCaptureAlgorithm(provider);
    assertNotSame(first, second);
  }

  @Test
  void refusesInvalidWallSizes() {
    this.command.showMapImage(this.sender, this.selector, RESOLUTION, "3-2", MAP_ID, DitheringArgument.FILTER_LITE, MRL);
    verify(this.command, never()).displayImage(any(), any(), any(), any());
    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
  }

  @Test
  void refusesNullArguments() {
    final DitheringArgument dithering = DitheringArgument.FILTER_LITE;
    final AbstractImageCommand.ImageConfigurationProvider provider = _ -> "configuration";
    final Pair<Integer, Integer> resolution = Pair.pair(384, 256);
    assertThrows(NullPointerException.class, () ->
      this.command.showMapImage(null, this.selector, RESOLUTION, BLOCKS, MAP_ID, dithering, MRL)
    );
    assertThrows(NullPointerException.class, () -> this.command.showMapImage(this.sender, null, RESOLUTION, BLOCKS, MAP_ID, dithering, MRL)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.showMapImage(this.sender, this.selector, null, BLOCKS, MAP_ID, dithering, MRL)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.showMapImage(this.sender, this.selector, RESOLUTION, null, MAP_ID, dithering, MRL)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.showMapImage(this.sender, this.selector, RESOLUTION, BLOCKS, MAP_ID, null, MRL)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.showMapImage(this.sender, this.selector, RESOLUTION, BLOCKS, MAP_ID, dithering, null)
    );
    assertThrows(NullPointerException.class, () -> this.command.createImage(null, provider));
    assertThrows(NullPointerException.class, () -> this.command.createImage(resolution, null));
  }
}
