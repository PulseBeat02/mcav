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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.cef.browser.McavOffscreenBrowser;
import org.checkerframework.checker.nullness.qual.Nullable;

// The paint buffer of CEF is valid only during the paint, so the page is copied; damage is merged, never dropped, so the
// receiver ends up with the page. A settle delay sends the last change of a page that stops changing.
final class PageCompositor implements McavOffscreenBrowser.PaintListener {

  private static final byte FULL_CHANNEL = (byte) 0xFF;

  private final int width;
  private final int height;
  private final int frameInterval;
  private final long settleNanoseconds;
  private final LongSupplier clock;
  private final byte[] page;

  private @Nullable Rectangle damage;
  private long damageSince;
  private int paintsSinceSent;
  private boolean closed;
  private @Nullable Rectangle popupBounds;
  private @Nullable Popup shownPopup;

  PageCompositor(final int width, final int height, final int frameInterval, final long settleMilliseconds, final LongSupplier clock) {
    this.width = width;
    this.height = height;
    this.frameInterval = frameInterval;
    this.settleNanoseconds = TimeUnit.MILLISECONDS.toNanos(settleMilliseconds);
    this.clock = clock;
    this.page = new byte[width * height * HelperProtocol.PIXEL_BYTES];
    Arrays.fill(this.page, FULL_CHANNEL);
  }

  @Override
  public synchronized void onPaint(
    final boolean isPopup,
    final Rectangle[] dirtyRects,
    final ByteBuffer buffer,
    final int bufferWidth,
    final int bufferHeight
  ) {
    if (isPopup) {
      this.paintPopup(buffer, bufferWidth, bufferHeight);
      return;
    }

    if (bufferWidth != this.width || bufferHeight != this.height) {
      return;
    }

    final Rectangle pageBounds = new Rectangle(0, 0, this.width, this.height);
    for (final Rectangle dirty : dirtyRects) {
      final Rectangle clipped = dirty.intersection(pageBounds);
      if (clipped.isEmpty()) {
        continue;
      }
      this.copyRows(buffer, this.width, clipped, this.page, this.width, clipped.x, clipped.y);
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

  private void hidePopup() {
    final Popup shown = this.shownPopup;
    if (shown != null) {
      this.shownPopup = null;
      this.addPopupDamage(shown.bounds());
      this.paintsSinceSent++;
      this.notifyAll();
    }
  }

  @Override
  public synchronized void onPopupShow(final boolean isShown) {
    if (!isShown) {
      this.hidePopup();
    }
  }

  @Override
  public synchronized void onPopupSize(final Rectangle bounds) {
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

  synchronized @Nullable FrameRegion takeDamage(final byte[] target, final long timeoutMilliseconds) throws InterruptedException {
    final long deadline = this.clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(timeoutMilliseconds);
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
      final long settleLeft = current == null ? remaining : this.damageSince + this.settleNanoseconds - now;
      final long waitNanoseconds = Math.max(1L, Math.min(remaining, settleLeft));
      TimeUnit.NANOSECONDS.timedWait(this, waitNanoseconds);
    }
    return null;
  }

  private boolean isDue(final long now) {
    final boolean enoughPaints = this.paintsSinceSent >= this.frameInterval;
    final boolean settled = now - this.damageSince >= this.settleNanoseconds;
    return enoughPaints || settled;
  }

  private FrameRegion copyDamage(final Rectangle area, final byte[] target) {
    final ByteBuffer pageBuffer = ByteBuffer.wrap(this.page);
    this.copyRows(pageBuffer, this.width, area, target, area.width, 0, 0);
    final Popup shown = this.shownPopup;
    if (shown != null) {
      final Rectangle bounds = shown.bounds();
      final Rectangle overlap = area.intersection(bounds);
      if (!overlap.isEmpty()) {
        final Rectangle inPopup = new Rectangle(overlap.x - bounds.x, overlap.y - bounds.y, overlap.width, overlap.height);
        final ByteBuffer popupBuffer = ByteBuffer.wrap(shown.pixels());
        this.copyRows(popupBuffer, bounds.width, inPopup, target, area.width, overlap.x - area.x, overlap.y - area.y);
      }
    }
    this.damage = null;
    this.paintsSinceSent = 0;

    return new FrameRegion(this.width, this.height, area.x, area.y, area.width, area.height, target);
  }

  private void copyRows(
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

  synchronized void close() {
    this.closed = true;
    this.notifyAll();
  }

  int getPageBytes() {
    return this.page.length;
  }

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
