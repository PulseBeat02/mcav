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
package me.brandonli.mcav.sandbox.locale;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

import com.google.common.base.Preconditions;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Builds the messages of the plugin from the keys of the message file of the configured language.
 *
 * <p>Every message the plugin sends is declared once, in {@link Message}, with one of the {@code direct} methods.
 * The methods do not look the message up right away: they return a small builder whose {@code build} method renders
 * the message, with its arguments, each time it is sent. The message text is MiniMessage, such as
 * {@code <gold>Image loaded!</gold>}, and arguments appear in it as {@code <arg:0>}, {@code <arg:1>} and so on, or as
 * the special placeholder {@code $URL$} in text and {@code open_url} click targets. Other tag parameters do
 * not support this placeholder; see {@link me.brandonli.mcav.sandbox.locale.minimessage.MiniMessageTranslator}.
 */
public interface LocaleTools {
  /**
   * The messages of the language set by the {@code language} option of {@code config.yml}. They are loaded from the
   * data folder of the plugin the first time a message is built, so the configuration must be read before that;
   * changes to the message file take effect after a restart.
   */
  TranslationManager MANAGER = new TranslationManager();

  /**
   * Declares a message without arguments.
   *
   * @param key the key of the message in the message file, such as {@code mcav.command.image.load}
   * @return a builder that renders the message each time it is called
   * @throws NullPointerException if the key is {@code null}
   */
  static NullComponent direct(final String key) {
    Preconditions.checkNotNull(key, "Key must not be null");
    return () -> {
      final TranslatableComponent component = translatable(key);
      return MANAGER.render(component);
    };
  }

  /**
   * Declares a message with one argument, inserted where the message has {@code <arg:0>}.
   *
   * @param key      the key of the message in the message file
   * @param function turns the argument into the inserted text, or {@code null} to use its {@code toString()}
   * @param <T>      the type of the argument
   * @return a builder that renders the message with an argument each time it is called
   * @throws NullPointerException if the key is {@code null}
   */
  static <T> UniComponent<T> direct(final String key, final @Nullable Function<T, String> function) {
    Preconditions.checkNotNull(key, "Key must not be null");
    return argument -> {
      final Component text = createFinalText(argument, function);
      final TranslatableComponent component = translatable(key, text);
      return MANAGER.render(component);
    };
  }

  /**
   * Declares a message with two arguments, inserted where the message has {@code <arg:0>} and {@code <arg:1>}.
   *
   * @param key            the key of the message in the message file
   * @param firstFunction  turns the first argument into the inserted text, or {@code null} to use its
   *                       {@code toString()}
   * @param secondFunction turns the second argument into the inserted text, or {@code null} to use its
   *                       {@code toString()}
   * @param <T>            the type of the first argument
   * @param <U>            the type of the second argument
   * @return a builder that renders the message with two arguments each time it is called
   * @throws NullPointerException if the key is {@code null}
   */
  static <T, U> BiComponent<T, U> direct(
    final String key,
    final @Nullable Function<T, String> firstFunction,
    final @Nullable Function<U, String> secondFunction
  ) {
    Preconditions.checkNotNull(key, "Key must not be null");
    return (firstArgument, secondArgument) -> {
      final Component firstText = createFinalText(firstArgument, firstFunction);
      final Component secondText = createFinalText(secondArgument, secondFunction);
      final TranslatableComponent component = translatable(key, firstText, secondText);
      return MANAGER.render(component);
    };
  }

  /**
   * Declares a message with three arguments, inserted where the message has {@code <arg:0>}, {@code <arg:1>}, and
   * {@code <arg:2>}.
   *
   * @param key            the key of the message in the message file
   * @param firstFunction  turns the first argument into the inserted text, or {@code null} to use its
   *                       {@code toString()}
   * @param secondFunction turns the second argument into the inserted text, or {@code null} to use its
   *                       {@code toString()}
   * @param thirdFunction  turns the third argument into the inserted text, or {@code null} to use its
   *                       {@code toString()}
   * @param <T>            the type of the first argument
   * @param <U>            the type of the second argument
   * @param <V>            the type of the third argument
   * @return a builder that renders the message with three arguments each time it is called
   * @throws NullPointerException if the key is {@code null}
   */
  static <T, U, V> TriComponent<T, U, V> direct(
    final String key,
    final @Nullable Function<T, String> firstFunction,
    final @Nullable Function<U, String> secondFunction,
    final @Nullable Function<V, String> thirdFunction
  ) {
    Preconditions.checkNotNull(key, "Key must not be null");
    return (firstArgument, secondArgument, thirdArgument) -> {
      final Component firstText = createFinalText(firstArgument, firstFunction);
      final Component secondText = createFinalText(secondArgument, secondFunction);
      final Component thirdText = createFinalText(thirdArgument, thirdFunction);
      final TranslatableComponent component = translatable(key, firstText, secondText, thirdText);
      return MANAGER.render(component);
    };
  }

  /**
   * Turns a message argument into the plain text inserted into the message.
   *
   * @param argument the argument, which may be {@code null}
   * @param function turns the argument into text, and then receives {@code null} arguments too; or {@code null}
   *                 to use the {@code toString()} of the argument, with empty text for a {@code null} argument
   * @param <T>      the type of the argument
   * @return the text component, without any style of its own so it takes the style of the surrounding message
   */
  static <T> Component createFinalText(final T argument, final @Nullable Function<T, String> function) {
    if (function != null) {
      final String converted = function.apply(argument);
      return text(converted);
    }

    final String plain = argument == null ? "" : argument.toString();
    return text(plain);
  }

  /**
   * A message without arguments.
   */
  @FunctionalInterface
  interface NullComponent {
    /**
     * Renders the message in the configured language.
     *
     * @return the message, ready to send
     * @throws java.util.MissingResourceException if the message file has no message with the key
     */
    Component build();
  }

  /**
   * A message with one argument.
   *
   * @param <A0> the type of the argument
   */
  @FunctionalInterface
  interface UniComponent<A0> {
    /**
     * Renders the message in the configured language.
     *
     * @param argument the argument inserted at {@code <arg:0>}
     * @return the message, ready to send
     * @throws java.util.MissingResourceException if the message file has no message with the key
     */
    Component build(A0 argument);
  }

  /**
   * A message with two arguments.
   *
   * @param <A0> the type of the first argument
   * @param <A1> the type of the second argument
   */
  @FunctionalInterface
  interface BiComponent<A0, A1> {
    /**
     * Renders the message in the configured language.
     *
     * @param firstArgument  the argument inserted at {@code <arg:0>}
     * @param secondArgument the argument inserted at {@code <arg:1>}
     * @return the message, ready to send
     * @throws java.util.MissingResourceException if the message file has no message with the key
     */
    Component build(A0 firstArgument, A1 secondArgument);
  }

  /**
   * A message with three arguments.
   *
   * @param <A0> the type of the first argument
   * @param <A1> the type of the second argument
   * @param <A2> the type of the third argument
   */
  @FunctionalInterface
  interface TriComponent<A0, A1, A2> {
    /**
     * Renders the message in the configured language.
     *
     * @param firstArgument  the argument inserted at {@code <arg:0>}
     * @param secondArgument the argument inserted at {@code <arg:1>}
     * @param thirdArgument  the argument inserted at {@code <arg:2>}
     * @return the message, ready to send
     * @throws java.util.MissingResourceException if the message file has no message with the key
     */
    Component build(A0 firstArgument, A1 secondArgument, A2 thirdArgument);
  }
}
