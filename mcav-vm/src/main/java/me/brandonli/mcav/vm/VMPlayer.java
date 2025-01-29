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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.multimedia.ControllablePlayer;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.utils.interaction.MouseClick;

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

  void moveMouse(final int x, final int y);

  void sendKeyEvent(final String text);

  void sendMouseEvent(final MouseClick type, final int x, final int y);

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
