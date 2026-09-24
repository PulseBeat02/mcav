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
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.MapDisplaySettings;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav video map}: dithers a video onto a wall of maps built with {@code /mcav screen}.
 */
public final class VideoMapCommand extends AbstractVideoCommand {

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public VideoMapCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Handles {@code /mcav video map <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions>
   * <mapId> <ditheringAlgorithm> <flags> <mrl>}: dithers a video onto a wall of maps, the highest quality way to
   * show a video in the world.
   *
   * <p>Build the wall first with {@code /mcav screen}, using the same block dimensions and map id. Each map holds
   * 128x128 pixels, and the maps are numbered row by row from the top left one. Frames are scaled to the
   * resolution, then centered on the wall; parts that do not fit are cropped equally on every side. Only one video
   * plays at a time; a new video command replaces the current video, whatever its display.
   *
   * <p>Requires the permission {@code mcav.command.video.map}; players and the console can run it. The arguments,
   * the player, and the audio output are checked first, and any problem is reported with an error message. The
   * viewers are then told that the video is loading, the media is resolved in the background, and the sender is
   * told once it plays; viewers get a link to the Discord channel or the audio web page when those outputs are
   * chosen.
   *
   * @param sender             who ran the command
   * @param playerSelector     the players who see the video on the maps, such as a player name or {@code @a}
   * @param playerType         the video backend that decodes the media, see {@link PlayerArgument}
   * @param audioType          where the sound is played, see {@link AudioArgument}
   * @param videoResolution    the resolution frames are scaled to as {@code <width>x<height>} in pixels; use 128
   *                           times the block dimensions, such as {@code 640x640} for a 5x5 wall, to fill the wall
   *                           exactly
   * @param blockDimensions    the size of the wall as {@code <width>x<height>} in maps, such as {@code 5x5}
   * @param mapId              the id of the top left map of the wall, as given to {@code /mcav screen}
   * @param ditheringAlgorithm how colors are reduced to the map palette; {@code FILTER_LITE} is recommended, and
   *                           {@code FLOYD_STEINBERG_TEMPORAL} flickers least, see {@link DitheringArgument}
   * @param flags              extra options, in quotes; {@code ""} for none, or yt-dlp options such as
   *                           {@code "--yt-dlp{format=best,no-playlist}"}
   * @param mrl                the media, in quotes if it contains spaces: a file path on the server, a direct media
   *                           URL, a website yt-dlp understands such as YouTube, a capture device number such as
   *                           {@code 0}, or a raw FFmpeg input written as {@code format||input}
   */
  @Command(
    "mcav video map <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <flags> <mrl>"
  )
  @Permission("mcav.command.video.map")
  @CommandDescription("mcav.command.video.map.info")
  public void playMapVideo(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    @Argument(suggestions = "resolutions") @Quoted final String videoResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Quoted final String flags,
    @Quoted final String mrl
  ) {
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null");

    final Pair<Integer, Integer> blocks = parseDimensions(sender, blockDimensions);
    if (blocks == null) {
      return;
    }

    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final VideoConfigurationProvider configurationProvider = resolution ->
      createSettings(blocks, resolution, mapId, players, ditheringAlgorithm);
    this.playVideo(configurationProvider, sender, playerSelector, playerType, audioType, videoResolution, mrl, flags);
  }

  private static MapDisplaySettings createSettings(
    final Pair<Integer, Integer> blocks,
    final Pair<Integer, Integer> resolution,
    final int mapId,
    final Collection<UUID> players,
    final DitheringArgument ditheringAlgorithm
  ) {
    final MapConfiguration configuration = MapDisplaySettings.createConfiguration(blocks, resolution, mapId, players);
    return new MapDisplaySettings(configuration, ditheringAlgorithm);
  }

  /**
   * Creates the maps that show the frames and the filter that dithers the frames onto them, and starts the filter
   * on the main thread. Every call creates its own dithering algorithm, so a stateful algorithm never mixes up the
   * frames of two videos. Called on the worker thread.
   *
   * @param resolution            the resolution frames are scaled to
   * @param configurationProvider the provider created by
   *                              {@link #playMapVideo(CommandSender, MultiplePlayerSelector, PlayerArgument, AudioArgument, String, String, int, DitheringArgument, String, String)},
   *                              which returns {@link MapDisplaySettings}
   * @return the pipeline that dithers the frames onto the maps
   */
  @Override
  public VideoPipelineStep createVideoFilter(
    final Pair<Integer, Integer> resolution,
    final VideoConfigurationProvider configurationProvider
  ) {
    Preconditions.checkNotNull(resolution, "Resolution must not be null");
    Preconditions.checkNotNull(configurationProvider, "Configuration provider must not be null");

    final MapDisplaySettings settings = (MapDisplaySettings) configurationProvider.buildConfiguration(resolution);
    final MapConfiguration configuration = settings.getConfiguration();
    final DitherAlgorithm algorithm = settings.createAlgorithm();
    final CompressedMapResult result = new CompressedMapResult(configuration);
    final FunctionalVideoFilter ditherFilter = DitherFilter.dither(algorithm, result);

    this.manager.startFilter(ditherFilter);
    return VideoPipelineStep.of(ditherFilter);
  }
}
