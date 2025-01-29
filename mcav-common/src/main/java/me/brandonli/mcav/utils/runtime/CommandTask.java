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
package me.brandonli.mcav.utils.runtime;

import com.google.common.base.Preconditions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * A specialized CommandTask which executes native commands from the Runtime. The class is used for
 * easier command execution as well as an easier way to "hold" onto commands and wait before
 * execution.
 */
public class CommandTask {

  private final String[] command;
  private Process process;
  private String errorOutput;
  private String output;

  /**
   * Instantiates a CommandTask.
   *
   * @param command       command
   * @param runOnCreation whether it should be run instantly
   * @throws IOException if the command isn't valid (when ran instantly)
   */
  public CommandTask(final String[] command, final boolean runOnCreation) throws IOException {
    Preconditions.checkNotNull(command);
    this.command = command;
    if (runOnCreation) {
      this.run(command);
    }
  }

  /**
   * Instantiates a CommandTask.
   *
   * @param command command
   */
  public CommandTask(final String... command) {
    Preconditions.checkNotNull(command);
    this.command = command;
  }

  /**
   * Executes the specified command using the system runtime and processes its output.
   *
   * @param command an array of strings representing the command and its arguments to be executed
   * @throws IOException if an I/O error occurs during command execution or while reading the output
   */
  public void run(@UnderInitialization CommandTask this, final String[] command) throws IOException {
    final Runtime runtime = Runtime.getRuntime();
    this.process = runtime.exec(command);
    this.readOutput(this.process);
  }

  /**
   * Reads the output from the process once
   *
   * @throws IOException if the output cannot be read
   */
  private void readOutput(@UnderInitialization CommandTask this, final Process process) throws IOException {
    if (this.output == null && process != null) {
      final StringBuilder outputBuilder = new StringBuilder();
      final StringBuilder errorOutputBuilder = new StringBuilder();
      final Thread stdoutThread = this.createStdOutReader(process, outputBuilder);
      final Thread stderrThread = this.createStdErrReader(process, errorOutputBuilder);
      stdoutThread.start();
      stderrThread.start();
      try {
        process.waitFor();
        stdoutThread.join();
        stderrThread.join();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Process interrupted", e);
      }
      this.output = outputBuilder.toString();
      this.errorOutput = errorOutputBuilder.toString();
    }
  }

  private Thread createStdOutReader(@UnderInitialization CommandTask this, final Process process, final StringBuilder outputBuilder) {
    return new Thread(() -> {
      try (final BufferedReader reader = this.getStdBufferedReader(process)) {
        String str;
        while ((str = reader.readLine()) != null) {
          outputBuilder.append(str).append(System.lineSeparator());
        }
      } catch (final IOException e) {
        throw new ProcessException(e.getMessage());
      }
    });
  }

  private Thread createStdErrReader(@UnderInitialization CommandTask this, final Process process, final StringBuilder outputBuilder) {
    return new Thread(() -> {
      try (final BufferedReader reader = this.getStdErrBufferedReader(process)) {
        String str;
        while ((str = reader.readLine()) != null) {
          outputBuilder.append(str).append(System.lineSeparator());
        }
      } catch (final IOException e) {
        throw new ProcessException(e.getMessage());
      }
    });
  }

  /**
   * Gets the output for the command
   *
   * @return the command output
   * @throws IOException if the process hasn't been started
   */
  public String getOutput() throws IOException {
    if (this.process == null) {
      throw new IOException("Process has not been started");
    }
    return this.output != null ? this.output : "";
  }

  /**
   * Gets the error output for the command
   *
   * @return the command error output
   * @throws IOException if the process hasn't been started
   */
  public String getErrorOutput() throws IOException {
    if (this.process == null) {
      throw new IOException("Process has not been started");
    }
    return this.errorOutput != null ? this.errorOutput : "";
  }

  private BufferedReader getStdErrBufferedReader(@UnderInitialization CommandTask this, final Process process) {
    return new BufferedReader(new InputStreamReader(process.getErrorStream()));
  }

  private BufferedReader getStdBufferedReader(@UnderInitialization CommandTask this, final Process process) {
    return new BufferedReader(new InputStreamReader(process.getInputStream()));
  }

  /**
   * Gets the command.
   *
   * @return array of command arguments
   */
  public String[] getCommand() {
    return this.command;
  }

  /**
   * Gets the process with this specific command.
   *
   * @return the process
   */
  public Process getProcess() {
    return this.process;
  }
}
