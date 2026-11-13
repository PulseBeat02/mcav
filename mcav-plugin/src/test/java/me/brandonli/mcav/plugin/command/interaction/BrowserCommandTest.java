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
package me.brandonli.mcav.plugin.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import me.brandonli.mcav.browser.BrowserOptions;
import me.brandonli.mcav.browser.BrowserPlayer;
import me.brandonli.mcav.browser.BrowserSource;
import me.brandonli.mcav.browser.BrowserUnavailableException;
import me.brandonli.mcav.browser.PageRefusedException;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.StandardErrorCapture;
import me.brandonli.mcav.plugin.testing.TestServer;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.utils.interaction.MouseClick;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class BrowserCommandTest {

  private static final long RELEASE_MILLISECONDS = 5000;

  private static final String RESOLUTION = "1280x720";
  private static final int FRAME_INTERVAL = 2;
  private static final String BLOCK_DIMENSIONS = "5x3";

  private static final String PAGE_URL = "https://example.com/page";
  private static final String SITE_URL = "https://example.com";
  private static final String AUDIO_HTTP_URL = "http://mc.example.com:3000/";

  private static final String BROWSER_RELEASE_FAILURE_MESSAGE = "release broke!";

  private static final String REFUSED_PAGE_MESSAGE =
    "The browser refused page https://example.com/page, because its address is not public!";

  private static final String START_FAILURE_LOG = "Starting the browser for";

  private BrowserCommand command;
  private MCAVSandbox plugin;
  private AudioProvider provider;
  private PluginDataConfigurationMapper configuration;
  private CommandSender sender;
  private MultiplePlayerSelector selector;
  private Player viewer;
  private BrowserPlayer browser;
  private VideoAttachableCallback callback;
  private FunctionalVideoFilter ditherFilter;
  private MockedStatic<BrowserPlayer> browsers;
  private MockedStatic<DitherFilter> dithers;
  private MockedConstruction<CompressedMapResult> maps;

  @BeforeEach
  void createCommand() {
    final Server server = TestServer.reset();
    final MCAVSandbox plugin = mock(MCAVSandbox.class);
    when(plugin.getOnlinePlayers()).thenReturn(new OnlinePlayers());
    this.plugin = plugin;
    when(plugin.getServer()).thenReturn(server);
    when(plugin.isBrowserSupported()).thenReturn(true);
    this.provider = mock(AudioProvider.class);
    when(plugin.getAudioProvider()).thenReturn(this.provider);
    this.configuration = mock(PluginDataConfigurationMapper.class);
    when(plugin.getConfiguration()).thenReturn(this.configuration);
    when(this.configuration.getMcv2DefaultCodec()).thenReturn(MapCodec.DITHER);
    this.command = new BrowserCommand(plugin);
    this.sender = mock(CommandSender.class);
    this.selector = mock(MultiplePlayerSelector.class);
    this.viewer = mock(Player.class);
    final List<Player> viewers = List.of(this.viewer);
    when(this.selector.values()).thenReturn(viewers);

    this.browser = mock(BrowserPlayer.class);
    this.callback = mock(VideoAttachableCallback.class);
    when(this.browser.getVideoAttachableCallback()).thenReturn(this.callback);
    when(this.browser.getAudioAttachableCallback()).thenReturn(mock(AudioAttachableCallback.class));
    this.browsers = Mockito.mockStatic(BrowserPlayer.class);
    this.browsers.when(() -> BrowserPlayer.create(any(BrowserOptions.class))).thenReturn(this.browser);
    this.ditherFilter = mock(FunctionalVideoFilter.class);
    this.dithers = Mockito.mockStatic(DitherFilter.class);
    this.dithers.when(() -> DitherFilter.dither(any(), any())).thenReturn(this.ditherFilter);
    this.maps = Mockito.mockConstruction(CompressedMapResult.class);
  }

  @AfterEach
  void closeMocks() {
    this.browsers.close();
    this.dithers.close();
    this.maps.close();
    this.command.shutdown();
  }

  @Test
  void shouldOpenThePageInABrowserOnTheScreen() {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);

    this.create(RESOLUTION, BLOCK_DIMENSIONS, PAGE_URL);

    this.assertDithersOntoTheScreen();
    this.assertOpenedThePage();
    assertSame(this.browser, this.command.player);

    final Component started = Message.START_BROWSER.build();
    this.assertReceived(Message.BROWSER_LOADING.build(), started);
  }

  private void create(final String resolution, final String blocks, final String url) {
    this.create(resolution, blocks, AudioArgument.NONE, url);
  }

  private void create(final String resolution, final String blocks, final AudioArgument audio, final String url) {
    this.command.createBrowser(
      this.sender,
      this.selector,
      resolution,
      FRAME_INTERVAL,
      blocks,
      0,
      DitheringArgument.FILTER_LITE,
      audio,
      url,
      null
    );
  }

  private void assertReceived(final Component... expected) {
    final List<Component> messages = Components.received(this.sender);
    final List<Component> expectedMessages = List.of(expected);
    assertEquals(expectedMessages, messages);
  }

  private void assertDithersOntoTheScreen() {
    final ArgumentCaptor<VideoPipelineStep> pipelines = ArgumentCaptor.forClass(VideoPipelineStep.class);
    verify(this.callback).attach(pipelines.capture());

    final VideoPipelineStep pipeline = pipelines.getValue();
    final VideoFilter filter = pipeline.getFilter();
    assertSame(this.ditherFilter, filter);
  }

  private void assertOpenedThePage() {
    final ArgumentCaptor<BrowserSource> sources = ArgumentCaptor.forClass(BrowserSource.class);
    verify(this.browser).startAsync(sources.capture(), any());

    final BrowserSource source = sources.getValue();
    final URI uri = source.getUri();
    final URI expected = URI.create(PAGE_URL);
    final int width = source.getWidth();
    final int height = source.getHeight();
    final int nth = source.getFrameInterval();
    assertEquals(expected, uri);
    assertEquals(1280, width);
    assertEquals(720, height);
    assertEquals(FRAME_INTERVAL, nth);
  }

  @Test
  void shouldTellAServerTheBrowserDoesNotRunOnSoAndGiveItNoBrowser() {
    when(this.plugin.isBrowserSupported()).thenReturn(false);

    this.create(RESOLUTION, BLOCK_DIMENSIONS, PAGE_URL);

    this.assertReceived(Message.BROWSER_UNSUPPORTED.build());
    this.browsers.verify(() -> BrowserPlayer.create(any(BrowserOptions.class)), never());
    assertNull(this.command.output);
  }

  @Test
  void shouldPlayTheSoundOfThePageIntoTheChosenOutputAndLetGoOfItOnRelease() {
    this.makeVoiceChatReady();
    final AudioFilter output = mock(AudioFilter.class);
    when(this.provider.constructFilter(eq(AudioArgument.SIMPLE_VOICE_CHAT), any(), any(), eq(this.browser))).thenReturn(output);
    final AudioAttachableCallback audio = mock(AudioAttachableCallback.class);
    when(this.browser.getAudioAttachableCallback()).thenReturn(audio);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(CompletableFuture.completedFuture(true));

    this.create(RESOLUTION, BLOCK_DIMENSIONS, AudioArgument.SIMPLE_VOICE_CHAT, PAGE_URL);

    final ArgumentCaptor<AudioPipelineStep> pipelines = ArgumentCaptor.forClass(AudioPipelineStep.class);
    verify(audio).attach(pipelines.capture());

    final AudioFilter filter = pipelines.getValue().getFilter();
    assertSame(output, filter);

    this.command.releaseBrowser(this.sender);
    verify(audio).detach();
    verify(this.provider).releaseAudioFilter(this.browser);
  }

  private void makeVoiceChatReady() {
    when(this.provider.isSimpleVoiceChatEnabled()).thenReturn(true);
    when(this.provider.isSimpleVoiceChatReady()).thenReturn(true);
  }

  @Test
  void shouldLetGoOfTheSoundWhenTheReleaseFailsAndWhileThePluginDisables() {
    this.makeVoiceChatReady();
    when(this.provider.constructFilter(eq(AudioArgument.SIMPLE_VOICE_CHAT), any(), any(), eq(this.browser))).thenReturn(
      mock(AudioFilter.class)
    );
    when(this.browser.getAudioAttachableCallback()).thenReturn(mock(AudioAttachableCallback.class));
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(CompletableFuture.completedFuture(true));
    this.create(RESOLUTION, BLOCK_DIMENSIONS, AudioArgument.SIMPLE_VOICE_CHAT, PAGE_URL);
    when(this.plugin.getAudioProvider()).thenThrow(new IllegalStateException("The audio provider is not available!"));
    Mockito.doThrow(new IllegalStateException(BROWSER_RELEASE_FAILURE_MESSAGE)).when(this.browser).release();
    this.command.releaseBrowser(this.sender);
    verify(this.provider).releaseAudioFilter(this.browser);
    verify(this.callback).detach();
    verify(this.browser, timeout(RELEASE_MILLISECONDS)).release();
  }

  @Test
  void shouldFailTheShutdownWhenABrowserFailsToStopWhileTheCommandShutsDown() {
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(CompletableFuture.completedFuture(true));
    this.create(RESOLUTION, BLOCK_DIMENSIONS, PAGE_URL);
    Mockito.doThrow(new IllegalStateException(BROWSER_RELEASE_FAILURE_MESSAGE)).when(this.browser).release();
    final IllegalStateException failure = assertThrows(IllegalStateException.class, this.command::shutdown);

    final String message = failure.getMessage();
    assertEquals(BROWSER_RELEASE_FAILURE_MESSAGE, message);
  }

  @Test
  void shouldSendTheLinkOfABrowserThatStartsWithTheWebPageAndNoneForOneThatFails() {
    final List<Component> expected = List.of(Message.AUDIO_HTTP.build(AUDIO_HTTP_URL));
    this.createWithTheWebPage(CompletableFuture.completedFuture(true));
    verify(this.provider).constructHttpUrl();

    final List<Component> received = Components.received(this.viewer);
    assertEquals(expected, received);

    final List<String> receivedText = Components.receivedText(this.viewer);
    final List<String> expectedText = List.of("Click on the URL to listen onto the website! http://mc.example.com:3000/");
    assertEquals(expectedText, receivedText);

    this.createWithTheWebPage(CompletableFuture.failedFuture(new IllegalStateException("the page broke!")));
    verify(this.provider).constructHttpUrl();

    final List<Component> componentsReceived = Components.received(this.viewer);
    assertEquals(expected, componentsReceived);

    final List<String> componentsReceivedText = Components.receivedText(this.viewer);
    final List<String> expectedComponentsText = List.of("Click on the URL to listen onto the website! http://mc.example.com:3000/");
    assertEquals(expectedComponentsText, componentsReceivedText);
  }

  private void createWithTheWebPage(final CompletableFuture<Boolean> start) {
    when(this.provider.isHttpEnabled()).thenReturn(true);
    when(this.provider.isHttpReady()).thenReturn(true);
    when(this.provider.constructHttpUrl()).thenReturn(AUDIO_HTTP_URL);
    when(this.provider.constructFilter(eq(AudioArgument.HTTP_SERVER), any(), any(), eq(this.browser))).thenReturn(mock(AudioFilter.class));
    when(this.browser.getAudioAttachableCallback()).thenReturn(mock(AudioAttachableCallback.class));
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);
    this.create(RESOLUTION, BLOCK_DIMENSIONS, AudioArgument.HTTP_SERVER, PAGE_URL);
  }

  @Test
  void shouldStartNoBrowserForAnAudioOutputThatCannotPlay() {
    this.create(RESOLUTION, BLOCK_DIMENSIONS, AudioArgument.DISCORD_BOT, PAGE_URL);

    this.assertReceived(Message.DISCORD_BOT_OFF.build());
    this.browsers.verify(() -> BrowserPlayer.create(any(BrowserOptions.class)), never());
  }

  @Test
  void shouldKeepThePageSilentWithoutAnAudioOutput() {
    final AudioAttachableCallback audio = mock(AudioAttachableCallback.class);
    when(this.browser.getAudioAttachableCallback()).thenReturn(audio);
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);

    this.create(RESOLUTION, BLOCK_DIMENSIONS, PAGE_URL);

    verify(audio, never()).attach(any());
    verify(this.provider, never()).constructFilter(any(), any(), any(), any());

    verify(this.viewer, never()).sendMessage(any(Component.class));
  }

  @Test
  void shouldKeepTheRunningBrowserWhenTheNextOneIsRefused() {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);
    this.create(RESOLUTION, BLOCK_DIMENSIONS, PAGE_URL);

    this.create(RESOLUTION, BLOCK_DIMENSIONS, AudioArgument.DISCORD_BOT, SITE_URL);

    assertSame(this.browser, this.command.player);
    assertSame(this.ditherFilter, this.command.output);
    verify(this.callback, never()).detach();
    verify(this.ditherFilter, never()).release();
    this.browsers.verify(() -> BrowserPlayer.create(any(BrowserOptions.class)));

    final Component started = Message.START_BROWSER.build();
    final Component refused = Message.DISCORD_BOT_OFF.build();
    this.assertReceived(Message.BROWSER_LOADING.build(), started, refused);
  }

  @Test
  void shouldDescribeABrowserThatCannotRunOnThisServerSo() {
    final BrowserUnavailableException unavailable = new BrowserUnavailableException("The browser cannot be installed: offline!");

    final Component direct = this.command.createStartMessage(StartOutcome.FAILED, unavailable);
    final Component wrapped = this.command.createStartMessage(StartOutcome.FAILED, new CompletionException(unavailable));
    final Component other = this.command.createStartMessage(
      StartOutcome.FAILED,
      new CompletionException(new IllegalStateException("page!"))
    );

    final Component expected = Message.BROWSER_UNAVAILABLE.build();
    assertEquals(expected, direct);

    final Component expectedUnavailable = Message.BROWSER_UNAVAILABLE.build();
    assertEquals(expectedUnavailable, wrapped);

    final Component expectedError = Message.BROWSER_ERROR.build();
    assertEquals(expectedError, other);
  }

  @Test
  void shouldTellTheSenderThatThePageWasRefused() {
    final PageRefusedException refused = new PageRefusedException(REFUSED_PAGE_MESSAGE);

    final Component direct = this.command.createStartMessage(StartOutcome.FAILED, refused);
    final Component wrapped = this.command.createStartMessage(StartOutcome.FAILED, new CompletionException(refused));

    final Component expected = Message.BROWSER_REFUSED.build();
    assertEquals(expected, direct);
    assertEquals(expected, wrapped);
  }

  @Test
  void shouldCountOnlyARefusedPageAsARefusal() {
    final PageRefusedException refused = new PageRefusedException(REFUSED_PAGE_MESSAGE);

    final boolean direct = this.command.isRefusal(refused);
    final boolean wrapped = this.command.isRefusal(new CompletionException(refused));
    final boolean other = this.command.isRefusal(new CompletionException(new IllegalStateException("page!")));
    assertTrue(direct);
    assertTrue(wrapped);
    assertFalse(other);
  }

  @Test
  void shouldLogNoErrorForAPageThatWasRefused() {
    final PageRefusedException refused = new PageRefusedException(REFUSED_PAGE_MESSAGE);
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(new CompletionException(refused));
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);
    final String output;
    try (final StandardErrorCapture capture = StandardErrorCapture.start()) {
      this.create(RESOLUTION, BLOCK_DIMENSIONS, PAGE_URL);
      verify(this.browser, timeout(RELEASE_MILLISECONDS)).release();
      output = capture.getOutput();
    }

    final boolean logged = output.contains(START_FAILURE_LOG);
    assertFalse(logged, output);

    final Component refusedMessage = Message.BROWSER_REFUSED.build();
    this.assertReceived(Message.BROWSER_LOADING.build(), refusedMessage);
  }

  @Test
  void shouldLogABrowserThatFailedToStartAsAnErrorWithItsPage() {
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(new IllegalStateException("page load!"));
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);
    final String output;
    try (final StandardErrorCapture capture = StandardErrorCapture.start()) {
      this.create(RESOLUTION, BLOCK_DIMENSIONS, PAGE_URL);
      verify(this.browser, timeout(RELEASE_MILLISECONDS)).release();
      output = capture.getOutput();
    }

    final String startFailure = "%s %s failed.".formatted(START_FAILURE_LOG, PAGE_URL);
    final boolean logged = output.contains(startFailure);
    final boolean error = output.contains("ERROR");
    assertTrue(logged, output);
    assertTrue(error, output);

    final Component failed = Message.BROWSER_ERROR.build();
    this.assertReceived(Message.BROWSER_LOADING.build(), failed);
  }

  @Test
  void shouldReleaseTheBrowserWhenItFailsToStart() {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(false);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);

    this.create(RESOLUTION, BLOCK_DIMENSIONS, PAGE_URL);

    verify(this.browser, timeout(RELEASE_MILLISECONDS)).release();
    assertNull(this.command.player);
    assertNull(this.command.output);

    final Component failed = Message.BROWSER_ERROR.build();
    this.assertReceived(Message.BROWSER_LOADING.build(), failed);
  }

  @Test
  void shouldNeedTheInteractPermissionForInput() {
    final String permission = this.command.getInteractionPermission();
    assertEquals("mcav.command.browser.interact", permission, "clicks and chat are the input of the interact subcommand");
  }

  @Test
  void shouldRefuseInvalidResolutions() {
    this.create("big", BLOCK_DIMENSIONS, PAGE_URL);

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();
  }

  @Test
  void shouldRefuseInvalidScreenSizes() {
    this.create(RESOLUTION, "0x3", PAGE_URL);

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();
  }

  @Test
  void shouldRefuseScreensLargerThanTheLimit() {
    this.create(RESOLUTION, "5x65", PAGE_URL);

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();
  }

  @Test
  void shouldRefuseResolutionsLargerThanTheLimit() {
    this.create("8193x720", BLOCK_DIMENSIONS, PAGE_URL);

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();
  }

  @Test
  void shouldRefuseBrowsersLargerThanTheBrowserCanPaint() {
    this.create("4097x720", BLOCK_DIMENSIONS, PAGE_URL);
    this.create("1280x4097", BLOCK_DIMENSIONS, PAGE_URL);

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error, error);
    this.browsers.verifyNoInteractions();
  }

  @Test
  void shouldStartABrowserOfTheLargestSizeTheBrowserCanPaint() {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);
    this.create("4096x4096", BLOCK_DIMENSIONS, PAGE_URL);
    final ArgumentCaptor<BrowserSource> sources = ArgumentCaptor.forClass(BrowserSource.class);
    verify(this.browser).startAsync(sources.capture(), any());

    final int width = sources.getValue().getWidth();
    assertEquals(4096, width);

    final int height = sources.getValue().getHeight();
    assertEquals(4096, height);
  }

  @Test
  void shouldLetTheBrowserFollowTheConfiguration() {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);
    this.create(RESOLUTION, BLOCK_DIMENSIONS, PAGE_URL);
    this.browsers.verify(this::createWithConfiguredOptions);

    when(this.configuration.isBrowserPrivateNetworks()).thenReturn(true);
    when(this.configuration.isBrowserJavaScriptJit()).thenReturn(true);
    when(this.configuration.isBrowserAutoplaySound()).thenReturn(true);
    when(this.configuration.isBrowserConfineChromium()).thenReturn(true);
    when(this.configuration.getBrowserRefusedHosts()).thenReturn(List.of("203.0.113.5"));
    final BrowserOptions options = this.command.createOptions();

    final boolean privateNetworks = options.isPrivateNetworks();
    assertTrue(privateNetworks);

    final boolean javaScriptJit = options.isJavaScriptJit();
    assertTrue(javaScriptJit);

    final boolean autoplay = options.isAutoplay();
    assertTrue(autoplay);

    final boolean confined = options.isConfined();
    assertTrue(confined);

    final List<String> refusedHosts = options.getRefusedHosts();
    final List<String> expectedRefusedHosts = List.of("203.0.113.5");
    assertEquals(expectedRefusedHosts, refusedHosts);
  }

  private void createWithConfiguredOptions() {
    BrowserPlayer.create(
      argThat(options -> !options.isPrivateNetworks() && !options.isJavaScriptJit() && !options.isAutoplay() && !options.isConfined())
    );
  }

  @Test
  void shouldRefuseInvalidUrls() {
    this.create(RESOLUTION, BLOCK_DIMENSIONS, "https://exa mple.com/page");

    final Component error = Message.UNSUPPORTED_URL.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();

    final List<CompressedMapResult> created = this.maps.constructed();

    final int size = created.size();
    assertEquals(0, size);
  }

  @Test
  void shouldReleaseTheScreenWhenTheBrowserFactoryFails() {
    final IllegalStateException failure = new IllegalStateException("browser factory!");
    this.browsers.when(() -> BrowserPlayer.create(any(BrowserOptions.class))).thenThrow(failure);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.create(RESOLUTION, BLOCK_DIMENSIONS, SITE_URL)
    );
    assertSame(failure, thrown);
    this.assertCreatedScreenReleased();
    verify(this.browser, never()).release();
  }

  private void assertCreatedScreenReleased() {
    final List<CompressedMapResult> created = this.maps.constructed();

    final int size = created.size();
    assertEquals(1, size);

    // the screen's output owns its maps: releasing it releases them
    verify(this.ditherFilter).release();
    assertNull(this.command.player);
    assertNull(this.command.output);
  }

  @Test
  void shouldReleaseTheBrowserAndScreenWhenCallbackAttachmentFails() {
    final IllegalStateException failure = new IllegalStateException("callback attach!");
    Mockito.doThrow(failure).when(this.callback).attach(any(VideoPipelineStep.class));
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.create(RESOLUTION, BLOCK_DIMENSIONS, SITE_URL)
    );
    assertSame(failure, thrown);
    verify(this.browser, timeout(RELEASE_MILLISECONDS)).release();
    this.assertCreatedScreenReleased();
  }

  @Test
  void shouldReleaseTheBrowserAndScreenWhenSubmissionIsRejected() {
    final RejectedExecutionException failure = new RejectedExecutionException("executor stopped!");
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenThrow(failure);
    final RejectedExecutionException thrown = assertThrows(RejectedExecutionException.class, () ->
      this.create(RESOLUTION, BLOCK_DIMENSIONS, SITE_URL)
    );
    assertSame(failure, thrown);
    verify(this.browser, timeout(RELEASE_MILLISECONDS)).release();
    this.assertCreatedScreenReleased();
  }

  @Test
  void shouldReleaseTheUnpublishedScreenWhenFilterStartupFails() {
    final IllegalStateException failure = new IllegalStateException("display start!");
    Mockito.doThrow(failure).when(this.ditherFilter).start();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.create(RESOLUTION, BLOCK_DIMENSIONS, SITE_URL)
    );
    assertSame(failure, thrown);
    this.assertCreatedScreenReleased();
    this.browsers.verifyNoInteractions();
  }

  @Test
  void shouldForwardClicksAndTextToTheBrowser() {
    this.command.forwardLeftClick(this.browser, 10, 20);
    this.command.forwardRightClick(this.browser, 30, 40);
    this.command.forwardTextInput(this.browser, "hello");

    verify(this.browser).sendMouseEvent(MouseClick.LEFT, 10, 20);
    verify(this.browser).sendMouseEvent(MouseClick.RIGHT, 30, 40);
    verify(this.browser).sendKeyEvent("hello");
  }

  @Test
  void shouldReleaseTheBrowserWhenAsked() {
    this.command.player = this.browser;

    this.command.releaseBrowser(this.sender);

    verify(this.browser, timeout(RELEASE_MILLISECONDS)).release();
    assertNull(this.command.player);

    final Component released = Message.RELEASE_BROWSER.build();
    this.assertReceived(released);

    this.command.releaseBrowser(this.sender);
    this.command.shutdown();

    verify(this.browser).release();
    this.assertReceived(released, released);
  }

  @Test
  void shouldToggleTheChatInteractionOfAPlayer() {
    final Player player = mock(Player.class);

    this.command.toggleInteraction(player);
    this.command.toggleInteraction(player);

    final Component enabled = Message.INTERACT_ENABLE.build();
    final Component disabled = Message.INTERACT_DISABLE.build();
    final List<Component> messages = Components.received(player);
    final List<Component> expectedMessages = List.of(enabled, disabled);
    assertEquals(expectedMessages, messages);
    verify(this.browser, never()).release();
  }

  @Test
  void shouldDescribeTheOutcomeOfTheStart() {
    final IllegalStateException error = new IllegalStateException("no browser!");

    final Component success = this.command.createStartMessage(StartOutcome.STARTED, null);
    final Component failure = this.command.createStartMessage(StartOutcome.FAILED, error);

    final Component started = Message.START_BROWSER.build();
    final Component notStarted = Message.BROWSER_ERROR.build();
    assertEquals(started, success);
    assertEquals(notStarted, failure);
  }

  @Test
  void shouldReportABrowserThatCannotStartWithoutBlamingTheUrl() {
    final IllegalStateException missingNatives = new IllegalStateException("The browser cannot be installed: no route to host!");
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(missingNatives);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);

    this.create(RESOLUTION, BLOCK_DIMENSIONS, PAGE_URL);

    verify(this.browser, timeout(RELEASE_MILLISECONDS)).release();

    final Component failed = Message.BROWSER_ERROR.build();
    this.assertReceived(Message.BROWSER_LOADING.build(), failed);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "file:///etc/passwd",
      "file:///C:/server/plugins/MCAV/config.yml",
      "data:text/html,hello",
      "chrome://settings",
      "javascript:alert(1)",
      "about:blank",
      "ftp://example.com/file",
      "/plugins/MCAV/config.yml",
      "example.com/page",
      "https:///no-host",
      "http:example.com",
    }
  )
  void shouldRefuseAddressesThatAreNotWebPages(final String url) {
    this.create(RESOLUTION, BLOCK_DIMENSIONS, url);

    final Component error = Message.UNSUPPORTED_URL.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();

    final List<CompressedMapResult> created = this.maps.constructed();

    final int size = created.size();
    assertEquals(0, size);
  }

  @ParameterizedTest
  @ValueSource(strings = { "http://example.com", "https://example.com/page?q=1#top", "HTTPS://Example.com/" })
  void shouldAcceptWebPageAddresses(final String url) {
    final URI uri = this.command.parseWebAddress(url);

    final URI expected = URI.create(url);
    assertEquals(expected, uri);
  }
}
