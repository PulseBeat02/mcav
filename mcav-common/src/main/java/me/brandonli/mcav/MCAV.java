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
package me.brandonli.mcav;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.loader.DependencyLoader;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import me.brandonli.mcav.module.MCAVModule;
import me.brandonli.mcav.module.ModuleLoader;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default implementation of {@link MCAVApi}. Create instances with {@link #api()}.
 *
 * <p>{@link #install(Class[])} loads the JavaCV natives and builds the lookup tables while it starts the modules, in
 * parallel, and returns once all of them are done. If one of them fails, the modules that were already started are
 * stopped again and the installation may be retried. The external programs VLC and yt-dlp are then prepared in the
 * background by a {@link BackgroundInstallation}, and their capabilities turn on as soon as they are ready.
 *
 * <p>Installing and releasing never overlap: {@link #release()} waits for an installation that is still running on
 * another thread, so it can never stop modules while they are being started. It cancels the background preparation
 * of VLC and yt-dlp instead of waiting for it, which never runs into the lock both methods share.
 */
public final class MCAV implements MCAVApi {

  private static final Logger LOGGER = LoggerFactory.getLogger(MCAV.class);

  private final DependencyLoader dependencyLoader;
  private final ModuleLoader moduleLoader;
  private final CapabilityGuard guard;
  private final AtomicBoolean installing;
  private final Object lifecycleLock;

  // written under the lifecycle lock and read without it, so it is volatile
  private volatile @Nullable BackgroundInstallation background;

  MCAV() {
    final DependencyLoader defaultDependencyLoader = new DependencyLoader();
    final ModuleLoader defaultModuleLoader = new ModuleLoader();
    final CapabilityGuard sharedGuard = CapabilityGuard.shared();
    this(defaultDependencyLoader, defaultModuleLoader, sharedGuard);
  }

  /**
   * Creates an instance that installs through the specified loaders.
   *
   * @param dependencyLoader loads the natives and installs the optional programs
   * @param moduleLoader     starts and stops the modules
   * @param guard            records which programs are still being prepared, for the features that consult it
   */
  @VisibleForTesting
  MCAV(final DependencyLoader dependencyLoader, final ModuleLoader moduleLoader, final CapabilityGuard guard) {
    this.dependencyLoader = dependencyLoader;
    this.moduleLoader = moduleLoader;
    this.guard = guard;
    this.installing = new AtomicBoolean(false);
    this.lifecycleLock = new Object();
  }

  /**
   * Creates a new, uninstalled instance of the library. Call {@link MCAVApi#install(Class[])} before using it.
   *
   * @return a new instance of the library
   */
  public static MCAVApi api() {
    return new MCAV();
  }

  @Override
  public boolean hasCapability(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null");
    final BackgroundInstallation installation = this.requireInstalled();
    return installation.isAvailable(capability);
  }

  @Override
  public CompletableFuture<Boolean> whenCapabilityReady(final Capability capability) {
    Preconditions.checkNotNull(capability, "Capability must not be null");
    final BackgroundInstallation installation = this.requireInstalled();
    return installation.whenReady(capability);
  }

  private BackgroundInstallation requireInstalled() {
    final BackgroundInstallation installation = this.background;
    if (installation == null) {
      throw new MCAVLoadingException("MCAV has not been installed yet, call install() first");
    }
    return installation;
  }

  @Override
  public void install(final Class<?>... modules) {
    Preconditions.checkNotNull(modules, "Modules must not be null");
    // claimed atomically, so two threads installing at the same time cannot both run the installation
    final boolean claimed = this.installing.compareAndSet(false, true);
    if (!claimed) {
      throw new MCAVLoadingException("MCAV has already been installed");
    }
    // release() takes the same lock, so it waits until the modules have been started or the installation failed
    synchronized (this.lifecycleLock) {
      this.runInstallation(modules);
      final BackgroundInstallation installation = new BackgroundInstallation(this.dependencyLoader, this.guard);
      installation.start();
      this.background = installation;
    }
  }

  private void runInstallation(final Class<?>[] modules) {
    final CompletableFuture<Void> natives = CompletableFuture.runAsync(this::loadNatives);
    final CompletableFuture<Void> loadedModules = CompletableFuture.runAsync(() -> this.moduleLoader.loadModules(modules));
    final CompletableFuture<Void> all = CompletableFuture.allOf(natives, loadedModules);
    try {
      all.join();
    } catch (final CompletionException exception) {
      // every step has finished once join() throws, so no module can still be starting
      this.moduleLoader.shutdownModules();
      this.installing.set(false);
      final Throwable cause = exception.getCause();
      final Throwable failure = cause == null ? exception : cause;
      final String message = failure.getMessage();
      throw new MCAVLoadingException("MCAV failed to install: " + message, failure);
    }
  }

  private void loadNatives() {
    this.dependencyLoader.loadModules();
    this.loadPalette();
    // the ImageIO disk cache only slows down the many small images the library decodes
    ImageIO.setUseCache(false);
  }

  private void loadPalette() {
    LOGGER.info("Building the map color lookup table...");
    final long start = System.currentTimeMillis();
    DitherPalette.init();
    final long end = System.currentTimeMillis();
    LOGGER.info("Map color lookup table built in {} ms", end - start);
  }

  @Override
  public void release() {
    synchronized (this.lifecycleLock) {
      final BackgroundInstallation installation = this.background;
      if (installation == null) {
        return;
      }
      this.background = null;
      // the installation threads never take the lifecycle lock, so waiting for them here cannot deadlock
      installation.cancel();
      this.moduleLoader.shutdownModules();
      this.installing.set(false);
    }
  }

  @Override
  public <T extends MCAVModule> T getModule(final Class<T> moduleClass) {
    Preconditions.checkNotNull(moduleClass, "Module class must not be null");
    return this.moduleLoader.getModule(moduleClass);
  }
}
