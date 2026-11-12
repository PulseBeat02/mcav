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

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfigurationBuilder;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.ScoreboardArguments;
import me.brandonli.mcav.plugin.command.SuggestionProvider;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.ArgumentUtils;
import me.brandonli.mcav.plugin.utils.FilterChain;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.FlagYielding;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Flag;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav image scoreboard}: shows an image to the selected players. The height of the resolution is the
 * number of lines, at most {@value ScoreboardConfiguration#MAXIMUM_LINES}.
 *
 * <p>This class is not thread-safe: use it on the server main thread.
 *
 * @since 1.0.0
 */
public final class ImageScoreboardCommand extends AbstractImageCommand {

  /**
   * Creates the command.
   *
   * @param plugin the plugin
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
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
   * above {@value ScoreboardConfiguration#MAXIMUM_LINES} lines is rejected with an error message before anything loads.
   * Otherwise the sender is told "Loading image..." while the image downloads or loads from disk in the background,
   * and "Image loaded!" once it is shown. An invalid resolution, a source that cannot be found or loaded, or an
   * animated GIF is reported with an error message instead.
   *
   * @param sender          who ran the command
   * @param playerSelector  the players whose sidebar shows the image, such as a player name or {@code @a}; only
   *                        those online when the image appears see it
   * @param imageResolution the size as {@code <width>x<height>}, in characters per line and lines, such as
   *                        {@code 24x15}; the height is at most {@value ScoreboardConfiguration#MAXIMUM_LINES}
   * @param character       the character every pixel is drawn with, typed in double quotes; a full block
   *                        {@code "█"} gives the most solid picture
   * @param mrl             the image to show: a path to a file on the server or an {@code http} or {@code https}
   *                        URL; the rest of the command line, so it may contain spaces
   * @param filters         the {@code --filters} option: filters applied to the image in order, such as
   *                        {@code "grayscale,blur=3"}, see {@link FilterChain}; none when absent
   * @throws NullPointerException if an argument other than {@code filters} is null
   * @since 1.0.0
   */
  @Command("mcav image scoreboard <playerSelector> <imageResolution> <character> <mrl>")
  @Permission("mcav.command.image.scoreboard")
  @CommandDescription("mcav.command.image.scoreboard.info")
  public void showImage(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = SuggestionProvider.SCOREBOARD_RESOLUTIONS) @Quoted final String imageResolution,
    @Argument(suggestions = SuggestionProvider.CHAT_CHARACTERS) @Quoted final String character,
    @Greedy @FlagYielding final String mrl,
    @Flag("filters") @Quoted final @Nullable String filters
  ) {
    Preconditions.checkNotNull(sender, "Sender must not be null!");
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null!");
    Preconditions.checkNotNull(imageResolution, "Image resolution must not be null!");
    Preconditions.checkNotNull(character, "Character must not be null!");
    Preconditions.checkNotNull(mrl, "MRL must not be null!");

    final boolean tooTall = ScoreboardArguments.isTooTall(imageResolution);
    if (tooTall) {
      final Component message = Message.SCOREBOARD_LINES.build();
      sender.sendMessage(message);
      return;
    }

    final Collection<UUID> players = ArgumentUtils.resolvePlayerIds(playerSelector);
    final ImageConfigurationProvider configurationProvider = resolution -> this.createConfiguration(resolution, players, character);
    this.displayImage(configurationProvider, sender, imageResolution, mrl, filters);
  }

  /**
   * Creates a sidebar display from the {@link ScoreboardConfiguration} built by the provider that the command passed
   * to {@link #displayImage}.
   *
   * @param resolution            the parsed resolution, in characters per line and lines
   * @param configurationProvider the provider that builds the scoreboard configuration
   * @return the scoreboard display
   * @throws NullPointerException if an argument is null
   * @throws ClassCastException   if the provider builds no {@link ScoreboardConfiguration}
   * @since 1.0.0
   */
  @Override
  public DisplayableImage createImage(final Pair<Integer, Integer> resolution, final ImageConfigurationProvider configurationProvider) {
    Preconditions.checkNotNull(resolution, "Resolution must not be null!");
    Preconditions.checkNotNull(configurationProvider, "Configuration provider must not be null!");

    final ScoreboardConfiguration configuration = (ScoreboardConfiguration) configurationProvider.buildConfiguration(resolution);
    return DisplayableImage.scoreboard(configuration);
  }

  private ScoreboardConfiguration createConfiguration(
    final Pair<Integer, Integer> resolution,
    final Collection<UUID> players,
    final String character
  ) {
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    final ScoreboardConfigurationBuilder builder = ScoreboardConfiguration.builder();
    builder.viewers(players);
    builder.width(width);
    builder.lines(height);
    builder.character(character);
    return builder.build();
  }
}
