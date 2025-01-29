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

import static org.opencv.imgcodecs.Imgcodecs.imread;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.examinable.ExaminableObject;
import me.brandonli.mcav.utils.examinable.ExaminableProperty;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * A class that represents an image backed by an OpenCV Mat object. It provides various image
 * processing methods such as resizing, rotating, flipping, and applying filters.
 */
public class MatImageBuffer extends ExaminableObject implements ImageBuffer {

  public static final ExaminableProperty<Mat> MAT_PROPERTY = ExaminableProperty.property("mat", Mat.class);

  private final Mat mat;

  MatImageBuffer(final byte[] bytes, final int width, final int height) {
    this.mat = new Mat(height, width, CvType.CV_8UC3);
    this.mat.put(0, 0, bytes);
    this.assignMat(this.mat);
  }

  MatImageBuffer(final UriSource source) {
    this(FileSource.path(IOUtils.downloadImage(source)));
  }

  MatImageBuffer(final FileSource source) {
    final String path = source.getResource();
    this.mat = imread(path);
    this.assignMat(this.mat);
  }

  MatImageBuffer(final int[] data, final int width, final int height) {
    final Mat originalMat = new Mat(height, width, CvType.CV_8UC4);
    final byte[] byteData = new byte[data.length * 4];
    for (int i = 0; i < data.length; i++) {
      final int pixel = data[i];
      final int idx = i * 4;
      byteData[idx] = (byte) (pixel & 0xFF);
      byteData[idx + 1] = (byte) ((pixel >> 8) & 0xFF);
      byteData[idx + 2] = (byte) ((pixel >> 16) & 0xFF);
      byteData[idx + 3] = (byte) 0xFF;
    }
    originalMat.put(0, 0, byteData);
    this.mat = originalMat;
    this.assignMat(this.mat);
  }

  MatImageBuffer(final BufferedImage image) throws IOException {
    final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", byteArrayOutputStream);
    byteArrayOutputStream.flush();
    this.mat = Imgcodecs.imdecode(new MatOfByte(byteArrayOutputStream.toByteArray()), Imgcodecs.IMREAD_UNCHANGED);
    this.assignMat(this.mat);
  }

  MatImageBuffer(final byte[] bytes) {
    this.mat = Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_UNCHANGED);
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
      final MatOfByte mob = new MatOfByte();
      Imgcodecs.imencode(".jpg", this.mat, mob);
      final byte[] ba = mob.toArray();
      return ImageIO.read(new ByteArrayInputStream(ba));
    } catch (final IOException e) {
      throw new me.brandonli.mcav.utils.UncheckedIOException(e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setPixel(final int x, final int y, final double[] value) {
    this.mat.put(y, x, value);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double[] getPixel(final int x, final int y) {
    return this.mat.get(y, x);
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
    final byte[] byteData = new byte[(int) (this.mat.total() * this.mat.elemSize())];
    this.mat.get(0, 0, byteData);
    if (channels == 4) {
      for (int i = 0; i < pixels.length; i++) {
        final int idx = i * 4;
        final int blue = byteData[idx] & 0xFF;
        final int green = byteData[idx + 1] & 0xFF;
        final int red = byteData[idx + 2] & 0xFF;
        final int alpha = byteData[idx + 3] & 0xFF;
        pixels[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
      }
    } else if (channels == 3) {
      for (int i = 0; i < pixels.length; i++) {
        final int idx = i * 3;
        final int blue = byteData[idx] & 0xFF;
        final int green = byteData[idx + 1] & 0xFF;
        final int red = byteData[idx + 2] & 0xFF;
        pixels[i] = (255 << 24) | (red << 16) | (green << 8) | blue;
      }
    } else if (channels == 1) {
      for (int i = 0; i < pixels.length; i++) {
        final int gray = byteData[i] & 0xFF;
        pixels[i] = (255 << 24) | (gray << 16) | (gray << 8) | gray;
      }
    } else {
      throw new IllegalStateException("Unsupported image format with " + channels + " channels");
    }
    return pixels;
  }

  @Override
  public void setAsBufferedImage(final BufferedImage image) {
    try {
      this.mat.release();
      final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      ImageIO.write(image, "jpg", byteArrayOutputStream);
      byteArrayOutputStream.flush();
      final MatOfByte matOfByte = new MatOfByte(byteArrayOutputStream.toByteArray());
      final Mat newMat = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_UNCHANGED);
      newMat.copyTo(this.mat);
      newMat.release();
      byteArrayOutputStream.close();
    } catch (final IOException e) {
      throw new me.brandonli.mcav.utils.UncheckedIOException(e.getMessage(), e);
    }
  }

  @Override
  public int getPixelCount() {
    return (int) this.mat.elemSize();
  }

  /**
   * Releases the resources associated with the 'mat' object and ensures proper cleanup.
   * If the 'mat' object is not null, it calls the release method on it and sets it to null.
   * This method is invoked to free up memory and prevent resource leaks.
   */
  @Override
  public void close() {
    this.mat.release();
  }

  @Override
  public void release() {
    this.close();
  }
}
