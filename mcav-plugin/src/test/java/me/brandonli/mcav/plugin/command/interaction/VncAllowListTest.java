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
package me.brandonli.mcav.plugin.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import me.brandonli.mcav.plugin.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class VncAllowListTest {

  private static final String DESKTOP_HOST = "Desktop.Example";
  private static final int DESKTOP_PORT = 5901;
  private static final String DESKTOP_PASSWORD = "secret";
  private static final VncAllowList.Entry DESKTOP = VncAllowList.Entry.of(DESKTOP_HOST, DESKTOP_PORT, DESKTOP_PASSWORD);

  private static final String LOOPBACK_HOST = "::1";
  private static final int LOOPBACK_PORT = 5902;
  private static final VncAllowList.Entry LOOPBACK = VncAllowList.Entry.of(LOOPBACK_HOST, LOOPBACK_PORT, null);

  private static final VncAllowList LIST = VncAllowList.of(List.of(DESKTOP, LOOPBACK));

  private static final String DESKTOP_ADDRESS = "desktop.example:5901";
  private static final String LOOPBACK_ADDRESS = "[::1]:5902";

  private static final int LAST_TCP_PORT = 65_535;

  private static final String HOST_NAME = "desktop";

  @Test
  void shouldParseAHostAndAPort() {
    final VncAllowList.Target expected = new VncAllowList.Target("desktop.example", DESKTOP_PORT);
    final VncAllowList.Target parsed = VncAllowList.parse(DESKTOP_ADDRESS);
    assertEquals(expected, parsed);

    final VncAllowList.Target secondExpected = new VncAllowList.Target("127.0.0.1", 1);
    final VncAllowList.Target vncAllowListParsed = VncAllowList.parse("127.0.0.1:1");
    assertEquals(secondExpected, vncAllowListParsed);

    final VncAllowList.Target thirdExpected = new VncAllowList.Target("example", LAST_TCP_PORT);
    final VncAllowList.Target secondParsed = VncAllowList.parse("example:65535");
    assertEquals(thirdExpected, secondParsed);
  }

  @Test
  void shouldParseAnIpv6AddressInBrackets() {
    final VncAllowList.Target expected = new VncAllowList.Target(LOOPBACK_HOST, LOOPBACK_PORT);
    final VncAllowList.Target parsed = VncAllowList.parse(LOOPBACK_ADDRESS);
    assertEquals(expected, parsed);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "",
      "desktop",
      "desktop:",
      ":5901",
      "::1:5902",
      "[]:5901",
      "desktop:0",
      "desktop:65536",
      "desktop:59o1",
      "desktop:+5901",
      "desktop:123456",
      " :5901",
      "[desktop]:5901",
      "[[::1]]:5902",
      "desk top:5901",
      "[::1:5902",
      "desk]top:5901",
      "desk[top:5901",
    }
  )
  void shouldRefuseTextThatIsNotAHostAndAPort(final String text) {
    final VncAllowList.Target parsed = VncAllowList.parse(text);
    assertNull(parsed);
  }

  @Test
  void shouldFindAListedServerWhateverTheCaseOfItsHost() {
    final VncAllowList.Entry found = LIST.find("DESKTOP.example:5901");
    assertSame(DESKTOP, found);

    final VncAllowList.Entry listFound = LIST.find(LOOPBACK_ADDRESS);
    assertSame(LOOPBACK, listFound);
  }

  @ParameterizedTest
  @ValueSource(strings = { "desktop.example:5902", "other.example:5901", "desktop.example", "[::1]:5901" })
  void shouldFindNoServerThatIsNotListed(final String text) {
    final VncAllowList.Entry found = LIST.find(text);
    assertNull(found);
  }

  @Test
  void shouldAllowNoServerForAnEmptyList() {
    final VncAllowList.Entry found = VncAllowList.NONE.find(DESKTOP_ADDRESS);
    assertNull(found);
  }

  @Test
  void shouldFormatAServerAsACommandNamesIt() {
    final String text = VncAllowList.format(DESKTOP.getHost(), DESKTOP.getPort());
    assertEquals("Desktop.Example:5901", text);

    final String loopbackText = VncAllowList.format(LOOPBACK.getHost(), LOOPBACK.getPort());
    assertEquals(LOOPBACK_ADDRESS, loopbackText);
  }

  @Test
  void shouldFindAnIpv6AddressListedInBrackets() {
    final VncAllowList.Entry bracketed = VncAllowList.Entry.of("[::1]", LOOPBACK_PORT, null);

    final String text = bracketed.getHost();
    assertEquals(LOOPBACK_HOST, text);

    final VncAllowList.Entry found = VncAllowList.of(List.of(bracketed)).find(LOOPBACK_ADDRESS);
    assertSame(bracketed, found);

    final String bracketedText = VncAllowList.format(bracketed.getHost(), bracketed.getPort());
    assertEquals(LOOPBACK_ADDRESS, bracketedText);

    final VncAllowList.Entry unbalanced = VncAllowList.Entry.of("[::1", LOOPBACK_PORT, null);

    final String unbalancedText = unbalanced.getHost();
    assertEquals("[::1", unbalancedText, "only a host in both brackets loses them");
  }

  @Test
  void shouldRefuseAnEntryWithoutAHostOrWithoutATcpPort() {
    assertThrows(IllegalArgumentException.class, () -> VncAllowList.Entry.of(" ", DESKTOP_PORT, null));
    assertThrows(IllegalArgumentException.class, () -> VncAllowList.Entry.of("[]", DESKTOP_PORT, null));
    assertThrows(IllegalArgumentException.class, () -> VncAllowList.Entry.of(HOST_NAME, 0, null));
    assertThrows(IllegalArgumentException.class, () -> VncAllowList.Entry.of(HOST_NAME, 65_536, null));
  }

  @Test
  void shouldTakeAnEntryOnTheFirstOrTheLastTcpPort() {
    final int port = VncAllowList.Entry.of(HOST_NAME, 1, null).getPort();
    assertEquals(1, port);

    final int secondPort = VncAllowList.Entry.of(HOST_NAME, LAST_TCP_PORT, null).getPort();
    assertEquals(LAST_TCP_PORT, secondPort);
  }

  @Test
  void shouldKeepItsEntriesWhenTheListItWasGivenChanges() {
    final List<VncAllowList.Entry> entries = new ArrayList<>(List.of(DESKTOP));
    final VncAllowList list = VncAllowList.of(entries);
    entries.clear();

    final VncAllowList.Entry found = list.find(DESKTOP_ADDRESS);
    assertSame(DESKTOP, found);
  }

  @Test
  void shouldGiveItsEntriesInTheirOrderAndUnmodifiable() {
    final List<VncAllowList.Entry> entries = LIST.getEntries();
    final List<VncAllowList.Entry> expected = List.of(DESKTOP, LOOPBACK);
    assertEquals(expected, entries);
    assertThrows(UnsupportedOperationException.class, () -> entries.add(DESKTOP));
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final VncAllowList equalList = VncAllowList.of(List.of(DESKTOP, LOOPBACK));
    final VncAllowList otherEntries = VncAllowList.of(List.of(LOOPBACK, DESKTOP));
    EqualityAssertions.assertEqualityContract(LIST, equalList, otherEntries, VncAllowList.NONE);
  }

  @Test
  void shouldFollowTheEqualityContractOfAnEntry() {
    final VncAllowList.Entry equalEntry = VncAllowList.Entry.of(DESKTOP_HOST, DESKTOP_PORT, DESKTOP_PASSWORD);
    final VncAllowList.Entry otherHost = VncAllowList.Entry.of("Laptop.Example", DESKTOP_PORT, DESKTOP_PASSWORD);
    final VncAllowList.Entry otherPort = VncAllowList.Entry.of(DESKTOP_HOST, DESKTOP_PORT + 1, DESKTOP_PASSWORD);
    final VncAllowList.Entry otherPassword = VncAllowList.Entry.of(DESKTOP_HOST, DESKTOP_PORT, null);
    EqualityAssertions.assertEqualityContract(DESKTOP, equalEntry, otherHost, otherPort, otherPassword);
  }

  @Test
  void shouldHashTheEntries() {
    final int hash = LIST.hashCode();
    final int expected = Objects.hash(List.of(DESKTOP, LOOPBACK));
    assertEquals(expected, hash);
  }

  @Test
  void shouldHashTheHostThenThePortThenThePassword() {
    final int hash = DESKTOP.hashCode();
    final int expected = Objects.hash(DESKTOP_HOST, DESKTOP_PORT, DESKTOP_PASSWORD);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRenderAListWithItsEntries() {
    final String text = LIST.toString();
    assertEquals("VncAllowList{entries=[Entry{host=Desktop.Example, port=5901}, Entry{host=::1, port=5902}]}", text);
  }

  @Test
  void shouldRenderAnEntryWithoutItsPassword() {
    final String text = DESKTOP.toString();
    assertEquals("Entry{host=Desktop.Example, port=5901}", text);
  }

  @Test
  void shouldGiveThePasswordOnlyOfAServerThatHasOne() {
    final Optional<String> password = DESKTOP.getPassword();
    final Optional<String> expectedPassword = Optional.of(DESKTOP_PASSWORD);
    assertEquals(expectedPassword, password);

    final Optional<String> loopbackPassword = LOOPBACK.getPassword();
    assertEquals(Optional.empty(), loopbackPassword);
  }
}
