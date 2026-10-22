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
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.IoUtils;
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
 * convert between the two layouts is kept between frames. Updating an image can reuse that scratch space;
 * the ARGB array is allocated again when requested after a change.
 *
 * <p>Operations that cannot work in place, such as resizing, write into a second matrix through
 * {@link #transformMat(BiConsumer)}. The image keeps whichever of the two matrices it does not currently use as a
 * spare, and reuses it for the next such operation and whenever it is resized back to the size of the spare. A video
 * whose frames are resized every frame therefore allocates no native memory once the sizes are settled, at the cost of
 * a second matrix that is released together with the image.
 *
 * <p>This class is not thread-safe.
 *
 * @since 1.0.0
 */
public final class MatImageBuffer implements ImageBuffer {

  private static final int CHANNELS = 3;
  private static final int GRAY_CHANNELS = 1;
  private static final int BGRA_CHANNELS = 4;
  private static final int GREEN_OFFSET = 1;
  private static final int RED_OFFSET = 2;

  private static final int ALPHA_OPAQUE = 0xFF << 24;
  private static final int CHANNEL_MASK = 0xFF;
  private static final int CHANNEL_MAXIMUM = 255;
  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final double SIXTEEN_BIT_SCALE = 1.0 / 257.0;
  private static final double THIRTY_TWO_BIT_SCALE = 1.0 / 16_843_009.0;
  private static final double FLOATING_POINT_SCALE = 255.0;
  private static final double SIGNED_SHIFT = 128.0;

  private static final String EMPTY_MAT_MESSAGE = "Mat must not be empty!";
  private static final String BYTE_COUNT_MESSAGE = "Byte count %s must be %s!";
  private static final String PIXEL_COUNT_MESSAGE = "Pixel count %s must be %s!";
  private static final String UNSUPPORTED_BYTES_MESSAGE = "Bytes are not a supported image format!";
  private static final String UNSUPPORTED_FILE_MESSAGE = "File %s is not a supported image!";

  private Mat mat;
  private @Nullable Mat spare;
  private int @Nullable [] cachedPixels;
  private @Nullable PixelCacheKey cachedKey;
  private byte @Nullable [] scratch;
  private boolean released;

  MatImageBuffer(final Mat mat) {
    final boolean empty = mat.empty();
    Preconditions.checkArgument(!empty, EMPTY_MAT_MESSAGE);

    this.mat = toContinuousBgr(mat);
  }

  MatImageBuffer(final byte[] bytes, final int width, final int height) {
    checkDimensions(width, height);

    final int expected = width * height * CHANNELS;
    Preconditions.checkArgument(bytes.length == expected, BYTE_COUNT_MESSAGE, bytes.length, expected);

    this.mat = new Mat(height, width, opencv_core.CV_8UC3);
    final ByteBuffer target = this.mat.createBuffer();
    target.put(bytes);
  }

  MatImageBuffer(final ByteBuffer bytes, final int width, final int height) {
    checkDimensions(width, height);

    final int expected = width * height * CHANNELS;
    final int remaining = bytes.remaining();
    Preconditions.checkArgument(remaining == expected, BYTE_COUNT_MESSAGE, remaining, expected);

    this.mat = new Mat(height, width, opencv_core.CV_8UC3);
    final ByteBuffer source = bytes.duplicate();
    final ByteBuffer target = this.mat.createBuffer();
    target.put(source);
  }

  MatImageBuffer(final byte[] encoded) {
    Preconditions.checkArgument(encoded.length > 0, "Encoded image must not be empty!");

    // OpenCV allocates the whole picture once it has read the header, so a small file declaring a huge size is
    // refused before it gets there
    DeclaredImageSize.checkBytes(encoded);
    try (final BytePointer pointer = new BytePointer(encoded); final Mat wrapper = new Mat(pointer)) {
      final Mat decoded = decodeBytes(wrapper);
      final boolean empty = decoded.empty();
      Preconditions.checkArgument(!empty, UNSUPPORTED_BYTES_MESSAGE);
      this.mat = toContinuousBgr(decoded);
    }
  }

  MatImageBuffer(final Frame frame) {
    // the converter returns a view over the memory of the frame, so the pixels have to be copied out; it returns
    // null for frames without an image, such as audio frames
    try (final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat()) {
      final Mat converted = converter.convert(frame);
      Preconditions.checkArgument(converted != null, "Frame does not contain an image!");

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

  MatImageBuffer(final int[] argb, final int width, final int height) {
    checkDimensions(width, height);

    final int expected = width * height;
    Preconditions.checkArgument(argb.length == expected, PIXEL_COUNT_MESSAGE, argb.length, expected);

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

  private static Mat readFile(final Path path) {
    DeclaredImageSize.checkFile(path);
    final Mat decoded = decodeFile(path);
    final boolean empty = decoded.empty();
    Preconditions.checkArgument(!empty, UNSUPPORTED_FILE_MESSAGE, path);

    return toContinuousBgr(decoded);
  }

  private static Mat decodeBytes(final Mat encoded) {
    try {
      return opencv_imgcodecs.imdecode(encoded, opencv_imgcodecs.IMREAD_COLOR);
    } catch (final RuntimeException exception) {
      // OpenCV raises an error for some images instead of returning an empty matrix, such as a side over its own limit
      throw new IllegalArgumentException(UNSUPPORTED_BYTES_MESSAGE, exception);
    }
  }

  private static Mat decodeFile(final Path path) {
    final String raw = path.toString();
    try {
      return opencv_imgcodecs.imread(raw, opencv_imgcodecs.IMREAD_COLOR);
    } catch (final RuntimeException exception) {
      // OpenCV raises an error for some images instead of returning an empty matrix, such as a side over its own limit
      final String message = UNSUPPORTED_FILE_MESSAGE.formatted(path);
      throw new IllegalArgumentException(message, exception);
    }
  }

  private static FileSource downloadFile(final UriSource source) {
    final Path downloaded = IoUtils.downloadImage(source);
    return FileSource.path(downloaded);
  }

  private static void checkDimensions(final int width, final int height) {
    Preconditions.checkArgument(width > 0 && height > 0, "Image dimensions %sx%s must be positive!", width, height);

    // BGR arrays and ByteBuffer capacities are indexed by int; the pixels are compared with the limit divided by the
    // channels, as multiplying two int dimensions and the channel count can overflow even a long
    final long pixels = (long) width * height;
    Preconditions.checkArgument(
      pixels <= Integer.MAX_VALUE / CHANNELS,
      "Image dimensions %sx%s exceed the maximum BGR buffer size!",
      width,
      height
    );
  }

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

  private static Mat copyToBgr(final Mat source) {
    return copyToBgr(source, Mat::release);
  }

  // test seam for the mutants that skip or misplace the release of the eight-bit copy, which only leak native memory
  @VisibleForTesting
  static Mat copyToBgr(final Mat source, final Consumer<Mat> release) {
    final int channels = source.channels();
    final Mat eightBit = toEightBit(source);
    // matrices compare by the address of their native header, so only a newly converted matrix differs
    final boolean converted = !eightBit.equals(source);
    if (channels == CHANNELS && converted) {
      return eightBit;
    }

    final Mat bgr = new Mat();
    switch (channels) {
      case GRAY_CHANNELS -> opencv_imgproc.cvtColor(eightBit, bgr, opencv_imgproc.COLOR_GRAY2BGR);
      case CHANNELS -> eightBit.copyTo(bgr);
      case BGRA_CHANNELS -> opencv_imgproc.cvtColor(eightBit, bgr, opencv_imgproc.COLOR_BGRA2BGR);
      default -> {
        final String message = "Channel count %s is not supported!".formatted(channels);
        throw new IllegalArgumentException(message);
      }
    }

    if (converted) {
      release.accept(eightBit);
    }

    return bgr;
  }

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

  private static void writeArgb(final Mat target, final int[] argb, final byte[] bytes) {
    int index = 0;
    for (final int pixel : argb) {
      bytes[index] = (byte) pixel;
      bytes[index + GREEN_OFFSET] = (byte) (pixel >> GREEN_SHIFT);
      bytes[index + RED_OFFSET] = (byte) (pixel >> RED_SHIFT);
      index += CHANNELS;
    }

    final ByteBuffer buffer = target.createBuffer();
    buffer.put(bytes, 0, index);
  }

  private static void writeBufferedImage(final Mat target, final BufferedImage image, final IntFunction<byte[]> scratchFactory) {
    final int type = image.getType();
    final boolean copyable = isPacked(image) && isSrgb(image);
    final WritableRaster raster = image.getRaster();
    final DataBuffer dataBuffer = raster.getDataBuffer();
    if (copyable && type == BufferedImage.TYPE_3BYTE_BGR) {
      final DataBufferByte byteBuffer = (DataBufferByte) dataBuffer;
      final byte[] pixels = byteBuffer.getData();
      final ByteBuffer bytes = target.createBuffer();
      bytes.put(pixels);
      return;
    }

    final boolean intType = type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB;
    if (copyable && intType) {
      final DataBufferInt intBuffer = (DataBufferInt) dataBuffer;
      final int[] pixels = intBuffer.getData();
      final byte[] bytes = scratchFactory.apply(pixels.length * CHANNELS);
      writeArgb(target, pixels, bytes);
      return;
    }

    // getRGB applies the source ColorModel's conversion to sRGB. Java2D's grayscale blit may instead copy
    // linear gray samples as sRGB bytes, darkening an image (gray sample 8 is sRGB 50, not 8).
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = new int[width * height];
    image.getRGB(0, 0, width, height, pixels, 0, width);
    final byte[] bytes = scratchFactory.apply(pixels.length * CHANNELS);
    writeArgb(target, pixels, bytes);
  }

  // Checks that the pixels of an image fill its data buffer exactly and start at its beginning. Sub-images share the
  // data buffer of their parent, so their pixels are read through the raster-aware getRGB fallback instead.
  private static boolean isPacked(final BufferedImage image) {
    final WritableRaster raster = image.getRaster();
    final DataBuffer dataBuffer = raster.getDataBuffer();
    final int translateX = raster.getSampleModelTranslateX();
    final int translateY = raster.getSampleModelTranslateY();
    final int width = raster.getWidth();
    final int height = raster.getHeight();
    final int elementsPerPixel = raster.getNumDataElements();
    final long expectedElements = (long) width * height * elementsPerPixel;
    final int bufferElements = dataBuffer.getSize();
    final int offset = dataBuffer.getOffset();
    final int banks = dataBuffer.getNumBanks();
    if (translateX != 0 || translateY != 0 || offset != 0 || banks != 1 || bufferElements != expectedElements) {
      return false;
    }

    // DataBuffer.getSize() describes its logical range, not the length or layout of the backing array.
    final SampleModel sampleModel = raster.getSampleModel();
    if (dataBuffer instanceof final DataBufferByte bytes && sampleModel instanceof final PixelInterleavedSampleModel interleaved) {
      final byte[] backing = bytes.getData();
      final int pixelStride = interleaved.getPixelStride();
      final int rowStride = interleaved.getScanlineStride();
      return backing.length == expectedElements && pixelStride == CHANNELS && rowStride == (long) width * CHANNELS;
    }

    if (dataBuffer instanceof final DataBufferInt integers && sampleModel instanceof final SinglePixelPackedSampleModel packed) {
      final int[] backing = integers.getData();
      final int rowStride = packed.getScanlineStride();
      return backing.length == expectedElements && rowStride == width;
    }

    return false;
  }

  // A linear RGB image reports the same types as an sRGB one, but its samples need the conversion of getRGB.
  private static boolean isSrgb(final BufferedImage image) {
    final ColorModel colorModel = image.getColorModel();
    final ColorSpace colorSpace = colorModel.getColorSpace();
    return colorSpace.isCS_sRGB();
  }

  // Returns the byte array this image converts pixels with, growing it when it is too small, so repeated conversions of
  // frames of the same size reuse one array.
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
   * Returns the OpenCV matrix of this image, which is continuous 8-bit BGR. The matrix is owned by this buffer and
   * must not be released. Call {@link #invalidateCache()} after modifying it.
   *
   * @return the matrix
   * @throws IllegalStateException if the image was released
   * @since 1.0.0
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
   * @since 1.0.0
   */
  public void setMat(final Mat replacement) {
    Preconditions.checkNotNull(replacement, "Mat must not be null!");

    final boolean empty = replacement.empty();
    Preconditions.checkArgument(!empty, EMPTY_MAT_MESSAGE);

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
   * @since 1.0.0
   */
  public void transformMat(final BiConsumer<Mat, Mat> operation) {
    Preconditions.checkNotNull(operation, "Operation must not be null!");

    this.checkNotReleased();

    final Mat target = this.takeSpare();
    final Mat result;
    try {
      operation.accept(this.mat, target);
      final boolean empty = target.empty();
      Preconditions.checkArgument(!empty, "The operation must write its result into the target matrix!");

      result = toContinuousBgr(target);
    } catch (final RuntimeException exception) {
      target.close();
      throw exception;
    }

    this.exchange(result);
  }

  // Takes the spare matrix out of this image, or creates an empty one if there is none, so a failing operation never
  // leaves a half-written spare behind.
  private Mat takeSpare() {
    final Mat existing = this.spare;
    this.spare = null;

    if (existing != null) {
      return existing;
    }

    return new Mat();
  }

  // Makes a result the matrix of this image and keeps the previous matrix as the spare, unless the result shares its
  // pixel memory, in which case writing into the spare would overwrite the image. Sharing is recognised by datastart(),
  // the address of the allocation the pixels belong to: an operation that returns its source, a matrix wrapping the
  // same data, and an OpenCV region of interest of the previous matrix, whose first pixel lies elsewhere in that
  // allocation, all compare equal on it. A matrix wrapped around a pointer into the middle of the pixels has an
  // allocation of its own and is not recognised, so transformMat(BiConsumer) still requires an operation that writes a
  // matrix of its own or returns the source unchanged.
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
   * @throws IllegalArgumentException if the dimensions are not positive or the packed BGR storage exceeds
   *                                  {@link Integer#MAX_VALUE} bytes
   * @throws IllegalStateException    if the image was released
   * @since 1.0.0
   */
  public void setSize(final int width, final int height) {
    checkDimensions(width, height);
    this.checkNotReleased();

    final boolean resized = this.ensureSize(width, height);
    if (resized) {
      this.invalidateCache();
    }
  }

  /**
   * Copies the backing OpenCV matrix into a new Java image of type {@link BufferedImage#TYPE_3BYTE_BGR}.
   *
   * @return a new Java image
   * @throws IllegalStateException if the image was released
   * @since 1.0.0
   */
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

  /**
   * Replaces the content of this buffer with a Java image, converting it to BGR and resizing the backing OpenCV matrix if necessary.
   *
   * @param image the image to copy
   * @throws IllegalStateException if the image was released
   * @throws NullPointerException  if {@code image} is null
   * @since 1.0.0
   */
  @Override
  public void setAsBufferedImage(final BufferedImage image) {
    Preconditions.checkNotNull(image, "Image must not be null!");

    this.checkNotReleased();

    final int width = image.getWidth();
    final int height = image.getHeight();
    this.ensureSize(width, height);
    writeBufferedImage(this.mat, image, this::getScratch);
    this.invalidateCache();
  }

  // Makes the matrix the given size, keeping it if it already has that size. Otherwise the spare matrix takes its place
  // if it has the size, and a new matrix if not. An image with a spare went through transformMat(BiConsumer), which
  // will most likely produce the size of the previous matrix again, so the previous matrix becomes the new spare;
  // images without a spare release it.
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

  // Checks the size of a matrix of this image. Both the matrix and the spare are always continuous 8-bit BGR, because
  // the spare is always a former matrix of the image, so the size is all that can differ.
  private static boolean hasSize(final Mat candidate, final int width, final int height) {
    final int candidateWidth = candidate.cols();
    final int candidateHeight = candidate.rows();
    return candidateWidth == width && candidateHeight == height;
  }

  /**
   * Sets the color of one pixel in the backing OpenCV matrix.
   *
   * @param column the zero-based column, less than {@link #getWidth()}
   * @param row    the zero-based row, less than {@link #getHeight()}
   * @param value  the non-null blue, green and red components in that order; the first three supplied
   *               values are rounded and clamped to 0 through 255, missing components are unchanged,
   *               and additional components are ignored
   * @throws IllegalArgumentException if the row or column is outside the image
   * @throws IllegalStateException    if the image was released
   * @throws NullPointerException     if {@code value} is null
   * @since 1.0.0
   */
  @Override
  public void setPixel(final int column, final int row, final double[] value) {
    Preconditions.checkNotNull(value, "Value must not be null!");

    this.checkNotReleased();
    this.checkCoordinates(column, row);

    final ByteBuffer bytes = this.mat.createBuffer();
    final int width = this.mat.cols();
    final int base = (row * width + column) * CHANNELS;
    final int count = Math.min(value.length, CHANNELS);
    for (int channel = 0; channel < count; channel++) {
      final long rounded = Math.round(value[channel]);
      final int clamped = Math.clamp(rounded, 0, CHANNEL_MAXIMUM);
      bytes.put(base + channel, (byte) clamped);
    }

    this.invalidateCache();
  }

  /**
   * Returns the color of one pixel, read from the backing OpenCV matrix.
   *
   * @param column the zero-based column, less than {@link #getWidth()}
   * @param row    the zero-based row, less than {@link #getHeight()}
   * @return a new three-element array containing blue, green and red, each from 0 to 255
   * @throws IllegalArgumentException if the row or column is outside the image
   * @throws IllegalStateException    if the image was released
   * @since 1.0.0
   */
  @Override
  public double[] getPixel(final int column, final int row) {
    this.checkNotReleased();
    this.checkCoordinates(column, row);

    final ByteBuffer bytes = this.mat.createBuffer();
    final int width = this.mat.cols();
    final int base = (row * width + column) * CHANNELS;
    final double[] values = new double[CHANNELS];
    for (int channel = 0; channel < CHANNELS; channel++) {
      values[channel] = bytes.get(base + channel) & CHANNEL_MASK;
    }

    return values;
  }

  private void checkCoordinates(final int column, final int row) {
    final int width = this.mat.cols();
    final int height = this.mat.rows();
    Preconditions.checkArgument(column >= 0 && column < width, "x %s must be between 0 and %s!", column, width - 1);
    Preconditions.checkArgument(row >= 0 && row < height, "y %s must be between 0 and %s!", row, height - 1);
  }

  /**
   * Returns the width of the image, read from the column count of the backing OpenCV matrix.
   *
   * @return the width in pixels
   * @throws IllegalStateException if the image was released
   * @since 1.0.0
   */
  @Override
  public int getWidth() {
    this.checkNotReleased();

    return this.mat.cols();
  }

  /**
   * Returns the height of the image, read from the row count of the backing OpenCV matrix.
   *
   * @return the height in pixels
   * @throws IllegalStateException if the image was released
   * @since 1.0.0
   */
  @Override
  public int getHeight() {
    this.checkNotReleased();

    return this.mat.rows();
  }

  /**
   * Returns the number of pixels of the image, the column count times the row count of the backing OpenCV matrix.
   *
   * @return the width multiplied by the height
   * @throws IllegalStateException if the image was released
   * @since 1.0.0
   */
  @Override
  public int getPixelCount() {
    this.checkNotReleased();

    final int width = this.mat.cols();
    final int height = this.mat.rows();
    return width * height;
  }

  /**
   * Returns every pixel as packed ARGB integers, laid out row by row, without copying them. The array is converted from the backing OpenCV
   * matrix and cached until the matrix is replaced or {@link #invalidateCache()} is called.
   *
   * <p><strong>The array is shared and must be treated as read-only.</strong> It is computed once and cached, and
   * every caller receives the same instance until the image changes. Writing into it does not change the image
   * itself, but it silently changes what every later caller of this method sees, and frame sources that replay the
   * same image, such as {@link me.brandonli.mcav.media.source.frame.RepeatingFrameSource}, then show the damage on
   * every later frame. The array is also replaced, not updated, when the image changes, so a caller that keeps it
   * holds the old pixels.
   *
   * <p>This method is meant for the per-frame path, where copying every frame would be too slow. Call
   * {@link #copyPixels()} for an array you may modify or keep, and {@link #getReadOnlyPixels()} for a view that
   * rejects writes without copying.
   *
   * @return the shared pixels of the image, which must not be modified
   * @throws IllegalStateException if the image was released
   * @since 1.0.0
   */
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

  // Identifies the pixel memory of a matrix. The address of the pixel data is used rather than the address of the
  // matrix header, because OpenCV functions reallocate the pixels of a matrix while its header stays in place.
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
      final int blue = raw[byteIndex] & CHANNEL_MASK;
      final int green = raw[byteIndex + GREEN_OFFSET] & CHANNEL_MASK;
      final int red = raw[byteIndex + RED_OFFSET] & CHANNEL_MASK;
      pixels[pixelIndex] = ALPHA_OPAQUE | (red << RED_SHIFT) | (green << GREEN_SHIFT) | blue;
      byteIndex += CHANNELS;
    }

    return pixels;
  }

  /**
   * Returns a view of the raw 8-bit BGR pixels of the backing OpenCV matrix, without padding. The pixels are the bytes between the position
   * and the limit of the returned buffer. The buffer shares memory with the image: changes to it change the image, and it becomes invalid
   * when the image is resized, transformed or released.
   *
   * @return a direct buffer over the pixels
   * @throws IllegalStateException if the image was released
   * @since 1.0.0
   */
  @Override
  public ByteBuffer getData() {
    this.checkNotReleased();

    return this.mat.createBuffer();
  }

  /**
   * Replaces the pixels of the backing OpenCV matrix with raw 8-bit BGR pixels, reusing its native memory when the dimensions did not
   * change. The bytes are copied from the current position of the buffer to its limit. Dimensions must be positive and their packed BGR
   * representation must fit within {@link Integer#MAX_VALUE} bytes. The source position and limit are unchanged.
   *
   * @param data   the pixels, exactly {@code width * height * 3} bytes remaining
   * @param width  the new width in pixels
   * @param height the new height in pixels
   * @throws IllegalArgumentException if dimensions are nonpositive, the packed BGR size exceeds
   *                                  {@link Integer#MAX_VALUE}, or the input count differs from the dimensions
   * @throws IllegalStateException    if the image was released
   * @throws NullPointerException     if {@code data} is null
   * @since 1.0.0
   */
  @Override
  public void updateData(final ByteBuffer data, final int width, final int height) {
    Preconditions.checkNotNull(data, "Data must not be null!");

    checkDimensions(width, height);
    this.checkNotReleased();

    final int expected = width * height * CHANNELS;
    final int remaining = data.remaining();
    Preconditions.checkArgument(remaining == expected, BYTE_COUNT_MESSAGE, remaining, expected);

    this.ensureSize(width, height);
    final ByteBuffer source = data.duplicate();
    final ByteBuffer target = this.mat.createBuffer();
    target.put(source);
    this.invalidateCache();
  }

  /**
   * Replaces the pixels of the backing OpenCV matrix with packed ARGB pixels, reusing its native memory when the dimensions did not change.
   * The alpha channel is discarded. Dimensions must be positive and the resulting packed BGR representation must fit within {@link
   * Integer#MAX_VALUE} bytes.
   *
   * @param pixels the pixels, exactly {@code width * height} integers laid out row by row
   * @param width  the new width in pixels
   * @param height the new height in pixels
   * @throws IllegalArgumentException if dimensions are nonpositive, the packed BGR size exceeds
   *                                  {@link Integer#MAX_VALUE}, or the input count differs from the dimensions
   * @throws IllegalStateException    if the image was released
   * @throws NullPointerException     if {@code pixels} is null
   * @since 1.0.0
   */
  @Override
  public void updateArgb(final int[] pixels, final int width, final int height) {
    Preconditions.checkNotNull(pixels, "Pixels must not be null!");

    checkDimensions(width, height);
    this.checkNotReleased();

    final int expected = width * height;
    Preconditions.checkArgument(pixels.length == expected, PIXEL_COUNT_MESSAGE, pixels.length, expected);

    this.ensureSize(width, height);
    final byte[] bytes = this.getScratch(expected * CHANNELS);
    writeArgb(this.mat, pixels, bytes);
    this.invalidateCache();
  }

  /**
   * Discards the ARGB pixel array cached from the backing OpenCV matrix, so the next call to {@link #getPixels()} converts the image again.
   * Video filters call this after modifying the image through {@link #getData()} or through OpenCV directly.
   *
   * @since 1.0.0
   */
  @Override
  public void invalidateCache() {
    this.cachedPixels = null;
  }

  /**
   * Creates a deep copy of this image by cloning its OpenCV matrix, so the copy shares no native memory with it.
   *
   * @return a new image buffer with the same pixels
   * @throws IllegalStateException if the image was released
   * @since 1.0.0
   */
  @Override
  public ImageBuffer copy() {
    this.checkNotReleased();

    final Mat clone = this.mat.clone();
    return new MatImageBuffer(clone);
  }

  /**
   * Releases the backing OpenCV matrix and its spare buffer. Calling this method more than once has no effect, and every other operation
   * accessing native pixels or dimensions throws {@link IllegalStateException} afterward. {@link #invalidateCache()} remains harmless.
   * Previously returned native views must no longer be used.
   *
   * @since 1.0.0
   */
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
      throw new IllegalStateException("Image buffer has been released!");
    }
  }
}
