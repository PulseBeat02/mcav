package me.brandonli.mcav.utils;

import static java.util.Objects.requireNonNull;

/**
 * Represents a function that accepts three arguments and produces a result.
 * This is the three-arity specialization of {@link java.util.function.Function}.
 *
 * @param <T> the type of the first argument
 * @param <U> the type of the second argument
 * @param <V> the type of the third argument
 * @param <R> the type of the result
 */
@FunctionalInterface
public interface TriFunction<T, U, V, R> {

  /**
   * Applies this function to the given arguments.
   *
   * @param t the first function argument
   * @param u the second function argument
   * @param v the third function argument
   * @return the function result
   */
  R apply(T t, U u, V v);

  /**
   * Returns a composed function that first applies this function to its input, and then applies
   * the {@code after} function to the result.
   *
   * @param <W> the type of output of the {@code after} function and of the composed function
   * @param after the function to apply after this function is applied
   * @return a composed function that first applies this function and then applies the {@code after} function
   * @throws NullPointerException if {@code after} is null
   */
  default <W> TriFunction<T, U, V, W> andThen(final java.util.function.Function<? super R, ? extends W> after) {
    requireNonNull(after);
    return (T t, U u, V v) -> after.apply(this.apply(t, u, v));
  }
}
