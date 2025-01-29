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
import me.brandonli.mcav.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.media.player.combined.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.result.FunctionalVideoFilter;
import me.brandonli.mcav.media.result.ScoreboardResult;
import me.brandonli.mcav.media.source.*;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
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
import org.incendo.cloud.annotations.*;

public final class VideoScoreboardCommand implements AnnotationCommandFeature {

  private VideoPlayerManager manager;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    this.manager = plugin.getVideoPlayerManager();
  }

  @Command("mcav video scoreboard <playerType> <videoResolution> <character> <mrl>")
  @Permission("mcav.command.video.scoreboard")
  @CommandDescription("mcav.command.video.scoreboard.info")
  public void playScoreboardVideo(
    final CommandSender player,
    final PlayerArgument playerType,
    @Argument(suggestions = "dimensions") @Quoted final String videoResolution,
    @Argument(suggestions = "chat-characters") @Quoted final String character,
    @Greedy final String mrl
  ) {
    final BukkitAudiences audiences = this.manager.getAudiences();
    final AtomicBoolean initializing = this.manager.getStatus();
    final Audience audience = audiences.sender(player);
    final Pair<Integer, Integer> resolution;
    try {
      resolution = ArgumentUtils.parseDimensions(videoResolution);
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
    CompletableFuture.runAsync(() -> this.synchronizeScoreboardPlayer(playerType, mrl, audience, character, resolution), service)
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

  private synchronized void synchronizeScoreboardPlayer(
    final PlayerArgument playerType,
    final String mrl,
    final Audience audience,
    final String character,
    final Pair<Integer, Integer> resolution
  ) {
    @Nullable
    final Source[] sources = this.retrievePair(mrl, playerType);
    if (sources == null) {
      audience.sendMessage(Message.MRL_ERROR.build());
      return;
    }
    this.manager.releaseVideoPlayer();
    this.startScoreboardPlayer(playerType, character, resolution, sources);
  }

  private void startScoreboardPlayer(
    final PlayerArgument playerType,
    final String character,
    final Pair<Integer, Integer> resolution,
    final @Nullable Source[] sources
  ) {
    final VideoPipelineStep videoPipelineStep = this.createScoreboardVideoFilter(character, resolution);
    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.NO_OP;
    final Source video = sources[0];
    final Source audio = sources[1];
    final VideoPlayerMultiplexer player = playerType.createPlayer();
    this.manager.setPlayer(player);
    requireNonNull(video);
    if (audio == null) {
      player.start(audioPipelineStep, videoPipelineStep, video);
    } else {
      requireNonNull(audio);
      player.start(audioPipelineStep, videoPipelineStep, video, audio);
    }
  }

  private VideoPipelineStep createScoreboardVideoFilter(final String character, final Pair<Integer, Integer> resolution) {
    final Collection<UUID> players = this.getAllViewers();
    final ScoreboardConfiguration configuration = this.constructScoreboardConfiguration(resolution, character, players);
    final FunctionalVideoFilter result = new ScoreboardResult(configuration);
    result.start();
    this.manager.setFilter(result);
    return PipelineBuilder.video().then(VideoFilter.FRAME_RATE).then(result).build();
  }

  private ScoreboardConfiguration constructScoreboardConfiguration(
    final Pair<@NonNull Integer, @NonNull Integer> resolution,
    final String character,
    final Collection<UUID> players
  ) {
    return ScoreboardConfiguration.builder()
      .viewers(players)
      .width(resolution.getFirst())
      .lines(resolution.getSecond())
      .character(character)
      .build();
  }

  private Collection<UUID> getAllViewers() {
    final Collection<? extends Player> online = Bukkit.getOnlinePlayers();
    return online.stream().map(Player::getUniqueId).toList();
  }

  private @Nullable Source@Nullable[] retrievePair(final String mrl, final PlayerArgument argument) {
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
    } else if (argument == PlayerArgument.FFMPEG) {
      final String[] split = mrl.split(":");
      final String format = split[0];
      final String rawMrl = split[1];
      video = FFmpegDirectSource.mrl(rawMrl, format);
    } else {
      video = null;
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
