/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.utils.IOUtils;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * Implementation of {@link DynamicImage} that uses FFmpeg to read GIF frames.
 * <p>
 * This class is not thread-safe.
 */
public class DynamicImageImpl implements DynamicImage {

  // safety net for cleaning up Mat resources
  private static final Cleaner MAT_CLEANER = Cleaner.create();

  private static class DynamicImageResources implements Runnable {

    private final List<StaticImage> frames;

    DynamicImageResources(final List<StaticImage> frames) {
      this.frames = frames;
    }

    @Override
    public void run() {
      if (this.frames != null) {
        for (final StaticImage frame : this.frames) {
          try {
            frame.close();
          } catch (final Exception ignored) {}
        }
      }
    }
  }

  private final Cleaner.Cleanable cleanable;

  @SuppressWarnings("all")
  private final Object cleanerKey = new Object(); // checker

  private final List<StaticImage> frames;
  private float frameRate;
  private int frameCount;

  DynamicImageImpl(final FileSource source) throws IOException {
    this.frames = new ArrayList<>();
    this.getGifFrames(this.frames, source);
    this.cleanable = MAT_CLEANER.register(this.cleanerKey, new DynamicImageResources(this.frames));
  }

  DynamicImageImpl(final UriSource source) throws IOException {
    this(FileSource.path(IOUtils.downloadImage(source)));
  }

  private void getGifFrames(@UnderInitialization DynamicImageImpl this, final List<StaticImage> frames, final FileSource source)
    throws IOException {
    final String filePath = source.getResource();
    final FrameGrabber grabber = new FFmpegFrameGrabber(filePath);
    grabber.start();
    final Java2DFrameConverter converter = new Java2DFrameConverter();
    this.frameRate = (float) grabber.getFrameRate();
    Frame current;
    while ((current = grabber.grabFrame()) != null) {
      final BufferedImage image = converter.convert(current);
      frames.add(StaticImage.image(image));
    }
    this.frameCount = frames.size();
    converter.close();
    grabber.close();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<StaticImage> getFrames() {
    return this.frames;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public float getFrameRate() {
    return this.frameRate;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public StaticImage getFrame(final int index) {
    return this.frames.get(index);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getFrameCount() {
    return this.frameCount;
  }

  /**
   * Closes all resources associated with this DynamicImage instance.
   * <p>
   * Releases any resources held by the frames of the image, ensuring
   * proper cleanup. This method should be called when the instance is
   * no longer needed to free system resources.
   */
  @Override
  public void close() {
    this.cleanable.clean();
  }
}
