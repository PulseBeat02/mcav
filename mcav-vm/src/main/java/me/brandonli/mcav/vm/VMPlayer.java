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

import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.player.ReleasablePlayer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.ControllablePlayer;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Runs a virtual machine in QEMU and streams its screen, forwarding mouse and keyboard input to it.
 *
 * <p>The player starts QEMU with a VNC display bound to the local machine and connects a
 * {@link me.brandonli.mcav.vnc.VNCPlayer} to it. A USB tablet is attached so the mouse pointer follows absolute
 * coordinates, and the fastest available accelerator is used unless the configuration sets one. QEMU must be
 * installed on the machine; see {@link VMModule}.
 *
 * <pre><code>
 *   final VMPlayer player = VMPlayer.create();
 *   final VideoAttachableCallback video = player.getVideoAttachableCallback();
 *   video.attach(pipeline);
 *   final VMConfiguration configuration = VMConfiguration.builder();
 *   configuration.cdrom("alpine.iso");
 *   configuration.memory(1024);
 *   final VMSettings settings = VMSettings.of(1024, 768, 30);
 *   player.start(settings, VMPlayer.Architecture.X86_64, configuration);
 * </code></pre>
 */
public interface VMPlayer extends ControllablePlayer, ReleasablePlayer, ExceptionHandler {
  /**
   * Creates a player.
   *
   * @return the player
   */
  static VMPlayer create() {
    return VMPlayerImpl.createDefault();
  }

  /**
   * Starts QEMU and connects to its display. Starting takes a few seconds; the call returns once the VNC display
   * is reachable.
   *
   * @param settings      how the machine is streamed
   * @param architecture  the guest architecture, which picks the QEMU program
   * @param configuration the QEMU command line
   * @return true if the machine started, false if the player is already running or released
   * @throws ExecutableNotInPathException              if the QEMU program is not installed
   * @throws me.brandonli.mcav.media.player.PlayerException if QEMU exits or its display never becomes reachable
   */
  boolean start(final VMSettings settings, final Architecture architecture, final VMConfiguration configuration);

  /**
   * Starts the machine on an executor.
   *
   * @param settings      how the machine is streamed
   * @param architecture  the guest architecture
   * @param configuration the QEMU command line
   * @param executor      the executor that starts QEMU
   * @return a future that completes with the result of {@link #start(VMSettings, Architecture, VMConfiguration)}
   */
  default CompletableFuture<Boolean> startAsync(
    final VMSettings settings,
    final Architecture architecture,
    final VMConfiguration configuration,
    final ExecutorService executor
  ) {
    Preconditions.checkNotNull(settings, "Settings must not be null");
    Preconditions.checkNotNull(architecture, "Architecture must not be null");
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    Preconditions.checkNotNull(executor, "Executor must not be null");
    return CompletableFuture.supplyAsync(() -> this.start(settings, architecture, configuration), executor);
  }

  /**
   * Starts the machine on the common pool.
   *
   * @param settings      how the machine is streamed
   * @param architecture  the guest architecture
   * @param configuration the QEMU command line
   * @return a future that completes with the result of {@link #start(VMSettings, Architecture, VMConfiguration)}
   */
  default CompletableFuture<Boolean> startAsync(
    final VMSettings settings,
    final Architecture architecture,
    final VMConfiguration configuration
  ) {
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.startAsync(settings, architecture, configuration, pool);
  }

  /**
   * Moves the mouse pointer.
   *
   * @param x the x coordinate in the streamed frame
   * @param y the y coordinate in the streamed frame
   */
  void moveMouse(final int x, final int y);

  /**
   * Types text. A key name from the X11 keysym table, such as {@code Return} or {@code Escape}, presses that
   * key; any other text is typed character by character.
   *
   * @param text the text or key name
   */
  void sendKeyEvent(final String text);

  /**
   * Moves the mouse pointer and performs a click.
   *
   * @param type the kind of click
   * @param x    the x coordinate in the streamed frame
   * @param y    the y coordinate in the streamed frame
   */
  void sendMouseEvent(final MouseClick type, final int x, final int y);

  /**
   * Checks whether the machine is running and frames are delivered.
   *
   * @return true while running and not paused
   */
  boolean isPlaying();

  /**
   * Gets the slot that holds the video pipeline the frames are sent through.
   *
   * @return the video pipeline slot
   */
  VideoAttachableCallback getVideoAttachableCallback();

  /**
   * The guest architectures QEMU can emulate, each with its own program.
   */
  enum Architecture {
    /**
     * 64-bit x86 guests, run by {@code qemu-system-x86_64}.
     */
    X86_64("qemu-system-x86_64"),
    /**
     * 32-bit ARM guests, run by {@code qemu-system-arm}.
     */
    ARM("qemu-system-arm"),
    /**
     * 64-bit ARM guests, run by {@code qemu-system-aarch64}.
     */
    AARCH64("qemu-system-aarch64"),
    /**
     * 64-bit RISC-V guests, run by {@code qemu-system-riscv64}.
     */
    RISCV64("qemu-system-riscv64");

    private final String command;

    Architecture(final String command) {
      this.command = command;
    }

    /**
     * Gets the name of the QEMU program for this architecture.
     *
     * @return the program name
     */
    public String getCommand() {
      return this.command;
    }
  }
}
