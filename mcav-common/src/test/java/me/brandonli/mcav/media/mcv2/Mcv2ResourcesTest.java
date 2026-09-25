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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

/**
 * Loading the profile's static data: a resource is read completely, checked against its length and SHA-256, and any
 * missing, unreadable or altered resource is refused.
 */
final class Mcv2ResourcesTest {

  private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2Resources.class);
  }

  @Test
  void hashesWithSha256() {
    assertEquals(EMPTY_SHA256, Mcv2Resources.sha256(new byte[0]));
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Mcv2Resources.sha256(new byte[] { 'a', 'b', 'c' }));
  }

  @Test
  void refusesAnUnknownDigest() {
    assertThrows(IllegalStateException.class, () -> Mcv2Resources.digest(new byte[0], "NOT-A-DIGEST"));
  }

  @Test
  void loadsAndChecksTheBooks() {
    final byte[] books = Mcv2Resources.load("residual_books.bin", ResidualBooks.SHA256, ResidualBooks.BYTES);
    assertArrayEquals(ResidualBooks.bytes(), books);
  }

  @Test
  void refusesAMissingResource() {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      Mcv2Resources.load("missing.bin", EMPTY_SHA256, 0)
    );
    assertEquals("Missing MCV2 resource missing.bin", exception.getMessage());
  }

  @Test
  void refusesTheWrongLengthOrChecksum() {
    final byte[] empty = new byte[0];
    assertSame(empty, Mcv2Resources.verify(empty, EMPTY_SHA256, 0, "empty"));
    assertThrows(IllegalStateException.class, () -> Mcv2Resources.verify(empty, EMPTY_SHA256, 1, "empty"));
    assertThrows(IllegalStateException.class, () -> Mcv2Resources.verify(new byte[1], EMPTY_SHA256, 1, "one"));
  }

  @Test
  void readsAStreamAndReportsItsFailure() {
    assertArrayEquals(new byte[] { 1, 2 }, Mcv2Resources.read(new ByteArrayInputStream(new byte[] { 1, 2 }), "two"));
    final InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken");
      }

      @Override
      public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        throw new IOException("broken");
      }
    };
    assertThrows(UncheckedIOException.class, () -> Mcv2Resources.read(broken, "broken"));
  }
}
