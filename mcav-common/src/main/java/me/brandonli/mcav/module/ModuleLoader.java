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
package me.brandonli.mcav.module;

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, starts, and stops the modules of the library.
 *
 * <p>Modules are created through their no-argument constructor, which may be package-private, so module classes
 * can hide their constructor from library users. Modules are started in the order they were requested and stopped
 * in reverse order. Loading, shutdown and the ordered snapshot query are serialized on the loader.
 * Individual module lookup is concurrent and can observe only modules whose startup has finished; a lookup
 * racing with shutdown can return a module that is being stopped. The caller owns the loader and must call
 * {@link #shutdownModules()} when its modules are no longer needed.
 */
public final class ModuleLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(ModuleLoader.class);

  private static final String STARTING_MODULE = "Starting module {}";

  private static final String STOP_AFTER_FAILED_START_FAILED = "Module {} failed to stop after it failed to start";

  private static final String STOP_FAILED = "Module {} failed to stop";

  private final Map<Class<?>, MCAVModule> modules;
  private final List<MCAVModule> startOrder;

  /**
   * Constructs a new loader without any modules. {@link me.brandonli.mcav.MCAV} creates one for you.
   */
  public ModuleLoader() {
    this.modules = new ConcurrentHashMap<>();
    this.startOrder = new ArrayList<>();
  }

  /**
   * Gets a started module.
   *
   * @param moduleClass the class of the module
   * @param <T>         the type of the module
   * @return the module
   * @throws ModuleException if no module of that class was started
   * @throws NullPointerException if {@code moduleClass} is null
   */
  public <T extends MCAVModule> T getModule(final Class<T> moduleClass) {
    Preconditions.checkNotNull(moduleClass, "Module class must not be null");
    final MCAVModule module = this.modules.get(moduleClass);
    if (module == null) {
      final String name = moduleClass.getSimpleName();
      final String message = "Module %s is not installed".formatted(name);
      throw new ModuleException(message);
    }
    return moduleClass.cast(module);
  }

  /**
   * Creates and starts the specified modules. Every class is checked before the first module is created, so an
   * invalid class never leaves some of the modules started. Classes whose module was started before are skipped.
   *
   * <p>A module that fails to start is stopped again right away, so it can release whatever it acquired before
   * failing. If a module cannot be created or fails to start, the modules started before it stay started; stop them
   * with {@link #shutdownModules()}.
   *
   * @param moduleClasses the non-null array of non-null module classes implementing {@link MCAVModule};
   *                      each needs an accessible no-argument constructor, duplicates are skipped
   * @throws ModuleException if a class is not a module, cannot be created, or fails to start
   * @throws NullPointerException if the array or a module class is null
   */
  public synchronized void loadModules(final Class<?>... moduleClasses) {
    Preconditions.checkNotNull(moduleClasses, "Module classes must not be null");
    for (final Class<?> moduleClass : moduleClasses) {
      Preconditions.checkNotNull(moduleClass, "Module class must not be null");
      final boolean isModule = MCAVModule.class.isAssignableFrom(moduleClass);
      if (!isModule) {
        final String name = moduleClass.getSimpleName();
        final String message = "%s does not implement MCAVModule".formatted(name);
        throw new ModuleException(message);
      }
    }
    final MethodType constructorType = MethodType.methodType(void.class);
    final MethodHandles.Lookup lookup = MethodHandles.lookup();
    for (final Class<?> moduleClass : moduleClasses) {
      final boolean alreadyLoaded = this.modules.containsKey(moduleClass);
      if (alreadyLoaded) {
        continue;
      }
      final MCAVModule module = createModule(moduleClass, lookup, constructorType);
      startModule(module);
      this.modules.put(moduleClass, module);
      this.startOrder.add(module);
    }
  }

  private static void startModule(final MCAVModule module) {
    final String moduleName = module.getModuleName();
    LOGGER.info(STARTING_MODULE, moduleName);
    try {
      module.start();
    } catch (final ModuleException exception) {
      stopAfterFailedStart(module, moduleName, exception);
      throw exception;
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      final String reason = exception.getMessage();
      final String message = "Module %s failed to start: %s".formatted(moduleName, reason);
      final ModuleException failure = new ModuleException(message, exception);
      stopAfterFailedStart(module, moduleName, failure);
      throw failure;
    }
  }

  /**
   * Stops a module whose start failed, so whatever it acquired before failing is released. A failure to stop is
   * logged and attached to the start failure instead of replacing it.
   */
  private static void stopAfterFailedStart(final MCAVModule module, final String moduleName, final ModuleException startFailure) {
    try {
      module.stop();
    } catch (final RuntimeException | Error stopFailure) {
      // a module is foreign code, so a failed stop must not replace the start failure; only a virtual machine error,
      // which no module can cause or recover from, is too severe to be attached
      ThrowableUtils.throwIfFatal(stopFailure);
      LOGGER.error(STOP_AFTER_FAILED_START_FAILED, moduleName, stopFailure);
      final Equivalence<Object> identity = Equivalence.identity();
      if (!identity.equivalent(stopFailure, startFailure)) {
        startFailure.addSuppressed(stopFailure);
      }
    }
  }

  private static MCAVModule createModule(final Class<?> moduleClass, final MethodHandles.Lookup lookup, final MethodType constructorType) {
    try {
      final MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(moduleClass, lookup);
      final MethodHandle constructor = privateLookup.findConstructor(moduleClass, constructorType);
      final Object instance = constructor.invoke();
      return (MCAVModule) instance;
    } catch (final Throwable throwable) {
      // invoking a method handle throws Throwable, so everything a constructor throws becomes a module failure except a
      // virtual machine error, which must reach the caller unwrapped
      ThrowableUtils.throwIfFatal(throwable);
      final String name = moduleClass.getSimpleName();
      final String reason = throwable.getMessage();
      final String message = "Module %s could not be created: %s".formatted(name, reason);
      throw new ModuleException(message, throwable);
    }
  }

  /**
   * Stops started modules in reverse start order and clears the registry. Recoverable exceptions or errors
   * thrown by a module's {@code stop} callback are logged so remaining modules can be stopped. Fatal VM errors
   * propagate immediately. Calling this again after successful shutdown does nothing.
   */
  public synchronized void shutdownModules() {
    final List<MCAVModule> reversed = new ArrayList<>(this.startOrder);
    Collections.reverse(reversed);
    for (final MCAVModule module : reversed) {
      final String moduleName = module.getModuleName();
      try {
        module.stop();
      } catch (final RuntimeException | Error exception) {
        // one broken module, even one failing an assertion or a native call, must not keep the others running; only a
        // virtual machine error stops the shutdown, because nothing can be released reliably after it
        ThrowableUtils.throwIfFatal(exception);
        LOGGER.error(STOP_FAILED, moduleName, exception);
      }
    }
    this.startOrder.clear();
    this.modules.clear();
  }

  /**
   * Gets every started module.
   *
   * @return an unmodifiable snapshot of started modules in start order; module instances are borrowed
   *         and later shutdown may stop them
   */
  public synchronized Collection<MCAVModule> getModules() {
    return List.copyOf(this.startOrder);
  }
}
