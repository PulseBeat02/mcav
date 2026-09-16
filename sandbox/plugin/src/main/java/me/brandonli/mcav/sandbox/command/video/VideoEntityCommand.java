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
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.media.result.EntityResult;
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
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav video entity}: plays a video to the selected players.
 */
public final class VideoEntityCommand extends AbstractVideoCommand {

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public VideoEntityCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Handles {@code /mcav video entity <playerSelector> <playerType> <audioType> <videoResolution> <character>
   * <location> <flags> <mrl>}: plays a video as colored text inside a floating text display entity.
   *
   * <p>Every frame is drawn with the chosen character in the color of each pixel, one line of text per row of
   * pixels. The entity always turns to face the viewer horizontally and is only visible to the selected players who
   * were online when it was spawned. Only one video plays at a time; a new video command replaces the current video,
   * whatever its display.
   *
   * <p>Requires the permission {@code mcav.command.video.entity}; players and the console can run it. The arguments,
   * the player, and the audio output are checked first, and any problem is reported with an error message. The
   * viewers are then told that the video is loading, the media is resolved in the background, and the sender is
   * told once it plays; viewers get a link to the Discord channel or the audio web page when those outputs are
   * chosen.
   *
   * @param sender          who ran the command
   * @param playerSelector  the players who see the entity, such as a player name or {@code @a}
   * @param playerType      the video backend that decodes the media, see {@link PlayerArgument}
   * @param audioType       where the sound is played, see {@link AudioArgument}
   * @param videoResolution the size as {@code <width>x<height>}, in characters per line and lines, such as
   *                        {@code 64x36}
   * @param character       the character every pixel is drawn with, in quotes; a full block {@code █} gives the
   *                        most solid picture
   * @param location        where the entity is spawned, such as {@code ~ ~2 ~}
   * @param flags           extra options, in quotes; {@code ""} for none, or yt-dlp options such as
   *                        {@code "--yt-dlp{format=best,no-playlist}"}
   * @param mrl             the media, in quotes if it contains spaces: a file path on the server, a direct media
   *                        URL, a website yt-dlp understands such as YouTube, a capture device number such as
   *                        {@code 0}, or a raw FFmpeg input written as {@code format||input}
   */
  @Command("mcav video entity <playerSelector> <playerType> <audioType> <videoResolution> <character> <location> <flags> <mrl>")
  @Permission("mcav.command.video.entity")
  @CommandDescription("mcav.command.video.entity.info")
  public void playVideo(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    @Argument(suggestions = "dimensions") @Quoted final String videoResolution,
    @Argument(suggestions = "chat-characters") @Quoted final String character,
    final Location location,
    @Quoted final String flags,
    @Quoted final String mrl
  ) {
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(character, "Character must not be null");
    Preconditions.checkNotNull(location, "Location must not be null");

    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final VideoConfigurationProvider configurationProvider = resolution -> createConfiguration(resolution, players, character, location);
    this.playVideo(configurationProvider, sender, playerSelector, playerType, audioType, videoResolution, mrl, flags);
  }

  /**
   * Creates the text display entity that shows the frames, and spawns it on the main thread. Called on the worker
   * thread.
   *
   * @param resolution            the size in characters per line and lines
   * @param configurationProvider the provider created by
   *                              {@link #playVideo(CommandSender, MultiplePlayerSelector, PlayerArgument, AudioArgument, String, String, Location, String, String)},
   *                              which returns an {@link EntityConfiguration}
   * @return the pipeline that shows the frames in the entity
   */
  @Override
  public VideoPipelineStep createVideoFilter(
    final Pair<Integer, Integer> resolution,
    final VideoConfigurationProvider configurationProvider
  ) {
    Preconditions.checkNotNull(resolution, "Resolution must not be null");
    Preconditions.checkNotNull(configurationProvider, "Configuration provider must not be null");

    final EntityConfiguration configuration = (EntityConfiguration) configurationProvider.buildConfiguration(resolution);
    final FunctionalVideoFilter result = new EntityResult(configuration);
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTask(this.plugin, result::start);
    this.manager.setFilter(result);
    return VideoPipelineStep.of(result);
  }

  private static EntityConfiguration createConfiguration(
    final Pair<Integer, Integer> resolution,
    final Collection<UUID> players,
    final String character,
    final Location location
  ) {
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    final EntityConfiguration.Builder<?> builder = EntityConfiguration.builder();
    builder.viewers(players);
    builder.entityWidth(width);
    builder.entityHeight(height);
    builder.character(character);
    builder.position(location);
    return builder.build();
  }
}
