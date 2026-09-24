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
package me.brandonli.mcav.vnc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.shinyhut.vernacular.client.VernacularClient;
import com.shinyhut.vernacular.client.VernacularConfig;
import com.shinyhut.vernacular.client.exceptions.VncException;
import com.shinyhut.vernacular.client.rendering.ColorDepth;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.interaction.MouseClick;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link VNCPlayer}, built on the Vernacular VNC client.
 *
 * <p>The client delivers screen updates on its own thread; the newest one is handed to a render thread which
 * scales it and runs the pipeline, so slow filters never block the protocol. Input is translated from frame
 * coordinates to the coordinates of the remote screen, whose size is learned from the first update.
 *
 * <p>When the connection fails while streaming, the failure is reported, the player stops playing, and it can be
 * started again.
 */
public final class VNCPlayerImpl implements VNCPlayer {

  private static final int LEFT_BUTTON = 1;
  private static final int RIGHT_BUTTON = 3;
  private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
  private static final long STOP_TIMEOUT_MILLIS = 2_000L;
  private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

  private final Function<VernacularConfig, VernacularClient> clientFactory;
  private final Supplier<Socket> socketFactory;
  private final VideoAttachableCallback videoCallback;
  private final ExceptionHandler exceptionHandler;
  private final Lock lock;
  private final Object frameLock;
  private final long idleParkNanos;
  private final AtomicBoolean paused;
  private final AtomicBoolean released;
  private final AtomicReference<@Nullable BufferedImage> latestFrame;

  private volatile @Nullable Session session;
  // volatile like its siblings session and source: written under the lock, but read without it by
  // getConnectedClient() on the input path
  private volatile @Nullable VernacularClient client;
  private @Nullable Thread renderThread;
  private volatile @Nullable VNCSource source;
  private volatile int remoteWidth;
  private volatile int remoteHeight;

  VNCPlayerImpl() {
    this(VernacularClient::new, Socket::new);
  }

  /**
   * Constructs a player that creates its VNC clients and sockets with the given factories.
   *
   * @param clientFactory creates the client of a session from its configuration
   * @param socketFactory creates the unconnected socket of a session
   */
  @VisibleForTesting
  VNCPlayerImpl(final Function<VernacularConfig, VernacularClient> clientFactory, final Supplier<Socket> socketFactory) {
    this(clientFactory, socketFactory, IDLE_PARK_NANOS);
  }

  /**
   * Constructs a player with a replaceable idle wait so wake-up behavior can be tested without timing races.
   *
   * @param clientFactory creates the client of a session
   * @param socketFactory creates its socket
   * @param idleParkNanos how long an idle renderer waits unless a frame or shutdown wakes it first
   */
  @VisibleForTesting
  VNCPlayerImpl(
    final Function<VernacularConfig, VernacularClient> clientFactory,
    final Supplier<Socket> socketFactory,
    final long idleParkNanos
  ) {
    Preconditions.checkArgument(idleParkNanos > 0, "Idle wait must be positive");
    this.clientFactory = clientFactory;
    this.socketFactory = socketFactory;
    this.videoCallback = VideoAttachableCallback.create();
    this.exceptionHandler = ExceptionHandler.createDefault();
    this.lock = new ReentrantLock();
    this.frameLock = new Object();
    this.idleParkNanos = idleParkNanos;
    this.paused = new AtomicBoolean(false);
    this.released = new AtomicBoolean(false);
    this.latestFrame = new AtomicReference<>();
  }

  @Override
  public boolean start(final VNCSource source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    this.lock.lock();
    try {
      final boolean gone = this.released.get();
      final boolean active = this.isActive();
      if (gone || active) {
        return false;
      }

      this.resetForNewSession(source);
      this.launchSession(source);
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  private void resetForNewSession(final VNCSource source) {
    // the client and render thread of a session whose connection failed are still around
    this.stopClient();
    this.stopRenderThread();

    synchronized (this.frameLock) {
      this.session = null;
      this.source = source;
      this.remoteWidth = 0;
      this.remoteHeight = 0;
      this.paused.set(false);
      this.latestFrame.set(null);
    }
  }

  private void launchSession(final VNCSource source) {
    final Session created = new Session();

    // the thread exists before the session so screen updates can wake it; it starts once the session is up
    final Thread renderWorker = new Thread(() -> this.render(source, created), "mcav-vnc-render");
    renderWorker.setDaemon(true);

    // Publish the owner before the handshake, which can deliver the first screen update synchronously.
    synchronized (this.frameLock) {
      this.session = created;
    }
    try {
      this.client = this.openSession(source, created, renderWorker);
    } catch (final RuntimeException | Error exception) {
      synchronized (this.frameLock) {
        created.end();
        this.session = null;
        this.latestFrame.set(null);
      }
      throw exception;
    }
    this.renderThread = renderWorker;
    renderWorker.start();
  }

  private VernacularClient openSession(final VNCSource source, final Session created, final Thread renderWorker) {
    final VernacularConfig config = this.createConfig(source, created, renderWorker);
    final VernacularClient vncClient = this.clientFactory.apply(config);
    final Socket socket = this.connect(source);
    try {
      vncClient.start(socket);
    } catch (final RuntimeException exception) {
      closeQuietly(socket);
      final String message = exception.getMessage();
      throw new PlayerException("Failed to start the VNC session with " + source + ": " + message, exception);
    }

    // the client reports handshake failures, such as a rejected password, to the error listener and returns; a
    // failure that arrives after this point is a failure of the running session
    final VncException failure = created.finishStartup();
    if (failure != null) {
      vncClient.stop();
      closeQuietly(socket);
      final String message = failure.getMessage();
      throw new PlayerException("Failed to start the VNC session with " + source + ": " + message, failure);
    }
    return vncClient;
  }

  // the client connects on its own thread and would only report failures later, so the socket is opened here
  private Socket connect(final VNCSource source) {
    final String host = source.getHost();
    final int port = source.getPort();
    final InetSocketAddress address = new InetSocketAddress(host, port);
    final Socket socket = this.socketFactory.get();
    try {
      socket.connect(address, CONNECT_TIMEOUT_MILLIS);
      socket.setTcpNoDelay(true);
      return socket;
    } catch (final IOException exception) {
      closeQuietly(socket);
      final String message = exception.getMessage();
      throw new PlayerException("Failed to connect to " + source + ": " + message, exception);
    }
  }

  private static void closeQuietly(final Socket socket) {
    try {
      socket.close();
    } catch (final IOException exception) {
      // nothing more can be done with a socket that refuses to close
    }
  }

  private VernacularConfig createConfig(final VNCSource source, final Session created, final Thread renderWorker) {
    final VernacularConfig config = new VernacularConfig();
    config.setColorDepth(ColorDepth.BPP_24_TRUE);
    config.setShared(true);
    config.setUseLocalMousePointer(false);
    final int frameRate = source.getTargetFrameRate();
    config.setTargetFramesPerSecond(frameRate);
    config.setScreenUpdateListener(image -> this.onScreenUpdate(created, image, renderWorker));
    config.setErrorListener(error -> this.onError(created, error));

    final String username = source.getUsername();
    if (username != null && !username.isEmpty()) {
      config.setUsernameSupplier(() -> username);
    }
    final String password = source.getPassword();
    if (password != null && !password.isEmpty()) {
      config.setPasswordSupplier(() -> password);
    }
    return config;
  }

  private void onScreenUpdate(final Session owner, final Image image, final Thread renderWorker) {
    if (!(image instanceof final BufferedImage frame)) {
      return;
    }

    synchronized (this.frameLock) {
      // A callback from a previous client may finish after stop() returns. Check and publish under the same lock
      // as session replacement, so that callback cannot overwrite the new session's dimensions or pending frame.
      if (this.session != owner || !owner.acceptsFrames()) {
        return;
      }
      final int width = frame.getWidth();
      final int height = frame.getHeight();
      this.remoteWidth = width;
      this.remoteHeight = height;
      final boolean pausedNow = this.paused.get();
      if (pausedNow) {
        return;
      }

      // The first update may arrive during the handshake; the renderer picks it up once it starts.
      this.latestFrame.set(frame);
    }
    LockSupport.unpark(renderWorker);
  }

  private void onError(final Session failed, final VncException error) {
    final boolean duringStartup = failed.recordStartupError(error);
    if (duringStartup) {
      return;
    }

    // errors of a session that already ended, such as those caused by a release, are not reported
    final boolean wasAlive = failed.end();
    if (wasAlive) {
      this.report("The VNC connection failed", error);
    }
  }

  private void render(final VNCSource source, final Session owner) {
    @Nullable ResizeFilter resizeFilter = null;
    @Nullable OriginalVideoMetadata metadata = null;
    while (owner.isAlive()) {
      final BufferedImage frame = this.latestFrame.getAndSet(null);
      if (frame == null) {
        LockSupport.parkNanos(this.idleParkNanos);
        continue;
      }

      final int width = frameWidth(source, frame);
      final int height = frameHeight(source, frame);
      if (resizeFilter == null || !resizeFilter.matches(width, height)) {
        final int frameRate = source.getTargetFrameRate();
        resizeFilter = new ResizeFilter(width, height);
        metadata = OriginalVideoMetadata.of(width, height, frameRate);
      }

      // the metadata is created together with the resize filter
      final OriginalVideoMetadata current = Objects.requireNonNull(metadata, "Metadata must exist with the filter");
      this.deliver(frame, resizeFilter, current);
    }
  }

  private static int frameWidth(final VNCSource source, final BufferedImage frame) {
    final int configuredWidth = source.getScreenWidth();
    final int screenUpdateWidth = frame.getWidth();
    return sizeOrFallback(configuredWidth, screenUpdateWidth);
  }

  private static int frameHeight(final VNCSource source, final BufferedImage frame) {
    final int configuredHeight = source.getScreenHeight();
    final int screenUpdateHeight = frame.getHeight();
    return sizeOrFallback(configuredHeight, screenUpdateHeight);
  }

  // a configured size of 0 keeps the size of the remote screen
  private static int sizeOrFallback(final int configuredSize, final int fallbackSize) {
    if (configuredSize > 0) {
      return configuredSize;
    }
    return fallbackSize;
  }

  private void deliver(final BufferedImage frame, final ResizeFilter resizeFilter, final OriginalVideoMetadata metadata) {
    try (final ImageBuffer image = ImageBuffer.image(frame)) {
      resizeFilter.applyFilter(image, metadata);
      final VideoPipelineStep pipeline = this.videoCallback.retrieve();
      pipeline.processAll(image, metadata);
    } catch (final RuntimeException | Error exception) {
      // a frame runs user filters and native OpenCV code, which can fail with any Error, such as an AssertionError or
      // a LinkageError; only errors of the virtual machine are thrown, every other failure is reported
      ThrowableUtils.throwIfFatal(exception);
      this.report("Failed to process a VNC frame", exception);
    }
  }

  private void report(final String message, final Throwable error) {
    final BiConsumer<String, Throwable> handler = this.exceptionHandler.getExceptionHandler();
    handler.accept(message, error);
  }

  private boolean isActive() {
    final Session current = this.session;
    return current != null && current.isAlive();
  }

  /**
   * Checks whether a screen update is waiting for the render thread. Pausing and starting a session drop the update
   * that is waiting, so that a frame of a paused or of an ended session is never shown. Visible for testing.
   *
   * @return true while an update waits to be rendered
   */
  @VisibleForTesting
  boolean hasPendingFrame() {
    final BufferedImage pending = this.latestFrame.get();
    return pending != null;
  }

  @Override
  public boolean pause() {
    this.lock.lock();
    try {
      final boolean active = this.isActive();
      if (!active) {
        return false;
      }

      synchronized (this.frameLock) {
        final boolean changed = this.paused.compareAndSet(false, true);
        if (changed) {
          this.latestFrame.set(null);
        }
        return changed;
      }
    } finally {
      this.lock.unlock();
    }
  }

  @Override
  public boolean resume() {
    this.lock.lock();
    try {
      final boolean active = this.isActive();
      if (!active) {
        return false;
      }
      return this.paused.compareAndSet(true, false);
    } finally {
      this.lock.unlock();
    }
  }

  @Override
  public boolean release() {
    this.lock.lock();
    try {
      final boolean first = this.released.compareAndSet(false, true);
      if (!first) {
        return false;
      }

      final Session current = this.session;
      if (current != null) {
        current.end();
      }
      this.stopClient();
      this.stopRenderThread();
      synchronized (this.frameLock) {
        this.latestFrame.set(null);
      }
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  private void stopClient() {
    final VernacularClient vncClient = this.client;
    if (vncClient == null) {
      return;
    }

    // the client waits for its threads and swallows an interrupt of the caller, which must survive the release
    final Thread current = Thread.currentThread();
    final boolean interrupted = current.isInterrupted();
    try {
      vncClient.stop();
    } catch (final RuntimeException exception) {
      this.report("Failed to close the VNC connection", exception);
    }
    if (interrupted) {
      current.interrupt();
    }
    this.client = null;
  }

  private void stopRenderThread() {
    final Thread renderWorker = this.renderThread;
    if (renderWorker == null) {
      return;
    }

    LockSupport.unpark(renderWorker);
    this.join(renderWorker);
    this.renderThread = null;
  }

  private void join(final Thread thread) {
    final Thread caller = Thread.currentThread();
    if (thread.equals(caller)) {
      // A filter can release or restart its player. Its old renderer exits when that callback returns.
      return;
    }
    try {
      thread.join(STOP_TIMEOUT_MILLIS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  @Override
  public boolean isPlaying() {
    final boolean active = this.isActive();
    final boolean pausedNow = this.paused.get();
    return active && !pausedNow;
  }

  @Override
  public void moveMouse(final int x, final int y) {
    final VernacularClient vncClient = this.getConnectedClient();
    if (vncClient == null) {
      return;
    }

    final int[] translated = this.translateCoordinates(x, y);
    this.forward(() -> vncClient.moveMouse(translated[0], translated[1]));
  }

  @Override
  public void sendMouseEvent(final MouseClick type, final int x, final int y) {
    Preconditions.checkNotNull(type, "Mouse click type must not be null");
    final VernacularClient vncClient = this.getConnectedClient();
    if (vncClient == null) {
      return;
    }

    final int[] translated = this.translateCoordinates(x, y);
    this.forward(() -> {
        vncClient.moveMouse(translated[0], translated[1]);
        final Runnable press = clickAction(vncClient, type);
        press.run();
      });
  }

  private static Runnable clickAction(final VernacularClient vncClient, final MouseClick type) {
    return switch (type) {
      case LEFT -> () -> vncClient.click(LEFT_BUTTON);
      case RIGHT -> () -> vncClient.click(RIGHT_BUTTON);
      case DOUBLE -> () -> {
        vncClient.click(LEFT_BUTTON);
        vncClient.click(LEFT_BUTTON);
      };
      case HOLD -> () -> vncClient.updateMouseButton(LEFT_BUTTON, true);
      case RELEASE -> () -> vncClient.updateMouseButton(LEFT_BUTTON, false);
    };
  }

  @Override
  public void sendKeyEvent(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    final VernacularClient vncClient = this.getConnectedClient();
    if (vncClient == null) {
      return;
    }

    final OptionalInt symbol = KeySymbols.lookup(text);
    this.forward(() -> {
        if (symbol.isPresent()) {
          final int code = symbol.getAsInt();
          vncClient.updateKey(code, true);
          vncClient.updateKey(code, false);
        } else {
          vncClient.type(text);
        }
      });
  }

  private @Nullable VernacularClient getConnectedClient() {
    final boolean active = this.isActive();
    if (!active) {
      return null;
    }
    return this.client;
  }

  private void forward(final Runnable action) {
    try {
      action.run();
    } catch (final RuntimeException exception) {
      final boolean active = this.isActive();
      if (active) {
        this.report("Failed to forward input to the VNC server", exception);
      }
    }
  }

  private int[] translateCoordinates(final int x, final int y) {
    // input is only forwarded while connected, and the source is set before the connection is made
    final VNCSource current = Objects.requireNonNull(this.source, "Source must be set while connected");
    final int targetWidth = this.remoteWidth;
    final int targetHeight = this.remoteHeight;

    // the remote size is unknown until the first screen update arrives
    final int smallerSide = Math.min(targetWidth, targetHeight);
    if (smallerSide <= 0) {
      final int untranslatedX = Math.max(0, x);
      final int untranslatedY = Math.max(0, y);
      return new int[] { untranslatedX, untranslatedY };
    }

    final int configuredWidth = current.getScreenWidth();
    final int configuredHeight = current.getScreenHeight();
    final int frameWidth = sizeOrFallback(configuredWidth, targetWidth);
    final int frameHeight = sizeOrFallback(configuredHeight, targetHeight);
    final double widthRatio = (double) targetWidth / frameWidth;
    final double heightRatio = (double) targetHeight / frameHeight;
    final long scaledX = Math.round(x * widthRatio);
    final long scaledY = Math.round(y * heightRatio);
    final int clampedX = Math.clamp(scaledX, 0, targetWidth - 1);
    final int clampedY = Math.clamp(scaledY, 0, targetHeight - 1);
    return new int[] { clampedX, clampedY };
  }

  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoCallback;
  }

  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.exceptionHandler.getExceptionHandler();
  }

  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    Preconditions.checkNotNull(exceptionHandler, "Exception handler must not be null");
    this.exceptionHandler.setExceptionHandler(exceptionHandler);
  }

  /**
   * The state of one connection. Errors the client reports while the handshake runs fail the start; the hand-over
   * from the handshake to the running session is atomic, so no error falls between the two.
   */
  private static final class Session {

    private final AtomicBoolean alive;
    private boolean starting;
    private @Nullable VncException startupError;

    Session() {
      this.alive = new AtomicBoolean(false);
      this.starting = true;
    }

    /**
     * Records an error if the handshake is still running.
     *
     * @param error the error
     * @return true if the error belongs to the handshake
     */
    synchronized boolean recordStartupError(final VncException error) {
      if (!this.starting) {
        return false;
      }
      if (this.startupError == null) {
        this.startupError = error;
      }
      return true;
    }

    /**
     * Ends the handshake: the session comes alive unless an error was recorded.
     *
     * @return the first error of the handshake, or null if it succeeded
     */
    synchronized @Nullable VncException finishStartup() {
      this.starting = false;
      final VncException failure = this.startupError;
      if (failure == null) {
        this.alive.set(true);
      }
      return failure;
    }

    /**
     * Allows updates during the handshake and while streaming, but rejects callbacks after the session ends.
     *
     * @return true while the session can still produce frames
     */
    synchronized boolean acceptsFrames() {
      return this.starting || this.alive.get();
    }

    /**
     * Checks whether the session is running.
     *
     * @return true if the handshake succeeded and the session has not ended
     */
    boolean isAlive() {
      return this.alive.get();
    }

    /**
     * Ends the session.
     *
     * @return true if the session was alive
     */
    boolean end() {
      return this.alive.getAndSet(false);
    }
  }
}
