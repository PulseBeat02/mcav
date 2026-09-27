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

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.bukkit.hologram.Hologram;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.Format;
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
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.SourceDetectionHelper;
import me.brandonli.mcav.media.source.device.DeviceSource;
import me.brandonli.mcav.media.source.ffmpeg.FFmpegDirectSource;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioOutputs;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.sandbox.utils.TaskUtils;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.SourceUtils;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.immutable.Dimension;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The common flow of the video commands: validate the arguments, resolve the media on the worker thread, start
 * the player with the display type of the subclass, and report the outcome to the sender.
 */
public abstract class AbstractVideoCommand implements AnnotationCommandFeature {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractVideoCommand.class);

  /**
   * The plugin.
   */
  protected final MCAVSandbox plugin;

  /**
   * The provider of audio outputs.
   */
  protected final AudioProvider provider;

  /**
   * The manager of the running video player.
   */
  protected final VideoPlayerManager manager;

  /**
   * Constructs the command.
   *
   * @param plugin the plugin, whose video player manager and audio provider must be available
   */
  protected AbstractVideoCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
    this.manager = plugin.getVideoPlayerManager();
    this.provider = plugin.getAudioProvider();
  }

  /**
   * Plays a video, replacing the video played before.
   *
   * <p>The resolution, the player backend, and the audio output are checked first; any problem is reported to the
   * sender and nothing starts. Only one video can start at a time. The viewers are then told that the video is
   * loading, and the media is resolved and started on the worker thread of the video player manager.
   *
   * @param configurationProvider creates the display configuration for the parsed resolution
   * @param sender                who ran the command
   * @param selector              the players who watch
   * @param playerType            the player backend
   * @param audioType             the audio output
   * @param videoResolution       the resolution argument, such as {@code 640x640}
   * @param mrl                   the path, URL, device index, or FFmpeg input
   * @param flags                 the optional flags, may be empty
   */
  public void playVideo(
    final VideoConfigurationProvider configurationProvider,
    final CommandSender sender,
    final MultiplePlayerSelector selector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    final String videoResolution,
    final String mrl,
    final String flags
  ) {
    Preconditions.checkNotNull(configurationProvider, "Configuration provider must not be null");
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(selector, "Player selector must not be null");
    Preconditions.checkNotNull(playerType, "Player type must not be null");
    Preconditions.checkNotNull(audioType, "Audio type must not be null");
    Preconditions.checkNotNull(videoResolution, "Video resolution must not be null");
    Preconditions.checkNotNull(mrl, "MRL must not be null");
    Preconditions.checkNotNull(flags, "Flags must not be null");

    final Pair<Integer, Integer> resolution = parseDimensions(sender, videoResolution);
    if (resolution == null) {
      return;
    }
    final String[] ytdlpArguments = parseFlags(sender, flags);
    if (ytdlpArguments == null) {
      return;
    }
    final boolean ready = this.checkBackends(sender, playerType, audioType, mrl);
    if (!ready || !this.claim(sender)) {
      return;
    }

    try {
      final Player[] viewers = collectViewers(selector);
      notifyLoading(viewers);
      final PlaybackRequest request = new PlaybackRequest(
        playerType,
        audioType,
        mrl,
        ytdlpArguments,
        resolution,
        configurationProvider,
        viewers
      );
      this.launch(sender, request);
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      final AtomicBoolean initializing = this.manager.getStatus();
      initializing.set(false);
      throw exception;
    }
  }

  /**
   * Parses a size such as {@code 640x360}, telling the sender when it is not valid.
   *
   * @param sender who ran the command
   * @param text   the size as entered
   * @return the width and height, or {@code null} if the text is not a valid size
   */
  protected static @Nullable Pair<Integer, Integer> parseDimensions(final CommandSender sender, final String text) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(text, "Text must not be null");
    try {
      return ArgumentUtils.parseDimensions(text);
    } catch (final IllegalArgumentException exception) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return null;
    }
  }

  /**
   * Parses the size of a wall of maps such as {@code 5x5}, telling the sender when it is not valid or larger than
   * {@link ArgumentUtils#parseScreenDimensions(String)} allows.
   *
   * @param sender who ran the command
   * @param text   the size as entered
   * @return the width and height in maps, or {@code null} if the text is not a valid wall size
   */
  protected static @Nullable Pair<Integer, Integer> parseScreenDimensions(final CommandSender sender, final String text) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(text, "Text must not be null");
    try {
      return ArgumentUtils.parseScreenDimensions(text);
    } catch (final IllegalArgumentException exception) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return null;
    }
  }

  /**
   * Claims the right to start a video, which only one command may do at a time.
   *
   * @return true if claimed, false if another video is still starting
   */
  private boolean claim(final CommandSender sender) {
    final AtomicBoolean initializing = this.manager.getStatus();
    final boolean claimed = initializing.compareAndSet(false, true);
    if (!claimed) {
      final Component message = Message.PLAYER_ERROR.build();
      sender.sendMessage(message);
    }
    return claimed;
  }

  private static Player[] collectViewers(final MultiplePlayerSelector selector) {
    final Collection<Player> players = selector.values();
    return players.toArray(new Player[0]);
  }

  private static void notifyLoading(final Player[] viewers) {
    final Component loading = Message.LOAD_VIDEO.build();
    for (final Player viewer : viewers) {
      viewer.sendMessage(loading);
    }
  }

  /**
   * Parses the yt-dlp options of the flags, telling the sender when one of them is not supported.
   *
   * @return the arguments for yt-dlp, or {@code null} if an option was refused
   */
  private static String@Nullable[] parseFlags(final CommandSender sender, final String flags) {
    final VideoFlagsParser flagsParser = new VideoFlagsParser();
    try {
      return flagsParser.parseYTDLPFlags(flags);
    } catch (final IllegalArgumentException exception) {
      final String cause = exception.getMessage();
      final String reason = Objects.requireNonNullElse(cause, "The flags are not valid");
      final Component message = Message.UNSUPPORTED_FLAGS.build(reason);
      sender.sendMessage(message);
      return null;
    }
  }

  /**
   * Starts the video on the worker thread and reports the outcome to the sender on the main thread. The claim of
   * {@link #claim(CommandSender)} is given up once the worker is done, or at once if it refuses the video.
   */
  private void launch(final CommandSender sender, final PlaybackRequest request) {
    final AtomicBoolean initializing = this.manager.getStatus();
    final ExecutorService service = this.manager.getService();
    final CompletableFuture<Boolean> start;
    final long generation;
    try {
      generation = this.manager.beginStart();
      start = CompletableFuture.supplyAsync(() -> this.startPlayer(request, generation), service);
    } catch (final RejectedExecutionException | CancellationException exception) {
      initializing.set(false);
      LOGGER.error("The video worker refused to start a video", exception);
      final Component message = Message.VIDEO_START_ERROR.build();
      sender.sendMessage(message);
      return;
    }

    final AudioArgument audioType = request.getAudioType();
    final Player[] viewers = request.getViewers();
    TaskUtils.whenComplete(start, (started, error) -> {
      initializing.set(false);
      TaskUtils.runOnMainThread(this.plugin, () -> {
        if (this.manager.isCurrent(generation)) {
          this.report(sender, audioType, viewers, started, error);
        }
      });
    });
  }

  private void report(
    final CommandSender sender,
    final AudioArgument audioType,
    final Player[] viewers,
    final @Nullable Boolean started,
    final @Nullable Throwable error
  ) {
    if (error != null) {
      LOGGER.error("Failed to play a video", error);
      final Component message = Message.VIDEO_START_ERROR.build();
      sender.sendMessage(message);
      return;
    }

    // without an error the worker always reports whether the video started
    final boolean playing = Boolean.TRUE.equals(started);
    if (!playing) {
      final Component message = Message.UNSUPPORTED_MRL.build();
      sender.sendMessage(message);
      return;
    }

    AudioOutputs.sendLink(this.provider, audioType, viewers);
    final Component message = Message.START_VIDEO.build();
    sender.sendMessage(message);
  }

  private boolean checkBackends(
    final CommandSender sender,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    final String mrl
  ) {
    final Component problem = this.findProblem(playerType, audioType, mrl);
    if (problem == null) {
      return true;
    }
    sender.sendMessage(problem);
    return false;
  }

  /**
   * Finds the first reason the video cannot play now: the audio output, then the player backend, then yt-dlp.
   *
   * @return the message that explains the problem, or {@code null} if the video can play
   */
  private @Nullable Component findProblem(final PlayerArgument playerType, final AudioArgument audioType, final String mrl) {
    final Component audioProblem = AudioOutputs.findProblem(this.provider, audioType);
    if (audioProblem != null) {
      return audioProblem;
    }
    final Component playerProblem = this.findPlayerProblem(playerType);
    if (playerProblem != null) {
      return playerProblem;
    }
    return this.findYtdlpProblem(mrl);
  }

  /**
   * Checks whether the player backend can play now. VLC is prepared in the background when the plugin is enabled, so
   * it may still be downloading, or it may have turned out to be unavailable.
   *
   * @return the message that explains why the backend cannot play, or {@code null} if it can
   */
  private @Nullable Component findPlayerProblem(final PlayerArgument playerType) {
    if (playerType != PlayerArgument.VLC) {
      return null;
    }
    final boolean preparing = this.manager.isPreparing(Capability.VLC);
    if (preparing) {
      return Message.VLC_PREPARING.build();
    }
    final boolean supported = this.manager.isVLCSupported();
    if (!supported) {
      return Message.UNSUPPORTED_PLAYER.build();
    }
    return null;
  }

  /**
   * Checks whether the media can be resolved now. A web page, such as a YouTube video, is resolved with yt-dlp, which
   * is prepared in the background when the plugin is enabled and may still be downloading. Files, devices, FFmpeg
   * inputs and direct links to media need no yt-dlp.
   *
   * @return the message that explains why the media cannot be resolved yet, or {@code null} if it can
   */
  private @Nullable Component findYtdlpProblem(final String mrl) {
    final boolean url = SourceUtils.isUri(mrl);
    final boolean direct = SourceUtils.isDirectVideo(mrl);
    if (!url || direct) {
      return null;
    }
    final boolean preparing = this.manager.isPreparing(Capability.YT_DLP);
    return preparing ? Message.YTDLP_PREPARING.build() : null;
  }

  // runs on the worker thread
  private boolean startPlayer(final PlaybackRequest request, final long generation) {
    this.manager.checkStart();
    final String mrl = request.getMrl();
    final String[] ytdlpArguments = request.getYtdlpArguments();
    final SourceSelection selection = selectSources(mrl, ytdlpArguments);
    this.manager.checkStart();
    if (selection == null) {
      return false;
    }

    this.manager.clearCurrentVideo();
    this.manager.checkStart();
    final URLParseDump dump = selection.getDump();
    try {
      final VideoPlayerMultiplexer player = this.createPlayer(request, dump);
      final boolean playing = this.startPlayback(player, selection);
      if (!playing) {
        this.manager.clearCurrentVideo();
        return false;
      }
      TaskUtils.runOnMainThread(this.plugin, () -> {
        if (this.manager.isCurrent(generation)) {
          this.showHologram(dump);
        }
      });
      return true;
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      cleanupAfterFailure(exception, this.manager::clearCurrentVideo);
      throw exception;
    }
  }

  /**
   * Creates the player of the request, makes it the running one, and attaches the video, audio, and dimension
   * outputs to it.
   *
   * @return the player, which has not started yet
   */
  private VideoPlayerMultiplexer createPlayer(final PlaybackRequest request, final URLParseDump dump) {
    final Pair<Integer, Integer> resolution = request.getResolution();
    final VideoConfigurationProvider configurationProvider = request.getConfigurationProvider();
    final VideoPipelineStep videoPipeline = this.createVideoFilter(resolution, configurationProvider);

    final AudioArgument audioType = request.getAudioType();
    final Player[] viewers = request.getViewers();
    this.manager.checkStart();
    final AudioFilter audioFilter = this.provider.constructFilter(audioType, dump, viewers);
    this.manager.checkStart();
    final AudioPipelineStep audioPipeline = AudioPipelineStep.of(audioFilter);

    final PlayerArgument playerType = request.getPlayerType();
    final VideoPlayerMultiplexer player = playerType.createPlayer();
    this.manager.setPlayer(player);
    attachPipelines(player, videoPipeline, audioPipeline, resolution);
    return player;
  }

  private static void attachPipelines(
    final VideoPlayerMultiplexer player,
    final VideoPipelineStep videoPipeline,
    final AudioPipelineStep audioPipeline,
    final Pair<Integer, Integer> resolution
  ) {
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(videoPipeline);
    final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
    audioCallback.attach(audioPipeline);

    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    final Dimension dimension = new Dimension(width, height);
    final DimensionAttachableCallback dimensionCallback = player.getDimensionAttachableCallback();
    dimensionCallback.attach(dimension);
  }

  /**
   * Starts the player. When it fails to start, the player and its outputs are released at once, so nothing is
   * left on the screens of the viewers.
   *
   * @return true if the player started, false if it refused to start
   */
  private boolean startPlayback(final VideoPlayerMultiplexer player, final SourceSelection selection) {
    final Source video = selection.getVideo();
    final Source audio = selection.getAudio();
    return this.manager.startNative(() -> audio == null ? player.start(video) : player.start(video, audio));
  }

  /** Runs one cleanup after a recoverable failure; the caller explicitly rethrows that original failure. */
  private static void cleanupAfterFailure(final Throwable failure, final Runnable cleanup) {
    try {
      cleanup.run();
    } catch (final RuntimeException | Error cleanupFailure) {
      ThrowableUtils.throwIfFatal(cleanupFailure);
      final Equivalence<Object> identity = Equivalence.identity();
      if (!identity.equivalent(failure, cleanupFailure)) {
        failure.addSuppressed(cleanupFailure);
      }
    }
  }

  private void showHologram(final URLParseDump dump) {
    final Hologram existing = this.manager.getHologram();
    if (existing != null) {
      existing.kill();
      this.manager.setHologram(null);
    }

    final Location location = this.manager.getHologramLocation();
    if (location == null) {
      return;
    }

    final Hologram hologram = Hologram.basic();
    this.manager.setHologram(hologram);
    try {
      hologram.handleRequest(location, dump);
      hologram.start();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.manager.setHologram(null);
      cleanupAfterFailure(exception, hologram::kill);
      throw exception;
    }
  }

  /**
   * Creates the video pipeline that shows the frames. Called on the worker thread, once for every video that
   * starts.
   *
   * @param resolution            the parsed resolution
   * @param configurationProvider the provider passed to {@link #playVideo}
   * @return the pipeline
   */
  public abstract VideoPipelineStep createVideoFilter(
    final Pair<Integer, Integer> resolution,
    final VideoConfigurationProvider configurationProvider
  );

  private static @Nullable SourceSelection selectSources(final String mrl, final String[] ytdlpArguments) {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Optional<Source> detected = helper.detectSource(mrl);
    if (detected.isEmpty()) {
      return null;
    }

    final Source source = detected.get();
    if (source instanceof final UriSource page && !page.isDirect()) {
      return resolveWithYtdlp(page, ytdlpArguments);
    }
    final URLParseDump dump = describeDirectSource(source, mrl);
    return new SourceSelection(source, null, dump);
  }

  /**
   * Describes a source that can be played as it is, without asking yt-dlp: an FFmpeg input, a capture device, a
   * file, or a URL that points directly at a media file.
   *
   * @return the description
   */
  private static URLParseDump describeDirectSource(final Source source, final String mrl) {
    if (source instanceof final FFmpegDirectSource ffmpegSource) {
      final String input = ffmpegSource.getMrl();
      final String format = ffmpegSource.getFormat();
      return createDump(input, "FFmpeg input " + format);
    }
    if (source instanceof final DeviceSource device) {
      final int deviceId = device.getDeviceId();
      return createDump("Device " + deviceId, "Video from a capture device");
    }
    if (source instanceof final FileSource file) {
      final Path path = file.getPath();
      final String fileName = IOUtils.getName(path);
      return createDump(fileName, "Video from a file");
    }
    final String fileName = IOUtils.getFileNameFromUrl(mrl);
    return createDump(fileName, "Video from a URL");
  }

  private static URLParseDump createDump(final String title, final String description) {
    final URLParseDump dump = new URLParseDump();
    dump.title = title;
    dump.description = description;
    return dump;
  }

  private static SourceSelection resolveWithYtdlp(final UriSource page, final String[] ytdlpArguments) {
    final YTDLPParser parser = YTDLPParser.simple();
    final URLParseDump dump;
    try {
      dump = parser.parse(page, ytdlpArguments);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      final URI uri = page.getUri();
      throw new IllegalStateException("yt-dlp could not resolve " + uri + ": " + message, exception);
    }

    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final Format videoFormat = selector.getVideoSource(dump);
    final Format audioFormat = selector.getAudioSource(dump);
    final UriSource video = videoFormat.toUriSource();
    final UriSource audio = audioFormat.toUriSource();
    return new SourceSelection(video, audio, dump);
  }

  /**
   * The video source, the optional separate audio source, and the information shown about the media.
   */
  private static final class SourceSelection {

    private final Source video;
    private final @Nullable Source audio;
    private final URLParseDump dump;

    SourceSelection(final Source video, final @Nullable Source audio, final URLParseDump dump) {
      this.video = video;
      this.audio = audio;
      this.dump = dump;
    }

    Source getVideo() {
      return this.video;
    }

    @Nullable Source getAudio() {
      return this.audio;
    }

    URLParseDump getDump() {
      return this.dump;
    }
  }

  /**
   * Creates the display configuration of a video command for a resolution.
   */
  @FunctionalInterface
  public interface VideoConfigurationProvider {
    /**
     * Creates the configuration.
     *
     * @param resolution the parsed resolution
     * @return the configuration object the subclass expects
     */
    Object buildConfiguration(final Pair<Integer, Integer> resolution);
  }

  /**
   * Everything the worker thread needs to start a video, as entered by the command sender.
   */
  private static final class PlaybackRequest {

    private final PlayerArgument playerType;
    private final AudioArgument audioType;
    private final String mrl;
    private final String[] ytdlpArguments;
    private final Pair<Integer, Integer> resolution;
    private final VideoConfigurationProvider configurationProvider;
    private final Player[] viewers;

    PlaybackRequest(
      final PlayerArgument playerType,
      final AudioArgument audioType,
      final String mrl,
      final String[] ytdlpArguments,
      final Pair<Integer, Integer> resolution,
      final VideoConfigurationProvider configurationProvider,
      final Player[] viewers
    ) {
      this.playerType = playerType;
      this.audioType = audioType;
      this.mrl = mrl;
      this.ytdlpArguments = ytdlpArguments;
      this.resolution = resolution;
      this.configurationProvider = configurationProvider;
      this.viewers = viewers;
    }

    PlayerArgument getPlayerType() {
      return this.playerType;
    }

    AudioArgument getAudioType() {
      return this.audioType;
    }

    String getMrl() {
      return this.mrl;
    }

    String[] getYtdlpArguments() {
      return this.ytdlpArguments;
    }

    Pair<Integer, Integer> getResolution() {
      return this.resolution;
    }

    VideoConfigurationProvider getConfigurationProvider() {
      return this.configurationProvider;
    }

    Player[] getViewers() {
      return this.viewers;
    }
  }
}
