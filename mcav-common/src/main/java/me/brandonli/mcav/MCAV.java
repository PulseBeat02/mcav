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
 *
 * <p>While MCAV is installed, a {@link NativeHeapTrimmer} hands the memory the C library keeps free back to the system
 * once a minute, on Linux.
 *
 * <p>This class is thread-safe.
 *
 * {@snippet :
 * final MCAVApi api = MCAV.api();
 * api.install();
 * // at shutdown
 * api.release();
 * }
 *
 * @since 1.0.0
 */
public final class MCAV implements MCAVApi {

  private static final Logger LOGGER = LoggerFactory.getLogger(MCAV.class);

  private static final String CAPABILITY_NULL_MESSAGE = "Capability must not be null!";

  private final DependencyLoader dependencyLoader;
  private final ModuleLoader moduleLoader;
  private final CapabilityGuard guard;
  private final Runnable buildPalette;
  private final AtomicBoolean installing;
  private final Object lifecycleLock;
  private final NativeHeapTrimmer trimmer;

  private volatile @Nullable BackgroundInstallation background;

  private MCAV() {
    final DependencyLoader defaultDependencyLoader = new DependencyLoader();
    final ModuleLoader defaultModuleLoader = new ModuleLoader();
    final CapabilityGuard sharedGuard = CapabilityGuard.shared();
    this(defaultDependencyLoader, defaultModuleLoader, sharedGuard, DitherPalette::init);
  }

  // Takes the palette step, so a test sees install() build the lookup tables, which kills the mutant that skips DitherPalette.init().
  @VisibleForTesting
  MCAV(final DependencyLoader dependencyLoader, final ModuleLoader moduleLoader, final CapabilityGuard guard, final Runnable buildPalette) {
    this.dependencyLoader = dependencyLoader;
    this.moduleLoader = moduleLoader;
    this.guard = guard;
    this.buildPalette = buildPalette;
    this.installing = new AtomicBoolean(false);
    this.lifecycleLock = new Object();
    this.trimmer = NativeHeapTrimmer.create();
  }

  /**
   * Creates a new, uninstalled instance of the library. Call {@link MCAVApi#install(Class[])} before using it.
   *
   * @return a new instance of the library
   * @since 1.0.0
   */
  public static MCAVApi api() {
    return new MCAV();
  }

  /**
   * Returns whether an optional capability is available right now in this installation, which depends on the platform and on whether the
   * program behind it could be installed. A capability that is still being prepared in the background is not available yet; wait for it
   * with {@link #whenCapabilityReady(Capability)}.
   *
   * @param capability the capability to check
   * @return true if the capability is available
   * @throws MCAVLoadingException if the library has not been installed yet
   * @throws NullPointerException if {@code capability} is null
   * @since 1.0.0
   */
  @Override
  public boolean hasCapability(final Capability capability) {
    Preconditions.checkNotNull(capability, CAPABILITY_NULL_MESSAGE);

    final BackgroundInstallation installation = this.requireInstalled();
    return installation.isAvailable(capability);
  }

  /**
   * Returns a future that completes once a capability of this installation is ready, without polling. It completes with true when the
   * capability is available, and with false when it cannot be provided: the system does not support it, its installation failed, or {@link
   * #release()} cancelled the installation first. Once the future completed with true, {@link #hasCapability(Capability)} reports the
   * capability as available for that installation until release. The returned future is separate from the installation's internal future:
   * completing or cancelling it does not change or cancel preparation.
   *
   * <p>A capability that is not prepared in the background, or whose preparation already finished, gets a future
   * that is complete already. Callbacks attached to the future never run on the installation threads of the library,
   * so they may call any method of the library, including {@link #release()}.
   *
   * @param capability the capability to wait for
   * @return a future that completes with whether the capability is available
   * @throws MCAVLoadingException if the library has not been installed yet
   * @throws NullPointerException if {@code capability} is null
   * @since 1.0.0
   */
  @Override
  public CompletableFuture<Boolean> whenCapabilityReady(final Capability capability) {
    Preconditions.checkNotNull(capability, CAPABILITY_NULL_MESSAGE);

    final BackgroundInstallation installation = this.requireInstalled();
    return installation.whenReady(capability);
  }

  private BackgroundInstallation requireInstalled() {
    final BackgroundInstallation installation = this.background;
    if (installation == null) {
      final String message = "MCAV has not been installed yet, call install() first!";
      throw new MCAVLoadingException(message);
    }

    return installation;
  }

  /**
   * Installs the library and starts the specified modules on this instance. This method returns once the
   * JavaCV natives are loaded, the modules are started, and the lookup tables are built; VLC and yt-dlp are prepared in the background
   * afterward, see {@link #whenCapabilityReady(Capability)}.
   *
   * <p>It may be called once per release: calling it again while the library is installed, or while another thread
   * is installing it, fails. After {@link #release()}, or after an installation that failed, the library can be
   * installed again.
   *
   * <p>Installing calls {@link ImageIO#setUseCache(boolean)} with {@code false}, which applies to the whole JVM: every
   * library and plugin in it then caches image streams in memory instead of in temporary files.
   *
   * @param modules a non-null array of non-null module classes implementing {@link MCAVModule}, with
   *                accessible no-argument constructors; duplicates are started once and an empty array is allowed
   * @throws MCAVLoadingException if the library cannot be installed, is installed already, or is being installed by
   *                              another thread
   * @throws NullPointerException if {@code modules} is null
   * @since 1.0.0
   */
  @Override
  public void install(final Class<?>... modules) {
    Preconditions.checkNotNull(modules, "Modules must not be null!");

    final boolean claimed = this.installing.compareAndSet(false, true);
    if (!claimed) {
      final String message = "MCAV has already been installed!";
      throw new MCAVLoadingException(message);
    }

    // release() takes this lock and must wait for startup or installation failure.
    synchronized (this.lifecycleLock) {
      this.runInstallation(modules);
      final BackgroundInstallation installation = new BackgroundInstallation(this.dependencyLoader, this.guard);
      installation.start();
      this.background = installation;
      this.trimmer.start();
    }
  }

  private void runInstallation(final Class<?>[] modules) {
    final CompletableFuture<Void> natives = CompletableFuture.runAsync(this::loadNatives);
    final CompletableFuture<Void> loadedModules = CompletableFuture.runAsync(() -> this.moduleLoader.loadModules(modules));
    final CompletableFuture<Void> all = CompletableFuture.allOf(natives, loadedModules);
    try {
      all.join();
    } catch (final CompletionException exception) {
      this.moduleLoader.shutdownModules();
      this.installing.set(false);
      final Throwable cause = exception.getCause();
      final Throwable failure = cause == null ? exception : cause;
      final String reason = failure.getMessage();
      final String message = "Because of \"%s\", MCAV failed to install!".formatted(reason);
      throw new MCAVLoadingException(message, failure);
    }
  }

  private void loadNatives() {
    this.dependencyLoader.loadModules();
    this.loadPalette();
    ImageIO.setUseCache(false);
  }

  private void loadPalette() {
    LOGGER.info("Building the map color lookup table.");
    final long start = System.currentTimeMillis();
    this.buildPalette.run();
    final long end = System.currentTimeMillis();
    LOGGER.info("Map color lookup table built in {} ms.", end - start);
  }

  /**
   * Stops all modules this instance started and releases the resources of the library. Media players that are still running should be
   * released before calling this method.
   *
   * <p>If another thread is installing the library, this method waits for that installation to finish and then
   * releases it. A background preparation of VLC or yt-dlp that is still running is cancelled rather than waited for:
   * its download is interrupted, its temporary file is deleted, and its thread has ended when this method returns,
   * unless it ignores the interruption for more than ten seconds, which is logged. The futures of
   * {@link #whenCapabilityReady(Capability)} for the cancelled programs complete with false. Releasing a library that
   * is not installed does nothing.
   *
   * @since 1.0.0
   */
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
      this.trimmer.stop();
      this.installing.set(false);
    }
  }

  /**
   * Returns a module that {@link #install(Class[])} started, looked up by its class.
   *
   * @param moduleClass  the class of the module
   * @param <ModuleType> the type of the module
   * @return the module instance
   * @throws me.brandonli.mcav.module.ModuleException if the module was not installed
   * @throws NullPointerException                     if {@code moduleClass} is null
   * @since 1.0.0
   */
  @Override
  public <ModuleType extends MCAVModule> ModuleType getModule(final Class<ModuleType> moduleClass) {
    Preconditions.checkNotNull(moduleClass, "Module class must not be null!");

    return this.moduleLoader.getModule(moduleClass);
  }

  @VisibleForTesting
  boolean isTrimmingNativeHeap() {
    return this.trimmer.isTrimming();
  }
}
