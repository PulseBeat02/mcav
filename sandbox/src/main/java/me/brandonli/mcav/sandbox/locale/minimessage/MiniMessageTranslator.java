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
package me.brandonli.mcav.sandbox.locale.minimessage;

import static java.util.Objects.requireNonNull;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.translation.Translator;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public abstract class MiniMessageTranslator implements Translator {

  private final MiniMessage miniMessage;

  public MiniMessageTranslator() {
    this(MiniMessage.miniMessage());
  }

  public MiniMessageTranslator(final MiniMessage miniMessage) {
    this.miniMessage = miniMessage;
  }

  @Override
  public @Nullable MessageFormat translate(final @NonNull String key, final @NonNull Locale locale) {
    throw new UnsupportedOperationException();
  }

  @Override
  public @Nullable Component translate(final TranslatableComponent component, final @NonNull Locale locale) {
    final String key = component.key();
    final String miniMessageString = requireNonNull(this.getMiniMessageString(key, locale));
    final List<? extends ComponentLike> args = component.arguments();
    final boolean empty = args.isEmpty();
    final MiniMessage parser = MiniMessage.miniMessage();
    final ArgumentTag tag = new ArgumentTag(args);
    final Component resultingComponent = empty ? parser.deserialize(miniMessageString) : parser.deserialize(miniMessageString, tag);
    final List<Component> children = component.children();
    return children.isEmpty() ? resultingComponent : resultingComponent.children(children);
  }

  protected abstract String getMiniMessageString(final String key, final Locale locale);
}
