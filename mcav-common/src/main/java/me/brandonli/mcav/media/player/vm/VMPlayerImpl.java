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
package me.brandonli.mcav.media.player.vm;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.player.vnc.VNCPlayer;
import me.brandonli.mcav.media.source.VNCSource;
import me.brandonli.mcav.utils.UncheckedIOException;
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
  private VideoMetadata metadata;
  private int vncPort;

  VMPlayerImpl() {
    this.vncPlayer = VNCPlayer.vm();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean start(
    final VideoPipelineStep step,
    final int port,
    final Architecture architecture,
    final VMConfiguration arguments,
    final VideoMetadata metadata
  ) {
    this.architecture = architecture;
    this.qemuArgs = arguments;
    this.vncPort = port;
    this.metadata = metadata;
    this.startQemuProcess();
    final VNCSource source = this.getVncSource();
    final VNCPlayer vncPlayer = requireNonNull(this.vncPlayer);
    return vncPlayer.start(step, source);
  }

  private VNCSource getVncSource() {
    return VNCSource.vnc().host("localhost").port(this.vncPort).name("VNC Connection").videoMetadata(this.metadata).build();
  }

  private void startQemuProcess() {
    try {
      final List<String> command = this.formatQemuArguments();
      final String[] arguments = command.toArray(new String[0]);
      final ProcessBuilder processBuilder = new ProcessBuilder(arguments);
      this.qemuProcess = processBuilder.start();
      try {
        Thread.sleep(10000);
      } catch (final InterruptedException e) {
        final Thread currentThread = Thread.currentThread();
        currentThread.interrupt();
        throw new AssertionError(e);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e.getMessage(), e);
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
      command.add("-vnc");
      command.add(":" + (this.vncPort - 5900));
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
  public void updateMouseButton(final int button, final boolean pressed) {
    if (this.vncPlayer != null) {
      this.vncPlayer.updateMouseButton(button, pressed);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updateKeyButton(final int keyCode, final boolean pressed) {
    if (this.vncPlayer != null) {
      this.vncPlayer.updateKeyButton(keyCode, pressed);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void type(final String text) {
    if (this.vncPlayer != null) {
      this.vncPlayer.type(text);
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
      qemuProcess.destroy();
      qemuProcess.descendants().forEach(ProcessHandle::destroy);
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
