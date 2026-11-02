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
package me.brandonli.mcav.vnc;

import static me.brandonli.mcav.vnc.RfbSession.MINOR_VERSION_3_8;
import static me.brandonli.mcav.vnc.RfbSession.NONE;
import static me.brandonli.mcav.vnc.RfbSession.version;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import org.junit.jupiter.api.Tag;

// Fuzzes RfbGuard with what a hostile VNC server could send: after a handshake, or from the first byte, any bytes in
// any chunks. The guard passes them or refuses them with an IOException; any other failure, or a hang, is a crash. The
// client side is the one mcav's VNC client sends.
@Tag("fuzz")
final class RfbGuardFuzzTest {

  private static final int SIDE = 64;

  private static final int MAXIMUM_CHUNK = 512;

  @FuzzTest(maxDuration = "30s")
  void shouldCheckWhatAConnectedServerSends(final FuzzedDataProvider data) throws IOException {
    final RfbSession session = new RfbSession();
    session.handshake(MINOR_VERSION_3_8, NONE, SIDE, SIDE);
    this.feed(session, data);
  }

  @FuzzTest(maxDuration = "30s")
  void shouldCheckAHandshakeFromTheFirstByte(final FuzzedDataProvider data) {
    final RfbSession session = new RfbSession();
    final int minor = data.pickValue(new int[] { 3, 7, 8 });
    try {
      session.sendFromServer(data.consumeBytes(12));
      session.sendFromClient(version(minor));
      if (minor >= 7) {
        session.sendFromServer(data.consumeBytes(1 + data.consumeInt(0, 4)));
        session.sendFromClient(new byte[] { (byte) data.consumeInt(0, 255) });
      }
      session.sendFromClient(data.consumeBytes(data.consumeInt(0, 400)));
    } catch (final IOException exception) {
      return;
    }
    this.feed(session, data);
  }

  private void feed(final RfbSession session, final FuzzedDataProvider data) {
    try {
      while (data.remainingBytes() > 0) {
        session.sendFromServer(data.consumeBytes(data.consumeInt(1, MAXIMUM_CHUNK)));
      }
    } catch (final IOException exception) {
      // the one failure the guard may report
    }
  }
}
