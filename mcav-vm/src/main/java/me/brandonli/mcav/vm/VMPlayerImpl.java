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
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vnc.VNCPlayer;
import me.brandonli.mcav.vnc.VNCSource;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link VMPlayer}: a {@link VMProcess} for QEMU plus a {@link VNCPlayer} attached to its display.
 *
 * <p>The machine runs while QEMU runs: when QEMU exits on its own, as when the guest shuts down, the player stops
 * playing, ignores input, and can be started again.
 */
public final class VMPlayerImpl implements VMPlayer {

  private final VNCPlayer vncPlayer;
  private final ExecutableFinder finder;
  private final ProcessFactory processFactory;
  private final Lock lock;
  private final AtomicBoolean running;
  private final AtomicBoolean released;

  private @Nullable VMProcess process;

  /**
   * Creates a player that runs QEMU from the {@code PATH} and streams its display with a new VNC player.
   *
   * @return the player
   */
  static VMPlayerImpl createDefault() {
    final VNCPlayer vncPlayer = VNCPlayer.create();
    final ExecutableFinder finder = new ExecutableFinder();
    return new VMPlayerImpl(vncPlayer, finder, VMProcess::create);
  }

  /**
   * Constructs a player from its parts, so tests can replace QEMU and the VNC connection.
   *
   * @param vncPlayer      the player that streams the display of the machine
   * @param finder         finds the QEMU program
   * @param processFactory creates the QEMU process
   */
  @VisibleForTesting
  VMPlayerImpl(final VNCPlayer vncPlayer, final ExecutableFinder finder, final ProcessFactory processFactory) {
    this.vncPlayer = vncPlayer;
    this.finder = finder;
    this.processFactory = processFactory;
    this.lock = new ReentrantLock();
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

      this.clearExitedMachine();
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
    final VMProcess qemu = this.processFactory.create(settings, executable, configuration);
    qemu.start();
    this.process = qemu;

    this.connectDisplay(qemu, settings);
    this.running.set(true);
  }

  /**
   * Cleans up after a QEMU process that exited on its own, so the machine can be started again.
   */
  private void clearExitedMachine() {
    this.running.set(false);
    final VMProcess exited = this.process;
    if (exited != null) {
      exited.shutdown();
      this.process = null;
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

  /**
   * Connects the VNC player to the display of the started QEMU process, shutting QEMU down if that fails.
   */
  private void connectDisplay(final VMProcess qemu, final VMSettings settings) {
    final VNCSource source = createSource(settings);
    try {
      final boolean connected = this.vncPlayer.start(source);
      if (!connected) {
        throw new PlayerException("The VNC player could not be started");
      }
    } catch (final PlayerException exception) {
      qemu.shutdown();
      this.process = null;
      throw exception;
    }
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
    final boolean active = this.isActive();
    return active && this.vncPlayer.pause();
  }

  @Override
  public boolean resume() {
    final boolean active = this.isActive();
    return active && this.vncPlayer.resume();
  }

  @Override
  public boolean isPlaying() {
    final boolean active = this.isActive();
    return active && this.vncPlayer.isPlaying();
  }

  @Override
  public boolean release() {
    this.lock.lock();
    try {
      final boolean first = this.released.compareAndSet(false, true);
      if (!first) {
        return false;
      }

      this.running.set(false);
      this.vncPlayer.release();

      final VMProcess qemu = this.process;
      if (qemu != null) {
        qemu.shutdown();
        this.process = null;
      }
      return true;
    } finally {
      this.lock.unlock();
    }
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
     * @param executable    the QEMU program
     * @param configuration the QEMU options
     * @return the process
     */
    VMProcess create(VMSettings settings, Path executable, VMConfiguration configuration);
  }
}
