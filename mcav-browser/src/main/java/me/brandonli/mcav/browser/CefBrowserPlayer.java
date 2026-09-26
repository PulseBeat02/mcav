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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.audio.DelayedAudioOutput;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.utils.os.OS;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The {@link BrowserPlayer}: a {@link HelperSession} per start, whose frames it runs through the video pipeline and
 * whose input it forwards.
 *
 * <p>A player is idle until it starts, plays until it is released or its helper ends by itself, and then counts as
 * failed until it starts again, which closes the failed helper first. A released player cannot start again. Frames and
 * failures of a helper that is no longer the current one are ignored, so a late frame of a replaced browser never
 * reaches the pipeline.
 */
final class CefBrowserPlayer implements BrowserPlayer {

  /**
   * How long a helper may take until its page shows. The first start after a download can be slow, for example while a
   * virus scanner checks the new files.
   */
  static final long START_TIMEOUT_MILLIS = 180_000L;

  /**
   * How long the sound of the page is held before the audio pipeline gets it, in milliseconds: not at all. The page
   * hands its sound over in chunks of 2048 frames (43 ms) as soon as Chromium rendered them, a little ahead of time.
   * Timed as the sound of a virtual machine is, where the last sample of a chunk counts as played when the chunk
   * arrives, it reaches the pipeline about 20 ms before its picture ({@code BrowserSoundTest}); but an output starts to
   * play a chunk only when it arrives, 43 ms later than that, so a hold would only make the sound late.
   */
  static final int AUDIO_DELAY_MILLIS = 0;

  /**
   * The most sound of the page that waits for the audio pipeline, the delay included, in milliseconds.
   */
  static final int MAX_QUEUED_AUDIO_MILLIS = AUDIO_DELAY_MILLIS + 120;

  private static final SessionFactory DEFAULT_SESSIONS = new DefaultSessionFactory();
  private static final Equivalence<Object> SESSION_IDENTITY = Equivalence.identity();

  private final BrowserOptions options;
  private final SessionFactory sessions;
  private final VideoAttachableCallback videoCallback;
  private final AudioAttachableCallback audioCallback;
  private final ExceptionHandler exceptionHandler;
  private final Lock lock;
  private final AtomicReference<State> state;
  private final AtomicBoolean released;
  // a player who clicks while the helper does not read its input makes a report for every click
  private final LogBudget dropReports;
  private volatile @Nullable BrowserSession session;
  private volatile @Nullable DelayedAudioOutput audioOutput;
  private volatile @Nullable BrowserSource source;

  /**
   * Constructs a player that starts real helpers.
   *
   * @param options how the player treats the pages it shows
   */
  CefBrowserPlayer(final BrowserOptions options) {
    this(options, DEFAULT_SESSIONS);
  }

  /**
   * Constructs a player that starts its sessions with a factory, so tests can control the helper.
   *
   * @param options  how the player treats the pages it shows
   * @param sessions starts the session of every start
   */
  @VisibleForTesting
  CefBrowserPlayer(final BrowserOptions options, final SessionFactory sessions) {
    this(options, sessions, System::nanoTime);
  }

  /**
   * Constructs a player with a factory for its sessions and the clock of the budget of its reports, so tests can move
   * time.
   *
   * @param options  how the player treats the pages it shows
   * @param sessions starts the session of every start
   * @param clock    a monotonic clock in nanoseconds
   */
  @VisibleForTesting
  CefBrowserPlayer(final BrowserOptions options, final SessionFactory sessions, final LongSupplier clock) {
    this.options = options;
    this.sessions = sessions;
    this.videoCallback = VideoAttachableCallback.create();
    this.audioCallback = AudioAttachableCallback.create();
    this.exceptionHandler = ExceptionHandler.createDefault();
    this.lock = new ReentrantLock();
    this.state = new AtomicReference<>(State.IDLE);
    this.released = new AtomicBoolean();
    this.dropReports = new LogBudget(clock);
  }

  @Override
  public boolean start(final BrowserSource source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    this.lock.lock();
    try {
      final boolean idle = !this.released.get() && this.state.get() != State.PLAYING;
      return idle && this.startSession(source);
    } finally {
      this.lock.unlock();
    }
  }

  private boolean startSession(final BrowserSource source) {
    this.closeSession();
    this.source = source;
    final int width = source.getWidth();
    final int height = source.getHeight();
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(width, height);
    final SessionListener listener = new SessionListener(metadata);
    final BrowserSession started = this.sessions.open(source, this.options, listener);
    this.session = started;
    this.audioOutput = DelayedAudioOutput.start(
      "the browser",
      AUDIO_DELAY_MILLIS,
      MAX_QUEUED_AUDIO_MILLIS,
      this.audioCallback::retrieve,
      this::report
    );
    this.state.set(State.PLAYING);
    // an end of the helper that arrived before the session was the player's is passed on now, and fails the start
    listener.setSession(started);
    final boolean playing = this.state.get() == State.PLAYING;
    if (playing) {
      // the frames of the start arrived before the session was the player's; the page is sent once more, so a page
      // that never changes again still reaches the pipeline
      started.requestFrame();
    }
    return playing;
  }

  private void closeSession() {
    final BrowserSession current = this.session;
    this.session = null;
    if (current != null) {
      current.close();
    }
    this.closeAudio();
  }

  private void closeAudio() {
    final DelayedAudioOutput output = this.audioOutput;
    this.audioOutput = null;
    if (output != null) {
      output.close();
    }
  }

  @Override
  public boolean release() {
    this.lock.lock();
    try {
      final boolean first = this.released.compareAndSet(false, true);
      if (first) {
        this.state.set(State.IDLE);
        this.closeSession();
      }
      return first;
    } finally {
      this.lock.unlock();
    }
  }

  @Override
  public boolean isPlaying() {
    return this.state.get() == State.PLAYING;
  }

  @Override
  public void moveMouse(final int x, final int y) {
    this.sendMouse(HelperProtocol.MOUSE_MOVE, x, y, HelperProtocol.BUTTON_LEFT, 0);
  }

  @Override
  public void sendMouseEvent(final MouseClick type, final int x, final int y) {
    Preconditions.checkNotNull(type, "Mouse click type must not be null");
    this.moveMouse(x, y);
    final Runnable input =
      switch (type) {
        case LEFT -> () -> this.click(HelperProtocol.BUTTON_LEFT, x, y, 1);
        case RIGHT -> () -> this.click(HelperProtocol.BUTTON_RIGHT, x, y, 1);
        case DOUBLE -> () -> {
          this.click(HelperProtocol.BUTTON_LEFT, x, y, 1);
          this.click(HelperProtocol.BUTTON_LEFT, x, y, 2);
        };
        case HOLD -> () -> this.sendMouse(HelperProtocol.MOUSE_PRESS, x, y, HelperProtocol.BUTTON_LEFT, 1);
        case RELEASE -> () -> this.sendMouse(HelperProtocol.MOUSE_RELEASE, x, y, HelperProtocol.BUTTON_LEFT, 1);
      };
    input.run();
  }

  private void click(final int button, final int x, final int y, final int clickCount) {
    this.sendMouse(HelperProtocol.MOUSE_PRESS, x, y, button, clickCount);
    this.sendMouse(HelperProtocol.MOUSE_RELEASE, x, y, button, clickCount);
  }

  @Override
  public void scroll(final int x, final int y, final int deltaX, final int deltaY) {
    final BrowserSession current = this.getInputSession();
    if (current == null) {
      return;
    }
    final int[] position = this.clamp(x, y);
    final int clampedX = Math.clamp(deltaX, Short.MIN_VALUE, Short.MAX_VALUE);
    final int clampedY = Math.clamp(deltaY, Short.MIN_VALUE, Short.MAX_VALUE);
    final MouseInput input = new MouseInput(
      HelperProtocol.MOUSE_WHEEL,
      position[0],
      position[1],
      HelperProtocol.BUTTON_LEFT,
      0,
      clampedX,
      clampedY
    );
    final boolean queued = current.sendMouse(input);
    this.reportIfDropped(queued);
  }

  private void sendMouse(final int action, final int x, final int y, final int button, final int clickCount) {
    final BrowserSession current = this.getInputSession();
    if (current == null) {
      return;
    }
    final int[] position = this.clamp(x, y);
    final MouseInput input = new MouseInput(action, position[0], position[1], button, clickCount, 0, 0);
    final boolean queued = current.sendMouse(input);
    this.reportIfDropped(queued);
  }

  @Override
  public void sendKeyEvent(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    final BrowserSession current = this.getInputSession();
    if (current == null) {
      return;
    }
    final boolean special = SpecialKeys.isSpecialKey(text);
    final int action = special ? HelperProtocol.KEY_PRESS : HelperProtocol.KEY_TYPE;
    final boolean queued = current.sendKey(action, text);
    this.reportIfDropped(queued);
  }

  private void reportIfDropped(final boolean queued) {
    if (!queued) {
      this.dropReports.log(
          () -> this.report("Browser input queue is full", new RejectedExecutionException("The browser input backlog is full")),
          dropped -> this.report("Browser input queue is full", new RejectedExecutionException(dropped + " more inputs were dropped"))
        );
    }
  }

  private @Nullable BrowserSession getInputSession() {
    final BrowserSession current = this.session;
    return this.isPlaying() ? current : null;
  }

  /**
   * Clamps a position to the page.
   *
   * @param x the x coordinate
   * @param y the y coordinate
   * @return the coordinates inside the page
   */
  @VisibleForTesting
  int[] clamp(final int x, final int y) {
    final BrowserSource current = this.source;
    final int width = current == null ? 1 : current.getWidth();
    final int height = current == null ? 1 : current.getHeight();
    final int clampedX = Math.clamp(x, 0, width - 1);
    final int clampedY = Math.clamp(y, 0, height - 1);
    return new int[] { clampedX, clampedY };
  }

  /**
   * Runs a picture of the page through the video pipeline, unless the session is no longer the current one. Failures
   * are reported, except errors of the virtual machine, which are thrown.
   *
   * @param from     the session the picture comes from
   * @param metadata the size of the page of that session
   * @param frame    the picture, which is closed afterwards
   */
  @VisibleForTesting
  void deliver(final BrowserSession from, final OriginalVideoMetadata metadata, final ImageBuffer frame) {
    try {
      if (!this.isCurrent(from) || !this.isPlaying()) {
        return;
      }
      final VideoPipelineStep pipeline = this.videoCallback.retrieve();
      pipeline.processAll(frame, metadata);
    } catch (final RuntimeException | Error exception) {
      // a frame runs user filters and native OpenCV code, which can fail with any Error; only errors of the virtual
      // machine are thrown, every other failure is reported
      ThrowableUtils.throwIfFatal(exception);
      this.report("Failed to process a browser frame", exception);
    } finally {
      frame.close();
    }
  }

  /**
   * Hands sound of the current session to its audio output; sound of a session that is over is dropped.
   *
   * @param from    the session that played it
   * @param samples 16-bit little-endian stereo samples at 48 kHz
   */
  @VisibleForTesting
  void deliverAudio(final BrowserSession from, final byte[] samples) {
    final DelayedAudioOutput output = this.audioOutput;
    if (output != null && this.isCurrent(from)) {
      output.accept(samples, samples.length);
    }
  }

  /**
   * Marks the player as failed when its current helper ended by itself, and reports why.
   *
   * @param from   the session that ended
   * @param reason why it ended
   * @param cause  the failure
   */
  @VisibleForTesting
  void onEnded(final BrowserSession from, final String reason, final Throwable cause) {
    if (!this.isCurrent(from)) {
      return;
    }
    final boolean failed = this.state.compareAndSet(State.PLAYING, State.FAILED);
    if (failed) {
      // the sound of a helper that ended is over, and its thread ends now; the session is closed by the next start or
      // release, as before
      this.closeAudio();
      this.report(reason, cause);
    }
  }

  /**
   * Checks whether a session is the current one of the player. Sessions compare by identity: an old session may
   * belong to the same page, but its frames and failures are over.
   *
   * @param from the session
   * @return true if it is the current session
   */
  private boolean isCurrent(final BrowserSession from) {
    final BrowserSession current = this.session;
    return current != null && SESSION_IDENTITY.equivalent(current, from);
  }

  private void report(final String message, final Throwable error) {
    final BiConsumer<String, Throwable> handler = this.exceptionHandler.getExceptionHandler();
    handler.accept(message, error);
  }

  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoCallback;
  }

  @Override
  public AudioAttachableCallback getAudioAttachableCallback() {
    return this.audioCallback;
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
   * Starts the session of a start.
   */
  @FunctionalInterface
  interface SessionFactory {
    /**
     * Starts a session and waits until it shows the page.
     *
     * @param source   the page and its size
     * @param options  the options of the player
     * @param listener receives the frames and an unexpected end
     * @return the session
     * @throws PlayerException if the browser cannot be started
     */
    BrowserSession open(BrowserSource source, BrowserOptions options, BrowserSession.Listener listener);
  }

  /**
   * Installs the CEF natives on first use and starts real helper processes.
   */
  static final class DefaultSessionFactory implements SessionFactory {

    private final JcefNatives natives;
    private final LinuxLibraries libraries;
    private final List<String> extraJvmOptions;
    private @Nullable HelperLauncher launcher;

    DefaultSessionFactory() {
      this(new JcefNatives(), List.of());
    }

    /**
     * Constructs a factory with another installer and JVM options for the helpers, so tests can add a coverage agent.
     *
     * @param natives         installs the CEF natives
     * @param extraJvmOptions options added to the JVM of every helper
     */
    @VisibleForTesting
    DefaultSessionFactory(final JcefNatives natives, final List<String> extraJvmOptions) {
      this(natives, new LinuxLibraries(), extraJvmOptions);
    }

    /**
     * Constructs a factory with other installers and JVM options for the helpers.
     *
     * @param natives         installs the CEF natives
     * @param libraries       installs the libraries a Linux server may lack
     * @param extraJvmOptions options added to the JVM of every helper
     */
    @VisibleForTesting
    DefaultSessionFactory(final JcefNatives natives, final LinuxLibraries libraries, final List<String> extraJvmOptions) {
      this.natives = natives;
      this.libraries = libraries;
      this.extraJvmOptions = List.copyOf(extraJvmOptions);
    }

    @Override
    public BrowserSession open(final BrowserSource source, final BrowserOptions options, final BrowserSession.Listener listener) {
      // a stopped module downloads nothing
      HelperProcesses.requireOpen();
      final Path installation;
      final HelperLauncher current;
      try {
        installation = this.natives.install();
        current = withLibraries(this.getLauncher(), this.libraries);
      } catch (final IOException exception) {
        throw new BrowserUnavailableException("The browser cannot be installed: " + exception.getMessage(), exception);
      }
      return HelperSession.open(current, installation, source, options, listener);
    }

    /**
     * Gives the helpers of a Linux server the libraries it lacks, installing them first.
     *
     * @param base      the launcher
     * @param libraries installs and links the libraries
     * @return the launcher, with the libraries on Linux
     * @throws IOException if the libraries cannot be installed
     */
    @VisibleForTesting
    static HelperLauncher withLibraries(final HelperLauncher base, final LinuxLibraries libraries) throws IOException {
      if (base.getOs() != OS.LINUX) {
        return base;
      }
      return withLibraries(base, libraries, Path.of("/"));
    }

    /**
     * Gives the helpers of a Linux server the libraries it lacks, reading the server's loader configuration below a
     * root, so tests can make up a server.
     *
     * @param base      the launcher
     * @param libraries installs the libraries
     * @param root      the root of the file system of the server
     * @return the launcher that links the libraries into every session, or the launcher itself when the server lacks
     *         none, which downloads nothing then
     * @throws IOException if the libraries cannot be installed
     */
    @VisibleForTesting
    static HelperLauncher withLibraries(final HelperLauncher base, final LinuxLibraries libraries, final Path root) throws IOException {
      final JcefNatives.NativePlatform platform = JcefNatives.detectCurrent();
      final String identifier = platform.getIdentifier();
      final List<Path> folders = LinuxLibraries.hostFolders(root, identifier);
      final List<LinuxLibraries.Pin> missing = libraries.findMissing(identifier, folders);
      if (missing.isEmpty()) {
        return base;
      }
      final Path bundle = libraries.install(identifier);
      return base.withLibraries(session -> LinuxLibraries.link(bundle, session, missing));
    }

    private synchronized HelperLauncher getLauncher() {
      HelperLauncher current = this.launcher;
      if (current == null) {
        current = HelperLauncher.createDefault(START_TIMEOUT_MILLIS, this.extraJvmOptions);
        this.launcher = current;
      }
      return current;
    }
  }

  /**
   * Passes the frames, the sound and the end of a session to the player, once the session is known. Frames and sound
   * before are dropped; an end before is kept and passed on when the session becomes known.
   */
  private final class SessionListener implements BrowserSession.Listener {

    private final OriginalVideoMetadata metadata;
    private volatile @Nullable BrowserSession owner;
    private @Nullable Consumer<BrowserSession> earlyEnd;

    SessionListener(final OriginalVideoMetadata metadata) {
      this.metadata = metadata;
    }

    void setSession(final BrowserSession session) {
      final Consumer<BrowserSession> end;
      synchronized (this) {
        this.owner = session;
        end = this.earlyEnd;
      }
      if (end != null) {
        end.accept(session);
      }
    }

    @Override
    public void onFrame(final ImageBuffer frame) {
      final BrowserSession from = this.owner;
      if (from == null) {
        // a frame of the start, before the session is the player's; the next frame shows the same page
        frame.close();
        return;
      }
      CefBrowserPlayer.this.deliver(from, this.metadata, frame);
    }

    @Override
    public void onAudio(final byte[] samples) {
      final BrowserSession from = this.owner;
      if (from != null) {
        CefBrowserPlayer.this.deliverAudio(from, samples);
      }
    }

    @Override
    public void onEnded(final String reason, final Throwable cause) {
      final BrowserSession from;
      synchronized (this) {
        from = this.owner;
        if (from == null) {
          // the session is still starting; the player hears of the end once the session is its own
          this.earlyEnd = session -> CefBrowserPlayer.this.onEnded(session, reason, cause);
          return;
        }
      }
      CefBrowserPlayer.this.onEnded(from, reason, cause);
    }
  }

  /**
   * The lifecycle of the player.
   */
  private enum State {
    /** Not started, or released. */
    IDLE,
    /** Streaming a page. */
    PLAYING,
    /** The helper ended by itself; it is closed when the player starts again or is released. */
    FAILED,
  }
}
