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
package me.brandonli.mcav.plugin.command.video;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfigurationBuilder;
import me.brandonli.mcav.bukkit.media.result.ScoreboardResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.ScoreboardArguments;
import me.brandonli.mcav.plugin.command.SuggestionProvider;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.ArgumentUtils;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.FilterChain;
import me.brandonli.mcav.plugin.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Flag;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav video scoreboard}: plays a video to the selected players. The height of the resolution is the
 * number of lines, at most {@value ScoreboardConfiguration#MAXIMUM_LINES}.
 *
 * <p>This class is not thread-safe: use it on the server main thread, except {@code createVideoFilter}, which {@link
 * AbstractVideoCommand} calls on the worker thread.
 *
 * @since 1.0.0
 */
public final class VideoScoreboardCommand extends AbstractVideoCommand {

  /**
   * Creates the command.
   *
   * @param plugin the plugin
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  public VideoScoreboardCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Handles {@code /mcav video scoreboard <playerSelector> <playerType> <audioType> <videoResolution> <character>
   * <ytdlpOptions> <mrl>}: plays a video on the sidebar scoreboard of the selected players.
   *
   * <p>Every frame is drawn with the chosen character in the color of each pixel, one sidebar line per row of
   * pixels. The sidebar replaces any other sidebar the players see while the video plays. Only one video plays at a
   * time; a new video command replaces the current video, whatever its display.
   *
   * <p>Requires the permission {@code mcav.command.video.scoreboard}; players and the console can run it. A height
   * above {@value ScoreboardConfiguration#MAXIMUM_LINES} lines is rejected first. The other arguments, the player, and
   * the audio output are checked next, and any problem is reported with an error message. The viewers are then told
   * that the video is loading, the media is resolved in the background, and the sender is told once it plays;
   * viewers get a link to the Discord channel or the audio web page when those outputs are chosen.
   *
   * @param sender          who ran the command
   * @param playerSelector  the players whose sidebar shows the video, such as a player name or {@code @a}; only
   *                        those online when the video starts see it
   * @param playerType      the video backend that decodes the media, see {@link PlayerArgument}
   * @param audioType       where the sound is played, see {@link AudioArgument}
   * @param videoResolution the size as {@code <width>x<height>}, in characters per line and lines, such as
   *                        {@code 24x15}; the height is at most {@value ScoreboardConfiguration#MAXIMUM_LINES}
   * @param character       the character every pixel is drawn with, typed in double quotes; a full block
   *                        {@code "█"} gives the most solid picture
   * @param ytdlpOptions    extra options, in quotes; {@code ""} for none, or yt-dlp options such as
   *                        {@code "--yt-dlp{format=best,no-playlist}"}
   * @param mrl             the media, in quotes if it contains spaces: a file path on the server, a direct media
   *                        URL, a website yt-dlp understands such as YouTube, a capture device number such as
   *                        {@code 0}, or a raw FFmpeg input written as {@code format||input}
   * @param filters         the {@code --filters} option: filters applied to every frame in order, such as
   *                        {@code "grayscale,blur=3"}, see {@link FilterChain}; none when absent
   * @throws NullPointerException if an argument other than {@code filters} is null
   * @since 1.0.0
   */
  @Command("mcav video scoreboard <playerSelector> <playerType> <audioType> <videoResolution> <character> <ytdlpOptions> <mrl>")
  @Permission("mcav.command.video.scoreboard")
  @CommandDescription("mcav.command.video.scoreboard.info")
  public void playVideo(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    @Argument(suggestions = SuggestionProvider.SCOREBOARD_RESOLUTIONS) @Quoted final String videoResolution,
    @Argument(suggestions = SuggestionProvider.CHAT_CHARACTERS) @Quoted final String character,
    @Quoted final String ytdlpOptions,
    @Quoted final String mrl,
    @Flag("filters") @Quoted final @Nullable String filters
  ) {
    Preconditions.checkNotNull(sender, "Sender must not be null!");
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null!");
    Preconditions.checkNotNull(videoResolution, "Video resolution must not be null!");
    Preconditions.checkNotNull(character, "Character must not be null!");

    final boolean tooTall = ScoreboardArguments.isTooTall(videoResolution);
    if (tooTall) {
      final Component message = Message.SCOREBOARD_LINES.build();
      sender.sendMessage(message);
      return;
    }

    final Collection<UUID> players = ArgumentUtils.resolvePlayerIds(playerSelector);
    final VideoConfigurationProvider configurationProvider = resolution -> this.createConfiguration(resolution, players, character);
    this.playVideo(configurationProvider, sender, playerSelector, playerType, audioType, videoResolution, mrl, ytdlpOptions, filters);
  }

  /**
   * Creates the sidebar that shows the frames, and shows it on the main thread. Called on the worker thread.
   *
   * @param resolution            the size in characters per line and lines
   * @param configurationProvider the provider created by
   *                              {@link #playVideo(CommandSender, MultiplePlayerSelector, PlayerArgument, AudioArgument, String, String, String, String, String)},
   *                              which returns a {@link ScoreboardConfiguration}
   * @return the pipeline that shows the frames on the sidebar
   * @throws NullPointerException                       if an argument is null
   * @throws java.util.concurrent.CancellationException if the video startup was cancelled or the manager is shut down
   * @since 1.0.0
   */
  @Override
  public VideoPipelineStep createVideoFilter(
    final Pair<Integer, Integer> resolution,
    final VideoConfigurationProvider configurationProvider
  ) {
    Preconditions.checkNotNull(resolution, "Resolution must not be null!");
    Preconditions.checkNotNull(configurationProvider, "Configuration provider must not be null!");

    final ScoreboardConfiguration configuration = (ScoreboardConfiguration) configurationProvider.buildConfiguration(resolution);
    final FunctionalVideoFilter result = new ScoreboardResult(configuration);
    this.manager.startFilter(result);

    return VideoPipelineStep.of(result);
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
