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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Reaches the encoder's private members by reflection, so the tests need no wider encoder API. */
final class Mcv2Internals {

  private Mcv2Internals() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  static Class<?> nested(final String name) {
    return Arrays.stream(MCV2.class.getDeclaredClasses())
      .filter(type -> type.getSimpleName().equals(name))
      .findFirst()
      .orElseThrow();
  }

  static Object construct(final Class<?> type, final Class<?>[] parameters, final @Nullable Object... arguments) {
    try {
      final Constructor<?> constructor = type.getDeclaredConstructor(parameters);
      constructor.setAccessible(true);
      return constructor.newInstance(arguments);
    } catch (final InvocationTargetException exception) {
      throw failure(exception);
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
      throw failure(exception);
    } catch (final ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }
  }

  static Object invoke(
    final Class<?> type,
    final @Nullable Object target,
    final String name,
    final Class<?>[] parameters,
    final @Nullable Object... arguments
  ) {
    return Objects.requireNonNull(call(type, target, name, parameters, arguments));
  }

  static Object field(final Class<?> type, final @Nullable Object target, final String name) {
    try {
      final Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return Objects.requireNonNull(field.get(target));
    } catch (final ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }
  }

  private static RuntimeException failure(final InvocationTargetException exception) {
    final Throwable cause = exception.getCause();
    if (cause instanceof final RuntimeException runtime) {
      return runtime;
    }
    if (cause instanceof final Error error) {
      throw error;
    }
    throw new AssertionError(cause);
  }

  static final class Workers {

    private static final Class<?> TYPE = nested("Workers");
    static final Workers SEQUENTIAL = new Workers(new ForkJoinPool(1), 1);
    private final Object value;

    Workers(final ForkJoinPool pool, final int threads) {
      this.value = construct(TYPE, new Class<?>[] { ForkJoinPool.class, int.class }, pool, threads);
    }

    int threads() {
      return (int) invoke(TYPE, this.value, "threads", new Class<?>[0]);
    }

    <T> void forEach(final int count, final Supplier<T> factory, final ObjIntConsumer<T> action) {
      call(TYPE, this.value, "forEach", new Class<?>[] { int.class, Supplier.class, ObjIntConsumer.class }, count, factory, action);
    }
  }

  static final class MotionLambda {

    private static final Class<?> TYPE = nested("MotionLambda");
    static final double KNEE = (double) field(TYPE, null, "KNEE");
    static final double EXPONENT = (double) field(TYPE, null, "EXPONENT");
    static final double MAX_RAISE = (double) field(TYPE, null, "MAX_RAISE");
    static final double SMOOTHING = (double) field(TYPE, null, "SMOOTHING");
    private final Object value = construct(TYPE, new Class<?>[0]);

    double lambda(final double base) {
      return (double) invoke(TYPE, this.value, "lambda", new Class<?>[] { double.class }, base);
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
      return (double) invoke(TYPE, null, "raise", new Class<?>[] { double.class }, motion);
    }

    static int[] blurredLuma(
      final byte[] rgb,
      final int width,
      final int height,
      final Workers workers,
      final int[] sampled,
      final int[] blurred
    ) {
      return (int[]) invoke(
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
      return (double) invoke(
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
    long distortion();
    boolean predicted(int[] prediction, int size, int[] out);
    boolean solid(int color, int size, int[] out);
    boolean palette(byte[] record, int size, int[] out);
    boolean compact(int[] prediction, byte[] record, int quantizer, int size, int[] out);
    void predict(byte[] reference, int width, int height, int left, int top, int size, int motionX, int motionY, int[] out);
    void fit(float[] values, int size, float[] out);
    void cluster(int[] source, int size, int[] endpoints);
    void finishPalette(int[] source, int count, int[] endpoints, int[] colors, byte[] selectors);
    boolean finishPattern(int[] source, int size, int[] endpoints, int[] colors, byte[] selectors);
    int seeded(byte[] reference, int width, int height, int[] source, int left, int top, int size, int range, int[] seeds);
    void loadSource(byte[] image, int width, int height, int left, int top, int size, int[] source);
    void halve(int[] block, int size, int[] out);
    void residualTarget(int[] source, int[] prediction, int count, float[] target);

    default void forgetArrays() {}
  }

  static Kernels javaKernels() {
    final Class<?> type = nested("Kernels");
    final Object target = construct(nested("JavaKernels"), new Class<?>[0]);
    return (Kernels) Proxy.newProxyInstance(Kernels.class.getClassLoader(), new Class<?>[] { Kernels.class }, (_, method, arguments) ->
      call(type, target, method.getName(), method.getParameterTypes(), arguments == null ? new Object[0] : arguments)
    );
  }
}
