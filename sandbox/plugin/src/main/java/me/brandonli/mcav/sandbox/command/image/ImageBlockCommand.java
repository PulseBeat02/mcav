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
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

public final class ImageBlockCommand extends AbstractImageCommand {

  @Command("mcav image block <playerSelector> <imageResolution> <location> <mrl>")
  @Permission("mcav.command.image.block")
  @CommandDescription("mcav.command.image.block.info")
  public void showBlockImage(
    final CommandSender player,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "dimensions") @Quoted final String imageResolution,
    final Location location,
    @Greedy final String mrl
  ) {
    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final ImageConfigurationProvider configProvider = resolution -> this.constructBlockConfiguration(resolution, location, players);
    this.displayImage(configProvider, player, imageResolution, mrl);
  }

  @Override
  public DisplayableImage createImage(final Pair<Integer, Integer> resolution, final ImageConfigurationProvider configProvider) {
    final BlockConfiguration configuration = (BlockConfiguration) configProvider.buildConfiguration(resolution);
    return DisplayableImage.block(configuration);
  }

  private BlockConfiguration constructBlockConfiguration(
    final Pair<@NonNull Integer, @NonNull Integer> resolution,
    final Location location,
    final Collection<UUID> players
  ) {
    return BlockConfiguration.builder()
      .viewers(players)
      .blockWidth(resolution.getFirst())
      .blockHeight(resolution.getSecond())
      .position(location)
      .build();
  }
}
