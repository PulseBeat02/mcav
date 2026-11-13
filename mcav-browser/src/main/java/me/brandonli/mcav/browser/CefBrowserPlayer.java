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
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.audio.DelayedAudioOutput;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.utils.os.Os;
import org.checkerframework.checker.nullness.qual.Nullable;

// Frames and failures of a helper that is no longer current are ignored, so a replaced browser's late frame never reaches the pipeline.
final class CefBrowserPlayer implements BrowserPlayer {

  private static final int NO_SCROLL = 0;

  private static final int UNSTARTED_PAGE_SIDE = 1;

  private static final String INPUT_QUEUE_FULL_MESSAGE = "Browser input queue is full.";

  // the first start after a download can be slow, for example while a virus scanner checks the new files
  private static final long START_TIMEOUT_MILLISECONDS = 180_000L;

  // the page sends its sound slightly ahead of its picture and outputs play a chunk only on arrival, so a hold would only delay the sound
  private static final int AUDIO_DELAY_MILLISECONDS = 0;

  static final int MAXIMUM_QUEUED_AUDIO_MILLISECONDS = AUDIO_DELAY_MILLISECONDS + 120;

  private static final SessionFactory DEFAULT_SESSIONS = new HelperSessionFactory();

  private static final Equivalence<Object> SESSION_IDENTITY = Equivalence.identity();

  private static final int NO_CLICK = 0;
  private static final int SINGLE_CLICK = 1;
  private static final int DOUBLE_CLICK = 2;

  private final BrowserOptions options;
  private final SessionFactory sessions;
  private final VideoAttachableCallback videoCallback;
  private final AudioAttachableCallback audioCallback;
  private final ExceptionHandler exceptionHandler;
  private final Lock lock;
  private final AtomicReference<State> state;
  private final AtomicBoolean released;
  private final Object starter;
  private final LogBudget dropReports;

  // release() interrupts the starting thread rather than await its lock; guarded by starter, so no interrupt lands after the start
  private @Nullable Thread starting;
  // guarded by starter; the starting thread clears the interrupt of release()
  private boolean startInterrupted;
  private volatile @Nullable BrowserSession session;
  private volatile @Nullable DelayedAudioOutput audioOutput;
  private volatile @Nullable BrowserSource source;

  CefBrowserPlayer(final BrowserOptions options) {
    this(options, DEFAULT_SESSIONS);
  }

  @VisibleForTesting
  CefBrowserPlayer(final BrowserOptions options, final SessionFactory sessions) {
    this(options, sessions, System::nanoTime);
  }

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
    this.starter = new Object();
    this.dropReports = new LogBudget(clock);
  }

  @Override
  public boolean start(final BrowserSource source) {
    Preconditions.checkNotNull(source, "Source must not be null!");

    this.lock.lock();
    try {
      return this.startReleasably(source);
    } finally {
      this.lock.unlock();
    }
  }

  private boolean startReleasably(final BrowserSource source) {
    final Thread caller = Thread.currentThread();
    // an interrupt the caller had before is its own, even if a release interrupts the start too
    final boolean callerInterrupted = caller.isInterrupted();
    synchronized (this.starter) {
      this.starting = caller;
    }

    try {
      // checked once start is interruptible, so a release either finds this start or is seen here
      final boolean idle = !this.released.get() && this.state.get() != State.PLAYING;
      return idle && this.startSession(source);
    } catch (final BrowserException exception) {
      if (this.released.get()) {
        return false;
      }

      throw exception;
    } finally {
      final boolean interrupted;
      synchronized (this.starter) {
        this.starting = null;
        interrupted = this.startInterrupted;
        this.startInterrupted = false;
      }

      if (interrupted && !callerInterrupted) {
        Thread.interrupted();
      }
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
      AUDIO_DELAY_MILLISECONDS,
      MAXIMUM_QUEUED_AUDIO_MILLISECONDS,
      this.audioCallback::retrieve,
      this::report
    );
    this.state.set(State.PLAYING);
    // a helper exit before the session was owned must fail the start too
    listener.setSession(started);

    final boolean playing = this.state.get() == State.PLAYING;
    if (playing) {
      // the frames of the start arrived before the session was the player's; a page that stays still needs them again
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
    final boolean first = this.released.compareAndSet(false, true);
    if (!first) {
      return false;
    }

    // a start holds the lock while its helper starts, up to START_TIMEOUT_MILLISECONDS, so it is stopped rather than awaited
    synchronized (this.starter) {
      final Thread thread = this.starting;
      if (thread != null) {
        this.startInterrupted = true;
        thread.interrupt();
      }
    }

    this.lock.lock();
    try {
      this.state.set(State.IDLE);
      this.closeSession();
    } finally {
      this.lock.unlock();
    }

    return true;
  }

  @Override
  public void moveMouse(final int pageX, final int pageY) {
    this.sendMouse(HelperProtocol.MOUSE_MOVE, pageX, pageY, HelperProtocol.BUTTON_LEFT, NO_CLICK);
  }

  @Override
  public void sendMouseEvent(final MouseClick type, final int pageX, final int pageY) {
    Preconditions.checkNotNull(type, "Mouse click type must not be null!");

    this.moveMouse(pageX, pageY);

    final Runnable input = switch (type) {
      case LEFT -> () -> this.click(HelperProtocol.BUTTON_LEFT, pageX, pageY, SINGLE_CLICK);
      case RIGHT -> () -> this.click(HelperProtocol.BUTTON_RIGHT, pageX, pageY, SINGLE_CLICK);
      case DOUBLE -> () -> this.doubleClick(pageX, pageY);
      case HOLD -> () -> this.sendMouse(HelperProtocol.MOUSE_PRESS, pageX, pageY, HelperProtocol.BUTTON_LEFT, SINGLE_CLICK);
      case RELEASE -> () -> this.sendMouse(HelperProtocol.MOUSE_RELEASE, pageX, pageY, HelperProtocol.BUTTON_LEFT, SINGLE_CLICK);
    };
    input.run();
  }

  private void click(final int button, final int pageX, final int pageY, final int clickCount) {
    this.sendMouse(HelperProtocol.MOUSE_PRESS, pageX, pageY, button, clickCount);
    this.sendMouse(HelperProtocol.MOUSE_RELEASE, pageX, pageY, button, clickCount);
  }

  private void doubleClick(final int pageX, final int pageY) {
    this.click(HelperProtocol.BUTTON_LEFT, pageX, pageY, SINGLE_CLICK);
    this.click(HelperProtocol.BUTTON_LEFT, pageX, pageY, DOUBLE_CLICK);
  }

  @Override
  public void scroll(final int pageX, final int pageY, final int deltaX, final int deltaY) {
    final BrowserSession current = this.getInputSession();
    if (current == null) {
      return;
    }

    final int[] position = this.clamp(pageX, pageY);
    final int clampedX = Math.clamp(deltaX, Short.MIN_VALUE, Short.MAX_VALUE);
    final int clampedY = Math.clamp(deltaY, Short.MIN_VALUE, Short.MAX_VALUE);
    final MouseInput input = new MouseInput(
      HelperProtocol.MOUSE_WHEEL,
      position[0],
      position[1],
      HelperProtocol.BUTTON_LEFT,
      NO_CLICK,
      clampedX,
      clampedY
    );
    final boolean queued = current.sendMouse(input);
    if (!queued) {
      this.reportDropped();
    }
  }

  private void sendMouse(final int action, final int pageX, final int pageY, final int button, final int clickCount) {
    final BrowserSession current = this.getInputSession();
    if (current == null) {
      return;
    }

    final int[] position = this.clamp(pageX, pageY);
    final MouseInput input = new MouseInput(action, position[0], position[1], button, clickCount, NO_SCROLL, NO_SCROLL);
    final boolean queued = current.sendMouse(input);
    if (!queued) {
      this.reportDropped();
    }
  }

  @Override
  public void sendKeyEvent(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null!");

    final BrowserSession current = this.getInputSession();
    if (current == null) {
      return;
    }

    final boolean isSpecialKey = SpecialKeys.isSpecialKey(text);
    final int action = isSpecialKey ? HelperProtocol.KEY_PRESS : HelperProtocol.KEY_TYPE;
    final boolean queued = current.sendKey(action, text);
    if (!queued) {
      this.reportDropped();
    }
  }

  private void reportDropped() {
    this.dropReports.log(
      () -> this.report(INPUT_QUEUE_FULL_MESSAGE, new RejectedExecutionException("The browser input backlog is full!")),
      this::reportDroppedInputs
    );
  }

  private void reportDroppedInputs(final long dropped) {
    final String message = "The browser dropped %s more inputs!".formatted(dropped);
    final RejectedExecutionException exception = new RejectedExecutionException(message);
    this.report(INPUT_QUEUE_FULL_MESSAGE, exception);
  }

  private @Nullable BrowserSession getInputSession() {
    final BrowserSession current = this.session;
    return this.isPlaying() ? current : null;
  }

  @VisibleForTesting
  int[] clamp(final int pageX, final int pageY) {
    final BrowserSource current = this.source;
    final int width = current == null ? UNSTARTED_PAGE_SIDE : current.getWidth();
    final int height = current == null ? UNSTARTED_PAGE_SIDE : current.getHeight();
    final int clampedX = Math.clamp(pageX, 0, width - 1);
    final int clampedY = Math.clamp(pageY, 0, height - 1);
    return new int[] { clampedX, clampedY };
  }

  @VisibleForTesting
  void deliver(final BrowserSession from, final OriginalVideoMetadata metadata, final ImageBuffer frame) {
    try {
      if (!this.isCurrent(from) || !this.isPlaying()) {
        return;
      }

      final VideoPipelineStep pipeline = this.videoCallback.retrieve();
      pipeline.processAll(frame, metadata);
    } catch (final RuntimeException | Error exception) {
      // user filters and native OpenCV code can fail with any Error; only errors of the virtual machine are thrown
      ThrowableUtils.throwIfFatal(exception);
      this.report("Failed to process a browser frame.", exception);
    } finally {
      frame.close();
    }
  }

  @VisibleForTesting
  void deliverAudio(final BrowserSession from, final byte[] samples) {
    final DelayedAudioOutput output = this.audioOutput;
    if (output != null && this.isCurrent(from)) {
      output.accept(samples, samples.length);
    }
  }

  private void onEnded(final BrowserSession from, final String reason, final Throwable failure) {
    if (!this.isCurrent(from)) {
      return;
    }

    final boolean failed = this.state.compareAndSet(State.PLAYING, State.FAILED);
    if (failed) {
      this.closeAudio();

      final String message = "Because of \"%s\", the browser session ended.".formatted(reason);
      this.report(message, failure);
    }
  }

  // by identity: an old session may show the same page, but it is over
  private boolean isCurrent(final BrowserSession from) {
    final BrowserSession current = this.session;
    return current != null && SESSION_IDENTITY.equivalent(current, from);
  }

  private void report(final String message, final Throwable error) {
    final BiConsumer<String, Throwable> handler = this.exceptionHandler.getExceptionHandler();
    handler.accept(message, error);
  }

  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    Preconditions.checkNotNull(exceptionHandler, "Exception handler must not be null!");

    this.exceptionHandler.setExceptionHandler(exceptionHandler);
  }

  @Override
  public boolean isPlaying() {
    return this.state.get() == State.PLAYING;
  }

  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.exceptionHandler.getExceptionHandler();
  }

  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.videoCallback;
  }

  @Override
  public AudioAttachableCallback getAudioAttachableCallback() {
    return this.audioCallback;
  }

  @FunctionalInterface
  interface SessionFactory {
    BrowserSession open(final BrowserSource source, final BrowserOptions options, final BrowserSession.Listener listener);
  }

  static final class HelperSessionFactory implements SessionFactory {

    private static final Path ROOT = Path.of("/");

    private final JcefNatives natives;
    private final LinuxLibraries libraries;
    private final List<String> extraJvmOptions;

    private @Nullable HelperLauncher launcher;

    private HelperSessionFactory() {
      this(new JcefNatives(), List.of());
    }

    @VisibleForTesting
    HelperSessionFactory(final JcefNatives natives, final List<String> extraJvmOptions) {
      this(natives, new LinuxLibraries(), extraJvmOptions, null);
    }

    // serves the mutants of open and getLauncher, which otherwise only the cef group reaches
    @VisibleForTesting
    HelperSessionFactory(final JcefNatives natives, final LinuxLibraries libraries, final HelperLauncher launcher) {
      this(natives, libraries, List.of(), launcher);
    }

    private HelperSessionFactory(
      final JcefNatives natives,
      final LinuxLibraries libraries,
      final List<String> extraJvmOptions,
      final @Nullable HelperLauncher launcher
    ) {
      this.natives = natives;
      this.libraries = libraries;
      this.extraJvmOptions = List.copyOf(extraJvmOptions);
      this.launcher = launcher;
    }

    @Override
    public BrowserSession open(final BrowserSource source, final BrowserOptions options, final BrowserSession.Listener listener) {
      HelperProcesses.requireOpen();

      final Path installation;
      final HelperLauncher current;
      try {
        installation = this.natives.install();
        final HelperLauncher base = this.getLauncher();
        current = this.withLibraries(base, this.libraries);
      } catch (final IOException | DownloadException | FileOperationException exception) {
        final String message = "The browser cannot be installed!";
        throw new BrowserUnavailableException(message, exception);
      }

      return HelperSession.open(current, installation, source, options, listener);
    }

    @VisibleForTesting
    HelperLauncher withLibraries(final HelperLauncher base, final LinuxLibraries libraries) throws IOException {
      if (base.getOs() != Os.LINUX) {
        return base;
      }

      return this.withLibraries(base, libraries, ROOT);
    }

    @VisibleForTesting
    HelperLauncher withLibraries(final HelperLauncher base, final LinuxLibraries libraries, final Path root) throws IOException {
      final Path java = base.getJava();
      LinuxLibraries.requireJawt(java);

      final JcefNatives.NativePlatform platform = JcefNatives.detectCurrent();
      final String identifier = platform.getIdentifier();
      final List<Path> folders = LinuxLibraries.hostFolders(root, identifier);
      libraries.requireHostLibraries(identifier, folders);

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
        current = HelperLauncher.createDefault(START_TIMEOUT_MILLISECONDS, this.extraJvmOptions);
        this.launcher = current;
      }

      return current;
    }
  }

  // frames and sound before the session is known are dropped; an end is kept until then
  private final class SessionListener implements BrowserSession.Listener {

    private final OriginalVideoMetadata metadata;

    private volatile @Nullable BrowserSession owner;
    private @Nullable Consumer<BrowserSession> earlyEnd;

    SessionListener(final OriginalVideoMetadata metadata) {
      this.metadata = metadata;
    }

    private void setSession(final BrowserSession session) {
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
    public void onEnded(final String reason, final Throwable failure) {
      final BrowserSession from;
      synchronized (this) {
        from = this.owner;
        if (from == null) {
          this.earlyEnd = session -> CefBrowserPlayer.this.onEnded(session, reason, failure);
          return;
        }
      }

      CefBrowserPlayer.this.onEnded(from, reason, failure);
    }
  }

  private enum State {
    IDLE,
    PLAYING,
    // closed when the player starts again or is released
    FAILED,
  }
}
