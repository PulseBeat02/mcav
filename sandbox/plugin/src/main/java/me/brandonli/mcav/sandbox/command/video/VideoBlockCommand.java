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
package me.brandonli.mcav.sandbox.command.video;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.result.BlockResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Default;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav video block}: plays a video to the selected players.
 */
public final class VideoBlockCommand extends AbstractVideoCommand {

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public VideoBlockCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Handles {@code /mcav video block <playerSelector> <playerType> <audioType> <videoResolution> <location> <flags>
   * <mrl>}: plays a video as an upright wall of colored blocks, one block per pixel.
   *
   * <p>The wall stands along the x axis, is centered horizontally on the location, and grows upward from it. The
   * blocks are only sent to the selected players as fake block changes, so the world is never modified and the
   * original blocks come back when the video is released. Every frame changes many blocks, so keep the wall small.
   * Only one video plays at a time; a new video command replaces the current video, whatever its display.
   *
   * <p>Requires the permission {@code mcav.command.video.block}; players and the console can run it. The arguments,
   * the player, and the audio output are checked first, and any problem is reported with an error message. The
   * viewers are then told that the video is loading, the media is resolved in the background, and the sender is
   * told once it plays; viewers get a link to the Discord channel or the audio web page when those outputs are
   * chosen.
   *
   * @param sender          who ran the command
   * @param playerSelector  the players who see the wall, such as a player name or {@code @a}
   * @param playerType      the video backend that decodes the media, see {@link PlayerArgument}
   * @param audioType       where the sound is played, see {@link AudioArgument}
   * @param videoResolution the size of the wall as {@code <width>x<height>} in blocks, such as {@code 32x18}
   * @param location        the bottom center of the wall, such as {@code ~ ~ ~}
   * @param flags           extra options, in quotes; {@code ""} for none, or yt-dlp options such as
   *                        {@code "--yt-dlp{format=best,no-playlist}"}
   * @param mrl             the media, in quotes if it contains spaces: a file path on the server, a direct media
   *                        URL, a website yt-dlp understands such as YouTube, a capture device number such as
   *                        {@code 0}, or a raw FFmpeg input written as {@code format||input}
   */
  @Command("mcav video block <playerSelector> <playerType> <audioType> <videoResolution> <location> <flags> <mrl>")
  @Permission("mcav.command.video.block")
  @CommandDescription("mcav.command.video.block.info")
  public void playVideo(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    @Argument(suggestions = "dimensions") @Quoted final String videoResolution,
    final Location location,
    @Default @Quoted final String flags,
    @Quoted final String mrl
  ) {
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(location, "Location must not be null");

    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final VideoConfigurationProvider configurationProvider = resolution -> createConfiguration(resolution, players, location);
    this.playVideo(configurationProvider, sender, playerSelector, playerType, audioType, videoResolution, mrl, flags);
  }

  /**
   * Creates the block wall that shows the frames, and starts it on the main thread, since it changes blocks the
   * viewers see. Called on the worker thread.
   *
   * @param resolution            the size of the wall in blocks
   * @param configurationProvider the provider created by
   *                              {@link #playVideo(CommandSender, MultiplePlayerSelector, PlayerArgument, AudioArgument, String, Location, String, String)},
   *                              which returns a {@link BlockConfiguration}
   * @return the pipeline that shows the frames on the wall
   */
  @Override
  public VideoPipelineStep createVideoFilter(
    final Pair<Integer, Integer> resolution,
    final VideoConfigurationProvider configurationProvider
  ) {
    Preconditions.checkNotNull(resolution, "Resolution must not be null");
    Preconditions.checkNotNull(configurationProvider, "Configuration provider must not be null");

    final BlockConfiguration configuration = (BlockConfiguration) configurationProvider.buildConfiguration(resolution);
    final FunctionalVideoFilter result = new BlockResult(configuration);
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTask(this.plugin, result::start);
    this.manager.setFilter(result);
    return VideoPipelineStep.of(result);
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
