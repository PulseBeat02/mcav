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

import static java.util.Objects.requireNonNull;

import com.google.common.primitives.Ints;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.config.MapConfiguration;
import me.brandonli.mcav.media.player.combined.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.result.MapResult;
import me.brandonli.mcav.media.source.DeviceSource;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.SourceUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.*;

public final class VideoMapCommand implements AnnotationCommandFeature {

  private VideoPlayerManager manager;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    this.manager = plugin.getVideoPlayerManager();
  }

  @Command("mcav video map <playerType> <videoResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <mrl>")
  @Permission("mcav.command.video.map")
  @CommandDescription("mcav.command.video.map.info")
  public void playMapVideo(
    final Player player,
    final PlayerArgument playerType,
    @Argument(suggestions = "resolutions") @Quoted final String videoResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Greedy final String mrl
  ) {
    final BukkitAudiences audiences = this.manager.getAudiences();
    final AtomicBoolean initializing = this.manager.getStatus();
    final Audience audience = audiences.sender(player);
    final Pair<Integer, Integer> resolution;
    final Pair<Integer, Integer> dimensions;
    try {
      resolution = ArgumentUtils.parseDimensions(videoResolution);
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException e) {
      audience.sendMessage(Message.DIMENSION_ERROR.build());
      return;
    }

    if (initializing.get()) {
      audience.sendMessage(Message.VIDEO_LOADING_ERROR.build());
      return;
    }

    initializing.set(true);

    audience.sendMessage(Message.VIDEO_LOADING.build());

    final ExecutorService service = this.manager.getService();
    CompletableFuture.runAsync(
      () -> this.synchronizeMapPlayer(playerType, mapId, ditheringAlgorithm, mrl, audience, dimensions, resolution),
      service
    )
      .exceptionally(this.handleException())
      .thenRun(() -> initializing.set(false))
      .thenRun(() -> audience.sendMessage(Message.VIDEO_STARTED.build()));
  }

  private @NonNull Function<Throwable, Void> handleException() {
    final AtomicBoolean initializing = this.manager.getStatus();
    return e -> {
      initializing.set(false);
      throw new AssertionError(e);
    };
  }

  private synchronized void synchronizeMapPlayer(
    final PlayerArgument playerType,
    final int mapId,
    final DitheringArgument ditheringAlgorithm,
    final String mrl,
    final Audience audience,
    final Pair<Integer, Integer> dimensions,
    final Pair<Integer, Integer> resolution
  ) {
    @Nullable
    final Source[] sources = this.retrievePair(mrl);
    if (sources == null) {
      audience.sendMessage(Message.MRL_ERROR.build());
      return;
    }
    this.releaseVideoPlayer();
    this.startMapPlayer(playerType, mapId, ditheringAlgorithm, dimensions, resolution, sources);
  }

  private void startMapPlayer(
    final PlayerArgument playerType,
    final int mapId,
    final DitheringArgument ditheringAlgorithm,
    final Pair<Integer, Integer> dimensions,
    final Pair<Integer, Integer> resolution,
    final @Nullable Source[] sources
  ) {
    final VideoPipelineStep videoPipelineStep = this.createMapVideoFilter(mapId, ditheringAlgorithm, dimensions, resolution);
    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.NO_OP;
    final Source video = sources[0];
    final Source audio = sources[1];
    final VideoPlayerMultiplexer player = playerType.createPlayer();
    this.manager.setPlayer(player);
    try {
      requireNonNull(video);
      if (audio == null) {
        player.start(audioPipelineStep, videoPipelineStep, video);
      } else {
        requireNonNull(audio);
        player.start(audioPipelineStep, videoPipelineStep, video, audio);
      }
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  private VideoPipelineStep createMapVideoFilter(
    final int mapId,
    final DitheringArgument ditheringAlgorithm,
    final Pair<Integer, Integer> dimensions,
    final Pair<Integer, Integer> resolution
  ) {
    final Collection<UUID> players = this.getAllViewers();
    final MapConfiguration configuration = this.constructMapConfiguration(mapId, dimensions, resolution, players);
    final MapResult result = new MapResult(configuration);
    final DitherAlgorithm algorithm = ditheringAlgorithm.getAlgorithm();
    final VideoFilter ditherFilter = DitherFilter.dither(algorithm, result);
    return PipelineBuilder.video().then(VideoFilter.FRAME_RATE).then(ditherFilter).build();
  }

  private MapConfiguration constructMapConfiguration(
    final int mapId,
    final Pair<@NonNull Integer, @NonNull Integer> dimensions,
    final Pair<@NonNull Integer, @NonNull Integer> resolution,
    final Collection<UUID> players
  ) {
    return MapConfiguration.builder()
      .map(mapId)
      .mapBlockWidth(dimensions.getFirst())
      .mapBlockHeight(dimensions.getSecond())
      .mapWidthResolution(resolution.getFirst())
      .mapHeightResolution(resolution.getSecond())
      .viewers(players)
      .build();
  }

  private Collection<UUID> getAllViewers() {
    final Collection<? extends Player> online = Bukkit.getOnlinePlayers();
    return online.stream().map(Player::getUniqueId).toList();
  }

  private void releaseVideoPlayer() {
    final VideoPlayerMultiplexer videoPlayer = this.manager.getPlayer();
    if (videoPlayer != null) {
      try {
        videoPlayer.release();
      } catch (final Exception e) {
        throw new AssertionError(e);
      }
      this.manager.setPlayer(null);
    }
  }

  private @Nullable Source@Nullable[] retrievePair(final String mrl) {
    final Source video;
    Source audio = null;
    final Integer deviceId = Ints.tryParse(mrl);
    if (SourceUtils.isPath(mrl)) {
      video = FileSource.path(Path.of(mrl));
    } else if (deviceId != null) {
      video = DeviceSource.device(deviceId);
    } else if (SourceUtils.isUri(mrl)) {
      final UriSource uri = UriSource.uri(URI.create(mrl));
      if (!SourceUtils.isDirectVideoFile(mrl)) {
        final URLParseDump dump = this.getUrlParseDump(uri);
        final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.FIRST_VIDEO);
        video = selector.getVideoSource(dump).toUriSource();
        audio = selector.getAudioSource(dump).toUriSource();
      } else {
        video = uri;
      }
    } else {
      return null;
    }
    return new Source[] { video, audio };
  }

  private URLParseDump getUrlParseDump(final UriSource uri) {
    final YTDLPParser parser = YTDLPParser.simple();
    try {
      return parser.parse(uri);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }
}
