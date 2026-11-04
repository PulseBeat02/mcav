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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

final class Mcv2Internals {

  static final Class<?> KERNELS_TYPE = nested("Kernels");
  static final Class<?> JAVA_KERNELS_TYPE = nested("JavaKernels");

  private Mcv2Internals() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  static Class<?> nested(final String name) {
    final String message = "Nested class Mcv2.%s is missing!".formatted(name);
    return Arrays.stream(Mcv2.class.getDeclaredClasses())
      .filter(type -> type.getSimpleName().equals(name))
      .findFirst()
      .orElseThrow(() -> new LinkageError(message));
  }

  static Object construct(final Class<?> type, final Class<?>[] parameters, final @Nullable Object... arguments) {
    try {
      final Constructor<?> constructor = type.getDeclaredConstructor(parameters);
      constructor.setAccessible(true);
      return constructor.newInstance(arguments);
    } catch (final InvocationTargetException exception) {
      throw unwrapCause(exception);
    } catch (final ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }
  }

  static @Nullable Object call(
    final Class<?> type,
    final @Nullable Object target,
    final String name,
    final Class<?>[] parameters,
    final @Nullable Object... arguments
  ) {
    try {
      final Method method = type.getDeclaredMethod(name, parameters);
      method.setAccessible(true);
      return method.invoke(target, arguments);
    } catch (final InvocationTargetException exception) {
      throw unwrapCause(exception);
    } catch (final ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }
  }

  static Object callForResult(
    final Class<?> type,
    final @Nullable Object target,
    final String name,
    final Class<?>[] parameters,
    final @Nullable Object... arguments
  ) {
    final @Nullable Object result = call(type, target, name, parameters, arguments);
    return requireNonNull(result);
  }

  static Object field(final Class<?> type, final @Nullable Object target, final String name) {
    try {
      final Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      final @Nullable Object result = field.get(target);
      return requireNonNull(result);
    } catch (final ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }
  }

  private static RuntimeException unwrapCause(final InvocationTargetException exception) {
    final Throwable cause = exception.getCause();
    if (cause instanceof final RuntimeException runtimeException) {
      return runtimeException;
    }

    if (cause instanceof final Error error) {
      throw error;
    }

    throw new AssertionError(cause);
  }

  static Kernels javaKernels() {
    final Object target = construct(JAVA_KERNELS_TYPE, new Class<?>[0]);
    final ClassLoader loader = Kernels.class.getClassLoader();
    return (Kernels) Proxy.newProxyInstance(loader, new Class<?>[] { Kernels.class }, (_, method, arguments) ->
      call(KERNELS_TYPE, target, method.getName(), method.getParameterTypes(), arguments == null ? new Object[0] : arguments)
    );
  }

  static final class Workers {

    static final Class<?> TYPE = nested("Workers");

    private static final int SEQUENTIAL_THREADS = 1;
    static final Workers SEQUENTIAL = new Workers(new ForkJoinPool(SEQUENTIAL_THREADS), SEQUENTIAL_THREADS);

    private final Object value;

    Workers(final ForkJoinPool pool, final int threads) {
      this.value = construct(TYPE, new Class<?>[] { ForkJoinPool.class, int.class }, pool, threads);
    }

    int threads() {
      return (int) field(TYPE, this.value, "threads");
    }

    <ScratchType> void forEach(final int count, final Supplier<ScratchType> factory, final ObjIntConsumer<ScratchType> action) {
      call(TYPE, this.value, "forEach", new Class<?>[] { int.class, Supplier.class, ObjIntConsumer.class }, count, factory, action);
    }
  }

  static final class MotionLambda {

    private static final Class<?> TYPE = nested("MotionLambda");

    static final double KNEE = (double) field(TYPE, null, "KNEE");
    static final double EXPONENT = (double) field(TYPE, null, "EXPONENT");
    static final double MAXIMUM_RAISE = (double) field(TYPE, null, "MAXIMUM_RAISE");
    static final double SMOOTHING = (double) field(TYPE, null, "SMOOTHING");

    private final Object value;

    MotionLambda() {
      this.value = construct(TYPE, new Class<?>[0]);
    }

    double lambda(final double base) {
      return (double) callForResult(TYPE, this.value, "lambda", new Class<?>[] { double.class }, base);
    }

    double average() {
      return (double) field(TYPE, this.value, "motion");
    }

    void add(final double motion) {
      call(TYPE, this.value, "add", new Class<?>[] { double.class }, motion);
    }

    void observe(final byte[] rgb, final int width, final int height, final Workers workers) {
      call(
        TYPE,
        this.value,
        "observe",
        new Class<?>[] { byte[].class, int.class, int.class, Workers.TYPE },
        rgb,
        width,
        height,
        workers.value
      );
    }

    static double raise(final double motion) {
      return (double) callForResult(TYPE, null, "raise", new Class<?>[] { double.class }, motion);
    }

    static int[] blurredLuma(
      final byte[] rgb,
      final int width,
      final int height,
      final Workers workers,
      final int[] sampled,
      final int[] blurred
    ) {
      return (int[]) callForResult(
        TYPE,
        null,
        "blurredLuma",
        new Class<?>[] { byte[].class, int.class, int.class, Workers.TYPE, int[].class, int[].class },
        rgb,
        width,
        height,
        workers.value,
        sampled,
        blurred
      );
    }

    static double temporalInformation(final int[] current, final int[] previous, final Workers workers) {
      return (double) callForResult(
        TYPE,
        null,
        "temporalInformation",
        new Class<?>[] { int[].class, int[].class, Workers.TYPE },
        current,
        previous,
        workers.value
      );
    }
  }

  interface Kernels {
    void start(int[] source, double rate, double limit);

    long getDistortion();

    boolean predicted(int[] prediction, int size, int[] output);

    boolean solid(int color, int size, int[] output);

    boolean palette(byte[] record, int size, int[] output);

    boolean compact(int[] prediction, byte[] record, int quantizer, int size, int[] output);

    void predict(byte[] reference, int width, int height, int left, int top, int size, int motionX, int motionY, int[] output);

    void fit(float[] values, int size, float[] output);

    void cluster(int[] source, int size, int[] endpoints);

    void finishPalette(int[] source, int count, int[] endpoints, int[] colors, byte[] selectors);

    boolean finishPattern(int[] source, int size, int[] endpoints, int[] colors, byte[] selectors);

    int seeded(byte[] reference, int width, int height, int[] source, int left, int top, int size, int range, int[] seeds);

    void loadSource(byte[] image, int width, int height, int left, int top, int size, int[] source);

    void halve(int[] block, int size, int[] output);

    void residualTarget(int[] source, int[] prediction, int count, float[] target);

    default void forgetArrays() {}
  }
}
