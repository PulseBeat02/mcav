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

import static java.util.Objects.requireNonNull;

import com.google.common.primitives.Ints;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.bukkit.hologram.Hologram;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.*;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.sandbox.utils.TaskUtils;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.SourceUtils;
import me.brandonli.mcav.utils.immutable.Dimension;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractVideoCommand implements AnnotationCommandFeature {

  protected MCAVSandbox plugin;
  protected AudioProvider provider;
  protected VideoPlayerManager manager;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    this.plugin = plugin;
    this.manager = plugin.getVideoPlayerManager();
    this.provider = plugin.getAudioProvider();
  }

  public void playVideo(
    final VideoConfigurationProvider configProvider,
    final CommandSender player,
    final MultiplePlayerSelector selector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    final String videoResolution,
    final String mrl,
    final String flags
  ) {
    if (!this.sanitizeArguments(player, playerType, audioType, videoResolution)) {
      return;
    }

    final Collection<Player> players = selector.values();
    for (final Player watcher : players) {
      watcher.sendMessage(Message.LOAD_VIDEO.build());
    }

    final Pair<Integer, Integer> resolution = requireNonNull(this.sanitizeResolution(videoResolution));
    final AtomicBoolean initializing = this.manager.getStatus();
    final ExecutorService service = this.manager.getService();
    final VideoFlagsParser flagsParser = new VideoFlagsParser();
    final String[] arguments = flagsParser.parseYTDLPFlags(flags);
    final Runnable command = () -> this.synchronizePlayer(playerType, audioType, mrl, arguments, player, resolution, configProvider);
    CompletableFuture.runAsync(command, service)
      .thenRun(() -> initializing.set(false))
      .thenRun(() -> this.sendArgumentUrl(audioType, selector))
      .thenRun(TaskUtils.handleAsyncTask(this.plugin, () -> player.sendMessage(Message.START_VIDEO.build())))
      .exceptionally(this::handleException);
  }

  private @Nullable Void handleException(final Throwable throwable) {
    final Logger logger = LoggerFactory.getLogger("MCAV Video");
    logger.error("An exception occurred while playing a video", throwable);
    return null;
  }

  private void sendArgumentUrl(final AudioArgument audioType, final MultiplePlayerSelector selector) {
    final Collection<Player> players = selector.values();
    final Component msg =
      switch (audioType) {
        case DISCORD_BOT -> {
          final String url = this.provider.constructVoiceChannelUrl();
          yield Message.AUDIO_DISCORD.build(url);
        }
        case HTTP_SERVER -> {
          final String url = this.provider.constructHttpUrl();
          yield Message.AUDIO_HTTP.build(url);
        }
        default -> null;
      };

    if (msg == null) {
      return;
    }

    for (final Player player : players) {
      player.sendMessage(msg);
    }
  }

  private boolean sanitizeArguments(
    final Audience audience,
    final PlayerArgument playerType,
    final AudioArgument argument,
    final String videoResolution
  ) {
    if (argument == AudioArgument.DISCORD_BOT && !this.provider.isDiscordBotEnabled()) {
      audience.sendMessage(Message.UNSUPPORTED_AUDIO.build());
      return false;
    }

    if (argument == AudioArgument.HTTP_SERVER && !this.provider.isHttpEnabled()) {
      audience.sendMessage(Message.UNSUPPORTED_AUDIO.build());
      return false;
    }

    final Pair<Integer, Integer> resolution = this.sanitizeResolution(videoResolution);
    if (resolution == null) {
      audience.sendMessage(Message.UNSUPPORTED_DIMENSION.build());
      return false;
    }

    if (playerType == PlayerArgument.VLC && !this.manager.isVLCSupported()) {
      audience.sendMessage(Message.UNSUPPORTED_PLAYER.build());
      return false;
    }

    final AtomicBoolean initializing = this.manager.getStatus();
    if (initializing.get()) {
      audience.sendMessage(Message.PLAYER_ERROR.build());
      return false;
    }
    initializing.set(true);

    return true;
  }

  private @Nullable Pair<Integer, Integer> sanitizeResolution(final String videoResolution) {
    try {
      return ArgumentUtils.parseDimensions(videoResolution);
    } catch (final IllegalArgumentException e) {
      return null;
    }
  }

  private synchronized void synchronizePlayer(
    final PlayerArgument playerType,
    final AudioArgument audioType,
    final String mrl,
    final String[] arguments,
    final Audience audience,
    final Pair<Integer, Integer> resolution,
    final VideoConfigurationProvider configProvider
  ) {
    final RetrievalResult sources = this.retrievePair(playerType, mrl, arguments);
    if (sources.audio == null && sources.video == null) {
      audience.sendMessage(Message.UNSUPPORTED_MRL.build());
      return;
    }
    this.manager.releaseVideoPlayer(false);

    this.startPlayer(playerType, audioType, resolution, sources, configProvider);
  }

  private void startPlayer(
    final PlayerArgument playerType,
    final AudioArgument audioType,
    final Pair<Integer, Integer> resolution,
    final RetrievalResult source,
    final VideoConfigurationProvider configProvider
  ) {
    final URLParseDump dump = source.dump;
    final VideoPipelineStep videoPipelineStep = this.createVideoFilter(resolution, configProvider);
    final AudioFilter filter = this.provider.constructFilter(audioType, dump);
    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(filter);
    final Source video = source.video;
    final Source audio = source.audio;
    final VideoPlayerMultiplexer player = playerType.createPlayer();
    this.manager.setPlayer(player);
    requireNonNull(video);

    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(videoPipelineStep);

    final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
    audioCallback.attach(audioPipelineStep);

    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    final DimensionAttachableCallback dimensionCallback = player.getDimensionAttachableCallback();
    final Dimension dimension = new Dimension(width, height);
    dimensionCallback.attach(dimension);

    if (audio == null) {
      player.start(video);
    } else {
      player.start(video, audio);
    }

    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTask(this.plugin, () -> {
      final Hologram existing = this.manager.getHologram();
      if (existing != null) {
        existing.kill();
        this.manager.setHologram(null);
      }

      final Location location = this.manager.getHologramLocation();
      if (location == null) {
        return;
      }

      if (dump == null) {
        return;
      }

      final Hologram hologram = Hologram.basic();
      hologram.handleRequest(location, dump);
      hologram.start();
      this.manager.setHologram(hologram);
    });
  }

  public abstract VideoPipelineStep createVideoFilter(Pair<Integer, Integer> resolution, VideoConfigurationProvider configProvider);

  private RetrievalResult retrievePair(final PlayerArgument argument, final String mrl, final String[] arguments) {
    final Source video;
    Source audio = null;
    URLParseDump dump = null;
    final Integer deviceId = Ints.tryParse(mrl);
    if (SourceUtils.isPath(mrl)) {
      final Path path = Path.of(mrl);
      video = FileSource.path(path);
      dump = new URLParseDump();
      dump.title = IOUtils.getName(path);
      dump.description = "Video from File";
    } else if (deviceId != null) {
      video = DeviceSource.device(deviceId);
      dump = new URLParseDump();
      dump.title = "Device Input %s".formatted(deviceId);
      dump.description = "Video from Device";
    } else if (SourceUtils.isUri(mrl)) {
      final UriSource uri = UriSource.uri(URI.create(mrl));
      if (!SourceUtils.isDirectVideoFile(mrl)) {
        dump = this.getUrlParseDump(uri, arguments);
        final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
        video = selector.getVideoSource(dump).toUriSource();
        audio = selector.getAudioSource(dump).toUriSource();
      } else {
        dump = new URLParseDump();
        dump.title = IOUtils.getFileNameFromUrl(mrl);
        dump.description = "Video from URL";
        video = uri;
      }
    } else if (argument == PlayerArgument.FFMPEG) {
      final String[] split = mrl.split(":");
      final String format = split[0];
      final String rawMrl = split[1];
      video = FFmpegDirectSource.mrl(rawMrl, format);
      dump = new URLParseDump();
      dump.title = rawMrl;
      dump.description = "Custom format with format %s".formatted(format);
    } else {
      dump = new URLParseDump();
      video = null;
    }
    return new RetrievalResult(video, audio, dump);
  }

  private record RetrievalResult(@Nullable Source video, @Nullable Source audio, URLParseDump dump) {}

  private URLParseDump getUrlParseDump(final UriSource uri, final String[] arguments) {
    final YTDLPParser parser = YTDLPParser.simple();
    try {
      return parser.parse(uri, arguments);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  public interface VideoConfigurationProvider {
    Object buildConfiguration(Pair<Integer, Integer> resolution);
  }
}
