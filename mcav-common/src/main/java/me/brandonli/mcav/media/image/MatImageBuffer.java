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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.WritableRaster;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.IOUtils;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The image buffer implementation backed by an OpenCV {@link Mat}.
 *
 * <p>The matrix is always continuous 8-bit BGR, so it can be handed to any OpenCV function directly through
 * {@link #getMat()}. Whoever modifies the matrix through OpenCV must call {@link #invalidateCache()} afterward,
 * so the cached ARGB pixels are rebuilt; the video filters of the library do that automatically.
 *
 * <p>The packed ARGB pixels are only computed when {@link #getPixels()} asks for them, and the byte array used to
 * convert between the two layouts is kept between frames, so updating an image every frame allocates nothing.
 *
 * <p>Operations that cannot work in place, such as resizing, write into a second matrix through
 * {@link #transformMat(BiConsumer)}. The image keeps whichever of the two matrices it does not currently use as a
 * spare, and reuses it for the next such operation and whenever it is resized back to the size of the spare. A video
 * whose frames are resized every frame therefore allocates no native memory once the sizes are settled, at the cost of
 * a second matrix that is released together with the image.
 */
public final class MatImageBuffer implements ImageBuffer {

  private static final int CHANNELS = 3;
  private static final int ALPHA_OPAQUE = 0xFF << 24;
  private static final double SIXTEEN_BIT_SCALE = 1.0 / 257.0;
  private static final double THIRTY_TWO_BIT_SCALE = 1.0 / 16_843_009.0;
  private static final double FLOATING_POINT_SCALE = 255.0;
  private static final double SIGNED_SHIFT = 128.0;

  private Mat mat;
  private @Nullable Mat spare;
  private int@Nullable[] cachedPixels;
  private @Nullable PixelCacheKey cachedKey;
  private byte@Nullable[] scratch;
  private boolean released;

  MatImageBuffer(final Mat mat) {
    final boolean empty = mat.empty();
    Preconditions.checkArgument(!empty, "Mat must not be empty");
    this.mat = toContinuousBgr(mat);
  }

  MatImageBuffer(final byte[] bytes, final int width, final int height) {
    checkDimensions(width, height);
    final int expected = width * height * CHANNELS;
    Preconditions.checkArgument(bytes.length == expected, "Expected %s bytes but got %s", expected, bytes.length);
    this.mat = new Mat(height, width, opencv_core.CV_8UC3);
    final ByteBuffer target = this.mat.createBuffer();
    target.put(bytes);
  }

  MatImageBuffer(final ByteBuffer bytes, final int width, final int height) {
    checkDimensions(width, height);
    final int expected = width * height * CHANNELS;
    final int remaining = bytes.remaining();
    Preconditions.checkArgument(remaining == expected, "Expected %s bytes but got %s", expected, remaining);
    this.mat = new Mat(height, width, opencv_core.CV_8UC3);
    final ByteBuffer source = bytes.duplicate();
    final ByteBuffer target = this.mat.createBuffer();
    target.put(source);
  }

  MatImageBuffer(final byte[] encoded) {
    Preconditions.checkArgument(encoded.length > 0, "Encoded image must not be empty");
    try (final BytePointer pointer = new BytePointer(encoded); final Mat wrapper = new Mat(pointer)) {
      final Mat decoded = opencv_imgcodecs.imdecode(wrapper, opencv_imgcodecs.IMREAD_COLOR);
      final boolean empty = decoded.empty();
      Preconditions.checkArgument(!empty, "Bytes are not a supported image format");
      this.mat = toContinuousBgr(decoded);
    }
  }

  MatImageBuffer(final Frame frame) {
    // the converter returns a view over the memory of the frame, so the pixels have to be copied out; it returns
    // null for frames without an image, such as audio frames
    try (final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat()) {
      final Mat converted = converter.convert(frame);
      Preconditions.checkArgument(converted != null, "Frame does not contain an image");
      this.mat = copyToBgr(converted);
    }
  }

  MatImageBuffer(final UriSource source) {
    final FileSource downloaded = downloadFile(source);
    final Path path = downloaded.getPath();
    this.mat = readFile(path);
  }

  MatImageBuffer(final FileSource source) {
    final Path path = source.getPath();
    this.mat = readFile(path);
  }

  private static Mat readFile(final Path path) {
    final String raw = path.toString();
    final Mat decoded = opencv_imgcodecs.imread(raw, opencv_imgcodecs.IMREAD_COLOR);
    final boolean empty = decoded.empty();
    Preconditions.checkArgument(!empty, "File is not a supported image: %s", path);
    return toContinuousBgr(decoded);
  }

  MatImageBuffer(final int[] argb, final int width, final int height) {
    checkDimensions(width, height);
    final int expected = width * height;
    Preconditions.checkArgument(argb.length == expected, "Expected %s pixels but got %s", expected, argb.length);
    final byte[] bytes = new byte[expected * CHANNELS];
    this.mat = new Mat(height, width, opencv_core.CV_8UC3);
    this.scratch = bytes;
    writeArgb(this.mat, argb, bytes);
  }

  MatImageBuffer(final BufferedImage image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    checkDimensions(width, height);
    this.mat = new Mat(height, width, opencv_core.CV_8UC3);
    writeBufferedImage(this.mat, image, byte[]::new);
  }

  private static FileSource downloadFile(final UriSource source) {
    final Path downloaded = IOUtils.downloadImage(source);
    return FileSource.path(downloaded);
  }

  private static void checkDimensions(final int width, final int height) {
    Preconditions.checkArgument(width > 0 && height > 0, "Image dimensions must be positive but were %sx%s", width, height);
  }

  /**
   * Converts a matrix into continuous 8-bit BGR, releasing the original if a new matrix had to be created.
   */
  private static Mat toContinuousBgr(final Mat source) {
    final int channels = source.channels();
    final int depth = source.depth();
    final boolean continuous = source.isContinuous();
    if (channels == CHANNELS && depth == opencv_core.CV_8U && continuous) {
      return source;
    }
    final Mat converted = copyToBgr(source);
    source.release();
    return converted;
  }

  /**
   * Copies a matrix into a new continuous 8-bit BGR matrix without releasing the original.
   */
  private static Mat copyToBgr(final Mat source) {
    final int channels = source.channels();
    final Mat eightBit = toEightBit(source);
    // matrices compare by the address of their native header, so only a newly converted matrix differs
    final boolean converted = !eightBit.equals(source);
    if (channels == CHANNELS && converted) {
      // converting the depth already produced a new continuous BGR matrix, so a second copy is not needed
      return eightBit;
    }
    final Mat bgr = new Mat();
    switch (channels) {
      case 1 -> opencv_imgproc.cvtColor(eightBit, bgr, opencv_imgproc.COLOR_GRAY2BGR);
      case 3 -> eightBit.copyTo(bgr);
      case 4 -> opencv_imgproc.cvtColor(eightBit, bgr, opencv_imgproc.COLOR_BGRA2BGR);
      default -> throw new IllegalArgumentException("Unsupported number of channels: " + channels);
    }
    if (converted) {
      eightBit.release();
    }
    return bgr;
  }

  /**
   * Converts a matrix of any depth to eight bits per channel by mapping the full range of the depth onto 0 to 255:
   * unsigned integers by dividing by their range, signed integers the same way around the middle value 128, and
   * floating point values, which OpenCV keeps from 0 to 1, by multiplying with 255.
   */
  private static Mat toEightBit(final Mat source) {
    final int depth = source.depth();
    if (depth == opencv_core.CV_8U) {
      return source;
    }
    final double scale = getEightBitScale(depth);
    final boolean signed = depth == opencv_core.CV_8S || depth == opencv_core.CV_16S || depth == opencv_core.CV_32S;
    final double shift = signed ? SIGNED_SHIFT : 0.0;
    final Mat converted = new Mat();
    source.convertTo(converted, opencv_core.CV_8U, scale, shift);
    return converted;
  }

  private static double getEightBitScale(final int depth) {
    return switch (depth) {
      case opencv_core.CV_8S -> 1.0;
      case opencv_core.CV_16U, opencv_core.CV_16S -> SIXTEEN_BIT_SCALE;
      case opencv_core.CV_32S -> THIRTY_TWO_BIT_SCALE;
      default -> FLOATING_POINT_SCALE;
    };
  }

  /**
   * Writes packed ARGB pixels into a BGR matrix of the same size, using a byte array of at least three bytes per
   * pixel to convert them in one bulk copy.
   */
  private static void writeArgb(final Mat target, final int[] argb, final byte[] bytes) {
    int index = 0;
    for (final int pixel : argb) {
      bytes[index] = (byte) pixel;
      bytes[index + 1] = (byte) (pixel >> 8);
      bytes[index + 2] = (byte) (pixel >> 16);
      index += CHANNELS;
    }
    final ByteBuffer buffer = target.createBuffer();
    buffer.put(bytes, 0, index);
  }

  private static void writeBufferedImage(final Mat target, final BufferedImage image, final IntFunction<byte[]> scratchFactory) {
    final int type = image.getType();
    final boolean packed = isPacked(image);
    final WritableRaster raster = image.getRaster();
    final DataBuffer dataBuffer = raster.getDataBuffer();
    if (packed && type == BufferedImage.TYPE_3BYTE_BGR) {
      final DataBufferByte byteBuffer = (DataBufferByte) dataBuffer;
      final byte[] pixels = byteBuffer.getData();
      final ByteBuffer bytes = target.createBuffer();
      bytes.put(pixels);
      return;
    }
    final boolean intType = type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB;
    if (packed && intType) {
      final DataBufferInt intBuffer = (DataBufferInt) dataBuffer;
      final int[] pixels = intBuffer.getData();
      final byte[] bytes = scratchFactory.apply(pixels.length * CHANNELS);
      writeArgb(target, pixels, bytes);
      return;
    }
    final BufferedImage bgr = convertToBgr(image);
    writeBufferedImage(target, bgr, scratchFactory);
  }

  /**
   * Checks that the pixels of an image fill its data buffer exactly and start at its beginning. Sub-images share
   * the data buffer of their parent, so their pixels have to be copied out through drawing instead.
   */
  private static boolean isPacked(final BufferedImage image) {
    final WritableRaster raster = image.getRaster();
    final DataBuffer dataBuffer = raster.getDataBuffer();
    final int translateX = raster.getSampleModelTranslateX();
    final int translateY = raster.getSampleModelTranslateY();
    final int width = raster.getWidth();
    final int height = raster.getHeight();
    final int elementsPerPixel = raster.getNumDataElements();
    final int expectedElements = width * height * elementsPerPixel;
    final int bufferElements = dataBuffer.getSize();
    return translateX == 0 && translateY == 0 && bufferElements == expectedElements;
  }

  private static BufferedImage convertToBgr(final BufferedImage image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final BufferedImage bgr = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
    final Graphics2D graphics = bgr.createGraphics();
    try {
      graphics.drawImage(image, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    return bgr;
  }

  /**
   * Gets the byte array this image converts pixels with, growing it when it is too small, so repeated conversions
   * of frames of the same size reuse one array.
   *
   * @param length the number of bytes needed
   * @return an array of at least that length, whose content is undefined
   */
  @VisibleForTesting
  byte[] getScratch(final int length) {
    final byte[] current = this.scratch;
    if (current != null && current.length >= length) {
      return current;
    }
    final byte[] created = new byte[length];
    this.scratch = created;
    return created;
  }

  /**
   * Gets the OpenCV matrix of this image, which is continuous 8-bit BGR. The matrix is owned by this buffer and
   * must not be released. Call {@link #invalidateCache()} after modifying it.
   *
   * @return the matrix
   * @throws IllegalStateException if the image was released
   */
  public Mat getMat() {
    this.checkNotReleased();
    return this.mat;
  }

  /**
   * Replaces the OpenCV matrix of this image with the result of an operation that could not be done in place.
   * The previous matrix is released, and the new matrix is converted to continuous 8-bit BGR if necessary.
   *
   * @param replacement the new matrix, of which this buffer takes ownership; passing the current matrix only
   *                    refreshes the cached pixels
   * @throws NullPointerException     if the replacement is null
   * @throws IllegalArgumentException if the replacement is empty or has a number of channels other than 1, 3 or 4
   * @throws IllegalStateException    if the image was released
   */
  public void setMat(final Mat replacement) {
    Preconditions.checkNotNull(replacement, "Mat must not be null");
    final boolean empty = replacement.empty();
    Preconditions.checkArgument(!empty, "Mat must not be empty");
    this.checkNotReleased();
    // matrices compare by the address of their native header, so a second wrapper of the current matrix is never
    // mistaken for a new one and released along with the current matrix
    final boolean current = replacement.equals(this.mat);
    if (current) {
      this.invalidateCache();
      return;
    }
    final Mat previous = this.mat;
    this.mat = toContinuousBgr(replacement);
    previous.release();
    this.invalidateCache();
  }

  /**
   * Replaces the matrix of this image with the result of an operation that cannot work in place, such as resizing,
   * rotating, or bilateral filtering, without allocating a new matrix for every call.
   *
   * <p>The operation reads the current matrix and writes its result into the spare matrix of this image. The two are
   * exchanged afterward: the result becomes the matrix of the image, and the previous matrix becomes the spare, which
   * the next operation writes into, and which {@link #setSize(int, int)} and the update methods reuse when they resize
   * the image back to its size. OpenCV only allocates when the result has another size or type than the spare, so a
   * filter that runs every frame allocates nothing once the sizes are settled. The cached pixels are invalidated. If
   * the operation fails, the image keeps its matrix and drops the spare.
   *
   * @param operation reads the first matrix, which it must leave untouched, and writes its result into the second, for
   *                  example with {@code (source, target) -> opencv_core.transpose(source, target)}; the result must
   *                  not be empty, must have 1, 3 or 4 channels, and is converted to continuous 8-bit BGR if necessary
   * @throws NullPointerException     if the operation is null
   * @throws IllegalArgumentException if the operation left the target empty or it has an unsupported number of
   *                                  channels
   * @throws IllegalStateException    if the image was released
   */
  public void transformMat(final BiConsumer<Mat, Mat> operation) {
    Preconditions.checkNotNull(operation, "Operation must not be null");
    this.checkNotReleased();
    final Mat target = this.takeSpare();
    final Mat result;
    try {
      operation.accept(this.mat, target);
      final boolean empty = target.empty();
      Preconditions.checkArgument(!empty, "The operation must write its result into the target matrix");
      result = toContinuousBgr(target);
    } catch (final RuntimeException failure) {
      target.close();
      throw failure;
    }
    this.exchange(result);
  }

  /**
   * Takes the spare matrix out of this image, or creates an empty one if there is none, so a failing operation never
   * leaves a half-written spare behind.
   */
  private Mat takeSpare() {
    final Mat existing = this.spare;
    this.spare = null;
    if (existing != null) {
      return existing;
    }
    return new Mat();
  }

  /**
   * Makes a result the matrix of this image and keeps the previous matrix as the spare, unless the result shares its
   * pixel memory, in which case writing into the spare would overwrite the image.
   *
   * <p>Sharing is recognised by the address the pixels start at, which an operation that returns its source, or a
   * matrix wrapping the same data, compares equal on. A view into the middle of the previous matrix, such as a region
   * of interest, starts at another address and would be kept as the spare although it points into the image, so
   * {@link #transformMat(BiConsumer)} requires an operation that writes a matrix of its own or returns the source
   * unchanged. No filter of the library produces such a view.
   */
  private void exchange(final Mat result) {
    final Mat previous = this.mat;
    final BytePointer resultStart = result.datastart();
    final BytePointer previousStart = previous.datastart();
    final boolean shared = resultStart.address() == previousStart.address();
    this.mat = result;
    if (shared) {
      previous.release();
    } else {
      this.spare = previous;
    }
    this.invalidateCache();
  }

  /**
   * Changes the size of this image without keeping its pixels, for code that fills the image itself through
   * {@link #getData()}, such as a decoder. An image that already has the size is left untouched. Otherwise the spare
   * matrix takes the place of the current one if it has the size, see {@link #transformMat(BiConsumer)}, and a new
   * matrix is allocated if not; the pixels are undefined afterward.
   *
   * @param width  the new width in pixels
   * @param height the new height in pixels
   * @throws IllegalArgumentException if the width or the height is not positive
   * @throws IllegalStateException    if the image was released
   */
  public void setSize(final int width, final int height) {
    checkDimensions(width, height);
    this.checkNotReleased();
    final boolean resized = this.ensureSize(width, height);
    if (resized) {
      this.invalidateCache();
    }
  }

  @Override
  public BufferedImage toBufferedImage() {
    this.checkNotReleased();
    final int width = this.mat.cols();
    final int height = this.mat.rows();
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
    final WritableRaster raster = image.getRaster();
    final DataBufferByte dataBuffer = (DataBufferByte) raster.getDataBuffer();
    final byte[] pixels = dataBuffer.getData();
    final ByteBuffer bytes = this.mat.createBuffer();
    bytes.get(pixels);
    return image;
  }

  @Override
  public void setAsBufferedImage(final BufferedImage image) {
    Preconditions.checkNotNull(image, "Image must not be null");
    this.checkNotReleased();
    final int width = image.getWidth();
    final int height = image.getHeight();
    this.ensureSize(width, height);
    writeBufferedImage(this.mat, image, this::getScratch);
    this.invalidateCache();
  }

  /**
   * Makes the matrix the given size, keeping it if it already has that size. Otherwise the spare matrix takes its place
   * if it has the size, and a new matrix if not. An image with a spare went through {@link #transformMat(BiConsumer)},
   * which will most likely produce the size of the previous matrix again, so the previous matrix becomes the new spare;
   * images without a spare release it.
   *
   * @return true if the matrix was replaced, false if it already had the size
   */
  private boolean ensureSize(final int width, final int height) {
    final Mat previous = this.mat;
    if (hasSize(previous, width, height)) {
      return false;
    }
    final Mat reusable = this.spare;
    if (reusable == null) {
      this.mat = new Mat(height, width, opencv_core.CV_8UC3);
      previous.release();
      return true;
    }
    if (hasSize(reusable, width, height)) {
      this.mat = reusable;
    } else {
      this.mat = new Mat(height, width, opencv_core.CV_8UC3);
      reusable.release();
    }
    this.spare = previous;
    return true;
  }

  /**
   * Checks the size of a matrix of this image. Both the matrix and the spare are always continuous 8-bit BGR, because
   * the spare is always a former matrix of the image, so the size is all that can differ.
   */
  private static boolean hasSize(final Mat candidate, final int width, final int height) {
    final int candidateWidth = candidate.cols();
    final int candidateHeight = candidate.rows();
    return candidateWidth == width && candidateHeight == height;
  }

  @Override
  public void setPixel(final int x, final int y, final double[] value) {
    Preconditions.checkNotNull(value, "Value must not be null");
    this.checkNotReleased();
    this.checkCoordinates(x, y);
    final ByteBuffer bytes = this.mat.createBuffer();
    final int width = this.mat.cols();
    final int base = (y * width + x) * CHANNELS;
    final int count = Math.min(value.length, CHANNELS);
    for (int channel = 0; channel < count; channel++) {
      final long rounded = Math.round(value[channel]);
      final int clamped = Math.clamp(rounded, 0, 255);
      bytes.put(base + channel, (byte) clamped);
    }
    this.invalidateCache();
  }

  @Override
  public double[] getPixel(final int x, final int y) {
    this.checkNotReleased();
    this.checkCoordinates(x, y);
    final ByteBuffer bytes = this.mat.createBuffer();
    final int width = this.mat.cols();
    final int base = (y * width + x) * CHANNELS;
    final double[] values = new double[CHANNELS];
    for (int channel = 0; channel < CHANNELS; channel++) {
      values[channel] = bytes.get(base + channel) & 0xFF;
    }
    return values;
  }

  private void checkCoordinates(final int x, final int y) {
    final int width = this.mat.cols();
    final int height = this.mat.rows();
    Preconditions.checkArgument(x >= 0 && x < width, "x must be between 0 and %s but was %s", width - 1, x);
    Preconditions.checkArgument(y >= 0 && y < height, "y must be between 0 and %s but was %s", height - 1, y);
  }

  @Override
  public int getWidth() {
    this.checkNotReleased();
    return this.mat.cols();
  }

  @Override
  public int getHeight() {
    this.checkNotReleased();
    return this.mat.rows();
  }

  @Override
  public int getPixelCount() {
    this.checkNotReleased();
    final int width = this.mat.cols();
    final int height = this.mat.rows();
    return width * height;
  }

  @Override
  public int[] getPixels() {
    this.checkNotReleased();
    final PixelCacheKey key = createCacheKey(this.mat);
    final int[] cached = this.cachedPixels;
    if (cached != null && key.equals(this.cachedKey)) {
      return cached;
    }
    final int width = key.getWidth();
    final int height = key.getHeight();
    final int[] pixels = this.readArgb(width, height);
    this.cachedPixels = pixels;
    this.cachedKey = key;
    return pixels;
  }

  /**
   * Identifies the pixel memory of a matrix. The address of the pixel data is used rather than the address of the
   * matrix header, because OpenCV functions reallocate the pixels of a matrix while its header stays in place.
   */
  private static PixelCacheKey createCacheKey(final Mat mat) {
    final BytePointer data = mat.data();
    final long address = data.address();
    final int width = mat.cols();
    final int height = mat.rows();
    return new PixelCacheKey(address, width, height);
  }

  private int[] readArgb(final int width, final int height) {
    final int count = width * height;
    final int length = count * CHANNELS;
    final int[] pixels = new int[count];
    final byte[] raw = this.getScratch(length);
    final ByteBuffer bytes = this.mat.createBuffer();
    bytes.get(raw, 0, length);
    int byteIndex = 0;
    for (int pixelIndex = 0; pixelIndex < count; pixelIndex++) {
      final int blue = raw[byteIndex] & 0xFF;
      final int green = raw[byteIndex + 1] & 0xFF;
      final int red = raw[byteIndex + 2] & 0xFF;
      pixels[pixelIndex] = ALPHA_OPAQUE | (red << 16) | (green << 8) | blue;
      byteIndex += CHANNELS;
    }
    return pixels;
  }

  @Override
  public ByteBuffer getData() {
    this.checkNotReleased();
    return this.mat.createBuffer();
  }

  @Override
  public void updateData(final ByteBuffer data, final int width, final int height) {
    Preconditions.checkNotNull(data, "Data must not be null");
    checkDimensions(width, height);
    this.checkNotReleased();
    final int expected = width * height * CHANNELS;
    final int remaining = data.remaining();
    Preconditions.checkArgument(remaining == expected, "Expected %s bytes but got %s", expected, remaining);
    this.ensureSize(width, height);
    final ByteBuffer source = data.duplicate();
    final ByteBuffer target = this.mat.createBuffer();
    target.put(source);
    this.invalidateCache();
  }

  @Override
  public void updateArgb(final int[] pixels, final int width, final int height) {
    Preconditions.checkNotNull(pixels, "Pixels must not be null");
    checkDimensions(width, height);
    this.checkNotReleased();
    final int expected = width * height;
    Preconditions.checkArgument(pixels.length == expected, "Expected %s pixels but got %s", expected, pixels.length);
    this.ensureSize(width, height);
    final byte[] bytes = this.getScratch(expected * CHANNELS);
    writeArgb(this.mat, pixels, bytes);
    this.invalidateCache();
  }

  @Override
  public void invalidateCache() {
    this.cachedPixels = null;
  }

  @Override
  public ImageBuffer copy() {
    this.checkNotReleased();
    final Mat clone = this.mat.clone();
    return new MatImageBuffer(clone);
  }

  @Override
  public void release() {
    if (this.released) {
      return;
    }
    this.released = true;
    this.cachedPixels = null;
    this.scratch = null;
    this.mat.release();
    final Mat unused = this.spare;
    this.spare = null;
    if (unused != null) {
      unused.release();
    }
  }

  private void checkNotReleased() {
    if (this.released) {
      throw new IllegalStateException("Image buffer has been released");
    }
  }
}
