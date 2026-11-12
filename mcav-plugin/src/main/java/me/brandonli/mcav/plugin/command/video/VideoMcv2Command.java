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
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.SuggestionProvider;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.utils.ArgumentUtils;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.FilterChain;
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
 * {@code /mcav video mcv2}: plays a video on a wall of maps built with {@code /mcav screen}, encoded with MCV2 for the
 * players whose client loads the MCV2 resource pack, and dithered onto the maps for everyone else.
 *
 * <p>This class is not thread-safe: use it on the server main thread, except {@code createVideoFilter}, which {@link
 * AbstractVideoCommand} calls on the worker thread.
 *
 * @since 1.0.0
 */
public final class VideoMcv2Command extends AbstractVideoCommand {

  /**
   * Creates the command.
   *
   * @param plugin the plugin
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  public VideoMcv2Command(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Handles {@code /mcav video mcv2 <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions>
   * <mapId> <preset> <ditheringAlgorithm> <ytdlpOptions> <mrl>}.
   *
   * <p>Build the wall first with {@code /mcav screen}, using the same block dimensions and map id. The screen takes a
   * slot of the one MCV2 resource pack that decodes every MCV2 screen of the server, which the viewers are offered if
   * they do not have it yet; those whose client loads it see the video decoded by the pack's shader over the wall,
   * everyone else sees it dithered onto the maps. For {@code @a}, a player who joins while the video plays is a viewer
   * too. Frames are scaled to the resolution, which should have the wall's aspect ratio. The encoder is not real time
   * at large resolutions: it encodes the newest frame whenever it is done with the last one, and steps down to a
   * faster preset, a smaller video or, at worst, the dithered maps when its budget cannot keep up.
   *
   * <p>Requires the permission {@code mcav.command.video.mcv2}.
   *
   * @param sender             who ran the command
   * @param playerSelector     the players who watch
   * @param playerType         the video backend, see {@link PlayerArgument}
   * @param audioType          where the sound is played, see {@link AudioArgument}
   * @param videoResolution    the encoded resolution as {@code <width>x<height>}, such as {@code 640x360}
   * @param blockDimensions    the size of the wall as {@code <width>x<height>} in maps, such as {@code 5x3}
   * @param mapId              the id of the top-left map of the wall, as given to {@code /mcav screen}
   * @param preset             the encoder preset, see {@link Mcv2Profile}
   * @param ditheringAlgorithm how the video is dithered for players without the pack, see {@link DitheringArgument}
   * @param ytdlpOptions       extra options, in quotes; {@code ""} for none
   * @param mrl                the media, in quotes if it contains spaces
   * @param filters            the {@code --filters} option: filters applied to every frame in order, such as
   *                           {@code "grayscale,blur=3"}, see {@link FilterChain}; none when absent
   * @throws NullPointerException if {@code playerSelector}, {@code preset} or {@code ditheringAlgorithm} is null
   * @since 1.0.0
   */
  @Command(
    "mcav video mcv2 <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions> <mapId> <preset> <ditheringAlgorithm> <ytdlpOptions> <mrl>"
  )
  @Permission("mcav.command.video.mcv2")
  @CommandDescription("mcav.command.video.mcv2.info")
  public void playMcv2Video(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    @Argument(suggestions = SuggestionProvider.RESOLUTIONS) @Quoted final String videoResolution,
    @Argument(suggestions = SuggestionProvider.DIMENSIONS) @Quoted final String blockDimensions,
    @Argument(suggestions = SuggestionProvider.IDS) @Range(min = "0") final int mapId,
    final Mcv2Profile preset,
    final DitheringArgument ditheringAlgorithm,
    @Quoted final String ytdlpOptions,
    @Quoted final String mrl,
    @Flag("filters") @Quoted final @Nullable String filters
  ) {
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null!");
    Preconditions.checkNotNull(preset, "Preset must not be null!");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null!");

    final Optional<Pair<Integer, Integer>> parsedBlocks = parseScreenDimensions(sender, blockDimensions);
    final @Nullable Pair<Integer, Integer> blocks = parsedBlocks.orElse(null);
    final Optional<Pair<Integer, Integer>> parsedResolution = parseDimensions(sender, videoResolution);
    final @Nullable Pair<Integer, Integer> resolution = parsedResolution.orElse(null);
    if (blocks == null || resolution == null) {
      return;
    }

    final OnlinePlayers onlinePlayers = this.plugin.getOnlinePlayers();
    final Collection<UUID> viewers = ArgumentUtils.resolveViewers(playerSelector, onlinePlayers);
    final Mcv2Support support = this.plugin.getMcv2Support();
    final Settings settings = preset.getSettings();
    final Optional<Mcv2Configuration> foundConfiguration = support.configure(sender, blocks, resolution, mapId, settings, viewers);
    final @Nullable Mcv2Configuration configuration = foundConfiguration.orElse(null);
    if (configuration == null) {
      return;
    }

    final VideoConfigurationProvider provider = _ -> new Mcv2Settings(configuration, ditheringAlgorithm, sender);
    this.playVideo(provider, sender, playerSelector, playerType, audioType, videoResolution, mrl, ytdlpOptions, filters);
  }

  /**
   * Creates the output that encodes the frames in a slot of the MCV2 pack, and starts it on the main thread. Called on
   * the worker thread. When every pack slot plays a screen, the frames are dithered onto the maps instead.
   *
   * @param resolution            the resolution frames are scaled to
   * @param configurationProvider the provider, which returns {@code Mcv2Settings}
   * @return the pipeline step that encodes the frames, or dithers them onto the maps
   * @throws NullPointerException                       if an argument is null
   * @throws ClassCastException                         if the provider builds no {@code Mcv2Settings}
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

    final Mcv2Settings settings = (Mcv2Settings) configurationProvider.buildConfiguration(resolution);
    final Mcv2Support support = this.plugin.getMcv2Support();
    final FunctionalVideoFilter output = support.createMcv2Filter(settings);
    this.manager.startFilter(output);

    return VideoPipelineStep.of(output);
  }

  record Mcv2Settings(Mcv2Configuration configuration, DitheringArgument dithering, CommandSender sender) {}
}
