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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.multimedia.ControllablePlayer;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.IOUtils;

/**
 * The VMPlayer interface defines the contract for a virtual machine-based
 * video media player. It extends {@code ControllablePlayer} and {@code ReleasablePlayer},
 * providing additional methods for managing video processing pipelines asynchronously
 * or synchronously across various architecture types.
 */
public interface VMPlayer extends ControllablePlayer, ReleasablePlayer {
  /**
   * Provides a new instance of the VMPlayer implementation.
   *
   * @return a new implementation of {@code VMPlayer}.
   */
  static VMPlayer vm() {
    return new VMPlayerImpl();
  }

  /**
   * Starts the execution of a specific step in the video processing pipeline.
   *
   * @param step         The video pipeline step to be executed.
   * @param port         The port number to be used for communication or data transfer.
   * @param architecture The architecture type for which the step is to be executed.
   * @param arguments    An array of additional arguments required for the step execution.
   * @param metadata     the metadata associated with the video. Must not be null.
   * @return True if the step started successfully, otherwise false.
   */
  boolean start(
    final VideoPipelineStep step,
    final int port,
    final Architecture architecture,
    final VMConfiguration arguments,
    final VideoMetadata metadata
  );

  /**
   * Starts the given video pipeline step with the specified architecture and arguments.
   *
   * @param step         the video pipeline step to start
   * @param architecture the architecture to be used
   * @param arguments    the arguments to be passed to the video pipeline step
   * @param metadata     the metadata associated with the video. Must not be null.
   * @return true if the step was started successfully; false otherwise
   */
  default boolean start(
    final VideoPipelineStep step,
    final Architecture architecture,
    final VMConfiguration arguments,
    final VideoMetadata metadata
  ) {
    final int free = IOUtils.getNextFreeVNCPort();
    return this.start(step, free, architecture, arguments, metadata);
  }

  /**
   * Asynchronously starts the provided video pipeline step using the
   * specified architecture and arguments within the given executor service.
   *
   * @param step         the video pipeline step to be executed
   * @param architecture the target architecture for the video pipeline
   * @param arguments    additional arguments required to configure the pipeline step
   * @param service      the executor service used to perform the operation asynchronously
   * @param metadata     the metadata associated with the video. Must not be null.
   * @return a CompletableFuture that resolves to {@code true} if the operation
   * completes successfully, or {@code false} otherwise
   */
  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep step,
    final Architecture architecture,
    final VMConfiguration arguments,
    final VideoMetadata metadata,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(step, architecture, arguments, metadata), service);
  }

  /**
   * Asynchronously starts the video playback pipeline using the provided video pipeline step,
   * architecture, and arguments, utilizing the common ForkJoinPool for execution.
   *
   * @param step         the video pipeline step to be used in the playback process.
   *                     Must not be null.
   * @param architecture the architecture to be used for the playback environment.
   *                     Must not be null.
   * @param arguments    an array of arguments to configure the playback pipeline.
   *                     Must not be null.
   * @param metadata     the metadata associated with the video. Must not be null.
   * @return a {@code CompletableFuture} that completes with {@code true} if the playback
   * pipeline is successfully started, or {@code false} otherwise.
   */
  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep step,
    final Architecture architecture,
    final VMConfiguration arguments,
    final VideoMetadata metadata
  ) {
    return this.startAsync(step, architecture, arguments, metadata, ForkJoinPool.commonPool());
  }

  /**
   * Initiates an asynchronous execution of the video pipeline step with the specified parameters
   * using a provided executor service.
   *
   * @param step         the video pipeline step to be started; must not be null.
   * @param port         the port number to be used; must be a valid and open port.
   * @param architecture the architecture to be used for executing the pipeline step; must not be null.
   * @param arguments    an array of additional command-line arguments to configure the step; can be empty but not null.
   * @param service      the {@link ExecutorService} to execute the asynchronous task; must not be null.
   * @param metadata     the metadata associated with the video. Must not be null.
   * @return a {@link CompletableFuture} representing the result of the asynchronous execution.
   * The future holds {@code true} if the operation was successful, {@code false} otherwise.
   */
  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep step,
    final int port,
    final Architecture architecture,
    final VMConfiguration arguments,
    final VideoMetadata metadata,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(step, port, architecture, arguments, metadata), service);
  }

  /**
   * Starts the video pipeline step asynchronously on the specified port, architecture,
   * and with the given arguments using the common pool for execution.
   *
   * @param step         the video pipeline step to be started. Must not be null.
   * @param port         the port number to be used for the operation.
   * @param architecture the architecture to be used. Must not be null.
   * @param arguments    an array of arguments for the pipeline step. Can be empty but not null.
   * @param metadata     the metadata associated with the video. Must not be null.
   * @return a CompletableFuture that resolves to {@code true} if the operation is successful,
   * or {@code false} otherwise.
   */
  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep step,
    final int port,
    final Architecture architecture,
    final VMConfiguration arguments,
    final VideoMetadata metadata
  ) {
    return this.startAsync(step, port, architecture, arguments, metadata, ForkJoinPool.commonPool());
  }

  /**
   * Moves the mouse pointer to the specified coordinates within the VNC session.
   *
   * @param x the x-coordinate to move the mouse to, in pixels
   * @param y the y-coordinate to move the mouse to, in pixels
   */
  void moveMouse(final int x, final int y);

  /**
   * Simulates typing the provided text into the VNC session by sending corresponding keyboard input events.
   *
   * @param text the string of characters to be sent as keyboard events within the VNC session;
   *             cannot be null or empty. Each character in the string is processed individually.
   */
  void type(final String text);

  /**
   * Updates the state of a mouse button in the VNC player session.
   * This method simulates mouse button press or release actions
   * and sends the corresponding event to the VNC session.
   *
   * @param type    the mouse button type, where 0 typically represents the left button,
   *                1 represents the middle button, and 2 represents the right button.
   * @param pressed a boolean value representing the button state; true for pressed,
   *                and false for released.
   */
  void updateMouseButton(final int type, final boolean pressed);

  /**
   * Updates the state of a keyboard button in the VNC session.
   *
   * @param keyCode the code of the keyboard key to update
   * @param pressed {@code true} if the key is to be pressed, {@code false} if it is to be released
   */
  void updateKeyButton(final int keyCode, final boolean pressed);

  /**
   * Represents supported architectures for virtualization and emulation.
   * Each enum constant is associated with a specific QEMU system command
   * that pertains to the respective architecture.
   */
  enum Architecture {
    /**
     * Represents the x86_64 architecture used for virtualization.
     * This architecture corresponds to the "qemu-system-x86_64" command,
     * which is used to emulate and run virtual machines for the x86_64 architecture.
     */
    X86_64("qemu-system-x86_64"),
    /**
     * Represents the ARM architecture for virtualization, specifically using the QEMU system emulator.
     * The associated command for this architecture is "qemu-system-arm".
     * This is used to specify the platform to emulate in virtual machine configurations.
     */
    ARM("qemu-system-arm"),
    /**
     * Represents the AARCH64 architecture used for virtualization.
     * This constant specifies the corresponding QEMU system command
     * for running AARCH64-based virtual machines.
     */
    AARCH64("qemu-system-aarch64"),
    /**
     * Specifies the RISC-V 64-bit architecture for virtualization and emulation.
     * This constant is associated with the `qemu-system-riscv64` command, which is used for
     * launching QEMU instances targeting the RISC-V 64-bit architecture.
     * <p>
     * It is a member of the {@code Architecture} enum, which represents supported
     * architectures for virtualization.
     */
    RISCV64("qemu-system-riscv64");

    private final String command;

    Architecture(final String command) {
      this.command = command;
    }

    /**
     * Retrieves the system command associated with a specific architecture.
     *
     * @return the command string corresponding to the architecture.
     */
    public String getCommand() {
      return this.command;
    }
  }
}
