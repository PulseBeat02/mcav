/*
 * This file is part of mcav, a media playback library for Minecraft
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

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.MapResult;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

public final class VideoMapCommand extends AbstractVideoCommand {

  @Command(
    "mcav video map <playerSelector> <playerType> <audioType> <videoResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <mrl>"
  )
  @Permission("mcav.command.video.map")
  @CommandDescription("mcav.command.video.map.info")
  public void playMapVideo(
    final CommandSender player,
    final MultiplePlayerSelector playerSelector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    @Argument(suggestions = "resolutions") @Quoted final String videoResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Greedy final String mrl
  ) {
    final Pair<Integer, Integer> dimensions;
    try {
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException e) {
      player.sendMessage(Message.UNSUPPORTED_DIMENSION.build());
      return;
    }
    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final VideoConfigurationProvider configProvider = resolution ->
      this.constructMapConfiguration(mapId, dimensions, resolution, players, ditheringAlgorithm);
    this.playVideo(configProvider, player, playerSelector, playerType, audioType, videoResolution, mrl);
  }

  @Override
  public VideoPipelineStep createVideoFilter(final Pair<Integer, Integer> resolution, final VideoConfigurationProvider configProvider) {
    final MapConfigurationData config = (MapConfigurationData) configProvider.buildConfiguration(resolution);
    final MapConfiguration mapConfig = config.mapConfiguration();
    final MapResult result = new MapResult(mapConfig);
    final DitherAlgorithm algorithm = config.ditheringAlgorithm().getAlgorithm();
    final VideoFilter ditherFilter = DitherFilter.dither(algorithm, result);
    return PipelineBuilder.video().then(VideoFilter.FRAME_RATE).then(ditherFilter).build();
  }

  private MapConfigurationData constructMapConfiguration(
    final int mapId,
    final Pair<@NonNull Integer, @NonNull Integer> dimensions,
    final Pair<@NonNull Integer, @NonNull Integer> resolution,
    final Collection<UUID> players,
    final DitheringArgument ditheringAlgorithm
  ) {
    final MapConfiguration config = MapConfiguration.builder()
      .map(mapId)
      .mapBlockWidth(dimensions.getFirst())
      .mapBlockHeight(dimensions.getSecond())
      .mapWidthResolution(resolution.getFirst())
      .mapHeightResolution(resolution.getSecond())
      .viewers(players)
      .build();
    return new MapConfigurationData(config, ditheringAlgorithm);
  }

  private record MapConfigurationData(MapConfiguration mapConfiguration, DitheringArgument ditheringAlgorithm) {}
}
