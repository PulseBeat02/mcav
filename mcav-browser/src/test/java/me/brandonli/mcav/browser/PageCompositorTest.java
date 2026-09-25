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
import org.junit.jupiter.api.Test;

class PageCompositorTest {

  private static final int WIDTH = 8;
  private static final int HEIGHT = 6;
  private static final long NOW = 1_000_000_000L;

  private final AtomicLong clock = new AtomicLong(NOW);

  private PageCompositor compositor(final int frameInterval) {
    return new PageCompositor(WIDTH, HEIGHT, frameInterval, 100L, this.clock::get);
  }

  /** A buffer whose every pixel is (value, x, y, 255) in BGRA order. */
  private static ByteBuffer buffer(final int width, final int height, final int value) {
    final ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        buffer.put((byte) value);
        buffer.put((byte) x);
        buffer.put((byte) y);
        buffer.put((byte) 255);
      }
    }
    buffer.flip();
    return buffer;
  }

  private static int pixel(final FrameRegion region, final int x, final int y, final int channel) {
    final int offset = ((y - region.getY()) * region.getWidth() + (x - region.getX())) * 4 + channel;
    return region.getPixels()[offset] & 0xFF;
  }

  private static FrameRegion takeNow(final PageCompositor compositor) {
    try {
      return compositor.takeDamage(new byte[compositor.getPageBytes()], 0L);
    } catch (final InterruptedException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static FrameRegion take(final PageCompositor compositor) throws InterruptedException {
    final byte[] target = new byte[compositor.getPageBytes()];
    final FrameRegion region = compositor.takeDamage(target, 0L);
    assertNotNull(region, "damage was due");
    return region;
  }

  @Test
  void thePageStartsWhiteAndHoldsTheWholeViewport() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    assertEquals(WIDTH * HEIGHT * 4, compositor.getPageBytes());
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, buffer(WIDTH, HEIGHT, 9), WIDTH, HEIGHT);
    // only the painted pixel changed; the damage covers it alone
    final FrameRegion region = take(compositor);
    assertEquals(1, region.getWidth());
    assertEquals(9, pixel(region, 0, 0, 0));
  }

  @Test
  void aPaintCopiesOnlyItsDirtyRectanglesAndTheirUnionIsSent() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    final Rectangle[] dirty = { new Rectangle(1, 1, 2, 1), new Rectangle(5, 3, 1, 2) };
    compositor.onPaint(false, dirty, buffer(WIDTH, HEIGHT, 7), WIDTH, HEIGHT);
    final FrameRegion region = take(compositor);
    assertEquals(WIDTH, region.getPageWidth());
    assertEquals(HEIGHT, region.getPageHeight());
    assertEquals(new Rectangle(1, 1, 5, 4), new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight()));
    // painted pixels carry the buffer, the others inside the union are still white
    assertEquals(7, pixel(region, 1, 1, 0));
    assertEquals(2, pixel(region, 2, 1, 1));
    assertEquals(4, pixel(region, 5, 4, 2));
    assertEquals(255, pixel(region, 3, 2, 0));
    // everything was taken
    assertNull(compositor.takeDamage(new byte[compositor.getPageBytes()], 0L));
  }

  @Test
  void dirtyRectanglesAreClippedToThePage() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    final Rectangle[] dirty = { new Rectangle(-5, -5, 7, 7), new Rectangle(20, 20, 3, 3) };
    compositor.onPaint(false, dirty, buffer(WIDTH, HEIGHT, 3), WIDTH, HEIGHT);
    final FrameRegion region = take(compositor);
    assertEquals(new Rectangle(0, 0, 2, 2), new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight()));
  }

  @Test
  void aPaintOfAnotherSizeIsIgnored() {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, buffer(WIDTH + 1, HEIGHT, 1), WIDTH + 1, HEIGHT);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, buffer(WIDTH, HEIGHT + 1, 1), WIDTH, HEIGHT + 1);
    assertNull(takeNow(compositor));
  }

  @Test
  void damageWaitsForTheFrameIntervalOrTheSettleDelay() throws InterruptedException {
    final PageCompositor compositor = this.compositor(3);
    final Rectangle[] all = { new Rectangle(0, 0, WIDTH, HEIGHT) };
    compositor.onPaint(false, all, buffer(WIDTH, HEIGHT, 1), WIDTH, HEIGHT);
    compositor.onPaint(false, all, buffer(WIDTH, HEIGHT, 2), WIDTH, HEIGHT);
    assertNull(takeNow(compositor), "two paints of three");
    compositor.onPaint(false, all, buffer(WIDTH, HEIGHT, 3), WIDTH, HEIGHT);
    assertEquals(3, pixel(take(compositor), 0, 0, 0));
    compositor.onPaint(false, all, buffer(WIDTH, HEIGHT, 4), WIDTH, HEIGHT);
    assertNull(takeNow(compositor), "one paint of three, not settled");
    this.clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
    assertEquals(4, pixel(take(compositor), 0, 0, 0));
  }

  @Test
  void aWaitingTakerWakesUpWhenDamageBecomesDue() throws Exception {
    final PageCompositor compositor = new PageCompositor(WIDTH, HEIGHT, 1, 100L, System::nanoTime);
    final byte[] target = new byte[compositor.getPageBytes()];
    final CompletableFuture<FrameRegion> taken = CompletableFuture.supplyAsync(() -> {
      try {
        return compositor.takeDamage(target, 10_000L);
      } catch (final InterruptedException exception) {
        throw new IllegalStateException(exception);
      }
    });
    Thread.sleep(50L);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, buffer(WIDTH, HEIGHT, 5), WIDTH, HEIGHT);
    final FrameRegion region = taken.get(10, TimeUnit.SECONDS);
    assertSame(target, region.getPixels());
    assertEquals(5, pixel(region, 0, 0, 0));
  }

  @Test
  void aTakerThatWaitsForTheSettleDelayGetsTheDamageOnceItSettled() throws Exception {
    final PageCompositor compositor = new PageCompositor(WIDTH, HEIGHT, 5, 60L, System::nanoTime);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, buffer(WIDTH, HEIGHT, 6), WIDTH, HEIGHT);
    final long start = System.nanoTime();
    final FrameRegion region = compositor.takeDamage(new byte[compositor.getPageBytes()], 10_000L);
    final long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertNotNull(region);
    assertTrue(waited >= 50L, "waited " + waited + " ms for the settle delay");
  }

  @Test
  void takingTimesOutWithoutDamage() throws InterruptedException {
    final PageCompositor compositor = new PageCompositor(WIDTH, HEIGHT, 1, 100L, System::nanoTime);
    assertNull(compositor.takeDamage(new byte[compositor.getPageBytes()], 20L));
  }

  @Test
  void closingWakesAWaitingTakerWithNothing() throws Exception {
    final PageCompositor compositor = new PageCompositor(WIDTH, HEIGHT, 1, 100L, System::nanoTime);
    final CompletableFuture<FrameRegion> taken = CompletableFuture.supplyAsync(() -> {
      try {
        return compositor.takeDamage(new byte[compositor.getPageBytes()], 60_000L);
      } catch (final InterruptedException exception) {
        throw new IllegalStateException(exception);
      }
    });
    Thread.sleep(50L);
    compositor.close();
    assertNull(taken.get(10, TimeUnit.SECONDS));
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, buffer(WIDTH, HEIGHT, 1), WIDTH, HEIGHT);
    assertNull(compositor.takeDamage(new byte[compositor.getPageBytes()], 0L), "a closed compositor hands out nothing");
  }

  @Test
  void anOpenPopupIsDrawnOverThePageAndItsAreaIsDamaged() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, WIDTH, HEIGHT) }, buffer(WIDTH, HEIGHT, 1), WIDTH, HEIGHT);
    take(compositor);
    compositor.onPopupShow(true);
    compositor.onPopupSize(new Rectangle(2, 2, 3, 2));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 3, 2) }, buffer(3, 2, 200), 3, 2);
    final FrameRegion region = take(compositor);
    assertEquals(new Rectangle(2, 2, 3, 2), new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight()));
    // the popup's own pixel (1, 1) lands on the page at (3, 3)
    assertEquals(200, pixel(region, 3, 3, 0));
    assertEquals(1, pixel(region, 3, 3, 1));
    assertEquals(1, pixel(region, 3, 3, 2));
    // a page change under the open popup still shows the popup
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, WIDTH, HEIGHT) }, buffer(WIDTH, HEIGHT, 9), WIDTH, HEIGHT);
    final FrameRegion whole = take(compositor);
    assertEquals(200, pixel(whole, 2, 2, 0));
    assertEquals(9, pixel(whole, 0, 0, 0));
  }

  @Test
  void damageBesideAnOpenPopupShowsThePageAlone() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPopupSize(new Rectangle(2, 2, 3, 2));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 3, 2) }, buffer(3, 2, 200), 3, 2);
    take(compositor);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, 1, 1) }, buffer(WIDTH, HEIGHT, 7), WIDTH, HEIGHT);
    final FrameRegion region = take(compositor);
    assertEquals(new Rectangle(0, 0, 1, 1), new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight()));
    assertEquals(7, pixel(region, 0, 0, 0));
  }

  @Test
  void hidingThePopupSendsThePageUnderIt() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPaint(false, new Rectangle[] { new Rectangle(0, 0, WIDTH, HEIGHT) }, buffer(WIDTH, HEIGHT, 1), WIDTH, HEIGHT);
    take(compositor);
    compositor.onPopupSize(new Rectangle(1, 1, 2, 2));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, buffer(2, 2, 150), 2, 2);
    take(compositor);
    compositor.onPopupShow(false);
    final FrameRegion region = take(compositor);
    assertEquals(new Rectangle(1, 1, 2, 2), new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight()));
    assertEquals(1, pixel(region, 1, 1, 0));
    // hiding a popup that never had a size changes nothing
    final PageCompositor fresh = this.compositor(1);
    fresh.onPopupShow(false);
    assertNull(takeNow(fresh));
  }

  @Test
  void aPopupPaintIsIgnoredUntilItsSizeIsKnownOrWhenItDoesNotMatch() {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, buffer(2, 2, 150), 2, 2);
    assertNull(takeNow(compositor), "no size yet");
    compositor.onPopupSize(new Rectangle(0, 0, 3, 3));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, buffer(2, 2, 150), 2, 2);
    assertNull(takeNow(compositor), "the size does not match");
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 3, 2) }, buffer(3, 2, 150), 3, 2);
    assertNull(takeNow(compositor), "the height does not match");
  }

  @Test
  void movingAnOpenPopupDamagesWhereItWas() throws InterruptedException {
    final PageCompositor compositor = this.compositor(1);
    compositor.onPopupSize(new Rectangle(0, 0, 2, 2));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, buffer(2, 2, 150), 2, 2);
    take(compositor);
    compositor.onPopupSize(new Rectangle(4, 3, 2, 2));
    final FrameRegion region = take(compositor);
    assertEquals(new Rectangle(0, 0, 2, 2), new Rectangle(region.getX(), region.getY(), region.getWidth(), region.getHeight()));
    // a popup outside the page damages nothing
    compositor.onPopupSize(new Rectangle(100, 100, 2, 2));
    compositor.onPaint(true, new Rectangle[] { new Rectangle(0, 0, 2, 2) }, buffer(2, 2, 150), 2, 2);
    compositor.onPopupShow(false);
    assertNull(takeNow(compositor));
  }
}
