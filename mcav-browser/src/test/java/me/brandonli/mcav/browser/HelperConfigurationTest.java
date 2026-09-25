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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class HelperConfigurationTest {

  private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();
  private static final Path SOCKET = ROOT.resolve("tmp").resolve("s");
  private static final Path NATIVES = ROOT.resolve("natives");
  private static final Path PROFILE = ROOT.resolve("profile");

  private static byte[] token() {
    final byte[] token = new byte[HelperProtocol.TOKEN_BYTES];
    for (int index = 0; index < token.length; index++) {
      token[index] = (byte) (index * 7);
    }
    return token;
  }

  private static HelperConfiguration configuration(final boolean jit) {
    return new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, URI.create("https://example.com/page"), 640, 480, 2, 30, jit, false);
  }

  @Test
  void aConfigurationSurvivesTheLine() {
    final HelperConfiguration original = new HelperConfiguration(
      token(),
      SOCKET,
      NATIVES,
      PROFILE,
      URI.create("https://example.com/a?b=%20c"),
      640,
      480,
      3,
      45,
      true,
      true
    );
    final String line = original.toLine();
    assertFalse(line.contains("\n"));
    final HelperConfiguration read = HelperConfiguration.fromLine(line);
    assertArrayEquals(token(), read.getToken());
    assertEquals(SOCKET, read.getSocket());
    assertEquals(NATIVES, read.getNatives());
    assertEquals(PROFILE, read.getProfile());
    assertEquals(URI.create("https://example.com/a?b=%20c"), read.getUrl());
    assertEquals(640, read.getWidth());
    assertEquals(480, read.getHeight());
    assertEquals(3, read.getFrameInterval());
    assertEquals(45, read.getFrameRate());
    assertTrue(read.isJavaScriptJit());
    assertTrue(read.isPrivateNetworks());
    final HelperConfiguration defaults = HelperConfiguration.fromLine(configuration(false).toLine());
    assertFalse(defaults.isJavaScriptJit());
    assertFalse(defaults.isPrivateNetworks());
  }

  @Test
  void theTokenIsCopiedInAndOut() {
    final byte[] token = token();
    final HelperConfiguration configuration = new HelperConfiguration(
      token,
      SOCKET,
      NATIVES,
      PROFILE,
      URI.create("http://a.b/"),
      1,
      1,
      1,
      1,
      false,
      false
    );
    token[0] = 99;
    assertEquals(0, configuration.getToken()[0]);
    configuration.getToken()[1] = 99;
    assertEquals(7, configuration.getToken()[1]);
  }

  @Test
  void everyValueIsChecked() {
    final URI page = URI.create("https://example.com/");
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(new byte[1], SOCKET, NATIVES, PROFILE, page, 1, 1, 1, 1, false, false)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), Path.of("relative"), NATIVES, PROFILE, page, 1, 1, 1, 1, false, false)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, Path.of("relative"), PROFILE, page, 1, 1, 1, 1, false, false)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, Path.of("relative"), page, 1, 1, 1, 1, false, false)
    );
    final URI file = URI.create("file:///etc/passwd");
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, file, 1, 1, 1, 1, false, false)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 0, 1, 1, 1, false, false)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 4097, 1, 1, 1, false, false)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 0, 1, 1, false, false)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 4097, 1, 1, false, false)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 1, 0, 1, false, false)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 1, 1001, 1, false, false)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 1, 1, 0, false, false)
    );
    final IllegalArgumentException tooFast = assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 1, 1, 61, false, false)
    );
    assertEquals("The frame rate must be between 1 and 60 but was 61", tooFast.getMessage());
    // the largest values are accepted
    new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 4096, 4096, 1000, 60, false, false);
  }

  private static String[] parts(final HelperConfiguration configuration) {
    return configuration.toLine().split(":", -1);
  }

  private static String encode(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String withValue(final int index, final String value) {
    final String[] parts = parts(configuration(false));
    parts[index] = encode(value);
    return String.join(":", parts);
  }

  @Test
  void aLineWithTooFewOrTooManyValuesIsRefused() {
    final String[] parts = parts(configuration(false));
    final String missing = String.join(":", java.util.Arrays.copyOf(parts, parts.length - 1));
    final IllegalArgumentException tooFew = assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(missing));
    assertEquals("The configuration has 10 values instead of 11", tooFew.getMessage());
    final String extra = configuration(false).toLine() + ":" + encode("x");
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(extra));
  }

  @Test
  void aLineWithAnInvalidValueIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(9, "yes")));
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(10, "TRUE")));
    assertThrows(NumberFormatException.class, () -> HelperConfiguration.fromLine(withValue(5, "wide")));
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(0, "zz")));
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(4, "file:///etc/passwd")));
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine("not base64!:a:b:c:d:e:f:g:h:i:j"));
    final String tooLong = "A".repeat(64 * 1024 + 1);
    final IllegalArgumentException longLine = assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(tooLong));
    assertEquals("The configuration line is too long", longLine.getMessage());
  }
}
