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
package me.brandonli.mcav.plugin.testing;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class UtilityClassAssertionsTest {

  private static final String REFUSAL_MESSAGE = "Utility class cannot be instantiated!";

  @ParameterizedTest
  @ValueSource(classes = { NotFinal.class, TwoConstructors.class, VisibleConstructor.class, Instantiable.class, WrongRefusal.class })
  void shouldRejectAClassThatIsNotFinalWithOnePrivateConstructorThatRefuses(final Class<?> candidate) {
    assertThrows(AssertionError.class, () -> UtilityClassAssertions.assertNotInstantiable(candidate));
  }

  // not final on purpose: the assertion under test must reject it
  private static class NotFinal {

    private NotFinal() {
      throw new UnsupportedOperationException(REFUSAL_MESSAGE);
    }
  }

  private static final class TwoConstructors {

    private TwoConstructors() {
      this(REFUSAL_MESSAGE);
    }

    private TwoConstructors(final String message) {
      throw new UnsupportedOperationException(message);
    }
  }

  private static final class VisibleConstructor {

    VisibleConstructor() {
      throw new UnsupportedOperationException(REFUSAL_MESSAGE);
    }
  }

  private static final class Instantiable {

    private Instantiable() {}
  }

  private static final class WrongRefusal {

    private WrongRefusal() {
      throw new IllegalStateException(REFUSAL_MESSAGE);
    }
  }
}
