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
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.Translator;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public abstract class MiniMessageTranslator implements Translator {

  private static final PlainTextComponentSerializer PLAIN_TEST_SERIALIZER = PlainTextComponentSerializer.plainText();
  private static final Collection<String> SPECIAL_PLACEHOLDERS = List.of("$URL$");

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
    final String content = this.checkIfSpecialString(miniMessageString, component);
    final List<? extends ComponentLike> args = component.arguments();
    final boolean empty = args.isEmpty();
    final ArgumentTag tag = new ArgumentTag(args);
    final Component resultingComponent = empty ? this.miniMessage.deserialize(content) : this.miniMessage.deserialize(content, tag);
    final List<Component> children = component.children();
    return children.isEmpty() ? resultingComponent : resultingComponent.children(children);
  }

  private String checkIfSpecialString(final String value, final TranslatableComponent component) {
    final List<? extends ComponentLike> args = component.arguments();
    final Iterator<? extends ComponentLike> iterator = args.iterator();
    String copy = value;
    for (final String placeholder : SPECIAL_PLACEHOLDERS) {
      if (!copy.contains(placeholder)) {
        continue;
      }
      final ComponentLike arg = iterator.next();
      final Component comp = arg.asComponent();
      final String name = PLAIN_TEST_SERIALIZER.serialize(comp);
      copy = copy.replace(placeholder, name);
    }
    return copy;
  }

  protected abstract String getMiniMessageString(final String key, final Locale locale);
}
