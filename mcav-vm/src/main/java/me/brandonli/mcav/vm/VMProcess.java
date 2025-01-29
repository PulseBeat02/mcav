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

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import me.brandonli.mcav.media.player.PlayerException;
import org.checkerframework.checker.nullness.qual.Nullable;

final class VMProcess {

  private final VMSettings settings;
  private final VMPlayer.Architecture architecture;
  private final VMConfiguration configuration;

  private @Nullable Process qemuProcess;

  VMProcess(final VMSettings settings, final VMPlayer.Architecture architecture, final VMConfiguration configuration) {
    this.settings = settings;
    this.configuration = configuration;
    this.architecture = architecture;
  }

  void start() {
    try {
      final String[] arguments = this.constructCommand();
      final ProcessBuilder processBuilder = new ProcessBuilder(arguments);
      this.qemuProcess = processBuilder.start();
      this.waitForConnection();
    } catch (final IOException e) {
      throw new PlayerException(e.getMessage(), e);
    }
  }

  void shutdown() {
    final Process qemuProcess = this.qemuProcess;
    if (qemuProcess != null) {
      final long id = qemuProcess.pid();
      final Optional<ProcessHandle> handle = ProcessHandle.of(id);
      qemuProcess.destroyForcibly();
      handle.ifPresent(ProcessHandle::destroyForcibly);
      this.qemuProcess = null;
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

  private String[] constructCommand() {
    final List<String> command = new ArrayList<>();
    final String executable = this.architecture.getCommand();
    command.add(executable);

    final String[] args = this.configuration.buildArgs();
    final boolean hasVncOption = Arrays.stream(args).anyMatch(arg -> arg.contains("-vnc"));
    command.addAll(Arrays.asList(args));
    if (!hasVncOption) {
      final int vncPort = this.settings.getPort();
      command.add("-vnc");
      command.add(":" + (vncPort - 5900));
    }

    return command.toArray(new String[0]);
  }
}
