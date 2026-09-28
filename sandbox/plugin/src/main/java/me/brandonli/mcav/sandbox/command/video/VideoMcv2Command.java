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
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
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
 * {@code /mcav video mcv2}: plays a video on a wall of maps built with {@code /mcav screen}, encoded with MCV2 for the
 * players whose client loads the MCV2 resource pack, and dithered onto the maps for everyone else.
 */
public final class VideoMcv2Command extends AbstractVideoCommand {

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public VideoMcv2Command(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Handles {@code /mcav video mcv2 <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions>
   * <mapId> <profile> <ditheringAlgorithm> <flags> <mrl>}.
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
   * @param profile            the encoder profile, see {@link Mcv2Profile}
   * @param ditheringAlgorithm how the video is dithered for players without the pack, see {@link DitheringArgument}
   * @param flags              extra options, in quotes; {@code ""} for none
   * @param mrl                the media, in quotes if it contains spaces
   */
  @Command(
    "mcav video mcv2 <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions> <mapId> <profile> <ditheringAlgorithm> <flags> <mrl>"
  )
  @Permission("mcav.command.video.mcv2")
  @CommandDescription("mcav.command.video.mcv2.info")
  public void playMcv2Video(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    @Argument(suggestions = "resolutions") @Quoted final String videoResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final Mcv2Profile profile,
    final DitheringArgument ditheringAlgorithm,
    @Quoted final String flags,
    @Quoted final String mrl
  ) {
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(profile, "Profile must not be null");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null");
    final Pair<Integer, Integer> blocks = parseScreenDimensions(sender, blockDimensions);
    final Pair<Integer, Integer> resolution = parseDimensions(sender, videoResolution);
    if (blocks == null || resolution == null) {
      return;
    }
    final Collection<UUID> viewers = ArgumentUtils.parseViewers(playerSelector, this.plugin.getOnlinePlayers());
    final Mcv2Support support = this.plugin.getMcv2Support();
    final Mcv2Configuration configuration = support.configure(sender, blocks, resolution, mapId, profile.getSettings(), viewers);
    if (configuration == null) {
      return;
    }
    final VideoConfigurationProvider provider = _ -> new Mcv2Settings(configuration, ditheringAlgorithm, sender);
    this.playVideo(provider, sender, playerSelector, playerType, audioType, videoResolution, mrl, flags);
  }

  /**
   * The system property that sets the page slots of the screens started next, for a measurement; unset or 0 for
   * mcav's default.
   */
  static final String PAGE_SLOTS_PROPERTY = "mcav.sandbox.mcv2.pageSlots";

  /**
   * The system property that sets the backlog limit of the screens started next, in bytes, or {@code none} for no
   * backpressure at all (no backlog limit and no cap on a viewer's unsent bytes), for a measurement; unset for mcav's
   * default.
   */
  static final String BACKLOG_PROPERTY = "mcav.sandbox.mcv2.backlogLimit";

  /**
   * The backlog limit of new screens: the {@link #BACKLOG_PROPERTY} system property in bytes, {@code none} for no
   * limit, or mcav's default when it is not set.
   *
   * @return the limit in bytes
   */
  static long backlogLimit() {
    final String value = System.getProperty(BACKLOG_PROPERTY);
    if (value == null) {
      return Mcv2Configuration.DEFAULT_BACKLOG_LIMIT;
    }
    return value.equals("none") ? Long.MAX_VALUE : Long.parseLong(value);
  }

  /**
   * The cap on a viewer's unsent bytes of new screens: none when the {@link #BACKLOG_PROPERTY} system property is
   * {@code none}, a measurement without backpressure, and mcav's default otherwise.
   *
   * @return the cap in bytes, 0 for none
   */
  static int unsentLimit() {
    return "none".equals(System.getProperty(BACKLOG_PROPERTY)) ? 0 : Mcv2Configuration.DEFAULT_UNSENT_LIMIT;
  }

  /**
   * Creates the output that encodes the frames in a slot of the MCV2 pack, and starts it on the main thread. Called on
   * the worker thread.
   *
   * @param resolution            the resolution frames are scaled to
   * @param configurationProvider the provider, which returns {@link Mcv2Settings}
   * @return the pipeline
   */
  @Override
  public VideoPipelineStep createVideoFilter(
    final Pair<Integer, Integer> resolution,
    final VideoConfigurationProvider configurationProvider
  ) {
    Preconditions.checkNotNull(resolution, "Resolution must not be null");
    Preconditions.checkNotNull(configurationProvider, "Configuration provider must not be null");
    final Mcv2Settings settings = (Mcv2Settings) configurationProvider.buildConfiguration(resolution);
    final FunctionalVideoFilter output = mcv2Filter(this.plugin.getMcv2Support(), settings);
    this.manager.startFilter(output);
    return VideoPipelineStep.of(output);
  }

  /**
   * The filter of an MCV2 video screen: its output in a slot of the pack, or, when every slot plays a screen, the
   * dithered maps of the same wall.
   *
   * @param support  the plugin's MCV2 support
   * @param settings the screen
   * @return the filter, not started
   */
  static FunctionalVideoFilter mcv2Filter(final Mcv2Support support, final Mcv2Settings settings) {
    final Mcv2Configuration configuration = settings.configuration();
    final Mcv2Output output = support.output(settings.sender(), configuration, settings.dithering());
    if (output != null) {
      return output;
    }
    final MapConfiguration maps = MapDisplaySettings.createConfiguration(
      Pair.pair(configuration.getColumns(), configuration.getRows()),
      Pair.pair(configuration.getVideoWidth(), configuration.getVideoHeight()),
      configuration.getMap(),
      configuration.getViewers()
    );
    return DitherFilter.dither(settings.dithering().createAlgorithm(), new CompressedMapResult(maps));
  }

  /**
   * What {@link #createVideoFilter} needs.
   *
   * @param configuration the screen
   * @param dithering     the fallback dithering
   * @param sender        who started the screen, who is told when it steps down or back up
   */
  record Mcv2Settings(Mcv2Configuration configuration, DitheringArgument dithering, CommandSender sender) {}
}
