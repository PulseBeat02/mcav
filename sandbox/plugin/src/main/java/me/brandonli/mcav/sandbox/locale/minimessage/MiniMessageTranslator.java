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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
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

/**
 * An Adventure translator whose messages are written in MiniMessage, such as {@code <gold>Image loaded!</gold>}.
 *
 * <p>The arguments of a translatable component are inserted where the message has {@code <arg:0>},
 * {@code <arg:1>}, and so on (see {@link ArgumentTag}). The special placeholder {@code $URL$} is replaced by the
 * plain text of the first argument before the message is parsed, so it also works inside tag arguments such as
 * {@code <click:open_url:'$URL$'>}, where {@code <arg:0>} cannot be used. Subclasses only decide where the message
 * text for a key comes from.
 */
public abstract class MiniMessageTranslator implements Translator {

  private static final PlainTextComponentSerializer PLAIN_TEXT_SERIALIZER = PlainTextComponentSerializer.plainText();
  private static final Collection<String> SPECIAL_PLACEHOLDERS = List.of("$URL$");

  private final MiniMessage miniMessage;

  /**
   * Creates a translator that parses messages with the default MiniMessage instance, which understands every
   * standard tag.
   */
  public MiniMessageTranslator() {
    this(MiniMessage.miniMessage());
  }

  /**
   * Creates a translator that parses messages with a specific MiniMessage instance, for example one limited to
   * some tags.
   *
   * @param miniMessage the parser of the messages
   * @throws NullPointerException if the parser is {@code null}
   */
  public MiniMessageTranslator(final MiniMessage miniMessage) {
    Preconditions.checkNotNull(miniMessage, "MiniMessage must not be null");
    this.miniMessage = miniMessage;
  }

  /**
   * Refuses to translate to a {@link MessageFormat}: the messages are MiniMessage, which only
   * {@link #translate(TranslatableComponent, Locale)} can render.
   *
   * @param key    the key of the message
   * @param locale the locale Adventure renders for
   * @return never returns
   * @throws UnsupportedOperationException always
   */
  @Override
  public @Nullable MessageFormat translate(final @NonNull String key, final @NonNull Locale locale) {
    throw new UnsupportedOperationException("MiniMessage messages are rendered as components, not message formats");
  }

  /**
   * Renders a translatable component: looks up the MiniMessage text of its key, replaces the special
   * placeholders, inserts its arguments at the {@code <arg:N>} tags, and appends its children.
   *
   * @param component the component to render
   * @param locale    the locale Adventure renders for, passed on to {@link #getMiniMessageString(String, Locale)}
   * @return the rendered component
   * @throws NullPointerException               if the component or the locale is {@code null}
   * @throws java.util.MissingResourceException if there is no message with the key of the component
   */
  @Override
  public Component translate(final @NonNull TranslatableComponent component, final @NonNull Locale locale) {
    Preconditions.checkNotNull(component, "Component must not be null");
    Preconditions.checkNotNull(locale, "Locale must not be null");
    final String key = component.key();
    final String nullableMessage = this.getMiniMessageString(key, locale);
    final String message = requireNonNull(nullableMessage);
    final String content = replaceSpecialPlaceholders(message, component);

    final List<? extends ComponentLike> arguments = component.arguments();
    final Component rendered = this.deserialize(content, arguments);
    final List<Component> children = component.children();
    return children.isEmpty() ? rendered : rendered.children(children);
  }

  private Component deserialize(final String content, final List<? extends ComponentLike> arguments) {
    if (arguments.isEmpty()) {
      return this.miniMessage.deserialize(content);
    }
    final ArgumentTag tag = new ArgumentTag(arguments);
    return this.miniMessage.deserialize(content, tag);
  }

  private static String replaceSpecialPlaceholders(final String message, final TranslatableComponent component) {
    final List<? extends ComponentLike> arguments = component.arguments();
    final Iterator<? extends ComponentLike> iterator = arguments.iterator();
    String replaced = message;
    for (final String placeholder : SPECIAL_PLACEHOLDERS) {
      if (!replaced.contains(placeholder)) {
        continue;
      }
      final ComponentLike argument = iterator.next();
      final Component argumentComponent = argument.asComponent();
      final String plainText = PLAIN_TEXT_SERIALIZER.serialize(argumentComponent);
      replaced = replaced.replace(placeholder, plainText);
    }
    return replaced;
  }

  /**
   * Gets the MiniMessage text of a message.
   *
   * @param key    the key of the message, such as {@code mcav.command.image.load}
   * @param locale the locale Adventure renders for; implementations may ignore it when they serve one language
   * @return the message text
   * @throws java.util.MissingResourceException if there is no message with the key
   */
  protected abstract String getMiniMessageString(final String key, final Locale locale);
}
