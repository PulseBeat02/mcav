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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class HelperConfigurationTest {

  private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();
  private static final Path SOCKET = ROOT.resolve("tmp").resolve("s");
  private static final Path NATIVES = ROOT.resolve("natives");
  private static final Path PROFILE = ROOT.resolve("profile");
  private static final Path SERVER = ROOT.resolve("server");

  private static final Path RELATIVE_PATH = Path.of("relative");
  private static final Path RELATIVE_SERVER = Path.of("server");

  private static final Pattern FIELD_SEPARATOR = Pattern.compile(":");

  private static byte[] token() {
    final byte[] token = new byte[HelperProtocol.TOKEN_BYTES];
    for (int index = 0; index < token.length; index++) {
      token[index] = (byte) (index * 7);
    }
    return token;
  }

  private static HelperConfiguration configuration(final boolean allowsJit) {
    return new HelperConfiguration(
      token(),
      SOCKET,
      NATIVES,
      PROFILE,
      URI.create("https://example.com/page"),
      640,
      480,
      2,
      30,
      allowsJit,
      false,
      false,
      false,
      SERVER,
      List.of()
    );
  }

  @Test
  void shouldKeepTheLongestAddressInCharactersOfThreeBytesThroughTheLine() {
    final String start = "https://example.com/";
    final URI longest = URI.create(start + "\u20ac".repeat(BrowserSource.MAXIMUM_ADDRESS_LENGTH - start.length()));
    final Path path = NATIVES.resolve("p".repeat(255)).resolve("q".repeat(255));
    final HelperConfiguration original = new HelperConfiguration(
      token(),
      path,
      path,
      path,
      longest,
      640,
      480,
      3,
      45,
      true,
      true,
      false,
      true,
      path,
      List.of()
    );
    final HelperConfiguration read = HelperConfiguration.fromLine(original.toLine());
    final URI url = read.getUrl();
    assertEquals(longest, url);
  }

  @Test
  void shouldKeepAConfigurationThroughTheLine() {
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
      true,
      true,
      true,
      SERVER,
      List.of("mc.example.com", "203.0.113.5", "2001:db8::1")
    );
    final String line = original.toLine();
    final boolean breaksTheLine = line.contains("\n");
    assertFalse(breaksTheLine);
    final HelperConfiguration read = HelperConfiguration.fromLine(line);
    final byte[] keptToken = read.getToken();
    assertArrayEquals(token(), keptToken);
    final Path socket = read.getSocket();
    assertEquals(SOCKET, socket);
    final Path natives = read.getNatives();
    assertEquals(NATIVES, natives);
    final Path profile = read.getProfile();
    assertEquals(PROFILE, profile);
    final URI url = read.getUrl();
    assertEquals(URI.create("https://example.com/a?b=%20c"), url);
    final int width = read.getWidth();
    assertEquals(640, width);
    final int height = read.getHeight();
    assertEquals(480, height);
    final int frameInterval = read.getFrameInterval();
    assertEquals(3, frameInterval);
    final int frameRate = read.getFrameRate();
    assertEquals(45, frameRate);
    final boolean keptJavaScriptJit = read.isJavaScriptJit();
    assertTrue(keptJavaScriptJit);
    final boolean keptPrivateNetworks = read.isPrivateNetworks();
    assertTrue(keptPrivateNetworks);
    final boolean keptAutoplay = read.isAutoplay();
    assertTrue(keptAutoplay);
    final boolean keptConfined = read.isConfined();
    assertTrue(keptConfined);
    final Path serverFolder = read.getServerFolder();
    assertEquals(SERVER, serverFolder);
    final List<String> keptRefusedHosts = read.getRefusedHosts();
    assertEquals(List.of("mc.example.com", "203.0.113.5", "2001:db8::1"), keptRefusedHosts);
    final HelperConfiguration defaults = HelperConfiguration.fromLine(configuration(false).toLine());
    final boolean defaultJavaScriptJit = defaults.isJavaScriptJit();
    assertFalse(defaultJavaScriptJit);
    final boolean defaultPrivateNetworks = defaults.isPrivateNetworks();
    assertFalse(defaultPrivateNetworks);
    final boolean defaultAutoplay = defaults.isAutoplay();
    assertFalse(defaultAutoplay);
    final boolean defaultConfined = defaults.isConfined();
    assertFalse(defaultConfined);
    final List<String> defaultRefusedHosts = defaults.getRefusedHosts();
    assertEquals(List.of(), defaultRefusedHosts);
  }

  @Test
  void shouldCopyTheTokenInAndOut() {
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
      false,
      false,
      false,
      SERVER,
      List.of()
    );
    token[0] = 99;
    final byte firstTokenByte = configuration.getToken()[0];
    assertEquals(0, firstTokenByte);
    configuration.getToken()[1] = 99;
    final byte secondTokenByte = configuration.getToken()[1];
    assertEquals(7, secondTokenByte);
  }

  @Test
  void shouldCheckEveryValue() {
    final URI page = URI.create("https://example.com/");
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(new byte[1], SOCKET, NATIVES, PROFILE, page, 1, 1, 1, 1, false, false, false, false, SERVER, List.of())
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), RELATIVE_PATH, NATIVES, PROFILE, page, 1, 1, 1, 1, false, false, false, false, SERVER, List.of())
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, RELATIVE_PATH, PROFILE, page, 1, 1, 1, 1, false, false, false, false, SERVER, List.of())
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, RELATIVE_PATH, page, 1, 1, 1, 1, false, false, false, false, SERVER, List.of())
    );
    final URI file = URI.create("file:///etc/passwd");
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, file, 1, 1, 1, 1, false, false, false, false, SERVER, List.of())
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 0, 1, 1, 1, false, false, false, false, SERVER, List.of())
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 4097, 1, 1, 1, false, false, false, false, SERVER, List.of())
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 0, 1, 1, false, false, false, false, SERVER, List.of())
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 4097, 1, 1, false, false, false, false, SERVER, List.of())
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 1, 0, 1, false, false, false, false, SERVER, List.of())
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 1, 1001, 1, false, false, false, false, SERVER, List.of())
    );
    assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 1, 1, 0, false, false, false, false, SERVER, List.of())
    );
    final IllegalArgumentException tooFast = assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 1, 1, 61, false, false, false, false, SERVER, List.of())
    );
    final String tooFastMessage = tooFast.getMessage();
    assertEquals("The frame rate 61 must be between 1 and 60!", tooFastMessage);
    final IllegalArgumentException relativeServer = assertThrows(IllegalArgumentException.class, () ->
      new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 1, 1, 1, false, false, false, true, RELATIVE_SERVER, List.of())
    );
    final String relativeServerMessage = relativeServer.getMessage();
    assertEquals("The server folder path server must be absolute!", relativeServerMessage);
    for (final String host : List.of("", "two,hosts", "a host")) {
      final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
        new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 1, 1, 1, 1, false, false, false, true, SERVER, List.of(host))
      );
      final String refusedMessage = refused.getMessage();
      assertEquals("Host %s is not a host name or address!".formatted(host), refusedMessage);
    }
    new HelperConfiguration(token(), SOCKET, NATIVES, PROFILE, page, 4096, 4096, 1000, 60, false, false, false, false, SERVER, List.of());
  }

  private static String[] parts(final HelperConfiguration configuration) {
    return FIELD_SEPARATOR.split(configuration.toLine(), -1);
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
  void shouldRefuseALineWithTooFewOrTooManyValues() {
    final String[] parts = parts(configuration(false));
    final String missing = String.join(":", Arrays.copyOf(parts, parts.length - 1));
    final IllegalArgumentException tooFew = assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(missing));
    final String message = tooFew.getMessage();
    assertEquals("The configuration has 14 values instead of 15 values!", message);
    final String extra = configuration(false).toLine() + ":" + encode("x");
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(extra));
  }

  @Test
  void shouldRefuseALineWithAnInvalidValue() {
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(9, "yes")));
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(10, "TRUE")));
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(12, "on")));
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(13, "relative")));
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(14, "mc.example.com,,10.0.0.1")));
    assertThrows(NumberFormatException.class, () -> HelperConfiguration.fromLine(withValue(5, "wide")));
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(0, "zz")));
    assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(withValue(4, "file:///etc/passwd")));
    final String[] invalidEncoding = parts(configuration(false));
    invalidEncoding[3] = "!" + invalidEncoding[3];
    final IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class, () ->
      HelperConfiguration.fromLine(String.join(":", invalidEncoding))
    );
    final String malformedMessage = malformed.getMessage();
    assertEquals("Illegal base64 character 21", malformedMessage);
    final String tooLong = "A".repeat(1024 * 1024 + 1);
    final IllegalArgumentException longLine = assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(tooLong));
    final String longLineMessage = longLine.getMessage();
    assertEquals("The configuration line is too long!", longLineMessage);
  }

  @Test
  void shouldReadALineOfTheLongestLengthAndThenJudgeItByItsValues() {
    final String longest = "x".repeat(1024 * 1024);
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> HelperConfiguration.fromLine(longest));
    final boolean countsTheValues = failure.getMessage().startsWith("The configuration has 1 values instead of ");
    assertTrue(countsTheValues, failure.getMessage());
    final IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class, () ->
      HelperConfiguration.fromLine(longest + "x")
    );
    final String message = tooLong.getMessage();
    assertEquals("The configuration line is too long!", message);
  }
}
