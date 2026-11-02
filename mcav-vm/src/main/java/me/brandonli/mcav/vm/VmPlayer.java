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
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.ControllablePlayer;
import me.brandonli.mcav.media.player.multimedia.ExceptionHandler;
import me.brandonli.mcav.utils.interaction.MouseClick;

/**
 * Runs a virtual machine in QEMU and streams its screen and its sound, forwarding mouse and keyboard input to it.
 *
 * <p>The player starts QEMU with a VNC display bound to the local machine and connects a
 * {@link me.brandonli.mcav.vnc.VncPlayer} to it. A USB tablet is attached so the mouse pointer follows absolute
 * coordinates, and the fastest available accelerator is used unless the configuration sets one. On x86 PC and Q35
 * machines the player adds an Intel HD Audio card and routes the PC speaker, and a second VNC connection receives
 * their sound through QEMU's audio extension, in the format of the audio pipeline; the configuration may not set
 * {@code -vnc}, {@code -audio} or {@code -audiodev} itself. QEMU must be installed on the machine; see
 * {@link VmModule}.
 *
 * <p>Pausing suppresses picture and sound delivery; it does not suspend the guest or its QEMU process. Input
 * can still be forwarded while paused. The caller owns the player and must {@link #release()} it before
 * unloading the backend. Release is terminal for playback, although repeated release retries termination of
 * a surviving QEMU process. Pipelines and their filters remain caller-owned. Video and audio callbacks run
 * on background threads and must not retain borrowed buffers without copying them.
 *
 * {@snippet :
 * final VmPlayer player = VmPlayer.create();
 * final VideoAttachableCallback video = player.getVideoAttachableCallback();
 * video.attach(videoPipeline);
 * final String isoPath = "alpine.iso";
 * final int memoryMegabytes = 1024;
 * final VmConfigurationBuilder builder = VmConfiguration.builder();
 * final VmConfiguration configuration = builder.cdrom(isoPath).memory(memoryMegabytes).build();
 * final int width = 1024;
 * final int height = 768;
 * final int targetFrameRate = 30;
 * final VmSettings settings = VmSettings.of(width, height, targetFrameRate);
 * player.start(settings, VmPlayer.Architecture.X86_64, configuration);
 * // when done
 * player.release();
 * }
 *
 * <p>Implementations in this library are thread-safe.
 *
 * @since 1.0.0
 */
public interface VmPlayer extends ControllablePlayer, ReleasablePlayer, ExceptionHandler {
  /**
   * Creates a player.
   *
   * @return the player
   * @since 1.0.0
   */
  static VmPlayer create() {
    return QemuVmPlayer.createDefault();
  }

  /**
   * Starts QEMU and connects to its display. Starting takes a few seconds; the call returns once the VNC display
   * is reachable. Each QEMU launch waits up to 60 seconds for its display, and an automatic accelerator fallback
   * can make another launch attempt. An audio connection failure is reported to the exception handler and may
   * leave an otherwise successful machine running without audio.
   *
   * @param settings      the non-null output dimensions, requested frame rate and local VNC port
   * @param architecture  the non-null guest architecture, which picks the QEMU program
   * @param configuration the non-null QEMU options
   * @return true if the machine started, false if the player is already running or released
   * @throws ExecutableNotInPathException       if the QEMU program is not installed and MCAV cannot install it on this platform
   * @throws me.brandonli.mcav.MCAVException    if QEMU has to be installed and its download or installation fails
   * @throws IllegalArgumentException           if the configuration sets a display or sound option the player reserves
   * @throws VmException                        if the port cannot be bound, QEMU cannot launch or exits, the display
   *                                            does not open within the startup deadline, the wait is interrupted, or a
   *                                            previous machine cannot be stopped
   * @throws me.brandonli.mcav.vnc.VncException if the display opens but the VNC connection to it fails
   * @throws NullPointerException               if any argument is null
   * @since 1.0.0
   */
  boolean start(final VmSettings settings, final Architecture architecture, final VmConfiguration configuration);

  /**
   * Starts the machine on an executor.
   *
   * @param settings      how the machine is streamed
   * @param architecture  the guest architecture
   * @param configuration the QEMU command line
   * @param executor      the non-null caller-owned executor that starts QEMU; it is never shut down by the player
   * @return a future that completes with the startup result, or exceptionally if startup fails; cancelling
   *         the future does not release the player or terminate its process
   * @throws java.util.concurrent.RejectedExecutionException if the executor refuses the startup task
   * @throws NullPointerException                            if {@code settings}, {@code architecture}, {@code configuration} or
   *                                                         {@code executor} is null
   * @since 1.0.0
   */
  default CompletableFuture<Boolean> startAsync(
    final VmSettings settings,
    final Architecture architecture,
    final VmConfiguration configuration,
    final ExecutorService executor
  ) {
    Preconditions.checkNotNull(settings, "Settings must not be null!");
    Preconditions.checkNotNull(architecture, "Architecture must not be null!");
    Preconditions.checkNotNull(configuration, "Configuration must not be null!");
    Preconditions.checkNotNull(executor, "Executor must not be null!");

    return CompletableFuture.supplyAsync(() -> this.start(settings, architecture, configuration), executor);
  }

  /**
   * Starts the machine on the common pool.
   *
   * @param settings      how the machine is streamed
   * @param architecture  the guest architecture
   * @param configuration the QEMU command line
   * @return a future that completes with the startup result, or exceptionally if startup fails; cancelling
   *         the future does not release the player or terminate its process
   * @throws NullPointerException if any argument is null
   * @since 1.0.0
   */
  default CompletableFuture<Boolean> startAsync(
    final VmSettings settings,
    final Architecture architecture,
    final VmConfiguration configuration
  ) {
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    return this.startAsync(settings, architecture, configuration, pool);
  }

  /**
   * Moves the mouse pointer while the machine is active, including while paused; otherwise does nothing.
   * Coordinates are translated and clamped as {@link me.brandonli.mcav.vnc.VncPlayer#moveMouse(int, int)} describes.
   *
   * @param frameX the horizontal pixel coordinate from the left edge of the output frame
   * @param frameY the vertical pixel coordinate from the top edge of the output frame
   * @since 1.0.0
   */
  void moveMouse(final int frameX, final int frameY);

  /**
   * Types text. A key name from the X11 keysym table, such as {@code Return} or {@code Escape}, presses that
   * key; any other text is typed character by character.
   *
   * @param text the text or key name
   * @throws NullPointerException if {@code text} is null, including while inactive
   * @since 1.0.0
   */
  void sendKeyEvent(final String text);

  /**
   * Moves the mouse pointer and performs a click.
   *
   * @param type   the kind of click
   * @param frameX the horizontal pixel coordinate from the left edge of the output frame
   * @param frameY the vertical pixel coordinate from the top edge of the output frame
   * @throws NullPointerException if {@code type} is null, including while inactive
   * @since 1.0.0
   */
  void sendMouseEvent(final MouseClick type, final int frameX, final int frameY);

  /**
   * Returns whether the machine is running and frames are delivered.
   *
   * @return true while running and not paused
   * @since 1.0.0
   */
  boolean isPlaying();

  /**
   * Returns the slot that holds the video pipeline the frames are sent through.
   *
   * @return the video pipeline slot
   * @since 1.0.0
   */
  VideoAttachableCallback getVideoAttachableCallback();

  /**
   * Returns the slot that holds the audio pipeline the sound of the guest is sent through: 16-bit little-endian stereo
   * PCM at 48 kHz. Only x86 PC and Q35 machines have sound; while the player is paused, their sound is dropped.
   *
   * @return the audio pipeline slot
   * @since 1.0.0
   */
  AudioAttachableCallback getAudioAttachableCallback();

  /**
   * The guest architectures QEMU can emulate, each with its own program.
   *
   * <p>This enum is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  enum Architecture {
    /**
     * 64-bit x86 guests, run by {@code qemu-system-x86_64}.
     *
     * @since 1.0.0
     */
    X86_64("qemu-system-x86_64"),
    /**
     * 32-bit ARM guests, run by {@code qemu-system-arm}.
     *
     * @since 1.0.0
     */
    ARM("qemu-system-arm"),
    /**
     * 64-bit ARM guests, run by {@code qemu-system-aarch64}.
     *
     * @since 1.0.0
     */
    AARCH64("qemu-system-aarch64"),
    /**
     * 64-bit RISC-V guests, run by {@code qemu-system-riscv64}.
     *
     * @since 1.0.0
     */
    RISCV64("qemu-system-riscv64");

    private final String command;

    Architecture(final String command) {
      this.command = command;
    }

    /**
     * Returns the name of the QEMU program for this architecture.
     *
     * @return the program name
     * @since 1.0.0
     */
    public String getCommand() {
      return this.command;
    }
  }
}
