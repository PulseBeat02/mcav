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
package me.brandonli.mcav.bukkit.media.mcv2;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Binding;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Natives;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Resolution;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Kernels;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The native kernels for the tests: the library of this platform, loaded once from the jar's resources into the build
 * folder, and its kernels at every level this processor runs. Where the jar ships a library for the platform it must
 * load, or every test that asks for it fails; only a platform recorded as running Java runs the tests with
 * {@code -Dmcv2.native.expect=java}, and there, as where the jar has no library, there are no levels to test.
 */
final class NativeTesting {

  /** The system property that records a platform whose library does not load, so its tests expect Java. */
  private static final String EXPECT = "mcv2.native.expect";

  /** Where the tests extract the library: the module's build folder. */
  static final Path FOLDER = Path.of("build", "mcv2-natives");

  private static @Nullable Resolution loaded;

  private NativeTesting() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Gets whether this platform should run the native kernels: the jar has its library, and no one recorded it as a Java
   * platform.
   *
   * @return whether the native kernels must load
   */
  static boolean expected() {
    final String platform = Natives.platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    return !"java".equals(System.getProperty(EXPECT)) && platform != null && MCV2.NATIVE_DIGESTS.containsKey(platform);
  }

  /**
   * Loads the library of this platform once, failing where it should load and does not.
   *
   * @return the resolution, native where expected, else Java
   * @throws IllegalStateException if the library should load and did not
   */
  static synchronized Resolution resolution() {
    Resolution current = loaded;
    if (current == null) {
      current = Natives.resolve(
        MCV2.NATIVE_AUTO,
        Natives.platform(System.getProperty("os.name", ""), System.getProperty("os.arch", "")),
        FOLDER,
        platform -> Natives.read(MCV2.class.getResourceAsStream("/mcav/mcv2/natives/" + platform + "/" + Natives.libraryName(platform))),
        null
      );
      if (expected() && current.binding() == null) {
        throw new IllegalStateException("The MCV2 native kernels must load here and did not: " + current.description());
      }
      loaded = current;
    }
    return current;
  }

  /**
   * Gets the levels this processor runs, best first, or none where no library loads.
   *
   * @return the levels
   */
  static List<Level> levels() {
    final Resolution resolution = resolution();
    final List<Level> levels = new ArrayList<>();
    for (final Level level : Level.values()) {
      if (resolution.binding() != null && level.isIn(resolution.levels())) {
        levels.add(level);
      }
    }
    return levels;
  }

  /**
   * Gets the loaded library.
   *
   * @return its symbols
   * @throws IllegalStateException if no library loads here
   */
  @SuppressWarnings("restricted")
  static SymbolLookup library() {
    if (resolution().binding() == null) {
      throw new IllegalStateException("No MCV2 native library loads on this platform");
    }
    final String platform = Objects.requireNonNull(Natives.platform(System.getProperty("os.name", ""), System.getProperty("os.arch", "")));
    final String digest = MCV2.NATIVE_DIGESTS.get(platform);
    return SymbolLookup.libraryLookup(FOLDER.resolve("mcv2kernels-" + digest + "-" + Natives.libraryName(platform)), Arena.global());
  }

  /**
   * Makes kernels at one level.
   *
   * @param level a level the processor runs
   * @return the kernels
   */
  static NativeKernels kernels(final Level level) {
    return kernels(Binding.of(library(), level));
  }

  static NativeKernels kernels(final Binding binding) {
    return view(create(binding));
  }

  private static Object create(final Binding binding) {
    return Mcv2Internals.construct(Mcv2Internals.nested("NativeKernels"), new Class<?>[] { Binding.class }, binding);
  }

  /**
   * Gets the factory of the kernels at one level, for an encoder.
   *
   * @param level a level the processor runs
   * @return the factory
   */
  static Supplier<?> factory(final Level level) {
    final Binding binding = Binding.of(library(), level);
    return () -> create(binding);
  }

  static Supplier<?> factory(final Resolution resolution) {
    return (Supplier<?>) Mcv2Internals.invoke(Resolution.class, resolution, "factory", new Class<?>[0]);
  }

  static Supplier<?> javaFactory() {
    return factory(Resolution.java("test", false));
  }

  interface NativeKernels extends Kernels {
    Level level();
    boolean keeps(Object array);
  }

  static NativeKernels view(final Object kernels) {
    final Class<?> type = Mcv2Internals.nested("NativeKernels");
    return (NativeKernels) Proxy.newProxyInstance(
      NativeKernels.class.getClassLoader(),
      new Class<?>[] { NativeKernels.class },
      (_, method, arguments) -> {
        if (method.getName().equals("level")) {
          return ((Binding) Mcv2Internals.field(type, kernels, "binding")).level();
        }
        if (method.getName().equals("keeps")) {
          final Object array = Objects.requireNonNull(arguments)[0];
          final int slot = (int) Mcv2Internals.invoke(type, null, "slot", new Class<?>[] { Object.class }, array);
          return Mcv2Internals.invoke(type, kernels, "remembers", new Class<?>[] { int.class, Object.class }, slot, array);
        }
        return Mcv2Internals.call(
          type,
          kernels,
          method.getName(),
          method.getParameterTypes(),
          arguments == null ? new Object[0] : arguments
        );
      }
    );
  }

  static MCV2 encoder(final Settings settings, final int threads, final boolean verify, final Supplier<?> factory) {
    final MCV2 encoder = new MCV2(settings, ForkJoinPool.commonPool(), threads, verify);
    try {
      final Field kernels = MCV2.class.getDeclaredField("kernels");
      kernels.setAccessible(true);
      kernels.set(encoder, factory);
    } catch (final ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }
    return encoder;
  }
}
