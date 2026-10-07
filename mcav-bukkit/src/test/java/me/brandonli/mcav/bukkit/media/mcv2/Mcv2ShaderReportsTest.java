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

/**
 * Tests how the reports of the MCV2 client mod are read: only well-formed reports of version 1, at the rate allowed,
 * each changing only the state of the player who sent it.
 */
final class Mcv2ShaderReportsTest {

  private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000061");

  private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000062");

  private static final byte[] NO_IRIS = { 1, 0, 0, 0 };

  private static final byte[] SHADERS_OFF = { 1, 1, 0, 0 };

  private static final byte[] SHADERS_ON = { 1, 1, 1, 0 };

  private static final byte[] SHADERS_DECODED = { 1, 1, 1, 1 };

  private static final byte[] SHADERS_UNKNOWN = { 1, 1, 2, 0 };

  private final AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 3 * Mcv2ShaderReports.INTERVAL_NANOS);

  private Mcv2ShaderReports reports;

  private Player alice;

  private Player bob;

  @BeforeEach
  void createReports() {
    this.reports = new Mcv2ShaderReports(this.clock::get);
    this.alice = player(ALICE, "Alice");
    this.bob = player(BOB, "Bob");
  }

  private static Player player(final UUID uuid, final String name) {
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(uuid);
    when(player.getName()).thenReturn(name);
    return player;
  }

  private void send(final Player player, final byte[] message) {
    this.reports.onPluginMessageReceived(Mcv2ShaderReports.CHANNEL, player, message);
  }

  /** Sends a report a second after the last, which the rate always allows. */
  private void sendLater(final Player player, final byte[] message) {
    this.clock.addAndGet(Mcv2ShaderReports.INTERVAL_NANOS);
    this.send(player, message);
  }

  private static List<String> messages(final LogCapture logs, final Level level) {
    return logs
      .getEvents()
      .stream()
      .filter(event -> event.getLevel().equals(level))
      .map(LogCapture.RecordedEvent::getMessage)
      .toList();
  }

  @Test
  void onlyAShaderPackInUseThatMcv2DoesNotDecodeUnderBlocksDecoding() {
    assertFalse(this.reports.hasReported(ALICE), "nothing is known of a player without the mod");
    assertFalse(this.reports.blocksDecoding(ALICE));
    try (final LogCapture logs = LogCapture.capture(Mcv2ShaderReports.class)) {
      this.send(this.alice, NO_IRIS);
      assertTrue(this.reports.hasReported(ALICE));
      assertFalse(this.reports.blocksDecoding(ALICE));
      this.sendLater(this.alice, NO_IRIS);
      this.sendLater(this.alice, SHADERS_OFF);
      assertFalse(this.reports.blocksDecoding(ALICE));
      this.sendLater(this.alice, SHADERS_ON);
      assertTrue(this.reports.blocksDecoding(ALICE));
      this.sendLater(this.alice, SHADERS_DECODED);
      assertFalse(this.reports.blocksDecoding(ALICE));
      this.sendLater(this.alice, SHADERS_UNKNOWN);
      assertFalse(this.reports.blocksDecoding(ALICE), "a shader pack Iris cannot tell leaves the screens as they were");
      assertEquals(
        List.of(
          "The MCV2 client mod of Alice reports no Iris: MCV2 screens show them the video",
          "The MCV2 client mod of Alice reports Iris without a shader pack: MCV2 screens show them the video",
          "The MCV2 client mod of Alice reports Iris with a shader pack in use: MCV2 screens show them the dithered maps",
          "The MCV2 client mod of Alice reports Iris with a shader pack MCV2 decodes under: MCV2 screens show them the video",
          "The MCV2 client mod of Alice reports Iris, whose shader pack the mod cannot tell: MCV2 screens show them the video"
        ),
        messages(logs, Level.INFO),
        "one line for each change, none for a report that repeats the last"
      );
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
  void ignoresAMalformedReportWithOneDebugLine(final String hex) {
    final byte[] message = HexFormat.ofDelimiter(" ").parseHex(hex);
    this.send(this.alice, SHADERS_ON);
    try (final LogCapture logs = LogCapture.capture(Mcv2ShaderReports.class)) {
      this.sendLater(this.alice, message);
      assertEquals(List.of("Ignoring a malformed MCV2 client report from Alice"), messages(logs, Level.DEBUG));
      assertEquals(List.of(), messages(logs, Level.INFO));
    }
    assertTrue(this.reports.blocksDecoding(ALICE), "an ignored report changes nothing");
  }

  @ParameterizedTest
  @ValueSource(bytes = { 0, 2, -1 })
  void ignoresAReportOfAnotherVersion(final byte version) {
    try (final LogCapture logs = LogCapture.capture(Mcv2ShaderReports.class)) {
      this.send(this.alice, new byte[] { version, 1, 1, 0 });
      assertEquals(List.of("Ignoring an MCV2 client report of version " + version + " from Alice"), messages(logs, Level.DEBUG));
    }
    assertFalse(this.reports.hasReported(ALICE));
  }

  @Test
  void ignoresAReportLongerThanTheLongestUnread() {
    try (final LogCapture logs = LogCapture.capture(Mcv2ShaderReports.class)) {
      final byte[] longest = new byte[Mcv2ShaderReports.MAX_BYTES];
      longest[0] = Mcv2ShaderReports.VERSION;
      this.send(this.alice, longest);
      final byte[] longer = new byte[Mcv2ShaderReports.MAX_BYTES + 1];
      longer[0] = Mcv2ShaderReports.VERSION;
      for (int report = 0; report < Mcv2ShaderReports.BURST; report++) {
        this.send(this.alice, longer);
      }
      assertEquals(
        List.of(
          "Ignoring a malformed MCV2 client report from Alice",
          "Ignoring an MCV2 client report of 65 bytes from Alice: a report has at most 64",
          "Ignoring an MCV2 client report of 65 bytes from Alice: a report has at most 64",
          "Ignoring an MCV2 client report of 65 bytes from Alice: a report has at most 64",
          "Ignoring an MCV2 client report of 65 bytes from Alice: a report has at most 64",
          "Ignoring an MCV2 client report of 65 bytes from Alice: a report has at most 64"
        ),
        messages(logs, Level.DEBUG)
      );
    }
    // a report too long to read takes nothing from the allowance, and one of the longest length takes one
    for (int report = 1; report < Mcv2ShaderReports.BURST; report++) {
      this.send(this.alice, SHADERS_ON);
    }
    assertTrue(this.reports.blocksDecoding(ALICE));
    this.send(this.alice, SHADERS_OFF);
    assertTrue(this.reports.blocksDecoding(ALICE), "the burst was used up");
  }

  @Test
  void allowsABurstThenOneReportASecondToEachPlayer() {
    for (int report = 0; report < Mcv2ShaderReports.BURST; report++) {
      this.send(this.alice, report % 2 == 0 ? SHADERS_ON : SHADERS_OFF);
    }
    assertTrue(this.reports.blocksDecoding(ALICE), "a burst is read whole");
    try (final LogCapture logs = LogCapture.capture(Mcv2ShaderReports.class)) {
      this.send(this.alice, SHADERS_OFF);
      assertTrue(this.reports.blocksDecoding(ALICE));
      assertEquals(List.of("Ignoring an MCV2 client report from Alice: more than one a second"), messages(logs, Level.DEBUG));
    }
    this.send(this.bob, SHADERS_ON);
    assertTrue(this.reports.blocksDecoding(BOB), "the allowance is each player's own");

    this.clock.addAndGet(Mcv2ShaderReports.INTERVAL_NANOS - 1);
    this.send(this.alice, SHADERS_OFF);
    assertTrue(this.reports.blocksDecoding(ALICE), "the allowance refills a second after the report it made room for");
    this.clock.addAndGet(1);
    this.send(this.alice, SHADERS_OFF);
    assertFalse(this.reports.blocksDecoding(ALICE));
    this.send(this.alice, SHADERS_ON);
    assertFalse(this.reports.blocksDecoding(ALICE), "one report a second");

    // a player quiet for longer than a burst takes has a whole burst again, and no more, past the clock's wrap too
    this.clock.addAndGet(2 * Mcv2ShaderReports.BURST * Mcv2ShaderReports.INTERVAL_NANOS);
    for (int report = 0; report < Mcv2ShaderReports.BURST; report++) {
      this.send(this.alice, report % 2 == 0 ? SHADERS_ON : SHADERS_OFF);
    }
    assertTrue(this.reports.blocksDecoding(ALICE));
    this.send(this.alice, SHADERS_OFF);
    assertTrue(this.reports.blocksDecoding(ALICE), "a quiet time saves up no more than a burst");
  }

  @Test
  void forgetsTheReportAndTheAllowanceOfAPlayerWhoLeft() {
    for (int report = 0; report < Mcv2ShaderReports.BURST; report++) {
      this.send(this.alice, SHADERS_ON);
    }
    this.send(this.bob, SHADERS_ON);
    this.reports.forget(ALICE);
    assertFalse(this.reports.hasReported(ALICE));
    assertFalse(this.reports.blocksDecoding(ALICE));
    assertTrue(this.reports.blocksDecoding(BOB), "the others are kept");
    this.send(this.alice, SHADERS_ON);
    assertTrue(this.reports.blocksDecoding(ALICE), "a player who joins again starts with a whole burst");
  }
}
