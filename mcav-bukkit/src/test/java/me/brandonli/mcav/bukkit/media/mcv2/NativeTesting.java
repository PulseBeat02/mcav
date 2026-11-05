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

import static java.util.Objects.requireNonNull;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Binding;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Level;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Natives;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Resolution;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Kernels;
import org.checkerframework.checker.nullness.qual.Nullable;

final class NativeTesting {

  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String OS_ARCHITECTURE_PROPERTY = "os.arch";

  private static final String NATIVES_RESOURCE_FOLDER = "/mcav/mcv2/natives/";
  private static final String PATH_SEPARATOR = "/";

  private static final String NATIVE_KERNELS_CLASS = "NativeKernels";

  // "java" on a platform whose library does not load, so its tests expect Java
  private static final String EXPECTATION_PROPERTY = "mcv2.native.expect";

  private static final String JAVA_EXPECTATION = "java";

  static final Path NATIVES_FOLDER = Path.of("build", "mcv2-natives");

  private static @Nullable Resolution loaded;

  private NativeTesting() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  static boolean areNativesExpected() {
    final @Nullable String platform = findPlatform();
    return (
      !JAVA_EXPECTATION.equals(System.getProperty(EXPECTATION_PROPERTY)) && platform != null && Mcv2.NATIVE_DIGESTS.containsKey(platform)
    );
  }

  private static @Nullable String findPlatform() {
    return Natives.platform(System.getProperty(OS_NAME_PROPERTY, ""), System.getProperty(OS_ARCHITECTURE_PROPERTY, ""));
  }

  static synchronized Resolution resolution() {
    final Resolution previous = loaded;
    if (previous != null) {
      return previous;
    }

    final @Nullable String platform = findPlatform();
    final Resolution current = Natives.resolve(Mcv2.NATIVE_AUTO, platform, NATIVES_FOLDER, NativeTesting::readLibrary, null);
    if (areNativesExpected() && current.binding() == null) {
      final String message = "The MCV2 native kernels did not load (%s) but must load on this platform!".formatted(current.description());
      throw new IllegalStateException(message);
    }

    loaded = current;

    return current;
  }

  private static byte @Nullable [] readLibrary(final String platform) {
    final String libraryName = Natives.libraryName(platform);
    final String resource = "%s%s%s%s".formatted(NATIVES_RESOURCE_FOLDER, platform, PATH_SEPARATOR, libraryName);
    return Natives.read(Mcv2.class.getResourceAsStream(resource));
  }

  static List<Level> levels() {
    final Resolution resolution = resolution();
    if (resolution.binding() == null) {
      return List.of();
    }

    final int levels = resolution.levels();
    return Arrays.stream(Level.values())
      .filter(level -> level.isIn(levels))
      .toList();
  }

  @SuppressWarnings("restricted") // a library lookup is a restricted method of the foreign function API
  static SymbolLookup library() {
    if (resolution().binding() == null) {
      final String message = "No MCV2 native library loads on this platform!";
      throw new IllegalStateException(message);
    }

    final String platform = requireNonNull(findPlatform());
    final String digest = Mcv2.NATIVE_DIGESTS.get(platform);
    final String libraryName = Natives.libraryName(platform);
    final String fileName = "mcv2kernels-%s-%s".formatted(digest, libraryName);
    final Path path = NATIVES_FOLDER.resolve(fileName);
    return SymbolLookup.libraryLookup(path, Arena.global());
  }

  static NativeKernels kernels(final Level level) {
    return kernels(Binding.of(library(), level));
  }

  static NativeKernels kernels(final Binding binding) {
    return view(create(binding));
  }

  private static Object create(final Binding binding) {
    return Mcv2Internals.construct(Mcv2Internals.nested(NATIVE_KERNELS_CLASS), new Class<?>[] { Binding.class }, binding);
  }

  static Supplier<?> factory(final Level level) {
    final Binding binding = Binding.of(library(), level);
    return () -> create(binding);
  }

  static Supplier<?> factory(final Resolution resolution) {
    return (Supplier<?>) Mcv2Internals.callForResult(Resolution.class, resolution, "factory", new Class<?>[0]);
  }

  static Supplier<?> javaFactory() {
    return factory(Resolution.java("test"));
  }

  static NativeKernels view(final Object kernels) {
    final Class<?> type = Mcv2Internals.nested(NATIVE_KERNELS_CLASS);
    return (NativeKernels) Proxy.newProxyInstance(
      NativeKernels.class.getClassLoader(),
      new Class<?>[] { NativeKernels.class },
      (_, method, arguments) -> invokeView(type, kernels, method, arguments)
    );
  }

  private static @Nullable Object invokeView(
    final Class<?> type,
    final Object kernels,
    final Method method,
    final @Nullable Object @Nullable [] arguments
  ) {
    if (method.getName().equals("getLevel")) {
      return ((Binding) Mcv2Internals.field(type, kernels, "binding")).getLevel();
    }

    if (method.getName().equals("keeps")) {
      final @Nullable Object[] values = requireNonNull(arguments);
      final @Nullable Object array = values[0];
      final int slot = (int) Mcv2Internals.callForResult(type, null, "slot", new Class<?>[] { Object.class }, array);
      return Mcv2Internals.callForResult(type, kernels, "remembers", new Class<?>[] { int.class, Object.class }, slot, array);
    }

    return Mcv2Internals.call(type, kernels, method.getName(), method.getParameterTypes(), arguments == null ? new Object[0] : arguments);
  }

  static Mcv2 encoder(final Settings settings, final int threads, final Mcv2.Verification verification, final Supplier<?> factory) {
    final Mcv2 encoder = new Mcv2(settings, ForkJoinPool.commonPool(), threads, verification);
    try {
      final Field kernels = Mcv2.class.getDeclaredField("kernels");
      kernels.setAccessible(true);
      kernels.set(encoder, factory);
    } catch (final ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }

    return encoder;
  }

  interface NativeKernels extends Kernels {
    Level getLevel();

    boolean keeps(Object array);
  }
}
