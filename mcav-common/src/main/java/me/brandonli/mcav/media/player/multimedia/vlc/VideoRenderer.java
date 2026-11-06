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

// Copies the visible pixels in display, because VLC reuses its buffer, and renders only the newest frame on a render thread.
final class VideoRenderer implements RenderCallback {

  private static final String THREAD_NAME = "mcav-vlc-render-video";

  private static final int BYTES_PER_PIXEL = 4;

  private static final int PENDING_FRAME_CAPACITY = 1;
  private static final int SPARE_PIXEL_ARRAYS = 2;

  private static final long QUEUE_POLL_MILLISECONDS = 50L;

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

  // Frames are dropped until start() is called.
  VideoRenderer(final VideoPlayerMultiplexer owner) {
    this.owner = owner;
    this.pendingFrame = new ArrayBlockingQueue<>(PENDING_FRAME_CAPACITY);
    this.sparePixels = new ArrayBlockingQueue<>(SPARE_PIXEL_ARRAYS);
    this.replacedFrames = new ArrayList<>(PENDING_FRAME_CAPACITY);
    this.thread = new RenderThread(THREAD_NAME, failure -> reportFailure(owner, "Video rendering failed.", failure));
    this.metadata = OriginalVideoMetadata.EMPTY;
  }

  private static void reportFailure(final VideoPlayerMultiplexer owner, final String message, final Throwable failure) {
    final BiConsumer<String, Throwable> handler = owner.getExceptionHandler();
    handler.accept(message, failure);
  }

  void start() {
    this.thread.start(this::renderNext, this::releaseRenderBuffer);
  }

  void stop() {
    this.thread.stop();
    this.pendingFrame.clear();
  }

  // The buffer has the decoded size, padding included, unless a size is attached, which VLC then scales the picture to.
  BufferFormat createBufferFormat(final int sourceWidth, final int sourceHeight, final int visibleWidth, final int visibleHeight) {
    this.metadata = OriginalVideoMetadata.of(visibleWidth, visibleHeight);
    final DimensionAttachableCallback dimensionCallback = this.owner.getDimensionAttachableCallback();
    // read the size once: a detach between two calls answers with the empty fallback, which would hand VLC a 0x0 buffer format
    final Dimension target = dimensionCallback.retrieve();
    final boolean shouldScale = !target.isEmpty();
    if (!shouldScale) {
      this.width = visibleWidth;
      this.height = visibleHeight;

      return new RV32BufferFormat(sourceWidth, sourceHeight);
    }

    final int targetWidth = target.getWidth();
    final int targetHeight = target.getHeight();
    this.width = targetWidth;
    this.height = targetHeight;

    return new RV32BufferFormat(targetWidth, targetHeight);
  }

  // Sets whether playback is paused. While paused, frames are dropped, and pausing also drops the queued frame.
  void setPaused(final boolean paused) {
    this.paused = paused;

    if (paused) {
      this.pendingFrame.clear();
    }
  }

  // Does nothing, because the buffer is copied inside display and needs no locking.
  @Override
  public void lock(final MediaPlayer mediaPlayer) {}

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
    final boolean empty = step.isNoOperation();
    if (empty) {
      return;
    }

    final DecodedFrame decoded = this.copyVisiblePixels(nativeBuffers[0], bufferFormat);
    if (decoded == null) {
      return;
    }

    this.offerLatest(decoded);
  }

  // Does nothing, because lock locks nothing.
  @Override
  public void unlock(final MediaPlayer mediaPlayer) {}

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
    this.readVisibleRows(nativeBuffer, bufferWidth, visibleWidth, visibleHeight, pixels);
    return new DecodedFrame(pixels, visibleWidth, visibleHeight);
  }

  // RV32 stores blue, green, red and padding, which little-endian integers read as packed ARGB, the padding in the discarded alpha.
  @VisibleForTesting
  void readVisibleRows(
    final ByteBuffer nativeBuffer,
    final int bufferWidth,
    final int visibleWidth,
    final int visibleHeight,
    final int[] pixels
  ) {
    final ByteBuffer view = nativeBuffer.duplicate();
    view.order(ByteOrder.LITTLE_ENDIAN);
    view.rewind();
    final IntBuffer packedPixels = view.asIntBuffer();
    if (bufferWidth == visibleWidth) {
      packedPixels.get(pixels, 0, visibleWidth * visibleHeight);
      return;
    }

    for (int row = 0; row < visibleHeight; row++) {
      packedPixels.position(row * bufferWidth);
      packedPixels.get(pixels, row * visibleWidth, visibleWidth);
    }
  }

  @VisibleForTesting
  int[] acquirePixels(final int length) {
    final int[] spare = this.sparePixels.poll();
    if (spare != null && spare.length == length) {
      return spare;
    }

    return new int[length];
  }

  // Keeps a copied pixel array for VLC's thread to reuse, but only as many as can be in flight; the rest go to the garbage collector.
  @VisibleForTesting
  boolean recyclePixels(final int[] pixels) {
    return this.sparePixels.offer(pixels);
  }

  private void offerLatest(final DecodedFrame frame) {
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
    final DecodedFrame frame = this.pendingFrame.poll(QUEUE_POLL_MILLISECONDS, TimeUnit.MILLISECONDS);
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

  private void scaleIfRequested(final ImageBuffer buffer) {
    final DimensionAttachableCallback dimensionCallback = this.owner.getDimensionAttachableCallback();
    // read the size once: a detach between two calls answers with the empty fallback, and a 0x0 resize filter rejects every frame
    final Dimension dimension = dimensionCallback.retrieve();
    final boolean empty = dimension.isEmpty();
    if (empty) {
      return;
    }

    final int targetWidth = dimension.getWidth();
    final int targetHeight = dimension.getHeight();
    final ResizeFilter resize = this.obtainResizeFilter(targetWidth, targetHeight);
    resize.applyFilter(buffer);
  }

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
      // filters are user code that may throw a LinkageError; only a virtual machine error, which a report would hide, ends the thread
      ThrowableUtils.throwIfFatal(exception);
      reportFailure(this.owner, "Video filter failed.", exception);
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
