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

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2BGR;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.examinable.ExaminableObject;
import me.brandonli.mcav.utils.examinable.ExaminableProperty;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.opencv.core.CvType;

/**
 * A class that represents an image backed by an OpenCV Mat object. It provides various image
 * processing methods such as resizing, rotating, flipping, and applying filters.
 */
public class MatImageBuffer extends ExaminableObject implements ImageBuffer {

  /**
   * A property that represents the OpenCV Mat object associated with this image buffer.
   */
  public static final ExaminableProperty<Mat> MAT_PROPERTY = ExaminableProperty.property("mat", Mat.class);

  @SuppressWarnings("all") // checker: ThreadLocal.withInitial guarantees non-null
  private static final ThreadLocal<OpenCVFrameConverter.ToMat> CONVERTER = ThreadLocal.withInitial(OpenCVFrameConverter.ToMat::new);

  @SuppressWarnings("all") // checker: ThreadLocal.withInitial guarantees non-null
  private static final ThreadLocal<Java2DFrameConverter> IMAGE_CONVERTER = ThreadLocal.withInitial(Java2DFrameConverter::new);

  private @Nullable BytePointer pointer;
  private final Mat mat;

  MatImageBuffer(final Mat mat) {
    this.mat = mat;
    this.assignMat(this.mat);
  }

  MatImageBuffer(final byte[] bytes, final int width, final int height) {
    final BytePointer ptr = new BytePointer(bytes);
    final int step = bytes.length / height;
    this.mat = new Mat(height, width, CV_8UC3, ptr, step);
    this.pointer = ptr;
    this.assignMat(this.mat);
  }

  MatImageBuffer(final Frame frame) {
    final Mat converted = CONVERTER.get().convert(frame);
    this.mat = new Mat();
    opencv_imgproc.cvtColor(converted, this.mat, opencv_imgproc.COLOR_YUV2BGR_I420);
    this.assignMat(this.mat);
  }

  MatImageBuffer(final ByteBuffer bytes, final int width, final int height) {
    bytes.rewind();
    final int totalBytes = bytes.remaining();
    final int stride = totalBytes / height;
    final BytePointer ptr = new BytePointer(bytes);
    final Mat mat = new Mat(height, width, CV_8UC3, ptr, stride);
    this.pointer = ptr;
    this.mat = mat;
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
    final Mat originalMat = new Mat(height, width, CV_8UC4);
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

    final Mat bgr = new Mat();
    opencv_imgproc.cvtColor(originalMat, bgr, COLOR_BGRA2BGR);
    originalMat.release();

    this.mat = bgr;
    this.assignMat(this.mat);
  }

  MatImageBuffer(final BufferedImage image) throws IOException {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final Mat mat;
    switch (image.getType()) {
      case BufferedImage.TYPE_3BYTE_BGR: {
        final byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat = new Mat(height, width, CV_8UC3);
        mat.data().put(pixels);
        this.mat = mat;
        this.assignMat(this.mat);
        break;
      }
      case BufferedImage.TYPE_4BYTE_ABGR: {
        final byte[] abgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat = new Mat(height, width, CV_8UC4);
        final BytePointer ptr = mat.data();
        for (int i = 0; i < abgr.length; i += 4) {
          ptr.put(i + 0, abgr[i + 1]);
          ptr.put(i + 1, abgr[i + 2]);
          ptr.put(i + 2, abgr[i + 3]);
          ptr.put(i + 3, abgr[i + 0]);
        }
        this.mat = mat;
        this.assignMat(this.mat);
        break;
      }
      case BufferedImage.TYPE_BYTE_GRAY: {
        final byte[] gray = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat = new Mat(height, width, CV_8UC1);
        mat.data().put(gray);
        this.mat = mat;
        this.assignMat(this.mat);
        break;
      }
      default: {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", byteArrayOutputStream);
        byteArrayOutputStream.flush();
        this.mat = opencv_imgcodecs.imdecode(
          new Mat(new BytePointer(byteArrayOutputStream.toByteArray())),
          opencv_imgcodecs.IMREAD_UNCHANGED
        );
        this.assignMat(this.mat);
        break;
      }
    }
  }

  MatImageBuffer(final byte[] bytes) {
    final BytePointer bytePointer = new BytePointer(bytes);
    final Mat temp = new Mat(bytePointer);
    this.mat = opencv_imgcodecs.imdecode(temp, opencv_imgcodecs.IMREAD_UNCHANGED);
    this.pointer = bytePointer;
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
    try (final Frame f = CONVERTER.get().convert(this.mat)) {
      return IMAGE_CONVERTER.get().getBufferedImage(f);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setPixel(final int x, final int y, final double[] value) {
    final ByteBuffer buf = this.mat.createBuffer();
    final int channels = this.mat.channels();
    final int len = Math.min(value.length, channels);
    if (this.mat.isContinuous()) {
      final int cols = this.mat.cols();
      final int base = (y * cols + x) * channels;
      for (int c = 0; c < len; c++) {
        buf.put(base + c, (byte) Math.round(value[c]));
      }
    } else {
      final long stepLong = this.mat.step();
      final int step = (int) stepLong;
      final int base = y * step + x * channels;
      for (int c = 0; c < len; c++) {
        buf.put(base + c, (byte) Math.round(value[c]));
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double[] getPixel(final int x, final int y) {
    final ByteBuffer buf = this.mat.createBuffer();
    final int channels = this.mat.channels();
    final double[] values = new double[channels];
    if (this.mat.isContinuous()) {
      final int cols = this.mat.cols();
      final int base = (y * cols + x) * channels;
      for (int c = 0; c < channels; c++) {
        values[c] = buf.get(base + c) & 0xFF;
      }
    } else {
      final long stepLong = this.mat.step();
      final int step = (int) stepLong;
      final int base = y * step + x * channels;
      for (int c = 0; c < channels; c++) {
        values[c] = buf.get(base + c) & 0xFF;
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
    final int total = width * height;
    final int[] pixels = new int[total];
    final ByteBuffer buf = this.mat.createBuffer();

    if (channels == 4 && this.mat.isContinuous()) {
      buf.order(ByteOrder.LITTLE_ENDIAN);
      final IntBuffer ib = buf.asIntBuffer();
      ib.get(pixels);
      return pixels;
    }

    final byte[] raw = new byte[total * channels];
    buf.get(raw);
    int i = 0, j = 0;
    if (channels == 4) {
      while (i < total) {
        final int b = raw[j++] & 0xFF;
        final int g = raw[j++] & 0xFF;
        final int r = raw[j++] & 0xFF;
        final int a = raw[j++] & 0xFF;
        pixels[i++] = (a << 24) | (r << 16) | (g << 8) | b;
      }
    } else if (channels == 3) {
      while (i < total) {
        final int b = raw[j++] & 0xFF;
        final int g = raw[j++] & 0xFF;
        final int r = raw[j++] & 0xFF;
        pixels[i++] = (0xFF << 24) | (r << 16) | (g << 8) | b;
      }
    } else if (channels == 1) {
      while (i < total) {
        final int v = raw[j++] & 0xFF;
        pixels[i++] = (0xFF << 24) | (v << 16) | (v << 8) | v;
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
    final BufferedImage bgrImage = this.convertImage(image);

    final byte[] pixels = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();
    final Mat newMat = new Mat(bgrImage.getHeight(), bgrImage.getWidth(), CvType.CV_8UC3);
    newMat.data().put(pixels);

    this.mat.release();
    newMat.copyTo(this.mat);
    newMat.release();
  }

  @SuppressWarnings("all") // fuck you checker for say observer can't be null
  private BufferedImage convertImage(final BufferedImage image) {
    final BufferedImage bgrImage;
    if (image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
      bgrImage = image;
    } else {
      bgrImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
      final Graphics2D g = bgrImage.createGraphics();
      try {
        g.drawImage(image, 0, 0, null);
      } finally {
        g.dispose();
      }
    }
    return bgrImage;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPixelCount() {
    return (int) this.mat.arraySize();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() {
    this.mat.release();
    if (this.pointer != null) {
      this.pointer.deallocate();
      this.pointer = null;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    this.close();
  }

  @Override
  public ByteBuffer getData() {
    return this.mat.asByteBuffer();
  }
}
