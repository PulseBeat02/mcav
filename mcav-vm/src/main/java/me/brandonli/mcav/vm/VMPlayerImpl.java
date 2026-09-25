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
package me.brandonli.mcav.vm;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.audio.DelayedAudioOutput;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vnc.VNCPlayer;
import me.brandonli.mcav.vnc.VNCSource;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link VMPlayer}: a {@link VMProcess} for QEMU plus a {@link VNCPlayer} attached to its display, and,
 * when the machine has sound, a {@link VMAudioClient} that hands it to the audio pipeline through a
 * {@link DelayedAudioOutput}.
 *
 * <p>The machine runs while QEMU runs: when QEMU exits on its own, as when the guest shuts down, the player stops
 * playing, ignores input, and can be started again. A sound connection that cannot be made is reported, and the
 * machine runs without sound.
 */
public final class VMPlayerImpl implements VMPlayer {

  /**
   * How long the sound of the guest is held before the pipeline gets it, in milliseconds. QEMU sends the sound of the
   * guest about every 10 ms, but it refreshes the picture of its VNC display 30 ms after a change at the earliest, and
   * later when the screen was idle, so without the delay the sound would run ahead of the picture.
   */
  static final int AUDIO_DELAY_MILLIS = 70;

  /**
   * The most sound of the guest that waits for the pipeline, the delay included, in milliseconds.
   */
  static final int MAX_QUEUED_AUDIO_MILLIS = AUDIO_DELAY_MILLIS + 60;

  private final VNCPlayer vncPlayer;
  private final ExecutableFinder finder;
  private final ProcessFactory processFactory;
  private final AudioConnector audioConnector;
  private final AudioAttachableCallback audioCallback;
  private final Lock lock;
  private final Object controls;
  private final AtomicBoolean running;
  private final AtomicBoolean released;

  // written under the lock but read without it by isActive(), so the read must not see a stale reference
  private volatile @Nullable VMProcess process;
  private volatile @Nullable DelayedAudioOutput audioOutput;
  private volatile @Nullable VMAudioClient audioClient;

  /**
   * Creates a player that runs QEMU from the {@code PATH} and streams its display with a new VNC player.
   *
   * @return the player
   */
  static VMPlayerImpl createDefault() {
    final VNCPlayer vncPlayer = VNCPlayer.create();
    final ExecutableFinder finder = new ExecutableFinder();
    return new VMPlayerImpl(vncPlayer, finder, VMProcess::create, VMAudioClient::connect);
  }

  /**
   * Constructs a player from its parts, so tests can replace QEMU and the VNC connection.
   *
   * @param vncPlayer      the player that streams the display of the machine
   * @param finder         finds the QEMU program
   * @param processFactory creates the QEMU process
   * @param audioConnector connects to the sound of the machine
   */
  @VisibleForTesting
  VMPlayerImpl(
    final VNCPlayer vncPlayer,
    final ExecutableFinder finder,
    final ProcessFactory processFactory,
    final AudioConnector audioConnector
  ) {
    this.vncPlayer = vncPlayer;
    this.finder = finder;
    this.processFactory = processFactory;
    this.audioConnector = audioConnector;
    this.audioCallback = AudioAttachableCallback.create();
    this.lock = new ReentrantLock();
    this.controls = new Object();
    this.running = new AtomicBoolean(false);
    this.released = new AtomicBoolean(false);
  }

  @Override
  public boolean start(final VMSettings settings, final Architecture architecture, final VMConfiguration configuration) {
    Preconditions.checkNotNull(settings, "Settings must not be null");
    Preconditions.checkNotNull(architecture, "Architecture must not be null");
    Preconditions.checkNotNull(configuration, "Configuration must not be null");

    this.lock.lock();
    try {
      final boolean gone = this.released.get();
      final boolean active = this.isActive();
      if (gone || active) {
        return false;
      }

      this.clearPreviousMachine();
      this.launchMachine(settings, architecture, configuration);
      return true;
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Starts QEMU and connects the VNC player to its display. The caller holds the lock.
   *
   * @param settings      how the machine is streamed
   * @param architecture  the guest architecture
   * @param configuration the QEMU command line
   */
  private void launchMachine(final VMSettings settings, final Architecture architecture, final VMConfiguration configuration) {
    final Path executable = this.findExecutable(architecture);
    final VMProcess qemu = this.processFactory.create(settings, architecture, executable, configuration);
    // start() can fail after creating a live child. Keep ownership before invoking any process lifecycle code.
    this.process = qemu;
    try {
      qemu.start();
      this.connectDisplay(settings);
      this.connectAudio(settings, qemu);
      this.running.set(true);
    } catch (final RuntimeException | Error failure) {
      ThrowableUtils.throwIfFatal(failure);
      this.shutdownAfterFailure(failure);
      throw failure;
    }
  }

  /**
   * Cleans up the previous machine before replacement, retaining ownership if its bounded shutdown times out.
   */
  private void clearPreviousMachine() {
    this.running.set(false);
    this.shutdownMachine();
    if (this.process != null) {
      throw new PlayerException("The previous QEMU process is still alive after shutdown");
    }
  }

  /** Stops the owned machine, forgetting it only after its process has exited. The caller holds the lock. */
  private void shutdownMachine() {
    this.disconnectAudio();
    final VMProcess qemu = this.process;
    if (qemu == null) {
      return;
    }
    qemu.shutdown();
    final boolean alive = qemu.isAlive();
    if (!alive) {
      this.process = null;
    }
  }

  /** Attempts cleanup without replacing an earlier failure with a recoverable shutdown failure. */
  private void shutdownAfterFailure(final Throwable failure) {
    try {
      this.shutdownMachine();
    } catch (final RuntimeException | Error cleanupFailure) {
      ThrowableUtils.throwIfFatal(cleanupFailure);
      final Equivalence<Object> identity = Equivalence.identity();
      final boolean sameFailure = identity.equivalent(failure, cleanupFailure);
      if (!sameFailure) {
        failure.addSuppressed(cleanupFailure);
      }
    }
  }

  /**
   * Checks whether the machine was started and QEMU still runs.
   *
   * @return true while the machine runs
   */
  private boolean isActive() {
    final VMProcess qemu = this.process;
    final boolean alive = qemu != null && qemu.isAlive();
    return this.running.get() && alive;
  }

  /** Connects the VNC player to the display; launchMachine retains responsibility for failed-start cleanup. */
  private void connectDisplay(final VMSettings settings) {
    final VNCSource source = createSource(settings);
    final boolean connected = this.vncPlayer.start(source);
    if (!connected) {
      throw new PlayerException("The VNC player could not be started");
    }
  }

  /**
   * Connects to the sound of a machine that has sound. A failure is reported, and the machine runs without sound.
   *
   * @param settings the display settings, whose port the sound comes from as well
   * @param qemu     the started machine
   */
  private void connectAudio(final VMSettings settings, final VMProcess qemu) {
    if (!qemu.hasAudio()) {
      return;
    }
    final DelayedAudioOutput output = DelayedAudioOutput.start(
      "the virtual machine",
      AUDIO_DELAY_MILLIS,
      MAX_QUEUED_AUDIO_MILLIS,
      this.audioCallback::retrieve,
      this::report
    );
    this.audioOutput = output;
    final InetSocketAddress address = new InetSocketAddress(VMProcess.LOOPBACK, settings.getPort());
    try {
      this.audioClient = this.audioConnector.connect(address, output::accept, this::report);
    } catch (final IOException exception) {
      this.disconnectAudio();
      this.report("The sound of the virtual machine could not be connected, it runs without sound", exception);
    }
  }

  /** Closes the sound connection and its output, if the machine has them. */
  private void disconnectAudio() {
    final VMAudioClient client = this.audioClient;
    if (client != null) {
      client.close();
      this.audioClient = null;
    }
    final DelayedAudioOutput output = this.audioOutput;
    if (output != null) {
      output.close();
      this.audioOutput = null;
    }
  }

  private void report(final String message, final Throwable failure) {
    final BiConsumer<String, Throwable> handler = this.getExceptionHandler();
    handler.accept(message, failure);
  }

  private Path findExecutable(final Architecture architecture) {
    final String command = architecture.getCommand();
    final Optional<Path> found = this.finder.find(command);
    if (found.isEmpty()) {
      throw new ExecutableNotInPathException(command);
    }
    return found.get();
  }

  private static VNCSource createSource(final VMSettings settings) {
    final int port = settings.getPort();
    final int width = settings.getWidth();
    final int height = settings.getHeight();
    final int frameRate = settings.getTargetFps();
    final VNCSource.Builder builder = VNCSource.builder();
    builder.host("127.0.0.1");
    builder.port(port);
    builder.screenWidth(width);
    builder.screenHeight(height);
    builder.targetFrameRate(frameRate);
    return builder.build();
  }

  @Override
  public void moveMouse(final int x, final int y) {
    final boolean active = this.isActive();
    if (active) {
      this.vncPlayer.moveMouse(x, y);
    }
  }

  @Override
  public void sendMouseEvent(final MouseClick type, final int x, final int y) {
    Preconditions.checkNotNull(type, "Mouse click type must not be null");
    final boolean active = this.isActive();
    if (active) {
      this.vncPlayer.sendMouseEvent(type, x, y);
    }
  }

  @Override
  public void sendKeyEvent(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null");
    final boolean active = this.isActive();
    if (active) {
      this.vncPlayer.sendKeyEvent(text);
    }
  }

  @Override
  public boolean pause() {
    // picture and sound change together, so a resume that runs meanwhile cannot leave one of them paused
    synchronized (this.controls) {
      final boolean active = this.isActive();
      final boolean paused = active && this.vncPlayer.pause();
      final DelayedAudioOutput output = this.audioOutput;
      if (paused && output != null) {
        // the sound of a paused machine is dropped, so it does not play late after the resume
        output.pause();
      }
      return paused;
    }
  }

  @Override
  public boolean resume() {
    synchronized (this.controls) {
      final boolean active = this.isActive();
      final boolean resumed = active && this.vncPlayer.resume();
      final DelayedAudioOutput output = this.audioOutput;
      if (resumed && output != null) {
        output.resume();
      }
      return resumed;
    }
  }

  @Override
  public boolean isPlaying() {
    final boolean active = this.isActive();
    return active && this.vncPlayer.isPlaying();
  }

  /**
   * Releases playback and attempts to stop QEMU. Later calls retry a surviving process without releasing VNC twice.
   *
   * @return true for the first release, false for subsequent cleanup attempts
   */
  @Override
  public boolean release() {
    this.lock.lock();
    try {
      final boolean first = this.released.compareAndSet(false, true);
      this.running.set(false);
      try {
        if (first) {
          this.vncPlayer.release();
        }
      } catch (final RuntimeException | Error failure) {
        ThrowableUtils.throwIfFatal(failure);
        this.shutdownAfterFailure(failure);
        throw failure;
      }
      // A repeated release still owns cleanup if QEMU survived the preceding bounded termination attempt.
      this.shutdownMachine();
      return first;
    } finally {
      this.lock.unlock();
    }
  }

  @Override
  public AudioAttachableCallback getAudioAttachableCallback() {
    // the output looks the pipeline up for every chunk, so pipelines attached while running take effect
    return this.audioCallback;
  }

  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    // the VNC player looks the pipeline up for every frame, so pipelines attached while running take effect
    return this.vncPlayer.getVideoAttachableCallback();
  }

  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.vncPlayer.getExceptionHandler();
  }

  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    Preconditions.checkNotNull(exceptionHandler, "Exception handler must not be null");
    this.vncPlayer.setExceptionHandler(exceptionHandler);
  }

  /**
   * Creates the QEMU process of a machine.
   */
  @FunctionalInterface
  interface ProcessFactory {
    /**
     * Creates a process that has not started yet.
     *
     * @param settings      the display settings
     * @param architecture  the guest architecture
     * @param executable    the QEMU program
     * @param configuration the QEMU options
     * @return the process
     */
    VMProcess create(VMSettings settings, Architecture architecture, Path executable, VMConfiguration configuration);
  }

  /**
   * Connects to the sound of a machine.
   */
  @FunctionalInterface
  interface AudioConnector {
    /**
     * Connects to the VNC server of a machine and starts receiving its sound.
     *
     * @param address  the address of the VNC server
     * @param sink     receives the samples
     * @param failures receives an unexpected end of the connection
     * @return the connection
     * @throws IOException if the connection or its handshake fails
     */
    VMAudioClient connect(InetSocketAddress address, VMAudioClient.Sink sink, BiConsumer<String, Throwable> failures) throws IOException;
  }
}
