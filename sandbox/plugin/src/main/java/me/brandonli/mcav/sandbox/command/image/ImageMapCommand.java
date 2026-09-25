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

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.MapDisplaySettings;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav image map}: dithers an image onto a wall of maps built with {@code /mcav screen}.
 */
public final class ImageMapCommand extends AbstractImageCommand {

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public ImageMapCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Handles {@code /mcav image map <playerSelector> <imageResolution> <blockDimensions> <mapId> <ditheringAlgorithm>
   * <mrl>}: dithers an image onto a wall of maps, the highest quality way to show an image in the world.
   *
   * <p>Build the wall first with {@code /mcav screen}, using the same block dimensions and map id, so that item
   * frames hold the maps the image is drawn on. Each map holds 128x128 pixels, and the maps are numbered row by row
   * from the top left one. The image is scaled to the resolution, then centered on the wall; parts that do not fit
   * are cropped equally on every side. Only one image is shown at a time; a new image command replaces the current
   * image, whatever its type.
   *
   * <p>Requires the permission {@code mcav.command.image.map}; players and the console can run it. The sender is
   * told "Loading image..." while the image downloads or loads from disk in the background, and "Image loaded!"
   * once it is shown. Invalid dimensions, a source that cannot be found or loaded, or an animated GIF is reported
   * with an error message instead.
   *
   * @param sender             who ran the command
   * @param playerSelector     the players who see the image on the maps, such as a player name or {@code @a}
   * @param imageResolution    the resolution the image is scaled to as {@code <width>x<height>} in pixels; use
   *                           128 times the block dimensions, such as {@code 640x640} for a 5x5 wall, to fill the
   *                           wall exactly
   * @param blockDimensions    the size of the wall as {@code <width>x<height>} in maps, such as {@code 5x5}
   * @param mapId              the id of the top left map of the wall, as given to {@code /mcav screen}
   * @param ditheringAlgorithm how colors are reduced to the map palette; {@code FILTER_LITE} gives the best
   *                           results for most images, see {@link DitheringArgument}
   * @param mrl                the image to show: a path to a file on the server or an {@code http} or
   *                           {@code https} URL; the rest of the command line, so it may contain spaces
   */
  @Command("mcav image map <playerSelector> <imageResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <mrl>")
  @Permission("mcav.command.image.map")
  @CommandDescription("mcav.command.image.map.info")
  public void showMapImage(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "resolutions") @Quoted final String imageResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Greedy final String mrl
  ) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(imageResolution, "Image resolution must not be null");
    Preconditions.checkNotNull(blockDimensions, "Block dimensions must not be null");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null");
    Preconditions.checkNotNull(mrl, "MRL must not be null");
    final Pair<Integer, Integer> blocks = parseBlocks(sender, blockDimensions);
    if (blocks == null) {
      return;
    }

    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final ImageConfigurationProvider configProvider = createProvider(blocks, mapId, players, ditheringAlgorithm);
    this.displayImage(configProvider, sender, imageResolution, mrl);
  }

  private static ImageConfigurationProvider createProvider(
    final Pair<Integer, Integer> blocks,
    final int mapId,
    final Collection<UUID> players,
    final DitheringArgument dithering
  ) {
    return resolution -> createSettings(blocks, resolution, mapId, players, dithering);
  }

  /**
   * Parses the size of the wall and tells the sender when it is invalid.
   *
   * @return the size in maps, or {@code null} if the sender was told that it is invalid
   */
  private static @Nullable Pair<Integer, Integer> parseBlocks(final CommandSender sender, final String blockDimensions) {
    try {
      return ArgumentUtils.parseScreenDimensions(blockDimensions);
    } catch (final IllegalArgumentException exception) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return null;
    }
  }

  private static MapDisplaySettings createSettings(
    final Pair<Integer, Integer> blocks,
    final Pair<Integer, Integer> resolution,
    final int mapId,
    final Collection<UUID> players,
    final DitheringArgument dithering
  ) {
    final MapConfiguration configuration = MapDisplaySettings.createConfiguration(blocks, resolution, mapId, players);
    return new MapDisplaySettings(configuration, dithering);
  }

  /**
   * Creates a map display from the {@link MapDisplaySettings} built by the provider of {@link #showMapImage}, with
   * a dithering algorithm created for this image alone.
   *
   * @param resolution     the parsed resolution, in pixels
   * @param configProvider the provider that builds the map display settings
   * @return the map display
   */
  @Override
  public DisplayableImage createImage(final Pair<Integer, Integer> resolution, final ImageConfigurationProvider configProvider) {
    Preconditions.checkNotNull(resolution, "Resolution must not be null");
    Preconditions.checkNotNull(configProvider, "Configuration provider must not be null");
    final MapDisplaySettings settings = (MapDisplaySettings) configProvider.buildConfiguration(resolution);
    final MapConfiguration configuration = settings.getConfiguration();
    final DitherAlgorithm algorithm = settings.createAlgorithm();
    return DisplayableImage.map(configuration, algorithm);
  }
}
