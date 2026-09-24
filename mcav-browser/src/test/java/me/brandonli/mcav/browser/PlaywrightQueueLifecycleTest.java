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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.Frames;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.junit.jupiter.api.Test;

/** Tests input ownership at exact queue/pump boundaries without creating a native browser. */
final class PlaywrightQueueLifecycleTest {

  @Test
  void disconnectionDuringFrameAcknowledgmentEndsPlaybackAndClosesOwnedResources() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      final Consumer<Browser> listener = Objects.requireNonNull(fixture.disconnected.get());
      when(fixture.cdp.send(anyString(), any(JsonObject.class))).thenAnswer(_ -> {
        listener.accept(fixture.browser);
        throw new AssertionError("a disconnected browser cannot finish the pending acknowledgment");
      });
      final JsonObject frame = new JsonObject();
      frame.addProperty("sessionId", 1);
      fixture.pumpEvent.set(() -> fixture.player.onFrame(fixture.cdp, frame));
      fixture.finishPump.countDown();
      Await.until("browser failure closes both owned resources", () -> !fixture.player.isPlaying());
      final Thread capture = fixture.captureThread();
      capture.join(2_000L);
      assertTrue(!capture.isAlive());
      assertEquals(List.of("The Playwright browser failed"), fixture.messages);
      fixture.releasePlayer();
      verify(fixture.browser).offDisconnected(listener);
      verify(fixture.browser).close();
      verify(fixture.playwright).close();
      listener.accept(fixture.browser);
      assertEquals(1, fixture.messages.size(), "late callbacks after release cannot report another failure");
    }
  }

  @Test
  void wakesTheIdleCaptureThreadWhenAFrameArrives() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      final VideoAttachableCallback callback = fixture.player.getVideoAttachableCallback();
      final CountDownLatch delivered = new CountDownLatch(1);
      final AtomicInteger receivedColor = new AtomicInteger();
      final VideoPipelineStepBuilder builder = PipelineBuilder.video();
      builder.then((image, metadata) -> {
        final int[] colors = image.getPixels();
        receivedColor.set(colors[0] & 0xFFFFFF);
        delivered.countDown();
        return false;
      });
      final VideoPipelineStep pipeline = builder.build();
      callback.attach(pipeline);
      final Thread capture = fixture.captureThread();
      Await.until("capture parks before the notification", () -> capture.getState() == Thread.State.TIMED_WAITING);
      final byte[] jpeg = Frames.jpeg(4, 4, 0x00FF00);
      final Base64.Encoder encoder = Base64.getEncoder();
      final String data = encoder.encodeToString(jpeg);
      final JsonObject frame = new JsonObject();
      frame.addProperty("data", data);
      fixture.player.onFrame(fixture.cdp, frame);
      final boolean received = delivered.await(2, TimeUnit.SECONDS);
      assertTrue(received, "the notification must wake capture before its thirty-second poll");
      final int color = receivedColor.get();
      final boolean green = Frames.isNear(0x00FF00, color);
      assertTrue(green);
    }
  }

  @Test
  void wakesTheIdleCaptureThreadWhenTheBrowserFails() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      final Thread capture = fixture.captureThread();
      Await.until("capture parks before browser failure", () -> capture.getState() == Thread.State.TIMED_WAITING);
      when(fixture.page.isClosed()).thenReturn(true);
      fixture.finishPump.countDown();
      capture.join(2_000L);
      assertTrue(!capture.isAlive(), "session failure must wake capture without explicit release");
      assertEquals(List.of("The Playwright browser failed"), fixture.messages);
    }
  }

  @Test
  void sendsConfiguredScreencastParameters() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      final JsonObject expected = new JsonObject();
      expected.addProperty("format", "jpeg");
      expected.addProperty("quality", 80);
      expected.addProperty("maxWidth", 4);
      expected.addProperty("maxHeight", 4);
      expected.addProperty("everyNthFrame", 1);
      verify(fixture.cdp).send("Page.startScreencast", expected);
    }
  }

  @Test
  void closesBothOwnedBrowserResources() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      fixture.releasePlayer();
      verify(fixture.browser).close();
      verify(fixture.playwright).close();
    }
  }

  @Test
  void reportsAFullQueueOnTheCurrentPlayingSession() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      fixture.fillInputQueue();
      fixture.player.sendKeyEvent("overflow");
      assertEquals(List.of("Browser input queue is full"), fixture.messages);
      final Throwable failure = fixture.errors.getFirst();
      assertInstanceOf(RejectedExecutionException.class, failure);
      verify(fixture.keyboard, never()).type(anyString());
    }
  }

  @Test
  void dropsAnAlreadyPolledActionAfterRelease() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      fixture.player.sendKeyEvent("late");
      final Runnable polled = fixture.pollInput();
      fixture.releasePlayer();
      polled.run();
      verify(fixture.keyboard, never()).type(anyString());
      assertTrue(fixture.messages.isEmpty(), fixture.messages::toString);
    }
  }

  @Test
  void dropsQueuedInputAfterTheSameSessionFails() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      fixture.player.sendKeyEvent("positive control");
      final Runnable first = fixture.pollInput();
      first.run();
      verify(fixture.keyboard).type("positive control");
      fixture.player.sendKeyEvent("late");
      final Runnable queued = fixture.pollInput();
      fixture.player.fail("browser failed", new IllegalStateException("disconnected"));
      queued.run();
      verify(fixture.keyboard, never()).type("late");
      assertEquals(List.of("browser failed"), fixture.messages);
    }
  }

  @Test
  void doesNotReportQueueOverflowAfterTheSameSessionFailsDuringSubmission() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      fixture.fillInputQueue();
      final AtomicBoolean firstCheck = new AtomicBoolean(true);
      doAnswer(invocation -> {
        if (firstCheck.getAndSet(false)) {
          fixture.player.fail("browser failed", new IllegalStateException("disconnected"));
          return true;
        }
        return invocation.callRealMethod();
      })
        .when(fixture.player)
        .canForwardInput();
      fixture.player.sendKeyEvent("racing failure");
      assertEquals(List.of("browser failed"), fixture.messages);
    }
  }

  @Test
  void doesNotReportQueueOverflowAfterReleaseOvertakesSubmission() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      final AtomicBoolean firstCheck = new AtomicBoolean(true);
      doAnswer(invocation -> {
        if (firstCheck.getAndSet(false)) {
          fixture.releasePlayer();
          return true;
        }
        return invocation.callRealMethod();
      })
        .when(fixture.player)
        .canForwardInput();
      fixture.player.sendKeyEvent("racing release");
      assertTrue(fixture.messages.isEmpty(), fixture.messages::toString);
      verify(fixture.keyboard, never()).type(anyString());
    }
  }

  @Test
  void detachesThePreviousScreencastBeforeFollowingAPopup() throws Exception {
    try (final HeldBrowser fixture = new HeldBrowser()) {
      final Page popup = mock(Page.class);
      final CDPSession popupSession = mock(CDPSession.class);
      when(fixture.context.newCDPSession(popup)).thenAnswer(_ -> {
        verify(fixture.cdp).detach();
        return popupSession;
      });
      final CountDownLatch attached = new CountDownLatch(1);
      fixture.pumpEvent.set(() -> {
        fixture.pumpEvent.set(() -> {});
        final Consumer<Page> listener = fixture.popupListener.get();
        assertNotNull(listener);
        listener.accept(popup);
        attached.countDown();
      });
      fixture.finishPump.countDown();
      final boolean followed = attached.await(5, TimeUnit.SECONDS);
      assertTrue(followed, "the popup must replace the previous capture session");
      verify(popupSession).send(eq("Page.startScreencast"), any(JsonObject.class));
    }
  }

  /** Real player lifecycle and real bounded Session queue, with only the browser transport held at its pump. */
  private static final class HeldBrowser implements AutoCloseable {

    private final PlaywrightPlayer player;
    private final Playwright playwright = mock(Playwright.class);
    private final Browser browser = mock(Browser.class);
    private final CDPSession cdp = mock(CDPSession.class);
    private final Page page = mock(Page.class);
    private final BrowserContext context = mock(BrowserContext.class);
    private final AtomicReference<Consumer<Page>> popupListener = new AtomicReference<>();
    private final Keyboard keyboard = mock(Keyboard.class);
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final CountDownLatch finishPump = new CountDownLatch(1);
    private final AtomicReference<Consumer<Browser>> disconnected = new AtomicReference<>();
    private final AtomicReference<Runnable> pumpEvent = new AtomicReference<>(() -> {});
    private final BlockingQueue<?> queuedInput;

    HeldBrowser() throws Exception {
      doAnswer(invocation -> {
        final Consumer<Browser> listener = invocation.getArgument(0);
        this.disconnected.set(listener);
        return null;
      })
        .when(this.browser)
        .onDisconnected(any());
      final BrowserType chromium = mock(BrowserType.class);
      doAnswer(invocation -> {
        final Consumer<Page> listener = invocation.getArgument(0);
        this.popupListener.set(listener);
        return null;
      })
        .when(this.context)
        .onPage(any());
      when(this.playwright.chromium()).thenReturn(chromium);
      when(chromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(this.browser);
      when(this.browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
      when(context.newPage()).thenReturn(this.page);
      when(context.newCDPSession(this.page)).thenReturn(this.cdp);
      when(this.page.keyboard()).thenReturn(this.keyboard);
      final CountDownLatch insidePump = new CountDownLatch(1);
      doAnswer(_ -> {
        insidePump.countDown();
        try {
          final boolean finished = this.finishPump.await(10, TimeUnit.SECONDS);
          assertTrue(finished, "the fixture must finish the pump before its timeout");
          final Runnable event = this.pumpEvent.get();
          event.run();
        } catch (final InterruptedException interrupted) {
          final Thread current = Thread.currentThread();
          current.interrupt();
        }
        return null;
      })
        .when(this.page)
        .waitForTimeout(anyDouble());
      final Duration timeout = Duration.ofSeconds(5);
      final long idlePollNanos = TimeUnit.SECONDS.toNanos(30);
      final PlaywrightPlayer created = new PlaywrightPlayer(() -> {}, timeout, timeout, () -> this.playwright, idlePollNanos);
      this.player = spy(created);
      this.player.setExceptionHandler((message, failure) -> {
          this.messages.add(message);
          this.errors.add(failure);
        });
      final URI uri = URI.create("http://127.0.0.1/mock");
      final BrowserSource source = BrowserSource.uri(uri, 80, 4, 4, 1);
      try {
        final boolean started = this.player.start(source);
        assertTrue(started);
        final boolean pumping = insidePump.await(5, TimeUnit.SECONDS);
        assertTrue(pumping, "queue operations begin only once the browser pump is held");
        // Read the real queue to model a worker being preempted after poll(). No lifecycle state is fabricated.
        final Field sessionField = PlaywrightPlayer.class.getDeclaredField("currentSession");
        sessionField.setAccessible(true);
        final Object sessionReference = sessionField.get(this.player);
        final AtomicReference<?> reference = assertInstanceOf(AtomicReference.class, sessionReference);
        final Object current = reference.get();
        final PlaywrightPlayer.Session session = assertInstanceOf(PlaywrightPlayer.Session.class, current);
        final Field actionsField = PlaywrightPlayer.Session.class.getDeclaredField("actions");
        actionsField.setAccessible(true);
        final Object queue = actionsField.get(session);
        this.queuedInput = assertInstanceOf(BlockingQueue.class, queue);
      } catch (final Exception | AssertionError failure) {
        this.finishPump.countDown();
        this.player.release();
        throw failure;
      }
    }

    Thread captureThread() throws ReflectiveOperationException {
      final Field captureField = PlaywrightPlayer.class.getDeclaredField("captureThread");
      captureField.setAccessible(true);
      final Object capture = captureField.get(this.player);
      return assertInstanceOf(Thread.class, capture);
    }

    void releasePlayer() throws InterruptedException {
      final AtomicReference<Throwable> failure = new AtomicReference<>();
      final Thread releaser = new Thread(
        () -> {
          try {
            this.player.release();
          } catch (final Throwable thrown) {
            failure.set(thrown);
          }
        },
        "held-playwright-releaser"
      );
      releaser.start();
      try {
        Await.until("release stops accepting input", () -> !this.player.isPlaying());
      } finally {
        this.finishPump.countDown();
        releaser.join(5_000L);
      }
      assertTrue(!releaser.isAlive(), "release must join the unblocked pump");
      final Throwable thrown = failure.get();
      assertTrue(thrown == null, () -> "release failed: " + thrown);
    }

    void fillInputQueue() {
      for (int index = 0; index < 128; index++) {
        this.player.sendKeyEvent("accepted");
      }
      assertEquals(128, this.queuedInput.size());
      assertTrue(this.messages.isEmpty(), this.messages::toString);
    }

    Runnable pollInput() {
      final Object action = this.queuedInput.poll();
      return assertInstanceOf(Runnable.class, action, "a successful send must have queued real input work");
    }

    @Override
    public void close() {
      this.finishPump.countDown();
      this.player.release();
    }
  }
}
