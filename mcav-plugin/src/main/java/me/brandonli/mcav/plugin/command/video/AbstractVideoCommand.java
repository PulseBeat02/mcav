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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import me.brandonli.mcav.bukkit.hologram.Hologram;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.json.ytdlp.YtdlpParseException;
import me.brandonli.mcav.json.ytdlp.YtdlpParser;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
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
import me.brandonli.mcav.media.source.ffmpeg.FfmpegDirectSource;
import me.brandonli.mcav.media.source.ffmpeg.FfmpegDirectSourceDetector;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.audio.AudioOutputs;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.command.AnnotationCommandFeature;
import me.brandonli.mcav.plugin.command.MediaUnavailableException;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.ArgumentUtils;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.CaptureDevices;
import me.brandonli.mcav.plugin.utils.FilterChain;
import me.brandonli.mcav.plugin.utils.MediaKind;
import me.brandonli.mcav.plugin.utils.PlayerArgument;
import me.brandonli.mcav.plugin.utils.TaskUtils;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.InputMetadataException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.MetadataUtils;
import me.brandonli.mcav.utils.SourceUtils;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.immutable.Dimension;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.runtime.ProcessException;
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
 *
 * <p>This class is not thread-safe: use it on the server main thread; the media resolves and starts on the video player
 * manager's worker, which calls {@link #createVideoFilter}.
 *
 * @since 1.0.0
 */
public abstract class AbstractVideoCommand implements AnnotationCommandFeature {

  private static final String SENDER_NULL_MESSAGE = "Sender must not be null!";
  private static final String TEXT_NULL_MESSAGE = "Text must not be null!";

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractVideoCommand.class);

  private static final FfmpegDirectSourceDetector RAW_INPUTS = new FfmpegDirectSourceDetector();

  private static final String HTTP_SCHEME = "http";
  private static final String HTTPS_SCHEME = "https";

  /**
   * The plugin.
   *
   * @since 1.0.0
   */
  protected final MCAVSandbox plugin;

  private final AudioProvider provider;

  /**
   * The manager of the running video player.
   *
   * @since 1.0.0
   */
  protected final VideoPlayerManager manager;

  /**
   * Creates the command.
   *
   * @param plugin the plugin, whose video player manager and audio provider must be available
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  protected AbstractVideoCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");

    this.plugin = plugin;
    this.manager = plugin.getVideoPlayerManager();
    this.provider = plugin.getAudioProvider();
  }

  /**
   * Plays a video, replacing the video played before.
   *
   * <p>The resolution, an empty source, the player backend, and the audio output are checked first; any problem is
   * reported to the sender and nothing starts. Only one video can start at a time. The viewers are then told that the
   * video is loading, and the media is resolved and started on the worker thread of the video player manager.
   *
   * <p>A video that is not a file on the server, a URL, a device index or an FFmpeg input, that the player cannot open,
   * or whose web page yt-dlp cannot resolve is refused, and the sender is told why, with no stack trace in the console.
   * So is a web page whose stream yt-dlp reports with a scheme other than {@code http} or {@code https}. Any other
   * failure while starting is logged with its stack trace, and the sender is told that the console has the details.
   *
   * @param configurationProvider creates the display configuration for the parsed resolution
   * @param sender                who ran the command
   * @param selector              the players who watch
   * @param playerType            the player backend
   * @param audioType             the audio output
   * @param videoResolution       the resolution argument, such as {@code 640x640}
   * @param mrl                   the path, URL, device index, or FFmpeg input
   * @param flags                 the optional flags, may be empty
   * @param filters               the {@code --filters} option, see {@link FilterChain}, or {@code null} for none
   * @throws NullPointerException if an argument other than {@code filters} is null
   * @since 1.0.0
   */
  public void playVideo(
    final VideoConfigurationProvider configurationProvider,
    final CommandSender sender,
    final MultiplePlayerSelector selector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    final String videoResolution,
    final String mrl,
    final String flags,
    final @Nullable String filters
  ) {
    Preconditions.checkNotNull(configurationProvider, "Configuration provider must not be null!");
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);
    Preconditions.checkNotNull(selector, "Player selector must not be null!");
    Preconditions.checkNotNull(playerType, "Player type must not be null!");
    Preconditions.checkNotNull(audioType, "Audio type must not be null!");
    Preconditions.checkNotNull(videoResolution, "Video resolution must not be null!");
    Preconditions.checkNotNull(mrl, "MRL must not be null!");
    Preconditions.checkNotNull(flags, "Flags must not be null!");

    final Optional<Pair<Integer, Integer>> parsedResolution = parseDimensions(sender, videoResolution);
    final @Nullable Pair<Integer, Integer> resolution = parsedResolution.orElse(null);
    if (resolution == null) {
      return;
    }

    final String @Nullable [] ytdlpArguments = this.parseFlags(sender, flags);
    if (ytdlpArguments == null) {
      return;
    }

    final Optional<FilterChain> parsedChain = parseFilters(sender, this.plugin, filters, MediaKind.VIDEO);
    final @Nullable FilterChain chain = parsedChain.orElse(null);
    if (chain == null) {
      return;
    }

    // a blank MRL would be detected as the working folder
    if (mrl.isBlank()) {
      final Component message = Message.UNSUPPORTED_MRL.build();
      sender.sendMessage(message);
      return;
    }

    final @Nullable Component problem = this.findBackendProblem(sender, playerType, audioType, mrl);
    if (problem != null) {
      sender.sendMessage(problem);
      return;
    }

    final boolean claimed = this.claim();
    if (!claimed) {
      final Component message = Message.PLAYER_ERROR.build();
      sender.sendMessage(message);
      return;
    }

    final Function<Player[], PlaybackRequest> requestFactory = viewers ->
      new PlaybackRequest(playerType, audioType, mrl, ytdlpArguments, resolution, configurationProvider, viewers, chain);
    this.launchForViewers(sender, selector, requestFactory);
  }

  private void launchForViewers(
    final CommandSender sender,
    final MultiplePlayerSelector selector,
    final Function<Player[], PlaybackRequest> requestFactory
  ) {
    try {
      final Player[] viewers = this.collectViewers(selector);
      this.notifyLoading(viewers);
      final PlaybackRequest request = requestFactory.apply(viewers);
      this.launch(sender, request);
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      final AtomicBoolean starting = this.manager.getStarting();
      starting.set(false);
      throw exception;
    }
  }

  /**
   * Parses the {@code --filters} option of a video or image command, telling the sender when it is not valid.
   *
   * @param sender  who ran the command
   * @param plugin  the plugin, whose data folder holds the overlays
   * @param filters the option as typed, or {@code null} for none
   * @param kind    what the pictures come from
   * @return the chain, or empty if the option is not valid
   * @throws NullPointerException if an argument other than {@code filters} is null
   * @since 1.0.0
   */
  public static Optional<FilterChain> parseFilters(
    final CommandSender sender,
    final MCAVSandbox plugin,
    final @Nullable String filters,
    final MediaKind kind
  ) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");
    Preconditions.checkNotNull(kind, "Media kind must not be null!");

    if (filters == null || filters.isEmpty()) {
      return Optional.of(FilterChain.NONE);
    }

    final File dataFolder = plugin.getDataFolder();
    final Path dataPath = dataFolder.toPath();
    final Path overlays = dataPath.resolve(FilterChain.OVERLAY_FOLDER);
    try {
      final FilterChain chain = FilterChain.parse(filters, overlays, kind);
      return Optional.of(chain);
    } catch (final IllegalArgumentException exception) {
      final String cause = exception.getMessage();
      final String reason = Objects.requireNonNullElse(cause, "");
      final Component message = Message.FILTERS_INVALID.build(reason);
      sender.sendMessage(message);
      return Optional.empty();
    }
  }

  /**
   * Parses a size such as {@code 640x360}, telling the sender when it is not valid.
   *
   * @param sender who ran the command
   * @param text   the size as entered
   * @return the width and height, or empty if the text is not a valid size
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  protected static Optional<Pair<Integer, Integer>> parseDimensions(final CommandSender sender, final String text) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);
    Preconditions.checkNotNull(text, TEXT_NULL_MESSAGE);

    try {
      final Pair<Integer, Integer> dimensions = ArgumentUtils.parseDimensions(text);
      return Optional.of(dimensions);
    } catch (final IllegalArgumentException exception) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return Optional.empty();
    }
  }

  /**
   * Parses the size of a wall of maps such as {@code 5x5}, telling the sender when it is not valid or larger than
   * {@link ArgumentUtils#parseScreenDimensions(String)} allows.
   *
   * @param sender who ran the command
   * @param text   the size as entered
   * @return the width and height in maps, or empty if the text is not a valid wall size
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  protected static Optional<Pair<Integer, Integer>> parseScreenDimensions(final CommandSender sender, final String text) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);
    Preconditions.checkNotNull(text, TEXT_NULL_MESSAGE);

    try {
      final Pair<Integer, Integer> dimensions = ArgumentUtils.parseScreenDimensions(text);
      return Optional.of(dimensions);
    } catch (final IllegalArgumentException exception) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return Optional.empty();
    }
  }

  private boolean claim() {
    final AtomicBoolean starting = this.manager.getStarting();
    return starting.compareAndSet(false, true);
  }

  private Player[] collectViewers(final MultiplePlayerSelector selector) {
    final Collection<Player> players = selector.values();
    return players.toArray(new Player[0]);
  }

  private void notifyLoading(final Player[] viewers) {
    final Component loading = Message.LOAD_VIDEO.build();
    for (final Player viewer : viewers) {
      viewer.sendMessage(loading);
    }
  }

  private String @Nullable [] parseFlags(final CommandSender sender, final String flags) {
    final VideoFlagsParser flagsParser = new VideoFlagsParser();
    try {
      return flagsParser.parseYtdlpFlags(flags);
    } catch (final IllegalArgumentException exception) {
      final String cause = exception.getMessage();
      final String reason = Objects.requireNonNullElse(cause, "The flags are not valid");
      final Component message = Message.UNSUPPORTED_FLAGS.build(reason);
      sender.sendMessage(message);
      return null;
    }
  }

  private void launch(final CommandSender sender, final PlaybackRequest request) {
    final AtomicBoolean starting = this.manager.getStarting();
    final ExecutorService service = this.manager.getService();
    final CompletableFuture<Void> start;
    final long generation;
    try {
      generation = this.manager.beginStart();
      start = CompletableFuture.runAsync(() -> this.startPlayer(request, generation), service);
    } catch (final RejectedExecutionException | CancellationException exception) {
      starting.set(false);
      LOGGER.error("The video worker refused to start a video.", exception);
      final Component message = Message.VIDEO_START_ERROR.build();
      sender.sendMessage(message);
      return;
    }

    TaskUtils.whenComplete(start, (_, failure) -> this.finishLaunch(sender, request, generation, failure));
  }

  private void finishLaunch(
    final CommandSender sender,
    final PlaybackRequest request,
    final long generation,
    final @Nullable Throwable failure
  ) {
    final AtomicBoolean starting = this.manager.getStarting();
    starting.set(false);
    TaskUtils.runOnMainThread(this.plugin, () -> this.reportIfCurrent(sender, request, generation, failure));
  }

  private void reportIfCurrent(
    final CommandSender sender,
    final PlaybackRequest request,
    final long generation,
    final @Nullable Throwable failure
  ) {
    if (this.manager.isCurrent(generation)) {
      final AudioArgument audioType = request.getAudioType();
      final Player[] viewers = request.getViewers();
      this.report(sender, audioType, viewers, failure);
    }
  }

  private void report(
    final CommandSender sender,
    final AudioArgument audioType,
    final Player[] viewers,
    final @Nullable Throwable failure
  ) {
    if (failure != null) {
      this.reportFailure(sender, failure);
      return;
    }

    AudioOutputs.sendLink(this.provider, audioType, viewers);
    final Component message = Message.START_VIDEO.build();
    sender.sendMessage(message);
  }

  private void reportFailure(final CommandSender sender, final Throwable failure) {
    // runAsync wraps what the worker throws in a CompletionException
    final @Nullable Throwable cause = failure.getCause();
    if (cause instanceof final StreamRefusedException refusal) {
      this.reportRefusal(sender, refusal);
      return;
    }

    if (cause instanceof final MediaUnavailableException unavailable) {
      this.reportUnavailable(sender, unavailable);
      return;
    }

    LOGGER.error("Failed to play a video.", failure);
    final Component message = Message.VIDEO_START_ERROR.build();
    sender.sendMessage(message);
  }

  private void reportRefusal(final CommandSender sender, final StreamRefusedException refusal) {
    final @Nullable String detail = refusal.getMessage();
    final String reason = String.valueOf(detail);
    final Component message = Message.VIDEO_STREAM_REFUSED.build(reason);
    sender.sendMessage(message);
  }

  private void reportUnavailable(final CommandSender sender, final MediaUnavailableException unavailable) {
    final @Nullable String detail = unavailable.getMessage();
    final String reason = String.valueOf(detail);
    final Component message = Message.VIDEO_UNAVAILABLE.build(reason);
    sender.sendMessage(message);
  }

  private @Nullable Component findBackendProblem(
    final CommandSender sender,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    final String mrl
  ) {
    final @Nullable Component deviceProblem = this.findDeviceProblem(sender, playerType, mrl);
    return deviceProblem != null ? deviceProblem : this.findProblem(playerType, audioType, mrl);
  }

  // DEVICE players and raw FFmpeg inputs (format||input) can open the server's cameras and microphones
  private @Nullable Component findDeviceProblem(final CommandSender sender, final PlayerArgument playerType, final String mrl) {
    final boolean isDevice = playerType == PlayerArgument.DEVICE;
    if (!isDevice && !RAW_INPUTS.isDetectedSource(mrl)) {
      return null;
    }

    if (!sender.hasPermission(CaptureDevices.PERMISSION)) {
      return Message.DEVICE_PERMISSION.build();
    }

    if (!isDevice) {
      return null;
    }

    final List<CaptureDevices.Device> devices = this.manager.getDevices();
    final Optional<CaptureDevices.Device> device = CaptureDevices.find(devices, mrl);
    final boolean unlisted = device.isEmpty();
    return unlisted ? Message.DEVICE_UNLISTED.build(mrl) : null;
  }

  private @Nullable Component findProblem(final PlayerArgument playerType, final AudioArgument audioType, final String mrl) {
    final Optional<Component> foundAudioProblem = AudioOutputs.findProblem(this.provider, audioType);
    final @Nullable Component audioProblem = foundAudioProblem.orElse(null);
    if (audioProblem != null) {
      return audioProblem;
    }

    final @Nullable Component playerProblem = this.findPlayerProblem(playerType);
    if (playerProblem != null) {
      return playerProblem;
    }

    return this.findYtdlpProblem(mrl);
  }

  // VLC is prepared in the background, so it may still be downloading or unavailable
  private @Nullable Component findPlayerProblem(final PlayerArgument playerType) {
    if (playerType != PlayerArgument.VLC) {
      return null;
    }

    final boolean preparing = this.manager.isPreparing(Capability.VLC);
    if (preparing) {
      return Message.VLC_PREPARING.build();
    }

    final boolean supported = this.manager.isVlcSupported();
    if (!supported) {
      return Message.UNSUPPORTED_PLAYER.build();
    }

    return null;
  }

  // yt-dlp is prepared in the background, so it may still be downloading or unavailable
  private @Nullable Component findYtdlpProblem(final String mrl) {
    final boolean url = SourceUtils.isUri(mrl);
    final boolean direct = SourceUtils.isDirectMedia(mrl);
    if (!url || direct) {
      return null;
    }

    final boolean preparing = this.manager.isPreparing(Capability.YT_DLP);
    return preparing ? Message.YTDLP_PREPARING.build() : null;
  }

  private void startPlayer(final PlaybackRequest request, final long generation) {
    this.manager.checkStart();
    final String mrl = request.getMrl();
    final String[] ytdlpArguments = request.getYtdlpArguments();
    final @Nullable SourceSelection selection = this.selectSources(mrl, ytdlpArguments);
    this.manager.checkStart();

    if (selection == null) {
      final String message = "Video %s is not a file on the server, a URL, a device index or an FFmpeg input!".formatted(mrl);
      throw new MediaUnavailableException(message);
    }

    this.manager.clearCurrentVideo();
    this.manager.checkStart();
    final UrlParseDump dump = selection.getDump();
    try {
      final VideoPlayerMultiplexer player = this.createPlayer(request, dump);
      this.startPlayback(player, request, selection);
      TaskUtils.runOnMainThread(this.plugin, () -> this.showHologramIfCurrent(generation, dump));
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, this.manager::clearCurrentVideo);
      throw exception;
    }
  }

  private void showHologramIfCurrent(final long generation, final UrlParseDump dump) {
    if (this.manager.isCurrent(generation)) {
      this.showHologram(dump);
    }
  }

  private VideoPlayerMultiplexer createPlayer(final PlaybackRequest request, final UrlParseDump dump) {
    final Pair<Integer, Integer> resolution = request.getResolution();
    final VideoConfigurationProvider configurationProvider = request.getConfigurationProvider();
    final FilterChain filters = request.getFilters();
    final VideoPipelineStep display = this.createVideoFilter(resolution, configurationProvider);
    final VideoPipelineStep videoPipeline = filters.prepend(display);

    final AudioArgument audioType = request.getAudioType();
    final Player[] viewers = request.getViewers();
    this.manager.checkStart();
    final AudioFilter audioFilter = this.provider.constructFilter(audioType, dump, viewers);
    this.manager.checkStart();
    final AudioPipelineStep output = AudioPipelineStep.of(audioFilter);
    final AudioPipelineStep audioPipeline = AudioPipelineStep.of(output, this.manager.getVolume());

    final PlayerArgument playerType = request.getPlayerType();
    final VideoPlayerMultiplexer player = playerType.createPlayer();
    this.manager.setPlayer(player);
    this.attachPipelines(player, videoPipeline, audioPipeline, resolution);
    return player;
  }

  private void attachPipelines(
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

  private void startPlayback(final VideoPlayerMultiplexer player, final PlaybackRequest request, final SourceSelection selection) {
    final Source video = selection.getVideo();
    final @Nullable Source audio = selection.getAudio();
    // the FFmpeg player keeps this handler after the start, so a flag decides which failures stay quiet
    final AtomicBoolean opening = new AtomicBoolean(true);
    player.setExceptionHandler((message, failure) -> this.logPlayerFailure(opening, message, failure));
    final boolean playing = this.manager.startNative(() -> this.startSources(player, video, audio));
    opening.set(false);

    if (!playing) {
      final String mrl = request.getMrl();
      final PlayerArgument playerType = request.getPlayerType();
      final String message = "Video %s could not be opened by the %s player!".formatted(mrl, playerType);
      throw new MediaUnavailableException(message);
    }
  }

  private void logPlayerFailure(final AtomicBoolean opening, final String message, final Throwable failure) {
    if (opening.get()) {
      LOGGER.debug("{}", message, failure);
      return;
    }

    LOGGER.error("{}", message, failure);
  }

  private boolean startSources(final VideoPlayerMultiplexer player, final Source video, final @Nullable Source audio) {
    if (audio == null) {
      return player.start(video);
    }

    return player.start(video, audio);
  }

  private void cleanupAfterFailure(final Throwable failure, final Runnable cleanup) {
    try {
      cleanup.run();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      final Equivalence<Object> identity = Equivalence.identity();
      if (!identity.equivalent(failure, exception)) {
        failure.addSuppressed(exception);
      }
    }
  }

  private void showHologram(final UrlParseDump dump) {
    final Optional<Hologram> foundHologram = this.manager.getHologram();
    final @Nullable Hologram existing = foundHologram.orElse(null);
    if (existing != null) {
      existing.kill();
      this.manager.setHologram(null);
    }

    final Optional<Location> foundLocation = this.manager.getHologramLocation();
    final @Nullable Location location = foundLocation.orElse(null);
    if (location == null) {
      return;
    }

    final Hologram hologram = Hologram.basic();
    this.manager.setHologram(hologram);
    try {
      hologram.spawn(location, dump);
      hologram.start();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.manager.setHologram(null);
      this.cleanupAfterFailure(exception, hologram::kill);
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
   * @throws CancellationException if the video startup was cancelled or the manager is shut down
   * @since 1.0.0
   */
  public abstract VideoPipelineStep createVideoFilter(
    final Pair<Integer, Integer> resolution,
    final VideoConfigurationProvider configurationProvider
  );

  private @Nullable SourceSelection selectSources(final String mrl, final String[] ytdlpArguments) {
    final SourceDetectionHelper helper = new SourceDetectionHelper();
    final Optional<Source> detected = helper.detectSource(mrl);
    final @Nullable Source source = detected.orElse(null);
    if (source == null) {
      return null;
    }

    if (source instanceof final UriSource page && !page.isDirect()) {
      return this.resolveWithYtdlp(page, ytdlpArguments);
    }

    final UrlParseDump dump = this.describeDirectSource(source, mrl);
    return new SourceSelection(source, null, dump);
  }

  private UrlParseDump describeDirectSource(final Source source, final String mrl) {
    if (source instanceof final FfmpegDirectSource ffmpegSource) {
      final String input = ffmpegSource.getMrl();
      final String format = ffmpegSource.getFormat();
      return this.createDump(input, "FFmpeg input %s".formatted(format));
    }

    if (source instanceof final DeviceSource device) {
      final int deviceId = device.getDeviceId();
      return this.createDump("Device %s".formatted(deviceId), "Video from a capture device");
    }

    if (source instanceof final FileSource file) {
      final Path path = file.getPath();
      final String fileName = IoUtils.getName(path);
      final Duration duration = this.readDuration(file);
      return UrlParseDump.of(fileName, "Video from a file", duration);
    }

    final String fileName = IoUtils.getFileNameFromUrl(mrl);
    return this.createDump(fileName, "Video from a URL");
  }

  private UrlParseDump createDump(final String title, final String description) {
    return UrlParseDump.of(title, description);
  }

  // a file FFmpeg cannot read may still play in VLC, so its length stays unknown
  private Duration readDuration(final FileSource file) {
    try {
      final Optional<Duration> duration = MetadataUtils.parseDuration(file);
      return requireNonNull(duration.orElse(Duration.ZERO)); // checker framework
    } catch (final InputMetadataException exception) {
      final Path path = file.getPath();
      LOGGER.debug("The length of {} could not be read, so its hologram shows no progress.", path, exception);
      return Duration.ZERO;
    }
  }

  private SourceSelection resolveWithYtdlp(final UriSource page, final String[] ytdlpArguments) {
    final YtdlpParser parser = YtdlpParser.simple();
    final UrlParseDump dump;
    try {
      dump = parser.parse(page, ytdlpArguments);
    } catch (final InstallationException | DownloadException | FileOperationException | ProcessException exception) {
      final String reason = exception.getMessage();
      final URI uri = page.getUri();
      final String message = "Because of \"%s\", page %s could not be resolved by yt-dlp!".formatted(reason, uri);
      throw new PluginException(message, exception);
    } catch (final YtdlpParseException | IllegalArgumentException exception) {
      final @Nullable String detail = exception.getMessage();
      final String message = String.valueOf(detail);
      throw new MediaUnavailableException(message, exception);
    }

    return this.selectStreams(page, dump);
  }

  private SourceSelection selectStreams(final UriSource page, final UrlParseDump dump) {
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final Format videoFormat = selector.getVideoSource(dump);
    final UriSource video = videoFormat.toUriSource();
    this.checkWebStream(page, video);

    final FormatStrategy audioStrategy = selector.getAudioStrategy();
    final Optional<Format> separateAudio = audioStrategy.select(dump);
    final @Nullable Format separateFormat = separateAudio.orElse(null);
    final Format audioFormat = separateFormat == null ? videoFormat : separateFormat;
    final UriSource audio = audioFormat.toUriSource();
    this.checkWebStream(page, audio);
    return new SourceSelection(video, audio, dump);
  }

  // the page chooses the stream URLs, and FFmpeg and VLC would open server files or local services too
  private void checkWebStream(final UriSource page, final UriSource stream) {
    final URI uri = stream.getUri();
    final String scheme = uri.getScheme();
    final boolean web = HTTP_SCHEME.equalsIgnoreCase(scheme) || HTTPS_SCHEME.equalsIgnoreCase(scheme);
    if (!web) {
      final URI pageUri = page.getUri();
      final String message = "yt-dlp reported a stream of %s whose scheme %s is not http or https!".formatted(pageUri, scheme);
      throw new StreamRefusedException(message);
    }
  }

  private static final class SourceSelection {

    private final Source video;
    private final @Nullable Source audio;
    private final UrlParseDump dump;

    SourceSelection(final Source video, final @Nullable Source audio, final UrlParseDump dump) {
      this.video = video;
      this.audio = audio;
      this.dump = dump;
    }

    private Source getVideo() {
      return this.video;
    }

    private @Nullable Source getAudio() {
      return this.audio;
    }

    private UrlParseDump getDump() {
      return this.dump;
    }
  }

  /**
   * Creates the display configuration of a video command for a resolution.
   *
   * <p>Implementations in this library are immutable and thread-safe.
   *
   * @since 1.0.0
   */
  @FunctionalInterface
  public interface VideoConfigurationProvider {
    /**
     * Creates the configuration.
     *
     * @param resolution the parsed resolution
     * @return the configuration object the subclass expects
     * @since 1.0.0
     */
    Object buildConfiguration(final Pair<Integer, Integer> resolution);
  }

  private static final class PlaybackRequest {

    private final PlayerArgument playerType;
    private final AudioArgument audioType;
    private final String mrl;
    private final String[] ytdlpArguments;
    private final Pair<Integer, Integer> resolution;
    private final VideoConfigurationProvider configurationProvider;
    private final Player[] viewers;
    private final FilterChain filters;

    PlaybackRequest(
      final PlayerArgument playerType,
      final AudioArgument audioType,
      final String mrl,
      final String[] ytdlpArguments,
      final Pair<Integer, Integer> resolution,
      final VideoConfigurationProvider configurationProvider,
      final Player[] viewers,
      final FilterChain filters
    ) {
      this.playerType = playerType;
      this.audioType = audioType;
      this.mrl = mrl;
      this.ytdlpArguments = ytdlpArguments;
      this.resolution = resolution;
      this.configurationProvider = configurationProvider;
      this.viewers = viewers;
      this.filters = filters;
    }

    private PlayerArgument getPlayerType() {
      return this.playerType;
    }

    private AudioArgument getAudioType() {
      return this.audioType;
    }

    private String getMrl() {
      return this.mrl;
    }

    private String[] getYtdlpArguments() {
      return this.ytdlpArguments;
    }

    private Pair<Integer, Integer> getResolution() {
      return this.resolution;
    }

    private VideoConfigurationProvider getConfigurationProvider() {
      return this.configurationProvider;
    }

    private Player[] getViewers() {
      return this.viewers;
    }

    private FilterChain getFilters() {
      return this.filters;
    }
  }
}
