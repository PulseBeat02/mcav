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
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.ScoreboardArguments;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav image scoreboard}: shows an image to the selected players. The height of the resolution is the
 * number of lines, at most {@value ScoreboardConfiguration#MAX_LINES}.
 */
public final class ImageScoreboardCommand extends AbstractImageCommand {

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public ImageScoreboardCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Handles {@code /mcav image scoreboard <playerSelector> <imageResolution> <character> <mrl>}: draws an image onto
   * the sidebar scoreboard of the selected players.
   *
   * <p>Every pixel is drawn as the chosen character in the color of the pixel, and every row of pixels becomes one
   * sidebar line. The sidebar replaces any other sidebar the players see while the image is shown. Only one image
   * is shown at a time; a new image command replaces the current image, whatever its type.
   *
   * <p>Requires the permission {@code mcav.command.image.scoreboard}; players and the console can run it. A height
   * above {@value ScoreboardConfiguration#MAX_LINES} lines is rejected with an error message before anything loads.
   * Otherwise the sender is told "Loading image..." while the image downloads or loads from disk in the background,
   * and "Image loaded!" once it is shown. An invalid resolution, a source that cannot be found or loaded, or an
   * animated GIF is reported with an error message instead.
   *
   * @param sender          who ran the command
   * @param playerSelector  the players whose sidebar shows the image, such as a player name or {@code @a}; only
   *                        those online when the image appears see it
   * @param imageResolution the size as {@code <width>x<height>}, in characters per line and lines, such as
   *                        {@code 24x15}; the height is at most {@value ScoreboardConfiguration#MAX_LINES}
   * @param character       the character every pixel is drawn with, in quotes if it is not a single word; a full
   *                        block {@code █} gives the most solid picture
   * @param mrl             the image to show: a path to a file on the server or an {@code http} or {@code https}
   *                        URL; the rest of the command line, so it may contain spaces
   */
  @Command("mcav image scoreboard <playerSelector> <imageResolution> <character> <mrl>")
  @Permission("mcav.command.image.scoreboard")
  @CommandDescription("mcav.command.image.scoreboard.info")
  public void showImage(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "dimensions") @Quoted final String imageResolution,
    @Argument(suggestions = "chat-characters") @Quoted final String character,
    @Greedy final String mrl
  ) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(imageResolution, "Image resolution must not be null");
    Preconditions.checkNotNull(character, "Character must not be null");
    Preconditions.checkNotNull(mrl, "MRL must not be null");
    final boolean fits = ScoreboardArguments.checkLines(sender, imageResolution);
    if (!fits) {
      return;
    }

    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final ImageConfigurationProvider configProvider = resolution -> createConfiguration(resolution, players, character);
    this.displayImage(configProvider, sender, imageResolution, mrl);
  }

  /**
   * Creates a sidebar display from the {@link ScoreboardConfiguration} built by the provider that the command passed
   * to {@link #displayImage}.
   *
   * @param resolution     the parsed resolution, in characters per line and lines
   * @param configProvider the provider that builds the scoreboard configuration
   * @return the scoreboard display
   */
  @Override
  public DisplayableImage createImage(final Pair<Integer, Integer> resolution, final ImageConfigurationProvider configProvider) {
    Preconditions.checkNotNull(resolution, "Resolution must not be null");
    Preconditions.checkNotNull(configProvider, "Configuration provider must not be null");
    final ScoreboardConfiguration configuration = (ScoreboardConfiguration) configProvider.buildConfiguration(resolution);
    return DisplayableImage.scoreboard(configuration);
  }

  private static ScoreboardConfiguration createConfiguration(
    final Pair<Integer, Integer> resolution,
    final Collection<UUID> players,
    final String character
  ) {
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    final ScoreboardConfiguration.Builder<?> builder = ScoreboardConfiguration.builder();
    builder.viewers(players);
    builder.width(width);
    builder.lines(height);
    builder.character(character);
    return builder.build();
  }
}
