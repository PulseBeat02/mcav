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

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.cef.browser.McavOffscreenBrowser;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Keeps the picture of a page in the helper process and hands out what changed.
 *
 * <p>CEF paints the page and, separately, popup widgets such as the list of an open drop-down box. The compositor
 * copies each painted rectangle out of CEF's buffer, which is only valid during the paint, into its own copy of the
 * page, and remembers the popup with its position. Changed areas add up to one damaged rectangle, which
 * {@link #takeDamage} copies out, with the popup drawn over the page where it is open. Damage is never dropped, only
 * merged, so the receiver's picture always ends up equal to the page.
 *
 * <p>Damage becomes due to be sent after every n-th paint of the page, or once it waited for the settle delay, so the
 * last change of a page that stops changing is not held back by the frame interval.
 */
final class PageCompositor implements McavOffscreenBrowser.PaintListener {

  private final int width;
  private final int height;
  private final int frameInterval;
  private final long settleNanos;
  private final LongSupplier clock;
  private final byte[] page;

  private @Nullable Rectangle damage;
  private long damageSince;
  private int paintsSinceSent;
  private boolean closed;
  private @Nullable Rectangle popupBounds;
  private @Nullable Popup shownPopup;

  /**
   * Constructs a compositor with a white page.
   *
   * @param width         the width of the page in pixels
   * @param height        the height of the page in pixels
   * @param frameInterval damage is due after this many paints of the page
   * @param settleMillis  damage is due once it waited this long
   * @param clock         a monotonic clock in nanoseconds
   */
  PageCompositor(final int width, final int height, final int frameInterval, final long settleMillis, final LongSupplier clock) {
    this.width = width;
    this.height = height;
    this.frameInterval = frameInterval;
    this.settleNanos = TimeUnit.MILLISECONDS.toNanos(settleMillis);
    this.clock = clock;
    this.page = new byte[width * height * HelperProtocol.PIXEL_BYTES];
    java.util.Arrays.fill(this.page, (byte) 0xFF);
  }

  /**
   * Gets the most bytes one region can have.
   *
   * @return the size of the whole page in bytes
   */
  int getPageBytes() {
    return this.page.length;
  }

  /**
   * Copies the painted rectangles of the page or of a popup.
   *
   * @param popup      true if the buffer holds a popup widget
   * @param dirtyRects the rectangles that changed, relative to the buffer
   * @param buffer     the pixels, BGRA, row by row
   * @param bufferWidth  the width of the buffer
   * @param bufferHeight the height of the buffer
   */
  @Override
  public synchronized void onPaint(
    final boolean popup,
    final Rectangle[] dirtyRects,
    final ByteBuffer buffer,
    final int bufferWidth,
    final int bufferHeight
  ) {
    if (popup) {
      this.paintPopup(buffer, bufferWidth, bufferHeight);
      return;
    }
    if (bufferWidth != this.width || bufferHeight != this.height) {
      // a paint of another size belongs to a resize this compositor does not do
      return;
    }
    final Rectangle pageBounds = new Rectangle(0, 0, this.width, this.height);
    for (final Rectangle dirty : dirtyRects) {
      final Rectangle clipped = dirty.intersection(pageBounds);
      if (clipped.isEmpty()) {
        continue;
      }
      copyRows(buffer, this.width, clipped, this.page, this.width, clipped.x, clipped.y);
      this.addDamage(clipped);
    }
    this.paintsSinceSent++;
    this.notifyAll();
  }

  private void paintPopup(final ByteBuffer buffer, final int bufferWidth, final int bufferHeight) {
    final Rectangle bounds = this.popupBounds;
    // CEF gives the size of a popup before it paints it; a paint of another size belongs to no known popup
    if (bounds == null || bounds.width != bufferWidth || bounds.height != bufferHeight) {
      return;
    }
    final int bytes = bufferWidth * bufferHeight * HelperProtocol.PIXEL_BYTES;
    final byte[] pixels = new byte[bytes];
    final ByteBuffer source = buffer.duplicate();
    source.position(0);
    source.get(pixels, 0, bytes);
    this.shownPopup = new Popup(new Rectangle(bounds), pixels);
    this.addPopupDamage(bounds);
    this.paintsSinceSent++;
    this.notifyAll();
  }

  /**
   * Hides the popup that is shown, sending the page under it again as soon as the next paint would be.
   */
  private void hidePopup() {
    final Popup shown = this.shownPopup;
    if (shown != null) {
      this.shownPopup = null;
      this.addPopupDamage(shown.bounds());
      this.paintsSinceSent++;
      this.notifyAll();
    }
  }

  /**
   * Shows or hides the popup widget.
   *
   * @param show true if the popup is shown
   */
  @Override
  public synchronized void onPopupShow(final boolean show) {
    // a popup is shown by its first paint
    if (!show) {
      this.hidePopup();
    }
  }

  /**
   * Remembers where the popup widget is drawn.
   *
   * @param bounds the bounds of the popup on the page
   */
  @Override
  public synchronized void onPopupSize(final Rectangle bounds) {
    // a popup that moves or changes its size is drawn again by its next paint
    this.hidePopup();
    this.popupBounds = new Rectangle(bounds);
  }

  private void addPopupDamage(final Rectangle bounds) {
    final Rectangle pageBounds = new Rectangle(0, 0, this.width, this.height);
    final Rectangle clipped = bounds.intersection(pageBounds);
    if (!clipped.isEmpty()) {
      this.addDamage(clipped);
    }
  }

  private void addDamage(final Rectangle area) {
    final Rectangle current = this.damage;
    if (current == null) {
      this.damage = new Rectangle(area);
      this.damageSince = this.clock.getAsLong();
    } else {
      current.add(area);
    }
  }

  /**
   * Waits until damage is due, then copies the damaged region, with the popup drawn over it, into the buffer.
   *
   * @param target        the buffer, at least {@link #getPageBytes()} bytes
   * @param timeoutMillis how long to wait at most
   * @return the region, or null if nothing was due in time or the compositor was closed
   * @throws InterruptedException if the waiting thread is interrupted
   */
  synchronized @Nullable FrameRegion takeDamage(final byte[] target, final long timeoutMillis) throws InterruptedException {
    final long deadline = this.clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (!this.closed) {
      final Rectangle current = this.damage;
      final long now = this.clock.getAsLong();
      if (current != null && this.isDue(now)) {
        return this.copyDamage(current, target);
      }
      final long remaining = deadline - now;
      if (remaining <= 0) {
        return null;
      }
      final long settleLeft = current == null ? remaining : this.damageSince + this.settleNanos - now;
      final long waitNanos = Math.max(1L, Math.min(remaining, settleLeft));
      TimeUnit.NANOSECONDS.timedWait(this, waitNanos);
    }
    return null;
  }

  private boolean isDue(final long now) {
    final boolean enoughPaints = this.paintsSinceSent >= this.frameInterval;
    final boolean settled = now - this.damageSince >= this.settleNanos;
    return enoughPaints || settled;
  }

  private FrameRegion copyDamage(final Rectangle area, final byte[] target) {
    final ByteBuffer pageBuffer = ByteBuffer.wrap(this.page);
    copyRows(pageBuffer, this.width, area, target, area.width, 0, 0);
    final Popup shown = this.shownPopup;
    if (shown != null) {
      final Rectangle bounds = shown.bounds();
      final Rectangle overlap = area.intersection(bounds);
      if (!overlap.isEmpty()) {
        final Rectangle inPopup = new Rectangle(overlap.x - bounds.x, overlap.y - bounds.y, overlap.width, overlap.height);
        final ByteBuffer popupBuffer = ByteBuffer.wrap(shown.pixels());
        copyRows(popupBuffer, bounds.width, inPopup, target, area.width, overlap.x - area.x, overlap.y - area.y);
      }
    }
    this.damage = null;
    this.paintsSinceSent = 0;
    return new FrameRegion(this.width, this.height, area.x, area.y, area.width, area.height, target);
  }

  /**
   * Copies a rectangle of a BGRA buffer into a BGRA array.
   *
   * @param source       the source pixels
   * @param sourceWidth  the width of the source in pixels
   * @param area         the rectangle, relative to the source
   * @param target       the target pixels
   * @param targetWidth  the width of the target in pixels
   * @param targetX      where the rectangle starts in the target
   * @param targetY      where the rectangle starts in the target
   */
  private static void copyRows(
    final ByteBuffer source,
    final int sourceWidth,
    final Rectangle area,
    final byte[] target,
    final int targetWidth,
    final int targetX,
    final int targetY
  ) {
    final ByteBuffer view = source.duplicate();
    final int rowBytes = area.width * HelperProtocol.PIXEL_BYTES;
    for (int row = 0; row < area.height; row++) {
      final int sourceOffset = ((area.y + row) * sourceWidth + area.x) * HelperProtocol.PIXEL_BYTES;
      final int targetOffset = ((targetY + row) * targetWidth + targetX) * HelperProtocol.PIXEL_BYTES;
      view.position(sourceOffset);
      view.get(target, targetOffset, rowBytes);
    }
  }

  /**
   * Wakes a thread waiting in {@link #takeDamage} and makes it return null from now on.
   */
  synchronized void close() {
    this.closed = true;
    this.notifyAll();
  }

  /**
   * A popup widget that is shown: where it is drawn on the page, and its pixels.
   */
  private static final class Popup {

    private final Rectangle bounds;
    private final byte[] pixels;

    Popup(final Rectangle bounds, final byte[] pixels) {
      this.bounds = bounds;
      this.pixels = pixels;
    }

    Rectangle bounds() {
      return this.bounds;
    }

    byte[] pixels() {
      return this.pixels;
    }
  }
}
