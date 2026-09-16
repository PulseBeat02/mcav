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
package me.brandonli.mcav.media.source.frame;

/**
 * The default {@link FrameSource}.
 */
public final class FrameSourceImpl implements FrameSource {

  private final SampleSupplier supplier;
  private final int width;
  private final int height;
  private final float frameRate;

  FrameSourceImpl(final SampleSupplier supplier, final int width, final int height, final float frameRate) {
    this.supplier = supplier;
    this.width = width;
    this.height = height;
    this.frameRate = frameRate;
  }

  @Override
  public SampleSupplier supplyFrameSamples() {
    return this.supplier;
  }

  @Override
  public int getFrameWidth() {
    return this.width;
  }

  @Override
  public int getFrameHeight() {
    return this.height;
  }

  @Override
  public float getFrameRate() {
    return this.frameRate;
  }
}
