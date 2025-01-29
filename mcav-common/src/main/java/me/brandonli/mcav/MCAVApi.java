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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.capability.Capability;

/**
 * Represents the main API interface for handling media playback capabilities,
 * installation, and resource management in the MCAV library.
 */
public interface MCAVApi {
  /**
   * Checks whether the specified capability is supported and enabled.
   *
   * @param capability the capability to check
   * @return true if the capability is supported and enabled, false otherwise
   */
  boolean hasCapability(final Capability capability);

  /**
   * Installs all dependencies and resources required for the library's functionality.
   * This method initializes the installation of tools and components such as VLC, QEMU,
   * YTDLP, and other required dependencies, based on the capabilities enabled in the library.
   * <p>
   * If any errors occur during the installation process, an exception is thrown to indicate
   * failure and halt further installations.
   * <p>
   * It is recommended to invoke this method before attempting to utilize any
   * capabilities provided by the library.
   */
  void install();

  /**
   * Asynchronously installs the required resources and dependencies for the library.
   * This method utilizes a default executor service, specifically the {@link ForkJoinPool#commonPool()},
   * to execute the installation process in an asynchronous manner.
   *
   * @return a {@link CompletableFuture} that completes when the installation process has finished.
   */
  default CompletableFuture<Void> installAsync() {
    return this.installAsync(ForkJoinPool.commonPool());
  }

  /**
   * Executes the {@link #install()} method asynchronously using the specified {@link ExecutorService}.
   * The asynchronous operation allows for the installation process to run in a separate thread,
   * enabling non-blocking behavior.
   *
   * @param service the {@link ExecutorService} to be used for running the asynchronous installation
   *                process. This allows the caller to control the thread-pool configuration, such
   *                as the number of threads and execution policy.
   * @return a {@link CompletableFuture} that completes when the {@link #install()} method has
   * finished execution, or completes exceptionally if an error occurs during installation.
   */
  default CompletableFuture<Void> installAsync(final ExecutorService service) {
    return CompletableFuture.runAsync(this::install, service);
  }

  /**
   * Releases resources and performs cleanup operations as required by the library.
   * <p>
   * This method ensures that all components and services initialized by the API,
   * such as media player factories or any background processes, are properly
   * shut down. It is essential to invoke this method when the library's functionalities
   * are no longer needed to prevent resource leaks.
   */
  void release();
}
