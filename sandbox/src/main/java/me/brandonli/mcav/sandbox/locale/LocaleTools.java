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
package me.brandonli.mcav.sandbox.locale;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.checkerframework.checker.nullness.qual.Nullable;

public interface LocaleTools {
  TranslationManager MANAGER = new TranslationManager();

  static NullComponent<Sender> direct(final String key) {
    return () -> MANAGER.render(translatable(key));
  }

  static <T> UniComponent<Sender, T> direct(final String key, final @Nullable Function<T, String> function) {
    return arg -> MANAGER.render(translatable(key, createFinalText(arg, function)));
  }

  static <T, U> BiComponent<Sender, T, U> direct(
    final String key,
    final @Nullable Function<T, String> function1,
    final @Nullable Function<U, String> function2
  ) {
    return (arg1, arg2) -> MANAGER.render(translatable(key, createFinalText(arg1, function1), createFinalText(arg2, function2)));
  }

  static <T, U, V> TriComponent<Sender, T, U, V> direct(
    final String key,
    final @Nullable Function<T, String> function1,
    final @Nullable Function<U, String> function2,
    final @Nullable Function<V, String> function3
  ) {
    return (arg1, arg2, arg3) ->
      MANAGER.render(
        translatable(key, createFinalText(arg1, function1), createFinalText(arg2, function2), createFinalText(arg3, function3))
      );
  }

  static <T> Component createFinalText(final T argument, final @Nullable Function<T, String> function) {
    final String text = argument == null ? "" : argument.toString();
    return text(function == null ? text : function.apply(argument));
  }

  @FunctionalInterface
  interface NullComponent<S extends Sender> {
    Component build();
  }

  @FunctionalInterface
  interface UniComponent<S extends Sender, A0> {
    Component build(A0 arg0);
  }

  @FunctionalInterface
  interface BiComponent<S extends Sender, A0, A1> {
    Component build(A0 arg0, A1 arg1);
  }

  @FunctionalInterface
  interface TriComponent<S extends Sender, A0, A1, A2> {
    Component build(A0 arg0, A1 arg1, A2 arg2);
  }
}
