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
package me.brandonli.mcav.sandbox.command;

import static java.util.Objects.requireNonNull;

import com.google.common.primitives.Ints;
import com.mojang.brigadier.context.CommandContext;
import java.net.URI;
import java.nio.file.Path;
import java.util.stream.Stream;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.player.combined.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.DeviceSource;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.media.video.result.MapResult;
import me.brandonli.mcav.sandbox.MCAV;
import me.brandonli.mcav.sandbox.locale.AudienceProvider;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.SourceUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.annotations.suggestion.Suggestions;

public final class VideoCommand implements AnnotationCommandFeature {

  private static final String SOUND_KEY = "mcav.video.sound";

  private MCAV plugin;
  private BukkitAudiences audiences;

  @Override
  public void registerFeature(final MCAV plugin, final AnnotationParser<CommandSender> parser) {
    final AudienceProvider provider = plugin.getAudience();
    this.plugin = plugin;
    this.audiences = provider.retrieve();
  }

  @Command("mcav maps <playerType> <videoResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <mrl>")
  @Permission("mcav.maps")
  @CommandDescription("mcav.command.maps.info")
  public void playMapsVideo(
    final Player player,
    final PlayerArgument playerType,
    @Argument(suggestions = "resolutions") @Quoted final String videoResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "id") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Quoted final String mrl
  ) throws Exception {
    final Audience audience = this.audiences.sender(player);
    final Pair<Integer, Integer> resolution;
    final Pair<Integer, Integer> dimensions;
    try {
      resolution = ArgumentUtils.parseDimensions(videoResolution);
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException e) {
      audience.sendMessage(Message.DIMENSION_ERROR.build());
      return;
    }

    Source video = null;
    Source audio = null;

    final Integer deviceId = Ints.tryParse(mrl);
    if (SourceUtils.isPath(mrl)) {
      video = FileSource.path(Path.of(mrl));
    } else if (deviceId != null) {
      video = DeviceSource.device(deviceId);
    } else if (SourceUtils.isUri(mrl)) {
      final UriSource uri = UriSource.uri(URI.create(mrl));
      if (!SourceUtils.isDirectVideoFile(mrl)) {
        final YTDLPParser parser = YTDLPParser.simple();
        final URLParseDump dump = parser.parse(uri);
        final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.FIRST_VIDEO);
        video = selector.getVideoSource(dump).toUriSource();
        audio = selector.getAudioSource(dump).toUriSource();
      }
    } else {
      audience.sendMessage(Message.MRL_ERROR.build());
      return;
    }
    requireNonNull(video);

    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.NO_OP;
    final VideoPipelineStep videoPipelineStep = PipelineBuilder.video()
      .then(
        DitherFilter.dither(
          ditheringAlgorithm.getAlgorithm(),
          MapResult.builder()
            .map(mapId)
            .mapBlockWidth(dimensions.getFirst())
            .mapBlockHeight(dimensions.getSecond())
            .mapWidthResolution(resolution.getFirst())
            .mapHeightResolution(resolution.getSecond())
            .build()
        )
      )
      .build();

    final VideoPlayerMultiplexer multiplexer = playerType.createPlayer();
    if (audio == null) {
      multiplexer.start(audioPipelineStep, videoPipelineStep, video);
    } else {
      multiplexer.start(audioPipelineStep, videoPipelineStep, video, audio);
    }
  }

  @Suggestions("id")
  public Stream<Integer> suggestId(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of(0, 5, 10, 100, 1000, 10000, 100000);
  }

  @Suggestions("dimensions")
  public Stream<String> suggestDimensions(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of("4x4", "5x5", "16x9", "32x18");
  }

  @Suggestions("resolutions")
  public Stream<String> suggestResolutions(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of("512x512", "640x640", "1280x720", "1920x1080");
  }
}
