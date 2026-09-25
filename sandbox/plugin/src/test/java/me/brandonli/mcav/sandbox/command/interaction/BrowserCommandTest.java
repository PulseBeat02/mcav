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
package me.brandonli.mcav.sandbox.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.browser.BrowserPlayer;
import me.brandonli.mcav.browser.BrowserSource;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.TestServer;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
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

/**
 * Tests {@link BrowserCommand}. The browser, the maps and the dithering are mocked.
 */
final class BrowserCommandTest {

  private BrowserCommand command;
  private CommandSender sender;
  private MultiplePlayerSelector selector;
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
    when(plugin.getServer()).thenReturn(server);
    this.command = new BrowserCommand(plugin);
    this.sender = mock(CommandSender.class);
    this.selector = mock(MultiplePlayerSelector.class);
    final Player viewer = mock(Player.class);
    final List<Player> viewers = List.of(viewer);
    when(this.selector.values()).thenReturn(viewers);

    this.browser = mock(BrowserPlayer.class);
    this.callback = mock(VideoAttachableCallback.class);
    when(this.browser.getVideoAttachableCallback()).thenReturn(this.callback);
    this.browsers = Mockito.mockStatic(BrowserPlayer.class);
    this.browsers.when(BrowserPlayer::selenium).thenReturn(this.browser);
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

  private void create(final String resolution, final String blocks, final String url) {
    this.command.createBrowser(this.sender, this.selector, resolution, 80, 2, blocks, 0, DitheringArgument.FILTER_LITE, url);
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
    final URI expected = URI.create("https://example.com/page");
    final int quality = source.getScreencastQuality();
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    final int nth = source.getScreencastNthFrame();
    assertEquals(expected, uri);
    assertEquals(80, quality);
    assertEquals(1280, width);
    assertEquals(720, height);
    assertEquals(2, nth);
  }

  @Test
  void opensThePageInABrowserOnTheScreen() {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(true);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);

    this.create("1280x720", "5x3", "https://example.com/page");

    this.assertDithersOntoTheScreen();
    this.assertOpenedThePage();
    assertSame(this.browser, this.command.player);
    final Component started = Message.START_BROWSER.build();
    this.assertReceived(started);
  }

  @Test
  void releasesTheBrowserWhenItFailsToStart() {
    final CompletableFuture<Boolean> start = CompletableFuture.completedFuture(false);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);

    this.create("1280x720", "5x3", "https://example.com/page");

    verify(this.browser).release();
    assertNull(this.command.player);
    assertNull(this.command.result);
    final Component failed = Message.BROWSER_ERROR.build();
    this.assertReceived(failed);
  }

  @Test
  void needsTheInteractPermissionForInput() {
    final String permission = this.command.getInteractionPermission();
    assertEquals("mcav.browser.interact", permission, "clicks and chat are the input of the interact subcommand");
  }

  @Test
  void refusesInvalidResolutions() {
    this.create("big", "5x3", "https://example.com/page");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();
  }

  @Test
  void refusesInvalidScreenSizes() {
    this.create("1280x720", "0x3", "https://example.com/page");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();
  }

  @Test
  void refusesScreensLargerThanTheLimit() {
    this.create("1280x720", "5x65", "https://example.com/page");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();
  }

  @Test
  void refusesResolutionsLargerThanTheLimit() {
    this.create("8193x720", "5x3", "https://example.com/page");

    final Component error = Message.UNSUPPORTED_DIMENSION.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();
  }

  @Test
  void refusesInvalidUrls() {
    this.create("1280x720", "5x3", "https://exa mple.com/page");

    final Component error = Message.UNSUPPORTED_URL.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();
    final List<CompressedMapResult> created = this.maps.constructed();
    assertEquals(0, created.size());
  }

  private void assertCreatedScreenReleased() {
    final List<CompressedMapResult> created = this.maps.constructed();
    assertEquals(1, created.size());
    final CompressedMapResult screen = created.getFirst();
    verify(screen).release();
    assertNull(this.command.player);
    assertNull(this.command.result);
  }

  @Test
  void releasesTheScreenWhenTheBrowserFactoryFails() {
    final IllegalStateException failure = new IllegalStateException("browser factory");
    this.browsers.when(BrowserPlayer::selenium).thenThrow(failure);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.create("1280x720", "5x3", "https://example.com")
    );
    assertSame(failure, thrown);
    this.assertCreatedScreenReleased();
    verify(this.browser, never()).release();
  }

  @Test
  void releasesTheBrowserAndScreenWhenCallbackAttachmentFails() {
    final IllegalStateException failure = new IllegalStateException("callback attach");
    Mockito.doThrow(failure).when(this.callback).attach(any(VideoPipelineStep.class));
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.create("1280x720", "5x3", "https://example.com")
    );
    assertSame(failure, thrown);
    verify(this.browser).release();
    this.assertCreatedScreenReleased();
  }

  @Test
  void releasesTheBrowserAndScreenWhenSubmissionIsRejected() {
    final java.util.concurrent.RejectedExecutionException failure = new java.util.concurrent.RejectedExecutionException("executor stopped");
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenThrow(failure);
    final java.util.concurrent.RejectedExecutionException thrown = assertThrows(java.util.concurrent.RejectedExecutionException.class, () ->
      this.create("1280x720", "5x3", "https://example.com")
    );
    assertSame(failure, thrown);
    verify(this.browser).release();
    this.assertCreatedScreenReleased();
  }

  @Test
  void releasesTheUnpublishedScreenWhenFilterStartupFails() {
    final IllegalStateException failure = new IllegalStateException("display start");
    Mockito.doThrow(failure).when(this.ditherFilter).start();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
      this.create("1280x720", "5x3", "https://example.com")
    );
    assertSame(failure, thrown);
    this.assertCreatedScreenReleased();
    this.browsers.verifyNoInteractions();
  }

  @Test
  void forwardsClicksAndTextToTheBrowser() {
    this.command.handleLeftClick(this.browser, 10, 20);
    this.command.handleRightClick(this.browser, 30, 40);
    this.command.handleTextInput(this.browser, "hello");

    verify(this.browser).sendMouseEvent(MouseClick.LEFT, 10, 20);
    verify(this.browser).sendMouseEvent(MouseClick.RIGHT, 30, 40);
    verify(this.browser).sendKeyEvent("hello");
  }

  @Test
  void releasesTheBrowserWhenAsked() {
    this.command.player = this.browser;

    this.command.releaseBrowser(this.sender);

    verify(this.browser).release();
    assertNull(this.command.player);
    final Component released = Message.RELEASE_BROWSER.build();
    this.assertReceived(released);
    this.command.releaseBrowser(this.sender);
    verify(this.browser).release();
  }

  @Test
  void togglesTheChatInteractionOfAPlayer() {
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
  void describesTheOutcomeOfTheStart() {
    final IllegalStateException error = new IllegalStateException("no chrome");

    final Component success = this.command.createStartMessage(true, null);
    final Component failure = this.command.createStartMessage(false, error);

    final Component started = Message.START_BROWSER.build();
    final Component notStarted = Message.BROWSER_ERROR.build();
    assertEquals(started, success);
    assertEquals(notStarted, failure);
  }

  @Test
  void reportsABrowserThatCannotStartWithoutBlamingTheUrl() {
    final IllegalStateException missingDriver = new IllegalStateException("chromedriver not found");
    final CompletableFuture<Boolean> start = CompletableFuture.failedFuture(missingDriver);
    when(this.browser.startAsync(any(BrowserSource.class), any())).thenReturn(start);

    this.create("1280x720", "5x3", "https://example.com/page");

    verify(this.browser).release();
    final Component failed = Message.BROWSER_ERROR.build();
    this.assertReceived(failed);
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
  void refusesAddressesThatAreNotWebPages(final String url) {
    this.create("1280x720", "5x3", url);

    final Component error = Message.UNSUPPORTED_URL.build();
    this.assertReceived(error);
    this.browsers.verifyNoInteractions();
    final List<CompressedMapResult> created = this.maps.constructed();
    assertEquals(0, created.size());
  }

  @ParameterizedTest
  @ValueSource(strings = { "http://example.com", "https://example.com/page?q=1#top", "HTTPS://Example.com/" })
  void acceptsWebPageAddresses(final String url) {
    final URI uri = BrowserCommand.parseWebAddress(url);

    final URI expected = URI.create(url);
    assertEquals(expected, uri);
  }
}
