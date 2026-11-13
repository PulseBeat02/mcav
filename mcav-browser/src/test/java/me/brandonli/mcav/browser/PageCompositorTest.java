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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

class PageCompositorTest {

  private static final int CHANNEL_MASK = 0xFF;
  private static final int FULL_CHANNEL = 255;
  private static final int POPUP_BLUE = 200;

  private static final long DAMAGE_TIMEOUT_MILLISECONDS = 10_000L;
  private static final long CLOSE_WAIT_TIMEOUT_MILLISECONDS = 60_000L;

  private static final int WIDTH = 8;
  private static final int HEIGHT = 6;

  private static final long NOW = 1_000_000_000L;

  private static final long SETTLE_MILLISECONDS = 100L;
  private static final long TAKER_START_MILLISECONDS = 50L;

  private static final long SPINNING_CLOCK_READS = 20L;

  private static final int POPUP_SHADE = 150;

  private final AtomicLong clock = new AtomicLong(NOW);

  private PageCompositor compositor(final int frameInterval) {
    return new PageCompositor(WIDTH, HEIGHT, frameInterval, SETTLE_MILLISECONDS, this.clock::get);
  }

  // A buffer whose every pixel is (value, column, row, 255) in BGRA order.
  private ByteBuffer buffer(final int width, final int height, final int value) {
    final ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        buffer.put((byte) value);
        buffer.put((byte) column);
        buffer.put((byte) row);
        buffer.put((byte) FULL_CHANNEL);
      }
    }

    buffer.flip();

    return buffer;
  }

  private int pixel(final FrameRegion region, final int column, final int row, final int channel) {
    final int offset = ((row - region.getY()) * region.getWidth() + (column - region.getX())) * 4 + channel;
    return region.getPixels()[offset] & CHANNEL_MASK;
  }

  private FrameRegion takeNow(final PageCompositor compositor) {
    try {
      return compositor.takeDamage(new byte[compositor.getPageBytes()], 0L);
    } catch (final InterruptedException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private FrameRegion take(final PageCompositor compositor) throws InterruptedException {
    final byte[] target = new byte[compositor.getPageBytes()];
    final FrameRegion region = compositor.takeDamage(target, 0L);
    assertNotNull(region, "damage was due");

    return region;
  }

  @Test
  void shouldStartWhiteAndHoldTheWholeViewport() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    final int pageBytes = compositor.getPageBytes();
    final int expectedPageBytes = WIDTH * HEIGHT * 4;
    assertEquals(expectedPageBytes, pageBytes);

    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, this.buffer(WIDTH, HEIGHT, 9), WIDTH, HEIGHT);
    final FrameRegion region = this.take(compositor);
    final int regionWidth = region.getWidth();
    assertEquals(1, regionWidth);

    final int paintedValue = this.pixel(region, 0, 0, 0);
    assertEquals(9, paintedValue);
  }

  @Test
  void shouldCopyOnlyTheDirtyRectanglesOfAPaintAndSendTheirUnion() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    final Rectangle[] dirty = { new Rectangle(1, 1, 2, 1), new Rectangle(5, 3, 1, 2) };
    compositor.onPaint(false, dirty, this.buffer(WIDTH, HEIGHT, 7), WIDTH, HEIGHT);
    final FrameRegion region = this.take(compositor);
    final int pageWidth = region.getPageWidth();
    assertEquals(WIDTH, pageWidth);

    final int pageHeight = region.getPageHeight();
    assertEquals(HEIGHT, pageHeight);

    final Rectangle bounds = new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight());
    final Rectangle expectedBounds = new Rectangle(1, 1, 5, 4);
    assertEquals(expectedBounds, bounds);

    final int paintedValue = this.pixel(region, 1, 1, 0);
    assertEquals(7, paintedValue);

    final int column = this.pixel(region, 2, 1, 1);
    assertEquals(2, column);

    final int row = this.pixel(region, 5, 4, 2);
    assertEquals(4, row);

    final int untouchedValue = this.pixel(region, 3, 2, 0);
    assertEquals(FULL_CHANNEL, untouchedValue);

    final @Nullable FrameRegion nextDamage = compositor.takeDamage(new byte[compositor.getPageBytes()], 0L);
    assertNull(nextDamage);
  }

  @Test
  void shouldClipDirtyRectanglesToThePage() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    final Rectangle[] dirty = { new Rectangle(-5, -5, 7, 7), new Rectangle(20, 20, 3, 3) };
    compositor.onPaint(false, dirty, this.buffer(WIDTH, HEIGHT, 3), WIDTH, HEIGHT);
    final FrameRegion region = this.take(compositor);
    final Rectangle bounds = new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight());
    final Rectangle expectedBounds = new Rectangle(0, 0, 2, 2);
    assertEquals(expectedBounds, bounds);
  }

  @Test
  void shouldIgnoreAPaintOfAnotherSize() {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, this.buffer(WIDTH + 1, HEIGHT, 1), WIDTH + 1, HEIGHT);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, this.buffer(WIDTH, HEIGHT + 1, 1), WIDTH, HEIGHT + 1);
    final FrameRegion damage = this.takeNow(compositor);
    assertNull(damage);
  }

  @Test
  void shouldHoldDamageForTheFrameIntervalOrTheSettleDelay() throws InterruptedException {
    final PageCompositor compositor = this.compositor(3);
    final Rectangle[] all = { new Rectangle(0, 0, WIDTH, HEIGHT) };
    compositor.onPaint(false, all, this.buffer(WIDTH, HEIGHT, 1), WIDTH, HEIGHT);
    compositor.onPaint(false, all, this.buffer(WIDTH, HEIGHT, 2), WIDTH, HEIGHT);
    final FrameRegion damageAfterTwoPaints = this.takeNow(compositor);
    assertNull(damageAfterTwoPaints, "two paints of three");

    compositor.onPaint(false, all, this.buffer(WIDTH, HEIGHT, 3), WIDTH, HEIGHT);
    final int thirdPaintValue = this.pixel(this.take(compositor), 0, 0, 0);
    assertEquals(3, thirdPaintValue);

    compositor.onPaint(false, all, this.buffer(WIDTH, HEIGHT, 4), WIDTH, HEIGHT);
    final FrameRegion damageAfterOnePaint = this.takeNow(compositor);
    assertNull(damageAfterOnePaint, "one paint of three, not settled");

    this.clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
    final int fourthPaintValue = this.pixel(this.take(compositor), 0, 0, 0);
    assertEquals(4, fourthPaintValue);
  }

  @Test
  void shouldWakeAWaitingTakerWhenDamageBecomesDue() throws Exception {
    final PageCompositor compositor = new PageCompositor(WIDTH, HEIGHT, 1, SETTLE_MILLISECONDS, System::nanoTime);
    final byte[] target = new byte[compositor.getPageBytes()];
    final CompletableFuture<FrameRegion> taken = CompletableFuture.supplyAsync(() ->
      this.awaitDamage(compositor, target, DAMAGE_TIMEOUT_MILLISECONDS)
    );
    Thread.sleep(TAKER_START_MILLISECONDS);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, this.buffer(WIDTH, HEIGHT, 5), WIDTH, HEIGHT);
    final FrameRegion region = taken.get(10, TimeUnit.SECONDS);
    final byte[] pixels = region.getPixels();
    assertSame(target, pixels);

    final int paintedValue = this.pixel(region, 0, 0, 0);
    assertEquals(5, paintedValue);
  }

  private @Nullable FrameRegion awaitDamage(final PageCompositor compositor, final byte[] target, final long timeoutMilliseconds) {
    try {
      return compositor.takeDamage(target, timeoutMilliseconds);
    } catch (final InterruptedException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Test
  void shouldGiveATakerThatWaitsForTheSettleDelayTheDamageOnceItSettled() throws Exception {
    final PageCompositor compositor = new PageCompositor(WIDTH, HEIGHT, 5, 60L, System::nanoTime);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, this.buffer(WIDTH, HEIGHT, 6), WIDTH, HEIGHT);
    final long start = System.nanoTime();
    final FrameRegion region = compositor.takeDamage(new byte[compositor.getPageBytes()], DAMAGE_TIMEOUT_MILLISECONDS);
    final long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertNotNull(region);

    final boolean waitedForSettleDelay = waited >= 50L;
    assertTrue(waitedForSettleDelay, "waited " + waited + " ms for the settle delay");
  }

  @Test
  void shouldSleepUntilTheDamageSettlesInsteadOfSpinning() throws InterruptedException {
    final AtomicLong reads = new AtomicLong();
    final PageCompositor compositor = new PageCompositor(WIDTH, HEIGHT, 5, 300L, () -> this.readNanoseconds(reads));
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, this.buffer(WIDTH, HEIGHT, 6), WIDTH, HEIGHT);
    final FrameRegion region = compositor.takeDamage(new byte[compositor.getPageBytes()], DAMAGE_TIMEOUT_MILLISECONDS);
    assertNotNull(region);

    // a spinning taker waits a millisecond per pass, about 300 passes
    final long clockReads = reads.get();
    final boolean slept = clockReads < SPINNING_CLOCK_READS;
    assertTrue(slept, "the taker read the clock " + clockReads + " times while it waited");
  }

  private long readNanoseconds(final AtomicLong reads) {
    reads.incrementAndGet();

    return System.nanoTime();
  }

  @Test
  void shouldTimeOutTakingWithoutDamageWithoutSpinning() throws InterruptedException {
    final AtomicLong reads = new AtomicLong();
    final PageCompositor compositor = new PageCompositor(WIDTH, HEIGHT, 1, SETTLE_MILLISECONDS, () -> this.readNanoseconds(reads));
    final @Nullable FrameRegion damage = compositor.takeDamage(new byte[compositor.getPageBytes()], 300L);
    assertNull(damage);

    final long clockReads = reads.get();
    final boolean slept = clockReads < SPINNING_CLOCK_READS;
    assertTrue(slept, "the taker read the clock " + clockReads + " times while it waited");
  }

  @Test
  void shouldWakeAWaitingTakerWithNothingOnClose() throws Exception {
    final PageCompositor compositor = new PageCompositor(WIDTH, HEIGHT, 1, SETTLE_MILLISECONDS, System::nanoTime);
    final byte[] target = new byte[compositor.getPageBytes()];
    final CompletableFuture<FrameRegion> taken = CompletableFuture.supplyAsync(() ->
      this.awaitDamage(compositor, target, CLOSE_WAIT_TIMEOUT_MILLISECONDS)
    );
    Thread.sleep(TAKER_START_MILLISECONDS);
    compositor.close();
    final FrameRegion wokenDamage = taken.get(10, TimeUnit.SECONDS);
    assertNull(wokenDamage);

    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, this.buffer(WIDTH, HEIGHT, 1), WIDTH, HEIGHT);
    final @Nullable FrameRegion closedDamage = compositor.takeDamage(new byte[compositor.getPageBytes()], 0L);
    assertNull(closedDamage, "a closed compositor hands out nothing");
  }

  @Test
  void shouldDrawAnOpenPopupOverThePageAndDamageItsArea() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, WIDTH, HEIGHT) }, this.buffer(WIDTH, HEIGHT, 1), WIDTH, HEIGHT);
    this.take(compositor);
    compositor.onPopupShow(true);
    compositor.onPopupSize(new Rectangle(2, 2, 3, 2));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 3, 2) }, this.buffer(3, 2, POPUP_BLUE), 3, 2);
    final FrameRegion region = this.take(compositor);
    final Rectangle bounds = new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight());
    final Rectangle expectedBounds = new Rectangle(2, 2, 3, 2);
    assertEquals(expectedBounds, bounds);

    final int popupValue = this.pixel(region, 3, 3, 0);
    assertEquals(POPUP_BLUE, popupValue);

    final int popupColumn = this.pixel(region, 3, 3, 1);
    assertEquals(1, popupColumn);

    final int popupRow = this.pixel(region, 3, 3, 2);
    assertEquals(1, popupRow);

    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, WIDTH, HEIGHT) }, this.buffer(WIDTH, HEIGHT, 9), WIDTH, HEIGHT);
    final FrameRegion whole = this.take(compositor);
    final int popupOverPage = this.pixel(whole, 2, 2, 0);
    assertEquals(POPUP_BLUE, popupOverPage);

    final int pageValue = this.pixel(whole, 0, 0, 0);
    assertEquals(9, pageValue);
  }

  @Test
  void shouldDrawOnlyThePartOfAPopupThatLiesOnThePage() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, WIDTH, HEIGHT) }, this.buffer(WIDTH, HEIGHT, 1), WIDTH, HEIGHT);
    this.take(compositor);
    compositor.onPopupSize(new Rectangle(-1, -1, 3, 3));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 3, 3) }, this.buffer(3, 3, POPUP_BLUE), 3, 3);
    final FrameRegion region = this.take(compositor);
    final Rectangle bounds = new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight());
    final Rectangle expectedBounds = new Rectangle(0, 0, 2, 2);
    assertEquals(expectedBounds, bounds);

    final int popupValue = this.pixel(region, 0, 0, 0);
    assertEquals(POPUP_BLUE, popupValue);

    final int firstPopupColumn = this.pixel(region, 0, 0, 1);
    assertEquals(1, firstPopupColumn);

    final int firstPopupRow = this.pixel(region, 0, 0, 2);
    assertEquals(1, firstPopupRow);

    final int lastPopupColumn = this.pixel(region, 1, 1, 1);
    assertEquals(2, lastPopupColumn);

    final int lastPopupRow = this.pixel(region, 1, 1, 2);
    assertEquals(2, lastPopupRow);
  }

  @Test
  void shouldShowThePageAloneForDamageBesideAnOpenPopup() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPopupSize(new Rectangle(2, 2, 3, 2));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 3, 2) }, this.buffer(3, 2, POPUP_BLUE), 3, 2);
    this.take(compositor);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, this.buffer(WIDTH, HEIGHT, 7), WIDTH, HEIGHT);
    final FrameRegion region = this.take(compositor);
    final Rectangle bounds = new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight());
    final Rectangle expectedBounds = new Rectangle(0, 0, 1, 1);
    assertEquals(expectedBounds, bounds);

    final int pageValue = this.pixel(region, 0, 0, 0);
    assertEquals(7, pageValue);
  }

  @Test
  void shouldSendThePageUnderThePopupWhenItHides() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, WIDTH, HEIGHT) }, this.buffer(WIDTH, HEIGHT, 1), WIDTH, HEIGHT);
    this.take(compositor);
    compositor.onPopupSize(new Rectangle(1, 1, 2, 2));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, this.buffer(2, 2, POPUP_SHADE), 2, 2);
    this.take(compositor);
    compositor.onPopupShow(false);
    final FrameRegion region = this.take(compositor);
    final Rectangle bounds = new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight());
    final Rectangle expectedBounds = new Rectangle(1, 1, 2, 2);
    assertEquals(expectedBounds, bounds);

    final int pageValue = this.pixel(region, 1, 1, 0);
    assertEquals(1, pageValue);

    final PageCompositor fresh = this.compositor(1);
    fresh.onPopupShow(false);
    final FrameRegion freshDamage = this.takeNow(fresh);
    assertNull(freshDamage);
  }

  @Test
  void shouldIgnoreAPopupPaintUntilItsSizeIsKnownOrWhenItDoesNotMatch() {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, this.buffer(2, 2, POPUP_SHADE), 2, 2);
    final FrameRegion damageWithoutSize = this.takeNow(compositor);
    assertNull(damageWithoutSize, "no size yet");

    compositor.onPopupSize(new Rectangle(0, 0, 3, 3));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, this.buffer(2, 2, POPUP_SHADE), 2, 2);
    final FrameRegion damageOfOtherSize = this.takeNow(compositor);
    assertNull(damageOfOtherSize, "the size does not match");

    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 3, 2) }, this.buffer(3, 2, POPUP_SHADE), 3, 2);
    final FrameRegion damageOfOtherHeight = this.takeNow(compositor);
    assertNull(damageOfOtherHeight, "the height does not match");
  }

  @Test
  void shouldDamageWhereAnOpenPopupWasWhenItMoves() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPopupSize(new Rectangle(0, 0, 2, 2));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, this.buffer(2, 2, POPUP_SHADE), 2, 2);
    this.take(compositor);
    compositor.onPopupSize(new Rectangle(4, 3, 2, 2));
    final FrameRegion region = this.take(compositor);
    final Rectangle bounds = new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight());
    final Rectangle expectedBounds = new Rectangle(0, 0, 2, 2);
    assertEquals(expectedBounds, bounds);

    compositor.onPopupSize(new Rectangle(100, 100, 2, 2));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, this.buffer(2, 2, POPUP_SHADE), 2, 2);
    compositor.onPopupShow(false);
    final FrameRegion damage = this.takeNow(compositor);
    assertNull(damage);
  }

  @Test
  void shouldWakeAWaitingTakerForAPopupAndForItsEnd() throws Exception {
    final PageCompositor compositor = new PageCompositor(WIDTH, HEIGHT, 1, SETTLE_MILLISECONDS, System::nanoTime);
    final byte[] target = new byte[compositor.getPageBytes()];
    compositor.onPopupSize(new Rectangle(1, 1, 2, 2));
    final CompletableFuture<FrameRegion> shown = CompletableFuture.supplyAsync(() -> this.waitForDamage(compositor, target));
    Thread.sleep(100L);
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, this.buffer(2, 2, 7), 2, 2);
    final int popupValue = this.pixel(shown.get(10, TimeUnit.SECONDS), 1, 1, 0);
    assertEquals(7, popupValue);

    final CompletableFuture<FrameRegion> hidden = CompletableFuture.supplyAsync(() -> this.waitForDamage(compositor, target));
    Thread.sleep(100L);
    compositor.onPopupShow(false);
    final FrameRegion page = hidden.get(10, TimeUnit.SECONDS);
    final Rectangle bounds = new Rectangle(page.getX(), page.getY(), page.getWidth(), page.getHeight());
    final Rectangle expectedBounds = new Rectangle(1, 1, 2, 2);
    assertEquals(expectedBounds, bounds);
  }

  private FrameRegion waitForDamage(final PageCompositor compositor, final byte[] target) {
    try {
      return compositor.takeDamage(target, CLOSE_WAIT_TIMEOUT_MILLISECONDS);
    } catch (final InterruptedException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
