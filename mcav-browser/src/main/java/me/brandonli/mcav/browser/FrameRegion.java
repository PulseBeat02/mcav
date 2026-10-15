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

// A changed rectangle of a page and its pixels, four bytes per pixel in BGRA order, row by row from the start of the
// pixel array. The array may be longer than the region needs, so a buffer can be reused for regions of any size.
final class FrameRegion {

  private final int pageWidth;
  private final int pageHeight;
  private final int left;
  private final int top;
  private final int width;
  private final int height;
  private final byte[] pixels;

  // Constructs a region. The values are not checked; HelperProtocol checks what it reads.
  FrameRegion(
    final int pageWidth,
    final int pageHeight,
    final int left,
    final int top,
    final int width,
    final int height,
    final byte[] pixels
  ) {
    this.pageWidth = pageWidth;
    this.pageHeight = pageHeight;
    this.left = left;
    this.top = top;
    this.width = width;
    this.height = height;
    this.pixels = pixels;
  }

  int getPixelBytes() {
    return this.width * this.height * HelperProtocol.PIXEL_BYTES;
  }

  int getPageWidth() {
    return this.pageWidth;
  }

  int getPageHeight() {
    return this.pageHeight;
  }

  int getX() {
    return this.left;
  }

  int getY() {
    return this.top;
  }

  int getWidth() {
    return this.width;
  }

  int getHeight() {
    return this.height;
  }

  byte[] getPixels() {
    return this.pixels;
  }
}
