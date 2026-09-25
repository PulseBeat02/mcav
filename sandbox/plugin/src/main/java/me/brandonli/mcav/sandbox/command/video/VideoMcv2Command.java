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
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
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
   * <p>Build the wall first with {@code /mcav screen}, using the same block dimensions and map id. The players are
   * sent the MCV2 resource pack, built for this screen and served on the server's port; those whose client loads it
   * see the video decoded by the pack's shader over the wall, everyone else sees it dithered onto the maps. Frames
   * are scaled to the resolution, which should have the wall's aspect ratio. The encoder is not real time at large
   * resolutions: it encodes the newest frame whenever it is done with the last one.
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
    final Mcv2Configuration configuration = configure(
      sender,
      blocks,
      resolution,
      mapId,
      profile,
      ArgumentUtils.parsePlayerSelectors(playerSelector)
    );
    if (configuration == null) {
      return;
    }
    final List<Player> players = List.copyOf(playerSelector.values());
    final Mcv2Viewers viewers = this.plugin.getMcv2Support().offer(configuration, players);
    final VideoConfigurationProvider provider = _ -> new Mcv2Settings(configuration, viewers, ditheringAlgorithm);
    this.playVideo(provider, sender, playerSelector, playerType, audioType, videoResolution, mrl, flags);
  }

  /**
   * Creates the configuration of an MCV2 screen on the wall that holds a map, or tells the sender there is none.
   *
   * @param sender     who ran the command
   * @param blocks     the size of the wall in maps
   * @param resolution the encoded resolution
   * @param mapId      the id of the top-left map
   * @param profile    the encoder profile
   * @param viewers    the players who watch
   * @return the configuration, or null if no frame holds the map
   */
  static @Nullable Mcv2Configuration configure(
    final CommandSender sender,
    final Pair<Integer, Integer> blocks,
    final Pair<Integer, Integer> resolution,
    final int mapId,
    final Mcv2Profile profile,
    final Collection<UUID> viewers
  ) {
    final ItemFrame frame = Mcv2Support.findFrame(Bukkit.getWorlds(), mapId);
    if (frame == null) {
      sender.sendMessage(Message.MCV2_SCREEN_ERROR.build(mapId));
      return null;
    }
    return Mcv2Configuration.builder()
      .viewers(viewers)
      .origin(frame.getLocation())
      .facing(frame.getFacing())
      .map(mapId)
      .columns(blocks.getFirst())
      .rows(blocks.getSecond())
      .video(resolution.getFirst(), resolution.getSecond())
      .settings(profile.getSettings())
      .build();
  }

  /**
   * Creates the result that encodes the frames, and starts it on the main thread. Called on the worker thread.
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
    final Mcv2Result result = new Mcv2Result(settings.configuration(), settings.viewers(), settings.dithering().createAlgorithm());
    this.manager.startFilter(result);
    return VideoPipelineStep.of(result);
  }

  /**
   * What {@link #createVideoFilter} needs.
   *
   * @param configuration the screen
   * @param viewers       who loaded the pack
   * @param dithering     the fallback dithering
   */
  record Mcv2Settings(Mcv2Configuration configuration, Mcv2Viewers viewers, DitheringArgument dithering) {}
}
