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
import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.bukkit.hologram.Hologram;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.json.ytdlp.YtdlpParser;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.audio.VolumeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.GrayscaleFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.InvertFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.device.DeviceSource;
import me.brandonli.mcav.media.source.ffmpeg.FfmpegDirectSource;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.StandardErrorCapture;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.CaptureDevices;
import me.brandonli.mcav.plugin.utils.FilterChain;
import me.brandonli.mcav.plugin.utils.MediaKind;
import me.brandonli.mcav.plugin.utils.PlayerArgument;
import me.brandonli.mcav.utils.InputMetadataException;
import me.brandonli.mcav.utils.MetadataUtils;
import me.brandonli.mcav.utils.immutable.Dimension;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

// the video manager works on the calling thread, so every step runs before playVideo returns
final class AbstractVideoCommandTest {

  private static final String WEB_PAGE = "https://www.youtube.com/watch?v=abc";
  private static final String VIDEO_STREAM = "https://cdn.example.com/video";
  private static final String AUDIO_STREAM = "https://cdn.example.com/audio";
  private static final String PAGE_VIDEO_FILE = "http://127.0.0.1:8872/clip.mp4";
  private static final String PAGE_AUDIO_FILE = "http://127.0.0.1:8872/clip.m4a";

  private static final String VIDEO_RESOLUTION = "640x360";
  private static final int VIDEO_WIDTH = 640;
  private static final int VIDEO_HEIGHT = 360;

  private static final String MRL = "0";
  private static final String DEVICE_MRL = "2";
  private static final String DEVICE_NAME = "camera";

  private static final String LIST_SEPARATOR = ", ";

  private static final String FORMAT_OPTION = "--format";
  private static final String BEST_FORMAT = "best";

  private static final String CONFIGURATION = "configuration";

  private static final String VIDEO_FILE_NAME = "clip.mp4";
  private static final String MISSING_FILE_NAME = "missing.mp4";
  private static final String WEBM_VIDEO_FILE_NAME = "clip.webm";

  private static final String NATIVE_START_FAILURE_MESSAGE = "native start!";

  private static final String FATAL_FAILURE_MESSAGE = "fatal sentinel!";

  private static final String ERROR_LEVEL = "ERROR";
  private static final String STACK_FRAME = "\tat ";

  @TempDir
  private Path folder;

  private final ExecutorService directExecutor = MoreExecutors.newDirectExecutorService();
  private final AtomicBoolean status = new AtomicBoolean();

  private VideoPlayerManager manager;

  private final VolumeFilter volume = new VolumeFilter();
  private AudioProvider provider;
  private RecordingCommand command;
  private CommandSender sender;
  private Player viewer;
  private MultiplePlayerSelector selector;
  private VideoPlayerMultiplexer player;
  private BiConsumer<String, Throwable> exceptionHandler;
  private VideoAttachableCallback videoCallback;
  private AudioAttachableCallback audioCallback;
  private DimensionAttachableCallback dimensionCallback;
  private Hologram hologram;
  private YtdlpParser parser;
  private MockedStatic<VideoPlayer> videoPlayers;
  private MockedStatic<Hologram> holograms;
  private MockedStatic<YtdlpParser> parsers;
  private MockedStatic<MetadataUtils> metadata;

  @BeforeEach
  void createCommand() {
    TestServer.reset();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    when(plugin.getDataFolder()).thenReturn(this.folder.toFile());
    this.manager = mock(VideoPlayerManager.class);
    this.provider = mock(AudioProvider.class);
    when(plugin.getVideoPlayerManager()).thenReturn(this.manager);
    when(plugin.getAudioProvider()).thenReturn(this.provider);
    when(this.manager.getStarting()).thenReturn(this.status);
    when(this.manager.getService()).thenReturn(this.directExecutor);
    when(this.manager.isCurrent(ArgumentMatchers.anyLong())).thenReturn(true);
    when(this.manager.startNative(any(BooleanSupplier.class))).thenAnswer(this::runStart);
    when(this.manager.isVlcSupported()).thenReturn(true);
    when(this.manager.getVolume()).thenReturn(this.volume);
    when(this.provider.constructFilter(any(), any(), any())).thenReturn(AudioFilter.NO_OPERATION);
    this.command = new RecordingCommand(plugin);

    this.sender = mock(CommandSender.class);
    this.viewer = mock(Player.class);
    this.selector = mock(MultiplePlayerSelector.class);
    final List<Player> viewers = List.of(this.viewer);
    when(this.selector.values()).thenReturn(viewers);
    this.mockPlayers();
    this.mockHologramsAndYtdlp();
    this.metadata = Mockito.mockStatic(MetadataUtils.class);
  }

  private boolean runStart(final InvocationOnMock invocation) {
    final BooleanSupplier start = invocation.getArgument(0);
    return start.getAsBoolean();
  }

  @AfterEach
  void closeMocks() {
    this.videoPlayers.close();
    this.holograms.close();
    this.parsers.close();
    this.metadata.close();
    this.directExecutor.shutdown();
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
    Mockito.doAnswer(this::keepExceptionHandler).when(this.player).setExceptionHandler(any());
    this.videoPlayers = Mockito.mockStatic(VideoPlayer.class);
    this.videoPlayers.when(VideoPlayer::ffmpeg).thenReturn(this.player);
    this.videoPlayers.when(VideoPlayer::device).thenReturn(this.player);
    this.videoPlayers.when(VideoPlayer::vlc).thenReturn(this.player);
  }

  private @Nullable Object keepExceptionHandler(final InvocationOnMock invocation) {
    this.exceptionHandler = invocation.getArgument(0);
    return null;
  }

  private void mockHologramsAndYtdlp() {
    this.hologram = mock(Hologram.class);
    this.holograms = Mockito.mockStatic(Hologram.class);
    this.holograms.when(Hologram::basic).thenReturn(this.hologram);
    this.parser = mock(YtdlpParser.class);
    this.parsers = Mockito.mockStatic(YtdlpParser.class);
    this.parsers.when(YtdlpParser::simple).thenReturn(this.parser);
  }

  @Test
  void shouldPlayAVideoFile() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    final Component loading = Message.LOAD_VIDEO.build();
    verify(this.viewer).sendMessage(loading);
    verify(this.manager).clearCurrentVideo();
    final UrlParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);

    final Optional<String> title = dump.getTitle();
    final Optional<String> expectedTitle = Optional.of(VIDEO_FILE_NAME);
    assertEquals(expectedTitle, title);

    final Optional<String> description = dump.getDescription();
    final Optional<String> expectedDescription = Optional.of("Video from a file");
    assertEquals(expectedDescription, description);

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
  void shouldGiveAVideoFileItsLength() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final Duration length = Duration.ofSeconds(255);
    final Optional<Duration> declared = Optional.of(length);
    this.metadata.when(() -> MetadataUtils.parseDuration(any(FileSource.class))).thenReturn(declared);
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    final UrlParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);
    final double seconds = dump.getDuration();
    assertEquals(255.0, seconds);
  }

  @Test
  void shouldPlayAVideoFileWhoseLengthCannotBeRead() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    this.metadata.when(() -> MetadataUtils.parseDuration(any(FileSource.class))).thenThrow(InputMetadataException.class);
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    final UrlParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);
    final double seconds = dump.getDuration();
    assertEquals(0.0, seconds);

    final Component started = Message.START_VIDEO.build();
    this.assertSenderReceived(started);
  }

  private Path createVideoFile() throws IOException {
    final Path file = this.folder.resolve(VIDEO_FILE_NAME);
    Files.writeString(file, "video");
    return file;
  }

  private void play(final PlayerArgument playerType, final AudioArgument audioType, final String mrl, final String flags) {
    this.command.playVideo(_ -> CONFIGURATION, this.sender, this.selector, playerType, audioType, VIDEO_RESOLUTION, mrl, flags, null);
  }

  private void assertSenderReceived(final Component... expected) {
    final List<Component> messages = Components.received(this.sender);
    final List<Component> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, messages);
  }

  private void assertNotStarting() {
    final boolean starting = this.status.get();
    assertFalse(starting);
  }

  private UrlParseDump verifyPlayedWithDump(final AudioArgument audioType) {
    final ArgumentCaptor<UrlParseDump> dumps = ArgumentCaptor.forClass(UrlParseDump.class);
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

    final AudioFilter filter = audioStep.getFilter();
    assertSame(this.volume, filter, "the volume comes before the output");

    final Optional<AudioPipelineStep> next = audioStep.next();
    final AudioPipelineStep output = next.orElseThrow();
    final AudioFilter audioFilter = output.getFilter();
    assertSame(AudioFilter.NO_OPERATION, audioFilter);

    final Dimension dimension = new Dimension(VIDEO_WIDTH, VIDEO_HEIGHT);
    verify(this.dimensionCallback).attach(dimension);
  }

  private void assertFilterCreatedForTheResolution() {
    final Pair<Integer, Integer> resolution = this.command.resolutions.getFirst();
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    assertEquals(VIDEO_WIDTH, width);
    assertEquals(VIDEO_HEIGHT, height);

    final AbstractVideoCommand.VideoConfigurationProvider configurationProvider = this.command.providers.getFirst();
    final Object configuration = configurationProvider.buildConfiguration(resolution);
    assertEquals(CONFIGURATION, configuration);
  }

  @Test
  void shouldShowTheTitleOnTheHologramAtTheConfiguredLocation() throws IOException {
    final Location location = new Location(null, 0.0, 70.0, 0.0);
    when(this.manager.getHologramLocation()).thenReturn(Optional.of(location));
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    final UrlParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);

    final InOrder order = inOrder(this.hologram, this.manager);
    order.verify(this.manager).setHologram(this.hologram);
    order.verify(this.hologram).spawn(location, dump);
    order.verify(this.hologram).start();
  }

  @Test
  void shouldReplaceTheHologramOfThePreviousVideo() throws IOException {
    final Hologram previous = mock(Hologram.class);
    when(this.manager.getHologram()).thenReturn(Optional.of(previous));
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    verify(previous).kill();
    verify(this.manager).setHologram(null);
    this.holograms.verifyNoInteractions();
  }

  @Test
  void shouldPlayAnFfmpegInput() {
    when(this.sender.hasPermission(CaptureDevices.PERMISSION)).thenReturn(true);
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "dshow||video=Camera", "");

    final UrlParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);

    final Optional<String> title = dump.getTitle();
    final Optional<String> expectedTitle = Optional.of("video=Camera");
    assertEquals(expectedTitle, title);

    final Optional<String> description = dump.getDescription();
    final Optional<String> expectedDescription = Optional.of("FFmpeg input dshow");
    assertEquals(expectedDescription, description);

    final Source source = this.verifyStartedWithOneSource();
    final FfmpegDirectSource input = assertInstanceOf(FfmpegDirectSource.class, source);

    final String format = input.getFormat();
    assertEquals("dshow", format);
  }

  @Test
  void shouldRefuseAnFfmpegInputOrADeviceWithoutTheirPermission() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "x11grab||:0", "");
    this.play(PlayerArgument.DEVICE, AudioArgument.NONE, DEVICE_MRL, "");

    final Component refused = Message.DEVICE_PERMISSION.build();
    this.assertSenderReceived(refused, refused);
    this.assertNotStarting();
    this.videoPlayers.verifyNoInteractions();
  }

  @Test
  void shouldPlayOnlyACaptureDeviceTheServerListed() {
    when(this.sender.hasPermission(CaptureDevices.PERMISSION)).thenReturn(true);
    when(this.manager.getDevices()).thenReturn(List.of(CaptureDevices.Device.of(0, DEVICE_NAME)));
    this.play(PlayerArgument.DEVICE, AudioArgument.NONE, DEVICE_MRL, "");

    this.assertSenderReceived(Message.DEVICE_UNLISTED.build(DEVICE_MRL));
    this.assertNotStarting();
    this.videoPlayers.verifyNoInteractions();
  }

  @Test
  void shouldPutTheFiltersBeforeTheDisplay() {
    this.command.playVideo(
      _ -> CONFIGURATION,
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      VIDEO_RESOLUTION,
      MRL,
      "",
      "grayscale,invert"
    );

    final ArgumentCaptor<VideoPipelineStep> steps = ArgumentCaptor.forClass(VideoPipelineStep.class);
    verify(this.videoCallback).attach(steps.capture());

    final VideoPipelineStep first = steps.getValue();

    final VideoFilter filter = first.getFilter();
    assertInstanceOf(GrayscaleFilter.class, filter);

    final Optional<VideoPipelineStep> afterFirst = first.next();
    final VideoPipelineStep second = afterFirst.orElseThrow();

    final VideoFilter secondFilter = second.getFilter();
    assertInstanceOf(InvertFilter.class, secondFilter);

    final Optional<VideoPipelineStep> afterSecond = second.next();
    final Optional<VideoPipelineStep> expectedOutput = Optional.of(this.command.pipeline);
    assertEquals(expectedOutput, afterSecond);
  }

  @Test
  void shouldTakeNoFiltersFromAnEmptyOption() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class);

    final FilterChain filters = AbstractVideoCommand.parseFilters(this.sender, plugin, "", MediaKind.VIDEO).orElseThrow();
    assertSame(FilterChain.NONE, filters);

    final FilterChain abstractVideoCommandFilters = AbstractVideoCommand.parseFilters(
      this.sender,
      plugin,
      null,
      MediaKind.IMAGE
    ).orElseThrow();
    assertSame(FilterChain.NONE, abstractVideoCommandFilters);

    verify(this.sender, never()).sendMessage(any(Component.class));
  }

  @Test
  void shouldRefuseFiltersThatAreNotAllowedBeforeStartingAnything() {
    this.command.playVideo(
      _ -> CONFIGURATION,
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      VIDEO_RESOLUTION,
      MRL,
      "",
      "sharpen"
    );

    final Component refused = Message.FILTERS_INVALID.build(
      "Filter sharpen does not exist, use one of %s instead!".formatted(String.join(LIST_SEPARATOR, FilterChain.NAMES))
    );
    this.assertSenderReceived(refused);
    this.assertNotStarting();
    this.videoPlayers.verifyNoInteractions();
  }

  @Test
  void shouldPlayACaptureDevice() {
    when(this.sender.hasPermission(CaptureDevices.PERMISSION)).thenReturn(true);
    when(this.manager.getDevices()).thenReturn(List.of(CaptureDevices.Device.of(2, DEVICE_NAME)));
    this.play(PlayerArgument.DEVICE, AudioArgument.NONE, DEVICE_MRL, "");

    final UrlParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);

    final Optional<String> title = dump.getTitle();
    final Optional<String> expectedTitle = Optional.of("Device 2");
    assertEquals(expectedTitle, title);

    final Optional<String> description = dump.getDescription();
    final Optional<String> expectedDescription = Optional.of("Video from a capture device");
    assertEquals(expectedDescription, description);

    final Source source = this.verifyStartedWithOneSource();
    final DeviceSource device = assertInstanceOf(DeviceSource.class, source);

    final int deviceId = device.getDeviceId();
    assertEquals(2, deviceId);
    this.videoPlayers.verify(VideoPlayer::device);
  }

  @Test
  void shouldRefuseUnsupportedYtdlpOptionsBeforeStartingAnything() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, "--yt-dlp{exec=anything}");

    final String supported = String.join(LIST_SEPARATOR, VideoFlagsParser.supportedOptions());
    final String reason = "yt-dlp option exec is not supported, use one of %s instead!".formatted(supported);
    final Component refused = Message.UNSUPPORTED_FLAGS.build(reason);
    this.assertSenderReceived(refused);
    Mockito.verifyNoInteractions(this.viewer);
    this.assertNotStarting();
    this.parsers.verifyNoInteractions();
    this.videoPlayers.verifyNoInteractions();
  }

  @Test
  void shouldPlayAUrlOfAMediaFileWithoutYtdlp() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "https://example.com/videos/clip.webm", "");

    final UrlParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);

    final Optional<String> title = dump.getTitle();
    final Optional<String> expectedTitle = Optional.of(WEBM_VIDEO_FILE_NAME);
    assertEquals(expectedTitle, title);

    final Optional<String> description = dump.getDescription();
    final Optional<String> expectedDescription = Optional.of("Video from a URL");
    assertEquals(expectedDescription, description);

    final Source source = this.verifyStartedWithOneSource();
    final UriSource uri = assertInstanceOf(UriSource.class, source);

    final URI played = uri.getUri();
    final URI expected = URI.create("https://example.com/videos/clip.webm");
    assertEquals(expected, played);
    this.parsers.verifyNoInteractions();
  }

  @Test
  void shouldResolveWebPagesWithYtdlpAndPlayTheirStreams() throws IOException {
    final UrlParseDump resolved = UrlParseDump.of("A video");
    when(this.parser.parse(any(UriSource.class), eq(FORMAT_OPTION), eq(BEST_FORMAT))).thenReturn(resolved);
    final URI videoStream = URI.create(VIDEO_STREAM);
    final URI audioStream = URI.create(AUDIO_STREAM);
    final UriSource videoStreamSource = UriSource.uri(videoStream);
    final UriSource audioStreamSource = UriSource.uri(audioStream);
    final StrategySelector strategies = this.mockStrategies(resolved, videoStreamSource, audioStreamSource);

    this.playWebPage(strategies, "--yt-dlp{format=best}");

    this.assertResolvedTheWebPage();
    final UrlParseDump dump = this.verifyPlayedWithDump(AudioArgument.NONE);
    assertSame(resolved, dump);
    this.assertStartedWithStreams(videoStreamSource, audioStreamSource);

    final Component started = Message.START_VIDEO.build();
    this.assertSenderReceived(started);
  }

  private StrategySelector mockStrategies(final UrlParseDump resolved, final UriSource videoSource, final UriSource audioSource) {
    final Format video = mock(Format.class);
    final Format audio = mock(Format.class);
    when(video.toUriSource()).thenReturn(videoSource);
    when(audio.toUriSource()).thenReturn(audioSource);
    final FormatStrategy audioStrategy = mock(FormatStrategy.class);
    when(audioStrategy.select(resolved)).thenReturn(Optional.of(audio));
    final StrategySelector strategies = mock(StrategySelector.class);
    when(strategies.getVideoSource(resolved)).thenReturn(video);
    when(strategies.getAudioStrategy()).thenReturn(audioStrategy);

    return strategies;
  }

  private void playWebPage(final StrategySelector strategies, final String options) {
    try (final MockedStatic<StrategySelector> selectors = Mockito.mockStatic(StrategySelector.class)) {
      selectors
        .when(() -> StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO))
        .thenReturn(strategies);
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, options);
    }
  }

  private void assertResolvedTheWebPage() throws IOException {
    final ArgumentCaptor<UriSource> pages = ArgumentCaptor.forClass(UriSource.class);
    verify(this.parser).parse(pages.capture(), eq(FORMAT_OPTION), eq(BEST_FORMAT));

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

  @ParameterizedTest
  @CsvSource({
    "file:///home/minecraft/world/level.dat, https://cdn.example.com/audio, file",
    "https://cdn.example.com/video, concat:/etc/hostname, concat",
    "http://cdn.example.com/video, file:///etc/hostname, file",
    "tcp://127.0.0.1:25575, https://cdn.example.com/audio, tcp",
  })
  void shouldRefuseStreamsOfAWebPageThatAreNotOnTheWeb(final String videoStream, final String audioStream, final String scheme)
    throws IOException {
    final UrlParseDump resolved = GsonProvider.getSimple().fromJson("{}", UrlParseDump.class);
    when(this.parser.parse(any(UriSource.class))).thenReturn(resolved);
    final UriSource videoStreamSource = UriSource.uri(URI.create(videoStream));
    final UriSource audioStreamSource = UriSource.uri(URI.create(audioStream));
    final StrategySelector strategies = this.mockStrategies(resolved, videoStreamSource, audioStreamSource);

    this.playWebPage(strategies, "");

    this.assertRefusedForScheme(scheme);
    verify(this.player, never()).start(any(), any());
    verify(this.manager, never()).setPlayer(any());
    this.assertNotStarting();
  }

  @Test
  void shouldPlayAPageWhoseOnlyStreamIsTheVideoFile() {
    final String videoFile = this.createVideoFormat(PAGE_VIDEO_FILE);
    this.resolveWebPageTo(videoFile);

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, "");

    final URI file = URI.create(PAGE_VIDEO_FILE);
    this.assertStartedWithStreamsAt(file, file);

    final Component started = Message.START_VIDEO.build();
    this.assertSenderReceived(started);
  }

  @Test
  void shouldTakeTheAudioFromTheVideoStreamWhenThePageHasNoSeparateAudio() {
    this.makeVoiceChatReady();
    final String videoFile = this.createVideoFormat(PAGE_VIDEO_FILE);
    this.resolveWebPageTo(videoFile);

    this.play(PlayerArgument.FFMPEG, AudioArgument.SIMPLE_VOICE_CHAT, WEB_PAGE, "");

    final URI file = URI.create(PAGE_VIDEO_FILE);
    this.assertStartedWithStreamsAt(file, file);
  }

  private void makeVoiceChatReady() {
    when(this.provider.isSimpleVoiceChatEnabled()).thenReturn(true);
    when(this.provider.isSimpleVoiceChatReady()).thenReturn(true);
  }

  @Test
  void shouldKeepTheBestSeparateAudioStreamWhenThePageHasOne() {
    this.makeVoiceChatReady();
    final String videoOnly = this.createVideoFormat(PAGE_VIDEO_FILE);
    final String audioOnly = this.createAudioFormat(PAGE_AUDIO_FILE);
    this.resolveWebPageTo(videoOnly, audioOnly);

    this.play(PlayerArgument.FFMPEG, AudioArgument.SIMPLE_VOICE_CHAT, WEB_PAGE, "");

    final URI video = URI.create(PAGE_VIDEO_FILE);
    final URI audio = URI.create(PAGE_AUDIO_FILE);
    this.assertStartedWithStreamsAt(video, audio);
  }

  @ParameterizedTest
  @CsvSource({ "file:///srv/media/clip.mp4, file", "rtsp://127.0.0.1:8554/clip, rtsp" })
  void shouldRefuseANonWebVideoStreamOfAPage(final String videoStream, final String scheme) {
    final String videoFile = this.createVideoFormat(videoStream);
    this.resolveWebPageTo(videoFile);

    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, "");
      output = errors.getOutput();
    }

    this.assertRefusedForScheme(scheme);
    verify(this.player, never()).start(any(), any());
    this.assertNoErrorLogged(output);
  }

  private String createVideoFormat(final String url) {
    return "{\"url\": \"%s\", \"video_ext\": \"mp4\", \"audio_ext\": \"none\"}".formatted(url);
  }

  private String createAudioFormat(final String url) {
    return "{\"url\": \"%s\", \"video_ext\": \"none\", \"audio_ext\": \"m4a\"}".formatted(url);
  }

  private void resolveWebPageTo(final String... formats) {
    final String formatList = String.join(LIST_SEPARATOR, formats);
    final String json = "{\"formats\": [%s]}".formatted(formatList);
    final Gson gson = GsonProvider.getSimple();
    final UrlParseDump resolved = gson.fromJson(json, UrlParseDump.class);
    when(this.parser.parse(any(UriSource.class))).thenReturn(resolved);
  }

  private void assertStartedWithStreamsAt(final URI expectedVideo, final URI expectedAudio) {
    final ArgumentCaptor<UriSource> videoSources = ArgumentCaptor.forClass(UriSource.class);
    final ArgumentCaptor<UriSource> audioSources = ArgumentCaptor.forClass(UriSource.class);
    verify(this.player).start(videoSources.capture(), audioSources.capture());

    final UriSource videoSource = videoSources.getValue();
    final UriSource audioSource = audioSources.getValue();
    final URI video = videoSource.getUri();
    final URI audio = audioSource.getUri();
    assertEquals(expectedVideo, video);
    assertEquals(expectedAudio, audio);
  }

  private void assertRefusedForScheme(final String scheme) {
    final String reason = "yt-dlp reported a stream of %s whose scheme %s is not http or https!".formatted(WEB_PAGE, scheme);
    final Component refused = Message.VIDEO_STREAM_REFUSED.build(reason);
    this.assertSenderReceived(refused);
  }

  private void assertNoErrorLogged(final String output) {
    final boolean logged = output.contains(ERROR_LEVEL) || output.contains(STACK_FRAME);
    assertFalse(logged, output);
  }

  @Test
  void shouldReportWebPagesThatYtdlpCannotResolve() {
    when(this.parser.parse(any(UriSource.class))).thenThrow(new InstallationException("offline!"));

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, "");

    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).clearCurrentVideo();
    verify(this.manager, never()).setPlayer(any());
    this.assertNotStarting();
  }

  @Test
  void shouldLogAPageThatYtdlpCannotResolveAsAnErrorWithItsTrace() {
    when(this.parser.parse(any(UriSource.class))).thenThrow(new InstallationException("offline!"));

    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, "");
      output = errors.getOutput();
    }

    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);

    final boolean logged =
      output.contains(ERROR_LEVEL) && output.contains(STACK_FRAME) && output.contains("could not be resolved by yt-dlp!");
    assertTrue(logged, output);
  }

  @Test
  void shouldTellTheSenderWhyYtdlpCannotResolveAPage() {
    final String reason = "yt-dlp found no video on the page!";
    when(this.parser.parse(any(UriSource.class))).thenThrow(new IllegalArgumentException(reason));

    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, "");
      output = errors.getOutput();
    }

    final Component unavailable = Message.VIDEO_UNAVAILABLE.build(reason);
    this.assertSenderReceived(unavailable);
    verify(this.manager, never()).clearCurrentVideo();
    this.assertNotStarting();
    this.assertNoErrorLogged(output);
  }

  @Test
  void shouldRefuseMediaThatCannotBeFound() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, "not a source", "");

    final String reason = "Video not a source is not a file on the server, a URL, a device index or an FFmpeg input!";
    final Component unavailable = Message.VIDEO_UNAVAILABLE.build(reason);
    this.assertSenderReceived(unavailable);
    verify(this.manager, never()).clearCurrentVideo();

    final boolean created = this.command.resolutions.isEmpty();
    assertTrue(created);
    this.assertNotStarting();
  }

  @Test
  void shouldStopAfterSourceResolutionWhenTheRequestWasCancelled() throws IOException {
    final AtomicBoolean cancelled = new AtomicBoolean();
    when(this.manager.isCurrent(ArgumentMatchers.anyLong())).thenAnswer(_ -> !cancelled.get());
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final AtomicInteger checks = new AtomicInteger();
    Mockito.doAnswer(_ -> this.cancelOnSecondCheck(checks, cancelled))
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

  private @Nullable Object cancelOnSecondCheck(final AtomicInteger checks, final AtomicBoolean cancelled) {
    if (checks.incrementAndGet() == 2) {
      cancelled.set(true);
      final String message = "released during resolution!";
      throw new CancellationException(message);
    }

    return null;
  }

  @Test
  void shouldReleasePartiallyConfiguredOutputsWhenAudioConstructionFails() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    when(this.provider.constructFilter(any(), any(), any())).thenThrow(new IllegalStateException("audio failed!"));
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
    verify(this.manager, times(2)).clearCurrentVideo();
    verify(this.manager, never()).setPlayer(any());

    final Component failed = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(failed);
    this.assertNotStarting();
  }

  @Test
  void shouldSuppressQueuedSuccessAndHologramAfterRelease() throws IOException {
    TestServer.resetWithDeferredTasks();
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
    when(this.manager.isCurrent(ArgumentMatchers.anyLong())).thenReturn(false);
    TestServer.runPendingTasks();
    verify(this.hologram, never()).start();
    verify(this.sender, never()).sendMessage(any(Component.class));
    this.assertNotStarting();
  }

  @Test
  void shouldReleaseAPlayerThatRefusesToStart() throws IOException {
    when(this.player.start(any())).thenReturn(false);
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    verify(this.manager, times(2)).clearCurrentVideo();

    this.assertNotOpened(mrl);
    verify(this.manager, never()).getHologramLocation();
  }

  private void assertNotOpened(final String mrl) {
    final String reason = "Video %s could not be opened by the FFMPEG player!".formatted(mrl);
    final Component unavailable = Message.VIDEO_UNAVAILABLE.build(reason);
    this.assertSenderReceived(unavailable);
  }

  @Test
  void shouldReleaseAPlayerThatRefusesToStartTheStreamsOfAWebPage() {
    when(this.player.start(any(), any())).thenReturn(false);
    final UrlParseDump resolved = UrlParseDump.of("A video the player refuses");
    when(this.parser.parse(any(UriSource.class))).thenReturn(resolved);
    final URI videoStream = URI.create(VIDEO_STREAM);
    final URI audioStream = URI.create(AUDIO_STREAM);
    final UriSource videoStreamSource = UriSource.uri(videoStream);
    final UriSource audioStreamSource = UriSource.uri(audioStream);
    final StrategySelector strategies = this.mockStrategies(resolved, videoStreamSource, audioStreamSource);

    this.playWebPage(strategies, "");

    verify(this.manager, times(2)).clearCurrentVideo();

    this.assertNotOpened(WEB_PAGE);
    verify(this.manager, never()).getHologramLocation();
  }

  @Test
  void shouldTellTheSenderWhichVideoThePlayerCouldNotOpenWithoutAnError() throws IOException {
    when(this.player.start(any())).thenAnswer(this::failToOpen);
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");
      output = errors.getOutput();
    }

    this.assertNotOpened(mrl);
    this.assertNoErrorLogged(output);
  }

  private boolean failToOpen(final InvocationOnMock invocation) {
    final IllegalStateException failure = new IllegalStateException("connection refused!");
    this.exceptionHandler.accept("Playback of clip.mp4 could not be started.", failure);
    return false;
  }

  @Test
  void shouldLogAPlayerFailureAfterTheStartAsAnErrorWithItsTrace() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    final String message = "Playback of clip.mp4 stopped.";
    final String output;
    try (final StandardErrorCapture errors = StandardErrorCapture.start()) {
      this.exceptionHandler.accept(message, new IllegalStateException("decoder failed!"));
      output = errors.getOutput();
    }

    final boolean logged = output.contains(ERROR_LEVEL) && output.contains(STACK_FRAME) && output.contains(message);
    assertTrue(logged, output);
  }

  @Test
  void shouldReleaseAPlayerThatFailsToStart() throws IOException {
    when(this.player.start(any())).thenThrow(new IllegalStateException("cannot open!"));
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    verify(this.manager, times(2)).clearCurrentVideo();

    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);
    this.assertNotStarting();
  }

  @Test
  void shouldRefuseInvalidResolutions() {
    this.command.playVideo(
      _ -> CONFIGURATION,
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      "wide",
      MRL,
      "",
      null
    );

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
    verify(this.viewer, never()).sendMessage(any(Component.class));
  }

  @Test
  void shouldRefuseTheDiscordBotWhenItIsDisabled() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.DISCORD_BOT, MRL, "");

    final Component error = Message.DISCORD_BOT_OFF.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
    this.assertNotStarting();
  }

  @Test
  void shouldRefuseTheWebPageWhenItIsDisabled() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.HTTP_SERVER, MRL, "");

    final Component error = Message.HTTP_SERVER_OFF.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
  }

  @Test
  void shouldRefuseVlcWhenItIsNotInstalled() {
    when(this.manager.isVlcSupported()).thenReturn(false);

    this.play(PlayerArgument.VLC, AudioArgument.NONE, MRL, "");

    final Component error = Message.UNSUPPORTED_PLAYER.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
  }

  @Test
  void shouldAskToWaitForVlcWhileItIsBeingPrepared() {
    when(this.manager.isPreparing(Capability.VLC)).thenReturn(true);
    when(this.manager.isVlcSupported()).thenReturn(false);

    this.play(PlayerArgument.VLC, AudioArgument.NONE, MRL, "");

    final Component message = Message.VLC_PREPARING.build();
    this.assertSenderReceived(message);
    verify(this.manager, never()).getService();
    this.videoPlayers.verify(VideoPlayer::vlc, never());
    this.assertNotStarting();
  }

  @Test
  void shouldAskToWaitForYtdlpBeforeResolvingAWebPage() {
    when(this.manager.isPreparing(Capability.YT_DLP)).thenReturn(true);

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, WEB_PAGE, "");

    final Component message = Message.YTDLP_PREPARING.build();
    this.assertSenderReceived(message);
    verify(this.manager, never()).getService();
    Mockito.verifyNoInteractions(this.parser);
    this.assertNotStarting();
  }

  @Test
  void shouldPlayMediaThatNeedsNoYtdlpWhileYtdlpIsBeingPrepared() throws IOException {
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
  void shouldPlayWithVlcWhenItIsInstalled() {
    this.play(PlayerArgument.VLC, AudioArgument.NONE, MRL, "");

    this.videoPlayers.verify(VideoPlayer::vlc);

    final Component started = Message.START_VIDEO.build();
    this.assertSenderReceived(started);
  }

  @Test
  void shouldRefuseToStartWhileAnotherVideoIsStarting() {
    this.status.set(true);

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, MRL, "");

    final Component error = Message.PLAYER_ERROR.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();

    final boolean starting = this.status.get();
    assertTrue(starting);
  }

  @Test
  void shouldSendTheViewersTheLinkToTheVoiceChannel() {
    when(this.provider.isDiscordBotEnabled()).thenReturn(true);
    when(this.provider.isDiscordBotReady()).thenReturn(true);
    when(this.provider.constructVoiceChannelUrl()).thenReturn("https://discord.com/channels/1/2");

    this.play(PlayerArgument.FFMPEG, AudioArgument.DISCORD_BOT, MRL, "");

    final Component loading = Message.LOAD_VIDEO.build();
    final Component link = Message.AUDIO_DISCORD.build("https://discord.com/channels/1/2");
    this.assertViewerReceived(loading, link);
    this.verifyPlayedWithDump(AudioArgument.DISCORD_BOT);

    final Component started = Message.START_VIDEO.build();
    this.assertSenderReceived(started);
  }

  private void assertViewerReceived(final Component... expected) {
    final List<Component> messages = Components.received(this.viewer);
    final List<Component> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, messages);
  }

  @Test
  void shouldSendTheViewersTheLinkToTheWebPage() {
    when(this.provider.isHttpEnabled()).thenReturn(true);
    when(this.provider.isHttpReady()).thenReturn(true);
    when(this.provider.constructHttpUrl()).thenReturn("http://localhost:3000/");

    this.play(PlayerArgument.FFMPEG, AudioArgument.HTTP_SERVER, MRL, "");

    final Component loading = Message.LOAD_VIDEO.build();
    final Component link = Message.AUDIO_HTTP.build("http://localhost:3000/");
    this.assertViewerReceived(loading, link);
  }

  @Test
  void shouldSendNoLinkForVoiceChatAudio() {
    this.makeVoiceChatReady();
    this.play(PlayerArgument.FFMPEG, AudioArgument.SIMPLE_VOICE_CHAT, MRL, "");

    final Component loading = Message.LOAD_VIDEO.build();
    this.assertViewerReceived(loading);
    this.verifyPlayedWithDump(AudioArgument.SIMPLE_VOICE_CHAT);
  }

  @Test
  void shouldRefuseVoiceChatAudioWhileItIsOff() {
    this.play(PlayerArgument.FFMPEG, AudioArgument.SIMPLE_VOICE_CHAT, MRL, "");

    final Component error = Message.SIMPLE_VOICE_CHAT_OFF.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
    this.assertNotStarting();
  }

  @Test
  void shouldRefuseTheDiscordBotWhileItIsNotReady() {
    when(this.provider.isDiscordBotEnabled()).thenReturn(true);

    this.play(PlayerArgument.FFMPEG, AudioArgument.DISCORD_BOT, MRL, "");

    final Component error = Message.AUDIO_NOT_READY.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
    this.assertNotStarting();
  }

  @Test
  void shouldRefuseTheWebPageWhileItIsNotReady() {
    when(this.provider.isHttpEnabled()).thenReturn(true);

    this.play(PlayerArgument.FFMPEG, AudioArgument.HTTP_SERVER, MRL, "");

    final Component error = Message.AUDIO_NOT_READY.build();
    this.assertSenderReceived(error);
    verify(this.manager, never()).getService();
  }

  @Test
  void shouldReportAndUnlockWhenTheWorkerRefusesTheVideo() {
    final ExecutorService stopped = Executors.newSingleThreadExecutor();
    stopped.shutdown();
    when(this.manager.getService()).thenReturn(stopped);

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, MRL, "");

    final Component error = Message.VIDEO_START_ERROR.build();
    this.assertSenderReceived(error);
    this.assertNotStarting();
    verify(this.manager, never()).setPlayer(any());
  }

  @Test
  void shouldUnlockWithoutReportingWhenThePluginWasDisabledMeanwhile() throws IOException {
    final BukkitScheduler scheduler = TestServer.getScheduler();
    when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenThrow(new IllegalPluginAccessException("disabled!"));
    final Path file = this.createVideoFile();
    final String mrl = file.toString();

    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    verify(this.player).start(any());
    verify(this.sender, never()).sendMessage(any(Component.class));
    this.assertNotStarting();
  }

  @Test
  void shouldRefuseNullDisplayArguments() {
    final AbstractVideoCommand.VideoConfigurationProvider configuration = _ -> CONFIGURATION;
    final PlayerArgument ffmpeg = PlayerArgument.FFMPEG;
    final AudioArgument none = AudioArgument.NONE;

    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(null, this.sender, this.selector, ffmpeg, none, VIDEO_RESOLUTION, MRL, "", null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, null, this.selector, ffmpeg, none, VIDEO_RESOLUTION, MRL, "", null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, null, ffmpeg, none, VIDEO_RESOLUTION, MRL, "", null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, this.selector, null, none, VIDEO_RESOLUTION, MRL, "", null)
    );

    verify(this.manager, never()).getService();
  }

  @Test
  void shouldRefuseNullPlaybackArguments() {
    final AbstractVideoCommand.VideoConfigurationProvider configuration = _ -> CONFIGURATION;
    final PlayerArgument ffmpeg = PlayerArgument.FFMPEG;
    final AudioArgument none = AudioArgument.NONE;

    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, this.selector, ffmpeg, null, VIDEO_RESOLUTION, MRL, "", null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, this.selector, ffmpeg, none, null, MRL, "", null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, this.selector, ffmpeg, none, VIDEO_RESOLUTION, null, "", null)
    );
    assertThrows(NullPointerException.class, () ->
      this.command.playVideo(configuration, this.sender, this.selector, ffmpeg, none, VIDEO_RESOLUTION, MRL, null, null)
    );

    verify(this.manager, never()).getService();
  }

  @Test
  void shouldRefuseANullPlugin() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> new RecordingCommand(null));
    final String message = exception.getMessage();
    assertEquals("Plugin must not be null!", message);
  }

  @Test
  void shouldReleaseTheStartupClaimWhenViewerSelectionFails() {
    final IllegalStateException failure = new IllegalStateException("selector failed!");
    when(this.selector.values()).thenThrow(failure);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, MISSING_FILE_NAME, "")
    );
    assertSame(failure, thrown);
    this.assertNotStarting();
    verify(this.manager, never()).beginStart();
  }

  @Test
  void shouldReleaseTheStartupClaimWhenLoadingNotificationFails() {
    final IllegalStateException failure = new IllegalStateException("viewer disconnected!");
    Mockito.doThrow(failure).when(this.viewer).sendMessage(any(Component.class));
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, MISSING_FILE_NAME, "")
    );
    assertSame(failure, thrown);
    this.assertNotStarting();
    verify(this.manager, never()).beginStart();
  }

  @Test
  void shouldPreserveStartupFailureWhenClearingPartialOutputsAlsoFails() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final IllegalStateException failure = new IllegalStateException(NATIVE_START_FAILURE_MESSAGE);
    final IllegalArgumentException cleanup = new IllegalArgumentException("clear outputs!");
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
  void shouldNotSuppressStartupFailureOntoItself() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final IllegalStateException failure = new IllegalStateException("same failure!");
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
  void shouldKillAHologramWhoseStartFailsAndPreserveCleanupFailure() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final Location location = mock(Location.class);
    when(this.manager.getHologramLocation()).thenReturn(Optional.of(location));
    final IllegalStateException failure = new IllegalStateException("hologram start!");
    final IllegalArgumentException cleanup = new IllegalArgumentException("hologram kill!");
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
  void shouldPropagateFatalPlayVideoFailureWithoutResettingStartingStatus() {
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    when(this.manager.getService()).thenThrow(fatal);

    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () ->
      this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, MRL, "")
    );
    assertSame(fatal, thrown);

    final boolean starting = this.status.get();
    assertTrue(starting, "status should not be reset to false on fatal error");
  }

  @Test
  void shouldReportFatalPlayerStartFailureWithoutRecoverableCleanup() throws IOException {
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    when(this.player.start(any())).thenThrow(fatal);
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    this.playAndRequireFatalReport(mrl);
    verify(this.manager, times(1)).clearCurrentVideo();
  }

  @Test
  void shouldReportFatalCleanupFailureWithoutSuppressingItOntoTheStartFailure() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final IllegalStateException failure = new IllegalStateException(NATIVE_START_FAILURE_MESSAGE);
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
    when(this.player.start(any())).thenThrow(failure);
    Mockito.doNothing().doThrow(fatal).when(this.manager).clearCurrentVideo();
    this.playAndRequireFatalReport(mrl);
    final Throwable[] suppressed = failure.getSuppressed();
    assertEquals(0, suppressed.length, "fatal cleanup must escape the recoverable-failure handler");
  }

  @Test
  void shouldReportFatalHologramStartFailureWithoutKillingHologramOrClearingManager() throws IOException {
    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final Location location = mock(Location.class);
    when(this.manager.getHologramLocation()).thenReturn(Optional.of(location));
    final OutOfMemoryError fatal = new OutOfMemoryError(FATAL_FAILURE_MESSAGE);
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
  void shouldStopBeforeDisplayCreationWhenCancelledBeforePlayerCreation() throws IOException {
    final AtomicBoolean cancelled = new AtomicBoolean();
    when(this.manager.isCurrent(ArgumentMatchers.anyLong())).thenAnswer(_ -> !cancelled.get());
    Mockito.doAnswer(_ -> this.cancel(cancelled))
      .when(this.manager)
      .clearCurrentVideo();
    Mockito.doAnswer(_ -> this.throwIfCancelled(cancelled, "cancelled before player creation!"))
      .when(this.manager)
      .checkStart();

    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    this.play(PlayerArgument.FFMPEG, AudioArgument.NONE, mrl, "");

    final boolean empty = this.command.resolutions.isEmpty();
    assertTrue(empty, "video filter must not be created when cancelled before player creation");

    verify(this.provider, never()).constructFilter(any(), any(), any());
    verify(this.manager, never()).setPlayer(any());
    verify(this.player, never()).start(any());
    verify(this.player, never()).start(any(), any());
    this.assertNotStarting();
  }

  private @Nullable Object cancel(final AtomicBoolean cancelled) {
    cancelled.set(true);

    return null;
  }

  private @Nullable Object throwIfCancelled(final AtomicBoolean cancelled, final String message) {
    if (cancelled.get()) {
      throw new CancellationException(message);
    }

    return null;
  }

  @Test
  void shouldStopBeforeAudioConstructionWhenCancelledDuringVideoConfiguration() throws IOException {
    final AtomicBoolean cancelled = new AtomicBoolean();
    when(this.manager.isCurrent(ArgumentMatchers.anyLong())).thenAnswer(_ -> !cancelled.get());
    Mockito.doAnswer(_ -> this.throwIfCancelled(cancelled, "cancelled during video configuration!"))
      .when(this.manager)
      .checkStart();

    final Path file = this.createVideoFile();
    final String mrl = file.toString();
    final AbstractVideoCommand.VideoConfigurationProvider configuration = _ -> this.cancelAndConfigure(cancelled);

    this.command.playVideo(
      configuration,
      this.sender,
      this.selector,
      PlayerArgument.FFMPEG,
      AudioArgument.NONE,
      VIDEO_RESOLUTION,
      mrl,
      "",
      null
    );

    final int size = this.command.resolutions.size();
    assertEquals(1, size);

    verify(this.provider, never()).constructFilter(any(), any(), any());
    verify(this.manager, never()).setPlayer(any());
    verify(this.player, never()).start(any());
    verify(this.player, never()).start(any(), any());
    this.assertNotStarting();
  }

  private Object cancelAndConfigure(final AtomicBoolean cancelled) {
    cancelled.set(true);

    return CONFIGURATION;
  }

  @Test
  void shouldStopBeforeNativeStartWhenCancelledDuringAudioConfiguration() throws IOException {
    final AtomicBoolean cancelled = new AtomicBoolean();
    when(this.manager.isCurrent(ArgumentMatchers.anyLong())).thenAnswer(_ -> !cancelled.get());
    when(this.provider.constructFilter(any(), any(), any())).thenAnswer(_ -> this.cancelAndPassNoAudio(cancelled));
    Mockito.doAnswer(_ -> this.throwIfCancelled(cancelled, "cancelled during audio configuration!"))
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

  private AudioFilter cancelAndPassNoAudio(final AtomicBoolean cancelled) {
    cancelled.set(true);

    return AudioFilter.NO_OPERATION;
  }

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
}
