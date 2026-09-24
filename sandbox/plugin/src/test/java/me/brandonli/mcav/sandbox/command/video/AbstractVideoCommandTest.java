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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.device.DeviceSource;
import me.brandonli.mcav.media.source.ffmpeg.FFmpegDirectSource;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.StandardErrorCapture;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Dimension;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link AbstractVideoCommand} with a video output that only records what it is asked to create.
 *
 * <p>The worker thread of the video manager is replaced by the calling thread, so every step runs before
 * {@code playVideo} returns. The players, holograms and yt-dlp are mocked.
 */
final class AbstractVideoCommandTest {

  private static final String WEB_PAGE = "https://www.youtube.com/watch?v=abc";

  @TempDir
  private Path folder;

  private final ExecutorService directExecutor = MoreExecutors.newDirectExecutorService();
  private final AtomicBoolean status = new AtomicBoolean();

  private VideoPlayerManager manager;
  private AudioProvider provider;
  private RecordingCommand command;
  private CommandSender sender;
  private Player viewer;
  private MultiplePlayerSelector selector;
  private VideoPlayerMultiplexer player;
  private VideoAttachableCallback videoCallback;
  private AudioAttachableCallback audioCallback;
  private DimensionAttachableCallback dimensionCallback;
  private Hologram hologram;
  private YTDLPParser parser;
  private MockedStatic<VideoPlayer> videoPlayers;
  private MockedStatic<Hologram> holograms;
  private MockedStatic<YTDLPParser> parsers;

  /**
   * A command whose video output is a mocked filter and that remembers what it was asked to create.
   */
  private static final class RecordingCommand extends AbstractVideoCommand {

    private final VideoPipelineStep pipeline;
    private final List<Pair<Integer, Integer>> resolutions;
    private final List<VideoConfigurationProvider> providers;

    RecordingCommand(final MCAVSandbox plugin) {
      super(plugin);
      final FunctionalVideoFilter filter = mock(FunctionalVideoFilter.class);
      this.pipeline = VideoPipelineStep.of(filter);
      this.resolutions = new ArrayList<>();
      this.providers = new ArrayList<>();
    }

    @Override
    public VideoPipelineStep createVideoFilter(
      final Pair<Integer, Integer> resolution,
      final VideoConfigurationProvider configurationProvider
    ) {
      this.resolutions.add(resolution);
      this.providers.add(configurationProvider);
      configurationProvider.buildConfiguration(resolution);
      return this.pipeline;
    }
  }

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    this.manager = mock(VideoPlayerManager.class);
    this.provider = mock(AudioProvider.class);
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(plugin.getAudioProvider()).thenReturn(this.provider);
    when(this.manager.getStatus()).thenReturn(this.status);
    when(this.manager.getService()).thenReturn(this.directExecutor);
    when(this.manager.isCurrent(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    when(this.manager.startNative(any(java.util.function.BooleanSupplier.class))).thenAnswer(invocation -> {
      final java.util.function.BooleanSupplier start = invocation.getArgument(0);
      return start.getAsBoolean();
    });
    when(this.manager.isVLCSupported()).thenReturn(true);
    when(this.provider.constructFilter(any(), any(), any())).thenReturn(AudioFilter.NO_OP);
    this.command = new RecordingCommand(plugin);

    this.sender = mock(CommandSender.class);
    this.viewer = mock(Player.class);
    this.selector = mock(MultiplePlayerSelector.class);
    final List<Player> viewers = List.of(this.viewer);
    when(this.selector.values()).thenReturn(viewers);
    this.mockPlayers();
    this.mockHologramsAndYtdlp();
  }

  private void mockPlayers() {
    this.player = mock(VideoPlayerMultiplexer.class);
    this.videoCallback = mock(VideoAttachableCallback.class);
    this.audioCallback = mock(AudioAttachableCallback.class);
    this.dimensionCallback = mock(DimensionAttachableCallback.class);
    when(this.player.getVideoAttachableCallback()).thenReturn(this.videoCallback);
    when(this.player.getAudioAttachableCallback()).thenReturn(this.audioCallback);
    when(this.player.getDimensionAttachableCallback()).thenReturn(this.dimensionCallback);
    when(this.player.start(any())).thenReturn(true);
    when(this.player.start(any(), any())).thenReturn(true);
    this.videoPlayers = Mockito.mockStatic(VideoPlayer.class);
    this.videoPlayers.when(VideoPlayer::ffmpeg).thenReturn(this.player);
    this.videoPlayers.when(VideoPlayer::device).thenReturn(this.player);
    this.videoPlayers.when(VideoPlayer::vlc).thenReturn(this.player);
  }

  private void mockHologramsAndYtdlp() {
    this.hologram = mock(Hologram.class);
    this.holograms = Mockito.mockStatic(Hologram.class);
    this.holograms.when(Hologram::basic).thenReturn(this.hologram);
    this.parser = mock(YTDLPParser.class);
    this.parsers = Mockito.mockStatic(YTDLPParser.class);
    this.parsers.when(YTDLPParser::simple).thenReturn(this.parser);
  }

  @AfterEach
  void closeMocks() {
    this.videoPlayers.close();
    this.holograms.close();
    this.parsers.close();
    this.directExecutor.shutdown();
  }

  private Path createVideoFile() throws IOException {
    final Path file = this.folder.resolve("clip.mp4");
    Files.writeString(file, "video");
    return file;
  }

  private void play(final PlayerArgument playerType, final AudioArgument audioType, final String mrl, final String flags) {
    this.command.playVideo(_ -> "configuration", this.sender, this.selector, playerType, audioType, "640x360", mrl, flags);
  }

  private void assertSenderReceived(final Component... expected) {
    final List<Component> messages = Components.received(this.sender);
    final List<Component> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, messages);
  }

  private void assertViewerReceived(final Component... expected) {
    final List<Component> messages = Components.received(this.viewer);
    final List<Component> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, messages);
  }

  private void assertNotStarting() {
    final boolean starting = this.status.get();
    assertFalse(starting);
  }

  private URLParseDump verifyPlayedWithDump(final AudioArgument audioType) {
    final ArgumentCaptor<URLParseDump> dumps = ArgumentCaptor.forClass(URLParseDump.class);
    final Object[] viewers = { this.viewer };
    verify(this.provider).constructFilter(eq(audioType), dumps.capture(), aryEq(viewers));
    return dumps.getValue();
  }

  private Source verifyStartedWithOneSource() {
    final ArgumentCaptor<Source> sources = ArgumentCaptor.forClass(Source.class);
    verify(this.player).start(sources.capture());
    verify(this.player, never()).start(any(), any());
    return sources.getValue();
  }

  private void assertOutputsAttached() {
    verify(this.manager).setPlayer(this.player);
    verify(this.videoCallback).attach(this.command.pipeline);
    final ArgumentCaptor<AudioPipelineStep> audioSteps = ArgumentCaptor.forClass(AudioPipelineStep.class);
    verify(this.audioCallback).attach(audioSteps.capture());
    final AudioPipelineStep audioStep = audioSteps.getValue();
    final AudioFilter audioFilter = audioStep.getFilter();
    assertSame(AudioFilter.NO_OP, audioFilter);
    final Dimension dimension = new Dimension(640, 360);
    verify(this.dimensionCallback).attach(dimension);
  }

  private void assertFilterCreatedForTheResolution() {
    final Pair<Integer, Integer> resolution = this.command.resolutions.getFirst();
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    assertEquals(640, width);
    assertEquals(360, height);
    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.command.providers.getFirst();
    final Object configuration = configurationProvider.buildConfiguration(resolution);
    assertEquals("configuration", configuration);
  }

  private static StrategySelector mockStrategies(final URLParseDump resolved, final UriSource videoSource, final UriSource audioSource) {
    final Format video = mock(Format.class);
    final Format audio = mock(Format.class);
    when(video.toUriSource()).thenReturn(videoSource);
    when(audio.toUriSource()).thenReturn(audioSource);
    final StrategySelector strategies = mock(StrategySelector.class);
    when(strategies.getVideoSource(resolved)).thenReturn(video);
    when(strategies.getAudioSource(resolved)).thenReturn(audio);
    return strategies;
  }

  private void assertResolvedTheWebPage() throws IOException {
    final ArgumentCaptor<UriSource> pages = ArgumentCaptor.forClass(UriSource.class);
    verify(this.parser).parse(pages.capture(), eq("--format"), eq("best"));
    final UriSource page = pages.getValue();
    final URI pageUri = page.getUri();
    final URI expected = URI.create(WEB_PAGE);
    assertEquals(expected, pageUri);
  }

  private void assertStartedWithStreams(final Source expectedVideo, final Source expectedAudio) {
    final ArgumentCaptor<Source> videoSources = ArgumentCaptor.forClass(Source.class);
    final ArgumentCaptor<Source> audioSources = ArgumentCaptor.forClass(Source.class);
    verify(this.player).start(videoSources.capture(), audioSources.capture());
    final Source videoSource = videoSources.getValue();
    final Source audioSource = audioSources.getValue();
    assertSame(expectedVideo, videoSource);
    assertSame(expectedAudio, audioSource);
  }

  @Test
  void playsAVideoFile() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    final Component loading = Message.LOAD_VIDEO.build();
    verify(this.viewer).sendMessage(loading);
    verify(this.manager).clearCurrentVideo();
    final URLParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);
    assertEquals("clip.mp4", dump.title);
    assertEquals("Video from a file", dump.description);
    this.assertOutputsAttached();
    final Source source = this.verifyStartedWithOneSource();
    final FileSource fileSource = assertInstanceOf(FileSource.class, source);
    final Path played = fileSource.getPath();
    assertEquals(file, played);
    this.assertFilterCreatedForTheResolution();
    this.holograms.verifyNoInteractions();
    final Component started = Message.START_VIDEO.build();
    this.assertSenderReceived(started);
    this.assertNotStarting();
  }

  @Test
  void showsTheTitleOnTheHologramAtTheConfiguredLocation() throws IOException {
    final Location location = new Location(null, 0.0, 70.0, 0.0);
    when(this.manager.getHologramLocation()).thenReturn(location);
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    final URLParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);
    final InOrder order = inOrder(this.hologram, this.manager);
    order.verify(this.manager).setHologram(this.hologram);
    order.verify(this.hologram).handleRequest(location, dump);
    order.verify(this.hologram).start();
  }

  @Test
  void replacesTheHologramOfThePreviousVideo() throws IOException {
    final Hologram previous = mock(Hologram.class);
    when(this.manager.getHologram()).thenReturn(previous);
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    verify(previous).kill();
    verify(this.manager).setHologram(null);
    this.holograms.verifyNoInteractions();
  }

  @Test
  void playsAnFFmpegInput() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "dshow||video=Camera", "");

    final URLParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);
    assertEquals("video=Camera", dump.title);
    assertEquals("FFmpeg input dshow", dump.description);
    final Source source = this.verifyStartedWithOneSource();
    final FFmpegDirectSource input = assertInstanceOf(FFmpegDirectSource.class, source);
    final String format = input.getFormat();
    assertEquals("dshow", format);
  }

  @Test
  void playsACaptureDevice() {
    this.play(PlayerArgument.DEVICE, AudioArgument.NONE, "2", "");

    final URLParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);
    assertEquals("Device 2", dump.title);
    assertEquals("Video from a capture device", dump.description);
    final Source source = this.verifyStartedWithOneSource();
    final DeviceSource device = assertInstanceOf(DeviceSource.class, source);
    final int deviceId = device.getDeviceId();
    assertEquals(2, deviceId);
    this.videoPlayers.verify(VideoPlayer::device);
  }

  @Test
  void playsAUrlOfAMediaFileWithoutYtdlp() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "https://example.com/videos/clip.webm", "");

    final URLParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);
    assertEquals("clip.webm", dump.title);
    assertEquals("Video from a URL", dump.description);
    final Source source = this.verifyStartedWithOneSource();
    final UriSource uri = assertInstanceOf(UriSource.class, source);
    final URI played = uri.getUri();
    final URI expected = URI.create("https://example.com/videos/clip.webm");
    assertEquals(expected, played);
    this.parsers.verifyNoInteractions();
  }

  @Test
  void resolvesWebPagesWithYtdlpAndPlaysTheirStreams() throws IOException {
    final URLParseDump resolved = new URLParseDump();
    resolved.title = "A video";
    when(this.parser.parse(any(UriSource.class), eq("--format"), eq("best"))).thenReturn(resolved);
    final URI videoStream = URI.create("https://cdn.example.com/video");
    final URI audioStream = URI.create("https://cdn.example.com/audio");
    final UriSource videoStreamSource = UriSource.uri(videoStream);
    final UriSource audioStreamSource = UriSource.uri(audioStream);
    final StrategySelector strategies = mockStrategies(resolved, videoStreamSource, audioStreamSource);

    try (final MockedStatic<StrategySelector> selectors = Mockito.mockStatic(StrategySelector.class)) {
      selectors
        .when(() -> StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO))
        .thenReturn(strategies);
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, "--yt-dlp{format=best}");
    }

    this.assertResolvedTheWebPage();
    final URLParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);
    assertSame(resolved, dump);
    this.assertStartedWithStreams(videoStreamSource, audioStreamSource);
    final Component started = Message.START_VIDEO.build();
    this.assertSenderReceived(started);
  }

  @Test
  void reportsWebPagesThatYtdlpCannotResolve() throws IOException {
    when(this.parser.parse(any(UriSource.class))).thenThrow(new IOException("offline"));

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, "");

    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).clearCurrentVideo();
    verify(this.manager, never()).setPlayer(any());
    this.assertNotStarting();
  }

  @Test
  void refusesMediaThatCannotBeFound() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "not a source", "");

    final Component error = Message.UNSUPPORTED_MRL.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).clearCurrentVideo();
    final boolean created = this.command.resolutions.isEmpty();
    assertTrue(created);
    this.assertNotStarting();
  }

  @Test
  void stopsAfterSourceResolutionWhenTheRequestWasCancelled() throws IOException {
    final AtomicBoolean cancelled = new AtomicBoolean();
    when(this.manager.isCurrent(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(_ -> !cancelled.get());
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    // The second check happens after source detection but before display/audio/player allocation.
    final java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
    Mockito.doAnswer(_ -> {
      if (checks.incrementAndGet() == 2) {
        cancelled.set(true);
        throw new java.util.concurrent.CancellationException("released during resolution");
      }
      return null;
    })
      .when(this.manager)
      .checkStart();
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
    verify(this.manager, never()).clearCurrentVideo();
    verify(this.manager, never()).setPlayer(any());
    final boolean noDisplay = this.command.resolutions.isEmpty();
    assertTrue(noDisplay);
    verify(this.sender, never()).sendMessage(any(Component.class));
    this.assertNotStarting();
  }

  @Test
  void releasesPartiallyConfiguredOutputsWhenAudioConstructionFails() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    when(this.provider.constructFilter(any(), any(), any())).thenThrow(new IllegalStateException("audio failed"));
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
    verify(this.manager, times(2)).clearCurrentVideo();
    verify(this.manager, never()).setPlayer(any());
    final Component failed = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(failed);
    this.assertNotStarting();
  }

  @Test
  void suppressesQueuedSuccessAndHologramAfterRelease() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
    when(this.manager.isCurrent(org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
    TestServer.runPendingTasks();
    verify(this.hologram, never()).start();
    verify(this.sender, never()).sendMessage(any(Component.class));
    this.assertNotStarting();
  }

  @Test
  void releasesAPlayerThatRefusesToStart() throws IOException {
    when(this.player.start(any())).thenReturn(false);
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    verify(this.manager, times(2)).clearCurrentVideo();
    final Component error = Message.UNSUPPORTED_MRL.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getHologramLocation();
  }

  @Test
  void releasesAPlayerThatFailsToStart() throws IOException {
    when(this.player.start(any())).thenThrow(new IllegalStateException("cannot open"));
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    verify(this.manager, times(2)).clearCurrentVideo();
    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);
    this.assertNotStarting();
  }

  @Test
  void refusesInvalidResolutions() {
    this.command.playVideo(_ -> "configuration", this.sender, this.selector, PlayerArgument.FFMPEG, AudioArgument.NONE, "wide", "0", "");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
    verify(this.viewer, never()).sendMessage(any(Component.class));
  }

  @Test
  void refusesTheDiscordBotWhenItIsDisabled() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.DISCORD_BOT, "0", "");

    final Component error = Message.UNSUPPORTED_AUDIO.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
    this.assertNotStarting();
  }

  @Test
  void refusesTheWebPageWhenItIsDisabled() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.HTTP_SERVER, "0", "");

    final Component error = Message.UNSUPPORTED_AUDIO.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
  }

  @Test
  void refusesVlcWhenItIsNotInstalled() {
    when(this.manager.isVLCSupported()).thenReturn(false);

    this.play(PlayerArgument.VLC, AudioArgument.NONE, "0", "");

    final Component error = Message.UNSUPPORTED_PLAYER.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
  }

  @Test
  void asksToWaitForVlcWhileItIsBeingPrepared() {
    when(this.manager.isPreparing(Capability.VLC)).thenReturn(true);
    when(this.manager.isVLCSupported()).thenReturn(false);

    this.play(PlayerArgument.VLC, AudioArgument.NONE, "0", "");

    final Component message = Message.VLC_PREPARING.build();
    this.assertSenderReceived(message);
    verify(this.manager, never()).getService();
    this.videoPlayers.verify(VideoPlayer::vlc, never());
    this.assertNotStarting();
  }

  @Test
  void asksToWaitForYtdlpBeforeResolvingAWebPage() {
    when(this.manager.isPreparing(Capability.YT_DLP)).thenReturn(true);

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, "");

    final Component message = Message.YTDLP_PREPARING.build();
    this.assertSenderReceived(message);
    verify(this.manager, never()).getService();
    Mockito.verifyNoInteractions(this.parser);
    this.assertNotStarting();
  }

  @Test
  void playsMediaThatNeedsNoYtdlpWhileYtdlpIsBeingPrepared() throws IOException {
    when(this.manager.isPreparing(Capability.YT_DLP)).thenReturn(true);
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "https://example.com/clip.mp4", "");

    final Component started = Message.START_VIDEO.build();
    this.assertSenderReceived(started, started);
    Mockito.verifyNoInteractions(this.parser);
  }

  @Test
  void playsWithVlcWhenItIsInstalled() {
    this.play(PlayerArgument.VLC, AudioArgument.NONE, "0", "");

    this.videoPlayers.verify(VideoPlayer::vlc);
    final Component started = Message.START_VIDEO.build();
    this.assertSenderReceived(started);
  }

  @Test
  void refusesToStartWhileAnotherVideoIsStarting() {
    this.status.set(true);

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "0", "");

    final Component error = Message.PLAYER_ERROR.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
    final boolean starting = this.status.get();
    assertTrue(starting);
  }

  @Test
  void sendsTheViewersTheLinkToTheVoiceChannel() {
    when(this.provider.isDiscordBotEnabled()).thenReturn(true);
    when(this.provider.isDiscordBotReady()).thenReturn(true);
    when(this.provider.constructVoiceChannelUrl()).thenReturn("https://discord.com/channels/1/2");

    this.play(PlayerArgument.FFMPEG, AudioArgument.DISCORD_BOT, "0", "");

    final Component loading = Message.LOAD_VIDEO.build();
    final Component link = Message.AUDIO_DISCORD.build("https://discord.com/channels/1/2");
    this.assertViewerReceived(loading, link);
    this.verifyPlayedWithDump(AudioArgument.DISCORD_BOT);
    final Component started = Message.START_VIDEO.build();
    this.assertSenderReceived(started);
  }

  @Test
  void sendsTheViewersTheLinkToTheWebPage() {
    when(this.provider.isHttpEnabled()).thenReturn(true);
    when(this.provider.isHttpReady()).thenReturn(true);
    when(this.provider.constructHttpUrl()).thenReturn("http://localhost:3000/");

    this.play(PlayerArgument.FFMPEG, AudioArgument.HTTP_SERVER, "0", "");

    final Component loading = Message.LOAD_VIDEO.build();
    final Component link = Message.AUDIO_HTTP.build("http://localhost:3000/");
    this.assertViewerReceived(loading, link);
  }

  @Test
  void sendsNoLinkForVoiceChatAudio() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.SIMPLE_VOICE_CHAT, "0", "");

    final Component loading = Message.LOAD_VIDEO.build();
    this.assertViewerReceived(loading);
    this.verifyPlayedWithDump(AudioArgument.SIMPLE_VOICE_CHAT);
  }

  @Test
  void refusesTheDiscordBotWhileItIsNotReady() {
    when(this.provider.isDiscordBotEnabled()).thenReturn(true);

    this.play(PlayerArgument.FFMPEG, AudioArgument.DISCORD_BOT, "0", "");

    final Component error = Message.AUDIO_NOT_READY.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
    this.assertNotStarting();
  }

  @Test
  void refusesTheWebPageWhileItIsNotReady() {
    when(this.provider.isHttpEnabled()).thenReturn(true);

    this.play(PlayerArgument.FFMPEG, AudioArgument.HTTP_SERVER, "0", "");

    final Component error = Message.AUDIO_NOT_READY.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
  }

  @Test
  void reportsAndUnlocksWhenTheWorkerRefusesTheVideo() {
    final ExecutorService stopped = Executors.newSingleThreadExecutor();
    stopped.shutdown();
    when(this.manager.getService()).thenReturn(stopped);

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "0", "");

    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);
    this.assertNotStarting();
    verify(this.manager, never()).setPlayer(any());
  }

  @Test
  void unlocksWithoutReportingWhenThePluginWasDisabledMeanwhile() throws IOException {
    final BukkitScheduler scheduler = TestServer.scheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(new IllegalPluginAccessException("disabled"));
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    verify(this.player).start(any());
    verify(this.sender, never()).sendMessage(any(Component.class));
    this.assertNotStarting();
  }

  @Test
  void refusesNullDisplayArguments() {
    final AbstractVideoCommand.VideoConfigurationProvider configuration = _ -> "configuration";
    final PlayerArgument ffmpeg = PlayerArgument.FFMPEG;
    final AudioArgument none = AudioArgument.NONE;

    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(null, this.sender, this.selector, ffmpeg, none, "640x360", "0", "")
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, null, this.selector, ffmpeg, none, "640x360", "0", "")
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, null, ffmpeg, none, "640x360", "0", "")
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, this.selector, null, none, "640x360", "0", "")
    );

    verify(this.manager, never()).getService();
  }

  @Test
  void refusesNullPlaybackArguments() {
    final AbstractVideoCommand.VideoConfigurationProvider configuration = _ -> "configuration";
    final PlayerArgument ffmpeg = PlayerArgument.FFMPEG;
    final AudioArgument none = AudioArgument.NONE;

    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, this.selector, ffmpeg, null, "640x360", "0", "")
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, this.selector, ffmpeg, none, null, "0", "")
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, this.selector, ffmpeg, none, "640x360", null, "")
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, this.selector, ffmpeg, none, "640x360", "0", null)
    );

    verify(this.manager, never()).getService();
  }

  @Test
  void releasesTheStartupClaimWhenViewerSelectionFails() {
    final IllegalStateException failure = new IllegalStateException("selector failed");
    when(this.selector.values()).thenThrow(failure);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "missing.mp4", "")
    );
    assertSame(failure, thrown);
    this.assertNotStarting();
    verify(this.manager, never()).beginStart();
  }

  @Test
  void releasesTheStartupClaimWhenLoadingNotificationFails() {
    final IllegalStateException failure = new IllegalStateException("viewer disconnected");
    Mockito.doThrow(failure).when(this.viewer).sendMessage(any(Component.class));
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "missing.mp4", "")
    );
    assertSame(failure, thrown);
    this.assertNotStarting();
    verify(this.manager, never()).beginStart();
  }

  @Test
  void preservesStartupFailureWhenClearingPartialOutputsAlsoFails() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final IllegalStateException failure = new IllegalStateException("native start");
    final IllegalArgumentException cleanup = new IllegalArgumentException("clear outputs");
    when(this.player.start(any())).thenThrow(failure);
    Mockito.doNothing().doThrow(cleanup).when(this.manager).clearCurrentVideo();
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
    verify(this.manager, times(2)).clearCurrentVideo();
    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);
    this.assertNotStarting();
  }

  @Test
  void doesNotSuppressStartupFailureOntoItself() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final IllegalStateException failure = new IllegalStateException("same failure");
    when(this.player.start(any())).thenThrow(failure);
    Mockito.doNothing().doThrow(failure).when(this.manager).clearCurrentVideo();
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(0, suppressed.length);
    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);
    this.assertNotStarting();
  }

  @Test
  void killsAHologramWhoseStartFailsAndPreservesCleanupFailure() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final Location location = mock(Location.class);
    when(this.manager.getHologramLocation()).thenReturn(location);
    final IllegalStateException failure = new IllegalStateException("hologram start");
    final IllegalArgumentException cleanup = new IllegalArgumentException("hologram kill");
    Mockito.doThrow(failure).when(this.hologram).start();
    Mockito.doThrow(cleanup).when(this.hologram).kill();
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
    final InOrder order = inOrder(this.manager, this.hologram);
    order.verify(this.manager).setHologram(this.hologram);
    order.verify(this.hologram).start();
    order.verify(this.manager).setHologram(null);
    order.verify(this.hologram).kill();
    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(1, suppressed.length);
    assertSame(cleanup, suppressed[0]);
    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);
    this.assertNotStarting();
  }

  @Test
  void propagatesFatalPlayVideoFailureWithoutResettingStartingStatus() {
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal sentinel");
    when(this.manager.getService()).thenThrow(fatal);

    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "0", "")
    );
    assertSame(fatal, thrown);
    final boolean starting = this.status.get();
    assertTrue(starting, "status should not be reset to false on fatal error");
  }

  @Test
  void reportsFatalPlayerStartFailureWithoutRecoverableCleanup() throws IOException {
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal sentinel");
    when(this.player.start(any())).thenThrow(fatal);
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    this.playAndRequireFatalReport(mrl);
    verify(this.manager, times(1)).clearCurrentVideo();
  }

  @Test
  void reportsFatalCleanupFailureWithoutSuppressingItOntoTheStartFailure() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final IllegalStateException failure = new IllegalStateException("native start");
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal sentinel");
    when(this.player.start(any())).thenThrow(failure);
    Mockito.doNothing().doThrow(fatal).when(this.manager).clearCurrentVideo();
    this.playAndRequireFatalReport(mrl);
    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(0, suppressed.length, "fatal cleanup must escape the recoverable-failure handler");
  }

  @Test
  void reportsFatalHologramStartFailureWithoutKillingHologramOrClearingManager() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final Location location = mock(Location.class);
    when(this.manager.getHologramLocation()).thenReturn(location);
    final OutOfMemoryError fatal = new OutOfMemoryError("fatal sentinel");
    Mockito.doThrow(fatal).when(this.hologram).start();
    this.playAndRequireFatalReport(mrl);
    verify(this.hologram, never()).kill();
    verify(this.manager, never()).setHologram(null);
  }

  private void playAndRequireFatalReport(final String mrl) {
    final String output;
    try (final StandardErrorCapture capture = StandardErrorCapture.start()) {
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
      output = capture.getOutput();
    }
    final boolean reported = output.contains("java.lang.OutOfMemoryError: fatal sentinel");
    assertTrue(reported, output);
    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);
  }

  @Test
  void stopsBeforeDisplayCreationWhenCancelledBeforePlayerCreation() throws IOException {
    final AtomicBoolean cancelled = new AtomicBoolean();
    when(this.manager.isCurrent(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(_ -> !cancelled.get());
    Mockito.doAnswer(_ -> {
      cancelled.set(true);
      return null;
    })
      .when(this.manager)
      .clearCurrentVideo();
    Mockito.doAnswer(_ -> {
      if (cancelled.get()) {
        throw new CancellationException("cancelled before player creation");
      }
      return null;
    })
      .when(this.manager)
      .checkStart();

    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    assertTrue(this.command.resolutions.isEmpty(), "video filter must not be created when cancelled before player creation");
    verify(this.provider, never()).constructFilter(any(), any(), any());
    verify(this.manager, never()).setPlayer(any());
    verify(this.player, never()).start(any());
    verify(this.player, never()).start(any(), any());
    this.assertNotStarting();
  }

  @Test
  void stopsBeforeAudioConstructionWhenCancelledDuringVideoConfiguration() throws IOException {
    final AtomicBoolean cancelled = new AtomicBoolean();
    when(this.manager.isCurrent(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(_ -> !cancelled.get());
    Mockito.doAnswer(_ -> {
      if (cancelled.get()) {
        throw new CancellationException("cancelled during video configuration");
      }
      return null;
    })
      .when(this.manager)
      .checkStart();

    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final AbstractVideoCommand.VideoConfigurationProvider configuration = _ -> {
      cancelled.set(true);
      return "configuration";
    };

    this.command.playVideo(configuration, this.sender, this.selector, PlayerArgument.FFMPEG, AudioArgument.NONE, "640x360", mrl, "");

    assertEquals(1, this.command.resolutions.size());
    verify(this.provider, never()).constructFilter(any(), any(), any());
    verify(this.manager, never()).setPlayer(any());
    verify(this.player, never()).start(any());
    verify(this.player, never()).start(any(), any());
    this.assertNotStarting();
  }

  @Test
  void stopsBeforeNativeStartWhenCancelledDuringAudioConfiguration() throws IOException {
    final AtomicBoolean cancelled = new AtomicBoolean();
    when(this.manager.isCurrent(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(_ -> !cancelled.get());
    when(this.provider.constructFilter(any(), any(), any())).thenAnswer(_ -> {
      cancelled.set(true);
      return AudioFilter.NO_OP;
    });
    Mockito.doAnswer(_ -> {
      if (cancelled.get()) {
        throw new CancellationException("cancelled during audio configuration");
      }
      return null;
    })
      .when(this.manager)
      .checkStart();

    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    verify(this.provider, times(1)).constructFilter(any(), any(), any());
    verify(this.manager, never()).setPlayer(any());
    verify(this.player, never()).start(any());
    verify(this.player, never()).start(any(), any());
    this.assertNotStarting();
  }
}
