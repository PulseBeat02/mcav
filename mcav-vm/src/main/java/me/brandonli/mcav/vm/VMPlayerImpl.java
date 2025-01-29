/*
 * This file is part of mcav, a media playback library for Minecraft
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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.VNCSource;
import me.brandonli.mcav.utils.UncheckedIOException;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.utils.natives.NativeUtils;
import me.brandonli.mcav.vnc.VNCPlayer;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Provides an implementation of the {@link VMPlayer} interface to manage
 * virtual machine operations using QEMU and VNC.
 * <p>
 * This class handles the initialization and control of a QEMU process
 * for virtualization along with a VNC player to interact with the
 * virtual machine. It supports features such as starting the VM, controlling
 * input actions (mouse and keyboard), pausing, resuming, and releasing
 * resources.
 */
public class VMPlayerImpl implements VMPlayer {

  private @Nullable VNCPlayer vncPlayer;
  private @Nullable Process qemuProcess;

  private Architecture architecture;
  private VMConfiguration qemuArgs;
  private VMSettings settings;

  VMPlayerImpl() {
    this.vncPlayer = VNCPlayer.vm();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(
    final VideoPipelineStep step,
    final VMSettings settings,
    final Architecture architecture,
    final VMConfiguration arguments
  ) {
    this.architecture = architecture;
    this.qemuArgs = arguments;
    this.settings = settings;
    this.startQemuProcess();
    final VNCSource source = this.getVncSource();
    final VNCPlayer vncPlayer = requireNonNull(this.vncPlayer);
    return vncPlayer.start(step, source);
  }

  private VNCSource getVncSource() {
    final int vncPort = this.settings.getPort();
    final int width = this.settings.getWidth();
    final int height = this.settings.getHeight();
    final int targetFps = this.settings.getTargetFps();
    return VNCSource.vnc().host("localhost").port(vncPort).screenWidth(width).screenHeight(height).targetFrameRate(targetFps).build();
  }

  private void startQemuProcess() {
    final String cmd = this.architecture.getCommand();
    if (!NativeUtils.checkIfExecutableInPath(cmd)) {
      throw new ExecutableNotInPathException("QEMU");
    }
    try {
      final List<String> command = this.formatQemuArguments();
      final String[] arguments = command.toArray(new String[0]);
      final ProcessBuilder processBuilder = new ProcessBuilder(arguments);
      this.qemuProcess = processBuilder.start();
      this.waitForConnection();
    } catch (final IOException e) {
      throw new UncheckedIOException(e.getMessage(), e);
    }
  }

  private void waitForConnection() {
    final long timeout = System.currentTimeMillis() + 30000;
    boolean connected = false;
    while (System.currentTimeMillis() < timeout && !connected) {
      try {
        final int vncPort = this.settings.getPort();
        final Socket socket = new Socket("localhost", vncPort);
        socket.close();
        connected = true;
        this.sleep();
      } catch (final IOException e) {
        this.sleep();
      }
    }
  }

  private void sleep() {
    try {
      Thread.sleep(500);
    } catch (final InterruptedException ex) {
      final Thread thread = Thread.currentThread();
      thread.interrupt();
      throw new PlayerException(ex.getMessage(), ex);
    }
  }

  private List<String> formatQemuArguments() {
    final List<String> command = new ArrayList<>();
    command.add(this.architecture.getCommand());
    boolean hasVncOption = false;

    final String[] args = this.qemuArgs.buildArgs();
    for (final String arg : args) {
      command.add(arg);
      if (arg.contains("-vnc")) {
        hasVncOption = true;
      }
    }

    if (!hasVncOption) {
      final int vncPort = this.settings.getPort();
      command.add("-vnc");
      command.add(":" + (vncPort - 5900));
    }

    return command;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void moveMouse(final int x, final int y) {
    if (this.vncPlayer != null) {
      this.vncPlayer.moveMouse(x, y);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void sendMouseEvent(final MouseClick type, final int x, final int y) {
    if (this.vncPlayer != null) {
      this.vncPlayer.sendMouseEvent(type, x, y);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void sendKeyEvent(final String text) {
    if (this.vncPlayer != null) {
      this.vncPlayer.sendKeyEvent(text);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean release() {
    if (this.vncPlayer != null) {
      this.vncPlayer.release();
      this.vncPlayer = null;
    }

    final Process qemuProcess = this.qemuProcess;
    if (qemuProcess != null) {
      qemuProcess.destroyForcibly();
      qemuProcess.descendants().forEach(ProcessHandle::destroyForcibly);
      try {
        qemuProcess.waitFor();
      } catch (final InterruptedException e) {
        final Thread currentThread = Thread.currentThread();
        currentThread.interrupt();
        return false;
      }
      this.qemuProcess = null;
    }

    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean pause() {
    return this.vncPlayer != null && this.vncPlayer.pause();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean resume() {
    return this.vncPlayer != null && this.vncPlayer.resume();
  }
}
