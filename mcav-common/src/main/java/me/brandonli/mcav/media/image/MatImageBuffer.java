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
package me.brandonli.mcav.media.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.examinable.ExaminableObject;
import me.brandonli.mcav.utils.examinable.ExaminableProperty;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.indexer.Indexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * A class that represents an image backed by an OpenCV Mat object. It provides various image
 * processing methods such as resizing, rotating, flipping, and applying filters.
 */
public class MatImageBuffer extends ExaminableObject implements ImageBuffer {

  /**
   * A property that represents the OpenCV Mat object associated with this image buffer.
   */
  public static final ExaminableProperty<Mat> MAT_PROPERTY = ExaminableProperty.property("mat", Mat.class);

  private final Mat mat;

  MatImageBuffer(final byte[] bytes, final int width, final int height) {
    this.mat = new Mat(height, width, opencv_core.CV_8UC3);
    this.mat.data().put(bytes);
    this.assignMat(this.mat);
  }

  MatImageBuffer(final UriSource source) {
    this(FileSource.path(IOUtils.downloadImage(source)));
  }

  MatImageBuffer(final FileSource source) {
    final String path = source.getResource();
    this.mat = opencv_imgcodecs.imread(path);
    this.assignMat(this.mat);
  }

  MatImageBuffer(final int[] data, final int width, final int height) {
    final Mat originalMat = new Mat(height, width, opencv_core.CV_8UC4);
    final byte[] byteData = new byte[data.length * 4];
    for (int i = 0; i < data.length; i++) {
      final int pixel = data[i];
      final int idx = i * 4;
      byteData[idx] = (byte) (pixel & 0xFF);
      byteData[idx + 1] = (byte) ((pixel >> 8) & 0xFF);
      byteData[idx + 2] = (byte) ((pixel >> 16) & 0xFF);
      byteData[idx + 3] = (byte) 0xFF;
    }
    originalMat.data().put(byteData);
    this.mat = originalMat;
    this.assignMat(this.mat);
  }

  MatImageBuffer(final BufferedImage image) throws IOException {
    image.setAccelerationPriority(1.0f);
    final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", byteArrayOutputStream);
    byteArrayOutputStream.flush();
    this.mat = opencv_imgcodecs.imdecode(new Mat(new BytePointer(byteArrayOutputStream.toByteArray())), opencv_imgcodecs.IMREAD_UNCHANGED);
    this.assignMat(this.mat);
  }

  MatImageBuffer(final byte[] bytes) {
    this.mat = opencv_imgcodecs.imdecode(new Mat(new BytePointer(bytes)), opencv_imgcodecs.IMREAD_UNCHANGED);
    this.assignMat(this.mat);
  }

  @SuppressWarnings("all") // checker
  private void assignMat(@UnderInitialization MatImageBuffer this, final Mat mat) {
    this.set(MAT_PROPERTY, mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BufferedImage toBufferedImage() {
    try {
      final BytePointer bytePointer = new BytePointer();
      opencv_imgcodecs.imencode(".jpg", this.mat, bytePointer);
      final byte[] byteArray = new byte[(int) bytePointer.limit()];
      bytePointer.get(byteArray);
      bytePointer.deallocate();
      final BufferedImage img = ImageIO.read(new ByteArrayInputStream(byteArray));
      img.setAccelerationPriority(1.0f);
      return img;
    } catch (final IOException e) {
      throw new me.brandonli.mcav.utils.UncheckedIOException(e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setPixel(final int x, final int y, final double[] value) {
    try (final Indexer indexer = this.mat.createIndexer()) {
      final int len = Math.min(value.length, this.mat.channels());
      for (int c = 0; c < len; c++) {
        final long[] indices = new long[] { y, x, c };
        indexer.putDouble(indices, value[c]);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double[] getPixel(final int x, final int y) {
    final double[] values = new double[this.mat.channels()];
    try (final Indexer indexer = this.mat.createIndexer()) {
      for (int c = 0; c < values.length; c++) {
        values[c] = indexer.getDouble(y, x, c);
      }
    }
    return values;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getWidth() {
    return this.mat.cols();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getHeight() {
    return this.mat.rows();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int[] getPixels() {
    final int width = this.mat.cols();
    final int height = this.mat.rows();
    final int channels = this.mat.channels();
    final int[] pixels = new int[width * height];
    final ByteBuffer buffer = this.mat.createBuffer();
    if (channels == 4) {
      for (int i = 0; i < pixels.length; i++) {
        final int blue = buffer.get() & 0xFF;
        final int green = buffer.get() & 0xFF;
        final int red = buffer.get() & 0xFF;
        final int alpha = buffer.get() & 0xFF;
        pixels[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
      }
    } else if (channels == 3) {
      for (int i = 0; i < pixels.length; i++) {
        final int blue = buffer.get() & 0xFF;
        final int green = buffer.get() & 0xFF;
        final int red = buffer.get() & 0xFF;
        pixels[i] = (255 << 24) | (red << 16) | (green << 8) | blue;
      }
    } else if (channels == 1) {
      for (int i = 0; i < pixels.length; i++) {
        final int gray = buffer.get() & 0xFF;
        pixels[i] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
      }
    } else {
      throw new IllegalStateException("Unsupported image format with " + channels + " channels");
    }
    return pixels;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setAsBufferedImage(final BufferedImage image) {
    try {
      final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      ImageIO.write(image, "jpg", byteArrayOutputStream);
      final byte[] bytes = byteArrayOutputStream.toByteArray();
      byteArrayOutputStream.close();
      final BytePointer bytePointer = new BytePointer(bytes);
      final Mat byteMat = new Mat(1, bytes.length, opencv_core.CV_8UC1, bytePointer);
      final Mat newMat = opencv_imgcodecs.imdecode(byteMat, opencv_imgcodecs.IMREAD_UNCHANGED);
      this.mat.release();
      newMat.copyTo(this.mat);
      newMat.release();
      byteMat.release();
      bytePointer.deallocate();
    } catch (final IOException e) {
      throw new me.brandonli.mcav.utils.UncheckedIOException(e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPixelCount() {
    return (int) this.mat.elemSize();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() {
    this.mat.release();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    this.close();
  }
}
