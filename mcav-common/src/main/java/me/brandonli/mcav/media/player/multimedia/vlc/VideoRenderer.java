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
package me.brandonli.mcav.media.player.multimedia.vlc;

import com.google.common.annotations.VisibleForTesting;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

/**
 * Copies the pictures VLC renders and runs the video pipeline of a player on a render thread.
 *
 * <p>VLC renders into an RV32 buffer and calls {@link #display} on its video output thread. The visible pixels are
 * copied there, because VLC reuses the buffer right away, and handed to the render thread, which keeps only the
 * newest unrendered frame. When a size is attached to the player, VLC is asked to render at that size, so it scales
 * the picture while converting it; frames that still arrive at another size, because the size was attached after VLC
 * chose the buffer, are resized on the render thread. The pixel arrays and the image buffer are reused from frame to
 * frame, so rendering allocates nothing per frame once the sizes are settled.
 */
final class VideoRenderer implements RenderCallback {

  private static final int BYTES_PER_PIXEL = 4;
  private static final int SPARE_PIXEL_ARRAYS = 2;
  private static final long QUEUE_POLL_MILLIS = 50L;

  private final VideoPlayerMultiplexer owner;
  private final BlockingQueue<DecodedFrame> pendingFrame;
  private final BlockingQueue<int[]> sparePixels;
  private final List<DecodedFrame> replacedFrames;
  private final RenderThread thread;

  private @Nullable ImageBuffer renderBuffer;
  private @Nullable ResizeFilter resizeFilter;
  private volatile boolean paused;
  private volatile int width;
  private volatile int height;
  private volatile OriginalVideoMetadata metadata;

  /**
   * Constructs a new renderer. Frames are dropped until {@link #start()} is called.
   *
   * @param owner the player that provides the video pipeline, the target size, and the exception handler
   */
  VideoRenderer(final VideoPlayerMultiplexer owner) {
    this.owner = owner;
    this.pendingFrame = new ArrayBlockingQueue<>(1);
    this.sparePixels = new ArrayBlockingQueue<>(SPARE_PIXEL_ARRAYS);
    // only the video output thread of VLC offers frames, so it can reuse one list for the frames it replaces
    this.replacedFrames = new ArrayList<>(1);
    this.thread = new RenderThread("mcav-vlc-render-video", failure -> reportFailure(owner, "Video rendering failed", failure));
    this.metadata = OriginalVideoMetadata.EMPTY;
  }

  private static void reportFailure(final VideoPlayerMultiplexer owner, final String message, final Throwable failure) {
    final BiConsumer<String, Throwable> handler = owner.getExceptionHandler();
    handler.accept(message, failure);
  }

  /**
   * Starts the render thread.
   */
  void start() {
    this.thread.start(this::renderNext, this::releaseRenderBuffer);
  }

  /**
   * Stops the render thread, waits for it to exit, and drops the queued frame.
   */
  void stop() {
    this.thread.stop();
    this.pendingFrame.clear();
  }

  /**
   * Chooses the buffer VLC renders into and remembers which part of it holds the picture. Without an attached size,
   * the buffer has the size VLC decodes at, which may include padding below and right of the visible picture. With
   * an attached size, VLC scales the visible picture into a buffer of exactly that size.
   *
   * @param sourceWidth   the width of the pictures VLC decodes, including padding
   * @param sourceHeight  the height of the pictures VLC decodes, including padding
   * @param visibleWidth  the width of the visible picture, at most the source width
   * @param visibleHeight the height of the visible picture, at most the source height
   * @return the RV32 buffer format VLC renders into
   */
  BufferFormat createBufferFormat(final int sourceWidth, final int sourceHeight, final int visibleWidth, final int visibleHeight) {
    this.metadata = OriginalVideoMetadata.of(visibleWidth, visibleHeight);
    final DimensionAttachableCallback dimensionCallback = this.owner.getDimensionAttachableCallback();
    final boolean scale = dimensionCallback.isAttached();
    if (!scale) {
      this.width = visibleWidth;
      this.height = visibleHeight;
      return new RV32BufferFormat(sourceWidth, sourceHeight);
    }
    final Dimension target = dimensionCallback.retrieve();
    final int targetWidth = target.getWidth();
    final int targetHeight = target.getHeight();
    this.width = targetWidth;
    this.height = targetHeight;
    return new RV32BufferFormat(targetWidth, targetHeight);
  }

  /**
   * Sets whether playback is paused. While paused, frames are dropped, and pausing also drops the queued frame.
   *
   * @param paused whether playback is paused
   */
  void setPaused(final boolean paused) {
    this.paused = paused;
    if (paused) {
      this.pendingFrame.clear();
    }
  }

  /**
   * Does nothing, because the buffer is copied inside {@link #display} and needs no locking.
   *
   * @param mediaPlayer the player that renders
   */
  @Override
  public void lock(final MediaPlayer mediaPlayer) {
    // the frame buffer is copied inside display, no locking is needed
  }

  /**
   * Copies the visible part of a finished picture and queues it for the render thread, replacing a frame that has
   * not been rendered yet.
   *
   * @param mediaPlayer   the player that renders
   * @param nativeBuffers the planes of the picture; RV32 has one
   * @param bufferFormat  the format of the buffers
   * @param displayWidth  the display width reported by VLC
   * @param displayHeight the display height reported by VLC
   */
  @Override
  public void display(
    final MediaPlayer mediaPlayer,
    final ByteBuffer[] nativeBuffers,
    final BufferFormat bufferFormat,
    final int displayWidth,
    final int displayHeight
  ) {
    final boolean accepting = this.thread.isRunning() && !this.paused && nativeBuffers.length > 0;
    if (!accepting) {
      return;
    }
    final VideoAttachableCallback callback = this.owner.getVideoAttachableCallback();
    final VideoPipelineStep step = callback.retrieve();
    final boolean empty = step.isNoOp();
    if (empty) {
      return;
    }
    final DecodedFrame decoded = this.copyVisiblePixels(nativeBuffers[0], bufferFormat);
    if (decoded == null) {
      return;
    }
    this.offerLatest(decoded);
  }

  /**
   * Does nothing, because {@link #lock} locks nothing.
   *
   * @param mediaPlayer the player that renders
   */
  @Override
  public void unlock(final MediaPlayer mediaPlayer) {
    // nothing to unlock
  }

  private @Nullable DecodedFrame copyVisiblePixels(final ByteBuffer nativeBuffer, final BufferFormat bufferFormat) {
    final int bufferWidth = bufferFormat.getWidth();
    final int bufferHeight = bufferFormat.getHeight();
    final int visibleWidth = Math.min(this.width, bufferWidth);
    final int visibleHeight = Math.min(this.height, bufferHeight);
    final int availablePixels = nativeBuffer.capacity() / BYTES_PER_PIXEL;
    final boolean complete = Math.min(visibleWidth, visibleHeight) > 0 && availablePixels >= bufferWidth * visibleHeight;
    if (!complete) {
      return null;
    }
    final int[] pixels = this.acquirePixels(visibleWidth * visibleHeight);
    readVisibleRows(nativeBuffer, bufferWidth, visibleWidth, visibleHeight, pixels);
    return new DecodedFrame(pixels, visibleWidth, visibleHeight);
  }

  /**
   * Reads the visible rows of an RV32 buffer as packed ARGB pixels. RV32 is stored as blue, green, red, and a padding
   * byte, which read as little-endian integers is exactly packed ARGB; the padding byte ends up in the alpha channel,
   * which images discard.
   *
   * @param nativeBuffer  the buffer VLC rendered into, rows of {@code bufferWidth} pixels
   * @param bufferWidth   the width of the buffer in pixels
   * @param visibleWidth  the number of pixels to read from each row, at most {@code bufferWidth}
   * @param visibleHeight the number of rows to read
   * @param pixels        receives the visible pixels, laid out row by row without padding; it must hold at least
   *                      {@code visibleWidth * visibleHeight} integers
   */
  @VisibleForTesting
  static void readVisibleRows(
    final ByteBuffer nativeBuffer,
    final int bufferWidth,
    final int visibleWidth,
    final int visibleHeight,
    final int[] pixels
  ) {
    final ByteBuffer view = nativeBuffer.duplicate();
    view.order(ByteOrder.LITTLE_ENDIAN);
    view.rewind();
    final IntBuffer ints = view.asIntBuffer();
    if (bufferWidth == visibleWidth) {
      ints.get(pixels, 0, visibleWidth * visibleHeight);
      return;
    }
    for (int row = 0; row < visibleHeight; row++) {
      ints.position(row * bufferWidth);
      ints.get(pixels, row * visibleWidth, visibleWidth);
    }
  }

  /**
   * Takes a pixel array of the given length from the spare arrays the render thread handed back, or creates one.
   *
   * @param length the number of pixels
   * @return an array of exactly that length, whose content is undefined
   */
  @VisibleForTesting
  int[] acquirePixels(final int length) {
    final int[] spare = this.sparePixels.poll();
    if (spare != null && spare.length == length) {
      return spare;
    }
    return new int[length];
  }

  /**
   * Hands a pixel array back once its pixels were copied into the image, so VLC's thread can reuse it. Only as many
   * arrays are kept as can be in flight at once; an array handed back while that many are already waiting is dropped
   * and left to the garbage collector, because a later frame would never need it.
   *
   * @param pixels the array, which the caller must not use afterward
   * @return true if the array was kept for reuse, false if it was dropped because enough spare arrays are waiting
   */
  @VisibleForTesting
  boolean recyclePixels(final int[] pixels) {
    return this.sparePixels.offer(pixels);
  }

  private void offerLatest(final DecodedFrame frame) {
    // only the newest frame matters, so an unrendered older one is replaced
    while (!this.pendingFrame.offer(frame)) {
      this.pendingFrame.drainTo(this.replacedFrames);
      for (final DecodedFrame replaced : this.replacedFrames) {
        final int[] pixels = replaced.getPixels();
        this.recyclePixels(pixels);
      }
      this.replacedFrames.clear();
    }
  }

  private void renderNext() throws InterruptedException {
    final DecodedFrame frame = this.pendingFrame.poll(QUEUE_POLL_MILLIS, TimeUnit.MILLISECONDS);
    if (frame != null) {
      this.render(frame);
    }
  }

  private void render(final DecodedFrame frame) {
    final ImageBuffer buffer;
    try {
      buffer = this.fillRenderBuffer(frame);
    } finally {
      final int[] pixels = frame.getPixels();
      this.recyclePixels(pixels);
    }
    this.scaleIfRequested(buffer);
    this.runPipeline(buffer);
  }

  private ImageBuffer fillRenderBuffer(final DecodedFrame frame) {
    final int frameWidth = frame.getWidth();
    final int frameHeight = frame.getHeight();
    final int[] pixels = frame.getPixels();
    final ImageBuffer existing = this.renderBuffer;
    if (existing != null) {
      existing.updateArgb(pixels, frameWidth, frameHeight);
      return existing;
    }
    final ImageBuffer created = ImageBuffer.buffer(pixels, frameWidth, frameHeight);
    this.renderBuffer = created;
    return created;
  }

  /**
   * Resizes frames that VLC did not render at the attached size, which happens when the size was attached or changed
   * after VLC chose its buffer.
   */
  private void scaleIfRequested(final ImageBuffer buffer) {
    final DimensionAttachableCallback dimensionCallback = this.owner.getDimensionAttachableCallback();
    final boolean scale = dimensionCallback.isAttached();
    if (!scale) {
      return;
    }
    final Dimension dimension = dimensionCallback.retrieve();
    final int targetWidth = dimension.getWidth();
    final int targetHeight = dimension.getHeight();
    final ResizeFilter resize = this.obtainResizeFilter(targetWidth, targetHeight);
    resize.applyFilter(buffer);
  }

  /**
   * Gets a resize filter for the target size, reusing the filter of the previous frame while the size stays the same.
   */
  private ResizeFilter obtainResizeFilter(final int targetWidth, final int targetHeight) {
    final ResizeFilter existing = this.resizeFilter;
    if (existing != null && existing.matches(targetWidth, targetHeight)) {
      return existing;
    }

    final ResizeFilter created = new ResizeFilter(targetWidth, targetHeight);
    this.resizeFilter = created;
    return created;
  }

  private void runPipeline(final ImageBuffer buffer) {
    final OriginalVideoMetadata currentMetadata = this.metadata;
    final VideoAttachableCallback callback = this.owner.getVideoAttachableCallback();
    try {
      final VideoPipelineStep pipeline = callback.retrieve();
      pipeline.processAll(buffer, currentMetadata);
    } catch (final RuntimeException | Error exception) {
      // filters are user code, and native filters can throw a LinkageError, so any failure is reported and the render
      // thread keeps rendering; only a virtual machine error ends the thread, because reporting it would hide it
      ThrowableUtils.throwIfFatal(exception);
      reportFailure(this.owner, "Video filter failed", exception);
    }
  }

  private void releaseRenderBuffer() {
    final ImageBuffer buffer = this.renderBuffer;
    this.renderBuffer = null;
    if (buffer != null) {
      buffer.release();
    }
  }
}
