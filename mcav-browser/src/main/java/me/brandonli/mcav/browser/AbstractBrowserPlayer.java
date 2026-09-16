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

import com.google.common.base.Preconditions;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The parts shared by the browser backends: lifecycle state, the video pipeline slot, frame decoding and
 * delivery, exception reporting, and the translation of input coordinates from frame space to page space.
 *
 * <p>Subclasses implement {@link #open(BrowserSource)}, {@link #close()}, and the input methods, call
 * {@link #deliverFrame(byte[], int, int)} for every screencast frame they receive, and call
 * {@link #fail(String, Throwable)} when the browser is lost.
 */
public abstract class AbstractBrowserPlayer implements BrowserPlayer {

  // frames are taken while the page opens, so the first frame of a page that never changes again is not lost
  private static final Set<State> ACTIVE = EnumSet.of(State.OPENING, State.PLAYING);

  private final VideoAttachableCallback videoCallback;
  private final ExceptionHandler exceptionHandler;
  private final Lock lock;
  private final AtomicReference<State> state;
  private final AtomicBoolean released;

  private volatile @Nullable BrowserSource source;
  private volatile @Nullable ResizeFilter resizeFilter;
  private volatile @Nullable OriginalVideoMetadata metadata;
  private volatile int pageWidth;
  private volatile int pageHeight;

  /**
   * Constructs a player.
   */
  protected AbstractBrowserPlayer() {
    this.videoCallback = VideoAttachableCallback.create();
    this.exceptionHandler = ExceptionHandler.createDefault();
    this.lock = new ReentrantLock();
    this.state = new AtomicReference<>(State.IDLE);
    this.released = new AtomicBoolean(false);
  }

  /**
   * Opens the page and starts the screencast. Called under the player lock, with the player not playing. Frames
   * delivered while the page opens are streamed, but input is ignored until the method returns.
   *
   * @param source the page and the screencast settings
   */
  protected abstract void open(final BrowserSource source);

  /**
   * Stops the screencast and closes the browser. Called under the player lock after playback stopped: on release,
   * and before a player whose browser failed is started again.
   */
  protected abstract void close();

  /**
   * Opens a page and starts streaming it. A player whose browser failed can be started again; the failed browser is
   * closed first.
   *
   * @param source the page and the screencast settings
   * @return true if streaming started, false if the player is already playing or released
   * @throws me.brandonli.mcav.media.player.PlayerException if the browser cannot be started
   */
  @Override
  public final boolean start(final BrowserSource source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    this.lock.lock();
    try {
      final boolean startable = this.prepareStart();
      if (!startable) {
        return false;
      }

      this.useSource(source);
      this.openPage(source);
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Checks whether the player can start, and closes the browser of a player whose browser failed.
   *
   * @return true if the player is neither released nor playing
   */
  private boolean prepareStart() {
    final boolean gone = this.released.get();
    if (gone) {
      return false;
    }

    final State current = this.state.get();
    if (current == State.PLAYING) {
      return false;
    }

    if (current == State.FAILED) {
      this.close();
    }
    return true;
  }

  /**
   * Records the source and creates the frame scaling for its size.
   *
   * @param source the page and the screencast settings
   */
  private void useSource(final BrowserSource source) {
    final int width = source.getScreencastWidth();
    final int height = source.getScreencastHeight();
    this.source = source;
    this.pageWidth = width;
    this.pageHeight = height;
    this.resizeFilter = new ResizeFilter(width, height);
    this.metadata = OriginalVideoMetadata.of(width, height);
  }

  /**
   * Opens the page, leaving the player idle if that fails and playing if it succeeds.
   *
   * @param source the page and the screencast settings
   */
  private void openPage(final BrowserSource source) {
    this.state.set(State.OPENING);
    try {
      this.open(source);
    } catch (final RuntimeException | Error exception) {
      // Selenium and Playwright are third-party code that can also fail with an Error, such as a LinkageError of a
      // missing native driver; the state is reset for every failure, which is rethrown unchanged, so none is hidden
      this.state.set(State.IDLE);
      throw exception;
    }

    // a browser that failed while the page opened stays failed
    this.state.compareAndSet(State.OPENING, State.PLAYING);
  }

  /**
   * Stops streaming and closes the browser for good. A released player cannot be started again.
   *
   * @return true if this call released the player, false if it was already released
   */
  @Override
  public final boolean release() {
    this.lock.lock();
    try {
      final boolean first = this.released.compareAndSet(false, true);
      if (!first) {
        return false;
      }
      this.state.set(State.IDLE);
      this.close();
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Checks whether a page is being streamed. A player stops playing when it is released or its browser is lost.
   *
   * @return true between a successful {@link #start(BrowserSource)} and {@link #release()} or a browser failure
   */
  @Override
  public final boolean isPlaying() {
    final State current = this.state.get();
    return current == State.PLAYING;
  }

  /**
   * Checks whether input can be forwarded, which is only the case while the player is playing.
   *
   * @return true if the player is playing
   */
  protected final boolean canForwardInput() {
    return this.isPlaying();
  }

  /**
   * Marks the browser as lost, so the player stops playing and can be started again, and reports the failure.
   * Failures after the player stopped are not reported, as they are caused by the shutdown.
   *
   * @param message what failed
   * @param error   the failure
   */
  protected final void fail(final String message, final Throwable error) {
    Preconditions.checkNotNull(message, "Message must not be null");
    Preconditions.checkNotNull(error, "Error must not be null");
    final State previous = this.state.getAndUpdate(AbstractBrowserPlayer::toFailed);
    final boolean wasActive = ACTIVE.contains(previous);
    if (wasActive) {
      this.report(message, error);
    }
  }

  private static State toFailed(final State current) {
    final boolean active = ACTIVE.contains(current);
    return active ? State.FAILED : current;
  }

  /**
   * Gets the source that is being streamed.
   *
   * @return the source
   * @throws IllegalStateException if the player was not started
   */
  protected final BrowserSource getSource() {
    final BrowserSource current = this.source;
    if (current == null) {
      throw new IllegalStateException("The player has not been started");
    }
    return current;
  }

  /**
   * Records the size of the page viewport in CSS pixels, which input coordinates are translated to.
   *
   * @param width  the viewport width
   * @param height the viewport height
   */
  protected final void setPageSize(final int width, final int height) {
    if (width > 0 && height > 0) {
      this.pageWidth = width;
      this.pageHeight = height;
    }
  }

  /**
   * Translates a coordinate from frame space to page space.
   *
   * @param x the x coordinate in the streamed frame
   * @param y the y coordinate in the streamed frame
   * @return the x and y coordinates on the page, clamped to the viewport
   */
  protected final int[] translateCoordinates(final int x, final int y) {
    final BrowserSource current = this.getSource();
    final int frameWidth = current.getScreencastWidth();
    final int frameHeight = current.getScreencastHeight();
    final int targetWidth = this.pageWidth;
    final int targetHeight = this.pageHeight;
    final double widthRatio = (double) targetWidth / frameWidth;
    final double heightRatio = (double) targetHeight / frameHeight;
    final int scaledX = (int) Math.round(x * widthRatio);
    final int scaledY = (int) Math.round(y * heightRatio);
    final int maximumX = Math.max(0, targetWidth - 1);
    final int maximumY = Math.max(0, targetHeight - 1);
    final int clampedX = Math.clamp(scaledX, 0, maximumX);
    final int clampedY = Math.clamp(scaledY, 0, maximumY);
    return new int[] { clampedX, clampedY };
  }

  /**
   * Decodes an encoded frame, scales it to the size of the source, and runs the video pipeline on it. Frames are
   * taken from the moment the page starts to open until the player stops. Failures are reported to the exception
   * handler and do not stop the screencast, except errors of the virtual machine, such as running out of memory,
   * which are thrown.
   *
   * @param encoded    the frame as a JPEG or PNG image
   * @param pageWidth  the viewport width the frame was captured at, or 0 if unknown
   * @param pageHeight the viewport height the frame was captured at, or 0 if unknown
   */
  protected final void deliverFrame(final byte[] encoded, final int pageWidth, final int pageHeight) {
    Preconditions.checkNotNull(encoded, "Encoded frame must not be null");
    final State current = this.state.get();
    final boolean active = ACTIVE.contains(current);
    if (!active) {
      return;
    }

    this.setPageSize(pageWidth, pageHeight);
    // both are created by start before the page opens
    final ResizeFilter resize = Objects.requireNonNull(this.resizeFilter, "The resize filter is created by start");
    final OriginalVideoMetadata frameMetadata = Objects.requireNonNull(this.metadata, "The metadata is created by start");
    try (final ImageBuffer image = ImageBuffer.bytes(encoded)) {
      resize.applyFilter(image, frameMetadata);
      final VideoPipelineStep pipeline = this.videoCallback.retrieve();
      pipeline.processAll(image, frameMetadata);
    } catch (final RuntimeException | Error exception) {
      // a frame runs user filters and native OpenCV code, which can fail with any Error, such as an AssertionError or
      // a LinkageError; only errors of the virtual machine are thrown, every other failure is reported
      ThrowableUtils.throwIfFatal(exception);
      this.report("Failed to process a browser frame", exception);
    }
  }

  /**
   * Reports a failure to the exception handler.
   *
   * @param message what failed
   * @param error   the failure
   */
  protected final void report(final String message, final Throwable error) {
    Preconditions.checkNotNull(message, "Message must not be null");
    Preconditions.checkNotNull(error, "Error must not be null");
    final BiConsumer<String, Throwable> handler = this.exceptionHandler.getExceptionHandler();
    handler.accept(message, error);
  }

  /**
   * Validates the arguments of {@link #sendMouseEvent(MouseClick, int, int)}.
   *
   * @param type the kind of click
   */
  protected static void checkMouseClick(final MouseClick type) {
    Preconditions.checkNotNull(type, "Mouse click type must not be null");
  }

  /**
   * Gets the slot that holds the video pipeline every decoded frame is sent through, scaled to the size of the
   * source.
   *
   * @return the video pipeline slot
   */
  @Override
  public final VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoCallback;
  }

  /**
   * Gets the handler that receives failures of the browser, of frame decoding, and of input forwarding.
   *
   * @return the exception handler
   */
  @Override
  public final BiConsumer<String, Throwable> getExceptionHandler() {
    return this.exceptionHandler.getExceptionHandler();
  }

  /**
   * Replaces the handler that receives failures of the browser, of frame decoding, and of input forwarding.
   *
   * @param exceptionHandler the new handler, called with a description of what failed and the failure
   */
  @Override
  public final void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    Preconditions.checkNotNull(exceptionHandler, "Exception handler must not be null");
    this.exceptionHandler.setExceptionHandler(exceptionHandler);
  }

  /**
   * The lifecycle of the browser.
   */
  private enum State {
    /**
     * No browser is open, because the player was never started, failed to start, or was released.
     */
    IDLE,
    /**
     * The page is opening; frames are streamed but input is ignored.
     */
    OPENING,
    /**
     * The page is streamed and receives input.
     */
    PLAYING,
    /**
     * The browser was lost; it is closed when the player starts again or is released.
     */
    FAILED,
  }
}
