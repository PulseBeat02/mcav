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
package me.brandonli.mcav.media.mcv2.encode;

import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The native kernels for the tests: the library of this platform, loaded once from the jar's resources into the build
 * folder, and its kernels at every level this processor runs. Where the jar ships a library for the platform it must
 * load, or every test that asks for it fails; only a platform recorded as running Java runs the tests with
 * {@code -Dmcv2.native.expect=java}, and there, as where the jar has no library, there are no levels to test.
 */
final class NativeTesting {

  /** The system property that records a platform whose library does not load, so its tests expect Java. */
  static final String EXPECT = "mcv2.native.expect";

  /** Where the tests extract the library: the module's build folder. */
  static final Path FOLDER = Path.of("build", "mcv2-natives");

  private static Mcv2Natives.@Nullable Resolution loaded;

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
    final String platform = Mcv2Natives.platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    return !"java".equals(System.getProperty(EXPECT)) && platform != null && Mcv2Natives.DIGESTS.containsKey(platform);
  }

  /**
   * Loads the library of this platform once, failing where it should load and does not.
   *
   * @return the resolution, native where expected, else Java
   * @throws IllegalStateException if the library should load and did not
   */
  static synchronized Mcv2Natives.Resolution resolution() {
    Mcv2Natives.Resolution current = loaded;
    if (current == null) {
      current = Mcv2Natives.resolve(
        Mcv2Natives.AUTO,
        Mcv2Natives.platform(System.getProperty("os.name", ""), System.getProperty("os.arch", "")),
        FOLDER,
        platform ->
          Mcv2Natives.read(Mcv2Natives.class.getResourceAsStream("natives/" + platform + "/" + Mcv2Natives.libraryName(platform))),
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
  static List<NativeKernels.Level> levels() {
    final Mcv2Natives.Resolution resolution = resolution();
    final List<NativeKernels.Level> levels = new ArrayList<>();
    for (final NativeKernels.Level level : NativeKernels.Level.values()) {
      if (resolution.binding() != null && level.in(resolution.levels())) {
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
  static SymbolLookup library() {
    final SymbolLookup library = resolution().library();
    if (library == null) {
      throw new IllegalStateException("No MCV2 native library loads on this platform");
    }
    return library;
  }

  /**
   * Makes kernels at one level.
   *
   * @param level a level the processor runs
   * @return the kernels
   */
  static NativeKernels kernels(final NativeKernels.Level level) {
    return new NativeKernels(NativeKernels.Binding.of(library(), level));
  }

  /**
   * Gets the factory of the kernels at one level, for an encoder.
   *
   * @param level a level the processor runs
   * @return the factory
   */
  static Kernels.Factory factory(final NativeKernels.Level level) {
    final NativeKernels.Binding binding = NativeKernels.Binding.of(library(), level);
    return () -> new NativeKernels(binding);
  }
}
