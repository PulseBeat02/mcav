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

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

public final class ImageMapCommand extends AbstractImageCommand {

  private volatile DitheringArgument argument;

  @Command("mcav image map <playerSelector> <imageResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <mrl>")
  @Permission("mcav.command.image.map")
  @CommandDescription("mcav.command.image.map.info")
  public void showMapImage(
    final CommandSender player,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "resolutions") @Quoted final String imageResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Greedy final String mrl
  ) {
    final Pair<Integer, Integer> dimensions;
    try {
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException e) {
      player.sendMessage(Message.UNSUPPORTED_DIMENSION.build());
      return;
    }
    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final ImageConfigurationProvider configProvider = resolution -> this.constructMapConfiguration(resolution, dimensions, mapId, players);
    this.argument = ditheringAlgorithm;
    this.displayImage(configProvider, player, imageResolution, mrl);
  }

  @Override
  public DisplayableImage createImage(final Pair<Integer, Integer> resolution, final ImageConfigurationProvider configProvider) {
    final MapConfiguration configuration = (MapConfiguration) configProvider.buildConfiguration(resolution);
    final DitherAlgorithm ditherAlgorithm = this.argument.getAlgorithm();
    return DisplayableImage.map(configuration, ditherAlgorithm);
  }

  private MapConfiguration constructMapConfiguration(
    final Pair<@NonNull Integer, @NonNull Integer> dimension,
    final Pair<@NonNull Integer, @NonNull Integer> resolution,
    final int mapId,
    final Collection<UUID> players
  ) {
    return MapConfiguration.builder()
      .viewers(players)
      .mapBlockWidth(dimension.getFirst())
      .mapBlockHeight(dimension.getSecond())
      .mapBlockWidth(resolution.getFirst())
      .mapBlockHeight(resolution.getSecond())
      .map(mapId)
      .build();
  }
}
