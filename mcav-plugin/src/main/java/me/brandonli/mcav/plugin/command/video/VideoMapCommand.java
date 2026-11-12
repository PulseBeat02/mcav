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
import java.util.Optional;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.MapDisplaySettings;
import me.brandonli.mcav.plugin.command.SuggestionProvider;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.utils.ArgumentUtils;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.FilterChain;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.plugin.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Flag;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav video map}: shows a video on a wall of maps built with {@code /mcav screen}, dithered or with MCV2.
 *
 * <p>This class is not thread-safe: use it on the server main thread, except {@code createVideoFilter}, which {@link
 * AbstractVideoCommand} calls on the worker thread.
 *
 * @since 1.0.0
 */
public final class VideoMapCommand extends AbstractVideoCommand {

  /**
   * Creates the command.
   *
   * @param plugin the plugin
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  public VideoMapCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Handles {@code /mcav video map <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions>
   * <mapId> <ditheringAlgorithm> <ytdlpOptions> <mrl> [--codec dither|mcv2]}: shows a video on a wall of maps, the
   * highest quality way to show a video in the world. With {@code --codec mcv2}, or without the flag when
   * {@code mcv2.default-codec} is {@code mcv2}, the video is encoded as it plays with the default live preset for the
   * players whose client loads the MCV2 resource pack, and dithered for everyone else, like {@code /mcav video mcv2}
   * with the {@code DEFAULT} preset.
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
   * @param ytdlpOptions       extra options, in quotes; {@code ""} for none, or yt-dlp options such as
   *                           {@code "--yt-dlp{format=best,no-playlist}"}
   * @param mrl                the media, in quotes if it contains spaces: a file path on the server, a direct media
   *                           URL, a website yt-dlp understands such as YouTube, a capture device number such as
   *                           {@code 0}, or a raw FFmpeg input written as {@code format||input}
   * @param codec              how the picture reaches the players, see {@link MapCodec}; the configured default when
   *                           absent
   * @param filters            the {@code --filters} option: filters applied to every frame in order, such as
   *                           {@code "grayscale,blur=3"}, see {@link FilterChain}; none when absent
   * @throws NullPointerException if {@code playerSelector} or {@code ditheringAlgorithm} is null
   * @since 1.0.0
   */
  @Command(
    "mcav video map <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <ytdlpOptions> <mrl>"
  )
  @Permission("mcav.command.video.map")
  @CommandDescription("mcav.command.video.map.info")
  public void playMapVideo(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    @Argument(suggestions = SuggestionProvider.RESOLUTIONS) @Quoted final String videoResolution,
    @Argument(suggestions = SuggestionProvider.DIMENSIONS) @Quoted final String blockDimensions,
    @Argument(suggestions = SuggestionProvider.IDS) @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Quoted final String ytdlpOptions,
    @Quoted final String mrl,
    @Flag("codec") final @Nullable MapCodec codec,
    @Flag("filters") @Quoted final @Nullable String filters
  ) {
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null!");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null!");

    final Optional<Pair<Integer, Integer>> parsedBlocks = parseScreenDimensions(sender, blockDimensions);
    final @Nullable Pair<Integer, Integer> blocks = parsedBlocks.orElse(null);
    if (blocks == null) {
      return;
    }

    final OnlinePlayers onlinePlayers = this.plugin.getOnlinePlayers();
    final Collection<UUID> players = ArgumentUtils.resolveViewers(playerSelector, onlinePlayers);
    final PluginDataConfigurationMapper pluginConfiguration = this.plugin.getConfiguration();
    final MapCodec defaultCodec = pluginConfiguration.getMcv2DefaultCodec();
    final MapCodec chosenCodec = codec != null ? codec : defaultCodec;
    VideoConfigurationProvider configurationProvider = resolution ->
      this.createSettings(blocks, resolution, mapId, players, ditheringAlgorithm);
    if (chosenCodec == MapCodec.MCV2) {
      final Optional<Pair<Integer, Integer>> parsedResolution = parseDimensions(sender, videoResolution);
      final @Nullable Pair<Integer, Integer> resolution = parsedResolution.orElse(null);
      if (resolution == null) {
        return;
      }

      final Mcv2Support support = this.plugin.getMcv2Support();
      final Optional<Mcv2Configuration> foundConfiguration = support.configure(
        sender,
        blocks,
        resolution,
        mapId,
        Settings.DEFAULT,
        players
      );
      final @Nullable Mcv2Configuration configuration = foundConfiguration.orElse(null);
      if (configuration != null) {
        configurationProvider = _ -> new VideoMcv2Command.Mcv2Settings(configuration, ditheringAlgorithm, sender);
      }
    }

    this.playVideo(configurationProvider, sender, playerSelector, playerType, audioType, videoResolution, mrl, ytdlpOptions, filters);
  }

  private MapDisplaySettings createSettings(
    final Pair<Integer, Integer> blocks,
    final Pair<Integer, Integer> resolution,
    final int mapId,
    final Collection<UUID> players,
    final DitheringArgument ditheringAlgorithm
  ) {
    final MapConfiguration configuration = MapDisplaySettings.createConfiguration(blocks, resolution, mapId, players);
    return MapDisplaySettings.of(configuration, ditheringAlgorithm);
  }

  /**
   * Creates the maps that show the frames and the filter that dithers the frames onto them, or the MCV2 output of the
   * wall, and starts the filter on the main thread. Every call creates its own dithering algorithm, so a stateful
   * algorithm never mixes up the frames of two videos. Called on the worker thread.
   *
   * @param resolution            the resolution frames are scaled to
   * @param configurationProvider the provider created by
   *                              {@link #playMapVideo(CommandSender, MultiplePlayerSelector, PlayerArgument, AudioArgument, String, String, int, DitheringArgument, String, String, MapCodec, String)},
   *                              which returns {@link MapDisplaySettings}, or {@code Mcv2Settings} for MCV2
   * @return the pipeline step that dithers the frames onto the maps, or that encodes them with MCV2
   * @throws NullPointerException                       if an argument is null
   * @throws ClassCastException                         if the provider builds neither {@link MapDisplaySettings} nor {@code Mcv2Settings}
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

    final Object built = configurationProvider.buildConfiguration(resolution);
    if (built instanceof final VideoMcv2Command.Mcv2Settings mcv2Settings) {
      final Mcv2Support support = this.plugin.getMcv2Support();
      final FunctionalVideoFilter output = support.createMcv2Filter(mcv2Settings);
      this.manager.startFilter(output);

      return VideoPipelineStep.of(output);
    }

    final MapDisplaySettings settings = (MapDisplaySettings) built;
    final MapConfiguration configuration = settings.getConfiguration();
    final DitherAlgorithm algorithm = settings.createAlgorithm();
    final CompressedMapResult result = new CompressedMapResult(configuration);
    final FunctionalVideoFilter ditherFilter = DitherFilter.dither(algorithm, result);
    this.manager.startFilter(ditherFilter);

    return VideoPipelineStep.of(ditherFilter);
  }
}
