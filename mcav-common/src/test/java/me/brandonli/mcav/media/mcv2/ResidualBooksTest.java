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
package me.brandonli.mcav.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/** The residual books: the published bytes, and the VQ and product-book views over them. */
final class ResidualBooksTest {

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(ResidualBooks.class);
  }

  @Test
  void holdsThePublishedBytes() {
    final byte[] books = ResidualBooks.bytes();
    assertEquals(ResidualBooks.BYTES, books.length);
    assertEquals(ResidualBooks.SHA256, Mcv2Resources.sha256(books));
    assertNotSame(books, ResidualBooks.bytes());
  }

  @Test
  void viewsTheVectorAndProductBooks() {
    final byte[] books = ResidualBooks.bytes();
    assertEquals(books[0], ResidualBooks.vq(0, 0));
    assertEquals(books[63 * 16 + 15], ResidualBooks.vq(63, 15));
    assertEquals(books[1024], ResidualBooks.pq(0, 0, 0));
    assertEquals(books[1024 + 512 + 63 * 8 + 7], ResidualBooks.pq(1, 63, 7));
  }
}
