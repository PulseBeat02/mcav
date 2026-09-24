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
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav image block}: shows an image to the selected players.
 */
public final class ImageBlockCommand extends AbstractImageCommand {

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public ImageBlockCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Handles {@code /mcav image block <playerSelector> <imageResolution> <location> <mrl>}: shows an image as an
   * upright wall of colored blocks, one block per pixel.
   *
   * <p>The wall stands along the x axis, is centered horizontally on the location, and grows upward from it. The
   * blocks are only sent to the selected players as fake block changes, so the world is never modified and the
   * original blocks come back when the image is released with {@code /mcav image release}. Only one image is shown
   * at a time; a new image command replaces the current image, whatever its type.
   *
   * <p>Requires the permission {@code mcav.command.image.block}; players and the console can run it. The sender is
   * told "Loading image..." while the image downloads or loads from disk in the background, and "Image loaded!"
   * once it is shown. An invalid resolution, a source that cannot be found or loaded, or an animated GIF is
   * reported with an error message instead.
   *
   * @param sender          who ran the command
   * @param playerSelector  the players who see the wall, such as a player name or {@code @a}; players who join
   *                        later do not see it
   * @param imageResolution the size of the wall as {@code <width>x<height>} in blocks, such as {@code 32x18}; every
   *                        block is one pixel, so large sizes send many block changes
   * @param location        the bottom center of the wall, such as {@code ~ ~ ~}
   * @param mrl             the image to show: a path to a file on the server or an {@code http} or {@code https}
   *                        URL; the rest of the command line, so it may contain spaces
   */
  @Command("mcav image block <playerSelector> <imageResolution> <location> <mrl>")
  @Permission("mcav.command.image.block")
  @CommandDescription("mcav.command.image.block.info")
  public void showImage(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "dimensions") @Quoted final String imageResolution,
    final Location location,
    @Quoted final String mrl
  ) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(imageResolution, "Image resolution must not be null");
    Preconditions.checkNotNull(location, "Location must not be null");
    Preconditions.checkNotNull(mrl, "MRL must not be null");
    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final ImageConfigurationProvider configProvider = resolution -> createConfiguration(resolution, players, location);
    this.displayImage(configProvider, sender, imageResolution, mrl);
  }

  /**
   * Creates a wall of blocks from the {@link BlockConfiguration} built by the provider that the command passed to
   * {@link #displayImage}.
   *
   * @param resolution     the parsed resolution, in blocks
   * @param configProvider the provider that builds the block configuration
   * @return the block display
   */
  @Override
  public DisplayableImage createImage(final Pair<Integer, Integer> resolution, final ImageConfigurationProvider configProvider) {
    Preconditions.checkNotNull(resolution, "Resolution must not be null");
    Preconditions.checkNotNull(configProvider, "Configuration provider must not be null");
    final BlockConfiguration configuration = (BlockConfiguration) configProvider.buildConfiguration(resolution);
    return DisplayableImage.block(configuration);
  }

  private static BlockConfiguration createConfiguration(
    final Pair<Integer, Integer> resolution,
    final Collection<UUID> players,
    final Location location
  ) {
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    final BlockConfiguration.Builder<?> builder = BlockConfiguration.builder();
    builder.viewers(players);
    builder.blockWidth(width);
    builder.blockHeight(height);
    builder.position(location);
    return builder.build();
  }
}
