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
package me.brandonli.mcav.sandbox.command;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.MapUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;

/**
 * {@code /mcav screen}: builds a wall of item frames holding consecutive maps to display media on.
 */
public final class ScreenCommand implements AnnotationCommandFeature {

  /**
   * Constructs the command.
   */
  public ScreenCommand() {
    // stateless
  }

  /**
   * Handles {@code /mcav screen <blockDimensions> <mapId> <material> <location>}: builds a wall of maps that the
   * map commands ({@code /mcav video map}, {@code /mcav image map}, {@code /mcav browser create}, and
   * {@code /mcav vm create}) draw on.
   *
   * <p>The wall is made of blocks of the chosen material with an item frame on every block. The frames hold the maps
   * from the map id on, counted row by row from the top left corner as seen by the sender. Players build the wall
   * facing the direction they look; the console builds it facing north. Use the same block dimensions and map id in
   * the command that shows media on it.
   *
   * <p>Requires the permission {@code mcav.command.screen}; players and the console can run it. The sender is told
   * when the wall is built, or gets an error message if the dimensions are invalid.
   *
   * @param sender          who builds the screen
   * @param blockDimensions the size of the wall as {@code <width>x<height>} in blocks, such as {@code 5x5}; each block
   *                        holds one 128x128 pixel map
   * @param mapId           the id of the map in the top left corner; the wall uses this id and the following
   *                        width times height minus one ids
   * @param material        the block the wall is made of, such as {@code black_concrete}
   * @param location        where the wall is built, such as {@code ~ ~ ~}
   */
  @Command("mcav screen <blockDimensions> <mapId> <material> <location>")
  @Permission("mcav.command.screen")
  @CommandDescription("mcav.command.screen.info")
  public void buildScreen(
    final CommandSender sender,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final Material material,
    final Location location
  ) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(blockDimensions, "Block dimensions must not be null");
    Preconditions.checkNotNull(material, "Material must not be null");
    Preconditions.checkNotNull(location, "Location must not be null");
    final Pair<Integer, Integer> dimensions;
    try {
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException exception) {
      final Component error = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(error);
      return;
    }

    final int width = dimensions.getFirst();
    final int height = dimensions.getSecond();
    MapUtils.buildMapScreen(sender, location, material, width, height, mapId);
    final Component built = Message.SCREEN_BUILD.build();
    sender.sendMessage(built);
  }
}
