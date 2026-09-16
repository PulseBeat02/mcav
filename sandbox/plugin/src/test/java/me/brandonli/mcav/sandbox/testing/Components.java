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
package me.brandonli.mcav.sandbox.testing;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.mockito.ArgumentCaptor;

/**
 * Reads the messages that mocked senders received.
 */
public final class Components {

  private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

  private Components() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Renders a component as plain text.
   *
   * @param component the component
   * @return the text without formatting
   */
  public static String plain(final Component component) {
    return PLAIN_TEXT.serialize(component);
  }

  /**
   * Gets the messages a mocked audience received with {@link Audience#sendMessage(Component)}, in order.
   *
   * @param audience the mocked audience, such as a command sender
   * @return the messages
   */
  public static List<Component> received(final Audience audience) {
    final ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
    verify(audience, atLeastOnce()).sendMessage(captor.capture());
    final List<Component> values = captor.getAllValues();
    return new ArrayList<>(values);
  }

  /**
   * Gets the messages a mocked audience received as plain text, in order.
   *
   * @param audience the mocked audience, such as a command sender
   * @return the messages without formatting
   */
  public static List<String> receivedText(final Audience audience) {
    final List<Component> messages = received(audience);
    final List<String> texts = new ArrayList<>();
    for (final Component message : messages) {
      final String text = plain(message);
      texts.add(text);
    }
    return texts;
  }
}
