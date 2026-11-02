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
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.utils.ThrowableUtils;
import me.brandonli.mcav.utils.audio.DelayedAudioOutput;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vnc.VncPlayer;
import me.brandonli.mcav.vnc.VncSource;
import me.brandonli.mcav.vnc.VncSourceBuilder;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link VmPlayer}: a {@link VmProcess} for QEMU plus a {@link VncPlayer} attached to its display, and,
 * when the machine has sound, a {@link VmAudioClient} that hands it to the audio pipeline through a
 * {@link DelayedAudioOutput}.
 *
 * <p>The machine runs while QEMU runs: when QEMU exits on its own, as when the guest shuts down, the player stops
 * playing, ignores input, and can be started again. A sound connection that cannot be made is reported, and the
 * machine runs without sound.
 *
 * <p>This class is thread-safe: lifecycle calls are serialized, and input may be sent from any thread.
 *
 * @since 1.0.0
 */
public final class QemuVmPlayer implements VmPlayer {

  // QEMU refreshes the VNC picture 30 ms or more after a change, so sound without this delay runs ahead of it
  private static final int AUDIO_DELAY_MILLISECONDS = 70;

  private static final int AUDIO_QUEUE_HEADROOM_MILLISECONDS = 60;
  private static final int MAXIMUM_QUEUED_AUDIO_MILLISECONDS = AUDIO_DELAY_MILLISECONDS + AUDIO_QUEUE_HEADROOM_MILLISECONDS;

  private final VncPlayer vncPlayer;
  private final ExecutableFinder finder;
  private final QemuInstaller installer;
  private final ProcessFactory processFactory;
  private final AudioConnector audioConnector;
  private final AudioAttachableCallback audioCallback;
  private final Lock lock;
  private final Object controls;
  private final AtomicBoolean running;
  private final AtomicBoolean released;

  private volatile @Nullable VmProcess process;
  private volatile @Nullable DelayedAudioOutput audioOutput;
  private volatile @Nullable VmAudioClient audioClient;

  @VisibleForTesting
  QemuVmPlayer(
    final VncPlayer vncPlayer,
    final ExecutableFinder finder,
    final QemuInstaller installer,
    final ProcessFactory processFactory,
    final AudioConnector audioConnector
  ) {
    this.vncPlayer = vncPlayer;
    this.finder = finder;
    this.installer = installer;
    this.processFactory = processFactory;
    this.audioConnector = audioConnector;
    this.audioCallback = AudioAttachableCallback.create();
    this.lock = new ReentrantLock();
    this.controls = new Object();
    this.running = new AtomicBoolean(false);
    this.released = new AtomicBoolean(false);
  }

  static QemuVmPlayer createDefault() {
    final VncPlayer vncPlayer = VncPlayer.create();
    final ExecutableFinder finder = new ExecutableFinder();
    final QemuInstaller installer = new QemuInstaller();
    return new QemuVmPlayer(vncPlayer, finder, installer, VmProcess::create, VmAudioClient::connect);
  }

  /**
   * Starts QEMU and connects to its display. Starting takes a few seconds; the call returns once the VNC display
   * is reachable. Each QEMU launch waits up to 60 seconds for its display, and an automatic accelerator fallback
   * can make another launch attempt. An audio connection failure is reported to the exception handler and may
   * leave an otherwise successful machine running without audio. On x86-64 and ARM64 Linux without QEMU, the first
   * start downloads it.
   *
   * @param settings      the non-null output dimensions, requested frame rate and local VNC port
   * @param architecture  the non-null guest architecture, which picks the QEMU program
   * @param configuration the non-null QEMU options
   * @return true if the machine started, false if the player is already running or released
   * @throws ExecutableNotInPathException       if the QEMU program is not installed and MCAV cannot install it on this platform
   * @throws me.brandonli.mcav.MCAVException    if QEMU has to be installed and its download or installation fails
   * @throws IllegalArgumentException           if the configuration sets a display or sound option the player reserves
   * @throws VmException                        if the port cannot be bound, QEMU cannot launch or exits, the display
   *                                            does not open within the startup deadline, the wait is interrupted, or a
   *                                            previous machine cannot be stopped
   * @throws me.brandonli.mcav.vnc.VncException if the display opens but the VNC connection to it fails
   * @throws NullPointerException               if any argument is null
   * @since 1.0.0
   */
  @Override
  public boolean start(final VmSettings settings, final Architecture architecture, final VmConfiguration configuration) {
    Preconditions.checkNotNull(settings, "Settings must not be null!");
    Preconditions.checkNotNull(architecture, "Architecture must not be null!");
    Preconditions.checkNotNull(configuration, "Configuration must not be null!");

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

  // the caller holds the lock
  private void launchMachine(final VmSettings settings, final Architecture architecture, final VmConfiguration configuration) {
    final QemuProgram program = this.findOrInstallProgram(architecture);
    final VmProcess qemu = this.processFactory.create(settings, architecture, program, configuration);
    // start() can fail after creating a live child. Keep ownership before invoking any process lifecycle code.
    this.process = qemu;

    try {
      qemu.start();
      this.connectDisplay(settings, qemu);
      this.connectAudio(settings, qemu);
      this.running.set(true);
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.shutdownAfterFailure(exception);
      throw exception;
    }
  }

  private void clearPreviousMachine() {
    this.running.set(false);
    this.shutdownMachine();

    if (this.process != null) {
      final String message = "Previous QEMU process is still alive after its shutdown!";
      throw new VmException(message);
    }
  }

  // the caller holds the lock
  private void shutdownMachine() {
    this.disconnectAudio();

    final VmProcess qemu = this.process;
    if (qemu == null) {
      return;
    }

    qemu.shutdown();

    final boolean alive = qemu.isAlive();
    if (!alive) {
      this.process = null;
    }
  }

  private void shutdownAfterFailure(final Throwable failure) {
    try {
      this.shutdownMachine();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);

      final Equivalence<Object> identity = Equivalence.identity();
      final boolean sameFailure = identity.equivalent(failure, exception);
      if (!sameFailure) {
        failure.addSuppressed(exception);
      }
    }
  }

  private boolean isActive() {
    final VmProcess qemu = this.process;
    final boolean alive = qemu != null && qemu.isAlive();
    return this.running.get() && alive;
  }

  private void connectDisplay(final VmSettings settings, final VmProcess qemu) {
    final String password = qemu.getPassword();
    final VncSource source = this.createSource(settings, password);
    final boolean connected = this.vncPlayer.start(source);
    if (!connected) {
      final String message = "VNC player could not be started!";
      throw new VmException(message);
    }
  }

  private void connectAudio(final VmSettings settings, final VmProcess qemu) {
    final boolean audio = qemu.hasAudio();
    if (!audio) {
      return;
    }

    final DelayedAudioOutput output = DelayedAudioOutput.start(
      "the virtual machine",
      AUDIO_DELAY_MILLISECONDS,
      MAXIMUM_QUEUED_AUDIO_MILLISECONDS,
      this.audioCallback::retrieve,
      this::report
    );
    this.audioOutput = output;

    final int port = settings.getPort();
    final InetSocketAddress address = new InetSocketAddress(VmProcess.LOOPBACK, port);
    final String password = qemu.getPassword();
    final OutputSink sink = new OutputSink(output);
    try {
      this.audioClient = this.audioConnector.connect(address, password, sink, this::report);
    } catch (final IOException exception) {
      this.disconnectAudio();
      this.report("The sound of the virtual machine could not be connected, so it runs without sound.", exception);
    }
  }

  private void disconnectAudio() {
    final VmAudioClient client = this.audioClient;
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

  private QemuProgram findOrInstallProgram(final Architecture architecture) {
    final String command = architecture.getCommand();
    final Optional<Path> found = this.finder.find(command);
    final Optional<QemuProgram> system = found.map(QemuProgram::of);
    return system.orElseGet(() -> this.installer.install(architecture));
  }

  private VncSource createSource(final VmSettings settings, final String password) {
    final int port = settings.getPort();
    final int width = settings.getWidth();
    final int height = settings.getHeight();
    final int frameRate = settings.getTargetFrameRate();
    final VncSourceBuilder builder = VncSource.builder();
    builder.host(VmProcess.LOCALHOST);
    builder.port(port);
    builder.screenWidth(width);
    builder.screenHeight(height);
    builder.targetFrameRate(frameRate);
    builder.password(password);
    return builder.build();
  }

  /**
   * Permanently releases playback, closes owned audio resources and attempts bounded termination of QEMU.
   * Later calls retry a surviving process without releasing VNC twice. A normal return does not guarantee that
   * a process which ignored forced termination has exited. Attached filters remain caller-owned; first release
   * makes further startup attempts return false.
   *
   * @return true for the first release, false for subsequent cleanup attempts
   * @since 1.0.0
   */
  @Override
  public boolean release() {
    this.lock.lock();
    try {
      final boolean first = this.released.compareAndSet(false, true);
      this.running.set(false);

      if (first) {
        this.releaseDisplay();
      }

      // A repeated release still owns cleanup if QEMU survived the preceding bounded termination attempt.
      this.shutdownMachine();

      return first;
    } finally {
      this.lock.unlock();
    }
  }

  private void releaseDisplay() {
    try {
      this.vncPlayer.release();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.shutdownAfterFailure(exception);
      throw exception;
    }
  }

  /**
   * Moves the mouse pointer while the machine is active, including while paused; otherwise does nothing.
   * Coordinates are translated and clamped as {@link VncPlayer#moveMouse(int, int)} describes.
   *
   * @param frameX the horizontal pixel coordinate from the left edge of the output frame
   * @param frameY the vertical pixel coordinate from the top edge of the output frame
   * @since 1.0.0
   */
  @Override
  public void moveMouse(final int frameX, final int frameY) {
    final boolean active = this.isActive();
    if (active) {
      this.vncPlayer.moveMouse(frameX, frameY);
    }
  }

  /**
   * Moves the mouse pointer and performs a click while the machine is active, including while paused; otherwise does
   * nothing.
   *
   * @param type   the kind of click
   * @param frameX the horizontal pixel coordinate from the left edge of the output frame
   * @param frameY the vertical pixel coordinate from the top edge of the output frame
   * @throws NullPointerException if {@code type} is null, including while inactive
   * @since 1.0.0
   */
  @Override
  public void sendMouseEvent(final MouseClick type, final int frameX, final int frameY) {
    Preconditions.checkNotNull(type, "Mouse click type must not be null!");

    final boolean active = this.isActive();
    if (active) {
      this.vncPlayer.sendMouseEvent(type, frameX, frameY);
    }
  }

  /**
   * Types text while the machine is active, including while paused; otherwise does nothing. A key name from the X11
   * keysym table, such as {@code Return} or {@code Escape}, presses that key; any other text is typed character by
   * character.
   *
   * @param text the text or key name
   * @throws NullPointerException if {@code text} is null, including while inactive
   * @since 1.0.0
   */
  @Override
  public void sendKeyEvent(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null!");

    final boolean active = this.isActive();
    if (active) {
      this.vncPlayer.sendKeyEvent(text);
    }
  }

  /**
   * Pauses picture and sound delivery while QEMU and the guest continue to run. Queued sound is discarded,
   * and new audio is dropped until resume. Input can still be sent to the guest.
   *
   * @return true if an active display was paused, false if inactive or already paused
   * @since 1.0.0
   */
  @Override
  public boolean pause() {
    // picture and sound change together, so a resume that runs meanwhile cannot leave one of them paused
    synchronized (this.controls) {
      final boolean active = this.isActive();
      if (!active) {
        return false;
      }

      final boolean paused = this.vncPlayer.pause();
      final DelayedAudioOutput output = this.audioOutput;
      if (paused && output != null) {
        output.pause();
      }

      return paused;
    }
  }

  /**
   * Resumes picture and sound delivery without replaying audio dropped during the pause.
   *
   * @return true if an active paused display resumed, false if inactive or already unpaused
   * @since 1.0.0
   */
  @Override
  public boolean resume() {
    synchronized (this.controls) {
      final boolean active = this.isActive();
      if (!active) {
        return false;
      }

      final boolean resumed = this.vncPlayer.resume();
      final DelayedAudioOutput output = this.audioOutput;
      if (resumed && output != null) {
        output.resume();
      }

      return resumed;
    }
  }

  /**
   * Returns whether the machine is running and frames are delivered.
   *
   * @return true while running and not paused
   * @since 1.0.0
   */
  @Override
  public boolean isPlaying() {
    final boolean active = this.isActive();
    return active && this.vncPlayer.isPlaying();
  }

  /**
   * Returns the slot that holds the audio pipeline the sound of the guest is sent through: 16-bit little-endian stereo
   * PCM at 48 kHz. Only x86 PC and Q35 machines have sound; while the player is paused, their sound is dropped.
   *
   * @return the audio pipeline slot
   * @since 1.0.0
   */
  @Override
  public AudioAttachableCallback getAudioAttachableCallback() {
    return this.audioCallback;
  }

  /**
   * Returns the slot that holds the video pipeline the frames are sent through.
   *
   * @return the video pipeline slot
   * @since 1.0.0
   */
  @Override
  public VideoAttachableCallback getVideoAttachableCallback() {
    return this.vncPlayer.getVideoAttachableCallback();
  }

  /**
   * Returns the handler that receives failures of the display and of the sound connection.
   *
   * @return the handler
   * @since 1.0.0
   */
  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.vncPlayer.getExceptionHandler();
  }

  /**
   * Sets the handler that receives failures of the display and of the sound connection. The handler is called on the
   * thread that failed, so it must be fast and must not throw.
   *
   * @param exceptionHandler the handler
   * @throws NullPointerException if {@code exceptionHandler} is null
   * @since 1.0.0
   */
  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    Preconditions.checkNotNull(exceptionHandler, "Exception handler must not be null!");

    this.vncPlayer.setExceptionHandler(exceptionHandler);
  }

  @FunctionalInterface
  interface ProcessFactory {
    VmProcess create(
      final VmSettings settings,
      final Architecture architecture,
      final QemuProgram program,
      final VmConfiguration configuration
    );
  }

  private static final class OutputSink implements VmAudioClient.Sink {

    private final DelayedAudioOutput output;

    OutputSink(final DelayedAudioOutput output) {
      this.output = output;
    }

    @Override
    public void accept(final byte[] samples, final int length) {
      this.output.accept(samples, length);
    }

    @Override
    public void quiet() {
      this.output.quiet();
    }
  }

  @FunctionalInterface
  interface AudioConnector {
    VmAudioClient connect(
      final InetSocketAddress address,
      final String password,
      final VmAudioClient.Sink sink,
      final BiConsumer<String, Throwable> failures
    ) throws IOException;
  }
}
