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
package me.brandonli.mcav;

import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.installer.qemu.QemuInstaller;
import me.brandonli.mcav.capability.installer.vlc.VLCInstallationKit;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.media.player.combined.vlc.MediaPlayerFactoryProvider;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * MCAV is the main implementation of the {@link MCAVApi} interface, providing core functionality
 * to manage and handle media playback capabilities, installation of dependencies, and resource cleanup.
 * <p>
 * This class is designed to initialize and manage the capabilities supported by the library based on
 * the {@link Capability} values provided. It utilizes a set of asynchronous tasks for installing
 * required tools and dependencies and ensures proper shutdown of resources when no longer in use.
 * <p>
 * This class is immutable and thread-safe.
 */
public final class MCAV implements MCAVApi {

  private final Set<Capability> capabilities;

  MCAV() {
    this.capabilities = Arrays.stream(Capability.values()).filter(Capability::isEnabled).collect(Collectors.toSet());
  }

  /**
   * Provides a static factory method to obtain an instance of the {@link MCAVApi} interface,
   * implemented by the {@link MCAV} class. This method initializes a new instance of {@code MCAV},
   * which serves as the primary implementation of the library's capabilities.
   *
   * @return a new instance of {@link MCAVApi} implemented by {@link MCAV}, offering media playback
   * capabilities, resource installation, and management functionalities.
   */
  public static MCAVApi api() {
    return new MCAV();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean hasCapability(final Capability capability) {
    return this.capabilities.contains(capability);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void install(final ExecutorService service) {
    final CompletableFuture<Void> ytDlpTask = CompletableFuture.runAsync(this::installYTDLP, service);
    final CompletableFuture<Void> vlcTask = CompletableFuture.runAsync(this::installVLC, service);
    final CompletableFuture<Void> qemuTask = CompletableFuture.runAsync(this::installQemu, service);
    final CompletableFuture<Void> miscTask = CompletableFuture.runAsync(this::loadMisc, service);
    CompletableFuture.allOf(ytDlpTask, vlcTask, qemuTask, miscTask).join();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    MediaPlayerFactoryProvider.shutdown();
  }

  private void installQemu() {
    try {
      if (!this.hasCapability(Capability.QEMU)) {
        return;
      }
      QemuInstaller.create().download(true);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  private void loadMisc() {
    if (!this.hasCapability(Capability.FFMPEG)) {
      return;
    }
    Loader.load(opencv_java.class);
  }

  private void installVLC() {
    try {
      if (!this.hasCapability(Capability.VLC)) {
        return;
      }
      VLCInstallationKit.create().start();
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  private void installYTDLP() {
    try {
      if (!this.hasCapability(Capability.YTDLP)) {
        return;
      }
      YTDLPInstaller.create().download(true);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }
}
