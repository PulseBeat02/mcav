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

public final class ArgumentTag implements TagResolver {

  private static final String NAME = "arg";

  private final List<? extends ComponentLike> argumentComponents;

  public ArgumentTag(final List<? extends ComponentLike> argumentComponents) {
    this.argumentComponents = argumentComponents;
  }

  @Override
  public Tag resolve(final @NonNull String name, final ArgumentQueue arguments, final @NonNull Context ctx) throws ParsingException {
    final Argument pop = arguments.pop();
    final OptionalInt optional = pop.asInt();
    final int index = optional.orElseThrow();
    final ComponentLike like = this.argumentComponents.get(index);
    return Tag.inserting(like);
  }

  @Override
  public boolean has(final String name) {
    return name.equals(NAME);
  }
}
