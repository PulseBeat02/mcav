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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Optional;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.MapDisplaySettings;
import me.brandonli.mcav.plugin.command.video.Mcv2Output;
import me.brandonli.mcav.plugin.command.video.Mcv2Support;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class ImageMapCommandTest {

  private static final String RESOLUTION = "384x256";
  private static final String BLOCKS = "3x2";
  private static final int MAP_ID = 5;
  private static final String MRL = "picture.png";

  private static final int RESOLUTION_WIDTH = 384;
  private static final int RESOLUTION_HEIGHT = 256;

  private static final int BLOCK_WIDTH = 3;
  private static final int BLOCK_HEIGHT = 2;

  private ImageMapCommand command;
  private PluginDataConfigurationMapper configuration;
  private Mcv2Support support;
  private MultiplePlayerSelector selector;
  private CommandSender sender;
  private UUID viewer;

  @BeforeEach
  void createCommand() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    final ImageManager manager = mock(ImageManager.class);
    when(plugin.getImageManager()).thenReturn(manager);
    when(plugin.getOnlinePlayers()).thenReturn(new OnlinePlayers());
    this.configuration = mock(PluginDataConfigurationMapper.class);
    when(this.configuration.getMcv2DefaultCodec()).thenReturn(MapCodec.DITHER);
    when(plugin.getConfiguration()).thenReturn(this.configuration);
    this.support = mock(Mcv2Support.class);
    when(plugin.getMcv2Support()).thenReturn(this.support);
    final ImageMapCommand realCommand = new ImageMapCommand(plugin);
    this.command = spy(realCommand);
    doNothing().when(this.command).displayImage(any(), any(), any(), any(), isNull());

    this.viewer = UUID.randomUUID();
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(this.viewer);
    final List<Player> selected = List.of(player);
    this.selector = mock(MultiplePlayerSelector.class);
    when(this.selector.values()).thenReturn(selected);
    this.sender = mock(CommandSender.class);
  }

  @Test
  void shouldBuildTheSettingsOfTheWallOfMaps() {
    final AbstractImageCommand.ImageConfigurationProvider provider = this.showAndCaptureProvider(DitheringArgument.FILTER_LITE);
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
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
    assertEquals(BLOCK_WIDTH, blockWidth);
    assertEquals(BLOCK_HEIGHT, blockHeight);
    assertEquals(RESOLUTION_WIDTH, width);
    assertEquals(RESOLUTION_HEIGHT, height);
  }

  private AbstractImageCommand.ImageConfigurationProvider showAndCaptureProvider(final DitheringArgument dithering) {
    this.command.showImage(this.sender, this.selector, RESOLUTION, BLOCKS, MAP_ID, dithering, MRL, null, null);
    final ArgumentCaptor<AbstractImageCommand.ImageConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractImageCommand.ImageConfigurationProvider.class
    );
    verify(this.command).displayImage(providers.capture(), eq(this.sender), eq(RESOLUTION), eq(MRL), isNull());

    return providers.getValue();
  }

  @Test
  void shouldDitherTheImageWithTheChosenAlgorithm() {
    final AbstractImageCommand.ImageConfigurationProvider provider = this.showAndCaptureProvider(DitheringArgument.FILTER_LITE);
    final DitherAlgorithm algorithm = this.createImageAndCaptureAlgorithm(provider);
    final DitherAlgorithm expected = DitheringArgument.FILTER_LITE.createAlgorithm();
    assertSame(expected, algorithm);
  }

  private DitherAlgorithm createImageAndCaptureAlgorithm(final AbstractImageCommand.ImageConfigurationProvider provider) {
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
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
  void shouldGiveEveryImageItsOwnTemporalAlgorithm() {
    final AbstractImageCommand.ImageConfigurationProvider provider = this.showAndCaptureProvider(
      DitheringArgument.FLOYD_STEINBERG_TEMPORAL
    );
    final DitherAlgorithm first = this.createImageAndCaptureAlgorithm(provider);
    final DitherAlgorithm second = this.createImageAndCaptureAlgorithm(provider);
    assertNotSame(first, second);
  }

  @ParameterizedTest
  @ValueSource(strings = { "3-2", "65x1", "1x65" })
  void shouldRefuseInvalidWallSizes(final String wall) {
    this.command.showImage(this.sender, this.selector, RESOLUTION, wall, MAP_ID, DitheringArgument.FILTER_LITE, MRL, null, null);
    verify(this.command, never()).displayImage(any(), any(), any(), any(), isNull());

    final List<Component> messages = Components.received(this.sender);
    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    final List<Component> expected = List.of(error);
    assertEquals(expected, messages);
  }

  @Test
  void shouldRefuseNullArguments() {
    final DitheringArgument dithering = DitheringArgument.FILTER_LITE;
    final AbstractImageCommand.ImageConfigurationProvider provider = _ -> "configuration";
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    assertThrows(NullPointerException.class, () ->
      this.command.showImage(null, this.selector, RESOLUTION, BLOCKS, MAP_ID, dithering, MRL, null, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.showImage(this.sender, null, RESOLUTION, BLOCKS, MAP_ID, dithering, MRL, null, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.showImage(this.sender, this.selector, null, BLOCKS, MAP_ID, dithering, MRL, null, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.showImage(this.sender, this.selector, RESOLUTION, null, MAP_ID, dithering, MRL, null, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.showImage(this.sender, this.selector, RESOLUTION, BLOCKS, MAP_ID, null, MRL, null, null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.showImage(this.sender, this.selector, RESOLUTION, BLOCKS, MAP_ID, dithering, null, null, null)
    );
    assertThrows(NullPointerException.class, () -> this.command.createImage(null, provider));
    assertThrows(NullPointerException.class, () -> this.command.createImage(resolution, null));
  }

  @Test
  void shouldShowTheImageWithMcv2WhenTheFlagSaysSo() {
    final AbstractImageCommand.ImageConfigurationProvider provider = this.showWithCodecAndCaptureProvider(MapCodec.MCV2);
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final Object builtConfiguration = provider.buildConfiguration(resolution);
    final ImageMapCommand.Mcv2ImageSettings settings = assertInstanceOf(ImageMapCommand.Mcv2ImageSettings.class, builtConfiguration);

    final List<UUID> copy = List.copyOf(settings.viewers());
    final List<UUID> expectedViewers = List.of(this.viewer);
    assertEquals(expectedViewers, copy);

    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    when(
      this.support.configure(this.sender, Pair.pair(BLOCK_WIDTH, BLOCK_HEIGHT), resolution, MAP_ID, Settings.DEFAULT, settings.viewers())
    ).thenReturn(Optional.of(configuration));
    final Mcv2Output output = mock(Mcv2Output.class);
    when(this.support.output(this.sender, configuration, DitheringArgument.NEAREST_COLOR)).thenReturn(Optional.of(output));

    final DisplayableImage image = this.command.createImage(resolution, provider);
    assertInstanceOf(Mcv2Image.class, image);
  }

  private AbstractImageCommand.ImageConfigurationProvider showWithCodecAndCaptureProvider(final @Nullable MapCodec codec) {
    this.command.showImage(this.sender, this.selector, RESOLUTION, BLOCKS, MAP_ID, DitheringArgument.NEAREST_COLOR, MRL, codec, null);
    final ArgumentCaptor<AbstractImageCommand.ImageConfigurationProvider> providers = ArgumentCaptor.forClass(
      AbstractImageCommand.ImageConfigurationProvider.class
    );
    verify(this.command).displayImage(providers.capture(), eq(this.sender), eq(RESOLUTION), eq(MRL), isNull());

    return providers.getValue();
  }

  @Test
  void shouldApplyTheConfiguredCodecWithoutTheFlag() {
    when(this.configuration.getMcv2DefaultCodec()).thenReturn(MapCodec.MCV2);
    final AbstractImageCommand.ImageConfigurationProvider provider = this.showWithCodecAndCaptureProvider(null);

    final Object configuration = provider.buildConfiguration(Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT));
    assertInstanceOf(ImageMapCommand.Mcv2ImageSettings.class, configuration);
  }

  @Test
  void shouldLetTheFlagWinOverTheConfiguredCodec() {
    when(this.configuration.getMcv2DefaultCodec()).thenReturn(MapCodec.MCV2);
    final AbstractImageCommand.ImageConfigurationProvider provider = this.showWithCodecAndCaptureProvider(MapCodec.DITHER);

    final Object configuration = provider.buildConfiguration(Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT));
    assertInstanceOf(MapDisplaySettings.class, configuration);
  }

  @Test
  void shouldDitherAnMcv2ImageWithoutAWallOrASlot() {
    final AbstractImageCommand.ImageConfigurationProvider provider = this.showWithCodecAndCaptureProvider(MapCodec.MCV2);
    final Mcv2Configuration configuration = mock(Mcv2Configuration.class);
    // no frame holds the map the first time, and every slot plays the second time
    when(this.support.configure(any(), any(), any(), eq(MAP_ID), any(), any()))
      .thenReturn(Optional.empty())
      .thenReturn(Optional.of(configuration));
    when(this.support.output(any(), any(), any())).thenReturn(Optional.empty());

    final DitherAlgorithm first = this.createImageAndCaptureAlgorithm(provider);
    final DitherAlgorithm second = this.createImageAndCaptureAlgorithm(provider);

    final DitherAlgorithm expected = DitheringArgument.NEAREST_COLOR.createAlgorithm();
    assertSame(expected, first);

    final DitherAlgorithm expectedSecond = DitheringArgument.NEAREST_COLOR.createAlgorithm();
    assertSame(expectedSecond, second);

    verify(this.support).output(this.sender, configuration, DitheringArgument.NEAREST_COLOR);
  }

  @Test
  void shouldPassTheFiltersOnToTheImage() {
    final String filters = "invert";
    doNothing().when(this.command).displayImage(any(), any(), any(), any(), eq(filters));
    this.command.showImage(this.sender, this.selector, RESOLUTION, BLOCKS, MAP_ID, DitheringArgument.FILTER_LITE, MRL, null, filters);

    verify(this.command).displayImage(any(), eq(this.sender), eq(RESOLUTION), eq(MRL), eq(filters));
  }

  @Test
  void shouldRefuseAProviderThatBuildsAnotherConfiguration() {
    final Pair<Integer, Integer> resolution = Pair.pair(RESOLUTION_WIDTH, RESOLUTION_HEIGHT);
    final AbstractImageCommand.ImageConfigurationProvider provider = _ -> new Object();
    assertThrows(ClassCastException.class, () -> this.command.createImage(resolution, provider));
  }
}
