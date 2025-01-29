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
import me.brandonli.mcav.media.player.multimedia.ControllablePlayer;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;

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

  boolean start(final VideoPipelineStep step, final VMSettings settings, final Architecture architecture, final VMConfiguration arguments);

  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep step,
    final VMSettings settings,
    final Architecture architecture,
    final VMConfiguration arguments,
    final ExecutorService service
  ) {
    return CompletableFuture.supplyAsync(() -> this.start(step, settings, architecture, arguments), service);
  }

  default CompletableFuture<Boolean> startAsync(
    final VideoPipelineStep step,
    final VMSettings settings,
    final Architecture architecture,
    final VMConfiguration arguments
  ) {
    return this.startAsync(step, settings, architecture, arguments, ForkJoinPool.commonPool());
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
