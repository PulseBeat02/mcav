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
package me.brandonli.mcav.sandbox.locale.minimessage;

import com.google.common.base.Preconditions;
import java.util.List;
import java.util.OptionalInt;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.Tag.Argument;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The MiniMessage tag {@code <arg:N>} of the message files: it inserts the N-th argument, counted from zero, of
 * the message being rendered.
 *
 * <p>For example, with the arguments {@code "Steve"} and {@code "5"}, the message
 * {@code <gold><arg:0> has <arg:1> maps</gold>} renders as "Steve has 5 maps" in gold. Server owners who edit the
 * files in the {@code locale} folder of the plugin can move these tags around or drop them, but must not use an
 * index the message does not receive.
 */
public final class ArgumentTag implements TagResolver {

  private static final String NAME = "arg";

  private final List<? extends ComponentLike> argumentComponents;

  /**
   * Creates the tag for the arguments of one message.
   *
   * @param argumentComponents the arguments of the translatable component being rendered, in order; the list is
   *                           used as is, not copied
   * @throws NullPointerException if the list is {@code null}
   */
  public ArgumentTag(final List<? extends ComponentLike> argumentComponents) {
    Preconditions.checkNotNull(argumentComponents, "Argument components must not be null");
    this.argumentComponents = argumentComponents;
  }

  /**
   * Resolves an {@code <arg:N>} tag to the N-th argument, inserted with the style of the surrounding text.
   *
   * @param name      the name of the tag, always {@code arg} because of {@link #has(String)}
   * @param arguments the arguments of the tag; the first one is the zero-based index of the message argument
   * @param context   the parsing context
   * @return a tag that inserts the argument
   * @throws ParsingException                 if the tag has no index
   * @throws java.util.NoSuchElementException if the index is not a whole number
   * @throws IndexOutOfBoundsException        if the message has no argument with the index
   */
  @Override
  public Tag resolve(final @NonNull String name, final @NonNull ArgumentQueue arguments, final @NonNull Context context)
    throws ParsingException {
    Preconditions.checkNotNull(arguments, "Arguments must not be null");
    final Argument indexArgument = arguments.pop();
    final OptionalInt parsedIndex = indexArgument.asInt();
    final int index = parsedIndex.orElseThrow();
    final ComponentLike argument = this.argumentComponents.get(index);
    return Tag.inserting(argument);
  }

  /**
   * Checks whether a tag is handled by this resolver, which is only the case for {@code arg}.
   *
   * @param name the name of the tag
   * @return true if the name is {@code arg}
   */
  @Override
  public boolean has(final @NonNull String name) {
    return NAME.equals(name);
  }
}
