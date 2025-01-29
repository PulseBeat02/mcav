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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.ControllablePlayer;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Represents a virtual machine player interface that extends the capabilities of a controllable player
 */
public interface VMPlayer extends ControllablePlayer, ReleasablePlayer, ExceptionHandler {
  /**
   * Creates a new instance of the VMPlayer implementation.
   *
   * @return a new instance of VMPlayer
   */
  static VMPlayer vm() {
    return new VMPlayerImpl();
  }

  /**
   * Starts the virtual machine with the specified settings and architecture.
   *
   * @param settings   the VM settings to use
   * @param architecture the architecture of the VM
   * @param arguments  additional configuration arguments for the VM
   * @return true if the VM started successfully, false otherwise
   */
  boolean start(final VMSettings settings, final Architecture architecture, final VMConfiguration arguments);

  /**
   * Starts the virtual machine asynchronously with the specified settings and architecture.
   *
   * @param settings   the VM settings to use
   * @param architecture the architecture of the VM
   * @param arguments  additional configuration arguments for the VM
   * @param service    the executor service to run the task on
   * @return a CompletableFuture that completes with true if the VM started successfully, false otherwise
   */
  default CompletableFuture<Boolean> startAsync(
    final VMSettings settings,
    final Architecture architecture,
    final VMConfiguration arguments,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(settings, architecture, arguments), service);
  }

  /**
   * Starts the virtual machine asynchronously with the specified settings and architecture.
   *
   * @param settings   the VM settings to use
   * @param architecture the architecture of the VM
   * @param arguments  additional configuration arguments for the VM
   * @return a CompletableFuture that completes with true if the VM started successfully, false otherwise
   */
  default CompletableFuture<Boolean> startAsync(
    final VMSettings settings,
    final Architecture architecture,
    final VMConfiguration arguments
  ) {
    return this.startAsync(settings, architecture, arguments, ForkJoinPool.commonPool());
  }

  /**
   * Moves the mouse cursor to the specified coordinates within the virtual machine.
   *
   * @param x the x-coordinate to move the mouse to
   * @param y the y-coordinate to move the mouse to
   */
  void moveMouse(final int x, final int y);

  /**
   * Sends a key event with the specified text to the virtual machine.
   *
   * @param text the text to send as a key event
   */
  void sendKeyEvent(final String text);

  /**
   * Sends a mouse event of the specified type at the given coordinates within the virtual machine.
   *
   * @param type the type of mouse click event (e.g., CLICK, DOUBLE_CLICK, RIGHT_CLICK)
   * @param x    the x-coordinate where the mouse event should occur
   * @param y    the y-coordinate where the mouse event should occur
   */
  void sendMouseEvent(final MouseClick type, final int x, final int y);

  /**
   * Gets the video-attachable callback associated with this player.
   *
   * @return The video-attachable callback.
   */
  VideoAttachableCallback getVideoAttachableCallback();

  /**
   * Represents supported architectures for virtualization and emulation.
   */
  enum Architecture {
    /**
     * Represents the x86_64 architecture
     */
    X86_64("qemu-system-x86_64"),
    /**
     * Represents the ARM architecture
     */
    ARM("qemu-system-arm"),
    /**
     * Represents the AARCH64 architecture
     */
    AARCH64("qemu-system-aarch64"),
    /**
     * Represents the RISC-V 64-bit architecture
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
