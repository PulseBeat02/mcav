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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import org.apache.logging.log4j.Level;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class Mcv2ShaderReportsTest {

  private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000061");

  private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000062");

  private static final byte[] NO_IRIS = { 1, 0, 0, 0 };

  private static final byte[] SHADERS_OFF = { 1, 1, 0, 0 };

  private static final byte[] SHADERS_ON = { 1, 1, 1, 0 };

  private static final byte[] SHADERS_DECODED = { 1, 1, 1, 1 };

  private static final byte[] SHADERS_UNKNOWN = { 1, 1, 2, 0 };

  private static final String MALFORMED_REPORT_MESSAGE = "A malformed MCV2 client report from Alice is being ignored.";

  private final AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 3 * Mcv2ShaderReports.INTERVAL_NANOSECONDS);

  private Mcv2ShaderReports reports;

  private Player alice;

  private Player bob;

  @BeforeEach
  void createReports() {
    this.reports = new Mcv2ShaderReports(this.clock::get);
    this.alice = this.player(ALICE, "Alice");
    this.bob = this.player(BOB, "Bob");
  }

  private Player player(final UUID uuid, final String name) {
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(uuid);
    when(player.getName()).thenReturn(name);
    return player;
  }

  private void send(final Player player, final byte[] message) {
    this.reports.onPluginMessageReceived(Mcv2ShaderReports.CHANNEL, player, message);
  }

  private void sendLater(final Player player, final byte[] message) {
    this.clock.addAndGet(Mcv2ShaderReports.INTERVAL_NANOSECONDS);
    this.send(player, message);
  }

  private List<String> messages(final LogCapture logs, final Level level) {
    return logs
      .getEvents()
      .stream()
      .filter(event -> event.getLevel().equals(level))
      .map(LogCapture.RecordedEvent::getMessage)
      .toList();
  }

  @Test
  void shouldBlockDecodingOnlyForAShaderPackInUseThatMcv2DoesNotDecodeUnder() {
    final boolean reportedWithoutTheMod = this.reports.hasReported(ALICE);
    assertFalse(reportedWithoutTheMod, "nothing is known of a player without the mod");

    final boolean blocksWithoutTheMod = this.reports.blocksDecoding(ALICE);
    assertFalse(blocksWithoutTheMod);

    try (final LogCapture logs = LogCapture.capture(Mcv2ShaderReports.class)) {
      this.send(this.alice, NO_IRIS);

      final boolean reportedWithoutIris = this.reports.hasReported(ALICE);
      assertTrue(reportedWithoutIris);

      final boolean blocksWithoutIris = this.reports.blocksDecoding(ALICE);
      assertFalse(blocksWithoutIris);

      this.sendLater(this.alice, NO_IRIS);
      this.sendLater(this.alice, SHADERS_OFF);

      final boolean blocksWithShadersOff = this.reports.blocksDecoding(ALICE);
      assertFalse(blocksWithShadersOff);

      this.sendLater(this.alice, SHADERS_ON);

      final boolean blocksWithShadersOn = this.reports.blocksDecoding(ALICE);
      assertTrue(blocksWithShadersOn);

      this.sendLater(this.alice, SHADERS_DECODED);

      final boolean blocksWithShadersDecoded = this.reports.blocksDecoding(ALICE);
      assertFalse(blocksWithShadersDecoded);

      this.sendLater(this.alice, SHADERS_UNKNOWN);

      final boolean blocksWithShadersUnknown = this.reports.blocksDecoding(ALICE);
      assertFalse(blocksWithShadersUnknown, "a shader pack Iris cannot tell leaves the screens as they were");

      final List<String> expectedDebugMessages = List.of(
        "The MCV2 client mod of Alice reports no Iris: MCV2 screens show them the video as a result.",
        "The MCV2 client mod of Alice reports Iris without a shader pack: MCV2 screens show them the video as a result.",
        "The MCV2 client mod of Alice reports Iris with a shader pack in use: MCV2 screens show them the dithered maps as a result.",
        "The MCV2 client mod of Alice reports Iris with a shader pack MCV2 decodes under: MCV2 screens show them the video as a result.",
        "The MCV2 client mod of Alice reports Iris, whose shader pack the mod cannot tell: MCV2 screens show them the video as a result."
      );
      final List<String> debugMessages = this.messages(logs, Level.DEBUG);
      assertEquals(expectedDebugMessages, debugMessages, "one line for each change, none for a report that repeats the last");
    }
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "",
      "01",
      "01 01 01",
      "01 01 01 00 00",
      "01 02 01 00",
      "01 ff 00 00",
      "01 01 01 02",
      "01 01 03 00",
      "01 01 ff 00",
      "01 00 01 00",
      "01 00 02 00",
    }
  )
  void shouldIgnoreAMalformedReportWithOneDebugLine(final String hexadecimal) {
    final byte[] message = HexFormat.ofDelimiter(" ").parseHex(hexadecimal);
    this.send(this.alice, SHADERS_ON);
    try (final LogCapture logs = LogCapture.capture(Mcv2ShaderReports.class)) {
      this.sendLater(this.alice, message);

      final List<String> expectedDebugMessages = List.of(MALFORMED_REPORT_MESSAGE);
      final List<String> debugMessages = this.messages(logs, Level.DEBUG);
      assertEquals(expectedDebugMessages, debugMessages);

      final List<String> informationMessages = this.messages(logs, Level.INFO);
      assertEquals(List.of(), informationMessages);
    }

    final boolean blocksDecoding = this.reports.blocksDecoding(ALICE);
    assertTrue(blocksDecoding, "an ignored report changes nothing");
  }

  @ParameterizedTest
  @ValueSource(bytes = { 0, 2, -1 })
  void shouldIgnoreAReportOfAnotherVersion(final byte version) {
    try (final LogCapture logs = LogCapture.capture(Mcv2ShaderReports.class)) {
      this.send(this.alice, new byte[] { version, 1, 1, 0 });

      final List<String> expectedDebugMessages = List.of(
        "An MCV2 client report of version %s from Alice is being ignored.".formatted(version)
      );
      final List<String> debugMessages = this.messages(logs, Level.DEBUG);
      assertEquals(expectedDebugMessages, debugMessages);
    }

    final boolean reported = this.reports.hasReported(ALICE);
    assertFalse(reported);
  }

  @Test
  void shouldIgnoreAReportLongerThanTheLongestUnread() {
    try (final LogCapture logs = LogCapture.capture(Mcv2ShaderReports.class)) {
      final byte[] longest = new byte[Mcv2ShaderReports.MAXIMUM_BYTES];
      longest[0] = Mcv2ShaderReports.VERSION;
      this.send(this.alice, longest);
      final byte[] longer = new byte[Mcv2ShaderReports.MAXIMUM_BYTES + 1];
      longer[0] = Mcv2ShaderReports.VERSION;
      for (int report = 0; report < Mcv2ShaderReports.BURST; report++) {
        this.send(this.alice, longer);
      }

      final List<String> expectedDebugMessages = List.of(
        MALFORMED_REPORT_MESSAGE,
        "Ignoring an MCV2 client report of 65 bytes from Alice: a report has at most 64 bytes.",
        "Ignoring an MCV2 client report of 65 bytes from Alice: a report has at most 64 bytes.",
        "Ignoring an MCV2 client report of 65 bytes from Alice: a report has at most 64 bytes.",
        "Ignoring an MCV2 client report of 65 bytes from Alice: a report has at most 64 bytes.",
        "Ignoring an MCV2 client report of 65 bytes from Alice: a report has at most 64 bytes."
      );
      final List<String> debugMessages = this.messages(logs, Level.DEBUG);
      assertEquals(expectedDebugMessages, debugMessages);
    }

    // a report too long to read takes nothing from the allowance, and one of the longest length takes one
    for (int report = 1; report < Mcv2ShaderReports.BURST; report++) {
      this.send(this.alice, SHADERS_ON);
    }

    final boolean blocksWithinTheBurst = this.reports.blocksDecoding(ALICE);
    assertTrue(blocksWithinTheBurst);

    this.send(this.alice, SHADERS_OFF);

    final boolean blocksPastTheBurst = this.reports.blocksDecoding(ALICE);
    assertTrue(blocksPastTheBurst, "the burst was used up");
  }

  @Test
  void shouldAllowABurstThenOneReportASecondToEachPlayer() {
    for (int report = 0; report < Mcv2ShaderReports.BURST; report++) {
      this.send(this.alice, report % 2 == 0 ? SHADERS_ON : SHADERS_OFF);
    }

    final boolean blocksAfterTheBurst = this.reports.blocksDecoding(ALICE);
    assertTrue(blocksAfterTheBurst, "a burst is read whole");

    try (final LogCapture logs = LogCapture.capture(Mcv2ShaderReports.class)) {
      this.send(this.alice, SHADERS_OFF);

      final boolean blocksPastTheBurst = this.reports.blocksDecoding(ALICE);
      assertTrue(blocksPastTheBurst);

      final List<String> expectedDebugMessages = List.of("Ignoring an MCV2 client report from Alice: more than one a second.");
      final List<String> debugMessages = this.messages(logs, Level.DEBUG);
      assertEquals(expectedDebugMessages, debugMessages);
    }

    this.send(this.bob, SHADERS_ON);

    final boolean bobBlocksDecoding = this.reports.blocksDecoding(BOB);
    assertTrue(bobBlocksDecoding, "the allowance is each player's own");

    this.clock.addAndGet(Mcv2ShaderReports.INTERVAL_NANOSECONDS - 1);
    this.send(this.alice, SHADERS_OFF);

    final boolean blocksBeforeTheRefill = this.reports.blocksDecoding(ALICE);
    assertTrue(blocksBeforeTheRefill, "the allowance refills a second after the report it made room for");

    this.clock.addAndGet(1);
    this.send(this.alice, SHADERS_OFF);

    final boolean blocksAfterTheRefill = this.reports.blocksDecoding(ALICE);
    assertFalse(blocksAfterTheRefill);

    this.send(this.alice, SHADERS_ON);

    final boolean blocksWithinTheSecond = this.reports.blocksDecoding(ALICE);
    assertFalse(blocksWithinTheSecond, "one report a second");

    // a player quiet for longer than a burst takes has a whole burst again, and no more, past the clock's wrap too
    this.clock.addAndGet(2 * Mcv2ShaderReports.BURST * Mcv2ShaderReports.INTERVAL_NANOSECONDS);
    for (int report = 0; report < Mcv2ShaderReports.BURST; report++) {
      this.send(this.alice, report % 2 == 0 ? SHADERS_ON : SHADERS_OFF);
    }

    final boolean blocksAfterTheQuietBurst = this.reports.blocksDecoding(ALICE);
    assertTrue(blocksAfterTheQuietBurst);

    this.send(this.alice, SHADERS_OFF);

    final boolean blocksPastTheQuietBurst = this.reports.blocksDecoding(ALICE);
    assertTrue(blocksPastTheQuietBurst, "a quiet time saves up no more than a burst");
  }

  @Test
  void shouldForgetTheReportAndTheAllowanceOfAPlayerWhoLeft() {
    for (int report = 0; report < Mcv2ShaderReports.BURST; report++) {
      this.send(this.alice, SHADERS_ON);
    }

    this.send(this.bob, SHADERS_ON);
    this.reports.forget(ALICE);

    final boolean aliceReported = this.reports.hasReported(ALICE);
    assertFalse(aliceReported);

    final boolean aliceBlocksDecoding = this.reports.blocksDecoding(ALICE);
    assertFalse(aliceBlocksDecoding);

    final boolean bobBlocksDecoding = this.reports.blocksDecoding(BOB);
    assertTrue(bobBlocksDecoding, "the others are kept");

    this.send(this.alice, SHADERS_ON);

    final boolean blocksAfterRejoining = this.reports.blocksDecoding(ALICE);
    assertTrue(blocksAfterRejoining, "a player who joins again starts with a whole burst");
  }
}
