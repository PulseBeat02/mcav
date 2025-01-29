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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.player.combined.VideoPlayerMultiplexer;
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
import me.brandonli.mcav.utils.SourceUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

public abstract class AbstractVideoCommand implements AnnotationCommandFeature {

  protected AudioProvider provider;
  protected VideoPlayerManager manager;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
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
    final String mrl
  ) {
    if (!this.sanitizeArguments(player, playerType, audioType, videoResolution)) {
      return;
    }
    player.sendMessage(Message.LOAD_VIDEO.build());

    final Pair<Integer, Integer> resolution = requireNonNull(this.sanitizeResolution(videoResolution));
    final AtomicBoolean initializing = this.manager.getStatus();
    final ExecutorService service = this.manager.getService();
    final Runnable command = () -> this.synchronizePlayer(playerType, audioType, mrl, player, resolution, configProvider);
    CompletableFuture.runAsync(command, service)
      .thenRun(() -> initializing.set(false))
      .thenRun(() -> this.sendArgumentUrl(audioType, selector))
      .thenRun(() -> player.sendMessage(Message.START_VIDEO.build()));
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
    final Audience audience,
    final Pair<Integer, Integer> resolution,
    final VideoConfigurationProvider configProvider
  ) {
    @Nullable
    final Source[] sources = this.retrievePair(mrl, playerType);
    if (sources == null) {
      audience.sendMessage(Message.UNSUPPORTED_MRL.build());
      return;
    }
    this.manager.releaseVideoPlayer();

    this.startPlayer(playerType, audioType, resolution, sources, configProvider);
  }

  private void startPlayer(
    final PlayerArgument playerType,
    final AudioArgument audioType,
    final Pair<Integer, Integer> resolution,
    final @Nullable Source[] sources,
    final VideoConfigurationProvider configProvider
  ) {
    final VideoPipelineStep videoPipelineStep = this.createVideoFilter(resolution, configProvider);
    final AudioFilter filter = this.provider.constructFilter(audioType);
    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(filter);
    final Source video = sources[0];
    final Source audio = sources[1];
    final VideoPlayerMultiplexer player = playerType.createPlayer();
    this.manager.setPlayer(player);
    requireNonNull(video);
    if (audio == null) {
      player.start(audioPipelineStep, videoPipelineStep, video);
    } else {
      player.start(audioPipelineStep, videoPipelineStep, video, audio);
    }
  }

  public abstract VideoPipelineStep createVideoFilter(Pair<Integer, Integer> resolution, VideoConfigurationProvider configProvider);

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

  protected URLParseDump getUrlParseDump(final UriSource uri) {
    final YTDLPParser parser = YTDLPParser.simple();
    try {
      return parser.parse(uri);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  public interface VideoConfigurationProvider {
    Object buildConfiguration(Pair<Integer, Integer> resolution);
  }
}
